package ai.minum.extract;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Identifies fallback chart pictures whose OOXML chart cache already supplies searchable values. */
final class DocxChartPreviews {
    private static final int MAX_RELEVANT_XML_BYTES = 16 * 1024 * 1024;

    private DocxChartPreviews() {
    }

    static Set<String> withUsableCache(Path source) throws Exception {
        Map<String, byte[]> parts = readRelevantParts(source);
        byte[] document = parts.get("word/document.xml");
        byte[] relationships = parts.get("word/_rels/document.xml.rels");
        if (document == null || relationships == null) {
            return Set.of();
        }

        Map<String, Relationship> relationById = relationships(relationships);
        Set<String> result = new HashSet<>();
        for (Preview preview : previews(document)) {
            Relationship chart = relationById.get(preview.chartRelationship());
            Relationship image = relationById.get(preview.imageRelationship());
            if (chart == null || image == null || !chart.type().endsWith("/chart")
                    || !image.type().endsWith("/image")) {
                continue;
            }
            String chartPart = resolvePart("word/document.xml", chart.target());
            byte[] chartXml = parts.get(chartPart);
            if (chartXml == null || !hasUsableCache(chartXml)) {
                continue;
            }
            String imagePart = resolvePart("word/document.xml", image.target());
            result.add(imagePart);
            result.add('/' + imagePart);
            int slash = imagePart.lastIndexOf('/');
            result.add(slash < 0 ? imagePart : imagePart.substring(slash + 1));
        }
        return Set.copyOf(result);
    }

    private static Map<String, byte[]> readRelevantParts(Path source) throws IOException {
        Map<String, byte[]> parts = new HashMap<>();
        try (InputStream input = Files.newInputStream(source);
             ZipInputStream archive = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                String name = entry.getName();
                if (!name.equals("word/document.xml")
                        && !name.equals("word/_rels/document.xml.rels")
                        && !(name.startsWith("word/charts/") && name.endsWith(".xml"))) {
                    continue;
                }
                byte[] bytes = archive.readNBytes(MAX_RELEVANT_XML_BYTES + 1);
                if (bytes.length > MAX_RELEVANT_XML_BYTES) {
                    throw new IOException("DOCX chart XML size limit exceeded");
                }
                parts.put(name, bytes);
            }
        }
        return parts;
    }

    private static Map<String, Relationship> relationships(byte[] xml) throws Exception {
        Map<String, Relationship> result = new HashMap<>();
        XMLStreamReader reader = reader(xml);
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT
                        || !reader.getLocalName().equals("Relationship")) {
                    continue;
                }
                String id = attribute(reader, "Id");
                String type = attribute(reader, "Type");
                String target = attribute(reader, "Target");
                String targetMode = attribute(reader, "TargetMode");
                if (id != null && type != null && target != null
                        && !"External".equalsIgnoreCase(targetMode)) {
                    result.put(id, new Relationship(type, target));
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    private static List<Preview> previews(byte[] xml) throws Exception {
        List<Preview> result = new ArrayList<>();
        XMLStreamReader reader = reader(xml);
        String chartRelationship = null;
        String imageRelationship = null;
        int alternateDepth = 0;
        boolean choice = false;
        boolean fallback = false;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("AlternateContent")) {
                        alternateDepth++;
                        if (alternateDepth == 1) {
                            chartRelationship = null;
                            imageRelationship = null;
                        }
                    } else if (alternateDepth == 1 && name.equals("Choice")) {
                        choice = true;
                    } else if (alternateDepth == 1 && name.equals("Fallback")) {
                        fallback = true;
                    } else if (alternateDepth == 1 && choice && name.equals("chart")) {
                        chartRelationship = relationshipAttribute(reader, "id");
                    } else if (alternateDepth == 1 && fallback
                            && (name.equals("blip") || name.equals("imagedata"))) {
                        imageRelationship = relationshipAttribute(reader,
                                name.equals("blip") ? "embed" : "id");
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("Choice")) {
                        choice = false;
                    } else if (name.equals("Fallback")) {
                        fallback = false;
                    } else if (name.equals("AlternateContent")) {
                        if (alternateDepth == 1 && chartRelationship != null
                                && imageRelationship != null) {
                            result.add(new Preview(chartRelationship, imageRelationship));
                        }
                        alternateDepth--;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return result;
    }

    private static boolean hasUsableCache(byte[] xml) throws Exception {
        XMLStreamReader reader = reader(xml);
        int cacheDepth = 0;
        int chartExDataDepth = 0;
        int valueCount = 0;
        int valueCharacters = 0;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (name.equals("numCache") || name.equals("strCache")
                            || name.equals("multiLvlStrCache")) {
                        cacheDepth++;
                    } else if (name.equals("chartData") && reader.getNamespaceURI() != null
                            && reader.getNamespaceURI().contains("drawing/2014/chartex")) {
                        chartExDataDepth++;
                    } else if (cacheDepth > 0 && name.equals("v")) {
                        String value = reader.getElementText().strip();
                        if (!value.isEmpty()) {
                            valueCount++;
                            valueCharacters += value.length();
                            if (valueCount >= 2 || valueCharacters >= 8) {
                                return true;
                            }
                        }
                    } else if (chartExDataDepth > 0 && name.equals("pt")) {
                        String value = reader.getElementText().strip();
                        if (!value.isEmpty()) {
                            valueCount++;
                            valueCharacters += value.length();
                            if (valueCount >= 2 || valueCharacters >= 8) {
                                return true;
                            }
                        }
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    String name = reader.getLocalName();
                    if ((name.equals("numCache") || name.equals("strCache")
                            || name.equals("multiLvlStrCache")) && cacheDepth > 0) {
                        cacheDepth--;
                    } else if (name.equals("chartData") && chartExDataDepth > 0) {
                        chartExDataDepth--;
                    }
                }
            }
        } finally {
            reader.close();
        }
        return false;
    }

    private static XMLStreamReader reader(byte[] xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some StAX providers omit optional properties; external access is still unavailable
            // because the parser receives an in-memory stream and no resolver.
        }
    }

    private static String relationshipAttribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (reader.getAttributeLocalName(index).equals(localName)
                    && reader.getAttributeNamespace(index) != null
                    && reader.getAttributeNamespace(index).contains("relationships")) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (reader.getAttributeLocalName(index).equals(localName)) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static String resolvePart(String basePart, String target) {
        Path base = Path.of(basePart).getParent();
        return (base == null ? Path.of(target) : base.resolve(target)).normalize()
                .toString().replace('\\', '/');
    }

    private record Preview(String chartRelationship, String imageRelationship) {
    }

    private record Relationship(String type, String target) {
    }
}
