package ai.minum;

import ai.minum.extract.*;
import com.j256.simplemagic.ContentInfo;
import com.j256.simplemagic.ContentInfoUtil;
import org.apache.tika.Tika;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.mime.MimeTypes;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

public class Mika {

    private static final Tika tika = new Tika();
    private static final TikaExtractor tikaExtractor = new TikaExtractor();
    private static final DocxExtract docxExtract = new DocxExtract();
    private static final DocExtract docExtract = new DocExtract();
    private static final PDFExtract pdfExtract = new PDFExtract();
    private static final ImageExtract imageExtract = new ImageExtract();

    private static final List<Extractor> extractors = List.of(pdfExtract, docExtract, docxExtract, imageExtract);

    public static Tika getTika() {
        return tika;
    }

    public static ExtractResult extract(String mimeType,InputStream stream, ExtractConfig config) {
        return extractors.stream()
                .filter(extractor -> extractor.support(mimeType))
                .findFirst()
                .map(extractor -> {
                    return extractor.extract(config, stream);
                })
                .orElseGet(() -> {
                    if (config.fallback()) {
                        return doFallbackExtract(stream);
                    }
                    return ExtractResult.error("No extractor found for " + mimeType);
                });

    }

    private static ExtractResult doFallbackExtract(InputStream stream) {
        return tikaExtractor.extract(ExtractConfig.defaultConfig(), stream);
    }
}
