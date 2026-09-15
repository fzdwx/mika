package ai.minum.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.TextNode;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Restores Word ruby/phonetic annotations that Tika currently emits as base text only. */
final class DocxPhonetics {
    private static final long MAX_DOCUMENT_XML_SIZE = 16L * 1024 * 1024;
    private static final int MAX_ANNOTATIONS = 4096;
    private static final int MAX_XML_DEPTH = 256;

    private DocxPhonetics() {
    }

    static int restore(Document document, Path source) throws Exception {
        List<AnnotatedParagraph> paragraphs = read(source);
        Map<String, List<AnnotatedParagraph>> byText = new LinkedHashMap<>();
        for (AnnotatedParagraph paragraph : paragraphs) {
            byText.computeIfAbsent(normalize(paragraph.baseText()), ignored -> new ArrayList<>())
                    .add(paragraph);
        }
        int restored = 0;
        for (Map.Entry<String, List<AnnotatedParagraph>> entry : byText.entrySet()) {
            List<Element> candidates = matchingContainers(document, entry.getKey());
            if (candidates.size() != entry.getValue().size()) {
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                Element candidate = candidates.get(index);
                // Another source-aware pass may already have restored an equation in this
                // paragraph. Replace only an unambiguous plain-text occurrence so the equation
                // structure survives; otherwise retain the readable base text.
                if (candidate.selectFirst(".mika-equation") != null) {
                    int mixedRestored = restoreWithoutRebuilding(candidate, entry.getValue().get(index));
                    if (mixedRestored > 0) {
                        candidate.addClass("mika-phonetic-restored");
                        restored += mixedRestored;
                    }
                    continue;
                }
                candidate.empty();
                for (Segment segment : entry.getValue().get(index).segments()) {
                    if (segment.annotated()) {
                        candidate.appendElement("span").addClass("mika-phonetic")
                                .text(segment.renderedText());
                    } else {
                        candidate.appendText(segment.renderedText());
                    }
                }
                restored += entry.getValue().get(index).annotationCount();
            }
        }
        return restored;
    }

    private static int restoreWithoutRebuilding(Element candidate, AnnotatedParagraph paragraph) {
        int restored = 0;
        for (Segment segment : paragraph.segments()) {
            if (!segment.annotated()) {
                continue;
            }
            List<TextNode> matches = new ArrayList<>();
            boolean ambiguous = false;
            for (Element element : candidate.getAllElements()) {
                if (element.hasClass("mika-equation")) {
                    continue;
                }
                for (TextNode text : element.textNodes()) {
                    int first = text.getWholeText().indexOf(segment.baseText());
                    if (first < 0) {
                        continue;
                    }
                    if (first != text.getWholeText().lastIndexOf(segment.baseText())) {
                        ambiguous = true;
                        break;
                    }
                    matches.add(text);
                }
                if (ambiguous) {
                    break;
                }
            }
            if (!ambiguous && matches.size() == 1) {
                TextNode match = matches.getFirst();
                match.text(match.getWholeText().replace(segment.baseText(), segment.renderedText()));
                restored++;
            }
        }
        return restored;
    }

    private static List<AnnotatedParagraph> read(Path source) throws Exception {
        byte[] xml;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            ZipEntry entry = archive.getEntry("word/document.xml");
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_DOCUMENT_XML_SIZE) {
                return List.of();
            }
            try (InputStream input = new LimitedInputStream(
                    archive.getInputStream(entry), MAX_DOCUMENT_XML_SIZE)) {
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

        NodeList nodes = sourceDocument.getElementsByTagNameNS("*", "p");
        List<AnnotatedParagraph> result = new ArrayList<>();
        int total = 0;
        for (int index = 0; index < nodes.getLength() && total < MAX_ANNOTATIONS; index++) {
            Node paragraph = nodes.item(index);
            String namespace = paragraph.getNamespaceURI();
            if (namespace == null || !namespace.contains("wordprocessingml")) {
                continue;
            }
            List<Segment> segments = new ArrayList<>();
            collectSegments(paragraph, segments);
            int count = (int) segments.stream().filter(Segment::annotated).count();
            if (count == 0 || total + count > MAX_ANNOTATIONS) {
                continue;
            }
            String base = segments.stream().map(Segment::baseText).reduce("", String::concat);
            if (!base.isBlank()) {
                result.add(new AnnotatedParagraph(base, List.copyOf(segments), count));
                total += count;
            }
        }
        return result;
    }

    private static void collectSegments(Node node, List<Segment> result) {
        if ("ruby".equals(localName(node))) {
            String reading = descendantText(child(node, "rt"));
            String base = descendantText(child(node, "rubyBase"));
            if (!base.isBlank() && !reading.isBlank()) {
                result.add(new Segment(base, base + "（" + reading + "）", true));
            } else if (!base.isBlank()) {
                result.add(new Segment(base, base, false));
            }
            return;
        }
        String name = localName(node);
        if ("t".equals(name)) {
            addPlainText(result, node.getTextContent());
            return;
        }
        if ("tab".equals(name)) {
            addPlainText(result, "\t");
            return;
        }
        if ("br".equals(name) || "cr".equals(name)) {
            addPlainText(result, "\n");
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectSegments(child, result);
        }
    }

    private static void addPlainText(List<Segment> result, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!result.isEmpty() && !result.getLast().annotated()) {
            Segment previous = result.removeLast();
            result.add(new Segment(previous.baseText() + text, previous.renderedText() + text, false));
        } else {
            result.add(new Segment(text, text, false));
        }
    }

    private static List<Element> matchingContainers(Document document, String expected) {
        List<Element> matches = new ArrayList<>();
        for (Element candidate : document.select("p, td, th, li")) {
            if (!normalize(candidate.text()).equals(expected)) {
                continue;
            }
            boolean matchingChild = candidate.select("p, td, th, li").stream()
                    .anyMatch(child -> child != candidate && normalize(child.text()).equals(expected));
            if (!matchingChild) {
                matches.add(candidate);
            }
        }
        return matches;
    }

    private static String descendantText(Node node) {
        if (node == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        appendText(node, text);
        return text.toString();
    }

    private static void appendText(Node node, StringBuilder text) {
        if ("t".equals(localName(node))) {
            text.append(node.getTextContent());
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            appendText(child, text);
        }
    }

    private static Node child(Node node, String name) {
        if (node != null) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (name.equals(localName(child))) {
                    return child;
                }
            }
        }
        return null;
    }

    private static String localName(Node node) {
        return node == null || node.getLocalName() == null ? "" : node.getLocalName();
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private static void setFeature(DocumentBuilderFactory factory, String name, boolean value) {
        try {
            factory.setFeature(name, value);
        } catch (Exception ignored) {
            // The JDK provider supports these features; retain compatibility with alternates.
        }
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

    private record Segment(String baseText, String renderedText, boolean annotated) {
    }

    private record AnnotatedParagraph(String baseText, List<Segment> segments, int annotationCount) {
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
                throw new IOException("DOCX document.xml exceeds phonetic annotation limit");
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
                throw new IOException("DOCX document.xml exceeds phonetic annotation limit");
            }
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
