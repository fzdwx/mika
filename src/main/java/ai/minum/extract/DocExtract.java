package ai.minum.extract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;

import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

public class DocExtract implements Extractor {

    private final static String MIME_TYPE_1 = "application/x-tika-msoffice";
    private final static List<String> SUPPORTED_MIME_TYPES = List.of(MIME_TYPE_1);

    // 图片
    static String regexPicture = "\\u0001";

    // 公式
    static String regexFormula = "\\u0013 EMBED Equation.KSEE3( .*)? \\u0014\\u0001\\u0015";

    // 超链接
    static String regexHyper = "\\u0013 HYPERLINK( [\\\\\\w]*)? \".*\" \\u0014";

    // 表格
    static String regexFormtext = "\\u0013FORMTEXT\\u0001\\u0014.*\\u0015.*|\\u0007";

    // Control Code
    static String regexControl = "[\\u0000-\\u001f]";

    static Pattern pattern = Pattern
            .compile(regexPicture + "|" + regexFormula + "|" + regexHyper + "|" + regexFormtext);
    static Pattern hyperPattern = Pattern.compile(regexHyper);
    static Pattern formtextPattern = Pattern.compile(regexFormtext);
    static Pattern controlPattern = Pattern.compile(regexControl);

    @Override
    public boolean support(String mimeType) {
        return SUPPORTED_MIME_TYPES.contains(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        HWPFDocument doc = new HWPFDocument(stream);
        ExtractResult result = ExtractResult.of();
        PicturesTable pictures = doc.getPicturesTable();
        Range range = doc.getRange();
        int numP = range.numParagraphs();

        long index = 0L;
        for (int i = 0; i < numP; i++) {
            Paragraph p = range.getParagraph(i);
            if (p.isInTable()) {
                result.setHasTable(true);
            }

            String content = cleanText(p.text());
            for (int j = 0; j < p.numCharacterRuns(); j++) {
                CharacterRun run = p.getCharacterRun(j);
                if (!pictures.hasPicture(run) || !config.ocr()) {
                    result.addPage(index++, content);
                    continue;
                }
                result.setHasImage(true);
                Picture pic = pictures.extractPicture(run, true);
                byte[] pictureData = pic.getContent();
                if (pictureData.length < config.imageExtractMaxSize()) {
                    String imageContent = config.getOcr().doOrc(pictureData);
                    if (imageContent != null && !imageContent.isEmpty()) {
                        content = content.concat(imageContent);
                    }
                }
                result.addPage(index++, content);
            }
        }
        return result;
    }

    private static String cleanText(String content) {
        content = removeFormText(content);
        content = pattern.matcher(content).replaceAll("").strip();
        content = controlPattern.matcher(content).replaceAll(" ").strip();
        return content;
    }

    private static String removeFormText(String text) {
        return formtextPattern.matcher(text).replaceAll("")
                .replaceAll("\t", " ")
                .replace("\n", " ")
                .strip();
    }
}

