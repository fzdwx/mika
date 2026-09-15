package ai.minum.extract;

import org.apache.tika.metadata.Metadata;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.util.Set;

/** Uses Tika's Word structure instead of flattening table cells and field contents. */
public class DocExtract extends TikaExtractor {
    private static final Set<String> FIELD_TYPES = Set.of(
            "ADDIN", "ADDRESSBLOCK", "ADVANCE", "ASK", "AUTHOR", "AUTONUM", "AUTONUMLGL",
            "AUTONUMOUT", "AUTOTEXT", "AUTOTEXTLIST", "BARCODE", "BIBLIOGRAPHY", "CITATION",
            "COMMENTS", "COMPARE", "CREATEDATE", "DATABASE", "DATE", "DOCPROPERTY", "DOCVARIABLE",
            "EDITTIME", "EMBED", "EQ", "FILENAME", "FILESIZE", "FILLIN", "FORMCHECKBOX",
            "FORMDROPDOWN", "FORMTEXT", "GLOSSARY", "GOTOBUTTON", "GREETINGLINE", "HYPERLINK", "IF",
            "INCLUDEPICTURE", "INCLUDETEXT", "INDEX", "INFO", "KEYWORDS", "LASTSAVEDBY", "LINK",
            "LISTNUM", "MACROBUTTON", "MERGEBARCODE", "MERGEFIELD", "MERGEREC", "MERGESEQ", "NEXT",
            "NEXTIF", "NOTEREF", "NUMCHARS", "NUMPAGES", "NUMWORDS", "PAGE", "PAGEREF", "PRINT",
            "PRINTDATE", "PRIVATE", "QUOTE", "RD", "REF", "REVNUM", "SAVEDATE", "SECTION",
            "SECTIONPAGES", "SEQ", "SET", "SKIPIF", "STYLEREF", "SUBJECT", "SYMBOL", "TA", "TC",
            "TEMPLATE", "TIME", "TITLE", "TOA", "TOC", "USERADDRESS", "USERINITIALS", "USERNAME", "XE");

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

    @Override
    protected void cleanDocument(Document document, Metadata metadata) {
        // Tika's fallback extraction for fields in old Word comments, notes and text boxes writes
        // the 0x13/0x14/0x15 field controls as U+FFFD. Keep the displayed field result and discard
        // both the internal instruction and its illegal control markers, matching normal Word text.
        for (var element : document.getAllElements()) {
            for (TextNode text : element.textNodes()) {
                text.text(cleanLegacyFields(text.getWholeText()));
            }
        }

        // HWPF comments and notes can start with Word's 0x02/0x05 reference marker. Tika's XML
        // serializer also turns these illegal XML controls into U+FFFD.
        for (var paragraph : document.select("p")) {
            for (Node child : paragraph.childNodes()) {
                if (!(child instanceof TextNode text)) {
                    break;
                }
                String value = text.getWholeText();
                if (value.isBlank()) {
                    continue;
                }
                int marker = 0;
                while (marker < value.length() && Character.isWhitespace(value.charAt(marker))) {
                    marker++;
                }
                if (marker < value.length() && value.charAt(marker) == '\uFFFD') {
                    text.text(value.substring(0, marker) + value.substring(marker + 1));
                }
                break;
            }
        }

        // HWPF can expose the PAGE field instruction as a literal footer paragraph followed by
        // the displayed page number. The instruction is not visible document content.
        for (var paragraph : document.select("div.footer > p").stream()
                .filter(candidate -> candidate.text().strip().equalsIgnoreCase("PAGE"))
                .toList()) {
            var displayedPage = paragraph.nextElementSibling();
            paragraph.remove();
            if (displayedPage != null && displayedPage.text().strip().matches("\\d+")) {
                displayedPage.remove();
            }
        }
    }

    private static String cleanLegacyFields(String value) {
        StringBuilder cleaned = new StringBuilder(value.length());
        int copiedThrough = 0;
        int searchFrom = 0;
        int fieldStart;
        while ((fieldStart = value.indexOf('\uFFFD', searchFrom)) >= 0) {
            int instructionStart = fieldStart + 1;
            while (instructionStart < value.length()
                    && Character.isWhitespace(value.charAt(instructionStart))) {
                instructionStart++;
            }
            int typeEnd = instructionStart;
            while (typeEnd < value.length()
                    && !Character.isWhitespace(value.charAt(typeEnd))
                    && value.charAt(typeEnd) != '\uFFFD') {
                typeEnd++;
            }
            String type = value.substring(instructionStart, typeEnd).toUpperCase(java.util.Locale.ROOT);
            if (!FIELD_TYPES.contains(type) && !type.startsWith("=")) {
                searchFrom = fieldStart + 1;
                continue;
            }
            int fieldSeparator = value.indexOf('\uFFFD', typeEnd);
            int fieldEnd = fieldSeparator < 0 ? -1 : value.indexOf('\uFFFD', fieldSeparator + 1);
            if (fieldSeparator < 0 || fieldEnd < 0
                    || fieldSeparator - fieldStart > 512 || fieldEnd - fieldSeparator > 4096
                    || containsLineBreak(value, fieldStart, fieldEnd)) {
                searchFrom = fieldStart + 1;
                continue;
            }
            int replacementStart = fieldStart > copiedThrough && value.charAt(fieldStart - 1) == '\uFFFD'
                    ? fieldStart - 1 : fieldStart;
            cleaned.append(value, copiedThrough, replacementStart);
            cleaned.append(value, fieldSeparator + 1, fieldEnd);
            copiedThrough = fieldEnd + 1;
            searchFrom = copiedThrough;
        }
        cleaned.append(value, copiedThrough, value.length());
        return cleaned.toString();
    }

    private static boolean containsLineBreak(String value, int start, int end) {
        for (int index = start; index < end; index++) {
            char current = value.charAt(index);
            if (current == '\r' || current == '\n') {
                return true;
            }
        }
        return false;
    }
}
