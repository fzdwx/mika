package ai.minum.extract;

import ai.minum.Mika;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class ImageExtractTest {
    @Test
    void emitsLegacyImageMarkerWithoutOcrAndPreservesUploaderKey() throws Exception {
        ExtractResult result = Mika.extract("IMAGE/PNG", new ByteArrayInputStream(OfficeMarkdownTest.png()),
                ExtractConfig.defaultConfig().imageUploader(image -> "images/中文 图(1)%20.png"));
        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals("[Image](images/中文 图(1)%20.png)[ImageEnd]", result.getMarkdown());
        assertTrue(result.hasImage());
    }

    @Test
    void reportsSizeAndCountLimitsWithoutCallingBackend() throws Exception {
        byte[] png = OfficeMarkdownTest.png();
        AtomicInteger uploads = new AtomicInteger();
        for (ExtractConfig config : new ExtractConfig[]{
                ExtractConfig.defaultConfig().imageExtractMaxSize(1),
                ExtractConfig.defaultConfig().maxHandleImageCount(0L)}) {
            config.imageUploader(image -> { uploads.incrementAndGet(); return "image.png"; });
            ExtractResult result = Mika.extract("png", new ByteArrayInputStream(png), config);
            assertFalse(result.isError(), result.getErrorMessage());
            assertTrue(result.hasImage());
            assertEquals("", result.getMarkdown());
            assertFalse(result.getWarnings().isEmpty());
        }
        assertEquals(0, uploads.get());
    }

    @Test
    void rejectsMissingOcrBackend() throws Exception {
        ExtractResult result = Mika.extract("png", new ByteArrayInputStream(OfficeMarkdownTest.png()),
                ExtractConfig.defaultConfig().ocr(true));
        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("no OCR backend"), result.getErrorMessage());
    }
}
