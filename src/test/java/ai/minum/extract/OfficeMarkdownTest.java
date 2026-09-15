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
import org.apache.tika.metadata.Metadata;
import org.junit.jupiter.api.Test;
import org.jsoup.Jsoup;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTStyle;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTTbl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STMerge;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class OfficeMarkdownTest {
    @Test
    void selfClosingTikaInlineTagDoesNotWrapFollowingParagraphs() {
        var document = TikaExtractor.parseTikaXhtml(
                "<html xmlns='http://www.w3.org/1999/xhtml'><body>"
                        + "<p><b /></p><p>first paragraph</p><p>second paragraph</p>"
                        + "</body></html>");

        assertTrue(document.select("b").first().text().isEmpty());
        assertTrue(document.select("b p").isEmpty());
        assertEquals("first paragraph\n\nsecond paragraph", Markdown.fromHtml(document.body().html()));
    }


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

    /** LibreOffice sw/qa/extras/ooxmlexport/data/CommentReply.docx (MPL-2.0). */
    @Test
    void docxCommentsKeepAuthorAnchorAndReplyHierarchy() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/libreoffice-comment-reply.docx")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("docx", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.startsWith("text\n\n### Comments"), markdown);
            assertTrue(markdown.contains("**Egor** on “text” (2018-10-22T17:50:00Z): Parent"), markdown);
            assertTrue(markdown.contains("**Egor** on “text” (2018-10-22T17:51:00Z): Child"), markdown);
            assertTrue(markdown.indexOf("Parent") < markdown.indexOf("Child"), markdown);
            assertEquals(1, count(markdown, "Parent"), markdown);
            assertEquals(1, count(markdown, "Child"), markdown);
        }
    }

    /** Apache POI test-data/document/comment.docx (Apache-2.0). */
    @Test
    void commentsOnlyDocxDoesNotPresentReviewTextAsOrdinaryBodyParagraphs() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-comment.docx")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("docx", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.startsWith("### Comments"), markdown);
            assertTrue(markdown.contains("**Unbekannter Autor** (2019-10-11T05:43:39Z): This is the first line"),
                    markdown);
            assertTrue(markdown.contains("This is the second line"), markdown);
            assertEquals(1, count(markdown, "This is the first line"), markdown);
            assertEquals(1, count(markdown, "This is the second line"), markdown);
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

    /** Apache POI test-data/document/Picture_Alternative_Text.doc (Apache-2.0). */
    @Test
    void legacyDocKeepsAuthorProvidedImageDescriptionWithoutOcr() throws Exception {
        try (var input = getClass().getResourceAsStream("/documents/poi-picture-alt.doc")) {
            assertNotNull(input);
            ExtractResult result = Mika.extract("doc", input, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals("[Image]This is the alternative text for the picture.[ImageEnd]",
                    result.getMarkdown());
            assertTrue(result.hasImage());
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
            assertEquals(2, count(result.getMarkdown(), "[Image](images/dot.png)Red dot[ImageEnd]"),
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
    void docxRestoresHorizontalAndVerticalMergedCells() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(3, 3);
            table.getRow(0).getCell(0).setText("合并标题");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewGridSpan()
                    .setVal(BigInteger.valueOf(2));
            table.getRow(0).removeCell(1);
            table.getRow(0).getCell(1).setText("第三列");

            table.getRow(1).getCell(0).setText("纵向分类");
            table.getRow(1).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.RESTART);
            table.getRow(1).getCell(1).setText("甲");
            table.getRow(1).getCell(2).setText("1");
            table.getRow(2).getCell(0).setText("");
            table.getRow(2).getCell(0).getCTTc().addNewTcPr().addNewVMerge().setVal(STMerge.CONTINUE);
            table.getRow(2).getCell(1).setText("乙");
            table.getRow(2).getCell(2).setText("2");

            XWPFTable legacyMerge = document.createTable(1, 3);
            legacyMerge.getRow(0).getCell(0).setText("旧式横向合并");
            legacyMerge.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.RESTART);
            legacyMerge.getRow(0).getCell(1).setText("");
            legacyMerge.getRow(0).getCell(1).getCTTc().addNewTcPr().addNewHMerge().setVal(STMerge.CONTINUE);
            legacyMerge.getRow(0).getCell(2).setText("末列");

            ExtractResult result = extract(document, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            String markdown = result.getMarkdown();
            assertTrue(markdown.contains("<table>"), markdown);
            assertTrue(markdown.contains("colspan=\"2\""), markdown);
            assertTrue(markdown.contains("rowspan=\"2\""), markdown);
            assertEquals(1, count(markdown, "纵向分类"), markdown);
            assertTrue(markdown.contains("甲") && markdown.contains("乙")
                    && markdown.contains("旧式横向合并") && markdown.contains("末列"), markdown);
        }
    }

    @Test
    void docxLayoutPassDoesNotEnableImageExtraction() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFTable table = document.createTable(1, 2);
            table.getRow(0).getCell(0).setText("图片旁标题");
            table.getRow(0).getCell(0).getCTTc().addNewTcPr().addNewGridSpan()
                    .setVal(BigInteger.valueOf(2));
            table.getRow(0).removeCell(1);
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png()),
                    Document.PICTURE_TYPE_PNG, "disabled.png", Units.toEMU(20), Units.toEMU(20));

            ExtractResult result = extract(document, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.getMarkdown().contains("colspan=\"2\""), result.getMarkdown());
            assertFalse(result.getMarkdown().contains("[Image]"), result.getMarkdown());
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
    void docxKeepsMeaningfulImageAlternativeTextWithoutOcrOrUpload() throws Exception {
        try (XWPFDocument document = new XWPFDocument()) {
            XWPFRun run = document.createParagraph().createRun();
            run.addPicture(new ByteArrayInputStream(png()), Document.PICTURE_TYPE_PNG,
                    "chart.png", Units.toEMU(20), Units.toEMU(20));
            run.getCTR().getDrawingArray(0).getInlineArray(0).getDocPr()
                    .setDescr("季度销售额从 100 万元增长到 180 万元");
            run.getEmbeddedPictures().getFirst().getCTPicture().getNvPicPr().getCNvPr()
                    .setDescr("季度销售额从 100 万元增长到 180 万元");

            ExtractResult result = extract(document, ExtractConfig.defaultConfig());

            assertFalse(result.isError(), result.getErrorMessage());
            assertEquals("[Image]季度销售额从 100 万元增长到 180 万元[ImageEnd]",
                    result.getMarkdown());
            assertTrue(result.hasImage());
        }
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
    void emptyOfficeFormattingDoesNotPolluteComplexTables() {
        String markdown = Markdown.fromHtml("""
                <table><tr><td colspan="2"><a name="bookmark"></a><i> </i>可见内容</td></tr></table>
                """);

        assertTrue(markdown.contains("<table>"), markdown);
        assertTrue(markdown.contains("可见内容"), markdown);
        assertFalse(markdown.contains("<a"), markdown);
        assertFalse(markdown.contains("<i"), markdown);
    }

    @Test
    void simpleTablesDoNotPadRowsAndSeparatorsToLongCellWidths() {
        String longCell = "很长的单元格".repeat(100);
        String markdown = Markdown.fromHtml("<table><tr><td>短</td><td>" + longCell + "</td></tr></table>");

        assertTrue(markdown.contains("| --- | --- |"), markdown);
        assertTrue(markdown.contains("| 短 | " + longCell + " |"), markdown);
        assertFalse(markdown.contains("-".repeat(100)), markdown);
        assertTrue(render(markdown).contains("<table>"), markdown);
    }

    @Test
    void legacyWordLinkTargetSwitchIsRemovedFromDestination() {
        String markdown = Markdown.fromHtml("""
                <p><a href='https://example.com/path&amp;quot; \\t &amp;quot;_blank'>旧 Word 链接</a></p>
                """);

        assertEquals("[旧 Word 链接](https://example.com/path)", markdown);
        assertFalse(markdown.contains("\\t"), markdown);
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
    void extractionWhitespaceDoesNotKeepNonBreakingOrInvisibleCharacters() {
        assertEquals("first secondthird", Markdown.fromText(
                "first\u00a0second\u200bthird\u00ad"));
        assertEquals("first secondthird", Markdown.fromHtml(
                "<p>first&nbsp;second&#x200b;third&#xad;</p>"));
    }

    @Test
    void imageAlternativeTextDropsGeneratedNamesWithoutDiscardingUsefulDescriptions() {
        assertEquals("", TikaExtractor.meaningfulAlternativeText("Picture 1", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText("image1.png", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText(
                "Logo\n\nDescription automatically generated", "image1.png"));
        assertEquals("A chart rising from 10 to 20", TikaExtractor.meaningfulAlternativeText(
                "A chart rising from 10 to 20\nDescription automatically generated", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText(
                "https://images.example.com/photo.jpg", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText("IMG_256", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText("济南1", "image1.png"));
        assertEquals("", TikaExtractor.meaningfulAlternativeText(
                "△图片来源：埃菲社", "image1.png"));
    }

    @Test
    void underlineAndInsertedTextRemainPlainPortableMarkdown() {
        String markdown = Markdown.fromHtml("<p><u>带下划线</u><ins>修订插入</ins></p>");

        assertEquals("带下划线修订插入", markdown);
        assertFalse(markdown.contains("++"), markdown);
    }

    @Test
    void legacyPageFieldInstructionDoesNotLeakFromFooter() {
        var document = Jsoup.parse("<div class=footer><p>PAGE</p><p>1</p></div><p>PAGE</p>");

        new DocExtract().cleanDocument(document, new Metadata());

        assertEquals("", document.selectFirst("div.footer").text());
        assertEquals(1, document.select("body > p:matchesOwn((?i)^PAGE$)").size());
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
