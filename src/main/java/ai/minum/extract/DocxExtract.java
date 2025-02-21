package ai.minum.extract;

import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlObject;
import org.openxmlformats.schemas.drawingml.x2006.main.CTBlip;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTDrawing;

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
        // 遍历段落中的每个XWPFRun
        for (XWPFRun run : paragraph.getRuns()) {
            // 获取当前Run中的CTDrawing数组
            List<CTDrawing> drawings = run.getCTR().getDrawingList();
            for (CTDrawing drawing : drawings) {
                // 使用XmlCursor解析CTDrawing内容
                try (XmlCursor cursor = drawing.newCursor()) {
                    String embedId = null;

                    // 定义XPath查找a:blip元素
                    String xpath = "declare namespace a='http://schemas.openxmlformats.org/drawingml/2006/main' " +
                            "declare namespace r='http://schemas.openxmlformats.org/officeDocument/2006/relationships' " +
                            ".//a:blip";
                    cursor.selectPath(xpath);

                    // 遍历查询结果
                    while (cursor.toNextSelection()) {
                        XmlObject obj = cursor.getObject();
                        if (obj instanceof CTBlip blip) {
                            embedId = blip.getEmbed(); // 获取嵌入ID
                            System.out.println("embedId: " + embedId);
                            imageBundleList.add(embedId);
                            break;
                        }
                    }
                }
            }
        }

        return imageBundleList;
    }

}
