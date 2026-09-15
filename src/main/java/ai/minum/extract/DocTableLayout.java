package ai.minum.extract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.usermodel.Table;
import org.apache.poi.hwpf.usermodel.TableCell;
import org.apache.poi.hwpf.usermodel.TableIterator;
import org.apache.poi.hwpf.usermodel.TableRow;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Restores old binary Word table merges from HWPF cell descriptors. */
final class DocTableLayout {
    private DocTableLayout() {
    }

    static int restore(Document document, HWPFDocument word) {
        List<Layout> layouts = layouts(word);
        List<Element> htmlTables = new ArrayList<>(document.select("table"));
        Set<Element> used = Collections.newSetFromMap(new IdentityHashMap<>());
        int restored = 0;
        for (Layout layout : layouts) {
            if (!layout.hasMerges()) {
                continue;
            }
            Element table = findMatch(layout, htmlTables, used);
            if (table != null && apply(layout, table)) {
                used.add(table);
                restored++;
            }
        }
        return restored;
    }

    private static List<Layout> layouts(HWPFDocument word) {
        List<Layout> layouts = new ArrayList<>();
        TableIterator tables = new TableIterator(word.getRange());
        while (tables.hasNext()) {
            Table table = tables.next();
            Layout layout = new Layout();
            for (int rowIndex = 0; rowIndex < table.numRows(); rowIndex++) {
                TableRow row = table.getRow(rowIndex);
                Row layoutRow = new Row();
                layout.rows.add(layoutRow);
                Cell horizontalStart = null;
                for (int cellIndex = 0; cellIndex < row.numCells(); cellIndex++) {
                    TableCell cell = row.getCell(cellIndex);
                    Cell layoutCell = new Cell(cell.getLeftEdge(), cell.getLeftEdge() + cell.getWidth(),
                            normalize(cell.text()));
                    layoutRow.cells.add(layoutCell);
                    if (cell.isFirstMerged()) {
                        horizontalStart = layoutCell;
                    } else if (cell.isMerged() && horizontalStart != null) {
                        horizontalStart.columnSpan++;
                        horizontalStart.right = layoutCell.right;
                        layoutCell.horizontalContinuation = true;
                    } else {
                        horizontalStart = null;
                    }
                    layoutCell.firstVertical = cell.isFirstVerticallyMerged();
                    layoutCell.vertical = cell.isVerticallyMerged();
                }
            }
            layout.resolveVerticalMerges();
            layouts.add(layout);
        }
        return layouts;
    }

    private static Element findMatch(Layout layout, List<Element> tables, Set<Element> used) {
        Element best = null;
        int bestScore = -1;
        for (Element table : tables) {
            if (used.contains(table)) {
                continue;
            }
            List<List<Element>> rows = htmlRows(table);
            if (rows.size() != layout.rows.size()) {
                continue;
            }
            boolean sameShape = true;
            int score = 0;
            int sourceWithText = 0;
            for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
                Row source = layout.rows.get(rowIndex);
                if (source.cells.size() != rows.get(rowIndex).size()) {
                    sameShape = false;
                    break;
                }
                for (int cellIndex = 0; cellIndex < source.cells.size(); cellIndex++) {
                    String sourceText = source.cells.get(cellIndex).text;
                    if (!sourceText.isEmpty()) {
                        sourceWithText++;
                        String htmlText = normalize(rows.get(rowIndex).get(cellIndex).text());
                        if (!htmlText.isEmpty()
                                && (sourceText.contains(htmlText) || htmlText.contains(sourceText))) {
                            score++;
                        }
                    }
                }
            }
            if (sameShape && (sourceWithText == 0 || score * 3 >= sourceWithText * 2)
                    && score > bestScore) {
                best = table;
                bestScore = score;
            }
        }
        return best;
    }

    private static boolean apply(Layout layout, Element table) {
        List<List<Element>> rows = htmlRows(table);
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            for (int cellIndex = 0; cellIndex < rows.get(rowIndex).size(); cellIndex++) {
                Cell source = layout.rows.get(rowIndex).cells.get(cellIndex);
                Element html = rows.get(rowIndex).get(cellIndex);
                if ((source.horizontalContinuation || source.verticalContinuation)
                        && (!normalize(html.text()).isEmpty() || !html.select("img, table").isEmpty())) {
                    return false;
                }
            }
        }
        for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
            for (int cellIndex = rows.get(rowIndex).size() - 1; cellIndex >= 0; cellIndex--) {
                Cell source = layout.rows.get(rowIndex).cells.get(cellIndex);
                Element html = rows.get(rowIndex).get(cellIndex);
                if (source.horizontalContinuation || source.verticalContinuation) {
                    html.remove();
                } else {
                    if (source.columnSpan > 1) {
                        html.attr("colspan", Integer.toString(source.columnSpan));
                    }
                    if (source.rowSpan > 1) {
                        html.attr("rowspan", Integer.toString(source.rowSpan));
                    }
                }
            }
        }
        return true;
    }

    private static List<List<Element>> htmlRows(Element table) {
        List<List<Element>> rows = new ArrayList<>();
        for (Element child : table.children()) {
            if ("tr".equals(child.tagName())) {
                rows.add(cells(child));
            } else if (Set.of("thead", "tbody", "tfoot").contains(child.tagName())) {
                for (Element row : child.children()) {
                    if ("tr".equals(row.tagName())) {
                        rows.add(cells(row));
                    }
                }
            }
        }
        return rows;
    }

    private static List<Element> cells(Element row) {
        return row.children().stream()
                .filter(cell -> "td".equals(cell.tagName()) || "th".equals(cell.tagName()))
                .toList();
    }

    private static String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFKC)
                .replace("\u0007", "").replaceAll("\\s+", "");
    }

    private static final class Layout {
        private final List<Row> rows = new ArrayList<>();

        private void resolveVerticalMerges() {
            Map<Bounds, Cell> active = Map.of();
            for (Row row : rows) {
                Map<Bounds, Cell> next = new HashMap<>();
                for (Cell cell : row.cells) {
                    if (cell.horizontalContinuation) {
                        continue;
                    }
                    Bounds bounds = new Bounds(cell.left, cell.right);
                    if (cell.firstVertical) {
                        // Some producers repeat both vertical bits on continuation cells. An empty
                        // cell with the same bounds as an active merge is still a continuation.
                        Cell start = active.get(bounds);
                        if (start != null && cell.text.isEmpty()) {
                            start.rowSpan++;
                            cell.verticalContinuation = true;
                            next.put(bounds, start);
                        } else {
                            next.put(bounds, cell);
                        }
                    } else if (cell.vertical) {
                        Cell start = active.get(bounds);
                        if (start != null) {
                            start.rowSpan++;
                            cell.verticalContinuation = true;
                            next.put(bounds, start);
                        }
                    }
                }
                active = next;
            }
        }

        private boolean hasMerges() {
            return rows.stream().flatMap(row -> row.cells.stream())
                    .anyMatch(cell -> cell.columnSpan > 1 || cell.rowSpan > 1
                            || cell.horizontalContinuation || cell.verticalContinuation);
        }
    }

    private static final class Row {
        private final List<Cell> cells = new ArrayList<>();
    }

    private static final class Cell {
        private final int left;
        private int right;
        private final String text;
        private int columnSpan = 1;
        private int rowSpan = 1;
        private boolean horizontalContinuation;
        private boolean firstVertical;
        private boolean vertical;
        private boolean verticalContinuation;

        private Cell(int left, int right, String text) {
            this.left = left;
            this.right = right;
            this.text = text;
        }
    }

    private record Bounds(int left, int right) {
    }
}
