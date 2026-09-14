package ai.minum.extract;

import ai.minum.Mika;
import com.vladsch.flexmark.ext.tables.TablesExtension;
import com.vladsch.flexmark.html.HtmlRenderer;
import com.vladsch.flexmark.parser.Parser;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.junit.jupiter.api.Test;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficeMarkdownTest {

    /** Apache POI test-data/document/chartex.docx (Apache-2.0). */
    @Test
    void extractsModernChartExWithTheSaxDocxParser() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-chartex.docx")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("docx", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("This is a stock chart"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("this is a box and whisker chart"), result.getMarkdown());
        }
    }

    /** Apache POI test-data/document/word2.doc (Apache-2.0). */
    @Test
    void extractsLegacyWord2Document() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-word2.doc")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("doc", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Member of 3GPP (ARIB)"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Mr. Frédéric Bonneau"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("| Mr. Benni Alexander | Nokia Japan Co, Ltd |"),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Organisation partner representative (ETSI)"),
                    result.getMarkdown());
            assertFalse(result.getMarkdown().contains("�"), result.getMarkdown());
            assertTrue(result.hasTable());
            assertTrue(render(result.getMarkdown()).contains("<table>"), result.getMarkdown());
        }
    }

    /** Apache Tika test-documents/testWORD_features.doc (Apache-2.0). */
    @Test
    void legacyDocCommentsDoNotExposeWordAnnotationControlMarkers() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/tika-word-features.doc")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("doc", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("This is another comment"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("This is a comment"), result.getMarkdown());
            assertFalse(result.getMarkdown().contains("�"), result.getMarkdown());
        }
    }

    /** Apache POI test-data/document/test-fields.doc (Apache-2.0). */
    @Test
    void legacyDocFieldsKeepDisplayedResultsWithoutControlMarkers() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-fields.doc")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("doc", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Field in text box: 2"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Footnote with field: Fridrich Strba"),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Field in comment: 19/11/2010"),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Field in EndNote. File size: 0"),
                    result.getMarkdown());
            assertFalse(result.getMarkdown().contains("MERGEFORMAT"), result.getMarkdown());
            assertFalse(result.getMarkdown().contains("�"), result.getMarkdown());
        }
    }

    @Test
    void docxIncludesHtmlAltChunkBodyWithoutSyntheticPartName() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/tika-altchunk-html.docx")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("docx", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Simple paragraph with a emphasized word."),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().contains("| Col 1 | Col 2 |"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("| ROW 2 | ROW 2 |"), result.getMarkdown());
            assertFalse(result.getMarkdown().contains("htmlDoc.html"), result.getMarkdown());
            assertTrue(result.hasTable());
        }
    }

    @Test
    void docxIncludesMhtmlAltChunkAndProcessesReferencedImage() throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        ExtractConfig config = ExtractConfig.defaultConfig()
                .imageUploader(image -> {
                    uploads.incrementAndGet();
                    return "images/dot.png";
                })
                .maxHandleImageCount(5L);
        try (var input = getClass().getResourceAsStream("/documents/tika-altchunk-mht.docx")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("docx", input, config);

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("Simple paragraph with a emphasized word."),
                    result.getMarkdown());
            assertTrue(result.getMarkdown().contains("Red PNG dot; ROW 2"), result.getMarkdown());
            assertEquals(1, uploads.get(), "Repeated MHTML image references share one MIME part");
            assertEquals(2, count(result.getMarkdown(), "[Image](images/dot.png)[ImageEnd]"),
                    result.getMarkdown());
            assertFalse(result.getMarkdown().contains("htmlDoc.mht"), result.getMarkdown());
            assertTrue(result.hasTable());
            assertTrue(result.hasImage());
        }
    }

    @Test
    void docxPreservesHeadingsTableBoundariesAndFollowingParagraph() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            CTStyle style = CTStyle.Factory.newInstance();
            style.setStyleId("Heading1");
            style.addNewName().setVal("heading 1");
            document.createStyles().addStyle(new XWPFStyle(style));
            XWPFParagraph title = document.createParagraph();
            title.setStyle("Heading1");
            title.createRun().setText("操作说明");
            XWPFTable table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("项目");
            table.getRow(0).getCell(1).setText("数值");
            table.getRow(1).getCell(0).setText("温度");
            table.getRow(1).getCell(1).setText("20 °C");
            document.createParagraph().createRun().setText("表格之后的正文");
            ExtractResult result = extract(document, ExtractConfig.defaultConfig());
            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.contains("# 操作说明"), markdown);
            assertTrue(markdown.contains("|"), markdown);
            assertTrue(render(markdown).contains("<table>"), markdown);
            assertTrue(markdown.contains("温度") && markdown.contains("20 °C"), markdown);
            assertTrue(markdown.indexOf("表格之后") > markdown.indexOf("20 °C"), markdown);
            assertTrue(result.hasTable());
        }
    }

    @Test
    void docxRetainsNestedTableAndCellImageWithoutRequiringOcr() throws Exception {
        byte[] png = png();
        AtomicInteger uploads = new AtomicInteger();
        ExtractConfig config = ExtractConfig.defaultConfig().maxHandleImageCount(1L)
                .imageUploader(image -> { uploads.incrementAndGet(); return "images/test.png"; });
        byte[] bytes;
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XWPFTable outer = document.createTable(1, 1);
            XWPFTableCell cell = outer.getRow(0).getCell(0);
            cell.setText("外层正文");
            CTTbl nested = cell.getCTTc().addNewTbl();
            nested.addNewTr().addNewTc().addNewP().addNewR().addNewT().setStringValue("嵌套内容");
            cell.getParagraphs().getFirst().createRun().addPicture(new ByteArrayInputStream(png),
                    Document.PICTURE_TYPE_PNG, "test.png", Units.toEMU(20), Units.toEMU(20));
            document.write(output);
            bytes = output.toByteArray();
        }
        for (int attempt = 0; attempt < 2; attempt++) {
            ExtractResult result = Mika.extract("docx", new ByteArrayInputStream(bytes), config);
            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.contains("外层正文") && markdown.contains("嵌套内容"), markdown);
            assertTrue(markdown.contains("<table>"), markdown);
            assertTrue(markdown.contains("images/test.png"), markdown);
            assertFalse(markdown.contains("embedded:"), markdown);
            assertTrue(markdown.contains("[Image](images/test.png)[ImageEnd]"), markdown);
            assertTrue(result.hasImage());
        }
        assertEquals(2, uploads.get(), "The config image budget must reset for each extraction");
    }

    @Test
    void docxProcessesOnlyImagesReferencedByTheDocumentBody() throws Exception {
        byte[] png = png();
        AtomicInteger uploads = new AtomicInteger();
        ExtractConfig config = ExtractConfig.defaultConfig().maxHandleImageCount(10L)
                .imageUploader(image -> "images/" + uploads.incrementAndGet() + ".png");
        try (XWPFDocument document = new XWPFDocument()) {
            document.addPictureData(png, Document.PICTURE_TYPE_PNG);
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png),
                    Document.PICTURE_TYPE_PNG, "referenced.png", Units.toEMU(20), Units.toEMU(20));

            ExtractResult result = extract(document, config);

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals(1, uploads.get(), "Unused package media must not consume upload/OCR work");
            assertEquals(1, count(result.getMarkdown(), "[Image](images/1.png)[ImageEnd]"));
        }
    }

    @Test
    void docxImageLimitSkipsBodyImageBeforeUpload() throws Exception {
        AtomicInteger uploads = new AtomicInteger();
        ExtractConfig config = ExtractConfig.defaultConfig().maxHandleImageCount(0L)
                .imageUploader(image -> "images/" + uploads.incrementAndGet() + ".png");
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG, "limited.png", Units.toEMU(20), Units.toEMU(20));

            ExtractResult result = extract(document, config);

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals(0, uploads.get());
            assertFalse(result.getMarkdown().contains("[Image]"), result.getMarkdown());
            assertFalse(result.getMarkdown().contains("limited.png"), result.getMarkdown());
            assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("count limit")));
        }
    }

    @Test
    void docxInputLimitFailsBeforeImageProcessing() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG, "large-document.png", Units.toEMU(20), Units.toEMU(20));

            ExtractResult result = extract(document, ExtractConfig.defaultConfig()
                    .maxExtractInputSize(1)
                    .imageUploader(image -> "unused"));

            assertTrue(result.isError());
            assertTrue(result.getErrorMessage().contains("File size limit exceeded"), result.getErrorMessage());
        }
    }

    @Test
    void docxInputLimitAlsoAppliesWithoutImageFeatures() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText("普通正文");

            ExtractResult result = extract(document, ExtractConfig.defaultConfig().maxExtractInputSize(1));

            assertTrue(result.isError());
            assertTrue(result.getErrorMessage().contains("File size limit exceeded"), result.getErrorMessage());
        }
    }

    @Test
    void docxImageLimitFollowsBodyOrderInsteadOfPackageOrder() throws Exception {
        byte[] later = png(0xFF0000);
        byte[] earlier = png(0x0000FF);
        AtomicReference<byte[]> uploaded = new AtomicReference<>();
        ExtractConfig config = ExtractConfig.defaultConfig().maxHandleImageCount(1L)
                .imageUploader(image -> {
                    uploaded.set(image.getData());
                    return "images/first.png";
                });
        try (XWPFDocument document = new XWPFDocument()) {
            document.addPictureData(later, Document.PICTURE_TYPE_PNG);
            document.addPictureData(earlier, Document.PICTURE_TYPE_PNG);
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(earlier),
                    Document.PICTURE_TYPE_PNG, "earlier.png", Units.toEMU(20), Units.toEMU(20));
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(later),
                    Document.PICTURE_TYPE_PNG, "later.png", Units.toEMU(20), Units.toEMU(20));

            ExtractResult result = extract(document, config);

            assertFalse(result.isError(), result.getErrorMessage());
            assertArrayEquals(earlier, uploaded.get());
            assertEquals(1, count(result.getMarkdown(), "[Image](images/first.png)[ImageEnd]"));
        }
    }

    @Test
    void docxImageUploadFailureRemainsAnExtractionError() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG, "image.png", Units.toEMU(20), Units.toEMU(20));
            ExtractResult result = extract(document, ExtractConfig.defaultConfig().imageUploader(image -> {
                throw new IllegalStateException("backend unavailable");
            }));
            assertTrue(result.isError(), "Image backend errors must not turn into silent content loss");
            assertTrue(result.getErrorMessage().contains("backend unavailable"), result.getErrorMessage());
        }
    }

    @Test
    void imagePlaceholderCannotReplaceDocumentText() throws Exception {
        String literal = "MIKAIMAGEMARKER0TOKEN";
        try (XWPFDocument document = new XWPFDocument()) {
            document.createParagraph().createRun().setText(literal);
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG, "image.png", Units.toEMU(20), Units.toEMU(20));
            ExtractResult result = extract(document,
                    ExtractConfig.defaultConfig().imageUploader(image -> "images/test.png"));

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains(literal), result.getMarkdown());
            assertEquals(1, count(result.getMarkdown(), "[Image](images/test.png)[ImageEnd]"));
        }
    }

    @Test
    void complexTableHtmlIsSanitized() {
        String markdown = Markdown.fromHtml("""
                <table onclick="alert(1)"><tr><td rowspan="2" onmouseover="alert(2)">
                <a href="javascript:alert(3)">链接</a><img src="x" onerror="alert(4)">
                </td></tr><tr></tr></table>
                """);

        assertTrue(markdown.contains("<table>"), markdown);
        assertTrue(markdown.contains("rowspan=\"2\""), markdown);
        assertTrue(markdown.contains("链接"), markdown);
        assertFalse(markdown.contains("javascript:"), markdown);
        assertFalse(markdown.contains("onmouseover"), markdown);
        assertFalse(markdown.contains("<img"), markdown);
    }

    @Test
    void officeLayoutBreaksDoNotPolluteMarkdown() {
        String markdown = Markdown.fromHtml("<p>第一段<br></p><p><br></p><p>第二段<br>仍是第二段</p>");

        assertFalse(markdown.startsWith("<br"), markdown);
        assertFalse(markdown.contains("第一段<br"), markdown);
        assertTrue(markdown.contains("第一段\n\n第二段"), markdown);
        assertTrue(markdown.contains("第二段  \n仍是第二段"), markdown);
    }

    @Test
    void simpleHtmlLinksAndImagesRejectExecutableSchemes() {
        String markdown = Markdown.fromHtml("""
                <p><a href="java&#x0A;script:alert(1)">危险链接</a>
                <img src="javascript:alert(2)" alt="图片说明">
                <a href="https://example.com/page">正常链接</a></p>
                """);

        assertFalse(markdown.toLowerCase().contains("javascript:"), markdown);
        assertTrue(markdown.contains("危险链接"), markdown);
        assertTrue(markdown.contains("图片说明"), markdown);
        assertTrue(markdown.contains("https://example.com/page"), markdown);
    }

    @Test
    void ocrTextCannotInjectHtmlWhenImageIsInsideComplexTable() {
        String image = Markdown.image("", "<img src=x onerror=alert(1)>");
        String markdown = Markdown.fromHtml("""
                <table><tr><td rowspan="2">IMAGE_TOKEN</td></tr><tr></tr></table>
                """).replace("IMAGE_TOKEN", image);

        String rendered = render(markdown);
        assertTrue(rendered.contains("[Image]"), rendered);
        assertTrue(org.jsoup.Jsoup.parse(rendered).select("img").isEmpty(), rendered);
    }

    @Test
    void xlsxPreservesSheetsAndRowsAsMarkdown() throws Exception {
        try (XSSFWorkbook workbook = new XSSFWorkbook(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet("库存");
            sheet.createRow(0).createCell(0).setCellValue("名称");
            sheet.getRow(0).createCell(1).setCellValue("数量");
            sheet.createRow(1).createCell(0).setCellValue("电机");
            sheet.getRow(1).createCell(1).setCellValue(3);
            workbook.createSheet("备注").createRow(0).createCell(0).setCellValue("校验完成");
            workbook.write(output);
            ExtractResult result = Mika.extract("xlsx", new ByteArrayInputStream(output.toByteArray()), ExtractConfig.defaultConfig());
            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.contains("库存") && markdown.contains("校验完成"), markdown);
            assertTrue(markdown.contains("电机") && markdown.contains("|"), markdown);
            assertTrue(render(markdown).contains("<table>"), markdown);
            assertTrue(result.hasTable());
        }
    }

    @Test
    void pptxPreservesTextFromEverySlide() throws Exception {
        try (XMLSlideShow presentation = new XMLSlideShow(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            XSLFSlide first = presentation.createSlide();
            first.createTextBox().setText("第一张幻灯片");
            presentation.createSlide().createTextBox().setText("第二张幻灯片");
            presentation.write(output);
            ExtractResult result = Mika.extract("pptx", new ByteArrayInputStream(output.toByteArray()), ExtractConfig.defaultConfig());
            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("第一张幻灯片"), result.getMarkdown());
            assertTrue(result.getMarkdown().contains("第二张幻灯片"), result.getMarkdown());
        }
    }

    private static ExtractResult extract(XWPFDocument document, ExtractConfig config) throws Exception {
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.write(output);
            return Mika.extract("Application/Vnd.Openxmlformats-Officedocument.Wordprocessingml.Document",
                    new ByteArrayInputStream(output.toByteArray()), config);
        }
    }

    static byte[] png() throws Exception {
        return png(0);
    }

    private static byte[] png(int rgb) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        BufferedImage image = new BufferedImage(20, 20, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, rgb);
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }

    private static String render(String markdown) {
        var extensions = List.of(TablesExtension.create());
        return HtmlRenderer.builder().extensions(extensions).build().render(
                Parser.builder().extensions(extensions).build().parse(markdown));
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
