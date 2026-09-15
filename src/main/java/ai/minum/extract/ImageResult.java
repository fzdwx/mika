package ai.minum.extract;

import org.apache.poi.hwpf.usermodel.PictureType;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;

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

    public enum Format {
        JPEG("image/jpeg", "jpeg", true, Rasterization.IMAGE_IO,
                "jpg", "image/jpg", "image/pjpeg"),
        JPEG2000("image/jp2", "jp2", true, Rasterization.IMAGE_IO,
                "jpx", "image/jpx", "image/jpeg2000", "image/x-jpeg2000"),
        BMP("image/bmp", "bmp", true, Rasterization.IMAGE_IO,
                "image/x-ms-bmp"),
        PNG("image/png", "png", true, Rasterization.IMAGE_IO,
                "image/x-png"),
        TIFF("image/tiff", "tiff", true, Rasterization.IMAGE_IO,
                "tif", "image/tif", "image/x-tiff"),
        GIF("image/gif", "gif", true, Rasterization.IMAGE_IO),
        WEBP("image/webp", "webp", true, Rasterization.IMAGE_IO),
        WMF("image/wmf", "wmf", false, Rasterization.WMF,
                "image/x-wmf", "application/x-msmetafile"),
        EMF("image/emf", "emf", false, Rasterization.EMF,
                "image/x-emf", "application/x-emf"),
        PICT("image/pict", "pict", false, Rasterization.IMAGE_IO,
                "image/x-pict", "image/x-macpict", "application/x-macpict"),
        SVG("image/svg+xml", "svg+xml", false, Rasterization.UNSUPPORTED,
                "application/svg+xml", "svg"),
        UNKNOWN("application/octet-stream", "", false, Rasterization.UNSUPPORTED),
        ;

        private final String mimeType;
        private final String extractorType;
        private final boolean acceptedByDefaultOcr;
        private final Rasterization rasterization;
        private final Set<String> aliases;

        Format(String mimeType, String extractorType, boolean acceptedByDefaultOcr,
               Rasterization rasterization, String... aliases) {
            this.mimeType = mimeType;
            this.extractorType = extractorType;
            this.acceptedByDefaultOcr = acceptedByDefaultOcr;
            this.rasterization = rasterization;
            this.aliases = Set.of(aliases);
        }

        public String getMimeType() {
            return mimeType;
        }

        public String getExtractorType() {
            return extractorType;
        }

        Rasterization rasterization() {
            return rasterization;
        }

        public static Set<Format> defaultOcrFormats() {
            EnumSet<Format> formats = EnumSet.noneOf(Format.class);
            for (Format format : values()) {
                if (format.acceptedByDefaultOcr) {
                    formats.add(format);
                }
            }
            return Set.copyOf(formats);
        }

        public static Format fromMimeType(String mimeType) {
            return fromInputType(mimeType);
        }

        public static Format fromInputType(String inputType) {
            if (inputType == null) {
                return UNKNOWN;
            }
            String normalized = inputType.split(";", 2)[0].strip().toLowerCase(Locale.ROOT);
            for (Format format : values()) {
                if (format.mimeType.equals(normalized) || format.extractorType.equals(normalized)
                        || format.aliases.contains(normalized)) {
                    return format;
                }
            }
            return UNKNOWN;
        }

        enum Rasterization {
            IMAGE_IO, WMF, EMF, UNSUPPORTED
        }

        public static Format from(PictureType pictureType) {
            return switch (pictureType) {
                case PictureType.JPEG -> JPEG;
                case PictureType.BMP -> BMP;
                case PictureType.PNG -> PNG;
                case PictureType.TIFF -> TIFF;
                case PictureType.GIF -> GIF;
                case PictureType.WMF -> WMF;
                case PictureType.EMF -> EMF;
                case PictureType.PICT -> PICT;
                default -> UNKNOWN;
            };
        }

        public static Format from(org.apache.poi.common.usermodel.PictureType pictureTypeEnum) {
            return switch (pictureTypeEnum) {
                case JPEG -> JPEG;
                case BMP -> BMP;
                case PNG -> PNG;
                case TIFF -> TIFF;
                case GIF -> GIF;
                case WMF -> WMF;
                case EMF -> EMF;
                case PICT -> PICT;
                case SVG -> SVG;
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
