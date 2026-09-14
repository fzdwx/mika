package ai.minum.extract;

import ai.minum.ocr.DefaultOcr;

public class ExtractConfig {

    private long maxExtractInputSize = 100L * 1024 * 1024;
    private int maxExtractedContentSize = 32 * 1024 * 1024;

    // 是否提取图片
    private boolean extractImage = false;
    // 图像提取最大大小 1MB
    private int imageExtractMaxSize = 1024 * 1024;
    // OCR 实例
    private DefaultOcr ocr;

    // 是否对其他格式使用 Tika 结构提取并转换成 Markdown
    private boolean fallback = true;

    private ImageUploader imageUploader;
    private boolean uploadImage = false;
    // 最大处理图片数量 -1 为不限制
    private Long maxHandleImageCount = 100L;

    public boolean canHandleImage() {
        if (maxHandleImageCount == -1) {
            return true;
        }
        if (maxHandleImageCount == 0) {
            return false;
        }
        maxHandleImageCount = maxHandleImageCount - 1;
        return maxHandleImageCount >= 0;
    }

    public Long getMaxHandleImageCount() {
        return maxHandleImageCount;
    }

    public ExtractConfig maxHandleImageCount(Long maxHandleImageCount) {
        if (maxHandleImageCount == null) {
            maxHandleImageCount = 100L;
        }
        if (maxHandleImageCount < 0) {
            maxHandleImageCount = -1L;
        }
        this.maxHandleImageCount = maxHandleImageCount;
        return this;
    }

    public static ExtractConfig defaultConfig() {
        return new ExtractConfig();
    }

    ExtractConfig copyForExtraction() {
        ExtractConfig copy = new ExtractConfig();
        copy.extractImage = extractImage;
        copy.imageExtractMaxSize = imageExtractMaxSize;
        copy.ocr = ocr;
        copy.fallback = fallback;
        copy.imageUploader = imageUploader;
        copy.uploadImage = uploadImage;
        copy.maxHandleImageCount = maxHandleImageCount;
        copy.maxExtractInputSize = maxExtractInputSize;
        copy.maxExtractedContentSize = maxExtractedContentSize;
        return copy;
    }

    public long maxExtractInputSize() {
        return maxExtractInputSize;
    }

    public ExtractConfig maxExtractInputSize(long maxExtractInputSize) {
        if (maxExtractInputSize <= 0) {
            throw new IllegalArgumentException("File size limit must be positive");
        }
        this.maxExtractInputSize = maxExtractInputSize;
        return this;
    }

    public int maxExtractedContentSize() {
        return maxExtractedContentSize;
    }

    public ExtractConfig maxExtractedContentSize(int maxExtractedContentSize) {
        if (maxExtractedContentSize <= 0) {
            throw new IllegalArgumentException("Extracted content size limit must be positive");
        }
        this.maxExtractedContentSize = maxExtractedContentSize;
        return this;
    }

    public boolean fallback() {
        return fallback;
    }

    public ExtractConfig fallback(boolean fallback) {
        this.fallback = fallback;
        return this;
    }

    public boolean uploadImage() {
        return uploadImage;
    }

    public ExtractConfig uploadImage(boolean uploadImage) {
        this.uploadImage = uploadImage;
        return this;
    }

    public ImageUploader imageUploader() {
        return imageUploader;
    }

    public ExtractConfig imageUploader(ImageUploader imageUploader) {
        this.imageUploader = imageUploader;
        this.uploadImage = true;
        return this;
    }

    public boolean ocr() {
        return extractImage;
    }

    public ExtractConfig ocr(boolean extractImage) {
        this.extractImage = extractImage;
        return this;
    }

    public ExtractConfig ocrUrl(String ocrUrl) {
        this.extractImage = true;
        this.ocr = DefaultOcr.of(ocrUrl);
        return this;
    }

    public DefaultOcr getOcr() {
        return ocr;
    }

    public int imageExtractMaxSize() {
        return imageExtractMaxSize;
    }

    public ExtractConfig imageExtractMaxSize(int imageExtractMaxSize) {
        if (imageExtractMaxSize < 0) {
            throw new IllegalArgumentException("Image size limit must not be negative");
        }
        this.imageExtractMaxSize = imageExtractMaxSize;
        return this;
    }
}
