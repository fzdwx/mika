package ai.minum.extract;

import java.util.ArrayList;
import java.util.List;

public class ExtractResult {

    private List<ExtractPage> pages;
    private boolean success;
    private String errorMessage;

    private boolean hasImage;
    private boolean hasTable;

    private ExtractResult(List<ExtractPage> pages, boolean status, String errorMessage) {
        this.pages = pages;
        this.success = status;
        this.errorMessage = errorMessage;
    }

    public static ExtractResult error(String errorMessage) {
        return new ExtractResult(null, false, errorMessage);
    }

    public static ExtractResult error(Exception e) {
        return error(e.getMessage());
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
