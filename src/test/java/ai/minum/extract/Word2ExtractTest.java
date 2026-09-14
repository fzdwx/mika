package ai.minum.extract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertTrue(result.getMarkdown().contains("## Headers and footers"), result.getMarkdown());
    }

    @Test
    void resolvesFieldResultsAndLegacyMarkersAsMarkdown() {
        String text = "\u0013SYMBOL 183 \\f \"Symbol\"\u0015\tFirst item\r"
                + "Read \u0013HYPERLINK https://example.com\u0014the reference\u0015\r"
                + "Picture \u0001\r"
                + "Imported \u0013IMPORT logo.bmp \u0001\u0015";

        String markdown = Word2Extract.toMarkdown(text);

        assertTrue(markdown.contains("- First item"), markdown);
        assertTrue(markdown.contains("Read the reference"), markdown);
        assertFalse(markdown.contains("HYPERLINK"), markdown);
        assertTrue(markdown.contains("[Image][ImageEnd]"), markdown);
        assertEquals(2, count(markdown, "[Image][ImageEnd]"), markdown);
    }

    @Test
    void convertsWord2CellAndRowMarkersToAGfmTable() {
        String text = "Product Number\r\n\r\u0007\r\nSize\r\n\r\u0007\r\nList Price"
                + "\r\n\r\u0007\r\n1-6\r\n\r\u0007\r\n7+\r\n\r\u0007\r\u0007"
                + "56XAE10\r\u000700\r\u00074.00\r\u00073.00\r\u00072.80\r\u0007\r\u0007";

        String markdown = Word2Extract.toMarkdown(text);

        assertTrue(markdown.contains("| Product Number | Size | List Price | 1-6 | 7+ |"), markdown);
        assertTrue(markdown.contains("| 56XAE10 | 00 | 4.00 | 3.00 | 2.80 |"), markdown);
    }

    @Test
    void followsTheFastSavePieceTableInsteadOfReadingStaleText() throws Exception {
        byte[] original = poiWord2();
        ExtractResult originalResult = new DocExtract().extract(
                ExtractConfig.defaultConfig(), new ByteArrayInputStream(original));
        byte[] document = fastSavedInTwoPieces(original);

        ExtractResult result = new DocExtract().extract(
                ExtractConfig.defaultConfig(), new ByteArrayInputStream(document));

        assertFalse(result.isError(), result.getErrorMessage());
        assertEquals(originalResult.getMarkdown(), result.getMarkdown());
    }

    @Test
    void doesNotIndexTheWordBasicMacroSubdocument() throws Exception {
        byte[] document = poiWord2();
        int headerCharacters = littleEndianInt(document, 60);
        putLittleEndianInt(document, 60, 0);
        putLittleEndianInt(document, 64, headerCharacters);

        ExtractResult result = new DocExtract().extract(
                ExtractConfig.defaultConfig(), new ByteArrayInputStream(document));

        assertFalse(result.isError(), result.getErrorMessage());
        assertFalse(result.getMarkdown().contains("PARTICIPANTS LIST"), result.getMarkdown());
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

    private static byte[] fastSavedInTwoPieces(byte[] original) {
        int textStart = littleEndianInt(original, 24);
        int characterCount = 0;
        for (int offset = 52; offset <= 68; offset += 4) {
            characterCount += littleEndianInt(original, offset);
        }
        int split = characterCount / 2;
        int appendedText = original.length;
        int clxStart = appendedText + characterCount - split;
        int propertyRecordLength = 3;
        int pieceTableLength = 28;
        int clxLength = 1 + 2 + propertyRecordLength + 1 + 2 + pieceTableLength;
        byte[] document = Arrays.copyOf(original, clxStart + clxLength);
        System.arraycopy(original, textStart + split, document, appendedText, characterCount - split);

        document[10] |= 0x04;
        putLittleEndianInt(document, 286, clxStart);
        putLittleEndianShort(document, 290, clxLength);
        int cursor = clxStart;
        document[cursor++] = 1;
        putLittleEndianShort(document, cursor, propertyRecordLength);
        cursor += 2;
        document[cursor++] = 7;
        document[cursor++] = 8;
        document[cursor++] = 9;
        document[cursor++] = 2;
        putLittleEndianShort(document, cursor, pieceTableLength);
        cursor += 2;
        putLittleEndianInt(document, cursor, 0);
        putLittleEndianInt(document, cursor + 4, split);
        putLittleEndianInt(document, cursor + 8, characterCount);
        int descriptors = cursor + 12;
        putLittleEndianInt(document, descriptors + 2, textStart);
        putLittleEndianInt(document, descriptors + 10, appendedText);
        return document;
    }

    private static int littleEndianInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | bytes[offset + 3] << 24;
    }

    private static void putLittleEndianInt(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
        bytes[offset + 2] = (byte) (value >>> 16);
        bytes[offset + 3] = (byte) (value >>> 24);
    }

    private static void putLittleEndianShort(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) value;
        bytes[offset + 1] = (byte) (value >>> 8);
    }

    private static int count(String value, String token) {
        return (value.length() - value.replace(token, "").length()) / token.length();
    }
}
