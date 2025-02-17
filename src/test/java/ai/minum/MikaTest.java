package ai.minum;

import org.apache.xmlbeans.impl.common.IOUtil;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;

import static org.junit.jupiter.api.Assertions.*;
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
        String path = "/home/like/aaaaaaaaa.docx";
        FileInputStream stream = new FileInputStream(path);
        var result = Mika.extract(new BufferedInputStream(stream),false);
        System.out.println(result);
    }
}