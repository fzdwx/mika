package ai.minum;

import ai.minum.extract.*;
import org.apache.tika.Tika;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;

public class Mika {

    private static final Tika tika = new Tika();
    private static final TikaExtractor tikaExtractor = new TikaExtractor();
    private static final DocxExtract docxExtract = new DocxExtract();
    private static final DocExtract docExtract = new DocExtract();
    private static final PDFExtract pdfExtract = new PDFExtract();
    private static final ImageExtract imageExtract = new ImageExtract();
    private static final MarkdownExtract mdExtract = new MarkdownExtract();

    private static final List<Extractor> extractors = List.of(pdfExtract, docExtract, docxExtract, imageExtract, mdExtract);

    public static Tika getTika() {
        return tika;
    }

    public static ExtractResult extract(String mimeType, InputStream stream, ExtractConfig config) {
        if (stream == null || config == null) {
            return ExtractResult.error("Input stream and extraction config are required");
        }
        String type = normalizeMimeType(mimeType);
        return extractors.stream()
                .filter(extractor -> extractor.support(type))
                .findFirst()
                .map(extractor -> {
                    return extractor.extract(config, stream);
                })
                .orElseGet(() -> {
                    if (config.fallback()) {
                        return tikaExtractor.extract(config, stream);
                    }
                    return ExtractResult.error("No extractor found for " + mimeType);
                });

    }

    private static String normalizeMimeType(String mimeType) {
        if (mimeType == null) {
            return "";
        }
        String type = mimeType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
        String normalizedDocument = switch (type) {
            case "application/pdf" -> "pdf";
            case "application/msword", "application/vnd.ms-word" -> "doc";
            case "application/vnd.ms-word.template", "application/x-dot" -> "dot";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "md", "text/markdown", "text/x-markdown" -> "markdown";
            default -> type;
        };
        ImageResult.Format image = ImageResult.Format.fromInputType(normalizedDocument);
        return image == ImageResult.Format.UNKNOWN
                ? normalizedDocument : image.getExtractorType();
    }
}
