package ai.minum.extract;

import org.apache.tika.metadata.Metadata;
import org.jsoup.nodes.Document;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Set;

/** Preserves headings, tables, hyperlinks, notes and images through Tika's XHTML output. */
public class DocxExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "docx".equals(mimeType)
                || "application/vnd.openxmlformats-officedocument.wordprocessingml.document".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        DocxPackageRepair.Repair repair = DocxPackageRepair.repair(stream.readAllBytes());
        ExtractResult result = super.doExtract(config, new ByteArrayInputStream(repair.bytes()));
        if (repair.repaired()) {
            result.addWarning("Repaired missing DOCX [Content_Types].xml");
        }
        return result;
    }

    @Override
    protected boolean requiresRepeatableSource() {
        return true;
    }

    @Override
    protected boolean splitIntoLogicalSections() {
        return true;
    }

    @Override
    protected void cleanDocument(Document document, Metadata metadata, Path repeatableSource,
                                 ExtractResult result) {
        try {
            DocxComments.restore(document, repeatableSource);
        } catch (Exception e) {
            // Keep Tika's flattened comment text if OOXML relationship/range data is malformed.
            logger.warn("Failed to restore DOCX comment structure", e);
        }
        try {
            DocxTableLayout.restore(document, repeatableSource);
        } catch (Exception e) {
            // Tika has already produced usable body text. A package-specific layout problem must not
            // turn that successful extraction into an empty/error result.
            logger.warn("Failed to restore DOCX merged table cells", e);
        }
    }

    @Override
    protected Set<String> imagesWithStructuredText(Path repeatableSource) {
        try {
            return DocxChartPreviews.withUsableCache(repeatableSource);
        } catch (Exception malformedPackage) {
            logger.debug("Cannot identify DOCX chart preview images", malformedPackage);
            return Set.of();
        }
    }
}
