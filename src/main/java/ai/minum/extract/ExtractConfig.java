package ai.minum.extract;

import ai.minum.ocr.DefaultOcr;
import ai.minum.ocr.Ocr;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;

public class ExtractConfig {

    private long maxExtractInputSize = 100L * 1024 * 1024;
    private int maxExtractedContentSize = 32 * 1024 * 1024;

    // 是否提取图片
    private boolean extractImage = false;
    // 图像提取最大大小 1MB
    private int imageExtractMaxSize = 1024 * 1024;
    // OCR 实例
    private Ocr ocr;
    // The bundled HTTP OCR protocol has been verified with these raster formats. Legacy vector
    // formats are converted to PNG before OCR while the uploader still receives the original.
    private Set<ImageResult.Format> ocrImageFormats = ImageResult.Format.defaultOcrFormats();

    private String pdfPassword = "";
    private byte[] pdfKeyStore;
    private String pdfKeyStorePassword = "";
    private String pdfKeyAlias;

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
        copy.ocrImageFormats = EnumSet.copyOf(ocrImageFormats);
        copy.pdfPassword = pdfPassword;
        copy.pdfKeyStore = pdfKeyStore == null ? null : Arrays.copyOf(pdfKeyStore, pdfKeyStore.length);
        copy.pdfKeyStorePassword = pdfKeyStorePassword;
        copy.pdfKeyAlias = pdfKeyAlias;
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

    public Ocr getOcr() {
        return ocr;
    }

    public ExtractConfig ocr(Ocr ocr) {
        if (ocr == null) {
            throw new IllegalArgumentException("OCR backend is required");
        }
        this.ocr = ocr;
        this.extractImage = true;
        return this;
    }

    /** Declares formats accepted directly by the OCR backend; other formats are rasterized to PNG. */
    public ExtractConfig ocrImageFormats(Set<ImageResult.Format> formats) {
        if (formats == null || formats.isEmpty()) {
            throw new IllegalArgumentException("At least one OCR image format is required");
        }
        this.ocrImageFormats = EnumSet.copyOf(formats);
        return this;
    }

    boolean ocrAccepts(ImageResult.Format format) {
        return ocrImageFormats.contains(format);
    }

    public ExtractConfig pdfPassword(String password) {
        this.pdfPassword = password == null ? "" : password;
        return this;
    }

    String pdfPassword() {
        return pdfPassword;
    }

    /** Supplies a PKCS#12/JKS key store and optional alias for certificate-encrypted PDFs. */
    public ExtractConfig pdfCertificate(byte[] keyStore, String alias) {
        return pdfCertificate(keyStore, "", alias);
    }

    /** Supplies a PKCS#12/JKS key store, its password, and an optional certificate alias. */
    public ExtractConfig pdfCertificate(byte[] keyStore, String keyStorePassword, String alias) {
        if (keyStore == null || keyStore.length == 0) {
            throw new IllegalArgumentException("PDF certificate key store is required");
        }
        this.pdfKeyStore = Arrays.copyOf(keyStore, keyStore.length);
        this.pdfKeyStorePassword = keyStorePassword == null ? "" : keyStorePassword;
        this.pdfKeyAlias = alias;
        return this;
    }

    byte[] pdfKeyStore() {
        return pdfKeyStore == null ? null : Arrays.copyOf(pdfKeyStore, pdfKeyStore.length);
    }

    String pdfKeyAlias() {
        return pdfKeyAlias;
    }

    String pdfKeyStorePassword() {
        return pdfKeyStorePassword;
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
