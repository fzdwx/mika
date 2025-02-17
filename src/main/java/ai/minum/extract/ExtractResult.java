package ai.minum.extract;

import java.util.List;

public class ExtractResult {

    private final List<ExtractPage> pages;
    private final byte status;
    private final String errorMessage;

    private ExtractResult(List<ExtractPage> pages, byte status, String errorMessage) {
        this.pages = pages;
        this.status = status;
        this.errorMessage = errorMessage;
    }

    public static ExtractResult error(String errorMessage) {
        return new ExtractResult(null, (byte) 1, errorMessage);
    }

    public static ExtractResult error(Exception e) {
        return error(e.getMessage());
    }

    public static ExtractResult success(List<ExtractPage> pages) {
        return new ExtractResult(pages, (byte) 0, null);
    }

    public static ExtractResult successOfOne(String content) {
        ExtractPage page = ExtractPage.of(content);
        return success(List.of(page));
    }


    public boolean isError() {
        return status != 0;
    }

    @Override
    public String toString() {
        return "ExtractResult{" +
                "pages=" + pages +
                ", status=" + status +
                ", errorMessage='" + errorMessage + '\'' +
                '}';
    }
}
