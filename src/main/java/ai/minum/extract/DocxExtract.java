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
        // Tika exposes Word's glossary/building-block part as XHTML. It contains design-time
        // content-control prompts (for example "Click or tap here to enter text") that are not
        // displayed in the document body and must not enter retrieval text.
        document.select("div.glossary").remove();
        document.select("p").stream()
                .filter(paragraph -> paragraph.text().strip().matches(
                        "(?iu)(?:\\d+\\s*)?原创精品资源学科网独家享有版权，侵权必究！"
                                + "|学科网[（(]北京[）)]股份有限公司"))
                .forEach(org.jsoup.nodes.Node::remove);
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
        try {
            int recovered = DocxDeepTableText.restore(document, repeatableSource);
            if (recovered > 0) {
                result.addWarning("Recovered " + recovered + " paragraphs beyond the XHTML table nesting limit");
            }
        } catch (Exception e) {
            // Keep Tika's safely bounded output if a deeply nested table is malformed. This
            // supplemental pass never replaces the primary parse.
            logger.warn("Failed to recover deeply nested DOCX table text", e);
        }
        try {
            DocxActiveXControls.restore(document, repeatableSource);
        } catch (Exception e) {
            // ActiveX is optional legacy content. Keep the successfully parsed Word body when a
            // control uses an unknown persistence format or contains a damaged OLE stream.
            logger.warn("Failed to restore DOCX ActiveX text box values", e);
        }
        try {
            DocxMath.restore(document, repeatableSource);
        } catch (Exception e) {
            // Keep Tika's readable flattened formula when an unfamiliar OMML construct cannot be
            // restored as Markdown math.
            logger.warn("Failed to restore DOCX equation structure", e);
        }
        try {
            DocxPhonetics.restore(document, repeatableSource);
        } catch (Exception e) {
            // Keep Tika's base text if a ruby annotation is malformed.
            logger.warn("Failed to restore DOCX phonetic annotations", e);
        }
        try {
            DocxObjectPreviews.restore(document, repeatableSource);
        } catch (Exception e) {
            // Keep Tika's body text if an embedded object's fallback preview is malformed.
            logger.warn("Failed to restore DOCX embedded object previews", e);
        }
        try {
            DocxCharts.restore(document, repeatableSource);
        } catch (Exception e) {
            // Keep Tika's flat chart cache text if an unusual chart cannot be structured.
            logger.warn("Failed to restore DOCX chart structure", e);
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
