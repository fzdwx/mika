package ai.minum.extract;

public class ExtractPage {

    private Long page;
    private String content;

    public ExtractPage(String content, Long page) {
        this.content = content;
        this.page = page;
    }

    public static ExtractPage of(String content) {
        return new ExtractPage(content, 0L);
    }

    public static ExtractPage of(Long pageID, String content) {
        return new ExtractPage(content, pageID);
    }

    @Override
    public String toString() {
        return "ExtractPage{" +
                "page=" + page +
                ", content='" + content + '\'' +
                '}';
    }

    public Long getPage() {
        return page;
    }

    public String getContent() {
        return content;
    }
}
