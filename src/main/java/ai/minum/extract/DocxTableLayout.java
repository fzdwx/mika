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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Restores merged-cell geometry that Tika's SAX DOCX extractor omits from XHTML. */
final class DocxTableLayout {
    private static final long MAX_DOCUMENT_XML_SIZE = 64L * 1024 * 1024;
    private static final int MAX_TABLE_COLUMNS = 1024;

    private DocxTableLayout() {
    }

    static int restore(Document document, Path source) throws IOException, XMLStreamException {
        if (source == null) {
            return 0;
        }
        List<TableLayout> sourceTables = readSourceTables(source);
        if (sourceTables.isEmpty()) {
            return 0;
        }

        List<Element> htmlTables = new ArrayList<>(document.select("table"));
        Set<Element> usedTables = Collections.newSetFromMap(new IdentityHashMap<>());
        int restored = 0;
        for (TableLayout layout : sourceTables) {
            layout.resolveVerticalMerges();
            if (!layout.hasMergedCells()) {
                continue;
            }
            Element match = findMatch(layout, htmlTables, usedTables);
            if (match != null && apply(layout, match)) {
                usedTables.add(match);
                restored++;
            }
        }
        return restored;
    }

    private static List<TableLayout> readSourceTables(Path source) throws IOException, XMLStreamException {
        try (ZipFile archive = new ZipFile(source.toFile())) {
            ZipEntry entry = archive.getEntry("word/document.xml");
            if (entry == null || entry.getSize() > MAX_DOCUMENT_XML_SIZE) {
                return List.of();
            }
            try (InputStream xml = new LimitedInputStream(archive.getInputStream(entry), MAX_DOCUMENT_XML_SIZE)) {
                return parseTables(xml);
            }
        }
    }

    private static List<TableLayout> parseTables(InputStream xml) throws XMLStreamException {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        setProperty(factory, XMLInputFactory.SUPPORT_DTD, false);
        setProperty(factory, "javax.xml.stream.isSupportingExternalEntities", false);
        XMLStreamReader reader = factory.createXMLStreamReader(xml);
        List<TableLayout> tables = new ArrayList<>();
        ArrayDeque<TableLayout> stack = new ArrayDeque<>();
        try {
            while (reader.hasNext()) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String name = reader.getLocalName();
                    if (!isWordprocessingElement(reader)) {
                        continue;
                    }
                    if ("tbl".equals(name)) {
                        TableLayout table = new TableLayout();
                        tables.add(table);
                        stack.push(table);
                    } else if (!stack.isEmpty()) {
                        consumeStart(reader, stack.peek(), name);
                    }
                } else if (event == XMLStreamConstants.CHARACTERS
                        || event == XMLStreamConstants.CDATA) {
                    if (!stack.isEmpty() && stack.peek().readingText) {
                        stack.peek().currentCellText().append(reader.getText());
                    }
                } else if (event == XMLStreamConstants.END_ELEMENT) {
                    if (!isWordprocessingElement(reader)) {
                        continue;
                    }
                    String name = reader.getLocalName();
                    if ("tbl".equals(name)) {
                        stack.pop();
                    } else if (!stack.isEmpty()) {
                        consumeEnd(stack.peek(), name);
                    }
                }
            }
        } finally {
            reader.close();
        }
        return tables;
    }

    private static void consumeStart(XMLStreamReader reader, TableLayout table, String name) {
        switch (name) {
            case "tr" -> table.startRow();
            case "tc" -> table.startCell();
            case "gridBefore" -> table.setGridBefore(integerAttribute(reader, "val", 0));
            case "gridSpan" -> table.setColumnSpan(integerAttribute(reader, "val", 1));
            case "vMerge" -> table.setVerticalMerge(attribute(reader, "val"));
            case "hMerge" -> table.setHorizontalMerge(attribute(reader, "val"));
            case "t" -> table.readingText = table.hasCurrentCell();
            case "tab", "br", "cr" -> table.appendSeparator();
            default -> {
            }
        }
    }

    private static void consumeEnd(TableLayout table, String name) {
        switch (name) {
            case "t" -> table.readingText = false;
            case "tc" -> table.endCell();
            case "tr" -> table.endRow();
            default -> {
            }
        }
    }

    private static Element findMatch(TableLayout layout, List<Element> htmlTables, Set<Element> used) {
        Element onlyShapeMatch = null;
        Element bestTextMatch = null;
        int bestTextScore = -1;
        int shapeMatches = 0;
        for (Element table : htmlTables) {
            if (used.contains(table)) {
                continue;
            }
            List<List<Element>> rows = htmlRows(table);
            if (!sameShape(layout, rows)) {
                continue;
            }
            shapeMatches++;
            onlyShapeMatch = table;
            int textScore = textMatchScore(layout, rows);
            if (textScore > bestTextScore) {
                bestTextScore = textScore;
                bestTextMatch = table;
            }
        }
        if (bestTextMatch != null) {
            return bestTextMatch;
        }
        // Empty template tables have no useful fingerprint. Apply only when the physical shape is
        // unique, so an AltChunk or header table cannot accidentally inherit body-table geometry.
        return layout.normalizedText().isEmpty() && shapeMatches == 1 ? onlyShapeMatch : null;
    }

    private static boolean sameShape(TableLayout layout, List<List<Element>> rows) {
        if (layout.rows.size() != rows.size()) {
            return false;
        }
        for (int row = 0; row < rows.size(); row++) {
            if (layout.rows.get(row).cells.size() != rows.get(row).size()) {
                return false;
            }
        }
        return true;
    }

    private static int textMatchScore(TableLayout layout, List<List<Element>> rows) {
        int sourceCellsWithText = 0;
        int compatibleCells = 0;
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            RowLayout sourceRow = layout.rows.get(rowIndex);
            List<Element> htmlRow = rows.get(rowIndex);
            for (int cellIndex = 0; cellIndex < htmlRow.size(); cellIndex++) {
                String sourceText = normalize(sourceRow.cells.get(cellIndex).text.toString());
                if (sourceText.isEmpty()) {
                    continue;
                }
                sourceCellsWithText++;
                Element cell = htmlRow.get(cellIndex);
                Element copy = cell.clone();
                copy.select("table").remove();
                String htmlText = normalize(copy.text());
                if (containsEither(sourceText, htmlText)
                        || containsEither(lettersAndDigits(sourceText), lettersAndDigits(htmlText))) {
                    compatibleCells++;
                }
            }
        }
        return sourceCellsWithText > 0 && compatibleCells * 3 >= sourceCellsWithText * 2
                ? compatibleCells : -1;
    }

    private static boolean containsEither(String first, String second) {
        return !first.isEmpty() && !second.isEmpty()
                && (first.contains(second) || second.contains(first));
    }

    private static String lettersAndDigits(String value) {
        StringBuilder result = new StringBuilder(value.length());
        value.codePoints().filter(Character::isLetterOrDigit).forEach(result::appendCodePoint);
        return result.toString();
    }

    private static boolean apply(TableLayout layout, Element table) {
        List<List<Element>> rows = htmlRows(table);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            RowLayout sourceRow = layout.rows.get(rowIndex);
            List<Element> htmlRow = rows.get(rowIndex);
            for (int cellIndex = 0; cellIndex < htmlRow.size(); cellIndex++) {
                CellLayout sourceCell = sourceRow.cells.get(cellIndex);
                Element htmlCell = htmlRow.get(cellIndex);
                if ((sourceCell.continuation || sourceCell.horizontalContinuation)
                        && (!normalize(htmlCell.text()).isEmpty()
                        || !htmlCell.select("img, table").isEmpty())) {
                    return false;
                }
            }
        }

        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            RowLayout sourceRow = layout.rows.get(rowIndex);
            List<Element> htmlRow = rows.get(rowIndex);
            for (int cellIndex = htmlRow.size() - 1; cellIndex >= 0; cellIndex--) {
                CellLayout sourceCell = sourceRow.cells.get(cellIndex);
                Element htmlCell = htmlRow.get(cellIndex);
                if (sourceCell.continuation || sourceCell.horizontalContinuation) {
                    htmlCell.remove();
                    continue;
                }
                if (sourceCell.columnSpan > 1) {
                    htmlCell.attr("colspan", Integer.toString(sourceCell.columnSpan));
                }
                if (sourceCell.rowSpan > 1) {
                    htmlCell.attr("rowspan", Integer.toString(sourceCell.rowSpan));
                }
            }
        }
        return true;
    }

    private static List<List<Element>> htmlRows(Element table) {
        List<List<Element>> rows = new ArrayList<>();
        for (Element child : table.children()) {
            if ("tr".equals(child.tagName())) {
                rows.add(htmlCells(child));
            } else if (Set.of("thead", "tbody", "tfoot").contains(child.tagName())) {
                for (Element row : child.children()) {
                    if ("tr".equals(row.tagName())) {
                        rows.add(htmlCells(row));
                    }
                }
            }
        }
        return rows;
    }

    private static List<Element> htmlCells(Element row) {
        List<Element> cells = new ArrayList<>();
        for (Element child : row.children()) {
            if ("td".equals(child.tagName()) || "th".equals(child.tagName())) {
                cells.add(child);
            }
        }
        return cells;
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replaceAll("\\s+", "");
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

    private static int integerAttribute(XMLStreamReader reader, String localName, int fallback) {
        try {
            int value = Integer.parseInt(attribute(reader, localName));
            return value >= fallback && value <= MAX_TABLE_COLUMNS ? value : fallback;
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    private static void setProperty(XMLInputFactory factory, String name, Object value) {
        try {
            factory.setProperty(name, value);
        } catch (IllegalArgumentException ignored) {
            // The JDK provider supports these properties; keep compatibility with alternate providers.
        }
    }

    private enum VerticalMerge {
        NONE, RESTART, CONTINUE
    }

    private enum HorizontalMerge {
        NONE, RESTART, CONTINUE
    }

    private static final class TableLayout {
        private final List<RowLayout> rows = new ArrayList<>();
        private RowLayout currentRow;
        private CellLayout currentCell;
        private boolean readingText;

        private void startRow() {
            currentRow = new RowLayout();
            rows.add(currentRow);
        }

        private void endRow() {
            resolveHorizontalMerges(currentRow);
            currentRow = null;
            currentCell = null;
            readingText = false;
        }

        private void startCell() {
            if (currentRow != null) {
                currentCell = new CellLayout();
                currentRow.cells.add(currentCell);
            }
        }

        private void endCell() {
            currentCell = null;
            readingText = false;
        }

        private boolean hasCurrentCell() {
            return currentCell != null;
        }

        private StringBuilder currentCellText() {
            return currentCell.text;
        }

        private void setGridBefore(int columns) {
            if (currentRow != null) {
                currentRow.gridBefore = columns;
            }
        }

        private void setColumnSpan(int columns) {
            if (currentCell != null) {
                currentCell.columnSpan = Math.max(1, columns);
            }
        }

        private void setVerticalMerge(String value) {
            if (currentCell == null) {
                return;
            }
            currentCell.verticalMerge = value == null || value.isBlank() || "continue".equals(value)
                    ? VerticalMerge.CONTINUE : "restart".equals(value)
                    ? VerticalMerge.RESTART : VerticalMerge.NONE;
        }

        private void setHorizontalMerge(String value) {
            if (currentCell == null) {
                return;
            }
            currentCell.horizontalMerge = value == null || value.isBlank() || "continue".equals(value)
                    ? HorizontalMerge.CONTINUE : "restart".equals(value)
                    ? HorizontalMerge.RESTART : HorizontalMerge.NONE;
        }

        private void resolveHorizontalMerges(RowLayout row) {
            if (row == null) {
                return;
            }
            CellLayout start = null;
            for (CellLayout cell : row.cells) {
                if (cell.horizontalMerge == HorizontalMerge.RESTART) {
                    start = cell;
                } else if (cell.horizontalMerge == HorizontalMerge.CONTINUE && start != null) {
                    start.columnSpan += cell.columnSpan;
                    cell.horizontalContinuation = true;
                } else {
                    start = null;
                }
            }
        }

        private void appendSeparator() {
            if (currentCell != null && !currentCell.text.isEmpty()) {
                currentCell.text.append(' ');
            }
        }

        private void resolveVerticalMerges() {
            Map<Integer, CellLayout> active = Map.of();
            for (RowLayout row : rows) {
                Map<Integer, CellLayout> next = new HashMap<>();
                Set<CellLayout> continued = Collections.newSetFromMap(new IdentityHashMap<>());
                int column = row.gridBefore;
                for (CellLayout cell : row.cells) {
                    if (cell.horizontalContinuation) {
                        continue;
                    }
                    if (cell.verticalMerge == VerticalMerge.RESTART) {
                        for (int offset = 0; offset < cell.columnSpan; offset++) {
                            next.put(column + offset, cell);
                        }
                    } else if (cell.verticalMerge == VerticalMerge.CONTINUE) {
                        Set<CellLayout> starts = new LinkedHashSet<>();
                        boolean complete = true;
                        for (int offset = 0; offset < cell.columnSpan; offset++) {
                            CellLayout start = active.get(column + offset);
                            if (start == null) {
                                complete = false;
                                break;
                            }
                            starts.add(start);
                        }
                        if (complete && starts.size() == 1) {
                            CellLayout start = starts.iterator().next();
                            if (continued.add(start)) {
                                start.rowSpan++;
                            }
                            cell.continuation = true;
                            for (int offset = 0; offset < cell.columnSpan; offset++) {
                                next.put(column + offset, start);
                            }
                        }
                    }
                    column += cell.columnSpan;
                }
                active = next;
            }
        }

        private boolean hasMergedCells() {
            return rows.stream().flatMap(row -> row.cells.stream())
                    .anyMatch(cell -> cell.columnSpan > 1 || cell.rowSpan > 1
                            || cell.continuation || cell.horizontalContinuation);
        }

        private String normalizedText() {
            StringBuilder text = new StringBuilder();
            for (RowLayout row : rows) {
                for (CellLayout cell : row.cells) {
                    text.append(cell.text);
                }
            }
            return normalize(text.toString());
        }
    }

    private static final class RowLayout {
        private final List<CellLayout> cells = new ArrayList<>();
        private int gridBefore;
    }

    private static final class CellLayout {
        private final StringBuilder text = new StringBuilder();
        private int columnSpan = 1;
        private int rowSpan = 1;
        private VerticalMerge verticalMerge = VerticalMerge.NONE;
        private HorizontalMerge horizontalMerge = HorizontalMerge.NONE;
        private boolean continuation;
        private boolean horizontalContinuation;
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long count;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++count > limit) {
                throw new IOException("DOCX document.xml size limit exceeded");
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            int allowed = (int) Math.min(length, Math.max(1, limit - count + 1));
            int read = super.read(bytes, offset, allowed);
            if (read >= 0 && (count += read) > limit) {
                throw new IOException("DOCX document.xml size limit exceeded");
            }
            return read;
        }

        @Override
        public long skip(long length) throws IOException {
            if (length <= 0) {
                return 0;
            }
            long allowed = Math.min(length, Math.max(1, limit - count + 1));
            long skipped = super.skip(allowed);
            if ((count += skipped) > limit) {
                throw new IOException("DOCX document.xml size limit exceeded");
            }
            return skipped;
        }
    }
}
