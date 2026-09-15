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
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Replaces Tika's flattened DOCX comment tail with author, anchor and reply structure. */
final class DocxComments {
    private static final long MAX_XML_SIZE = 32L * 1024 * 1024;
    private static final int MAX_COMMENTS = 4096;
    private static final int MAX_ANCHOR_CHARACTERS = 512;

    private DocxComments() {
    }

    static int restore(Document document, Path source) throws IOException, XMLStreamException {
        if (source == null) {
            return 0;
        }
        List<Comment> comments;
        Map<String, String> anchors;
        Map<String, String> parentParagraphs;
        try (ZipFile archive = new ZipFile(source.toFile())) {
            comments = readComments(archive);
            if (comments.isEmpty()) {
                return 0;
            }
            Set<String> commentIds = new LinkedHashSet<>();
            if (comments.stream().anyMatch(comment -> comment.id.isBlank() || !commentIds.add(comment.id))) {
                return 0;
            }
            anchors = readAnchors(archive);
            parentParagraphs = readParentParagraphs(archive);
        }

        List<String> flattenedParagraphs = comments.stream()
                .flatMap(comment -> comment.paragraphs.stream())
                .filter(value -> !value.isBlank())
                .toList();
        if (flattenedParagraphs.isEmpty() || !removeTikaCommentText(document, flattenedParagraphs)) {
            return 0;
        }

        Map<String, String> paragraphToComment = new HashMap<>();
        for (Comment comment : comments) {
            comment.anchor = anchors.getOrDefault(comment.id, "");
            if (!comment.paragraphId.isBlank()) {
                paragraphToComment.put(comment.paragraphId, comment.id);
            }
        }
        for (Comment comment : comments) {
            comment.parentId = paragraphToComment.getOrDefault(
                    parentParagraphs.getOrDefault(comment.paragraphId, ""), "");
        }
        appendStructuredComments(document, comments);
        return comments.size();
    }

    private static List<Comment> readComments(ZipFile archive) throws IOException, XMLStreamException {
        ZipEntry entry = archive.getEntry("word/comments.xml");
        if (!usable(entry)) {
            return List.of();
        }
        try (InputStream input = new LimitedInputStream(archive.getInputStream(entry), MAX_XML_SIZE)) {
            XMLStreamReader reader = xmlReader(input);
            List<Comment> comments = new ArrayList<>();
            Comment current = null;
            StringBuilder paragraph = null;
            boolean readingText = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String name = reader.getLocalName();
                        if ("comment".equals(name) && isWordprocessingElement(reader)
                                && comments.size() < MAX_COMMENTS) {
                            current = new Comment(attribute(reader, "id"), attribute(reader, "author"),
                                    attribute(reader, "date"));
                            comments.add(current);
                        } else if (current != null && "p".equals(name) && isWordprocessingElement(reader)) {
                            paragraph = new StringBuilder();
                            if (current.paragraphId.isBlank()) {
                                current.paragraphId = valueOrEmpty(attribute(reader, "paraId"));
                            }
                        } else if (current != null && paragraph != null && isWordprocessingElement(reader)) {
                            if ("t".equals(name)) {
                                readingText = true;
                            } else if ("tab".equals(name)) {
                                paragraph.append('\t');
                            } else if ("br".equals(name) || "cr".equals(name)) {
                                paragraph.append('\n');
                            }
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) && readingText && paragraph != null) {
                        paragraph.append(reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String name = reader.getLocalName();
                        if ("t".equals(name) && isWordprocessingElement(reader)) {
                            readingText = false;
                        } else if ("p".equals(name) && isWordprocessingElement(reader)
                                && current != null && paragraph != null) {
                            current.paragraphs.add(paragraph.toString().strip());
                            paragraph = null;
                        } else if ("comment".equals(name) && isWordprocessingElement(reader)) {
                            current = null;
                            paragraph = null;
                            readingText = false;
                        }
                    }
                }
            } finally {
                reader.close();
            }
            return comments.stream().filter(Comment::hasText).toList();
        }
    }

    private static Map<String, String> readAnchors(ZipFile archive) throws IOException, XMLStreamException {
        ZipEntry entry = archive.getEntry("word/document.xml");
        if (!usable(entry)) {
            return Map.of();
        }
        try (InputStream input = new LimitedInputStream(archive.getInputStream(entry), MAX_XML_SIZE)) {
            XMLStreamReader reader = xmlReader(input);
            Map<String, StringBuilder> anchors = new LinkedHashMap<>();
            Set<String> active = new LinkedHashSet<>();
            boolean readingText = false;
            try {
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT && isWordprocessingElement(reader)) {
                        String name = reader.getLocalName();
                        if ("commentRangeStart".equals(name)) {
                            String id = valueOrEmpty(attribute(reader, "id"));
                            if (!id.isBlank()) {
                                active.add(id);
                                anchors.computeIfAbsent(id, ignored -> new StringBuilder());
                            }
                        } else if ("commentRangeEnd".equals(name)) {
                            active.remove(valueOrEmpty(attribute(reader, "id")));
                        } else if ("t".equals(name)) {
                            readingText = true;
                        } else if ("tab".equals(name) || "br".equals(name) || "cr".equals(name)) {
                            appendToActive(anchors, active, " ");
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS
                            || event == XMLStreamConstants.CDATA) && readingText) {
                        appendToActive(anchors, active, reader.getText());
                    } else if (event == XMLStreamConstants.END_ELEMENT
                            && "t".equals(reader.getLocalName()) && isWordprocessingElement(reader)) {
                        readingText = false;
                    }
                }
            } finally {
                reader.close();
            }
            Map<String, String> result = new LinkedHashMap<>();
            anchors.forEach((id, value) -> result.put(id, abbreviate(value.toString().strip())));
            return result;
        }
    }

    private static Map<String, String> readParentParagraphs(ZipFile archive)
            throws IOException, XMLStreamException {
        ZipEntry entry = archive.getEntry("word/commentsExtended.xml");
        if (!usable(entry)) {
            return Map.of();
        }
        try (InputStream input = new LimitedInputStream(archive.getInputStream(entry), MAX_XML_SIZE)) {
            XMLStreamReader reader = xmlReader(input);
            Map<String, String> result = new HashMap<>();
            try {
                while (reader.hasNext()) {
                    if (reader.next() == XMLStreamConstants.START_ELEMENT
                            && "commentEx".equals(reader.getLocalName())) {
                        String child = valueOrEmpty(attribute(reader, "paraId"));
                        String parent = valueOrEmpty(attribute(reader, "paraIdParent"));
                        if (!child.isBlank() && !parent.isBlank()) {
                            result.put(child, parent);
                        }
                    }
                }
            } finally {
                reader.close();
            }
            return result;
        }
    }

    private static boolean removeTikaCommentText(Document document, List<String> expected) {
        List<Element> commentContainers = document.select("div.comment");
        if (!commentContainers.isEmpty()) {
            List<String> actual = commentContainers.stream()
                    .flatMap(container -> container.select("p").stream())
                    .map(Element::text)
                    .filter(value -> !normalize(value).isEmpty())
                    .toList();
            List<String> normalizedExpected = expected.stream()
                    .filter(value -> !normalize(value).isEmpty())
                    .toList();
            if (actual.size() == normalizedExpected.size()) {
                boolean matches = true;
                for (int index = 0; index < actual.size(); index++) {
                    if (!normalize(actual.get(index)).equals(normalize(normalizedExpected.get(index)))) {
                        matches = false;
                        break;
                    }
                }
                if (matches) {
                    commentContainers.forEach(Element::remove);
                    return true;
                }
            }
        }
        return removeFlattenedCommentTail(document, expected);
    }

    private static boolean removeFlattenedCommentTail(Document document, List<String> expected) {
        List<Element> children = new ArrayList<>(document.body().children());
        int expectedIndex = expected.size() - 1;
        int firstMatch = -1;
        for (int childIndex = children.size() - 1; childIndex >= 0 && expectedIndex >= 0; childIndex--) {
            String actual = normalize(children.get(childIndex).text());
            if (actual.isEmpty()) {
                continue;
            }
            if (!actual.equals(normalize(expected.get(expectedIndex)))) {
                return false;
            }
            firstMatch = childIndex;
            expectedIndex--;
        }
        if (expectedIndex >= 0) {
            return false;
        }
        for (int index = children.size() - 1; index >= firstMatch; index--) {
            children.get(index).remove();
        }
        return true;
    }

    private static void appendStructuredComments(Document document, List<Comment> comments) {
        Map<String, Comment> byId = new LinkedHashMap<>();
        Map<String, List<Comment>> replies = new LinkedHashMap<>();
        for (Comment comment : comments) {
            byId.put(comment.id, comment);
            replies.computeIfAbsent(comment.parentId, ignored -> new ArrayList<>()).add(comment);
        }
        Element section = document.body().appendElement("section").addClass("mika-comments");
        section.appendElement("h3").text("Comments");
        Element list = section.appendElement("ul");
        Set<String> rendered = new LinkedHashSet<>();
        for (Comment comment : comments) {
            if (comment.parentId.isBlank() || !byId.containsKey(comment.parentId)) {
                appendComment(list, comment, replies, rendered);
            }
        }
        for (Comment comment : comments) {
            appendComment(list, comment, replies, rendered);
        }
    }

    private static void appendComment(Element list, Comment comment, Map<String, List<Comment>> replies,
                                      Set<String> rendered) {
        if (!rendered.add(comment.id)) {
            return;
        }
        Element item = list.appendElement("li");
        Element first = item.appendElement("p");
        boolean hasLabel = false;
        if (!comment.author.isBlank()) {
            first.appendElement("strong").text(comment.author);
            hasLabel = true;
        }
        if (!comment.anchor.isBlank()) {
            if (hasLabel) {
                first.appendText(" on ");
            }
            first.appendText("“" + comment.anchor + "”");
            hasLabel = true;
        }
        if (!comment.date.isBlank()) {
            first.appendText(" (" + comment.date + ")");
            hasLabel = true;
        }
        List<String> paragraphs = comment.paragraphs.stream().filter(value -> !value.isBlank()).toList();
        if (hasLabel) {
            first.appendText(": ");
        }
        first.appendText(paragraphs.getFirst());
        for (int index = 1; index < paragraphs.size(); index++) {
            item.appendElement("p").text(paragraphs.get(index));
        }
        List<Comment> children = replies.getOrDefault(comment.id, List.of());
        if (!children.isEmpty()) {
            Element nested = item.appendElement("ul");
            for (Comment reply : children) {
                appendComment(nested, reply, replies, rendered);
            }
        }
    }

    private static XMLStreamReader xmlReader(InputStream input) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        return factory.createXMLStreamReader(input);
    }

    private static void appendToActive(Map<String, StringBuilder> anchors, Set<String> active, String text) {
        for (String id : active) {
            StringBuilder value = anchors.get(id);
            if (value != null && value.length() < MAX_ANCHOR_CHARACTERS) {
                value.append(text, 0, Math.min(text.length(), MAX_ANCHOR_CHARACTERS - value.length()));
            }
        }
    }

    private static String abbreviate(String value) {
        if (value.codePointCount(0, value.length()) <= 160) {
            return value;
        }
        int end = value.offsetByCodePoints(0, 159);
        return value.substring(0, end).stripTrailing() + "…";
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "").strip();
    }

    private static String attribute(XMLStreamReader reader, String localName) {
        for (int index = 0; index < reader.getAttributeCount(); index++) {
            if (localName.equals(reader.getAttributeLocalName(index))) {
                return reader.getAttributeValue(index);
            }
        }
        return null;
    }

    private static boolean isWordprocessingElement(XMLStreamReader reader) {
        String namespace = reader.getNamespaceURI();
        return namespace != null && namespace.contains("wordprocessingml");
    }

    private static boolean usable(ZipEntry entry) {
        return entry != null && (entry.getSize() < 0 || entry.getSize() <= MAX_XML_SIZE);
    }

    private static String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // The JDK provider supports these properties; retain alternate-provider compatibility.
        }
    }

    private static final class Comment {
        private final String id;
        private final String author;
        private final String date;
        private final List<String> paragraphs = new ArrayList<>();
        private String paragraphId = "";
        private String parentId = "";
        private String anchor = "";

        private Comment(String id, String author, String date) {
            this.id = valueOrEmpty(id);
            this.author = valueOrEmpty(author).strip();
            this.date = valueOrEmpty(date).strip();
        }

        private boolean hasText() {
            return paragraphs.stream().anyMatch(value -> !value.isBlank());
        }
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private long remaining;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            remaining = limit;
        }

        @Override
        public int read() throws IOException {
            if (remaining <= 0) {
                throw new IOException("DOCX XML size limit exceeded");
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
                throw new IOException("DOCX XML size limit exceeded");
            }
            int count = super.read(bytes, offset, (int) Math.min(length, remaining));
            if (count >= 0) {
                remaining -= count;
            }
            return count;
        }
    }
}
