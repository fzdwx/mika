package ai.minum.ocr;

/** Pluggable OCR backend. Implementations may route handwriting and printed text differently. */
@FunctionalInterface
public interface Ocr {
    String recognize(byte[] image, String mimeType);
}
