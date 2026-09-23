package ai.minum.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageTree;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDObjectReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotation;
import org.apache.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink;
import org.apache.pdfbox.pdmodel.interactive.action.PDActionURI;
import org.apache.pdfbox.pdmodel.interactive.form.PDButton;
import org.apache.pdfbox.pdmodel.interactive.form.PDChoice;
import org.apache.pdfbox.pdmodel.interactive.form.PDField;
import org.apache.pdfbox.pdmodel.interactive.form.PDNonTerminalField;
import org.apache.pdfbox.pdmodel.interactive.form.PDSignatureField;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;
import org.apache.pdfbox.util.Matrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

public class PDFExtract implements Extractor {
    // Below 20 PostScript points (about 7 mm) OCR cannot recover useful document text reliably.
    // This also removes the narrow monochrome strips emitted by diagram-heavy PDF producers.
    private static final float MIN_MEANINGFUL_IMAGE_POINTS = 20.0f;
    private static final int MAX_ANNOTATIONS_PER_PAGE = 1024;
    private static final int MAX_ANNOTATION_FIELD_CHARACTERS = 32 * 1024;
    private static final int MAX_ANNOTATION_URI_CHARACTERS = 4096;
    private static final int MAX_ANNOTATION_TOTAL_CHARACTERS = 1024 * 1024;
    private static final int MAX_ACCESSIBILITY_DESCRIPTIONS_PER_PAGE = 1024;
    private static final int MAX_ACCESSIBILITY_DESCRIPTION_CHARACTERS = 32 * 1024;
    private static final int MAX_ACCESSIBILITY_TOTAL_CHARACTERS = 1024 * 1024;
    private static final Logger logger = LoggerFactory.getLogger(PDFExtract.class);

    @Override
    public boolean support(String mimeType) {
        return "pdf".equals(mimeType) || "application/pdf".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        // PDFBox keeps byte-array inputs in memory for the document lifetime. A file-backed source
        // lets its bounded random-access cache serve large PDFs without retaining the whole input.
        Path source = Files.createTempFile("mika-pdf-", ".pdf");
        try {
            Files.copy(stream, source, StandardCopyOption.REPLACE_EXISTING);
            return extractDocument(config, source);
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException cleanupError) {
                logger.warn("Failed to delete temporary PDF file: {}", source, cleanupError);
                source.toFile().deleteOnExit();
            }
        }
    }

    private ExtractResult extractDocument(ExtractConfig config, Path source) throws Exception {
        ExtractResult result = ExtractResult.of();
        try (PDDocument doc = loadDocument(source, config)) {
            StructureContent structureContent = structureContent(doc);
            Map<Integer, List<FormValue>> formValues = formValues(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                PDPage page = doc.getPage(i);
                Map<Integer, String> pageActualText = structureContent.actualText().getOrDefault(
                        page.getCOSObject(), Map.of());
                // Keep content-stream order first. Tagged and multi-column PDFs commonly encode
                // their intended reading order there, while coordinate sorting interleaves columns.
                ExtractedPageText pageText = extractPageText(doc, page, false, pageActualText);
                String text = pageText.text();
                if (isPredominantlyRightToLeft(text) || isFragmentedExtraction(text)) {
                    // Older Arabic/Hebrew PDFs often store glyphs in visual order. PDFBox's
                    // position sorter applies bidi normalization. It also repairs PDFs whose
                    // transformed text matrices make content-stream extraction nearly character-wise.
                    ExtractedPageText positionSorted = extractPageText(doc, page, true, pageActualText);
                    if (isPredominantlyRightToLeft(text)
                            || hasMateriallyBetterLineStructure(text, positionSorted.text())) {
                        pageText = positionSorted;
                        text = pageText.text();
                    }
                }
                PageImages images = new PageImages(page);
                images.processPage(page);
                result.setHasImage(result.hasImage() || !images.placements.isEmpty());
                List<PositionedImage> positionedImages = processPageImages(config, result, images.placements, i);
                StringBuilder content = new StringBuilder(markdownWithPositionedImages(pageText, positionedImages));
                String pageFormValues = formValuesMarkdown(formValues.getOrDefault(i, List.of()));
                if (!pageFormValues.isBlank()) {
                    if (!content.isEmpty()) {
                        content.append("\n\n");
                    }
                    content.append(pageFormValues);
                }
                String pageAnnotations = annotationsMarkdown(page, text);
                if (!pageAnnotations.isBlank()) {
                    if (!content.isEmpty()) {
                        content.append("\n\n");
                    }
                    content.append(pageAnnotations);
                }
                String pageAlternatives = accessibilityDescriptionsMarkdown(
                        structureContent.alternativeText().getOrDefault(
                                page.getCOSObject(), List.of()), text);
                if (!pageAlternatives.isBlank()) {
                    if (!content.isEmpty()) {
                        content.append("\n\n");
                    }
                    content.append(pageAlternatives);
                }
                result.addPage((long) i, content.toString());
            }
        }
        return result;
    }

    private static PDDocument loadDocument(Path pdf, ExtractConfig config) throws IOException {
        byte[] keyStore = config.pdfKeyStore();
        if (keyStore != null) {
            return Loader.loadPDF(pdf.toFile(), config.pdfKeyStorePassword(), new ByteArrayInputStream(keyStore),
                    config.pdfKeyAlias());
        }
        return config.pdfPassword().isEmpty()
                ? Loader.loadPDF(pdf.toFile()) : Loader.loadPDF(pdf.toFile(), config.pdfPassword());
    }

    private List<PositionedImage> processPageImages(ExtractConfig config, ExtractResult result,
                                                     List<ImagePlacement> placements, int pageIndex)
            throws Exception {
        if (placements.isEmpty()) {
            return List.of();
        }
        if (!config.ocr() && !config.uploadImage()) {
            return placements.stream()
                    .map(placement -> new PositionedImage(
                            placement.yFromTop(), Markdown.image("", "")))
                    .toList();
        }
        Map<COSBase, ProcessedImage> processed = new IdentityHashMap<>();
        Set<COSBase> skipped = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<COSBase> emitted = Collections.newSetFromMap(new IdentityHashMap<>());
        List<PositionedImage> positioned = new ArrayList<>();
        for (ImagePlacement placement : placements) {
            COSBase identity = placement.image().getCOSObject();
            if (!processed.containsKey(identity) && !skipped.contains(identity)) {
                if (!config.canHandleImage()) {
                    result.addWarning("Image count limit reached; some images were skipped");
                    skipped.add(identity);
                } else {
                    ImageResult imageResult;
                    try {
                        if (placement.image() instanceof PDImageXObject xObject) {
                            imageResult = toImageResult(xObject);
                        } else {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            if (!ImageIO.write(placement.image().getImage(), "png", bytes)) {
                                throw new IOException("No PNG writer is available");
                            }
                            imageResult = ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG);
                        }
                    } catch (IOException | RuntimeException error) {
                        logger.warn("Skip undecodable PDF image: page={}", pageIndex + 1, error);
                        result.addWarning("Cannot decode image on page " + (pageIndex + 1));
                        skipped.add(identity);
                        imageResult = null;
                    }
                    if (imageResult != null) {
                        // Backend failures remain errors, so callers can retry OCR/upload.
                        ExtractedImage content = extractImageContent(config, imageResult, result, true);
                        processed.put(identity, new ProcessedImage(
                                content.firstPlacement(), content.repeatedPlacement()));
                    }
                }
            }
            ProcessedImage image = processed.get(identity);
            String markdown = image == null ? "" : emitted.add(identity)
                    ? image.firstPlacement() : image.repeatedPlacement();
            positioned.add(new PositionedImage(placement.yFromTop(), markdown.isBlank()
                    ? Markdown.image("", "") : markdown));
        }
        return positioned;
    }

    static String markdownWithPositionedImages(ExtractedPageText page, List<PositionedImage> images) {
        if (images.isEmpty()) {
            return Markdown.fromText(page.text());
        }
        String[] lines = page.text().split("\\n", -1);
        List<Integer> nonBlankLines = new ArrayList<>();
        for (int index = 0; index < lines.length; index++) {
            if (!lines[index].isBlank()) {
                nonBlankLines.add(index);
            }
        }
        int positionedLineCount = Math.min(nonBlankLines.size(), page.lineYFromTop().size());
        Map<Integer, List<String>> beforeLine = new LinkedHashMap<>();
        List<String> trailing = new ArrayList<>();
        int tokenIndex = 0;
        Map<String, String> replacements = new LinkedHashMap<>();
        for (PositionedImage image : images) {
            String token = "MIKAPDFIMAGETOKEN" + tokenIndex++;
            replacements.put(token, image.markdown());
            int target = -1;
            if (Float.isFinite(image.yFromTop())) {
                for (int index = 0; index < positionedLineCount; index++) {
                    if (page.lineYFromTop().get(index) > image.yFromTop()) {
                        target = nonBlankLines.get(index);
                        break;
                    }
                }
            }
            if (target < 0) {
                trailing.add(token);
            } else {
                beforeLine.computeIfAbsent(target, ignored -> new ArrayList<>()).add(token);
            }
        }
        StringBuilder positionedText = new StringBuilder(page.text().length() + images.size() * 32);
        for (int index = 0; index < lines.length; index++) {
            List<String> markers = beforeLine.get(index);
            if (markers != null) {
                for (String marker : markers) {
                    appendBlock(positionedText, marker);
                }
            }
            if (!positionedText.isEmpty() && positionedText.charAt(positionedText.length() - 1) != '\n') {
                positionedText.append('\n');
            }
            positionedText.append(lines[index]);
        }
        for (String marker : trailing) {
            appendBlock(positionedText, marker);
        }
        String markdown = Markdown.fromText(positionedText.toString());
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            markdown = markdown.replace(replacement.getKey(), replacement.getValue());
        }
        return markdown;
    }

    private static void appendBlock(StringBuilder content, String block) {
        if (!content.isEmpty() && content.charAt(content.length() - 1) != '\n') {
            content.append('\n');
        }
        if (content.length() >= 2 && content.charAt(content.length() - 2) != '\n') {
            content.append('\n');
        }
        content.append(block).append("\n\n");
    }

    private static Map<Integer, List<FormValue>> formValues(PDDocument document) {
        var acroForm = document.getDocumentCatalog().getAcroForm();
        if (acroForm == null) {
            return Map.of();
        }
        Map<COSDictionary, Integer> pageNumbers = new IdentityHashMap<>();
        Map<COSDictionary, Integer> annotationPages = new IdentityHashMap<>();
        try {
            for (int pageNumber = 0; pageNumber < document.getNumberOfPages(); pageNumber++) {
                PDPage page = document.getPage(pageNumber);
                pageNumbers.put(page.getCOSObject(), pageNumber);
                for (PDAnnotation annotation : page.getAnnotations()) {
                    annotationPages.put(annotation.getCOSObject(), pageNumber);
                }
            }
        } catch (IOException | RuntimeException malformedAnnotations) {
            logger.warn("Cannot map all PDF form widgets to physical pages", malformedAnnotations);
        }

        Map<Integer, List<FormValue>> values = new LinkedHashMap<>();
        try {
            for (PDField field : acroForm.getFieldTree()) {
                if (field instanceof PDNonTerminalField || field instanceof PDSignatureField) {
                    continue;
                }
                String value = formValue(field);
                if (value.isBlank()) {
                    continue;
                }
                String name = firstNonBlank(field.getAlternateFieldName(), field.getPartialName(),
                        field.getFullyQualifiedName(), "Field");
                Set<Integer> fieldPages = new LinkedHashSet<>();
                for (var widget : field.getWidgets()) {
                    if (widget.isHidden() || widget.isInvisible() || widget.isNoView()) {
                        continue;
                    }
                    Integer widgetPage = widget.getPage() == null
                            ? annotationPages.get(widget.getCOSObject())
                            : pageNumbers.get(widget.getPage().getCOSObject());
                    if (widgetPage != null) {
                        fieldPages.add(widgetPage);
                    }
                }
                if (fieldPages.isEmpty()) {
                    continue;
                }
                FormValue formValue = new FormValue(normalizeFormText(name).replace('\n', ' '), value);
                for (Integer pageNumber : fieldPages) {
                    List<FormValue> pageValues = values.computeIfAbsent(pageNumber, ignored -> new ArrayList<>());
                    if (!pageValues.contains(formValue)) {
                        pageValues.add(formValue);
                    }
                }
            }
        } catch (RuntimeException malformedForm) {
            // A malformed optional form tree must not discard the page text that was already readable.
            logger.warn("Cannot extract all PDF form values", malformedForm);
        }
        return values;
    }

    private static String formValue(PDField field) {
        String value = field.getValueAsString();
        if (value == null) {
            return "";
        }
        if (field instanceof PDButton && (value.equalsIgnoreCase("Off") || value.equalsIgnoreCase("/Off"))) {
            return "";
        }
        if (field instanceof PDChoice choice) {
            List<String> displayValues = choice.getOptionsDisplayValues();
            List<String> exportValues = choice.getOptionsExportValues();
            StringJoiner selected = new StringJoiner(", ");
            for (Integer index : choice.getSelectedOptionsIndex()) {
                if (index != null && index >= 0 && index < displayValues.size()) {
                    selected.add(displayValues.get(index));
                }
            }
            if (selected.length() == 0) {
                for (String selectedValue : choice.getValue()) {
                    int index = exportValues.indexOf(selectedValue);
                    selected.add(index >= 0 && index < displayValues.size()
                            ? displayValues.get(index) : selectedValue);
                }
            }
            if (selected.length() > 0) {
                value = selected.toString();
            }
        }
        return normalizeFormText(value);
    }

    private static String normalizeFormText(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        StringBuilder clean = new StringBuilder(value.length());
        boolean pendingSpace = false;
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (codePoint == '\r' || codePoint == '\n' || codePoint == 0x2028 || codePoint == 0x2029) {
                while (!clean.isEmpty() && clean.charAt(clean.length() - 1) == ' ') {
                    clean.setLength(clean.length() - 1);
                }
                if (!clean.isEmpty() && clean.charAt(clean.length() - 1) != '\n') {
                    clean.append('\n');
                }
                pendingSpace = false;
            } else if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                    || Character.getType(codePoint) == Character.SPACE_SEPARATOR) {
                pendingSpace = !clean.isEmpty() && clean.charAt(clean.length() - 1) != '\n';
            } else {
                if (pendingSpace) {
                    clean.append(' ');
                }
                clean.appendCodePoint(codePoint);
                pendingSpace = false;
            }
        }
        return clean.toString().strip();
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String formValuesMarkdown(List<FormValue> values) {
        if (values.isEmpty()) {
            return "";
        }
        StringJoiner markdown = new StringJoiner("\n", "### Form fields\n\n", "");
        for (FormValue value : values) {
            String body = Markdown.fromText(value.value()).replace("\n", "\n  ");
            markdown.add("- " + Markdown.fromText(value.name()) + ": " + body);
        }
        return markdown.toString();
    }

    private record FormValue(String name, String value) {
    }

    private static String annotationsMarkdown(PDPage page, String pageText) {
        List<String> annotations = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String searchablePageText = searchableText(pageText);
        int totalCharacters = 0;
        int inspectedAnnotations = 0;
        try {
            for (PDAnnotation annotation : page.getAnnotations()) {
                if (inspectedAnnotations++ >= MAX_ANNOTATIONS_PER_PAGE) {
                    break;
                }
                if (annotation.isHidden() || annotation.isInvisible() || annotation.isNoView()
                        || "Widget".equals(annotation.getSubtype())
                        || "Popup".equals(annotation.getSubtype())) {
                    continue;
                }
                String body = limitText(normalizeFormText(
                        pdfString(annotation.getCOSObject(), COSName.CONTENTS)),
                        MAX_ANNOTATION_FIELD_CHARACTERS);
                String searchableBody = searchableText(body);
                if (searchableBody.isBlank() || searchablePageText.contains(searchableBody)) {
                    body = "";
                }
                String author = limitText(normalizeFormText(
                        pdfString(annotation.getCOSObject(), COSName.T)).replace('\n', ' '),
                        MAX_ANNOTATION_FIELD_CHARACTERS);
                String subject = limitText(normalizeFormText(
                        pdfString(annotation.getCOSObject(), COSName.SUBJ)).replace('\n', ' '),
                        MAX_ANNOTATION_FIELD_CHARACTERS);
                String uri = annotation instanceof PDAnnotationLink link && link.getAction() instanceof PDActionURI action
                        ? safeAnnotationUri(action.getURI()) : "";
                String uriWithoutScheme = uri.replaceFirst("(?i)^(?:https?://|mailto:)", "");
                if (!uri.isBlank() && searchablePageText.contains(searchableText(uriWithoutScheme))) {
                    uri = "";
                }
                if (body.isBlank() && subject.isBlank() && uri.isBlank()) {
                    continue;
                }
                String signature = searchableText(author + " " + subject + " " + body + " " + uri);
                if (signature.isBlank() || !seen.add(signature)) {
                    continue;
                }
                String label = String.join(" — ", List.of(author, subject).stream()
                        .filter(value -> !value.isBlank()).toList());
                StringBuilder item = new StringBuilder("- ");
                if (!label.isBlank()) {
                    item.append("**").append(Markdown.fromText(label)).append(":**");
                }
                if (!body.isBlank()) {
                    if (!label.isBlank()) {
                        item.append(' ');
                    }
                    item.append(Markdown.fromText(body).replace("\n", "\n  "));
                }
                if (!uri.isBlank()) {
                    if (!label.isBlank() || !body.isBlank()) {
                        item.append(' ');
                    }
                    item.append('<').append(uri).append('>');
                }
                String rendered = item.toString();
                if (totalCharacters + rendered.length() > MAX_ANNOTATION_TOTAL_CHARACTERS) {
                    break;
                }
                annotations.add(rendered);
                totalCharacters += rendered.length();
            }
        } catch (IOException | RuntimeException malformedAnnotations) {
            logger.warn("Cannot extract all PDF annotations", malformedAnnotations);
        }
        return annotations.isEmpty() ? ""
                : "### Annotations\n\n" + String.join("\n", annotations);
    }

    private static String accessibilityDescriptionsMarkdown(List<String> descriptions, String pageText) {
        if (descriptions.isEmpty()) {
            return "";
        }
        String searchablePageText = searchableText(pageText);
        List<String> values = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        int totalCharacters = 0;
        int inspectedDescriptions = 0;
        for (String description : descriptions) {
            if (inspectedDescriptions++ >= MAX_ACCESSIBILITY_DESCRIPTIONS_PER_PAGE) {
                break;
            }
            String normalized = limitText(normalizeFormText(description),
                    MAX_ACCESSIBILITY_DESCRIPTION_CHARACTERS);
            String searchable = searchableText(normalized);
            if (!normalized.isBlank() && !searchable.isBlank() && !searchablePageText.contains(searchable)
                    && seen.add(searchable)) {
                String rendered = "- " + Markdown.fromText(normalized).replace("\n", "\n  ");
                if (totalCharacters + rendered.length() > MAX_ACCESSIBILITY_TOTAL_CHARACTERS) {
                    break;
                }
                values.add(rendered);
                totalCharacters += rendered.length();
            }
        }
        return values.isEmpty() ? ""
                : "### Accessibility descriptions\n\n" + String.join("\n", values);
    }

    private static String searchableText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        StringBuilder normalized = new StringBuilder(text.length());
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isLetterOrDigit(codePoint)) {
                normalized.appendCodePoint(Character.toLowerCase(codePoint));
            }
        }
        return normalized.toString();
    }

    private static String pdfString(COSDictionary dictionary, COSName key) {
        COSBase value = dictionary.getDictionaryObject(key);
        if (!(value instanceof COSString string)) {
            return null;
        }
        byte[] bytes = string.getBytes();
        // PDF 2.0 permits UTF-8 text strings with an EF BB BF byte-order marker. PDFBox 3.0.4
        // decodes these annotation strings as PDFDocEncoding, producing mojibake.
        if (bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB
                && bytes[2] == (byte) 0xBF) {
            return new String(bytes, 3, bytes.length - 3, StandardCharsets.UTF_8);
        }
        return string.getString();
    }

    private static String safeAnnotationUri(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String uri = value.strip();
        if (uri.length() > MAX_ANNOTATION_URI_CHARACTERS) {
            return "";
        }
        for (int offset = 0; offset < uri.length();) {
            int codePoint = uri.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (Character.isISOControl(codePoint) || Character.isWhitespace(codePoint)
                    || codePoint == '<' || codePoint == '>') {
                return "";
            }
        }
        if (uri.regionMatches(true, 0, "www.", 0, 4)) {
            uri = "https://" + uri;
        }
        try {
            URI parsed = URI.create(uri);
            String scheme = parsed.getScheme();
            if (scheme == null || !(scheme.equalsIgnoreCase("http")
                    || scheme.equalsIgnoreCase("https") || scheme.equalsIgnoreCase("mailto"))) {
                return "";
            }
            if ((scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))
                    && !isPlausibleWebHost(parsed.getHost())) {
                return "";
            }
            if (scheme.equalsIgnoreCase("mailto")
                    && !parsed.getSchemeSpecificPart().matches("[^@]+@[^@]+\\.[A-Za-z]{2,63}")) {
                return "";
            }
            return uri;
        } catch (IllegalArgumentException invalidUri) {
            return "";
        }
    }

    private static boolean isPlausibleWebHost(String host) {
        if (host == null || host.isBlank()) {
            return false;
        }
        if (host.startsWith("[") && host.endsWith("]")) {
            return true;
        }
        if (host.matches("(?:\\d{1,3}\\.){3}\\d{1,3}")) {
            return true;
        }
        int lastDot = host.lastIndexOf('.');
        return lastDot > 0 && host.substring(lastDot + 1).matches("[A-Za-z]{2,63}");
    }

    private static String limitText(String value, int maxCharacters) {
        if (value == null || value.isEmpty() || value.codePointCount(0, value.length()) <= maxCharacters) {
            return value == null ? "" : value;
        }
        int end = value.offsetByCodePoints(0, maxCharacters - 1);
        return value.substring(0, end).stripTrailing() + "…";
    }

    static ExtractedPageText extractPageText(PDDocument document, PDPage page,
                                             boolean sortByPosition,
                                             Map<Integer, String> structureActualText)
            throws IOException {
        StructureActualTextStripper reader = new StructureActualTextStripper(structureActualText, page);
        reader.setSortByPosition(sortByPosition);
        return new ExtractedPageText(normalizeExtractedUnicode(reader.getText(document)),
                List.copyOf(reader.lineYFromTop));
    }

    static String normalizeExtractedUnicode(String text) {
        StringBuilder normalized = new StringBuilder(text.length());
        for (int index = 0; index < text.length();) {
            char character = text.charAt(index);
            if (Character.isHighSurrogate(character)) {
                if (index + 1 < text.length() && Character.isLowSurrogate(text.charAt(index + 1))) {
                    normalized.append(character).append(text.charAt(index + 1));
                    index += 2;
                    continue;
                }

                // PDFBox can place a combining mark between the two UTF-16 code units of a
                // supplementary character (PDFBOX-5747). Rejoin the pair and retain the marks
                // after the completed code point so the result can always be encoded as UTF-8.
                int lowSurrogate = index + 1;
                while (lowSurrogate < text.length() && isCombiningMark(text.charAt(lowSurrogate))) {
                    lowSurrogate++;
                }
                if (lowSurrogate < text.length()
                        && Character.isLowSurrogate(text.charAt(lowSurrogate))) {
                    normalized.append(character).append(text.charAt(lowSurrogate));
                    normalized.append(text, index + 1, lowSurrogate);
                    index = lowSurrogate + 1;
                    continue;
                }
                normalized.append('\uFFFD');
            } else if (Character.isLowSurrogate(character)) {
                normalized.append('\uFFFD');
            } else {
                normalized.append(character);
            }
            index++;
        }
        return normalized.toString();
    }

    private static boolean isCombiningMark(char character) {
        int type = Character.getType(character);
        return type == Character.NON_SPACING_MARK
                || type == Character.COMBINING_SPACING_MARK
                || type == Character.ENCLOSING_MARK;
    }

    static boolean isPredominantlyRightToLeft(String text) {
        int letters = 0;
        int rightToLeftLetters = 0;
        for (int offset = 0; offset < text.length();) {
            int codePoint = text.codePointAt(offset);
            offset += Character.charCount(codePoint);
            if (!Character.isLetter(codePoint)) {
                continue;
            }
            letters++;
            byte direction = Character.getDirectionality(codePoint);
            if (direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT
                    || direction == Character.DIRECTIONALITY_RIGHT_TO_LEFT_ARABIC) {
                rightToLeftLetters++;
            }
        }
        return rightToLeftLetters >= 8 && rightToLeftLetters * 2 >= letters;
    }

    static boolean isFragmentedExtraction(String text) {
        return fragmentationScore(text) >= 0.65;
    }

    private static double fragmentationScore(String text) {
        int lines = nonBlankLineCount(text);
        int shortLines = 0;
        for (String line : text.split("\\R")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                continue;
            }
            int length = trimmed.codePointCount(0, trimmed.length());
            if (length <= 3) {
                shortLines++;
            }
        }
        if (lines < 20) {
            return 0;
        }
        return (double) shortLines / lines;
    }

    private static boolean hasMateriallyBetterLineStructure(String contentOrder, String positionOrder) {
        int contentLines = nonBlankLineCount(contentOrder);
        int positionLines = nonBlankLineCount(positionOrder);
        return fragmentationScore(positionOrder) < fragmentationScore(contentOrder)
                && contentLines >= 20
                && positionLines > 0
                && contentLines >= positionLines * 3L;
    }

    private static int nonBlankLineCount(String text) {
        int lines = 0;
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                lines++;
            }
        }
        return lines;
    }

    private static StructureContent structureContent(PDDocument document) {
        PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
        if (root == null) {
            return StructureContent.empty();
        }
        Map<COSDictionary, Map<Integer, String>> actualText = new IdentityHashMap<>();
        Map<COSDictionary, List<String>> alternativeText = new IdentityHashMap<>();
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            collectStructureContent(root, null, null, null, actualText, alternativeText, visited);
            return new StructureContent(actualText, alternativeText);
        } catch (RuntimeException malformedStructure) {
            // A malformed optional structure tree must not prevent extraction of the page streams.
            logger.warn("Ignore malformed PDF structure tree while reading replacement text", malformedStructure);
            return StructureContent.empty();
        }
    }

    private static void collectStructureContent(PDStructureNode node, PDPage inheritedPage,
                                                ActualTextGroup inheritedActualText,
                                                AlternativeTextGroup inheritedAlternativeText,
                                                Map<COSDictionary, Map<Integer, String>> actualText,
                                                Map<COSDictionary, List<String>> alternativeText,
                                                Set<COSDictionary> visited) {
        if (!visited.add(node.getCOSObject())) {
            return;
        }
        PDPage page = inheritedPage;
        ActualTextGroup actualTextGroup = inheritedActualText;
        AlternativeTextGroup alternativeTextGroup = inheritedAlternativeText;
        if (node instanceof PDStructureElement element) {
            if (element.getPage() != null) {
                page = element.getPage();
            }
            if (element.getActualText() != null) {
                actualTextGroup = new ActualTextGroup(element.getActualText());
            }
            if (isAlternativeDescriptionElement(element) && element.getAlternateDescription() != null) {
                alternativeTextGroup = new AlternativeTextGroup(element.getAlternateDescription());
            }
            if (page != null) {
                addAlternativeText(alternativeText, page, alternativeTextGroup);
            }
        }
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureNode child) {
                collectStructureContent(child, page, actualTextGroup, alternativeTextGroup,
                        actualText, alternativeText, visited);
            } else if (kid instanceof Integer mcid) {
                addActualText(actualText, page, mcid, actualTextGroup);
                addAlternativeText(alternativeText, page, alternativeTextGroup);
            } else if (kid instanceof PDMarkedContentReference reference) {
                PDPage referencePage = reference.getPage() == null ? page : reference.getPage();
                addActualText(actualText, referencePage, reference.getMCID(), actualTextGroup);
                addAlternativeText(alternativeText, referencePage, alternativeTextGroup);
            } else if (kid instanceof PDObjectReference reference) {
                PDPage referencePage = reference.getPage() == null ? page : reference.getPage();
                addAlternativeText(alternativeText, referencePage, alternativeTextGroup);
            }
        }
    }

    private static boolean isAlternativeDescriptionElement(PDStructureElement element) {
        return "Figure".equals(element.getStructureType()) || "Formula".equals(element.getStructureType());
    }

    private static void addActualText(Map<COSDictionary, Map<Integer, String>> result, PDPage page,
                                      int mcid, ActualTextGroup group) {
        if (page == null || mcid < 0 || group == null) {
            return;
        }
        Map<Integer, String> pageValues = result.computeIfAbsent(
                page.getCOSObject(), ignored -> new LinkedHashMap<>());
        if (!pageValues.containsKey(mcid)) {
            pageValues.put(mcid, group.take());
        }
    }

    private static final class ActualTextGroup {
        private final String text;
        private boolean emitted;

        private ActualTextGroup(String text) {
            this.text = text.replace("\u00ad", "");
        }

        private String take() {
            if (emitted) {
                return "";
            }
            emitted = true;
            return text;
        }
    }

    private static void addAlternativeText(Map<COSDictionary, List<String>> result, PDPage page,
                                           AlternativeTextGroup group) {
        if (page == null || group == null) {
            return;
        }
        String text = group.take();
        if (!text.isBlank()) {
            List<String> values = result.computeIfAbsent(page.getCOSObject(), ignored -> new ArrayList<>());
            if (values.size() < MAX_ACCESSIBILITY_DESCRIPTIONS_PER_PAGE) {
                values.add(text);
            }
        }
    }

    private static final class AlternativeTextGroup {
        private final String text;
        private boolean emitted;

        private AlternativeTextGroup(String text) {
            this.text = text;
        }

        private String take() {
            if (emitted) {
                return "";
            }
            emitted = true;
            return text;
        }
    }

    private record StructureContent(Map<COSDictionary, Map<Integer, String>> actualText,
                                    Map<COSDictionary, List<String>> alternativeText) {
        private static StructureContent empty() {
            return new StructureContent(Map.of(), Map.of());
        }
    }

    record ExtractedPageText(String text, List<Float> lineYFromTop) {
    }

    record PositionedImage(float yFromTop, String markdown) {
    }

    /** Adds /ActualText stored on structure elements to PDFBox's marked-content handling. */
    private static final class StructureActualTextStripper extends PDFTextStripper {
        private final Map<Integer, String> structureActualText;
        private final PDPage targetPage;
        private final List<Float> lineYFromTop = new ArrayList<>();
        private boolean beginningOfLine = true;

        private StructureActualTextStripper(Map<Integer, String> structureActualText, PDPage page) {
            this.structureActualText = structureActualText;
            this.targetPage = page;
        }

        @Override
        protected void processPages(PDPageTree pages) throws IOException {
            // PDFTextStripper 3.0.8 walks the complete page tree even when start/end page are set.
            // Mika already invokes one stripper per page, so process only that page directly.
            processPage(targetPage);
        }

        @Override
        public void beginMarkedContentSequence(COSName tag, COSDictionary properties) {
            COSDictionary effectiveProperties = properties;
            if (properties != null && properties.getString(COSName.ACTUAL_TEXT) == null) {
                int mcid = properties.getInt(COSName.MCID, -1);
                if (structureActualText.containsKey(mcid)) {
                    effectiveProperties = new COSDictionary(properties);
                    effectiveProperties.setString(COSName.ACTUAL_TEXT, structureActualText.get(mcid));
                }
            }
            super.beginMarkedContentSequence(tag, effectiveProperties);
        }

        @Override
        protected void writeString(String text, List<TextPosition> textPositions) throws IOException {
            if (beginningOfLine && !text.isBlank() && !textPositions.isEmpty()) {
                lineYFromTop.add(textPositions.getFirst().getYDirAdj());
                beginningOfLine = false;
            }
            super.writeString(separateOverlappingRuns(text, textPositions));
        }

        @Override
        protected void writeLineSeparator() throws IOException {
            super.writeLineSeparator();
            beginningOfLine = true;
        }

        private static String separateOverlappingRuns(String text, List<TextPosition> positions) {
            if (text.isBlank() || positions.size() < 2) {
                return text;
            }
            StringBuilder repaired = new StringBuilder(text.length() + 8);
            int textOffset = 0;
            TextPosition previous = null;
            for (TextPosition current : positions) {
                String glyph = current.getUnicode();
                if (glyph == null || glyph.isEmpty() || !text.startsWith(glyph, textOffset)) {
                    // Bidi normalization and /ActualText can intentionally make the logical string
                    // differ from the glyph sequence. Coordinate-based rewriting is unsafe there.
                    return text;
                }
                if (previous != null && sameBaseline(previous, current)
                        && isWordCharacter(previous.getUnicode()) && isWordCharacter(glyph)
                        && previous.getXDirAdj() - current.getXDirAdj()
                                > Math.max(previous.getWidthDirAdj(), current.getWidthDirAdj()) * 2
                        && !repaired.isEmpty() && !Character.isWhitespace(repaired.charAt(repaired.length() - 1))) {
                    repaired.append(' ');
                }
                repaired.append(glyph);
                textOffset += glyph.length();
                previous = current;
            }
            return textOffset == text.length() ? repaired.toString() : text;
        }

        private static boolean sameBaseline(TextPosition left, TextPosition right) {
            return Math.abs(left.getYDirAdj() - right.getYDirAdj())
                    <= Math.max(left.getHeightDir(), right.getHeightDir()) * 0.25f;
        }

        private static boolean isWordCharacter(String value) {
            if (value == null || value.isEmpty()) {
                return false;
            }
            int codePoint = value.codePointBefore(value.length());
            return Character.isLetterOrDigit(codePoint);
        }
    }

    /** Follows painted Form XObjects and inline images, including nested forms. */
    private static final class PageImages extends PDFGraphicsStreamEngine {
        private final List<ImagePlacement> placements = new ArrayList<>();
        private final Set<COSBase> placed = Collections.newSetFromMap(new IdentityHashMap<>());
        private Point2D currentPoint = new Point2D.Float();

        private PageImages(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage image) {
            Matrix matrix = getGraphicsState().getCurrentTransformationMatrix();
            Point2D lowerLeft = matrix.transformPoint(0, 0);
            Point2D lowerRight = matrix.transformPoint(1, 0);
            Point2D upperLeft = matrix.transformPoint(0, 1);
            Point2D upperRight = matrix.transformPoint(1, 1);
            double displayedWidth = lowerLeft.distance(lowerRight);
            double displayedHeight = lowerLeft.distance(upperLeft);
            // PDF producers frequently encode rules, glyph fragments and masks as tiny image
            // XObjects. They have no useful OCR/display value and can otherwise create hundreds
            // of empty markers. Keep real icons, figures and full-page scans.
            if (displayedWidth < MIN_MEANINGFUL_IMAGE_POINTS
                    || displayedHeight < MIN_MEANINGFUL_IMAGE_POINTS
                    || !placed.add(image.getCOSObject())) {
                return;
            }
            float centerY = (float) ((lowerLeft.getY() + upperRight.getY()) / 2.0);
            float pageTop = getPage().getCropBox().getUpperRightY();
            placements.add(new ImagePlacement(image, pageTop - centerY));
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            currentPoint = p0;
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override public void clip(int windingRule) {}
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }

    private record ImagePlacement(PDImage image, float yFromTop) {
    }

    private record ProcessedImage(String firstPlacement, String repeatedPlacement) {
    }
}
