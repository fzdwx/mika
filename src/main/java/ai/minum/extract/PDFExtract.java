package ai.minum.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDMarkedContentReference;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureNode;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PDFExtract implements Extractor {
    private static final Logger logger = LoggerFactory.getLogger(PDFExtract.class);

    @Override
    public boolean support(String mimeType) {
        return "pdf".equals(mimeType) || "application/pdf".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        try (PDDocument doc = Loader.loadPDF(stream.readAllBytes())) {
            Map<COSDictionary, Map<Integer, String>> structureActualText = structureActualText(doc);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                Map<Integer, String> pageActualText = structureActualText.getOrDefault(
                        doc.getPage(i).getCOSObject(), Map.of());
                // Keep content-stream order first. Tagged and multi-column PDFs commonly encode
                // their intended reading order there, while coordinate sorting interleaves columns.
                String text = extractPageText(doc, i, false, pageActualText);
                if (isPredominantlyRightToLeft(text) || isFragmentedExtraction(text)) {
                    // Older Arabic/Hebrew PDFs often store glyphs in visual order. PDFBox's
                    // position sorter applies bidi normalization. It also repairs PDFs whose
                    // transformed text matrices make content-stream extraction nearly character-wise.
                    String positionSorted = extractPageText(doc, i, true, pageActualText);
                    if (isPredominantlyRightToLeft(text)
                            || hasMateriallyBetterLineStructure(text, positionSorted)) {
                        text = positionSorted;
                    }
                }
                StringBuilder content = new StringBuilder(Markdown.fromText(text));
                PageImages images = new PageImages(doc.getPage(i));
                images.processPage(doc.getPage(i));
                result.setHasImage(result.hasImage() || !images.images.isEmpty());
                for (PDImage image : images.images) {
                    if (!config.ocr() && !config.uploadImage()) {
                        continue;
                    }
                    if (!config.canHandleImage()) {
                        result.addWarning("Image count limit reached; some images were skipped");
                        continue;
                    }
                    ImageResult imageResult;
                    try {
                        if (image instanceof PDImageXObject xObject) {
                            imageResult = toImageResult(xObject);
                        } else {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            ImageIO.write(image.getImage(), "png", bytes);
                            imageResult = ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG);
                        }
                    } catch (IOException | RuntimeException e) {
                        logger.warn("Skip undecodable PDF image: page={}", i + 1, e);
                        result.addWarning("Cannot decode image on page " + (i + 1));
                        continue;
                    }
                    // Backend failures remain errors, so callers can retry OCR/upload.
                    String imageContent = extractImage(config, imageResult, result);
                    if (!imageContent.isBlank()) {
                        if (!content.isEmpty()) {
                            content.append("\n\n");
                        }
                        content.append(imageContent);
                    }
                }
                result.addPage((long) i, content.toString());
            }
        }
        return result;
    }

    private static String extractPageText(PDDocument document, int pageIndex, boolean sortByPosition,
                                          Map<Integer, String> structureActualText) throws IOException {
        PDFTextStripper reader = new StructureActualTextStripper(structureActualText);
        reader.setSortByPosition(sortByPosition);
        reader.setStartPage(pageIndex + 1);
        reader.setEndPage(pageIndex + 1);
        return normalizeExtractedUnicode(reader.getText(document));
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

    private static Map<COSDictionary, Map<Integer, String>> structureActualText(PDDocument document) {
        PDStructureTreeRoot root = document.getDocumentCatalog().getStructureTreeRoot();
        if (root == null) {
            return Map.of();
        }
        Map<COSDictionary, Map<Integer, String>> result = new IdentityHashMap<>();
        Set<COSDictionary> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        try {
            collectActualText(root, null, null, result, visited);
            return result;
        } catch (RuntimeException malformedStructure) {
            // A malformed optional structure tree must not prevent extraction of the page streams.
            logger.warn("Ignore malformed PDF structure tree while reading ActualText", malformedStructure);
            return Map.of();
        }
    }

    private static void collectActualText(PDStructureNode node, PDPage inheritedPage,
                                          ActualTextGroup inheritedGroup,
                                          Map<COSDictionary, Map<Integer, String>> result,
                                          Set<COSDictionary> visited) {
        if (!visited.add(node.getCOSObject())) {
            return;
        }
        PDPage page = inheritedPage;
        ActualTextGroup group = inheritedGroup;
        if (node instanceof PDStructureElement element) {
            if (element.getPage() != null) {
                page = element.getPage();
            }
            if (element.getActualText() != null) {
                group = new ActualTextGroup(element.getActualText());
            }
        }
        for (Object kid : node.getKids()) {
            if (kid instanceof PDStructureNode child) {
                collectActualText(child, page, group, result, visited);
            } else if (kid instanceof Integer mcid) {
                addActualText(result, page, mcid, group);
            } else if (kid instanceof PDMarkedContentReference reference) {
                PDPage referencePage = reference.getPage() == null ? page : reference.getPage();
                addActualText(result, referencePage, reference.getMCID(), group);
            }
        }
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

    /** Adds /ActualText stored on structure elements to PDFBox's marked-content handling. */
    private static final class StructureActualTextStripper extends PDFTextStripper {
        private final Map<Integer, String> structureActualText;

        private StructureActualTextStripper(Map<Integer, String> structureActualText) {
            this.structureActualText = structureActualText;
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
    }

    /** Follows painted Form XObjects and inline images, including nested forms. */
    private static final class PageImages extends PDFGraphicsStreamEngine {
        private final List<PDImage> images = new ArrayList<>();
        private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private Point2D currentPoint = new Point2D.Float();

        private PageImages(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage image) {
            if (seen.add(image.getCOSObject())) {
                images.add(image);
            }
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
}
