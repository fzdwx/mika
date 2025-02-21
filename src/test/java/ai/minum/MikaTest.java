package ai.minum;

import ai.minum.extract.ExtractConfig;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import static org.junit.jupiter.api.Assertions.assertFalse;

class MikaTest {

    @Test
    void detect() throws FileNotFoundException {
        String path = "/home/like/aaaaaaaaa.docx";
        var stream = new FileInputStream(path);
        var result = Mika.detect(stream);
        assertFalse(result.isError());
        System.out.println(result);
    }

    @Test
    void extract() throws FileNotFoundException {
        String path = "/home/like/project/mika/bbbbbbbb.docx";
        FileInputStream stream = new FileInputStream(path);
        ExtractConfig config = ExtractConfig.defaultConfig().ocrUrl("http://192.168.50.191:15234/file/ocr").ocr(false);
        var result = Mika.extract(new BufferedInputStream(stream), config);
        System.out.println(result);
    }
}
