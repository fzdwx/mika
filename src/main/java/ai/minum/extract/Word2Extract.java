package ai.minum.extract;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recovers text from the flat binary format written by Word for Windows 2.x.
 *
 * <p>Unlike Word 6 and later DOC files, a Word 2 file is not an OLE2 container. Normally its FIB is
 * followed by a contiguous text section; fast-saved files instead map character positions to
 * disjoint byte ranges through a CLX piece table. Formatting tables are deliberately not
 * interpreted here, so binary formatting data can never leak into the result. Main text, footnotes,
 * headers/footers, and annotations are retained; executable WordBasic macro streams are skipped.</p>
 */
final class Word2Extract {
    private static final int WORD2_IDENT = 0xa5db;
    private static final int WORD2_FIB = 0x2d;
    private static final int MIN_FIB_SIZE = 326;
    private static final int FLAG_COMPLEX = 0x04;
    private static final int FLAG_HAS_PICTURES = 0x08;
    private static final int FLAG_ENCRYPTED = 0x01;
    private static final int FLAG_EXTENDED_CHARACTERS = 0x10;
    private static final char BULLET_MARKER = '\ue000';
    private static final char PAGE_BREAK_MARKER = '\ue001';
    private static final char IMAGE_MARKER = '\ue002';
    private static final char CELL_MARKER = '\ue003';
    private static final char ROW_MARKER = '\ue004';
    private static final Pattern SYMBOL_FIELD = Pattern.compile("(?i)^\\s*SYMBOL\\s+(\\d+)(?:\\s|$)");

    private Word2Extract() {
    }

    static boolean supports(byte[] signature) {
        return signature.length >= 4
                && unsignedShort(signature, 0) == WORD2_IDENT
                && (unsignedShort(signature, 2) & 0xff) == WORD2_FIB;
    }

    static ExtractResult extract(InputStream stream) throws IOException {
        byte[] document = stream.readAllBytes();
        Header header = Header.read(document);
        if (header.encrypted()) {
            throw new UnsupportedOperationException("Encrypted Word 2.0 documents are not supported");
        }

        int bytesPerCharacter = header.extendedCharacters() ? 2 : 1;
        Charset charset = header.extendedCharacters()
                ? StandardCharsets.UTF_16LE
                : charset(header.characterSet(), header.languageId());
        DecodedText decoded = header.complex()
                ? decodePieceTable(document, header, bytesPerCharacter, charset)
                : decodeContiguousText(document, header, bytesPerCharacter, charset);
        DocumentText text = DocumentText.split(decoded.text(), header);
        boolean hasInlinePicture = text.visibleText().indexOf('\u0001') >= 0;
        boolean containsPicture = header.hasPictures() || hasInlinePicture;
        String markdown = text.toMarkdown();
        if (header.hasPictures() && !hasInlinePicture) {
            markdown = markdown.isBlank() ? "[Image][ImageEnd]" : markdown + "\n\n[Image][ImageEnd]";
        }
        ExtractResult result = ExtractResult.successOfOne(markdown)
                .setHasTable(markdown.contains("\n| ---"));
        if (decoded.replacedInvalidBytes()) {
            result.addWarning("Invalid Word 2.0 characters were replaced while decoding " + charset.name());
        }
        if (containsPicture) {
            result.setHasImage(true);
            result.addWarning("Word 2.0 text was extracted, but legacy picture data could not be decoded");
        }
        return result;
    }

    private static DecodedText decodeContiguousText(
            byte[] document, Header header, int bytesPerCharacter, Charset charset) {
        long textByteCount = Math.multiplyExact((long) header.totalCharacters(), bytesPerCharacter);
        long textEnd = Math.addExact((long) header.textStart(), textByteCount);
        if (header.textStart() < MIN_FIB_SIZE
                || textEnd > header.textEnd()
                || header.textEnd() > document.length) {
            throw invalid("text bounds are outside the file");
        }
        byte[] textBytes = java.util.Arrays.copyOfRange(
                document, header.textStart(), Math.toIntExact(textEnd));
        return decodeDocumentBytes(textBytes, header, bytesPerCharacter, charset);
    }

    private static DecodedText decodePieceTable(
            byte[] document, Header header, int bytesPerCharacter, Charset charset) {
        int clxStart = header.complexTableStart();
        int clxLength = header.complexTableLength();
        long clxEnd = Math.addExact((long) clxStart, clxLength);
        if (clxStart < MIN_FIB_SIZE || clxLength < 3 || clxEnd > document.length) {
            throw invalid("fast-save table is outside the file");
        }

        int cursor = clxStart;
        while (cursor < clxEnd) {
            int recordType = Byte.toUnsignedInt(document[cursor++]);
            if (cursor + 2 > clxEnd) {
                throw invalid("truncated fast-save table record");
            }
            int recordLength = unsignedShort(document, cursor);
            cursor += 2;
            if ((long) cursor + recordLength > clxEnd) {
                throw invalid("fast-save table record is outside the CLX");
            }
            if (recordType == 2) {
                return decodePieces(document, cursor, recordLength, header,
                        bytesPerCharacter, charset);
            }
            cursor += recordLength;
        }
        throw invalid("fast-save table has no piece table");
    }

    private static DecodedText decodePieces(
            byte[] document, int tableStart, int tableLength, Header header,
            int bytesPerCharacter, Charset charset) {
        int characterCount = header.totalCharacters();
        if (tableLength < 4 || (tableLength - 4) % 12 != 0) {
            throw invalid("malformed fast-save piece table length");
        }
        int pieceCount = (tableLength - 4) / 12;
        int descriptorStart = tableStart + Math.multiplyExact(pieceCount + 1, 4);
        if (pieceCount == 0) {
            if (characterCount == 0) {
                return new DecodedText("", false);
            }
            throw invalid("empty fast-save piece table");
        }

        int previousPosition = signedInt(document, tableStart);
        if (previousPosition != 0) {
            throw invalid("fast-save piece table does not start at character zero");
        }
        int lastPosition = signedInt(document, tableStart + pieceCount * 4);
        if (lastPosition < characterCount) {
            throw invalid("fast-save piece table is shorter than the declared text");
        }

        int expectedBytes = Math.toIntExact(Math.multiplyExact(
                (long) characterCount, bytesPerCharacter));
        ByteArrayOutputStream textBytes = new ByteArrayOutputStream(expectedBytes);
        for (int piece = 0; piece < pieceCount && previousPosition < characterCount; piece++) {
            int nextPosition = signedInt(document, tableStart + (piece + 1) * 4);
            if (nextPosition < previousPosition) {
                throw invalid("fast-save character positions are not sorted");
            }
            int endPosition = Math.min(nextPosition, characterCount);
            int pieceCharacters = endPosition - previousPosition;
            if (pieceCharacters > 0) {
                int descriptor = descriptorStart + piece * 8;
                int fileOffset = signedInt(document, descriptor + 2);
                int byteCount = Math.multiplyExact(pieceCharacters, bytesPerCharacter);
                long pieceEnd = Math.addExact((long) fileOffset, byteCount);
                if (fileOffset < MIN_FIB_SIZE || pieceEnd > document.length) {
                    throw invalid("fast-save text piece is outside the file");
                }
                textBytes.write(document, fileOffset, byteCount);
            }
            previousPosition = nextPosition;
        }
        if (textBytes.size() != expectedBytes) {
            throw invalid("fast-save pieces do not cover the main text");
        }
        byte[] joined = textBytes.toByteArray();
        return decodeDocumentBytes(joined, header, bytesPerCharacter, charset);
    }

    private static DecodedText decodeDocumentBytes(
            byte[] textBytes, Header header, int bytesPerCharacter, Charset charset) {
        int macroStart = Math.multiplyExact(
                Math.addExact(Math.addExact(header.mainTextCharacters(), header.footnoteCharacters()),
                        header.headerCharacters()),
                bytesPerCharacter);
        int macroEnd = Math.addExact(macroStart,
                Math.multiplyExact(header.macroCharacters(), bytesPerCharacter));
        java.util.Arrays.fill(textBytes, macroStart, macroEnd, (byte) 0);
        return decode(textBytes, 0, textBytes.length, charset);
    }

    private static DecodedText decode(byte[] document, int offset, int length, Charset charset) {
        ByteBuffer input = ByteBuffer.wrap(document, offset, length).slice();
        try {
            String text = charset.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(input)
                    .toString();
            return new DecodedText(text, false);
        } catch (CharacterCodingException invalidCharacters) {
            input.rewind();
            try {
                String text = charset.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE)
                        .replaceWith("?")
                        .decode(input)
                        .toString();
                return new DecodedText(text, true);
            } catch (CharacterCodingException impossibleWithReplacement) {
                throw new IllegalArgumentException("Could not decode Word 2.0 text", impossibleWithReplacement);
            }
        }
    }

    static String toMarkdown(String rawText) {
        String fieldsResolved = normalizeTableControls(resolveFields(rawText));
        StringBuilder normalized = new StringBuilder(fieldsResolved.length());
        for (int index = 0; index < fieldsResolved.length(); index++) {
            char character = fieldsResolved.charAt(index);
            switch (character) {
                case '\r' -> {
                    normalized.append('\n');
                    if (index + 1 < fieldsResolved.length() && fieldsResolved.charAt(index + 1) == '\n') {
                        index++;
                    }
                }
                case '\n', '\t' -> normalized.append(character);
                case '\u0001' -> normalized.append(IMAGE_MARKER);
                case '\u000b', '\u000e' -> normalized.append('\n');
                case '\u000c' -> normalized.append('\n').append(PAGE_BREAK_MARKER).append('\n');
                case '\u001e' -> normalized.append('\u2011');
                case '\u001f' -> {
                    // Optional hyphen: omit unless Word chose to render it at a line break.
                }
                case CELL_MARKER -> normalized.append(CELL_MARKER);
                case ROW_MARKER -> normalized.append('\n');
                default -> {
                    if (character >= 0x20 || character == BULLET_MARKER) {
                        normalized.append(character);
                    }
                }
            }
        }

        String markdown = Markdown.fromText(normalized.toString())
                .replaceAll("(?m)^[\\t ]*" + BULLET_MARKER + "[\\t ]*", "- ")
                .replace(BULLET_MARKER, '\u2022')
                .replace(String.valueOf(PAGE_BREAK_MARKER), "\n\n---\n\n")
                .replace(String.valueOf(IMAGE_MARKER), "[Image][ImageEnd]")
                .replaceAll("(?m)[\\t ]+$", "")
                .replaceAll("\\n{3,}", "\n\n");
        return tabularParagraphsToMarkdown(markdown.strip());
    }

    private static String normalizeTableControls(String text) {
        String cell = String.valueOf(CELL_MARKER);
        String row = String.valueOf(ROW_MARKER);
        return text.replace("\r\u0007", cell)
                .replace("\u0007", cell)
                .replaceAll("[\\r\\n]*" + cell + "[\\r\\n]*" + cell + "[\\r\\n]*", row)
                .replaceAll("[\\r\\n]*" + cell + "[\\r\\n]*", cell);
    }

    private static String tabularParagraphsToMarkdown(String markdown) {
        String[] lines = markdown.split("\\n", -1);
        StringBuilder output = new StringBuilder(markdown.length() + 128);
        for (int index = 0; index < lines.length;) {
            String[] cells = tableCells(lines[index]);
            if (cells == null) {
                // A leading source tab is paragraph indentation; in Markdown it would accidentally create code.
                appendLine(output, lines[index++].replaceFirst("^\\t+", ""));
                continue;
            }

            int columns = cells.length;
            if (!output.isEmpty() && (output.length() < 2
                    || output.charAt(output.length() - 2) != '\n')) {
                output.append('\n');
            }
            appendTableRow(output, cells);
            String[] separator = new String[columns];
            java.util.Arrays.fill(separator, "---");
            appendTableRow(output, separator);
            index++;
            while (index < lines.length) {
                if (lines[index].isBlank()) {
                    int next = index + 1;
                    while (next < lines.length && lines[next].isBlank()) {
                        next++;
                    }
                    if (next < lines.length) {
                        String[] nextCells = tableCells(lines[next]);
                        if (nextCells != null && nextCells.length == columns) {
                            index = next;
                            continue;
                        }
                    }
                    break;
                }
                String[] nextCells = tableCells(lines[index]);
                if (nextCells == null || nextCells.length != columns) {
                    break;
                }
                appendTableRow(output, nextCells);
                index++;
            }
            if (index < lines.length && !lines[index].isBlank()) {
                output.append('\n');
            }
        }
        return output.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String[] tableCells(String line) {
        boolean explicitTableCells = line.indexOf(CELL_MARKER) >= 0;
        String[] cells = explicitTableCells
                ? line.strip().split(Pattern.quote(String.valueOf(CELL_MARKER)), -1)
                : line.strip().split("\\t", -1);
        int nonEmpty = 0;
        for (String cell : cells) {
            if (!cell.isBlank()) {
                nonEmpty++;
            }
        }
        if (explicitTableCells) {
            return cells.length >= 2 && nonEmpty >= 1 ? cells : null;
        }
        return cells.length >= 4 && nonEmpty >= 3 ? cells : null;
    }

    private static void appendTableRow(StringBuilder output, String[] cells) {
        output.append('|');
        for (String cell : cells) {
            output.append(' ').append(cell == null ? "" : cell.strip()).append(" |");
        }
        output.append('\n');
    }

    private static void appendLine(StringBuilder output, String line) {
        output.append(line).append('\n');
    }

    private static String resolveFields(String text) {
        StringBuilder output = new StringBuilder(text.length());
        Deque<Field> fields = new ArrayDeque<>();
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\u0013') {
                fields.push(new Field());
            } else if (character == '\u0014' && !fields.isEmpty()) {
                fields.peek().hasResult = true;
            } else if (character == '\u0015' && !fields.isEmpty()) {
                append(fields, output, fields.pop().render());
            } else {
                append(fields, output, String.valueOf(character));
            }
        }
        while (!fields.isEmpty()) {
            append(fields, output, fields.pop().render());
        }
        return output.toString();
    }

    private static void append(Deque<Field> fields, StringBuilder output, String value) {
        if (fields.isEmpty()) {
            output.append(value);
        } else {
            fields.peek().current().append(value);
        }
    }

    private static Charset charset(int characterSet, int languageId) {
        String name = switch (characterSet) {
            case 128 -> "Shift_JIS";
            case 129 -> "x-windows-949";
            case 134 -> "GBK";
            case 136 -> "Big5";
            case 161 -> "windows-1253";
            case 162 -> "windows-1254";
            case 163 -> "windows-1258";
            case 177 -> "windows-1255";
            case 178 -> "windows-1256";
            case 186 -> "windows-1257";
            case 204 -> "windows-1251";
            case 222 -> "windows-874";
            case 238 -> "windows-1250";
            default -> charsetForLanguage(languageId);
        };
        return Charset.forName(name);
    }

    private static String charsetForLanguage(int languageId) {
        int primaryLanguage = languageId & 0x03ff;
        return switch (primaryLanguage) {
            case 0x01, 0x20, 0x29 -> "windows-1256"; // Arabic, Urdu, Persian
            case 0x02, 0x19, 0x22, 0x23, 0x2f -> "windows-1251";
            case 0x04 -> switch (languageId) {
                case 0x0804, 0x1004 -> "GBK";       // Simplified Chinese
                default -> "Big5";                  // Traditional Chinese
            };
            case 0x05, 0x0e, 0x15, 0x18, 0x1a, 0x1b, 0x24 -> "windows-1250";
            case 0x08 -> "windows-1253";
            case 0x0d -> "windows-1255";
            case 0x11 -> "Shift_JIS";
            case 0x12 -> "x-windows-949";
            case 0x1e -> "windows-874";
            case 0x1f -> "windows-1254";
            case 0x25, 0x26, 0x27 -> "windows-1257";
            case 0x2a -> "windows-1258";
            default -> "windows-1252";
        };
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]) | Byte.toUnsignedInt(bytes[offset + 1]) << 8;
    }

    private static int signedInt(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset])
                | Byte.toUnsignedInt(bytes[offset + 1]) << 8
                | Byte.toUnsignedInt(bytes[offset + 2]) << 16
                | bytes[offset + 3] << 24;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException("Invalid Word 2.0 document: " + reason);
    }

    private static final class Field {
        private final StringBuilder instruction = new StringBuilder();
        private final StringBuilder result = new StringBuilder();
        private boolean hasResult;

        private StringBuilder current() {
            return hasResult ? result : instruction;
        }

        private String render() {
            if (hasResult) {
                return result.toString();
            }
            if (instruction.indexOf("\u0001") >= 0) {
                return String.valueOf(IMAGE_MARKER);
            }
            Matcher symbol = SYMBOL_FIELD.matcher(instruction);
            if (symbol.find() && "183".equals(symbol.group(1))) {
                return String.valueOf(BULLET_MARKER);
            }
            return "";
        }
    }

    private record DecodedText(String text, boolean replacedInvalidBytes) {
    }

    private record DocumentText(String main, String footnotes, String headers, String annotations) {
        private static DocumentText split(String text, Header header) {
            int mainEnd = header.mainTextCharacters();
            int footnotesEnd = Math.addExact(mainEnd, header.footnoteCharacters());
            int headersEnd = Math.addExact(footnotesEnd, header.headerCharacters());
            int macrosEnd = Math.addExact(headersEnd, header.macroCharacters());
            int annotationsEnd = Math.addExact(macrosEnd, header.annotationCharacters());
            if (annotationsEnd != text.length()) {
                throw invalid("decoded text length does not match the FIB subdocuments");
            }
            return new DocumentText(
                    text.substring(0, mainEnd),
                    text.substring(mainEnd, footnotesEnd),
                    text.substring(footnotesEnd, headersEnd),
                    text.substring(macrosEnd, annotationsEnd));
        }

        private String visibleText() {
            return main + footnotes + headers + annotations;
        }

        private String toMarkdown() {
            StringBuilder output = new StringBuilder();
            append(output, null, main);
            append(output, "Footnotes", footnotes);
            append(output, "Headers and footers", headers);
            append(output, "Comments", annotations);
            return output.toString();
        }

        private static void append(StringBuilder output, String heading, String text) {
            String markdown = Word2Extract.toMarkdown(text);
            if (markdown.isBlank()) {
                return;
            }
            if (!output.isEmpty()) {
                output.append("\n\n");
            }
            if (heading != null) {
                output.append("## ").append(heading).append("\n\n");
            }
            output.append(markdown);
        }
    }

    private record Header(int languageId, int flags, int secondaryFlags, int characterSet,
                          int textStart, int textEnd, int mainTextCharacters, int footnoteCharacters,
                          int headerCharacters, int macroCharacters, int annotationCharacters,
                          int complexTableStart, int complexTableLength) {
        private static Header read(byte[] document) {
            if (document.length < MIN_FIB_SIZE || !supports(document)) {
                throw invalid("missing or truncated FIB");
            }
            int mainTextCharacters = signedInt(document, 52);
            int footnoteCharacters = signedInt(document, 56);
            int headerCharacters = signedInt(document, 60);
            int macroCharacters = signedInt(document, 64);
            int annotationCharacters = signedInt(document, 68);
            if (mainTextCharacters < 0 || footnoteCharacters < 0 || headerCharacters < 0
                    || macroCharacters < 0 || annotationCharacters < 0) {
                throw invalid("negative subdocument text length");
            }
            return new Header(
                    unsignedShort(document, 6),
                    Byte.toUnsignedInt(document[10]),
                    Byte.toUnsignedInt(document[11]),
                    unsignedShort(document, 20),
                    signedInt(document, 24),
                    signedInt(document, 28),
                    mainTextCharacters,
                    footnoteCharacters,
                    headerCharacters,
                    macroCharacters,
                    annotationCharacters,
                    signedInt(document, 286),
                    unsignedShort(document, 290));
        }

        private int totalCharacters() {
            return Math.addExact(
                    Math.addExact(Math.addExact(mainTextCharacters, footnoteCharacters), headerCharacters),
                    Math.addExact(macroCharacters, annotationCharacters));
        }

        private boolean complex() {
            return (flags & FLAG_COMPLEX) != 0;
        }

        private boolean hasPictures() {
            return (flags & FLAG_HAS_PICTURES) != 0;
        }

        private boolean encrypted() {
            return (secondaryFlags & FLAG_ENCRYPTED) != 0;
        }

        private boolean extendedCharacters() {
            return (secondaryFlags & FLAG_EXTENDED_CHARACTERS) != 0;
        }
    }
}
