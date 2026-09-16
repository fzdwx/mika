package ai.minum.extract;

import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.poi.hemf.usermodel.HemfPicture;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwmf.usermodel.HwmfPicture;
import org.apache.poi.xwpf.usermodel.XWPFPictureData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

public interface Extractor {
    final static Logger logger = LoggerFactory.getLogger(Extractor.class);

    boolean support(String mimeType);

    ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception;

    default String extractImage(ExtractConfig config, ImageResult image, ExtractResult result) throws Exception {
        return extractImage(config, image, result, true);
    }

    default String extractImage(ExtractConfig config, ImageResult image, ExtractResult result,
                                boolean useOcr) throws Exception {
        return extractImageContent(config, image, result, useOcr).firstPlacement();
    }

    default ExtractedImage extractImageContent(ExtractConfig config, ImageResult image,
                                               ExtractResult result, boolean useOcr) throws Exception {
        if (image.length() > config.imageExtractMaxSize()) {
            result.addWarning("Image size limit exceeded; an image was skipped");
            return new ExtractedImage("", "");
        }
        if (image.length() == 0 || image.getMimeType() == ImageResult.Format.UNKNOWN) {
            result.addWarning("Empty or unsupported image was skipped");
            return new ExtractedImage("", "");
        }
        if (overridesLegacyImageHook()) {
            return new ExtractedImage(extractImage(config, image), "[Image][ImageEnd]");
        }
        return processImage(config, image, useOcr, result);
    }

    private boolean overridesLegacyImageHook() {
        try {
            return getClass().getMethod("extractImage", ExtractConfig.class, ImageResult.class)
                    .getDeclaringClass() != Extractor.class;
        } catch (NoSuchMethodException impossible) {
            return false;
        }
    }

    default String extractImage(ExtractConfig config, ImageResult result) throws Exception {
        return processImage(config, result, true, null).firstPlacement();
    }

    private ExtractedImage processImage(ExtractConfig config, ImageResult result, boolean useOcr,
                                        ExtractResult extractionResult)
            throws Exception {
        if (result.length() > config.imageExtractMaxSize()) {
            return new ExtractedImage("", "");
        }
        if (result.getData().length == 0) {
            return new ExtractedImage("", "");
        }

        if (ImageResult.Format.UNKNOWN == result.getMimeType()) {
            return new ExtractedImage("", "");
        }

        String imageContent = "";
        if (config.ocr() && useOcr) {
            if (config.getOcr() == null) {
                throw new IllegalStateException("OCR is enabled but no OCR backend is configured");
            }
            ImageResult ocrImage = result;
            if (!config.ocrAccepts(result.getMimeType())) {
                try {
                    ocrImage = rasterizeForOcr(result);
                } catch (IOException | RuntimeException rasterizationFailure) {
                    ocrImage = null;
                    addRasterizationWarning(extractionResult, result, rasterizationFailure);
                } catch (AssertionError rasterizationFailure) {
                    ocrImage = null;
                    addRasterizationWarning(extractionResult, result, rasterizationFailure);
                }
            }
            if (ocrImage != null) {
                List<String> columnText = new java.util.ArrayList<>();
                for (ImageResult column : OcrImageQuality.readingOrderColumns(ocrImage)) {
                    String recognized = config.getOcr().recognize(
                            column.getData(), column.getMimeType().getMimeType());
                    recognized = OcrImageQuality.cleanText(recognized);
                    if (!recognized.isBlank()) {
                        columnText.add(recognized);
                    }
                }
                imageContent = String.join("\n\n", columnText);
            }
        }

        String imageKey = "";
        if (config.uploadImage()) {
            if (config.imageUploader() == null) {
                throw new IllegalStateException("Image upload is enabled but no uploader is configured");
            }
            imageKey = config.imageUploader().upload(result);
        }
        return new ExtractedImage(Markdown.image(imageKey, imageContent), Markdown.image(imageKey, ""));
    }

    private static void addRasterizationWarning(ExtractResult extractionResult, ImageResult image,
                                                Throwable failure) throws IOException {
        if (extractionResult == null) {
            if (failure instanceof IOException io) {
                throw io;
            }
            throw new IOException("Cannot rasterize " + image.getMimeType().getMimeType(), failure);
        }
        String message = failure.getMessage();
        if (message != null && message.length() > 160) {
            message = message.substring(0, 160);
        }
        extractionResult.addWarning("OCR skipped for " + image.getMimeType().getMimeType()
                + " because it could not be rasterized"
                + (message == null || message.isBlank() ? "" : ": " + message));
    }

    private static ImageResult rasterizeForOcr(ImageResult source) throws IOException {
        BufferedImage image = switch (source.getMimeType().rasterization()) {
            case WMF -> drawMetafile(new HwmfPicture(new ByteArrayInputStream(source.getData())));
            case EMF -> drawMetafile(new HemfPicture(new ByteArrayInputStream(source.getData())));
            case IMAGE_IO -> ImageIO.read(new ByteArrayInputStream(source.getData()));
            case UNSUPPORTED -> null;
        };
        if (image == null) {
            throw new IOException("OCR backend does not accept " + source.getMimeType().getMimeType()
                    + " and the image cannot be rasterized to PNG");
        }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", bytes)) {
            throw new IOException("No PNG writer is available for OCR image conversion");
        }
        return ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG);
    }

    private static BufferedImage drawMetafile(HwmfPicture picture) {
        return drawMetafile(picture.getBoundsInPoints(), picture::draw);
    }

    private static BufferedImage drawMetafile(HemfPicture picture) {
        return drawMetafile(picture.getBoundsInPoints(), picture::draw);
    }

    private static BufferedImage drawMetafile(
            Rectangle2D bounds, java.util.function.BiConsumer<Graphics2D, Rectangle2D> renderer) {
        double widthPoints = Math.max(1, Math.abs(bounds.getWidth()));
        double heightPoints = Math.max(1, Math.abs(bounds.getHeight()));
        double scale = Math.min(2.0, Math.min(4096.0 / widthPoints, 4096.0 / heightPoints));
        int width = Math.max(1, (int) Math.ceil(widthPoints * scale));
        int height = Math.max(1, (int) Math.ceil(heightPoints * scale));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            renderer.accept(graphics, new Rectangle2D.Double(0, 0, width, height));
        } finally {
            graphics.dispose();
        }
        return image;
    }


    default ExtractResult extract(ExtractConfig config, InputStream stream) {
        ExtractConfig extractionConfig = config.copyForExtraction();
        SizeLimitedInputStream limited = new SizeLimitedInputStream(stream, extractionConfig.maxExtractInputSize());
        try {
            ExtractResult result = doExtract(extractionConfig, limited);
            limited.verifyExhausted();
            verifyExtractedContentSize(result, extractionConfig.maxExtractedContentSize());
            return result;
        } catch (Exception e) {
            return ExtractResult.error(e);
        }
    }

    private static void verifyExtractedContentSize(ExtractResult result, int limit) throws IOException {
        if (result == null || result.isError() || result.getPages() == null) {
            return;
        }
        long bytes = 0;
        boolean hasContent = false;
        for (ExtractPage page : result.getPages()) {
            if (page == null || page.getContent() == null || page.getContent().isBlank()) {
                continue;
            }
            if (hasContent) {
                bytes += 2; // getMarkdown() joins non-blank physical pages with two line feeds.
            }
            bytes += page.getContent().getBytes(StandardCharsets.UTF_8).length;
            hasContent = true;
            if (bytes > limit) {
                throw new IOException("Extracted content size limit exceeded: " + limit + " bytes");
            }
        }
    }

    default void close(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignore) {
        }
    }

    default ImageResult toImageResult(PDImageXObject img) throws IOException {
        String suffix = img.getSuffix();
        if ("jpx".equalsIgnoreCase(suffix) || "jp2".equalsIgnoreCase(suffix)) {
            // Preserve the JP2 codestream and stop PDFBox before JPXDecode. This keeps full-page
            // scans small and lets OCR/upload backends that support JPEG 2000 handle them without
            // requiring a license-sensitive ImageIO codec in Mika's fat jar.
            try (InputStream input = img.createInputStream(List.of(COSName.JPX_DECODE.getName()))) {
                return ImageResult.of(input.readAllBytes(), ImageResult.Format.JPEG2000);
            }
        }
        if ("jpg".equalsIgnoreCase(suffix) || "jpeg".equalsIgnoreCase(suffix)) {
            // Keep DCT-compressed scans in their original representation. Decoding a full-page JPEG and
            // re-encoding it as PNG can inflate a 1 MiB source to tens of MiB and trip the configured
            // upload/OCR limit even though the source image itself is within budget.
            try (InputStream input = img.createInputStream(List.of(
                    COSName.DCT_DECODE.getName(), COSName.DCT_DECODE_ABBREVIATION.getName()))) {
                return ImageResult.of(input.readAllBytes(), ImageResult.Format.JPEG);
            }
        }
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
        return ImageResult.of(content, pic.getPictureTypeEnum());
    }

    default boolean checkRatio(int a, int b) {
        int max = Math.max(a, b);
        int min = Math.min(a, b);
        return min <= 0 || (double) max / min > 8;
    }

    record ExtractedImage(String firstPlacement, String repeatedPlacement) {
    }
}
