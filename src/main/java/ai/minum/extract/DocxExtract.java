package ai.minum.extract;

/** Preserves headings, tables, hyperlinks, notes and images through Tika's XHTML output. */
public class DocxExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "docx".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType);
    }
}
