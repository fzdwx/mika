package ai.minum.extract;

import ai.minum.Mika;
import org.apache.tika.extractor.EmbeddedDocumentExtractor;
import org.apache.tika.extractor.ParsingEmbeddedDocumentExtractor;
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
import org.xml.sax.helpers.DefaultHandler;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class TikaExtractor implements Extractor {
    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        if (!config.ocr() && !config.uploadImage()) {
            return parseDocument(config, stream, null);
        }

        // Image positions are known only after Tika has produced XHTML. Keep the source on disk so a
        // second pass can load just the body-referenced images without caching every package resource.
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
        context.set(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor(context) {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                if (isThumbnail(metadata)) {
                    return false;
                }
                if (isImage(metadata)) {
                    return true;
                }
                // Attachments are separate documents. Recursive extraction creates ambiguous image
                // names and unbounded expansion; callers can submit them as independent files.
                return false;
            }

            @Override
            public void parseEmbedded(InputStream input, ContentHandler handler, Metadata metadata,
                                      boolean outputHtml) throws SAXException, IOException {
                if (isImage(metadata)) {
                    return;
                }
            }
        });

        // Unlike Tika.parseToString(), this path does not stop at its default text limit.
        SizeLimitedOutputStream xhtml = new SizeLimitedOutputStream(config.maxExtractedContentSize());
        ToXMLContentHandler handler = new ToXMLContentHandler(xhtml, StandardCharsets.UTF_8.name());
        Metadata metadata = new Metadata();
        parser.parse(stream, handler, metadata, context);
        Document document = Jsoup.parse(xhtml.toString(StandardCharsets.UTF_8));
        result.setHasTable(!document.select("table").isEmpty());
        result.setHasImage(!document.select("img").isEmpty());

        Set<String> referencedImages = new LinkedHashSet<>();
        for (Element image : document.select("img")) {
            String imageSource = image.attr("src");
            if (imageSource.startsWith("embedded:")) {
                referencedImages.add(imageSource.substring("embedded:".length()));
            }
        }
        Map<String, String> processedImages = repeatableSource == null || referencedImages.isEmpty()
                ? Map.of()
                : processReferencedImages(config, repeatableSource, referencedImages, result, parser);

        Map<String, String> imageMarkers = new LinkedHashMap<>();
        String sourceText = document.text();
        int markerIndex = 0;
        for (Element image : document.select("img")) {
            String source = image.attr("src");
            if (!source.startsWith("embedded:")) {
                continue;
            }
            String content = processedImages.get(source.substring("embedded:".length()));
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
        String contentType = metadata.get(Metadata.CONTENT_TYPE);
        String markdown = contentType != null && contentType.startsWith("text/plain")
                ? Markdown.fromText(document.body().wholeText())
                : Markdown.fromHtml(document.body().html());
        for (Map.Entry<String, String> marker : imageMarkers.entrySet()) {
            markdown = markdown.replace(marker.getKey(), marker.getValue());
        }
        result.addPage(0L, markdown);
        return result;
    }

    private Map<String, String> processReferencedImages(ExtractConfig config, Path source,
                                                         Set<String> referencedImages, ExtractResult result,
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
        ParseContext context = parseContext(parser);
        context.set(EmbeddedDocumentExtractor.class, new ParsingEmbeddedDocumentExtractor(context) {
            @Override
            public boolean shouldParseEmbedded(Metadata metadata) {
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                return isImage(metadata) && selectedImages.contains(name) && !processed.containsKey(name);
            }

            @Override
            public void parseEmbedded(InputStream input, ContentHandler handler, Metadata metadata,
                                      boolean outputHtml) throws SAXException, IOException {
                String name = metadata.get(TikaCoreProperties.RESOURCE_NAME_KEY);
                int limit = (int) Math.min((long) config.imageExtractMaxSize() + 1, Integer.MAX_VALUE);
                byte[] bytes = input.readNBytes(Math.max(0, limit));
                ImageResult image = ImageResult.of(bytes,
                        ImageResult.Format.fromMimeType(metadata.get(Metadata.CONTENT_TYPE)));
                try {
                    processed.put(name, extractImage(config, image, result));
                } catch (Exception e) {
                    throw new SAXException("Failed to process embedded image " + name, e);
                }
            }
        });
        try (InputStream secondPass = Files.newInputStream(source)) {
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
        String type = metadata.get(Metadata.CONTENT_TYPE);
        return type != null && type.startsWith("image/");
    }

    private static boolean isThumbnail(Metadata metadata) {
        return "THUMBNAIL".equals(metadata.get(TikaCoreProperties.EMBEDDED_RESOURCE_TYPE));
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
