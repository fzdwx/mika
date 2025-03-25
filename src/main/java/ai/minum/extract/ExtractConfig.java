package ai.minum.extract;

import ai.minum.ocr.DefaultOcr;

public class ExtractConfig {

    // 是否提取图片
    private boolean extractImage = false;
    // 图像提取最大大小 1MB
    private int imageExtractMaxSize = 1024 * 1024;
    // OCR 实例
    private DefaultOcr ocr;

    // 是否回退，使用 tika 纯文本提取
    private boolean fallback = true;

    private ImageUploader imageUploader;
    private boolean uploadImage = false;
    // 最大处理图片数量 -1 为不限制
    private Long maxHandleImageCount = 100L;

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
        this.imageExtractMaxSize = imageExtractMaxSize;
        return this;
    }
}
