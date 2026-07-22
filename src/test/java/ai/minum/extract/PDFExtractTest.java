package ai.minum.extract;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.junit.jupiter.api.Test;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PDFExtractTest {

    private static final String COLOR_SPACE_ERROR =
            "Numbers of source Raster bands and source color space components do not match";

    @Test
    void skipsAnImageThatCannotBeDecodedAndKeepsThePageText() throws Exception {
        byte[] pdf = createPdfWithTextAndImage();
        PDFExtract extractor = new PDFExtract() {
            @Override
            public ImageResult toImageResult(PDImageXObject image) {
                throw new IllegalArgumentException(COLOR_SPACE_ERROR);
            }
        };

        ExtractResult result = extractor.extract(
                ExtractConfig.defaultConfig().ocr(false),
                new ByteArrayInputStream(pdf)
        );

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.hasImage());
        assertTrue(result.getPages().getFirst().getContent().contains("kept text"));
    }

    @Test
    void doesNotHideImagePostProcessingFailures() throws Exception {
        byte[] pdf = createPdfWithTextAndImage();
        PDFExtract extractor = new PDFExtract() {
            @Override
            public ImageResult toImageResult(PDImageXObject image) {
                return ImageResult.of(new byte[]{1}, ImageResult.Format.PNG);
            }

            @Override
            public String extractImage(ExtractConfig config, ImageResult result) {
                throw new IllegalStateException("image post-processing failed");
            }
        };

        ExtractResult result = extractor.extract(
                ExtractConfig.defaultConfig().ocr(true),
                new ByteArrayInputStream(pdf)
        );

        assertTrue(result.isError());
        assertEquals("image post-processing failed", result.getErrorMessage());
    }

    private static byte[] createPdfWithTextAndImage() throws Exception {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);

            BufferedImage bufferedImage = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
            PDImageXObject image = LosslessFactory.createFromImage(document, bufferedImage);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText("kept text");
                content.endText();
                content.drawImage(image, 72, 650, 20, 20);
            }

            document.save(output);
            return output.toByteArray();
        }
    }
}
