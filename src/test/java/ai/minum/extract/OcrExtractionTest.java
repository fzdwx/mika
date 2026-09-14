package ai.minum.extract;

import ai.minum.Mika;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class OcrExtractionTest {
    @Test
    void keepsChineseOcrTextAsLiteralMarkdown() throws Exception {
        ExtractResult result = extractWithResponse(200, "{\"code\":0,\"data\":\"中文 *标签*\\n第二行\"}");
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("[Image]中文 \\*标签\\*\n第二行[ImageEnd]", result.getMarkdown());
    }

    @Test
    void failsOnHttpErrorAndMissingTextInsteadOfReturningEmptySuccess() throws Exception {
        assertTrue(extractWithResponse(503, "{\"data\":\"\"}").isError());
        assertTrue(extractWithResponse(200, "{\"code\":500,\"message\":\"failed\"}").isError());
        ExtractResult emptyText = extractWithResponse(200, "{\"code\":0,\"data\":\"\"}");
        assertFalse(emptyText.isError(), "A successful OCR with no recognized text is valid");
    }

    @Test
    void docxOcrReturnsTextAtBodyAndTableImagePositionsWithOrWithoutUpload() throws Exception {
        byte[] docx = docxWithBodyAndTableImages();
        for (boolean upload : new boolean[]{false, true}) {
            ExtractConfig config = ExtractConfig.defaultConfig();
            if (upload) {
                config.imageUploader(image -> "images/word.png");
            }
            ExtractResult result = extractWithResponse(200,
                    "{\"code\":0,\"data\":\"图中识别文字 *标签*\"}", "docx", docx, config);
            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.hasImage());
            assertTrue(result.hasTable());
            String markdown = result.getMarkdown();
            int bodyOcr = markdown.indexOf("图中识别文字");
            int tableOcr = markdown.indexOf("图中识别文字", bodyOcr + 1);
            assertTrue(bodyOcr > markdown.indexOf("正文图片之前"), markdown);
            assertTrue(bodyOcr < markdown.indexOf("正文图片之后"), markdown);
            assertTrue(tableOcr > markdown.indexOf("单元格图片之前"), markdown);
            assertTrue(tableOcr < markdown.indexOf("表格之后"), markdown);
            assertTrue(markdown.contains("\\*标签\\*"), markdown);
            assertEquals(upload, markdown.contains("[Image](images/word.png)"), markdown);
            assertTrue(markdown.contains("[ImageEnd]"), markdown);
            assertFalse(markdown.contains("embedded:"), markdown);
        }
    }

    @Test
    void docxOcrBackendFailureIsNotSwallowedByEmbeddedParsing() throws Exception {
        ExtractResult result = extractWithResponse(503, "{\"data\":\"\"}", "docx",
                docxWithBodyAndTableImages(), ExtractConfig.defaultConfig());
        assertTrue(result.isError(), "Embedded image OCR failure must fail extraction for retry");
    }

    private static byte[] docxWithBodyAndTableImages() throws Exception {
        byte[] png = OfficeMarkdownTest.png();
        try (XWPFDocument document = new XWPFDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("正文图片之前");
            document.createParagraph().createRun().addPicture(new ByteArrayInputStream(png),
                    Document.PICTURE_TYPE_PNG, "body.png", Units.toEMU(20), Units.toEMU(20));
            document.createParagraph().createRun().setText("正文图片之后");
            var cell = document.createTable(1, 1).getRow(0).getCell(0);
            cell.setText("单元格图片之前");
            cell.addParagraph().createRun().addPicture(new ByteArrayInputStream(png),
                    Document.PICTURE_TYPE_PNG, "cell.png", Units.toEMU(20), Units.toEMU(20));
            document.createParagraph().createRun().setText("表格之后");
            document.write(output);
            return output.toByteArray();
        }
    }

    private static ExtractResult extractWithResponse(int status, String body) throws Exception {
        return extractWithResponse(status, body, "png", OfficeMarkdownTest.png(), ExtractConfig.defaultConfig());
    }

    private static ExtractResult extractWithResponse(int status, String body, String type, byte[] input,
                                                    ExtractConfig config) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ocr", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var response = exchange.getResponseBody()) {
                response.write(bytes);
            }
            exchange.close();
        });
        server.start();
        try {
            return Mika.extract(type, new ByteArrayInputStream(input),
                    config.ocrUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/ocr"));
        } finally {
            server.stop(0);
        }
    }
}
