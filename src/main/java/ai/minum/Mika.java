package ai.minum;

import ai.minum.extract.*;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class Mika {

    private static final Tika tika = new Tika();
    private static final TikaExtractor tikaExtractor = new TikaExtractor();
    private static final DocxExtract docxExtract = new DocxExtract();
    private static final DocExtract docExtract = new DocExtract();
    private static final PDFExtract pdfExtract = new PDFExtract();

    private static final List<Extractor> extractors = List.of(pdfExtract, docExtract, docxExtract);

    public static Tika getTika() {
        return tika;
    }

    public static DetectResult detect(InputStream stream) {
        var metadata = new Metadata();
        final String result;
        try {
            result = tika.detect(stream, metadata);
            return new DetectResult(result, metadata);
        } catch (java.io.IOException e) {
            return new DetectResult((byte) 1, e.getMessage());
        }
    }

    public static ExtractResult extract(InputStream stream, ExtractConfig config) {
        DetectResult detectResult = Mika.detect(stream);
        try {
            stream.reset();
        } catch (IOException e) {
            return ExtractResult.error(e.getMessage());
        }

        if (detectResult.isError()) {
            if (config.fallback()) {
                return doFallbackExtract(stream);
            }
            return ExtractResult.error(detectResult.getErrorMessage());
        }

        return extractors.stream()
                .filter(extractor -> extractor.support(detectResult.getContent()))
                .findFirst()
                .map(extractor -> extractor.extract(config, stream))
                .orElseGet(() -> {
                    if (config.fallback()) {
                        return doFallbackExtract(stream);
                    }
                    return ExtractResult.error("No extractor found for " + detectResult.getContent());
                });

    }

    private static ExtractResult doFallbackExtract(InputStream stream) {
        return tikaExtractor.extract(ExtractConfig.defaultConfig(), stream);
    }
}
