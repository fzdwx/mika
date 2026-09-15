package ai.minum.extract;

import ai.minum.Mika;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
import org.apache.tika.io.TikaInputStream;
import org.apache.tika.metadata.HttpHeaders;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.apache.tika.parser.ParseContext;
import org.apache.tika.parser.Parser;
import org.apache.tika.parser.html.DefaultHtmlMapper;
import org.apache.tika.parser.html.HtmlMapper;
import org.apache.tika.parser.microsoft.OfficeParserConfig;
import org.apache.tika.parser.ocr.TesseractOCRConfig;
import org.apache.tika.sax.ToXMLContentHandler;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.xml.sax.ContentHandler;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.AttributesImpl;
import org.xml.sax.helpers.DefaultHandler;

import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TikaExtractor implements Extractor {
    private static final String MIKA_EMF_CONTENT_TYPE = "application/x-mika-emf";
    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        if (!requiresRepeatableSource() && !config.ocr() && !config.uploadImage()) {
            return parseDocument(config, stream, null);
        }

        // Keep a bounded source on disk for format-specific structure recovery. Image positions are
        // known only after Tika has produced XHTML, so image handling also needs this repeatable input.
        Path source = Files.createTempFile("mika-extract-", ".bin");
        try {
            Files.copy(stream, source, StandardCopyOption.REPLACE_EXISTING);
            try (InputStream firstPass = Files.newInputStream(source)) {
                return parseDocument(config, firstPass, source);
            }
        } finally {
            try {
                Files.deleteIfExists(source);
            } catch (IOException cleanupError) {
                logger.warn("Failed to delete temporary extraction file: {}", source, cleanupError);
                source.toFile().deleteOnExit();
            }
        }
    }

    private ExtractResult parseDocument(ExtractConfig config, InputStream stream, Path repeatableSource)
            throws Exception {
        ExtractResult result = ExtractResult.of();
        Parser parser = Mika.getTika().getParser();
        ParseContext context = parseContext(parser);
        context.set(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor() {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata, ParseContext embeddedContext) {
                if (isThumbnail(metadata)) {
                    return false;
                }
                if (isImage(metadata)) {
                    return true;
                }
                // An OOXML altChunk is part of the Word body even though Tika exposes it through
                // the embedded-document API. Parse HTML/MHTML chunks while keeping ordinary file
                // attachments out of the parent document.
                if (isAlternateFormatChunk(metadata)) {
                    return true;
                }
                // Word stores legacy Excel charts as OLE attachments even though the chart is a
                // visible part of the page. Parse only chart objects; embedded workbooks and other
                // arbitrary attachments remain separate documents.
                if (isVisibleLegacyExcelChart(metadata)) {
                    return true;
                }
                // Attachments are separate documents. Recursive extraction creates ambiguous image
                // names and unbounded expansion; callers can submit them as independent files.
                return false;
            }

            @Override
            public void parseEmbedded(TikaInputStream input, ContentHandler handler, Metadata metadata,
                                      ParseContext embeddedContext, boolean outputHtml)
                    throws SAXException, IOException {
                if (isImage(metadata)) {
                    return;
                }
                if (isAlternateFormatChunk(metadata)) {
                    super.parseEmbedded(input, handler, metadata, embeddedContext, outputHtml);
                } else if (isVisibleLegacyExcelChart(metadata)) {
                    AttributesImpl attributes = new AttributesImpl();
                    attributes.addAttribute("", "class", "class", "CDATA", "mika-legacy-excel-chart");
                    handler.startElement("http://www.w3.org/1999/xhtml", "div", "div", attributes);
                    super.parseEmbedded(input, handler, metadata, embeddedContext, outputHtml);
                    handler.endElement("http://www.w3.org/1999/xhtml", "div", "div");
                }
            }
        });

        // Unlike Tika.parseToString(), this path does not stop at its default text limit.
        SizeLimitedOutputStream xhtml = new SizeLimitedOutputStream(config.maxExtractedContentSize());
        ToXMLContentHandler handler = new ToXMLContentHandler(xhtml, StandardCharsets.UTF_8.name());
        Metadata metadata = new Metadata();
        try (TikaInputStream tikaInput = TikaInputStream.get(closeShield(stream))) {
            parser.parse(tikaInput, handler, metadata, context);
        }
        // ToXMLContentHandler emits XHTML as XML, including self-closing inline tags. Parse that
        // syntax first so <b/> cannot become an opening tag, serialize the established boundaries
        // as HTML pairs, then apply HTML whitespace and recovery rules to embedded HTML chunks.
        Document document = parseTikaXhtml(xhtml.toString(StandardCharsets.UTF_8));
        removeDuplicateLegacyChartViews(document);
        cleanDocument(document, metadata, repeatableSource, result);
        String contentType = metadata.get(HttpHeaders.CONTENT_TYPE);
        // Preserve blank-line paragraph boundaries before serializing Jsoup's HTML DOM; HTML
        // serialization otherwise collapses Tika's linked-text-box text into one retrieval line.
        if (contentType == null || !contentType.startsWith("text/plain")) {
            Markdown.splitEmbeddedParagraphs(document);
        }
        Map<String, String> equationMarkers = equationMarkers(document);
        result.setHasTable(!document.select("table").isEmpty());

        Set<String> referencedImages = new LinkedHashSet<>();
        Set<String> anonymousImages = new LinkedHashSet<>();
        for (Element image : document.select("img")) {
            String name = referencedImageName(image);
            if (name != null) {
                referencedImages.add(name);
                if (image.attr("src").startsWith("file:")) {
                    anonymousImages.add(name);
                }
            }
        }
        Set<String> imagesWithStructuredText = repeatableSource == null
                ? Set.of() : imagesWithStructuredText(repeatableSource);
        Map<String, String> processedImages = repeatableSource == null || referencedImages.isEmpty()
                || (!config.ocr() && !config.uploadImage())
                ? Map.of()
                : processReferencedImages(config, repeatableSource, referencedImages, anonymousImages,
                        imagesWithStructuredText, result, parser);

        Map<String, String> imageMarkers = new LinkedHashMap<>();
        String sourceText = document.text();
        int markerIndex = 0;
        for (Element image : document.select("img")) {
            String name = referencedImageName(image);
            String alternativeText = meaningfulAlternativeText(image.attr("alt"), name);
            String content = name == null ? null : processedImages.get(name);
            if (content != null && !content.isBlank() && !alternativeText.isBlank()) {
                content = appendImageText(content, alternativeText);
            } else if ((content == null || content.isBlank()) && !alternativeText.isBlank()) {
                content = Markdown.image("", alternativeText);
            } else if ((content == null || content.isBlank()) && name != null) {
                // The caller may intentionally disable OCR/upload, or an image may exceed a
                // processing limit. Its position is still part of the document and must survive.
                content = Markdown.image("", "");
            }
            if (content == null || content.isBlank()) {
                image.remove();
            } else {
                // Keep legacy image sentinels byte-for-byte; HTML-to-Markdown converters escape them.
                String marker;
                do {
                    marker = "MIKAIMAGEMARKER"
                            + UUID.randomUUID().toString().replace("-", "")
                            + markerIndex++ + "TOKEN";
                } while (sourceText.contains(marker));
                image.text(marker);
                image.unwrap();
                imageMarkers.put(marker, content);
            }
        }
        // Tika also emits anonymous <img/> elements for some VML text-box shapes. Report an image
        // only when a real reference, alternative text, upload, or OCR result survived as a marker.
        result.setHasImage(!imageMarkers.isEmpty());
        // Tika adds the package part name as a synthetic heading around altChunk content. It is an
        // implementation detail rather than Word body text and would otherwise pollute retrieval.
        document.select("div.package-entry > h1:first-child").remove();
        String markdown = contentType != null && contentType.startsWith("text/plain")
                ? Markdown.fromText(document.body().wholeText())
                : Markdown.fromHtml(document.body().html());
        for (Map.Entry<String, String> marker : imageMarkers.entrySet()) {
            markdown = markdown.replace(marker.getKey(), marker.getValue());
        }
        for (Map.Entry<String, String> marker : equationMarkers.entrySet()) {
            markdown = markdown.replace(marker.getKey(), marker.getValue());
        }
        if (splitIntoLogicalSections()) {
            long page = 0;
            for (String section : MarkdownSections.split(markdown)) {
                result.addPage(page++, section);
            }
        } else {
            result.addPage(0L, markdown);
        }
        return result;
    }

    static Document parseTikaXhtml(String xhtml) {
        Document xml = Jsoup.parse(xhtml, "", org.jsoup.parser.Parser.xmlParser());
        xml.outputSettings().syntax(Document.OutputSettings.Syntax.html);
        return Jsoup.parse(xml.outerHtml());
    }

    static String meaningfulAlternativeText(String value, String imageName) {
        if (value == null || value.isBlank()) {
            return "";
        }
        String text = value.replace("\r\n", "\n").replace('\r', '\n').strip();
        String compact = text.replaceAll("(?i)\\s*description automatically generated\\s*$", "")
                .replaceAll("\\s+", " ").strip();
        if (imageName != null && compact.equalsIgnoreCase(imageName)) {
            return "";
        }
        if (compact.matches("(?i)(?:image|picture|graphic|shape|drawing|photo|logo|图|图片|图像|形状|文本框)\\s*\\d*(?:\\.[a-z0-9]+)?")) {
            return "";
        }
        if (compact.matches("(?i)[^\\s/\\\\]+\\.(?:png|jpe?g|gif|bmp|tiff?|wmf|emf|svg|webp|jp2|jpx|pict)")) {
            return "";
        }
        if (compact.matches("(?i)(?:https?|file)://.*")
                || compact.matches("(?i)[a-z]:\\\\.*")
                || compact.matches("(?i)(?:img|dsc|微信图片)[_-]?\\d+.*")
                || compact.matches("(?i)△?图片来源[:：].*")
                || compact.matches("(?i)[0-9a-f]{12,}(?:[_-][a-z0-9]+)?")
                || compact.matches("\\d{6,}(?:\\(\\d+\\))?")
                || compact.matches("[\\p{L}_ -]{1,8}\\d{1,4}")) {
            return "";
        }
        return compact;
    }

    /** Prefer a chart's structured worksheet when it contains all values in the rendered view. */
    private static void removeDuplicateLegacyChartViews(Document document) {
        for (Element chart : document.select("div.mika-legacy-excel-chart")) {
            java.util.List<Element> pages = new java.util.ArrayList<>(chart.select("div.page"));
            Element best = null;
            int bestCells = -1;
            for (Element page : pages) {
                int cells = page.select("td, th").size();
                if (cells > bestCells) {
                    best = page;
                    bestCells = cells;
                }
            }
            if (best == null || bestCells == 0) {
                continue;
            }
            Set<String> structuredValues = significantValues(best);
            for (Element page : pages) {
                if (page == best) {
                    continue;
                }
                Element copy = page.clone();
                copy.select("h1:first-child").remove();
                Set<String> renderedValues = significantValues(copy);
                if (!renderedValues.isEmpty() && structuredValues.containsAll(renderedValues)) {
                    page.remove();
                }
            }
            chart.unwrap();
        }
    }

    private static Set<String> significantValues(Element element) {
        Set<String> values = new LinkedHashSet<>();
        for (String value : element.text().split("\\s+")) {
            String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
            if (!normalized.isEmpty()) {
                values.add(normalized);
            }
        }
        return values;
    }

    private static Map<String, String> equationMarkers(Document document) {
        Map<String, String> markers = new LinkedHashMap<>();
        int index = 0;
        String sourceText = document.text();
        for (Element equation : document.select(".mika-equation[data-mika-latex]")) {
            String marker;
            do {
                marker = "MIKAEQUATIONMARKER"
                        + UUID.randomUUID().toString().replace("-", "") + index++ + "TOKEN";
            } while (sourceText.contains(marker));
            String latex = equation.attr("data-mika-latex");
            String markdown = Boolean.parseBoolean(equation.attr("data-mika-display"))
                    ? "$$\n" + latex + "\n$$" : "$" + latex + "$";
            equation.removeClass("mika-equation")
                    .removeAttr("data-mika-latex")
                    .removeAttr("data-mika-display")
                    .text(marker);
            markers.put(marker, markdown);
        }
        return markers;
    }

    private static String appendImageText(String imageBlock, String text) {
        String markdown = Markdown.fromText(text);
        if (markdown.isBlank() || imageBlock.contains(markdown)) {
            return imageBlock;
        }
        int end = imageBlock.lastIndexOf("[ImageEnd]");
        if (end < 0) {
            return imageBlock;
        }
        int bodyStart = "[Image]".length();
        if (imageBlock.startsWith("[Image](")) {
            int destinationEnd = imageBlock.indexOf(')', bodyStart);
            if (destinationEnd >= 0 && destinationEnd < end) {
                bodyStart = destinationEnd + 1;
            }
        }
        String separator = imageBlock.substring(bodyStart, end).isBlank() ? "" : "\n\n";
        return imageBlock.substring(0, end) + separator + markdown + imageBlock.substring(end);
    }

    /** Format-specific cleanup after Tika has produced XHTML and before Markdown conversion. */
    protected void cleanDocument(Document document, Metadata metadata) {
    }

    /**
     * Indicates that a format needs a repeatable source even when image processing is disabled.
     * The source is still bounded by {@link ExtractConfig#maxExtractInputSize()}.
     */
    protected boolean requiresRepeatableSource() {
        return false;
    }

    /** Word processors use Markdown structure as logical storage sections. */
    protected boolean splitIntoLogicalSections() {
        return false;
    }

    /** Format-specific cleanup that also needs access to the bounded source and extraction result. */
    protected void cleanDocument(Document document, Metadata metadata, Path repeatableSource,
                                 ExtractResult result) {
        cleanDocument(document, metadata);
    }

    /** Images whose package already supplies usable structured text, such as chart caches. */
    protected Set<String> imagesWithStructuredText(Path repeatableSource) {
        return Set.of();
    }

    private Map<String, String> processReferencedImages(ExtractConfig config, Path source,
                                                         Set<String> referencedImages, Set<String> anonymousImages,
                                                         Set<String> imagesWithStructuredText,
                                                         ExtractResult result,
                                                         Parser parser) throws Exception {
        long imageLimit = config.getMaxHandleImageCount();
        Set<String> selectedImages = new LinkedHashSet<>();
        for (String name : referencedImages) {
            if (imageLimit >= 0 && selectedImages.size() >= imageLimit) {
                result.addWarning("Image count limit reached; some images were skipped");
                break;
            }
            selectedImages.add(name);
        }
        Map<String, String> processed = new LinkedHashMap<>();
        Iterator<String> anonymousImageNames = anonymousImages.stream()
                .filter(selectedImages::contains).iterator();
        ParseContext context = parseContext(parser);
        context.set(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor() {
            private int alternateFormatDepth;

            @Override
            public boolean shouldParseEmbedded(Metadata metadata, ParseContext embeddedContext) {
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                return isAlternateFormatChunk(metadata)
                        || isImage(metadata) && (name == null && alternateFormatDepth > 0
                        || selectedImages.contains(name) && !processed.containsKey(name));
            }

            @Override
            public void parseEmbedded(TikaInputStream input, ContentHandler handler, Metadata metadata,
                                      ParseContext embeddedContext, boolean outputHtml)
                    throws SAXException, IOException {
                if (isAlternateFormatChunk(metadata)) {
                    alternateFormatDepth++;
                    try {
                        super.parseEmbedded(input, handler, metadata, embeddedContext, outputHtml);
                    } finally {
                        alternateFormatDepth--;
                    }
                    return;
                }
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                // Tika's RFC 822 parser currently omits Content-Location from embedded MHTML image
                // metadata. Match anonymous parts to the remaining body references in encounter order.
                while (name == null && alternateFormatDepth > 0 && anonymousImageNames.hasNext()) {
                    String candidate = anonymousImageNames.next();
                    if (!processed.containsKey(candidate)) {
                        name = candidate;
                    }
                }
                if (name == null || !selectedImages.contains(name) || processed.containsKey(name)) {
                    return;
                }
                int limit = (int) Math.min((long) config.imageExtractMaxSize() + 1, Integer.MAX_VALUE);
                byte[] bytes = input.readNBytes(Math.max(0, limit));
                ImageResult image = ImageResult.of(bytes, imageFormat(metadata));
                try {
                    processed.put(name, extractImage(config, image, result,
                            !imagesWithStructuredText.contains(name)));
                } catch (Exception e) {
                    throw new SAXException("Failed to process embedded image " + name, e);
                }
            }
        });
        try (TikaInputStream secondPass = TikaInputStream.get(source)) {
            parser.parse(secondPass, new DefaultHandler(), new Metadata(), context);
        }
        return processed;
    }

    private static ParseContext parseContext(Parser parser) {
        ParseContext context = new ParseContext();
        context.set(Parser.class, parser);
        context.set(HtmlMapper.class, new DefaultHtmlMapper() {
            @Override
            public String mapSafeAttribute(String element, String attribute) {
                if (("td".equalsIgnoreCase(element) || "th".equalsIgnoreCase(element))
                        && ("rowspan".equalsIgnoreCase(attribute) || "colspan".equalsIgnoreCase(attribute))) {
                    return attribute.toLowerCase(java.util.Locale.ROOT);
                }
                return super.mapSafeAttribute(element, attribute);
            }
        });
        // OCR is explicitly routed through the caller's backend, never a local Tesseract process.
        TesseractOCRConfig ocr = new TesseractOCRConfig();
        ocr.setSkipOcr(true);
        context.set(TesseractOCRConfig.class, ocr);
        OfficeParserConfig office = new OfficeParserConfig();
        office.setConcatenatePhoneticRuns(false);
        context.set(OfficeParserConfig.class, office);
        return context;
    }

    private static boolean isImage(Metadata metadata) {
        String type = metadata.get(HttpHeaders.CONTENT_TYPE);
        return type != null && (type.startsWith("image/") || MIKA_EMF_CONTENT_TYPE.equals(type));
    }

    private static ImageResult.Format imageFormat(Metadata metadata) {
        String type = metadata.get(HttpHeaders.CONTENT_TYPE);
        return MIKA_EMF_CONTENT_TYPE.equals(type) ? ImageResult.Format.EMF
                : ImageResult.Format.fromMimeType(type);
    }

    private static InputStream closeShield(InputStream stream) {
        return new FilterInputStream(stream) {
            @Override
            public void close() {
                // The caller owns the source stream. TikaInputStream still closes its own
                // temporary resources without closing the stream supplied to Mika.
            }
        };
    }

    private static boolean isThumbnail(Metadata metadata) {
        return "THUMBNAIL".equals(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE));
    }

    private static boolean isAlternateFormatChunk(Metadata metadata) {
        return "ALTERNATE_FORMAT_CHUNK".equals(
                metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE));
    }

    private static boolean isVisibleLegacyExcelChart(Metadata metadata) {
        String programId = metadata.get("msoffice:prog-id");
        return "ATTACHMENT".equals(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE))
                && metadata.get(TikaCoreProperties.EMBEDDED_RELATIONSHIP_ID) != null
                && programId != null
                && programId.regionMatches(true, 0, "Excel.Chart.", 0, "Excel.Chart.".length());
    }

    private static String referencedImageName(Element image) {
        String source = image.attr("src");
        if (source.startsWith("embedded:")) {
            return source.substring("embedded:".length());
        }
        // HTML imported through an MHTML altChunk commonly uses file:///name image references.
        boolean insidePackageEntry = image.parents().stream()
                .anyMatch(parent -> parent.hasClass("package-entry"));
        if (source.startsWith("file:") && insidePackageEntry) {
            int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
            String name = source.substring(slash + 1);
            return name.isBlank() ? null : name;
        }
        return null;
    }

    private static final class SizeLimitedOutputStream extends OutputStream {
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final int limit;
        private int count;

        private SizeLimitedOutputStream(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            requireCapacity(1);
            output.write(value);
            count++;
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            requireCapacity(length);
            output.write(bytes, offset, length);
            count += length;
        }

        private void requireCapacity(int length) throws IOException {
            if ((long) count + length > limit) {
                throw new IOException("Extracted content size limit exceeded: " + limit + " bytes");
            }
        }

        private String toString(java.nio.charset.Charset charset) {
            return output.toString(charset);
        }
    }

    @Override
    public boolean support(String mimeType) {
        return true;
    }
}
