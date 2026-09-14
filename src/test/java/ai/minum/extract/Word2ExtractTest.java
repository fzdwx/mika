package ai.minum.extract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Word2ExtractTest {

    /** OpenPreserve format-corpus office-examples/Old Word file/NEWSSLID.DOC (CC0). */
    @Test
    void extractsWord2FieldsAsListsWithoutLeakingInstructions() throws Exception {
        ExtractResult result;
        try (var input = getClass().getResourceAsStream("/documents/openpreserve-word2.doc")) {
            assertNotNull(input);
            result = new DocExtract().extract(ExtractConfig.defaultConfig(), input);
        }

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.getMarkdown().contains("- What is USENET NEWS"), result.getMarkdown());
        assertTrue(result.getMarkdown().contains("- PC readers: trumpet, WinQVTnet"), result.getMarkdown());
        assertFalse(result.getMarkdown().contains("SYMBOL 183"), result.getMarkdown());
        assertFalse(result.getMarkdown().contains("styleref Title"), result.getMarkdown());
    }

    @Test
    void resolvesFieldResultsAndLegacyMarkersAsMarkdown() {
        String text = "\u0013SYMBOL 183 \\f \"Symbol\"\u0015\tFirst item\r"
                + "Read \u0013HYPERLINK https://example.com\u0014the reference\u0015\r"
                + "Picture \u0001";

        String markdown = Word2Extract.toMarkdown(text);

        assertTrue(markdown.contains("- First item"), markdown);
        assertTrue(markdown.contains("Read the reference"), markdown);
        assertFalse(markdown.contains("HYPERLINK"), markdown);
        assertTrue(markdown.contains("[Image][ImageEnd]"), markdown);
    }

    @Test
    void rejectsFastSavedWord2InsteadOfReadingFormattingBytesAsText() throws Exception {
        byte[] document = poiWord2();
        document[10] |= 0x04;

        ExtractResult result = new DocExtract().extract(
                ExtractConfig.defaultConfig(), new ByteArrayInputStream(document));

        assertTrue(result.isError());
        assertTrue(result.getErrorMessage().contains("Fast-saved Word 2.0"), result.getErrorMessage());
    }

    @Test
    void preservesAnImageBoundaryWhenOnlyTheLegacyPictureFlagIsAvailable() throws Exception {
        byte[] document = poiWord2();
        document[10] |= 0x08;

        ExtractResult result = new DocExtract().extract(
                ExtractConfig.defaultConfig(), new ByteArrayInputStream(document));

        assertFalse(result.isError(), result.getErrorMessage());
        assertTrue(result.hasImage());
        assertTrue(result.getMarkdown().endsWith("[Image][ImageEnd]"), result.getMarkdown());
        assertTrue(result.getWarnings().stream().anyMatch(warning -> warning.contains("picture data")));
    }

    private static byte[] poiWord2() throws Exception {
        try (var input = Word2ExtractTest.class.getResourceAsStream("/documents/poi-word2.doc")) {
            assertNotNull(input);
            return input.readAllBytes();
        }
    }
}
