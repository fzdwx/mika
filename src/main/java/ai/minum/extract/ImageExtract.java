package ai.minum.extract;

import ai.minum.Mika;
import java.io.InputStream;

public class ImageExtract implements Extractor {

    @Override
    public boolean support(String mimeType) {
        return ImageResult.Format.fromInputType(mimeType) != ImageResult.Format.UNKNOWN;
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
