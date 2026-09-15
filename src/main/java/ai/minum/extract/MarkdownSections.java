package ai.minum.extract;

import java.util.ArrayList;
import java.util.List;

/** Creates logical storage sections without changing the combined Markdown. */
final class MarkdownSections {
    private static final int TARGET_CHARACTERS = 64 * 1024;

    private MarkdownSections() {
    }

    static List<String> split(String markdown) {
        if (markdown == null || markdown.isBlank()) {
            return List.of(markdown == null ? "" : markdown);
        }
        List<String> structural = splitAtStructure(markdown);
        List<String> result = new ArrayList<>();
        for (String section : structural) {
            splitOversized(section, result);
        }
        return result;
    }

    private static List<String> splitAtStructure(String markdown) {
        List<String> result = new ArrayList<>();
        int start = 0;
        for (int index = 2; index < markdown.length(); index++) {
            if (markdown.charAt(index - 2) != '\n' || markdown.charAt(index - 1) != '\n') {
                continue;
            }
            boolean heading = isHeading(markdown, index);
            boolean pageBreak = markdown.startsWith("---\n", index)
                    || markdown.substring(index).equals("---");
            if (heading || pageBreak) {
                result.add(markdown.substring(start, index - 2));
                start = index;
            }
        }
        result.add(markdown.substring(start));
        return result.stream().filter(section -> !section.isBlank()).toList();
    }

    private static boolean isHeading(String markdown, int index) {
        int hashes = 0;
        while (index + hashes < markdown.length() && markdown.charAt(index + hashes) == '#'
                && hashes < 7) {
            hashes++;
        }
        return hashes >= 1 && hashes <= 6 && index + hashes < markdown.length()
                && (markdown.charAt(index + hashes) == ' ' || markdown.charAt(index + hashes) == '\t');
    }

    private static void splitOversized(String section, List<String> result) {
        int offset = 0;
        while (section.length() - offset > TARGET_CHARACTERS) {
            int target = offset + TARGET_CHARACTERS;
            int boundary = section.lastIndexOf("\n\n", target);
            if (boundary <= offset) {
                // ExtractResult.getMarkdown() joins pages with two line feeds. Splitting inside a
                // paragraph would therefore rewrite the source. Prefer the next real paragraph
                // boundary, and keep one large section when the paragraph has no safe boundary.
                boundary = section.indexOf("\n\n", target);
                if (boundary < 0) {
                    break;
                }
            }
            result.add(section.substring(offset, boundary));
            offset = boundary + 2;
        }
        if (offset < section.length()) {
            result.add(section.substring(offset));
        }
    }
}
