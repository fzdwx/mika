package ai.minum.extract;

import lombok.Data;
import lombok.Getter;
import org.apache.poi.hwpf.usermodel.PictureType;

public class ImageResult {
    private byte[] data;
    private Format mimeType;

    public static ImageResult of(byte[] content, PictureType pictureType) {
        return of(content, Format.from(pictureType));
    }

    public static ImageResult of(byte[] content, org.apache.poi.common.usermodel.PictureType pictureTypeEnum) {
        return of(content, Format.from(pictureTypeEnum));
    }

    public int length() {
        return data.length;
    }

    public Format getMimeType() {
        return mimeType;
    }

    public byte[] getData() {
        return data;
    }

    @Getter
    public enum Format {
        JPEG("image/jpeg"),
        BMP("image/bmp"),
        PNG("image/png"),
        TIFF("image/tiff"),
        UNKNOWN("application/octet-stream"),
        ;

        private final String mimeType;

        Format(String mimeType) {
            this.mimeType = mimeType;
        }

        public static Format from(PictureType pictureType) {
            return switch (pictureType) {
                case PictureType.JPEG -> JPEG;
                case PictureType.BMP -> BMP;
                case PictureType.PNG -> PNG;
                case PictureType.TIFF -> TIFF;
                default -> UNKNOWN;
            };
        }

        public static Format from(org.apache.poi.common.usermodel.PictureType pictureTypeEnum) {
            return switch (pictureTypeEnum) {
                case JPEG -> JPEG;
                case BMP -> BMP;
                case PNG -> PNG;
                case TIFF -> TIFF;
                default -> UNKNOWN;
            };
        }
    }

    public static ImageResult of(byte[] data, Format mimeType) {
        ImageResult result = new ImageResult();
        result.data = data;
        result.mimeType = mimeType;
        return result;
    }
}
