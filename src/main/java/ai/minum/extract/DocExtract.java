package ai.minum.extract;

/** Uses Tika's Word structure instead of flattening table cells and field contents. */
public class DocExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "doc".equals(mimeType) || "application/msword".equals(mimeType);
    }
}
