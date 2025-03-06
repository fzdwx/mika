package ai.minum.extract;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDResources;
import org.apache.pdfbox.pdmodel.graphics.PDXObject;
import org.apache.pdfbox.pdmodel.graphics.form.PDFormXObject;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.List;

public class PDFExtract implements Extractor {

    private final static String MIME_TYPE_1 = "application/pdf";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(MIME_TYPE_1);

    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        try (PDDocument doc = Loader.loadPDF(stream.readAllBytes())) {
            PDFTextStripper reader = new PDFTextStripper();
            reader.setSortByPosition(true);

            long index = 0L;
            int numberOfPages = doc.getNumberOfPages();
            for (int i = 0; i < numberOfPages; i++) {
                PDPage p = doc.getPage(i);
                reader.setStartPage(i + 1);
                reader.setEndPage(i + 1);
                PDResources resources = p.getResources();
                StringBuilder content = new StringBuilder(reader.getText(doc));
                if (resources != null) {
                    for (COSName name : resources.getXObjectNames()) {
                        PDXObject pdxObject = resources.getXObject(name);
                        if (pdxObject instanceof PDImageXObject img) {
                            result.setHasImage(true);
                            ImageResult imageResult = toImageResult(img);
                            String imageOcrResult = this.extractImage(config, imageResult);
                            content.append(imageOcrResult);
                        }

                        if (pdxObject instanceof PDFormXObject table) {
                            result.setHasTable(true);
                        }
                    }
                }

                result.addPage(index++, content.toString());
            }
        }
        return result;
    }
}
