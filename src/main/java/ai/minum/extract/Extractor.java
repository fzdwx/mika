package ai.minum.extract;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public interface Extractor {
    final static Logger logger = LoggerFactory.getLogger(Extractor.class);

    boolean support(String mimeType);

    ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception;

    default String extractImage(ExtractConfig config, ImageResult image, ExtractResult result) throws Exception {
        if (image.length() > config.imageExtractMaxSize()) {
            result.addWarning("Image size limit exceeded; an image was skipped");
            return "";
        }
        if (image.length() == 0 || image.getMimeType() == ImageResult.Format.UNKNOWN) {
            result.addWarning("Empty or unsupported image was skipped");
            return "";
        }
        return extractImage(config, image);
    }

    default String extractImage(ExtractConfig config, ImageResult result) throws Exception {
        if (result.length() > config.imageExtractMaxSize()) {
            return "";
        }
        if (result.getData().length == 0) {
            return "";
        }

        if (ImageResult.Format.UNKNOWN == result.getMimeType()) {
            return "";
        }

        String imageContent = "";
        if (config.ocr()) {
            if (config.getOcr() == null) {
                throw new IllegalStateException("OCR is enabled but no OCR backend is configured");
            }
            imageContent = config.getOcr().doOrc(result.getData());
            if (imageContent == null) {
                imageContent = "";
            }
        }

        String imageKey = "";
        if (config.uploadImage()) {
            if (config.imageUploader() == null) {
                throw new IllegalStateException("Image upload is enabled but no uploader is configured");
            }
            imageKey = config.imageUploader().upload(result);
        }
        return Markdown.image(imageKey, imageContent);
    }


    default ExtractResult extract(ExtractConfig config, InputStream stream) {
        ExtractConfig extractionConfig = config.copyForExtraction();
        SizeLimitedInputStream limited = new SizeLimitedInputStream(stream, extractionConfig.maxExtractInputSize());
        try {
            ExtractResult result = doExtract(extractionConfig, limited);
            limited.verifyExhausted();
            return result;
        } catch (Exception e) {
            return ExtractResult.error(e);
        }
    }

    default void close(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignore) {
        }
    }

    default ImageResult toImageResult(PDImageXObject img) throws IOException {
        BufferedImage image = img.getImage();
        if (image == null) {
            return ImageResult.of(new byte[]{}, ImageResult.Format.UNKNOWN);
        }
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return ImageResult.of(baos.toByteArray(), ImageResult.Format.PNG);
    }

    default ImageResult toImageResult(Picture pic) {
        if (pic == null) {
            return ImageResult.of(new byte[]{}, ImageResult.Format.UNKNOWN);
        }
        byte[] content = pic.getContent();
        return ImageResult.of(content, pic.suggestPictureType());
    }

    default ImageResult toImageResult(XWPFPictureData pic) {
        byte[] content = pic.getData();
        InputStream buffin = new ByteArrayInputStream(content);
        try {
            BufferedImage image = ImageIO.read(buffin);
            if (image == null) {
                return ImageResult.of(new byte[]{}, ImageResult.Format.UNKNOWN);
            }
        } catch (IOException e) {
            return ImageResult.of(new byte[]{}, ImageResult.Format.UNKNOWN);
        }


        return ImageResult.of(content, pic.getPictureTypeEnum());
    }

    default boolean checkRatio(int a, int b) {
        int max = Math.max(a, b);
        int min = Math.min(a, b);
        return min <= 0 || (double) max / min > 8;
    }
}
