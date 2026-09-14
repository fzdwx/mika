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
        return switch (type) {
            case "application/pdf" -> "pdf";
            case "application/msword" -> "doc";
            case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> "docx";
            case "md", "text/markdown", "text/x-markdown" -> "markdown";
            case "image/png", "image/jpeg", "image/jpg", "image/bmp", "image/webp", "image/gif", "image/tiff" -> type.substring(6);
            default -> type;
        };
    }
}
