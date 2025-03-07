package ai.minum.extract;

import java.io.InputStream;
import java.util.List;

public class ImageExtract implements Extractor {

    private final static String BMP = "bmp";
    private final static String PNG = "png";
    private final static String JPEG = "jpeg";
    private final static String JPG = "jpg";
    private final static String WEBP = "webp";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(BMP, PNG, JPEG, JPG, WEBP);


    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        result.setHasImage(true);
        if (!config.ocr()) {
            return result;
        }

        byte[] bytes = stream.readAllBytes();
        if (bytes.length > config.imageExtractMaxSize()) {
            return result;
        }

        String content = config.getOcr().doOrc(bytes);
        return ExtractResult.successOfOne(content);
    }
}
