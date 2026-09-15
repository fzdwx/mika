package ai.minum.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Restores visible VML previews for embedded objects at their paragraph position. */
final class DocxObjectPreviews {
    private static final long MAX_XML_SIZE = 16L * 1024 * 1024;
    private static final int MAX_OBJECTS = 1024;
    private static final int MAX_XML_DEPTH = 256;

    private DocxObjectPreviews() {
    }

    static int restore(Document document, Path source) throws Exception {
        PackageData data = read(source);
        Map<String, List<ObjectParagraph>> byText = new LinkedHashMap<>();
        Set<Preview> placed = new HashSet<>();
        int restored = 0;
        for (ObjectParagraph paragraph : data.paragraphs()) {
            for (Segment segment : paragraph.segments()) {
                Preview preview = segment.preview();
                if (preview == null || preview.objectRelationship() == null) {
                    continue;
                }
                Element embedded = document.getElementById(preview.objectRelationship());
                if (embedded != null && embedded.hasClass("embedded")) {
                    appendPreview(embedded, preview);
                    placed.add(preview);
                    restored++;
                }
            }
            if (paragraph.segments().stream()
                    .noneMatch(segment -> segment.preview() != null && !placed.contains(segment.preview()))) {
                continue;
            }
            byText.computeIfAbsent(compact(paragraph.text()), ignored -> new ArrayList<>()).add(paragraph);
        }
        for (Map.Entry<String, List<ObjectParagraph>> entry : byText.entrySet()) {
            List<Element> candidates = matchingContainers(document, entry.getKey());
            if (candidates.size() != entry.getValue().size()) {
                continue;
            }
            for (int index = 0; index < candidates.size(); index++) {
                Element target = candidates.get(index);
                // Preserve source structures restored by earlier passes. Tika's readable body
                // remains preferable to deleting a formula or phonetic annotation merely to
                // guess the position of an object whose embedded node was absent.
                if (target.hasClass("mika-phonetic-restored")
                        || target.selectFirst(".mika-equation, .mika-phonetic") != null) {
                    continue;
                }
                target.empty();
                for (Segment segment : entry.getValue().get(index).segments()) {
                    if (segment.preview() == null) {
                        target.appendText(segment.text());
                    } else if (placed.contains(segment.preview())) {
                        continue;
                    } else {
                        appendPreview(target, segment.preview());
                        restored++;
                    }
                }
            }
        }
        return restored;
    }

    private static void appendPreview(Element target, Preview preview) {
        target.appendElement("img")
                .attr("src", "embedded:" + preview.imageName())
                .attr("alt", preview.description());
    }

    private static PackageData read(Path source) throws Exception {
        byte[] documentXml;
        byte[] relationshipsXml;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            documentXml = readPart(archive, "word/document.xml");
            relationshipsXml = readPart(archive, "word/_rels/document.xml.rels");
        }
        if (documentXml == null || relationshipsXml == null) {
            return new PackageData(List.of());
        }
        Map<String, String> images = imageRelationships(relationshipsXml);
        org.w3c.dom.Document xml = parseXml(documentXml);
        if (!withinDepthLimit(xml)) {
            return new PackageData(List.of());
        }
        NodeList nodes = xml.getElementsByTagNameNS("*", "p");
        List<ObjectParagraph> paragraphs = new ArrayList<>();
        int objectCount = 0;
        for (int index = 0; index < nodes.getLength() && objectCount < MAX_OBJECTS; index++) {
            Node paragraph = nodes.item(index);
            String namespace = paragraph.getNamespaceURI();
            if (namespace == null || !namespace.contains("wordprocessingml")) {
                continue;
            }
            List<Segment> segments = new ArrayList<>();
            collectSegments(paragraph, images, segments);
            int count = (int) segments.stream().filter(segment -> segment.preview() != null).count();
            if (count == 0 || objectCount + count > MAX_OBJECTS) {
                continue;
            }
            String text = segments.stream().map(Segment::text).reduce("", String::concat);
            paragraphs.add(new ObjectParagraph(text, List.copyOf(segments)));
            objectCount += count;
        }
        return new PackageData(List.copyOf(paragraphs));
    }

    private static void collectSegments(Node node, Map<String, String> images, List<Segment> result) {
        if ("object".equals(localName(node))) {
            Node image = firstDescendant(node, "imagedata");
            String relationship = relationshipAttribute(image, "id");
            String imageName = images.get(relationship);
            if (imageName != null) {
                Node ole = firstDescendant(node, "OLEObject");
                String program = attribute(ole, "ProgID");
                String objectRelationship = relationshipAttribute(ole, "id");
                String description = program != null && program.toLowerCase(Locale.ROOT).startsWith("equation.")
                        ? "Embedded equation (" + program + ")"
                        : program == null || program.isBlank() ? "Embedded object" : "Embedded object (" + program + ")";
                result.add(new Segment("", new Preview(imageName, description, objectRelationship)));
            }
            return;
        }
        String name = localName(node);
        if ("t".equals(name)) {
            addText(result, node.getTextContent());
            return;
        }
        if ("tab".equals(name)) {
            addText(result, "\t");
            return;
        }
        if ("br".equals(name) || "cr".equals(name)) {
            addText(result, "\n");
            return;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            collectSegments(child, images, result);
        }
    }

    private static void addText(List<Segment> result, String text) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!result.isEmpty() && result.getLast().preview() == null) {
            Segment previous = result.removeLast();
            result.add(new Segment(previous.text() + text, null));
        } else {
            result.add(new Segment(text, null));
        }
    }

    private static List<Element> matchingContainers(Document document, String expected) {
        List<Element> result = new ArrayList<>();
        for (Element candidate : document.select("p, td, th, li")) {
            if (!compact(candidate.text()).equals(expected)) {
                continue;
            }
            boolean matchingChild = candidate.select("p, td, th, li").stream()
                    .anyMatch(child -> child != candidate && compact(child.text()).equals(expected));
            if (!matchingChild) {
                result.add(candidate);
            }
        }
        return result;
    }

    private static byte[] readPart(ZipFile archive, String name) throws IOException {
        ZipEntry entry = archive.getEntry(name);
        if (entry == null || entry.isDirectory() || entry.getSize() > MAX_XML_SIZE) {
            return null;
        }
        try (InputStream input = new LimitedInputStream(archive.getInputStream(entry), MAX_XML_SIZE)) {
            return input.readAllBytes();
        }
    }

    private static Map<String, String> imageRelationships(byte[] xml) throws Exception {
        Map<String, String> result = new HashMap<>();
        NodeList nodes = parseXml(xml).getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            org.w3c.dom.Element relationship = (org.w3c.dom.Element) nodes.item(index);
            if (!relationship.getAttribute("Type").endsWith("/image")
                    || relationship.getAttribute("TargetMode").equalsIgnoreCase("External")) {
                continue;
            }
            String id = relationship.getAttribute("Id");
            String target = relationship.getAttribute("Target");
            if (!id.isBlank() && !target.isBlank()) {
                String part = Path.of("word").resolve(target).normalize().toString().replace('\\', '/');
                int slash = part.lastIndexOf('/');
                result.put(id, slash < 0 ? part : part.substring(slash + 1));
            }
        }
        return result;
    }

    private static org.w3c.dom.Document parseXml(byte[] xml) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        setFeature(factory, "http://apache.org/xml/features/disallow-doctype-decl", true);
        setFeature(factory, "http://xml.org/sax/features/external-general-entities", false);
        setFeature(factory, "http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory.newDocumentBuilder().parse(new ByteArrayInputStream(xml));
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

    private static String relationshipAttribute(Node node, String name) {
        if (node == null || node.getAttributes() == null) {
            return null;
        }
        for (int index = 0; index < node.getAttributes().getLength(); index++) {
            Node attribute = node.getAttributes().item(index);
            if (name.equals(localName(attribute)) && attribute.getNamespaceURI() != null
                    && attribute.getNamespaceURI().contains("relationships")) {
                return attribute.getNodeValue();
            }
        }
        return null;
    }

    private static String attribute(Node node, String name) {
        if (node == null || node.getAttributes() == null) {
            return null;
        }
        Node attribute = node.getAttributes().getNamedItem(name);
        return attribute == null ? null : attribute.getNodeValue();
    }

    private static String localName(Node node) {
        return node == null || node.getLocalName() == null ? "" : node.getLocalName();
    }

    private static String compact(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
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

    private record Preview(String imageName, String description, String objectRelationship) {
    }

    private record Segment(String text, Preview preview) {
    }

    private record ObjectParagraph(String text, List<Segment> segments) {
    }

    private record PackageData(List<ObjectParagraph> paragraphs) {
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
                throw new IOException("DOCX object XML exceeds size limit");
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
                throw new IOException("DOCX object XML exceeds size limit");
            }
            int read = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
