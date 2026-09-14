package ai.minum.extract;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ExtractResult {

    private List<ExtractPage> pages;
    private boolean success;
    private String errorMessage;

    private boolean hasImage;
    private boolean hasTable;
    private final List<String> warnings = new ArrayList<>();

    private ExtractResult(List<ExtractPage> pages, boolean status, String errorMessage) {
        this.pages = pages;
        this.success = status;
        this.errorMessage = errorMessage;
    }

    public static ExtractResult error(String errorMessage) {
        return new ExtractResult(null, false, errorMessage);
    }

    public static ExtractResult error(Exception e) {
        if (e == null) {
            return error("Unknown extraction error");
        }
        Throwable current = e;
        String message = null;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                message = current.getMessage();
            }
            if (current.getCause() == current) {
                break;
            }
            current = current.getCause();
        }
        return error(message == null ? e.getClass().getSimpleName() : message);
    }

    public static ExtractResult of() {
        return new ExtractResult(new ArrayList<>(), true, null);
    }

    public static ExtractResult success(List<ExtractPage> pages) {
        return new ExtractResult(pages, true, null);
    }

    public static ExtractResult successOfOne(String content) {
        ExtractPage page = ExtractPage.of(content);
        return success(List.of(page));
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public List<ExtractPage> getPages() {
        return pages;
    }

    /** All page contents are Markdown. Page indices remain available through getPages(). */
    public String getMarkdown() {
        if (pages == null) {
            return "";
        }
        return pages.stream()
                .map(ExtractPage::getContent)
                .filter(content -> content != null && !content.isBlank())
                .collect(Collectors.joining("\n\n"));
    }

    public String getContentType() {
        return "text/markdown";
    }

    public List<String> getWarnings() {
        return List.copyOf(warnings);
    }

    public void addWarning(String warning) {
        if (!warnings.contains(warning)) {
            warnings.add(warning);
        }
    }

    public boolean isError() {
        return !success;
    }

    public boolean hasImage() {
        return hasImage;
    }

    public boolean hasTable() {
        return hasTable;
    }

    public ExtractResult setHasImage(boolean hasImage) {
        this.hasImage = hasImage;
        return this;
    }

    public ExtractResult setHasTable(boolean hasTable) {
        this.hasTable = hasTable;
        return this;
    }

    @Override
    public String toString() {
        return "ExtractResult{" +
                "pages=" + pages +
                ", status=" + success +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }

    public void addPage(Long pageID, String content) {
        if (pages == null) {
            pages = new ArrayList<>();
        }
        pages.add(ExtractPage.of(pageID, content));
    }

}
