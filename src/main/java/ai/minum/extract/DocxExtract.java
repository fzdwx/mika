package ai.minum.extract;

import com.microsoft.schemas.vml.CTShape;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTGraphicalObject;
import org.openxmlformats.schemas.drawingml.x2006.picture.CTPicture;
import org.openxmlformats.schemas.drawingml.x2006.wordprocessingDrawing.CTInline;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTObject;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTR;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

public class DocxExtract implements Extractor {

    private final static String MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(MIME_TYPE);

    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        XWPFDocument docx = new XWPFDocument(stream);
        ExtractResult result = ExtractResult.of();

        long index = 0L;
        for (IBodyElement bodyElement : docx.getBodyElements()) {
            if (bodyElement instanceof XWPFParagraph paragraph) {
                String content = paragraph.getText().strip();
                List<String> images = readImageInP(paragraph);
                if (!images.isEmpty()) {
                    result.setHasImage(true);
                }
                if (config.extractImage()) {
                    List<String> imageContentList = doOcr(config, docx, images);
                    content = content.concat(String.join("\n", imageContentList));
                }
                if (!content.isEmpty()) {
                    result.addPage(index++, content);
                }
            }
            if (bodyElement instanceof XWPFTable table) {
                result.setHasTable(true);
                String content = table.getText();
                if (!content.isEmpty()) {
                    result.addPage(index++, content);
                }
            }
        }

        return result;
    }

    private List<String> doOcr(ExtractConfig config, XWPFDocument docx, List<String> images) {
        List<String> result = new ArrayList<>();
        for (String imageId : images) {
            XWPFPictureData pic = docx.getPictureDataByID(imageId);
            byte[] pictureData = pic.getData();
            if (pictureData.length < config.imageExtractMaxSize()) {
                String imageContent = config.ocr().doOrc(pictureData);
                if (imageContent != null && !imageContent.isEmpty()) {
                    result.add(imageContent);
                }
            }
        }

        return result;
    }

    static List<String> readImageInP(XWPFParagraph paragraph) {
        // 图片索引List
        List<String> imageBundleList = new ArrayList<>();
        // 段落中所有XWPFRun
        List<XWPFRun> runList = paragraph.getRuns();
        for (XWPFRun run : runList) {
            CTR ctr = run.getCTR();
            try (XmlCursor c = ctr.newCursor();) {
                c.selectPath("./*");
                while (c.toNextSelection()) {
                    XmlObject o = c.getObject();
                    if (o instanceof CTDrawing drawing) {
                        CTInline[] ctInlines = drawing.getInlineArray();
                        for (CTInline ctInline : ctInlines) {
                            CTGraphicalObject graphic = ctInline.getGraphic();
                            try (XmlCursor cursor = graphic.getGraphicData().newCursor();) {
                                cursor.selectPath("./*");
                                while (cursor.toNextSelection()) {
                                    XmlObject xmlObject = cursor.getObject();
                                    if (xmlObject instanceof CTPicture picture) {
                                        imageBundleList.add(picture.getBlipFill().getBlip().getEmbed());
                                    }
                                }
                            }
                        }
                    }

                    if (o instanceof CTObject object) {
                        System.out.println(object);
                        try (XmlCursor w = object.newCursor();) {
                            w.selectPath("./*");
                            while (w.toNextSelection()) {
                                XmlObject xmlObject = w.getObject();
                                if (xmlObject instanceof CTShape shape) {
                                    imageBundleList.add(shape.getImagedataArray()[0].getId2());
                                }
                            }
                        }
                    }
                }
            }
        }
        return imageBundleList;
    }

}
