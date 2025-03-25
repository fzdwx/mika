package ai.minum.extract;

import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;

public interface Extractor {
    final static Logger logger = LoggerFactory.getLogger(Extractor.class);

    boolean support(String mimeType);

    ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception;

    default String extractImage(ExtractConfig config, ImageResult result) throws Exception {
        Long maxHandleImageCount = config.getMaxHandleImageCount();
        if (maxHandleImageCount == -1) {
            return doExtractImage(config, result);
        }
        if (maxHandleImageCount == 0) {
            return "";
        }
        
        try {
            if (maxHandleImageCount > 0) {
                return doExtractImage(config, result);
            }
        } finally {
            maxHandleImageCount--;
            if (maxHandleImageCount <= 0) {
                maxHandleImageCount = 0L;
            }
            config.maxHandleImageCount(maxHandleImageCount);
        }
        return "";
    }

    private static String doExtractImage(ExtractConfig config, ImageResult result) throws Exception {
        if (result.length() > config.imageExtractMaxSize()) {
            return "";
        }

        if (ImageResult.Format.UNKNOWN == result.getMimeType()
                || ImageResult.Format.TIFF == result.getMimeType()
        ) {
            return "";
        }

        String imageContent = "";
        if (config.ocr()) {
            imageContent = config.getOcr().doOrc(result.getData());
            if (imageContent == null) {
                imageContent = "";
            }
        }

        String content = "\n[Image";
        String imageKey = "";
        if (config.uploadImage() && config.imageUploader() != null) {
            imageKey = config.imageUploader().upload(result);
        }
        if (!imageKey.isEmpty()) {
            content = content.concat("](").concat(imageKey).concat(")");
        } else {
            content = content.concat("]");
        }
        return content.concat(imageContent).concat("[ImageEnd]\n");
    }


    default ExtractResult extract(ExtractConfig config, InputStream stream) {
        try {
            return doExtract(config, stream);
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
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return ImageResult.of(baos.toByteArray(), ImageResult.Format.PNG);
    }

    default ImageResult toImageResult(Picture pic) {
        byte[] content = pic.getContent();
        return ImageResult.of(content, pic.suggestPictureType());
    }

    default ImageResult toImageResult(XWPFPictureData pic) {
        byte[] content = pic.getData();
        return ImageResult.of(content, pic.getPictureTypeEnum());
    }
}
