package ai.minum.extract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Range;
import org.apache.poi.poifs.filesystem.DirectoryEntry;
import org.apache.poi.poifs.filesystem.DocumentEntry;
import org.apache.poi.poifs.filesystem.DocumentInputStream;
import org.apache.poi.poifs.filesystem.Entry;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Recovers searchable StarMath source from embedded OpenDocument formula objects in binary Word. */
final class DocEmbeddedFormulas {
    private static final String FORMULA_MIME = "application/vnd.oasis.opendocument.formula";
    private static final int MAX_PACKAGE_BYTES = 16 * 1024 * 1024;
    private static final int MAX_CONTENT_BYTES = 2 * 1024 * 1024;
    private static final int MAX_EXPANDED_BYTES = 32 * 1024 * 1024;
    private static final int MAX_ENTRIES = 128;
    private static final int MAX_FORMULAS = 256;
    private static final int MAX_XML_DEPTH = 128;

    private DocEmbeddedFormulas() {
    }

    static int restore(Document output, HWPFDocument word, ExtractResult result) throws Exception {
        Set<Integer> objectIds = formulaObjectIds(word.getOverallRange());
        Set<String> formulas = new LinkedHashSet<>();
        for (int objectId : objectIds) {
            if (formulas.size() >= MAX_FORMULAS) {
                result.addWarning("Embedded formula count limit reached; some formulas were skipped");
                break;
            }
            Entry object = word.getObjectsPool().getObjectById("_" + Integer.toUnsignedString(objectId));
            if (!(object instanceof DirectoryEntry directory) || !directory.hasEntry("package_stream")) {
                continue;
            }
            Entry packageEntry = directory.getEntry("package_stream");
            if (!(packageEntry instanceof DocumentEntry packageDocument)) {
                continue;
            }
            if (packageDocument.getSize() > MAX_PACKAGE_BYTES) {
                result.addWarning("Embedded formula size limit exceeded; a formula was skipped");
                continue;
            }
            try (DocumentInputStream input = new DocumentInputStream(packageDocument)) {
                byte[] bytes = input.readNBytes(MAX_PACKAGE_BYTES + 1);
                if (bytes.length > MAX_PACKAGE_BYTES) {
                    result.addWarning("Embedded formula size limit exceeded; a formula was skipped");
                    continue;
                }
                String formula = starMath(bytes);
                if (!formula.isBlank()) {
                    formulas.add(formula);
                }
            }
        }
        if (formulas.isEmpty()) {
            return 0;
        }

        String present = normalize(output.text());
        Element section = null;
        int restored = 0;
        for (String formula : formulas) {
            if (present.contains(normalize(formula))) {
                continue;
            }
            if (section == null) {
                section = output.body().appendElement("section").addClass("mika-embedded-formulas");
                section.appendElement("h3").text("Embedded formulas");
            }
            Element paragraph = section.appendElement("p");
            paragraph.appendText("StarMath: ");
            paragraph.appendElement("code").text(formula);
            restored++;
        }
        return restored;
    }

    private static Set<Integer> formulaObjectIds(Range range) {
        Set<Integer> result = new LinkedHashSet<>();
        for (int paragraphIndex = 0; paragraphIndex < range.numParagraphs(); paragraphIndex++) {
            var paragraph = range.getParagraph(paragraphIndex);
            for (int runIndex = 0; runIndex < paragraph.numCharacterRuns(); runIndex++) {
                CharacterRun run = paragraph.getCharacterRun(runIndex);
                if (run.isOle2() && run.getPicOffset() > 0) {
                    result.add(run.getPicOffset());
                }
            }
        }
        return result;
    }

    private static String starMath(byte[] packageBytes) throws Exception {
        byte[] content = null;
        String mime = "";
        int expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(packageBytes))) {
            ZipEntry entry;
            int entries = 0;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("Embedded formula package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("Embedded formula package contains duplicate entry: "
                            + entry.getName());
                }
                if (entry.isDirectory()) {
                    continue;
                }
                if ("mimetype".equals(entry.getName())) {
                    EntryBytes value = readEntry(archive, expanded, 256);
                    expanded = value.expanded();
                    byte[] bytes = value.bytes();
                    mime = new String(bytes, java.nio.charset.StandardCharsets.US_ASCII).strip();
                } else if ("content.xml".equals(entry.getName())) {
                    EntryBytes value = readEntry(archive, expanded, MAX_CONTENT_BYTES);
                    expanded = value.expanded();
                    content = value.bytes();
                } else {
                    expanded = readEntry(archive, expanded, 0).expanded();
                }
            }
        }
        if (!FORMULA_MIME.equals(mime) || content == null) {
            return "";
        }

        org.w3c.dom.Document xml = parseXml(content);
        if (!withinDepthLimit(xml)) {
            throw new IOException("Embedded formula XML depth limit exceeded");
        }
        NodeList annotations = xml.getElementsByTagNameNS("*", "annotation");
        for (int index = 0; index < annotations.getLength(); index++) {
            org.w3c.dom.Element annotation = (org.w3c.dom.Element) annotations.item(index);
            if ("StarMath 5.0".equalsIgnoreCase(annotation.getAttribute("encoding"))) {
                return clean(annotation.getTextContent());
            }
        }
        return "";
    }

    private static EntryBytes readEntry(ZipInputStream archive, int expanded, int captureLimit)
            throws IOException {
        ByteArrayOutputStream captured = captureLimit > 0 ? new ByteArrayOutputStream() : null;
        byte[] buffer = new byte[8192];
        int count;
        while ((count = archive.read(buffer)) != -1) {
            expanded += count;
            if (expanded > MAX_EXPANDED_BYTES) {
                throw new IOException("Embedded formula expanded package size limit exceeded");
            }
            if (captured != null) {
                if ((long) captured.size() + count > captureLimit) {
                    throw new IOException("Embedded formula entry size limit exceeded");
                }
                captured.write(buffer, 0, count);
            }
        }
        return new EntryBytes(captured == null ? new byte[0] : captured.toByteArray(), expanded);
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

    private static String clean(String value) {
        return value.replaceAll("[\\x00-\\x1F\\x7F]", " ").replaceAll("\\s+", " ").strip();
    }

    private static String normalize(String value) {
        return clean(value).toLowerCase(Locale.ROOT);
    }

    private record NodeDepth(Node node, int depth) {
    }

    private record EntryBytes(byte[] bytes, int expanded) {
    }
}
