package ai.minum.extract;

import ai.minum.Mika;
import org.apache.pdfbox.cos.COSDictionary;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDFormContentStream;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class PDFExtractTest {

    private static final String COLOR_SPACE_ERROR =
            "Numbers of source Raster bands and source color space components do not match";

    @Test
    void keepsDocumentReadingOrderInsteadOfInterleavingVisualColumns() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                writeText(content, "left first", 72, 720);
                writeText(content, "left second", 72, 700);
                writeText(content, "right first", 300, 720);
                writeText(content, "right second", 300, 700);
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        String markdown = Mika.extract("pdf", new ByteArrayInputStream(pdf), ExtractConfig.defaultConfig())
                .getMarkdown();

        assertTrue(markdown.indexOf("left second") < markdown.indexOf("right first"), markdown);
    }

    @Test
    void identifiesPagesThatNeedRightToLeftPositionSorting() {
        assertTrue(PDFExtract.isPredominantlyRightToLeft(
                "تقرير اقتصادي اجتماعي عن الأراضي الفلسطينية المحتلة"));
        assertFalse(PDFExtract.isPredominantlyRightToLeft(
                "English paragraph with only مثال عربي embedded"));
    }

    @Test
    void keepsJpegScansCompressedForOcrAndUploadLimits() throws Exception {
        BufferedImage bufferedImage = new BufferedImage(40, 40, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream jpeg = new ByteArrayOutputStream();
        ImageIO.write(bufferedImage, "jpeg", jpeg);
        try (PDDocument document = new PDDocument()) {
            PDImageXObject image = PDImageXObject.createFromByteArray(document, jpeg.toByteArray(), "scan.jpg");

            ImageResult result = new PDFExtract().toImageResult(image);

            assertEquals(ImageResult.Format.JPEG, result.getMimeType());
            assertArrayEquals(jpeg.toByteArray(), result.getData());
        }
    }

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
                ExtractConfig.defaultConfig().imageUploader(image -> "image.png"),
                new ByteArrayInputStream(pdf)
        );

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.hasImage());
        assertTrue(result.getPages().getFirst().getContent().contains("kept text"));
        assertEquals(1, result.getWarnings().size());
    }

    @Test
    void doesNotDecodeImagesWhenOnlyExtractingText() throws Exception {
        PDFExtract extractor = new PDFExtract() {
            @Override
            public ImageResult toImageResult(PDImageXObject image) {
                fail("Text-only extraction must not decode images");
                return null;
            }
        };
        ExtractResult result = extractor.extract(ExtractConfig.defaultConfig(),
                new ByteArrayInputStream(createPdfWithTextAndImage()));
        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.hasImage());
        assertTrue(result.getMarkdown().contains("kept text"));
    }

    @Test
    void findsNestedAndInlineImagesAndRetainsBlankPhysicalPages() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            document.addPage(new PDPage());
            PDImageXObject image = LosslessFactory.createFromImage(document,
                    new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB));
            PDFormXObject inner = new PDFormXObject(document);
            inner.setBBox(new PDRectangle(100, 100));
            inner.setResources(new PDResources());
            try (PDFormContentStream content = new PDFormContentStream(inner)) {
                content.drawImage(image, 0, 0, 20, 20);
            }
            PDFormXObject outer = new PDFormXObject(document);
            outer.setBBox(new PDRectangle(100, 100));
            outer.setResources(new PDResources());
            try (PDFormContentStream content = new PDFormContentStream(outer)) {
                content.drawForm(inner);
            }
            COSDictionary dictionary = new COSDictionary();
            dictionary.setInt(COSName.W, 1);
            dictionary.setInt(COSName.H, 1);
            dictionary.setInt(COSName.BPC, 8);
            dictionary.setItem(COSName.CS, COSName.RGB);
            PDInlineImage inline = new PDInlineImage(dictionary, new byte[]{0, 0, 0}, new PDResources());
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.drawForm(outer);
                content.drawForm(outer); // Shared images are processed once per page.
                content.drawImage(inline, 100, 100, 20, 20);
            }
            document.save(output);
            pdf = output.toByteArray();
        }
        AtomicInteger uploads = new AtomicInteger();
        ExtractResult result = Mika.extract("application/pdf", new ByteArrayInputStream(pdf),
                ExtractConfig.defaultConfig().imageUploader(image -> "images/" + uploads.incrementAndGet() + ".png"));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(2, uploads.get());
        assertTrue(result.getMarkdown().contains("[Image](images/1.png)[ImageEnd]"), result.getMarkdown());
        assertTrue(result.hasImage());
        assertFalse(result.hasTable(), "A Form XObject is not evidence of a table");
        assertEquals(2, result.getPages().size());
        assertEquals(1L, result.getPages().get(1).getPage());
        assertTrue(result.getPages().get(1).getContent().isEmpty());
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

    private static void writeText(PDPageContentStream content, String text, float x, float y) throws Exception {
        content.beginText();
        content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
        content.newLineAtOffset(x, y);
        content.showText(text);
        content.endText();
    }
}
