package ai.minum.extract;

import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Recovers visible paragraphs below the nesting depth retained by Tika's DOCX XHTML parser. */
final class DocxDeepTableText {
    private static final String WORDPROCESSING_NAMESPACE =
            "http://schemas.openxmlformats.org/wordprocessingml/2006/main";
    private static final long MAX_DOCUMENT_XML_SIZE = 64L * 1024 * 1024;
    private static final int XHTML_TABLE_DEPTH_LIMIT = 32;

    private DocxDeepTableText() {
    }

    static int restore(Document document, Path source) throws IOException, XMLStreamException {
        if (source == null) {
            return 0;
        }
        List<String> missing = readDeepParagraphs(source, document.text());
        if (missing.isEmpty()) {
            return 0;
        }
        Element recovered = document.body().appendElement("div")
                .addClass("mika-recovered-deep-table-text");
        missing.forEach(text -> recovered.appendElement("p").text(text));
        return missing.size();
    }

    private static List<String> readDeepParagraphs(Path source, String extractedText)
            throws IOException, XMLStreamException {
        try (ZipFile archive = new ZipFile(source.toFile())) {
            ZipEntry entry = archive.getEntry("word/document.xml");
            if (entry == null || entry.getSize() > MAX_DOCUMENT_XML_SIZE) {
                return List.of();
            }
            try (InputStream xml = new LimitedInputStream(
                    archive.getInputStream(entry), MAX_DOCUMENT_XML_SIZE)) {
                return parse(xml, extractedText);
            }
        }
    }

    private static List<String> parse(InputStream xml, String extractedText) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = factory.createXMLStreamReader(xml);
        List<String> missing = new ArrayList<>();
        int tableDepth = 0;
        int deletedDepth = 0;
        boolean readingText = false;
        StringBuilder paragraph = null;
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT && isWordElement(reader)) {
                    switch (reader.getLocalName()) {
                        case "tbl" -> tableDepth++;
                        case "del" -> deletedDepth++;
                        case "p" -> paragraph = tableDepth > XHTML_TABLE_DEPTH_LIMIT
                                ? new StringBuilder() : null;
                        case "t" -> readingText = paragraph != null && deletedDepth == 0;
                        case "tab" -> append(paragraph, "\t");
                        case "br", "cr" -> append(paragraph, "\n");
                        default -> {
                        }
                    }
                } else if ((event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) && readingText) {
                    paragraph.append(reader.getText());
                } else if (event == XMLStreamConstants.END_ELEMENT && isWordElement(reader)) {
                    switch (reader.getLocalName()) {
                        case "t" -> readingText = false;
                        case "p" -> {
                            String text = paragraph == null ? "" : paragraph.toString().strip();
                            if (!text.isEmpty() && !extractedText.contains(text)) {
                                missing.add(text);
                            }
                            paragraph = null;
                        }
                        case "del" -> deletedDepth--;
                        case "tbl" -> tableDepth--;
                        default -> {
                        }
                    }
                }
            }
        } finally {
            reader.close();
        }
        return missing;
    }

    private static boolean isWordElement(XMLStreamReader reader) {
        return WORDPROCESSING_NAMESPACE.equals(reader.getNamespaceURI());
    }

    private static void append(StringBuilder paragraph, String value) {
        if (paragraph != null) {
            paragraph.append(value);
        }
    }

    private static void setProperty(XMLInputFactory factory, String property, Object value) {
        try {
            factory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // The JDK factory supports these properties. Keep compatibility with alternate StAX
            // providers while the bounded stream still caps input expansion.
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining == 0) {
                throw new IOException("DOCX document.xml exceeds extraction limit");
            }
            int value = super.read();
            if (value >= 0) {
                remaining--;
            }
            return value;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (remaining == 0) {
                throw new IOException("DOCX document.xml exceeds extraction limit");
            }
            int read = super.read(buffer, offset, (int) Math.min(length, remaining));
            if (read > 0) {
                remaining -= read;
            }
            return read;
        }
    }
}
