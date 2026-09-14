package ai.minum.extract;

import ai.minum.Mika;
import java.io.InputStream;
import java.util.List;

public class ImageExtract implements Extractor {

    private final static String BMP = "bmp";
    private final static String PNG = "png";
    private final static String JPEG = "jpeg";
    private final static String JPG = "jpg";
    private final static String WEBP = "webp";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(BMP, PNG, JPEG, JPG, WEBP, "gif", "tif", "tiff");


    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        result.setHasImage(true);
        if (!config.ocr() && !config.uploadImage()) {
            return result;
        }
        if (!config.canHandleImage()) {
            result.addWarning("Image count limit reached; some images were skipped");
            return result;
        }

        int limit = (int) Math.min((long) config.imageExtractMaxSize() + 1, Integer.MAX_VALUE);
        byte[] bytes = stream.readNBytes(limit);

        String content = extractImage(config, ImageResult.of(bytes,
                ImageResult.Format.fromMimeType(Mika.getTika().detect(bytes))), result);
        result.addPage(0L, content);
        return result;
    }
}
