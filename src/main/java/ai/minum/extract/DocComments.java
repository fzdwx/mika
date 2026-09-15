package ai.minum.extract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.util.LittleEndian;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.util.ArrayList;
import java.util.List;

/** Restores the positional information that Tika flattens for binary Word annotations. */
final class DocComments {
    private static final char ANNOTATION_REFERENCE = '\u0005';
    private static final int FIB_FIELD_COUNT_OFFSET = 152;
    private static final int FIB_FIELDS_OFFSET = 154;
    private static final int PLCFAND_REF_FIELD = 4;
    private static final int PLCFAND_TEXT_FIELD = 5;
    private static final int CP_BYTES = 4;
    private static final int ANNOTATION_DESCRIPTOR_BYTES = 30;
    private static final int AUTHOR_INDEX_IN_DESCRIPTOR = 20;

    private DocComments() {
    }

    static int restore(Document document, HWPFDocument word, ExtractResult result) {
        List<Reference> references = references(word, result);
        List<String> comments = comments(word, references.size());
        if (comments.stream().allMatch(String::isBlank)) {
            return 0;
        }
        removeFlattenedComments(document, comments);

        Element section = document.body().appendElement("section");
        section.appendElement("h3").text("Comments");
        Element list = section.appendElement("ul");
        int restored = 0;
        for (int index = 0; index < comments.size(); index++) {
            String comment = comments.get(index);
            if (comment.isBlank()) {
                continue;
            }
            Element item = list.appendElement("li");
            Reference reference = index < references.size() ? references.get(index) : null;
            String anchor = reference == null ? "" : anchor(word.getRange().text(), reference.position());
            if (reference != null && !reference.author().isBlank()) {
                item.appendElement("strong").text(reference.author());
                item.appendText(": ");
            }
            item.appendText(comment);
            if (!anchor.isBlank()) {
                item.appendText(" (reference context: “" + anchor + "”)");
            }
            restored++;
        }
        return restored;
    }

    private static List<String> comments(HWPFDocument word, int expectedCount) {
        Range range = word.getCommentsRange();
        try {
            TableSlice textTable = fibTable(word, PLCFAND_TEXT_FIELD);
            int offset = textTable.offset();
            int length = textTable.length();
            byte[] table = word.getTableStream();
            if (expectedCount <= 0 || length != (expectedCount + 2) * 4
                    || offset < 0 || offset + length > table.length) {
                throw new IllegalArgumentException("invalid PlcfandTxt bounds");
            }
            String all = range.text();
            List<String> comments = new ArrayList<>();
            for (int index = 0; index < expectedCount; index++) {
                int start = LittleEndian.getInt(table, offset + index * 4);
                int end = LittleEndian.getInt(table, offset + (index + 1) * 4);
                if (start < 0 || start > end || end > all.length()) {
                    throw new IllegalArgumentException("invalid comment text range");
                }
                // Preserve empty slots: PlcfandRef and PlcfandTxt use the same ordinal. Removing
                // one here would attach every later comment to the previous author/reference.
                comments.add(clean(Range.stripFields(all.substring(start, end))));
            }
            return comments;
        } catch (RuntimeException malformedRanges) {
            return commentsByParagraph(range);
        }
    }

    private static List<String> commentsByParagraph(Range range) {
        List<String> comments = new ArrayList<>();
        for (int index = 0; index < range.numParagraphs(); index++) {
            String text = clean(Range.stripFields(range.getParagraph(index).text()));
            if (!text.isBlank()) {
                comments.add(text);
            }
        }
        return comments;
    }

    private static List<Reference> references(HWPFDocument word, ExtractResult result) {
        try {
            // PlcfandRef has (n+1) CPs followed by n fixed ATRDPre10 records. The ibst field
            // inside each descriptor maps into the revision-author string table.
            TableSlice referenceTable = fibTable(word, PLCFAND_REF_FIELD);
            int offset = referenceTable.offset();
            int length = referenceTable.length();
            int recordBytes = CP_BYTES + ANNOTATION_DESCRIPTOR_BYTES;
            int count = (length - CP_BYTES) / recordBytes;
            byte[] table = word.getTableStream();
            if (length < CP_BYTES || count <= 0
                    || length != (count + 1) * CP_BYTES + count * ANNOTATION_DESCRIPTOR_BYTES
                    || offset < 0 || offset + length > table.length) {
                throw new IllegalArgumentException("invalid PlcfandRef bounds");
            }
            int descriptors = offset + (count + 1) * CP_BYTES;
            List<String> authors = word.getRevisionMarkAuthorTable().getEntries();
            List<Reference> references = new ArrayList<>();
            for (int index = 0; index < count; index++) {
                int position = LittleEndian.getInt(table, offset + index * CP_BYTES);
                int authorIndex = LittleEndian.getUShort(table,
                        descriptors + index * ANNOTATION_DESCRIPTOR_BYTES + AUTHOR_INDEX_IN_DESCRIPTOR);
                String author = authorIndex < authors.size() ? authors.get(authorIndex).strip() : "";
                references.add(new Reference(position, author));
            }
            return references;
        } catch (RuntimeException malformedReferences) {
            List<Reference> references = new ArrayList<>();
            String mainText = word.getRange().text();
            for (int index = 0; index < mainText.length(); index++) {
                if (mainText.charAt(index) == ANNOTATION_REFERENCE) {
                    references.add(new Reference(index, ""));
                }
            }
            String commentText = clean(Range.stripFields(word.getCommentsRange().text()));
            if (!references.isEmpty() || !commentText.isBlank()) {
                result.addWarning("Old Word comment author mapping was unavailable; comment order was retained");
            }
            return references;
        }
    }

    private static TableSlice fibTable(HWPFDocument word, int field) {
        byte[] fib = word.getMainStream();
        int pairOffset = FIB_FIELDS_OFFSET + field * 2 * Integer.BYTES;
        if (field < 0 || fib.length < pairOffset + 2 * Integer.BYTES
                || LittleEndian.getUShort(fib, FIB_FIELD_COUNT_OFFSET) <= field) {
            throw new IllegalArgumentException("annotation FIB field is unavailable");
        }
        return new TableSlice(LittleEndian.getInt(fib, pairOffset),
                LittleEndian.getInt(fib, pairOffset + Integer.BYTES));
    }

    private static String anchor(String mainText, int position) {
        if (position < 0 || position >= mainText.length()
                || mainText.charAt(position) != ANNOTATION_REFERENCE) {
            return "";
        }
        int start = Math.max(Math.max(mainText.lastIndexOf('\r', position - 1),
                mainText.lastIndexOf('\n', position - 1)), position - 80) + 1;
        int end = position + 1;
        while (end < mainText.length() && end - position <= 80
                && mainText.charAt(end) != '\r' && mainText.charAt(end) != '\n'
                && mainText.charAt(end) != ANNOTATION_REFERENCE) {
            end++;
        }
        String before = clean(mainText.substring(start, position));
        String after = clean(mainText.substring(position + 1, end));
        String anchor = before.isBlank() ? after : before;
        return anchor.length() <= 80 ? anchor : anchor.substring(anchor.length() - 80);
    }

    private static void removeFlattenedComments(Document document, List<String> comments) {
        List<String> visibleComments = comments.stream().filter(comment -> !comment.isBlank()).toList();
        List<Element> paragraphs = new ArrayList<>(document.select("p"));
        List<Element> matches = new ArrayList<>();
        int searchBefore = paragraphs.size();
        for (int comment = visibleComments.size() - 1; comment >= 0; comment--) {
            Element match = null;
            for (int index = searchBefore - 1; index >= 0; index--) {
                if (clean(paragraphs.get(index).text()).equals(visibleComments.get(comment))) {
                    match = paragraphs.get(index);
                    searchBefore = index;
                    break;
                }
            }
            if (match == null) {
                return;
            }
            matches.add(match);
        }
        matches.forEach(Element::remove);
    }

    private static String clean(String text) {
        return text == null ? "" : text.replace("\u0002", "").replace("\u0005", "")
                .replace("\u0007", "").replace("\uFFFD", "")
                .replaceAll("[\\x00-\\x1F\\x7F]", " ")
                .replaceAll("\\s+", " ").strip();
    }

    private record Reference(int position, String author) {
    }

    private record TableSlice(int offset, int length) {
    }
}
