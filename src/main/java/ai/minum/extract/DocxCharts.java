package ai.minum.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Restores structured chart cache values, including charts in headers and footers. */
final class DocxCharts {
    private static final int MAX_PART_BYTES = 16 * 1024 * 1024;
    private static final long MAX_TOTAL_PART_BYTES = 256L * 1024 * 1024;
    private static final int MAX_CHARTS = 256;
    private static final int MAX_POINTS = 50_000;
    private static final int MAX_XML_DEPTH = 256;

    private DocxCharts() {
    }

    static int restore(Document document, Path source) throws Exception {
        PackageParts parts = readParts(source);
        List<ChartData> charts = new ArrayList<>();
        for (ChartReference reference : parts.visibleCharts()) {
            byte[] xml = parts.xml().get(reference.part());
            if (xml == null || charts.size() >= MAX_CHARTS) {
                continue;
            }
            ChartData chart = parseChart(xml, reference.headerOrFooter());
            if (chart != null && chart.hasData()) {
                charts.add(chart);
            }
        }

        int restored = 0;
        Set<Element> used = new HashSet<>();
        for (ChartData chart : charts) {
            Element candidate = findFlatChart(document, chart.flatValues(), used);
            if (candidate != null) {
                Element rendered = renderChart(chart);
                if (candidate.tagName().equals("body")) {
                    candidate.empty().appendChild(rendered);
                } else {
                    candidate.before(rendered);
                    candidate.remove();
                }
                used.add(rendered);
                restored++;
                continue;
            }
            if (chart.headerOrFooter() || !containsAllValues(document, chart.significantValues())) {
                document.body().appendChild(renderChart(chart));
                restored++;
            }
        }
        return restored;
    }

    private static PackageParts readParts(Path source) throws Exception {
        Map<String, byte[]> xml = new HashMap<>();
        long totalBytes = 0;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            var entries = archive.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!isRelevantPart(name)) {
                    continue;
                }
                byte[] bytes = archive.getInputStream(entry).readNBytes(MAX_PART_BYTES + 1);
                if (bytes.length > MAX_PART_BYTES) {
                    throw new IOException("DOCX chart part size limit exceeded");
                }
                totalBytes += bytes.length;
                if (totalBytes > MAX_TOTAL_PART_BYTES) {
                    throw new IOException("DOCX chart XML total size limit exceeded");
                }
                xml.put(name, bytes);
            }
        }

        Set<String> visibleOwners = visibleHeaderAndFooterParts(xml);
        visibleOwners.add("word/document.xml");
        Map<String, Boolean> visible = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, byte[]> part : xml.entrySet()) {
            if (!part.getKey().endsWith(".rels")) {
                continue;
            }
            String owner = ownerPart(part.getKey());
            if (owner == null || !visibleOwners.contains(owner)) {
                continue;
            }
            Set<String> referencedIds = referencedChartRelationships(xml.get(owner));
            if (referencedIds.isEmpty()) {
                continue;
            }
            org.w3c.dom.Document relationships = parseXml(part.getValue());
            NodeList nodes = relationships.getElementsByTagNameNS("*", "Relationship");
            for (int index = 0; index < nodes.getLength(); index++) {
                org.w3c.dom.Element relationship = (org.w3c.dom.Element) nodes.item(index);
                String type = relationship.getAttribute("Type");
                String target = relationship.getAttribute("Target");
                String id = relationship.getAttribute("Id");
                if (relationship.getAttribute("TargetMode").equalsIgnoreCase("External")
                        || target.isBlank() || !referencedIds.contains(id)
                        || !(type.endsWith("/chart") || type.endsWith("/chartEx"))) {
                    continue;
                }
                String chartPart = resolvePart(owner, target);
                visible.merge(chartPart, !owner.equals("word/document.xml"), Boolean::logicalOr);
            }
        }
        List<ChartReference> references = visible.entrySet().stream()
                .map(entry -> new ChartReference(entry.getKey(), entry.getValue())).toList();
        return new PackageParts(xml, references);
    }

    private static Set<String> visibleHeaderAndFooterParts(Map<String, byte[]> xml) throws Exception {
        byte[] relationshipsXml = xml.get("word/_rels/document.xml.rels");
        Set<String> referencedIds = referencedRelationships(xml.get("word/document.xml"),
                Set.of("headerReference", "footerReference"));
        if (relationshipsXml == null || referencedIds.isEmpty()) {
            return new LinkedHashSet<>();
        }
        Set<String> result = new LinkedHashSet<>();
        org.w3c.dom.Document relationships = parseXml(relationshipsXml);
        NodeList nodes = relationships.getElementsByTagNameNS("*", "Relationship");
        for (int index = 0; index < nodes.getLength(); index++) {
            org.w3c.dom.Element relationship = (org.w3c.dom.Element) nodes.item(index);
            String id = relationship.getAttribute("Id");
            String type = relationship.getAttribute("Type");
            String target = relationship.getAttribute("Target");
            if (referencedIds.contains(id) && !target.isBlank()
                    && !relationship.getAttribute("TargetMode").equalsIgnoreCase("External")
                    && (type.endsWith("/header") || type.endsWith("/footer"))) {
                result.add(resolvePart("word/document.xml", target));
            }
        }
        return result;
    }

    private static boolean isRelevantPart(String name) {
        return name.matches("word/charts/[^/]+\\.xml")
                || name.equals("word/document.xml")
                || name.matches("word/(?:header|footer)\\d+\\.xml")
                || name.equals("word/_rels/document.xml.rels")
                || name.matches("word/_rels/(?:header|footer)\\d+\\.xml\\.rels");
    }

    private static Set<String> referencedChartRelationships(byte[] ownerXml) throws Exception {
        return referencedRelationships(ownerXml, Set.of("chart"));
    }

    private static Set<String> referencedRelationships(byte[] ownerXml, Set<String> elementNames)
            throws Exception {
        if (ownerXml == null) {
            return Set.of();
        }
        org.w3c.dom.Document owner = parseXml(ownerXml);
        if (!withinDepthLimit(owner)) {
            return Set.of();
        }
        Set<String> result = new LinkedHashSet<>();
        for (String elementName : elementNames) {
            NodeList nodes = owner.getElementsByTagNameNS("*", elementName);
            for (int index = 0; index < nodes.getLength(); index++) {
                Node node = nodes.item(index);
                if (node.getAttributes() == null) {
                    continue;
                }
                for (int attributeIndex = 0; attributeIndex < node.getAttributes().getLength(); attributeIndex++) {
                    Node attribute = node.getAttributes().item(attributeIndex);
                    if ("id".equals(localName(attribute)) && attribute.getNamespaceURI() != null
                            && attribute.getNamespaceURI().contains("relationships")) {
                        result.add(attribute.getNodeValue());
                    }
                }
            }
        }
        return result;
    }

    private static String ownerPart(String relationshipsPart) {
        int marker = relationshipsPart.lastIndexOf("/_rels/");
        if (marker < 0 || !relationshipsPart.endsWith(".rels")) {
            return null;
        }
        return relationshipsPart.substring(0, marker + 1)
                + relationshipsPart.substring(marker + "/_rels/".length(),
                relationshipsPart.length() - ".rels".length());
    }

    private static String resolvePart(String owner, String target) {
        Path parent = Path.of(owner).getParent();
        return (parent == null ? Path.of(target) : parent.resolve(target)).normalize()
                .toString().replace('\\', '/');
    }

    private static ChartData parseChart(byte[] xml, boolean headerOrFooter) throws Exception {
        org.w3c.dom.Document chart = parseXml(xml);
        if (!withinDepthLimit(chart)) {
            return null;
        }
        String title = chartTitle(chart);
        List<Series> series = new ArrayList<>();
        NodeList nodes = chart.getElementsByTagNameNS("*", "ser");
        int pointCount = 0;
        for (int index = 0; index < nodes.getLength(); index++) {
            Node node = nodes.item(index);
            List<String> values = cachedValues(firstChild(node, "val", "yVal", "bubbleSize"));
            if (values.isEmpty()) {
                continue;
            }
            List<String> categories = cachedValues(firstChild(node, "cat", "xVal"));
            List<String> names = cachedValues(firstChild(node, "tx"));
            String name = names.isEmpty() ? "Series " + (series.size() + 1) : String.join(" ", names);
            pointCount += values.size() + categories.size();
            if (pointCount > MAX_POINTS) {
                return null;
            }
            series.add(new Series(name, categories, values));
        }
        if (series.isEmpty()) {
            return null;
        }

        List<String> flat = descendants(chart.getDocumentElement(), "v");
        if (flat.isEmpty()) {
            for (Series item : series) {
                flat.add(item.name());
                flat.addAll(item.categories());
                flat.addAll(item.values());
            }
        }
        Set<String> significant = new LinkedHashSet<>();
        for (String value : flat) {
            String normalized = normalize(value);
            if (!normalized.isBlank()) {
                significant.add(normalized);
            }
        }
        return new ChartData(title, List.copyOf(series), String.join(" ", flat),
                Set.copyOf(significant), headerOrFooter);
    }

    private static String chartTitle(org.w3c.dom.Document chart) {
        NodeList titles = chart.getElementsByTagNameNS("*", "title");
        if (titles.getLength() == 0) {
            return "Chart";
        }
        String title = String.join(" ", descendants(titles.item(0), "t")).strip();
        return title.isBlank() ? "Chart" : title;
    }

    private static List<String> cachedValues(Node parent) {
        return parent == null ? List.of() : descendants(parent, "v");
    }

    private static List<String> descendants(Node parent, String name) {
        List<String> values = new ArrayList<>();
        if (parent == null) {
            return values;
        }
        collectDescendants(parent, name, values);
        return values;
    }

    private static void collectDescendants(Node node, String name, List<String> values) {
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (name.equals(localName(child))) {
                String value = child.getTextContent().strip();
                if (!value.isBlank()) {
                    values.add(value);
                }
            } else {
                collectDescendants(child, name, values);
            }
        }
    }

    private static Node firstChild(Node node, String... names) {
        if (node == null) {
            return null;
        }
        for (Node child = node.getFirstChild(); child != null; child = child.getNextSibling()) {
            for (String name : names) {
                if (name.equals(localName(child))) {
                    return child;
                }
            }
        }
        return null;
    }

    private static Element findFlatChart(Document document, String flat, Set<Element> used) {
        String expected = normalize(flat);
        List<Element> matches = new ArrayList<>();
        for (Element candidate : document.select("p, div, body")) {
            if (!used.contains(candidate) && normalize(candidate.text()).equals(expected)
                    && !candidate.hasClass("mika-phonetic-restored")
                    && candidate.selectFirst(".mika-equation, .mika-phonetic, img[src^=embedded:]") == null) {
                boolean matchingChild = candidate.select("p, div").stream()
                        .anyMatch(child -> child != candidate && normalize(child.text()).equals(expected));
                if (!matchingChild) {
                    matches.add(candidate);
                }
            }
        }
        return matches.size() == 1 ? matches.getFirst() : null;
    }

    private static boolean containsAllValues(Document document, Set<String> values) {
        String text = normalize(document.text());
        return !values.isEmpty() && values.stream().allMatch(text::contains);
    }

    private static Element renderChart(ChartData chart) {
        Element section = new Element("section").addClass("mika-chart");
        section.appendElement("h3").text(chart.title());
        Element table = section.appendElement("table");
        Element header = table.appendElement("thead").appendElement("tr");
        header.appendElement("th").text("Category");
        for (Series series : chart.series()) {
            header.appendElement("th").text(series.name());
        }
        int rows = chart.series().stream()
                .mapToInt(series -> Math.max(series.categories().size(), series.values().size()))
                .max().orElse(0);
        Element body = table.appendElement("tbody");
        List<String> sharedCategories = chart.series().stream()
                .map(Series::categories).filter(values -> !values.isEmpty()).findFirst().orElse(List.of());
        for (int row = 0; row < rows; row++) {
            Element tr = body.appendElement("tr");
            tr.appendElement("td").text(row < sharedCategories.size()
                    ? sharedCategories.get(row) : Integer.toString(row + 1));
            for (Series series : chart.series()) {
                tr.appendElement("td").text(row < series.values().size() ? series.values().get(row) : "");
            }
        }
        return section;
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

    private static String localName(Node node) {
        return node.getLocalName() == null ? "" : node.getLocalName();
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
    }

    private record PackageParts(Map<String, byte[]> xml, List<ChartReference> visibleCharts) {
    }

    private record ChartReference(String part, boolean headerOrFooter) {
    }

    private record Series(String name, List<String> categories, List<String> values) {
    }

    private record ChartData(String title, List<Series> series, String flatValues,
                             Set<String> significantValues, boolean headerOrFooter) {
        private boolean hasData() {
            return !series.isEmpty();
        }
    }

    private record NodeDepth(Node node, int depth) {
    }
}
