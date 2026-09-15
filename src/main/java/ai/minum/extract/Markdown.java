package ai.minum.extract;

import com.vladsch.flexmark.html2md.converter.FlexmarkHtmlConverter;
import com.vladsch.flexmark.html2md.converter.HtmlNodeRendererHandler;
import com.vladsch.flexmark.util.data.MutableDataSet;
import com.vladsch.flexmark.util.format.TableFormatOptions;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.safety.Safelist;

import java.util.Set;
import java.util.StringJoiner;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared rendering rules for extracted content. Existing Markdown bypasses this renderer. */
final class Markdown {
    private static final Pattern OFFICE_LINK_TARGET = Pattern.compile(
            "(?i)\\s*(?:[\\\"']|&quot;|&#0*34;|&#x0*22;)\\s*(?:\\\\t|\\t)\\s*"
                    + "(?:[\\\"']|&quot;|&#0*34;|&#x0*22;).*$");
    private static final Pattern TABLE_SEPARATOR_CELL = Pattern.compile(":?-{3,}:?");
    private static final Safelist COMPLEX_TABLE_HTML = new Safelist()
            .addTags("table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "colgroup", "col",
                    "p", "div", "span", "br", "strong", "b", "em", "i", "u", "s", "del", "code", "pre",
                    "ul", "ol", "li", "a", "sub", "sup")
            .addAttributes("td", "rowspan", "colspan")
            .addAttributes("th", "rowspan", "colspan")
            .addAttributes("col", "span")
            .addAttributes("colgroup", "span")
            .addAttributes("a", "href", "title")
            .addProtocols("a", "href", "http", "https", "mailto")
            .preserveRelativeLinks(true);

    private Markdown() {
    }

    static String fromHtml(String html) {
        Document document = Jsoup.parse(html);
        document.select("script, style, iframe").remove();
        document.outputSettings().prettyPrint(false);
        for (Element link : document.select("a[href]")) {
            String href = cleanOfficeLinkTarget(link.attr("href"));
            link.attr("href", href);
            if (!hasAllowedScheme(href, Set.of("http", "https", "mailto"))) {
                link.removeAttr("href");
            }
        }
        for (Element image : document.select("img[src]")) {
            if (!image.attr("src").startsWith("embedded:")
                    && !hasAllowedScheme(image.attr("src"), Set.of("http", "https"))) {
                image.text(image.attr("alt"));
                image.unwrap();
            }
        }
        // Portable Markdown has no underline or insertion syntax. Flexmark otherwise renders both
        // as ++text++, which leaks presentation markup into chunks and is not understood by common
        // Markdown renderers. Keep the visible text and discard only the unsupported decoration.
        document.select("u, ins").unwrap();
        // Word bookmarks and empty styled form runs have no visible content. Flexmark drops them
        // from ordinary Markdown, but they would otherwise survive inside preserved HTML tables.
        for (Element inline : document.select("a, b, strong, i, em, span, u, ins, s, del, code, sub, sup")) {
            if (inline.text().isBlank() && inline.select("img, br, table").isEmpty()) {
                inline.remove();
            }
        }
        // Office parsers commonly emit empty paragraphs and trailing breaks as layout artifacts.
        // Paragraph boundaries already become blank lines in Markdown, so retaining these adds noise.
        for (Element paragraph : document.select("p")) {
            if (paragraph.text().isBlank()
                    && paragraph.children().stream().allMatch(child -> child.tagName().equals("br"))) {
                paragraph.remove();
                continue;
            }
            Element last = paragraph.lastElementChild();
            if (last != null && last.tagName().equals("br") && last.nextSibling() == null) {
                last.remove();
            }
        }
        for (Element table : document.select("table")) {
            // A GFM table needs a header row. Do not mislabel data as a source header.
            if (!isComplexTable(table) && table.select("th").isEmpty()) {
                int columns = table.select("tr").stream().mapToInt(row -> row.childrenSize()).max().orElse(0);
                if (columns > 0) {
                    Element header = table.prependElement("thead").appendElement("tr");
                    for (int column = 0; column < columns; column++) {
                        header.appendElement("th");
                    }
                }
            }
        }
        MutableDataSet options = new MutableDataSet()
                .set(FlexmarkHtmlConverter.SETEXT_HEADINGS, false)
                .set(FlexmarkHtmlConverter.SKIP_ATTRIBUTES, true)
                .set(FlexmarkHtmlConverter.TYPOGRAPHIC_QUOTES, false)
                .set(FlexmarkHtmlConverter.TYPOGRAPHIC_SMARTS, false)
                .set(FlexmarkHtmlConverter.BR_AS_PARA_BREAKS, false)
                .set(FlexmarkHtmlConverter.IGNORE_TABLE_HEADING_AFTER_ROWS, false)
                .set(TableFormatOptions.FORMAT_TABLE_ADJUST_COLUMN_WIDTH, false)
                .set(FlexmarkHtmlConverter.UNORDERED_LIST_DELIMITER, '-');
        FlexmarkHtmlConverter simpleTables = FlexmarkHtmlConverter.builder(options).build();
        String markdown = FlexmarkHtmlConverter.builder(options)
                .htmlNodeRendererFactory(ignored -> () -> Set.of(
                        new HtmlNodeRendererHandler<>("table", Element.class, (table, context, out) -> {
                            // GFM cannot represent merged or nested cells. Keep just these tables as HTML.
                            if (isComplexTable(table)) {
                                String safeTable = Jsoup.clean(table.outerHtml(), "", COMPLEX_TABLE_HTML,
                                        document.outputSettings());
                                out.blankLine().append(safeTable).blankLine();
                            } else {
                                out.blankLine().append(compactTableSeparators(
                                        simpleTables.convert(table.outerHtml()))).blankLine();
                            }
                        })))
                .build().convert(document.body().html()).strip();
        return markdown;
    }

    private static String cleanOfficeLinkTarget(String href) {
        Matcher target = OFFICE_LINK_TARGET.matcher(href);
        return (target.find() ? href.substring(0, target.start()) : href).strip();
    }

    private static String compactTableSeparators(String markdown) {
        String[] lines = markdown.split("\\n", -1);
        StringBuilder compact = new StringBuilder(markdown.length());
        int nonBlankLine = 0;
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                compact.append('\n');
            }
            String line = lines[index];
            compact.append(nonBlankLine == 1 ? compactTableSeparator(line) : line);
            if (!line.isBlank()) {
                nonBlankLine++;
            }
        }
        return compact.toString();
    }

    private static String compactTableSeparator(String line) {
        int contentStart = 0;
        while (contentStart < line.length() && Character.isWhitespace(line.charAt(contentStart))) {
            contentStart++;
        }
        String indent = line.substring(0, contentStart);
        String table = line.substring(contentStart).strip();
        if (!table.contains("|")) {
            return line;
        }
        boolean leadingPipe = table.startsWith("|");
        boolean trailingPipe = table.endsWith("|");
        String cells = table.substring(leadingPipe ? 1 : 0, table.length() - (trailingPipe ? 1 : 0));
        String[] parts = cells.split("\\|", -1);
        if (parts.length == 0) {
            return line;
        }
        StringJoiner separator = new StringJoiner(" | ");
        for (String part : parts) {
            String cell = part.strip();
            if (!TABLE_SEPARATOR_CELL.matcher(cell).matches()) {
                return line;
            }
            separator.add((cell.startsWith(":") ? ":" : "") + "---" + (cell.endsWith(":") ? ":" : ""));
        }
        return indent + (leadingPipe ? "| " : "") + separator + (trailingPipe ? " |" : "");
    }

    private static boolean isComplexTable(Element table) {
        return !table.select("td[colspan], th[colspan], td[rowspan], th[rowspan]").isEmpty()
                || table.select("table").size() > 1;
    }

    private static boolean hasAllowedScheme(String reference, Set<String> allowedSchemes) {
        String compact = reference == null ? "" : reference.replaceAll("[\\x00-\\x20]", "").strip();
        int colon = compact.indexOf(':');
        int pathSeparator = compact.indexOf('/');
        if (colon < 0 || (pathSeparator >= 0 && pathSeparator < colon)) {
            return true;
        }
        return allowedSchemes.contains(compact.substring(0, colon).toLowerCase(java.util.Locale.ROOT));
    }

    static String fromText(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n').replace("\u0000", "");
        StringBuilder escaped = new StringBuilder();
        for (char c : normalized.toCharArray()) {
            if (c == '&') {
                escaped.append("&amp;");
            } else if (c == '<') {
                escaped.append("&lt;");
            } else if (c == '>') {
                escaped.append("&gt;");
            } else {
                if ("\\`*_{}[]#|".indexOf(c) >= 0) {
                    escaped.append('\\');
                }
                escaped.append(c);
            }
        }
        return escaped.toString()
                .replaceAll("(?m)^(\\h*)([-+])(?=\\h)", "$1\\\\$2")
                .replaceAll("(?m)^(\\h*)([=-])(?=[=-]*\\h*$)", "$1\\\\$2")
                .replaceAll("(?m)^(\\h*\\d+)([.)])(?=\\h)", "$1\\\\$2")
                .strip();
    }

    static String image(String key, String text) {
        String ocr = fromText(text);
        if (key == null || key.isBlank()) {
            return "[Image]" + ocr + "[ImageEnd]";
        }
        return "[Image](" + key + ")" + ocr + "[ImageEnd]";
    }
}
