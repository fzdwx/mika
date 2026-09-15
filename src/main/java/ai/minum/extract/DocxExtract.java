package ai.minum.extract;

import org.apache.tika.metadata.Metadata;
import org.jsoup.nodes.Document;

import java.nio.file.Path;

/** Preserves headings, tables, hyperlinks, notes and images through Tika's XHTML output. */
public class DocxExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "docx".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType);
    }

    @Override
    protected boolean requiresRepeatableSource() {
        return true;
    }

    @Override
    protected void cleanDocument(Document document, Metadata metadata, Path repeatableSource) {
        try {
            DocxTableLayout.restore(document, repeatableSource);
        } catch (Exception e) {
            // Tika has already produced usable body text. A package-specific layout problem must not
            // turn that successful extraction into an empty/error result.
            logger.warn("Failed to restore DOCX merged table cells", e);
        }
    }
}
