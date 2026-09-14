package ai.minum.extract;

import org.apache.poi.poifs.filesystem.FileMagic;

import java.io.InputStream;

/** Uses Tika's Word structure instead of flattening table cells and field contents. */
public class DocExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "doc".equals(mimeType) || "application/msword".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        InputStream checkedStream = FileMagic.prepareToCheckMagic(stream);
        if (FileMagic.valueOf(checkedStream) == FileMagic.WORD2) {
            throw new UnsupportedOperationException(
                    "Legacy Word 2.0 documents are not supported; convert the file to DOCX before extraction");
        }
        return super.doExtract(config, checkedStream);
    }
}
