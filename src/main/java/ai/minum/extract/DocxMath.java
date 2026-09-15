package ai.minum.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Converts common Office Math structures into searchable Markdown math. */
final class DocxMath {
    private static final long MAX_DOCUMENT_XML_SIZE = 16L * 1024 * 1024;
    private static final int MAX_XML_DEPTH = 256;
    private static final int MAX_EQUATIONS = 1024;
    private static final int MAX_RENDERED_LENGTH = 1024 * 1024;

    private DocxMath() {
    }

    static int restore(Document document, Path source) throws Exception {
        if (source == null) {
            return 0;
        }
        List<Equation> equations = read(source);
        if (equations.isEmpty()) {
            return 0;
        }
        Set<Element> used = Collections.newSetFromMap(new IdentityHashMap<>());
        int restored = 0;
        for (Equation equation : equations) {
            Element candidate = findExactTextContainer(document, equation.flattenedText(), used);
            if (candidate == null) {
                continue;
            }
            candidate.empty()
                    .addClass("mika-equation")
                    .attr("data-mika-latex", equation.latex())
                    .attr("data-mika-display", Boolean.toString(equation.display()))
                    .text(equation.flattenedText());
            used.add(candidate);
            restored++;
        }
        return restored;
    }

    private static List<Equation> read(Path source) throws Exception {
        byte[] xml;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            ZipEntry document = null;
            int matches = 0;
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if ("word/document.xml".equals(entry.getName())) {
                    document = entry;
                    matches++;
                }
            }
            if (matches != 1 || document == null || document.isDirectory()
                    || document.getSize() > MAX_DOCUMENT_XML_SIZE) {
                return List.of();
            }
            try (InputStream input = new LimitedInputStream(
                    archive.getInputStream(document), MAX_DOCUMENT_XML_SIZE)) {
                xml = input.readAllBytes();
            }
        }

        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        org.w3c.dom.Document sourceDocument = factory.newDocumentBuilder()
                .parse(new java.io.ByteArrayInputStream(xml));
        if (!withinDepthLimit(sourceDocument)) {
            return List.of();
        }
        NodeList nodes = sourceDocument.getElementsByTagNameNS("*", "oMath");
        List<Equation> equations = new ArrayList<>();
        for (int index = 0; index < nodes.getLength() && equations.size() < MAX_EQUATIONS; index++) {
            org.w3c.dom.Element math = (org.w3c.dom.Element) nodes.item(index);
            if (hasAncestor(math, "oMath")) {
                continue;
            }
            String flattened = text(math).strip();
            String latex = renderChildren(math).strip();
            if (!flattened.isBlank() && !latex.isBlank() && latex.length() <= MAX_RENDERED_LENGTH) {
                boolean display = hasAncestor(math, "oMathPara") || hasDescendant(math, "m");
                equations.add(new Equation(flattened, latex, display));
            }
        }
        return equations;
    }

    private static Element findExactTextContainer(Document document, String expected, Set<Element> used) {
        String normalizedExpected = normalize(expected);
        List<Element> matches = new ArrayList<>();
        for (Element candidate : document.select("p, td, th, li")) {
            if (used.contains(candidate) || !normalize(candidate.text()).equals(normalizedExpected)) {
                continue;
            }
            boolean matchingChild = candidate.select("p, td, th, li").stream()
                    .anyMatch(child -> child != candidate
                            && normalize(child.text()).equals(normalizedExpected));
            if (!matchingChild) {
                matches.add(candidate);
            }
        }
        // Without a source-to-XHTML position map, replacing one of several identical paragraphs can
        // mark ordinary prose as an equation. Preserve Tika's flat text in that ambiguous case.
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static String render(Node node) {
        if (node.getNodeType() == Node.TEXT_NODE) {
            return "";
        }
        String name = localName(node);
        return switch (name) {
            case "r", "e", "oMath", "oMathPara", "func", "box", "borderBox" -> renderChildren(node);
            case "t" -> latexText(node.getTextContent());
            case "sSup" -> group(child(node, "e")) + "^{" + render(child(node, "sup")) + "}";
            case "sSub" -> group(child(node, "e")) + "_{" + render(child(node, "sub")) + "}";
            case "sSubSup" -> group(child(node, "e")) + "_{" + render(child(node, "sub"))
                    + "}^{" + render(child(node, "sup")) + "}";
            case "f" -> "\\frac{" + render(child(node, "num")) + "}{" + render(child(node, "den")) + "}";
            case "rad" -> radical(node);
            case "d" -> delimiter(node);
            case "m" -> matrix(node, "matrix");
            case "nary" -> nary(node);
            case "limLow" -> group(child(node, "e")) + "_{" + render(child(node, "lim")) + "}";
            case "limUpp" -> group(child(node, "e")) + "^{" + render(child(node, "lim")) + "}";
            case "acc" -> accent(node);
            case "groupChr" -> render(child(node, "e"));
            case "eqArr" -> equationArray(node);
            case "num", "den", "sup", "sub", "deg", "lim", "mr" -> renderChildren(node);
            default -> isPropertyElement(name) ? "" : renderChildren(node);
        };
    }

    private static String radical(Node node) {
        String degree = render(child(node, "deg"));
        String body = render(child(node, "e"));
        return degree.isBlank() ? "\\sqrt{" + body + "}" : "\\sqrt[" + degree + "]{" + body + "}";
    }

    private static String delimiter(Node node) {
        String beginning = propertyValue(node, "begChr", "(");
        String ending = propertyValue(node, "endChr", ")");
        Node body = child(node, "e");
        Node matrix = child(body, "m");
        if (matrix != null && "[".equals(beginning) && "]".equals(ending)) {
            return matrix(matrix, "bmatrix");
        }
        if (matrix != null && "(".equals(beginning) && ")".equals(ending)) {
            return matrix(matrix, "pmatrix");
        }
        return "\\left" + delimiterCharacter(beginning) + render(body)
                + "\\right" + delimiterCharacter(ending);
    }

    private static String matrix(Node node, String environment) {
        List<String> rows = new ArrayList<>();
        for (Node row : children(node, "mr")) {
            List<String> cells = new ArrayList<>();
            for (Node cell : children(row, "e")) {
                cells.add(render(cell));
            }
            rows.add(String.join(" & ", cells));
        }
        return "\\begin{" + environment + "}" + String.join(" \\\\ ", rows)
                + "\\end{" + environment + "}";
    }

    private static String nary(Node node) {
        String operator = propertyValue(node, "chr", "∑");
        operator = switch (operator) {
            case "∑" -> "\\sum";
            case "∏" -> "\\prod";
            case "∫" -> "\\int";
            default -> latexText(operator);
        };
        String subscript = render(child(node, "sub"));
        String superscript = render(child(node, "sup"));
        return operator + (subscript.isBlank() ? "" : "_{" + subscript + "}")
                + (superscript.isBlank() ? "" : "^{" + superscript + "}")
                + " " + render(child(node, "e"));
    }

    private static String accent(Node node) {
        String accent = propertyValue(node, "chr", "̂");
        String command = switch (accent) {
            case "̂", "^" -> "\\hat";
            case "̄", "¯" -> "\\bar";
            case "⃗", "→" -> "\\vec";
            default -> "\\overset{" + latexText(accent) + "}";
        };
        return command + "{" + render(child(node, "e")) + "}";
    }

    private static String equationArray(Node node) {
        List<String> rows = new ArrayList<>();
        for (Node expression : children(node, "e")) {
            rows.add(render(expression));
        }
        return "\\begin{aligned}" + String.join(" \\\\ ", rows) + "\\end{aligned}";
    }

    private static String renderChildren(Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            result.append(render(child));
        }
        return result.toString();
    }

    private static String group(Node node) {
        String value = render(node);
        return value.length() <= 1 ? value : "{" + value + "}";
    }

    private static String text(Node node) {
        StringBuilder result = new StringBuilder();
        collectText(node, result);
        return result.toString();
    }

    private static void collectText(Node node, StringBuilder result) {
        if ("t".equals(localName(node))) {
            result.append(node.getTextContent());
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectText(child, result);
        }
    }

    private static String latexText(String value) {
        StringBuilder escaped = new StringBuilder(value.length());
        value.codePoints().forEach(character -> {
            switch (character) {
                case '\\' -> escaped.append("\\backslash ");
                case '{' -> escaped.append("\\{");
                case '}' -> escaped.append("\\}");
                case '#', '$', '%', '&', '_' -> escaped.append('\\').appendCodePoint(character);
                case 'π' -> escaped.append("\\pi ");
                case '∞' -> escaped.append("\\infty ");
                case '×' -> escaped.append("\\times ");
                case '÷' -> escaped.append("\\div ");
                case '≤' -> escaped.append("\\le ");
                case '≥' -> escaped.append("\\ge ");
                case '≠' -> escaped.append("\\ne ");
                default -> escaped.appendCodePoint(character);
            }
        });
        return escaped.toString();
    }

    private static String delimiterCharacter(String value) {
        return switch (value) {
            case "{" -> "\\{";
            case "}" -> "\\}";
            case "" -> ".";
            default -> latexText(value);
        };
    }

    private static String propertyValue(Node node, String property, String fallback) {
        Node valueNode = firstDescendant(node, property);
        if (valueNode == null || valueNode.getAttributes() == null) {
            return fallback;
        }
        for (int index = 0; index < valueNode.getAttributes().getLength(); index++) {
            Node attribute = valueNode.getAttributes().item(index);
            if ("val".equals(localName(attribute))) {
                return attribute.getNodeValue();
            }
        }
        return fallback;
    }

    private static boolean isPropertyElement(String name) {
        return name.endsWith("Pr") || switch (name) {
            case "begChr", "endChr", "chr", "ctrlPr", "type", "grow", "hideDeg" -> true;
            default -> false;
        };
    }

    private static Node child(Node node, String name) {
        if (node == null) {
            return null;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(localName(child))) {
                return child;
            }
        }
        return null;
    }

    private static List<Node> children(Node node, String name) {
        List<Node> result = new ArrayList<>();
        if (node != null) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (name.equals(localName(child))) {
                    result.add(child);
                }
            }
        }
        return result;
    }

    private static Node firstDescendant(Node node, String name) {
        if (node == null) {
            return null;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(localName(child))) {
                return child;
            }
            Node nested = firstDescendant(child, name);
            if (nested != null) {
                return nested;
            }
        }
        return null;
    }

    private static boolean hasDescendant(Node node, String name) {
        return firstDescendant(node, name) != null;
    }

    private static boolean hasAncestor(Node node, String name) {
        for (Node parent = node.getParentNode(); parent != null; parent = parent.getParentNode()) {
            if (name.equals(localName(parent))) {
                return true;
            }
        }
        return false;
    }

    private static String localName(Node node) {
        String name = node.getLocalName();
        return name == null ? "" : name;
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static boolean withinDepthLimit(Node root) {
        ArrayDeque<NodeDepth> pending = new ArrayDeque<>();
        pending.push(new NodeDepth(root, 0));
        while (!pending.isEmpty()) {
            NodeDepth current = pending.pop();
            if (current.depth() > MAX_XML_DEPTH) {
                return false;
            }
            for (Node child = current.node().getFirstChild(); child != null; child = child.getNextSibling()) {
                pending.push(new NodeDepth(child, current.depth() + 1));
            }
        }
        return true;
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // The JDK provider supports these features; keep compatibility with alternate providers.
        }
    }

    private record Equation(String flattenedText, String latex, boolean display) {
    }

    private record NodeDepth(Node node, int depth) {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit + 1;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("DOCX document.xml exceeds safety limit");
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (remaining <= 0) {
                throw new IOException("DOCX document.xml exceeds safety limit");
            }
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
