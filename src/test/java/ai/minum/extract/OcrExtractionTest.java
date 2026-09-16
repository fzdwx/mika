package ai.minum.extract;

import ai.minum.Mika;
import com.sun.net.httpserver.HttpServer;
import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.Document;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.Color;
import java.awt.Graphics2D;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class OcrExtractionTest {
    @Test
    void removesRetrievalNoiseFromDecorativeOcrWithoutLosingHeadings() {
        assertEquals("教材分析\n课堂导入", OcrImageQuality.cleanText("""
                学科网 WWW.ZXXK.COM
                00
                F8教材分析
                A+ 课堂导入
                """));
    }

    @Test
    void ocrsScreenshotColumnsInReadingOrder() throws Exception {
        BufferedImage page = new BufferedImage(1200, 500, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, page.getWidth(), page.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(40, 40, 300, 420);
        graphics.fillRect(450, 40, 300, 420);
        graphics.fillRect(860, 40, 300, 420);
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 20);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(page, "png", png));
        AtomicInteger call = new AtomicInteger();

        ExtractResult result = Mika.extract("png", new ByteArrayInputStream(png.toByteArray()),
                ExtractConfig.defaultConfig().ocr((image, mime) -> "column-" + call.incrementAndGet()));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(3, call.get());
        assertTrue(result.getMarkdown().contains("column-1\n\ncolumn-2\n\ncolumn-3"),
                result.getMarkdown());
    }

    @Test
    void keepsBinaryArchivalScanAsOneOcrImage() throws Exception {
        BufferedImage scan = new BufferedImage(1200, 800, BufferedImage.TYPE_BYTE_BINARY);
        Graphics2D graphics = scan.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, scan.getWidth(), scan.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(50, 50, 300, 700);
        graphics.fillRect(800, 50, 300, 700);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(scan, "png", png));
        AtomicInteger calls = new AtomicInteger();

        ExtractResult result = Mika.extract("png", new ByteArrayInputStream(png.toByteArray()),
                ExtractConfig.defaultConfig().ocr((image, mime) -> "scan-" + calls.incrementAndGet()));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(1, calls.get());
        assertTrue(result.getMarkdown().contains("scan-1"), result.getMarkdown());
    }

    @Test
    void separatesNewspaperBodyColumnsBelowSpanningHeadline() throws Exception {
        BufferedImage page = new BufferedImage(1400, 1200, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = page.createGraphics();
        graphics.setColor(Color.WHITE);
        graphics.fillRect(0, 0, page.getWidth(), page.getHeight());
        graphics.setColor(Color.BLACK);
        graphics.fillRect(30, 20, 1000, 120); // spanning headline
        for (int left : new int[]{30, 360, 690, 1080}) {
            graphics.fillRect(left, 220, 280, 940);
        }
        graphics.setColor(Color.RED);
        graphics.fillRect(0, 0, 20, 20);
        graphics.dispose();
        ByteArrayOutputStream png = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(page, "png", png));
        AtomicInteger calls = new AtomicInteger();

        ExtractResult result = Mika.extract("png", new ByteArrayInputStream(png.toByteArray()),
                ExtractConfig.defaultConfig().ocr((image, mime) -> "region-" + calls.incrementAndGet()));

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(calls.get() >= 5, "headline, body columns and sidebar should be separate OCR regions");
        assertTrue(result.getMarkdown().indexOf("region-1") < result.getMarkdown().indexOf("region-2"),
                result.getMarkdown());
    }

    @Test
    void removesWordGlossaryPlaceholderFromBody() throws Exception {
        var document = org.jsoup.Jsoup.parse("""
                <p>visible body</p>
                <div class="glossary"><p>Klicken oder tippen Sie hier, um Text einzugeben.</p></div>
                """);

        new DocxExtract().cleanDocument(document, new org.apache.tika.metadata.Metadata(),
                java.nio.file.Path.of("missing.docx"), ExtractResult.of());

        assertEquals("visible body", document.body().text());
    }

    @Test
    void pictImageReaderIsAvailable() {
        assertTrue(ImageIO.getImageReadersByFormatName("PICT").hasNext());
    }

    @Test
    void acceptsCustomOcrProviderForHandwritingModels() throws Exception {
        AtomicReference<String> mimeType = new AtomicReference<>();
        ExtractResult result = Mika.extract("png", new ByteArrayInputStream(OfficeMarkdownTest.png()),
                ExtractConfig.defaultConfig().ocr((image, mime) -> {
                    mimeType.set(mime);
                    return "handwriting model text";
                }));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("image/png", mimeType.get());
        assertTrue(result.getMarkdown().contains("handwriting model text"));
    }

    @Test
    void rasterizesAnUnsupportedOcrImageFormatToPng() throws Exception {
        BufferedImage image = new BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream bmp = new ByteArrayOutputStream();
        assertTrue(ImageIO.write(image, "bmp", bmp));
        AtomicReference<String> mimeType = new AtomicReference<>();
        AtomicReference<byte[]> received = new AtomicReference<>();

        ExtractResult result = Mika.extract("bmp", new ByteArrayInputStream(bmp.toByteArray()),
                ExtractConfig.defaultConfig()
                        .ocrImageFormats(Set.of(ImageResult.Format.PNG, ImageResult.Format.JPEG))
                        .ocr((bytes, mime) -> {
                            mimeType.set(mime);
                            received.set(bytes);
                            return "converted";
                        }));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("image/png", mimeType.get());
        assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'},
                java.util.Arrays.copyOf(received.get(), 4));
    }

    @Test
    void rasterizesRealWmfEmfAndPictSamplesBeforeOcr() throws Exception {
        for (String fixture : List.of("poi-image.wmf", "poi-image.emf", "twelvemonkeys-1.pict")) {
            byte[] source;
            try (var input = getClass().getResourceAsStream("/documents/" + fixture)) {
                assertNotNull(input);
                source = input.readAllBytes();
            }
            AtomicReference<String> mime = new AtomicReference<>();
            AtomicReference<byte[]> image = new AtomicReference<>();

            ExtractResult result = Mika.extract(fixture.substring(fixture.lastIndexOf('.') + 1),
                    new ByteArrayInputStream(source), ExtractConfig.defaultConfig()
                            .imageExtractMaxSize(1024 * 1024)
                            .ocr((bytes, type) -> {
                                image.set(bytes);
                                mime.set(type);
                                return "legacy image text";
                            }));

            assertFalse(result.isError(), fixture + ": " + result.getErrorMessage());
            assertEquals("image/png", mime.get(), fixture);
            assertArrayEquals(new byte[]{(byte) 0x89, 'P', 'N', 'G'},
                    java.util.Arrays.copyOf(image.get(), 4), fixture);
        }
    }
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
