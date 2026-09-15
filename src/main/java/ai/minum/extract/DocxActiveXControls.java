package ai.minum.extract;

import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.POIFSFileSystem;
import org.apache.poi.util.LittleEndian;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Restores visible Microsoft Forms TextBox values omitted by Tika's DOCX parser. */
final class DocxActiveXControls {
    private static final String TEXT_BOX_CLASS_ID = "{8BD21D10-EC42-11CE-9E0D-00AA006002F3}";
    private static final String RELATIONSHIPS_NAMESPACE =
            "http://schemas.openxmlformats.org/officeDocument/2006/relationships";
    private static final String STRICT_RELATIONSHIPS_NAMESPACE =
            "http://purl.oclc.org/ooxml/officeDocument/relationships";
    private static final int MAX_CONTROLS = 128;
    private static final int MAX_XML_SIZE = 4 * 1024 * 1024;
    private static final int MAX_BINARY_SIZE = 8 * 1024 * 1024;
    private static final int MAX_VALUE_SIZE = 1024 * 1024;

    private DocxActiveXControls() {
    }

    static int restore(Document document, Path source) throws Exception {
        if (source == null) {
            return 0;
        }
        List<ControlValue> values;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            byte[] documentXml = read(archive, "word/document.xml", MAX_XML_SIZE);
            byte[] documentRelationships = read(archive, "word/_rels/document.xml.rels", MAX_XML_SIZE);
            if (documentXml == null || documentRelationships == null) {
                return 0;
            }
            Map<String, String> relationships = relationships(
                    documentRelationships, "word/document.xml", "/control");
            values = new ArrayList<>();
            for (ControlReference control : controls(documentXml)) {
                String controlPart = relationships.get(control.relationshipId());
                if (controlPart == null || !controlPart.startsWith("word/activeX/")) {
                    continue;
                }
                ControlValue value = readControl(archive, control, controlPart);
                if (value != null && !containsText(document, value.value())) {
                    values.add(value);
                }
            }
        }
        if (values.isEmpty()) {
            return 0;
        }

        Element section = document.body().appendElement("section").addClass("mika-form-controls");
        section.appendElement("h3").text("Form controls");
        Element list = section.appendElement("ul");
        for (ControlValue value : values) {
            Element item = list.appendElement("li");
            item.appendElement("strong").text(value.name().isBlank() ? "Text box" : value.name());
            item.appendText(": ");
            String[] lines = value.value().split("\\n", -1);
            for (int index = 0; index < lines.length; index++) {
                if (index > 0) {
                    item.appendElement("br");
                }
                item.appendText(lines[index]);
            }
        }
        return values.size();
    }

    private static ControlValue readControl(ZipFile archive, ControlReference control, String controlPart)
            throws Exception {
        byte[] controlXml = read(archive, controlPart, MAX_XML_SIZE);
        if (controlXml == null) {
            return null;
        }
        Ocx ocx = ocx(controlXml);
        if (ocx == null || !TEXT_BOX_CLASS_ID.equalsIgnoreCase(ocx.classId())) {
            return null;
        }
        if ("persistPropertyBag".equalsIgnoreCase(ocx.persistence())
                && !ocx.propertyValue().isBlank()) {
            String value = clean(ocx.propertyValue());
            return value.isBlank() ? null : new ControlValue(control.name(), value);
        }
        if (!"persistStorage".equalsIgnoreCase(ocx.persistence())
                || ocx.relationshipId().isBlank()) {
            return null;
        }
        String relationshipPart = relationshipPart(controlPart);
        byte[] relationshipXml = read(archive, relationshipPart, MAX_XML_SIZE);
        if (relationshipXml == null) {
            return null;
        }
        String binaryPart = relationships(
                relationshipXml, controlPart, "/activeXControlBinary").get(ocx.relationshipId());
        if (binaryPart == null || !binaryPart.startsWith("word/activeX/")) {
            return null;
        }
        byte[] binary = read(archive, binaryPart, MAX_BINARY_SIZE);
        if (binary == null) {
            return null;
        }
        String value = readStorageTextBoxValue(binary);
        return value.isBlank() ? null : new ControlValue(control.name(), value);
    }

    private static List<ControlReference> controls(byte[] xml) throws Exception {
        XMLStreamReader reader = xmlReader(xml);
        List<ControlReference> controls = new ArrayList<>();
        try {
            while (reader.hasNext() && controls.size() < MAX_CONTROLS) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && "control".equals(reader.getLocalName())) {
                    String relationshipId = relationshipId(reader);
                    if (!relationshipId.isBlank()) {
                        controls.add(new ControlReference(attribute(reader, "name"), relationshipId));
                    }
                }
            }
        } finally {
            reader.close();
        }
        return controls;
    }

    private static Ocx ocx(byte[] xml) throws Exception {
        XMLStreamReader reader = xmlReader(xml);
        String classId = "";
        String persistence = "";
        String relationshipId = "";
        String propertyValue = "";
        try {
            while (reader.hasNext()) {
                if (reader.next() != XMLStreamConstants.START_ELEMENT) {
                    continue;
                }
                if ("ocx".equals(reader.getLocalName())) {
                    classId = attribute(reader, "classid");
                    persistence = attribute(reader, "persistence");
                    relationshipId = relationshipId(reader);
                } else if ("ocxPr".equals(reader.getLocalName())
                        && "Value".equalsIgnoreCase(attribute(reader, "name"))) {
                    propertyValue = attribute(reader, "value");
                }
            }
        } finally {
            reader.close();
        }
        return classId.isBlank() ? null : new Ocx(classId, persistence, relationshipId, propertyValue);
    }

    private static Map<String, String> relationships(byte[] xml, String sourcePart, String typeSuffix)
            throws Exception {
        XMLStreamReader reader = xmlReader(xml);
        Map<String, String> relationships = new HashMap<>();
        try {
            while (reader.hasNext()) {
                if (reader.next() == XMLStreamConstants.START_ELEMENT
                        && "Relationship".equals(reader.getLocalName())
                        && !"External".equalsIgnoreCase(attribute(reader, "TargetMode"))
                        && attribute(reader, "Type").endsWith(typeSuffix)) {
                    String id = attribute(reader, "Id");
                    String target = attribute(reader, "Target");
                    String resolved = resolvePart(sourcePart, target);
                    if (!id.isBlank() && resolved != null) {
                        if (relationships.putIfAbsent(id, resolved) != null) {
                            throw new IOException("Duplicate DOCX relationship id: " + id);
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return relationships;
    }

    /** Parses the MorphDataControl structure defined by MS-OFORMS section 2.2.5. */
    private static String readStorageTextBoxValue(byte[] oleBytes) throws IOException {
        byte[] contents;
        try (POIFSFileSystem fileSystem = new POIFSFileSystem(new ByteArrayInputStream(oleBytes));
             DocumentInputStream input = fileSystem.createDocumentInputStream("contents")) {
            if (input.available() > MAX_BINARY_SIZE) {
                throw new IOException("ActiveX contents stream exceeds safety limit");
            }
            contents = input.readAllBytes();
        }
        if (contents.length < 12 || contents[0] != 0 || contents[1] != 2) {
            return "";
        }
        int controlEnd = 4 + LittleEndian.getUShort(contents, 2);
        if (controlEnd < 12 || controlEnd > contents.length) {
            return "";
        }
        long mask = LittleEndian.getLong(contents, 4);
        if ((mask & ~0x1ffffffffL) != 0) {
            return "";
        }
        int cursor = 12;
        long valueLength = -1;
        for (int bit = 0; bit <= 32; bit++) {
            if ((mask & (1L << bit)) == 0 || bit == 8 || bit == 19 || bit == 30 || bit == 31) {
                continue;
            }
            int size = propertySize(bit);
            if (size == 0) {
                return "";
            }
            cursor = align(cursor, size);
            if (cursor > controlEnd - size) {
                return "";
            }
            if (bit == 22) {
                valueLength = LittleEndian.getUInt(contents, cursor);
            }
            cursor += size;
        }
        cursor = align(cursor, 4);
        if ((mask & (1L << 8)) != 0) {
            cursor += 8;
        }
        if (valueLength < 0) {
            return "";
        }
        boolean compressed = (valueLength & 0x80000000L) != 0;
        int length = (int) (valueLength & 0x7fffffffL);
        if (length > MAX_VALUE_SIZE || cursor < 0 || cursor > controlEnd - length
                || !compressed && (length & 1) != 0) {
            return "";
        }
        String value = new String(contents, cursor, length,
                compressed ? StandardCharsets.ISO_8859_1 : StandardCharsets.UTF_16LE);
        return clean(value);
    }

    private static int propertySize(int bit) {
        return switch (bit) {
            case 0, 1, 2, 3, 10, 22, 23, 24, 25, 26, 32 -> 4;
            case 4, 5, 6, 7, 16, 17, 18, 20, 21 -> 1;
            case 9, 11, 12, 13, 14, 15, 27, 28, 29 -> 2;
            default -> 0;
        };
    }

    private static int align(int offset, int alignment) {
        return (offset + alignment - 1) & -alignment;
    }

    private static String relationshipPart(String sourcePart) {
        int slash = sourcePart.lastIndexOf('/');
        String directory = slash < 0 ? "" : sourcePart.substring(0, slash + 1);
        String name = slash < 0 ? sourcePart : sourcePart.substring(slash + 1);
        return directory + "_rels/" + name + ".rels";
    }

    private static String resolvePart(String sourcePart, String target) {
        if (target == null || target.isBlank() || target.indexOf('\\') >= 0) {
            return null;
        }
        try {
            URI resolved = URI.create("/" + sourcePart).resolve(target).normalize();
            String path = resolved.getPath();
            return resolved.getScheme() == null && path != null && path.startsWith("/")
                    && !path.startsWith("/../") ? path.substring(1) : null;
        } catch (IllegalArgumentException malformedTarget) {
            return null;
        }
    }

    private static byte[] read(ZipFile archive, String name, int limit) throws IOException {
        ZipEntry match = null;
        int matches = 0;
        var entries = archive.entries();
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            if (name.equals(entry.getName())) {
                match = entry;
                matches++;
            }
        }
        if (matches != 1 || match == null || match.isDirectory() || match.getSize() > limit) {
            return null;
        }
        try (InputStream input = new LimitedInputStream(archive.getInputStream(match), limit)) {
            return input.readAllBytes();
        }
    }

    private static XMLStreamReader xmlReader(byte[] xml) throws Exception {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        return factory.createXMLStreamReader(new ByteArrayInputStream(xml));
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // Some StAX providers do not expose every optional feature.
        }
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (localName.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return "";
    }

    private static String relationshipId(XMLStreamReader reader) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            String namespace = reader.getAttributeNamespace(index);
            if ("id".equals(reader.getAttributeLocalName(index))
                    && (RELATIONSHIPS_NAMESPACE.equals(namespace)
                    || STRICT_RELATIONSHIPS_NAMESPACE.equals(namespace))) {
                return reader.getAttributeValue(index);
            }
        }
        return "";
    }

    private static String clean(String value) {
        StringBuilder cleaned = new StringBuilder(Math.min(value.length(), MAX_VALUE_SIZE));
        value.replace("\r\n", "\n").replace('\r', '\n').codePoints()
                .filter(character -> character == '\n' || character == '\t'
                        || !Character.isISOControl(character))
                .limit(MAX_VALUE_SIZE)
                .forEach(cleaned::appendCodePoint);
        return cleaned.toString().strip();
    }

    private static boolean containsText(Document document, String value) {
        String expected = value.replaceAll("\\s+", " ").strip().toLowerCase(Locale.ROOT);
        String actual = document.text().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        return !expected.isBlank() && actual.contains(expected);
    }

    private record ControlReference(String name, String relationshipId) {
    }

    private record Ocx(String classId, String persistence, String relationshipId, String propertyValue) {
    }

    private record ControlValue(String name, String value) {
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private int remaining;

        private LimitedInputStream(InputStream input, int limit) {
            super(input);
            remaining = limit + 1;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("DOCX part exceeds safety limit");
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
                throw new IOException("DOCX part exceeds safety limit");
            }
            int read = super.read(bytes, offset, Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
