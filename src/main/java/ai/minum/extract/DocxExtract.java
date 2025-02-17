package ai.minum.extract;

import org.apache.poi.xwpf.usermodel.IBodyElement;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.InputStream;
import java.util.List;

public class DocxExtract implements Extractor {

    private final static String MIME_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(MIME_TYPE);

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        XWPFDocument docx = new XWPFDocument(stream);
        List<IBodyElement> bodyElements = docx.getBodyElements();
        for (IBodyElement element : bodyElements) {
            System.out.println(element);
        }

        return ExtractResult.successOfOne("hhh");
    }

    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }
}
