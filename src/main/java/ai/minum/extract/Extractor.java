package ai.minum.extract;

import com.rometools.utils.Strings;
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
        if (!config.ocr()) {
            return "";
        }

        if (result.length() > config.imageExtractMaxSize()) {
            return "";
        }

        if (ImageResult.Format.UNKNOWN == result.getMimeType()) {
            return "";
        }

        String imageContent = config.getOcr().doOrc(result.getData());
        String content = "\n[Image";
        if (config.uploadImage() && config.imageUploader() != null) {
            String imageKey = config.imageUploader().upload(result);
            if (!Strings.isEmpty(imageKey)) {
                content.concat("](").concat(imageKey).concat(")");
            }
        }
        return content.concat(imageContent);
    }


    default ExtractResult extract(ExtractConfig config, InputStream stream) {
        try {
            return doExtract(config, stream);
        } catch (Exception e) {
            return ExtractResult.error(e);
        } finally {
            close(stream);
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
        ImageIO.write(image, "bmp", baos);
        return ImageResult.of(baos.toByteArray(), ImageResult.Format.BMP);
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
