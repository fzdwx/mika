package ai.minum.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.contentstream.PDFGraphicsStreamEngine;
import org.apache.pdfbox.cos.COSBase;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImage;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.geom.Point2D;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

public class PDFExtract implements Extractor {
    private static final Logger logger = LoggerFactory.getLogger(PDFExtract.class);

    @Override
    public boolean support(String mimeType) {
        return "pdf".equals(mimeType) || "application/pdf".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        try (PDDocument doc = Loader.loadPDF(stream.readAllBytes())) {
            PDFTextStripper reader = new PDFTextStripper();
            reader.setSortByPosition(true);
            for (int i = 0; i < doc.getNumberOfPages(); i++) {
                reader.setStartPage(i + 1);
                reader.setEndPage(i + 1);
                StringBuilder content = new StringBuilder(Markdown.fromText(reader.getText(doc)));
                PageImages images = new PageImages(doc.getPage(i));
                images.processPage(doc.getPage(i));
                result.setHasImage(result.hasImage() || !images.images.isEmpty());
                for (PDImage image : images.images) {
                    if (!config.ocr() && !config.uploadImage()) {
                        continue;
                    }
                    if (!config.canHandleImage()) {
                        result.addWarning("Image count limit reached; some images were skipped");
                        continue;
                    }
                    ImageResult imageResult;
                    try {
                        if (image instanceof PDImageXObject xObject) {
                            imageResult = toImageResult(xObject);
                        } else {
                            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                            ImageIO.write(image.getImage(), "png", bytes);
                            imageResult = ImageResult.of(bytes.toByteArray(), ImageResult.Format.PNG);
                        }
                    } catch (IOException | RuntimeException e) {
                        logger.warn("Skip undecodable PDF image: page={}", i + 1, e);
                        result.addWarning("Cannot decode image on page " + (i + 1));
                        continue;
                    }
                    // Backend failures remain errors, so callers can retry OCR/upload.
                    String imageContent = extractImage(config, imageResult, result);
                    if (!imageContent.isBlank()) {
                        if (!content.isEmpty()) {
                            content.append("\n\n");
                        }
                        content.append(imageContent);
                    }
                }
                result.addPage((long) i, content.toString());
            }
        }
        return result;
    }

    /** Follows painted Form XObjects and inline images, including nested forms. */
    private static final class PageImages extends PDFGraphicsStreamEngine {
        private final List<PDImage> images = new ArrayList<>();
        private final Set<COSBase> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        private Point2D currentPoint = new Point2D.Float();

        private PageImages(PDPage page) {
            super(page);
        }

        @Override
        public void drawImage(PDImage image) {
            if (seen.add(image.getCOSObject())) {
                images.add(image);
            }
        }

        @Override
        public void appendRectangle(Point2D p0, Point2D p1, Point2D p2, Point2D p3) {
            currentPoint = p0;
        }

        @Override
        public void moveTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void lineTo(float x, float y) {
            currentPoint = new Point2D.Float(x, y);
        }

        @Override
        public void curveTo(float x1, float y1, float x2, float y2, float x3, float y3) {
            currentPoint = new Point2D.Float(x3, y3);
        }

        @Override
        public Point2D getCurrentPoint() {
            return currentPoint;
        }

        @Override public void clip(int windingRule) {}
        @Override public void closePath() {}
        @Override public void endPath() {}
        @Override public void strokePath() {}
        @Override public void fillPath(int windingRule) {}
        @Override public void fillAndStrokePath(int windingRule) {}
        @Override public void shadingFill(COSName shadingName) {}
    }
}
