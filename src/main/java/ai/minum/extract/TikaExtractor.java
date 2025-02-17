package ai.minum.extract;

import ai.minum.Mika;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;

import java.io.IOException;
import java.io.InputStream;

public class TikaExtractor implements Extractor {

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws TikaException, IOException {
        Tika tika = Mika.getTika();
        String content = tika.parseToString(stream);
        return ExtractResult.successOfOne(content);
    }

    @Override
    public boolean support(String mimeType) {
        return true;
    }
}