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

    public boolean extractImage() {
        return extractImage;
    }

    public ExtractConfig extractImage(boolean extractImage) {
        this.extractImage = extractImage;
        return this;
    }

    public ExtractConfig ocrUrl(String ocrUrl) {
        this.extractImage = true;
        this.ocr = DefaultOcr.of(ocrUrl);
        return this;
    }

    public DefaultOcr ocr() {
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
