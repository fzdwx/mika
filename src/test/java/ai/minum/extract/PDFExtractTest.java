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
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureElement;
import org.apache.pdfbox.pdmodel.documentinterchange.logicalstructure.PDStructureTreeRoot;
import org.apache.pdfbox.pdmodel.documentinterchange.taggedpdf.StandardStructureTypes;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDInlineImage;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.interactive.form.PDAcroForm;
import org.apache.pdfbox.pdmodel.interactive.form.PDTextField;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
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
    void identifiesCharacterWiseExtractionWithoutFlaggingNormalParagraphs() {
        assertTrue(PDFExtract.isFragmentedExtraction("a\np\na\nc\nh\ne\n".repeat(5)));
        assertTrue(PDFExtract.isFragmentedExtraction(
                "A normal paragraph with enough text on every line.\n".repeat(10)
                        + "成\n都\n市\n".repeat(12)));
        assertFalse(PDFExtract.isFragmentedExtraction(
                "A normal paragraph with enough text on every line.\n".repeat(30)));
    }

    /** Apache Tika test-documents/testPDF_rotated.pdf (Apache-2.0). */
    @Test
    void positionSortingRepairsRotatedTextMatrices() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/tika-rotated.pdf")) {
            assertTrue(input != null);
            ExtractResult result = Mika.extract("pdf", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Apache Tika is a toolkit for detecting"),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().lines().count() < 50, result.getMarkdown());
        }
    }

    /** Apache PDFBox PDFBOX-5747 reduced fixture (Apache-2.0). */
    @Test
    void repairsSupplementaryCharactersSplitByCombiningMarks() throws Exception {
        try (var input = getClass().getResourceAsStream(
                "/documents/pdfbox-unicode-surrogate-diacritic.pdf")) {
            assertTrue(input != null);

            ExtractResult result = Mika.extract("pdf", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals("\uD835\uDC4B\u0302", result.getMarkdown());
            assertEquals(6, result.getMarkdown().getBytes(StandardCharsets.UTF_8).length);
            assertEquals("before\uFFFDmiddle\uFFFDafter",
                    PDFExtract.normalizeExtractedUnicode("before\uD835middle\uDC4Bafter"));
        }
    }

    @Test
    void usesActualTextStoredInThePdfStructureTree() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDStructureTreeRoot root = new PDStructureTreeRoot();
            document.getDocumentCatalog().setStructureTreeRoot(root);
            PDStructureElement documentElement = new PDStructureElement(
                    StandardStructureTypes.DOCUMENT, root);
            root.appendKid(documentElement);
            PDStructureElement span = new PDStructureElement(StandardStructureTypes.SPAN, documentElement);
            span.setPage(page);
            span.setActualText("Available 24/7, you can book an appointment online.");
            span.appendKid(17);
            documentElement.appendKid(span);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginMarkedContent(COSName.getPDFName("Span"), 17);
                writeText(content, "Availal;!e 24/7, you c;m book an appointroP.nt online.", 72, 720);
                content.endMarkedContent();
            }
            document.save(output);
            pdf = output.toByteArray();
        }

        ExtractResult result = Mika.extract("pdf", new ByteArrayInputStream(pdf),
                ExtractConfig.defaultConfig());

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains(
                "Available 24/7, you can book an appointment online."), result.getMarkdown());
        assertFalse(result.getMarkdown().contains("Availal;!e"), result.getMarkdown());
    }

    @Test
    void includesInteractiveFormValuesAndRemovesControlCharacters() throws Exception {
        byte[] pdf;
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            PDAcroForm form = new PDAcroForm(document);
            document.getDocumentCatalog().setAcroForm(form);
            PDTextField field = new PDTextField(form);
            field.setPartialName("Applicant");
            field.getCOSObject().setString(COSName.V, "Alice\u0001 Zhang\u2028Shanghai");
            field.getWidgets().getFirst().setPage(page);
            page.getAnnotations().add(field.getWidgets().getFirst());
            form.setFields(List.of(field));
            document.save(output);
            pdf = output.toByteArray();
        }

        ExtractResult result = Mika.extract("pdf", new ByteArrayInputStream(pdf), ExtractConfig.defaultConfig());

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("### Form fields"), result.getMarkdown());
        assertTrue(result.getMarkdown().contains("- Applicant: Alice Zhang\n  Shanghai"), result.getMarkdown());
        assertFalse(result.getMarkdown().contains("\u0001"), result.getMarkdown());

        ExtractResult limited = Mika.extract("pdf", new ByteArrayInputStream(pdf),
                ExtractConfig.defaultConfig().maxExtractedContentSize(16));
        assertTrue(limited.isError());
        assertTrue(limited.getErrorMessage().contains("Extracted content size limit exceeded"),
                limited.getErrorMessage());
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

    /** Apache Tika test-documents/testPDF_jpeg2000.pdf (Apache-2.0). */
    @Test
    void keepsJpeg2000ScansCompressedForOcrAndUploadBackends() throws Exception {
        AtomicReference<ImageResult> uploaded = new AtomicReference<>();
        try (var input = getClass().getResourceAsStream("/documents/tika-jpeg2000.pdf")) {
            assertTrue(input != null);
            ExtractResult result = Mika.extract("pdf", input, ExtractConfig.defaultConfig()
                    .imageUploader(image -> {
                        uploaded.set(image);
                        return "images/scan.jp2";
                    }));

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals(ImageResult.Format.JPEG2000, uploaded.get().getMimeType());
            assertArrayEquals(new byte[]{0, 0, 0, 12, 106, 80, 32, 32},
                    Arrays.copyOf(uploaded.get().getData(), 8));
            assertTrue(result.getMarkdown().contains("[Image](images/scan.jp2)[ImageEnd]"),
                    result.getMarkdown());
            assertTrue(result.hasImage());
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
