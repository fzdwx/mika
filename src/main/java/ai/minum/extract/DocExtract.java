package ai.minum.extract;

import java.io.BufferedInputStream;
import java.io.InputStream;

/** Uses Tika's Word structure instead of flattening table cells and field contents. */
public class DocExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "doc".equals(mimeType)
                || "dot".equals(mimeType)
                || "application/msword".equals(mimeType)
                || "application/vnd.ms-word".equals(mimeType)
                || "application/vnd.ms-word.template".equals(mimeType)
                || "application/x-dot".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        InputStream checkedStream = stream.markSupported() ? stream : new BufferedInputStream(stream);
        checkedStream.mark(4);
        byte[] signature = checkedStream.readNBytes(4);
        checkedStream.reset();
        if (Word2Extract.supports(signature)) {
            return Word2Extract.extract(checkedStream);
        }
        return super.doExtract(config, checkedStream);
    }
}
