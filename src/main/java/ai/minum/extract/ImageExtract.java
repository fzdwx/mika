package ai.minum.extract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.List;

public class ImageExtract implements Extractor {

    private final static String BMP = "image/bmp";
    private final static String PNG = "image/png";
    private final static String JPEG = "image/jpeg";
    private final static String JPG = "image/jpg";
    private final static String WEBP = "image/webp";
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
