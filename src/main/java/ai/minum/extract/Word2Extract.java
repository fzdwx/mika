package ai.minum.extract;

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
 * <p>Unlike Word 6 and later DOC files, a normal Word 2 file is not an OLE2 container. Its FIB is
 * followed by a contiguous text section. Formatting tables are deliberately not interpreted here;
 * the extractor uses the FIB bounds so binary formatting data can never leak into the result.</p>
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
        if (header.complex()) {
            throw new UnsupportedOperationException(
                    "Fast-saved Word 2.0 documents are not supported; open and save the file normally before extraction");
        }
        if (header.encrypted()) {
            throw new UnsupportedOperationException("Encrypted Word 2.0 documents are not supported");
        }

        int bytesPerCharacter = header.extendedCharacters() ? 2 : 1;
        long textByteCount = Math.multiplyExact((long) header.mainTextCharacters(), bytesPerCharacter);
        long textEnd = Math.addExact((long) header.textStart(), textByteCount);
        if (header.textStart() < MIN_FIB_SIZE
                || textEnd > header.textEnd()
                || header.textEnd() > document.length) {
            throw invalid("text bounds are outside the file");
        }

        Charset charset = header.extendedCharacters()
                ? StandardCharsets.UTF_16LE
                : charset(header.characterSet(), header.languageId());
        DecodedText decoded = decode(document, header.textStart(), Math.toIntExact(textByteCount), charset);
        String rawText = decoded.text();
        boolean containsPicture = header.hasPictures() || rawText.indexOf('\u0001') >= 0;
        String markdown = toMarkdown(rawText);
        if (header.hasPictures() && rawText.indexOf('\u0001') < 0) {
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
        String fieldsResolved = resolveFields(rawText);
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
                case '\u0007' -> normalized.append('\t');
                case '\u000b', '\u000e' -> normalized.append('\n');
                case '\u000c' -> normalized.append('\n').append(PAGE_BREAK_MARKER).append('\n');
                case '\u001e' -> normalized.append('\u2011');
                case '\u001f' -> {
                    // Optional hyphen: omit unless Word chose to render it at a line break.
                }
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
            appendTableRow(output, new String[columns]);
            String[] separator = new String[columns];
            java.util.Arrays.fill(separator, "---");
            appendTableRow(output, separator);
            appendTableRow(output, cells);
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
        }
        return output.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String[] tableCells(String line) {
        String[] cells = line.strip().split("\\t", -1);
        int nonEmpty = 0;
        for (String cell : cells) {
            if (!cell.isBlank()) {
                nonEmpty++;
            }
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
            Matcher symbol = SYMBOL_FIELD.matcher(instruction);
            if (symbol.find() && "183".equals(symbol.group(1))) {
                return String.valueOf(BULLET_MARKER);
            }
            return "";
        }
    }

    private record DecodedText(String text, boolean replacedInvalidBytes) {
    }

    private record Header(int languageId, int flags, int secondaryFlags, int characterSet,
                          int textStart, int textEnd, int mainTextCharacters) {
        private static Header read(byte[] document) {
            if (document.length < MIN_FIB_SIZE || !supports(document)) {
                throw invalid("missing or truncated FIB");
            }
            int mainTextCharacters = signedInt(document, 52);
            if (mainTextCharacters < 0) {
                throw invalid("negative main text length");
            }
            return new Header(
                    unsignedShort(document, 6),
                    Byte.toUnsignedInt(document[10]),
                    Byte.toUnsignedInt(document[11]),
                    unsignedShort(document, 20),
                    signedInt(document, 24),
                    signedInt(document, 28),
                    mainTextCharacters);
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
