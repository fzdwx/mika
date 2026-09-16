package ai.minum.extract;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/** Conservative cleanup and reading-order repair for OCR applied to embedded document images. */
final class OcrImageQuality {
    private static final int MAX_OCR_REGIONS_PER_IMAGE = 24;
    private static final Pattern TEACHING_SITE_WATERMARK = Pattern.compile(
            "(?iu).*?(?:学科网|zxxk\\s*\\.\\s*com).*?");
    private static final Pattern DECORATIVE_HEADING_PREFIX = Pattern.compile(
            "(?iu)^[a-z](?:\\d|[+*#]){1,2}\\s*(?=\\p{IsHan})");
    private static final Pattern ISOLATED_NOISE = Pattern.compile(
            "^[\\p{N}\\p{P}\\p{S}\\s]{1,3}$");

    private OcrImageQuality() {
    }

    static String cleanText(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        List<String> kept = new ArrayList<>();
        for (String sourceLine : text.replace('\r', '\n').split("\\n+")) {
            String line = sourceLine.strip();
            if (line.isEmpty() || TEACHING_SITE_WATERMARK.matcher(line).matches()
                    || ISOLATED_NOISE.matcher(line).matches()) {
                continue;
            }
            line = DECORATIVE_HEADING_PREFIX.matcher(line).replaceFirst("").strip();
            if (!line.isEmpty()) {
                kept.add(line);
            }
        }
        return String.join("\n", kept);
    }

    /**
     * Splits screenshot-like raster images at full-height whitespace gutters. Native PDF text is
     * handled elsewhere; this only repairs the common case where an entire multi-column page is a
     * single embedded image.
     */
    static List<ImageResult> readingOrderColumns(ImageResult source) {
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(source.getData()));
            if (image == null || image.getWidth() < 1000 || image.getHeight() < 400) {
                return List.of(source);
            }
            // One-bit monochrome images are normally archival scans. Their paper margins and
            // handwritten gaps are not reliable column boundaries and splitting them multiplies
            // OCR calls without improving reading order.
            if (image.getColorModel().getPixelSize() <= 2 || isBinaryMonochrome(image)) {
                return List.of(source);
            }
            List<Gutter> gutters = gutters(image);
            if (gutters.isEmpty()) {
                return List.of(source);
            }
            if (gutters.size() == 1 && image.getHeight() >= 1000) {
                List<ImageResult> regions = complexPageRegions(image, gutters.getFirst());
                if (regions.size() > 2 && regions.size() <= MAX_OCR_REGIONS_PER_IMAGE) {
                    return regions;
                }
            }
            List<ImageResult> columns = new ArrayList<>();
            int left = 0;
            for (Gutter gutter : gutters) {
                addColumn(image, left, gutter.start(), columns);
                left = gutter.end();
            }
            addColumn(image, left, image.getWidth(), columns);
            return columns.size() > 1 ? columns : List.of(source);
        } catch (Exception unreadable) {
            return List.of(source);
        }
    }

    private static boolean isBinaryMonochrome(BufferedImage image) {
        int xStep = Math.max(1, image.getWidth() / 256);
        int yStep = Math.max(1, image.getHeight() / 256);
        for (int y = 0; y < image.getHeight(); y += yStep) {
            for (int x = 0; x < image.getWidth(); x += xStep) {
                int rgb = image.getRGB(x, y);
                int red = (rgb >>> 16) & 0xff;
                int green = (rgb >>> 8) & 0xff;
                int blue = rgb & 0xff;
                if (Math.abs(red - green) > 2 || Math.abs(red - blue) > 2
                        || (red > 8 && red < 247)) {
                    return false;
                }
            }
        }
        return true;
    }

    private static List<Gutter> gutters(BufferedImage image) {
        return gutters(image, 0, 0, image.getWidth(), image.getHeight());
    }

    private static List<Gutter> gutters(BufferedImage image, int originX, int originY,
                                        int width, int height) {
        int rowStep = Math.max(1, height / 1200);
        int samples = (height + rowStep - 1) / rowStep;
        double[] ink = new double[width];
        for (int x = 0; x < width; x++) {
            int dark = 0;
            for (int y = 0; y < height; y += rowStep) {
                int argb = image.getRGB(originX + x, originY + y);
                int alpha = argb >>> 24;
                if (alpha == 0) {
                    continue;
                }
                int red = (argb >>> 16) & 0xff;
                int green = (argb >>> 8) & 0xff;
                int blue = argb & 0xff;
                if ((red * 299 + green * 587 + blue * 114) / 1000 < 210) {
                    dark++;
                }
            }
            ink[x] = (double) dark / samples;
        }

        int edge = Math.max(1, width / 14);
        int minimumGutter = Math.max(8, width / 180);
        List<Gutter> candidates = new ArrayList<>();
        int start = -1;
        for (int x = edge; x < width - edge; x++) {
            if (ink[x] <= 0.008) {
                if (start < 0) {
                    start = x;
                }
            } else if (start >= 0) {
                if (x - start >= minimumGutter) {
                    candidates.add(new Gutter(start, x));
                }
                start = -1;
            }
        }
        if (start >= 0 && width - edge - start >= minimumGutter) {
            candidates.add(new Gutter(start, width - edge));
        }

        int minimumColumn = Math.max(180, width / 7);
        List<Gutter> selected = new ArrayList<>();
        for (Gutter candidate : candidates.stream()
                .sorted(Comparator.comparingInt(Gutter::width).reversed()).toList()) {
            int center = candidate.center();
            boolean enoughSpace = center >= minimumColumn && width - center >= minimumColumn;
            for (Gutter existing : selected) {
                enoughSpace &= Math.abs(center - existing.center()) >= minimumColumn;
            }
            if (enoughSpace && selected.size() < 3) {
                selected.add(candidate);
            }
        }
        selected.sort(Comparator.comparingInt(Gutter::start));
        return selected;
    }

    /** Handles newspaper pages whose headline spans several body columns. */
    private static List<ImageResult> complexPageRegions(BufferedImage image, Gutter mainGutter)
            throws Exception {
        List<ImageResult> result = new ArrayList<>();
        addLayoutColumn(image, 0, mainGutter.start(), result);
        addColumn(image, mainGutter.end(), image.getWidth(), result);
        return result;
    }

    private static void addLayoutColumn(BufferedImage image, int left, int right,
                                        List<ImageResult> output) throws Exception {
        int width = right - left;
        int slabHeight = 180;
        List<LayoutSlab> slabs = new ArrayList<>();
        for (int top = 0; top < image.getHeight(); top += slabHeight) {
            int bottom = Math.min(image.getHeight(), top + slabHeight);
            List<Gutter> local = mergeNearbyGutters(gutters(
                    image, left, top, width, bottom - top));
            slabs.add(new LayoutSlab(top, bottom, local));
        }

        int index = 0;
        while (index < slabs.size()) {
            LayoutSlab first = slabs.get(index);
            int end = index + 1;
            while (end < slabs.size() && sameLayout(first.gutters(), slabs.get(end).gutters(), width)) {
                end++;
            }
            int top = first.top();
            int bottom = slabs.get(end - 1).bottom();
            if (first.gutters().isEmpty()) {
                addRegion(image, left, top, width, bottom - top, output);
            } else {
                int columnLeft = 0;
                for (Gutter gutter : first.gutters()) {
                    addRegion(image, left + columnLeft, top,
                            gutter.start() - columnLeft, bottom - top, output);
                    columnLeft = gutter.end();
                }
                addRegion(image, left + columnLeft, top,
                        width - columnLeft, bottom - top, output);
            }
            index = end;
        }
    }

    private static List<Gutter> mergeNearbyGutters(List<Gutter> gutters) {
        if (gutters.size() < 2) {
            return gutters;
        }
        List<Gutter> merged = new ArrayList<>();
        Gutter current = gutters.getFirst();
        for (int i = 1; i < gutters.size(); i++) {
            Gutter next = gutters.get(i);
            if (next.start() - current.end() <= 6) {
                current = new Gutter(current.start(), next.end());
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);
        return merged;
    }

    private static boolean sameLayout(List<Gutter> left, List<Gutter> right, int width) {
        if (left.size() != right.size()) {
            return false;
        }
        int tolerance = Math.max(18, width / 35);
        for (int i = 0; i < left.size(); i++) {
            if (Math.abs(left.get(i).center() - right.get(i).center()) > tolerance) {
                return false;
            }
        }
        return true;
    }

    private static void addRegion(BufferedImage image, int left, int top, int width, int height,
                                  List<ImageResult> regions) throws Exception {
        if (width < 180 || height < 80) {
            return;
        }
        BufferedImage region = image.getSubimage(left, top, width, height);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (ImageIO.write(region, "png", bytes)) {
            regions.add(ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG));
        }
    }

    private static void addColumn(BufferedImage image, int left, int right,
                                  List<ImageResult> columns) throws Exception {
        int width = right - left;
        if (width < Math.max(180, image.getWidth() / 7)) {
            return;
        }
        BufferedImage column = image.getSubimage(left, 0, width, image.getHeight());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        if (ImageIO.write(column, "png", bytes)) {
            columns.add(ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG));
        }
    }

    private record Gutter(int start, int end) {
        int width() {
            return end - start;
        }

        int center() {
            return start + width() / 2;
        }
    }

    private record LayoutSlab(int top, int bottom, List<Gutter> gutters) {
    }
}
