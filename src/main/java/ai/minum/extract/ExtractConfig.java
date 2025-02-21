package ai.minum.extract;

import ai.minum.ocr.DefaultOcr;

public class ExtractConfig {

    // 是否提取图片
    private boolean extractImage = false;
    // OCR路径
    private String ocrPath;
    // 图像提取最大大小 1MB
    private int imageExtractMaxSize = 1024 * 1024;
    private DefaultOcr ocr;

    public static ExtractConfig defaultConfig() {
        return new ExtractConfig();
    }

    public boolean extractImage() {
        return extractImage;
    }

    public ExtractConfig extractImage(boolean extractImage) {
        this.extractImage = extractImage;
        return this;
    }

    public ExtractConfig ocrUrl(String ocrUrl) {
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
