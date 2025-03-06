package ai.minum.extract;

import org.apache.poi.hwpf.HWPFDocument;
import org.apache.poi.hwpf.model.PicturesTable;
import org.apache.poi.hwpf.usermodel.CharacterRun;
import org.apache.poi.hwpf.usermodel.Paragraph;
import org.apache.poi.hwpf.usermodel.Picture;
import org.apache.poi.hwpf.usermodel.Range;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.regex.Matcher;
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
        Range range = doc.getRange();
        int numP = range.numParagraphs();
        List<Paragraph> paras = new ArrayList<>();
        for (int i = 0; i < numP; i++) {
            paras.add(range.getParagraph(i));
        }
        Iterator<Paragraph> it = paras.iterator();
        PicturesTable pictures = doc.getPicturesTable();
        long index = 0L;
        while (it.hasNext()) {
            Paragraph para = it.next();
            String content = para.text();
            if (para.isInTable()) {
                StringBuilder sb = new StringBuilder();
                sb.append(removeFormText(content));
                if (content.charAt(content.length() - 1) != '\r') {
                    sb.append("\t");
                } else {
                    sb.append(" ");
                }
                while (it.hasNext()) {
                    para = it.next();
                    content = para.text();
                    if (!para.isInTable()) {
                        if (!sb.isEmpty()) {
                            sb.setLength(sb.length() - 2);
                        }
                        break;
                    }
                    sb.append(removeFormText(content));
                    if (para.isTableRowEnd()) {
                        if (!sb.isEmpty()) {
                            sb.setLength(sb.length() - 1);
                        }
                        sb.append("\n");
                    } else {
                        if (content.charAt(content.length() - 1) != '\r') {
                            sb.append("\t");
                        } else {
                            sb.append(" ");
                        }
                    }
                }
                result.setHasTable(true);
                result.addPage(index++, sb.toString());
                if (para.isInTable()) {
                    continue;
                }
            }
            content = pattern.matcher(content).replaceAll("").strip();
            content = controlPattern.matcher(content).replaceAll(" ").strip();
            for (int j = 0; j < para.numCharacterRuns(); j++) {
                CharacterRun run = para.getCharacterRun(j);
                if (pictures.hasPicture(run)) {
                    result.setHasImage(true);
                    if (!config.ocr()) {
                        continue;
                    }
                    Picture pic = pictures.extractPicture(run, true);
                    String imageOcrContent = this.extractImage(config, toImageResult(pic));
                    content = content.concat(imageOcrContent);
                }
            }
            if (!content.isEmpty()) {
                result.addPage(index++, content);
            }
        }
        return result;
    }

    private static String removeFormText(String text) {
        return formtextPattern.matcher(text).replaceAll("")
                .replaceAll("\t", " ")
                .replace("\n", " ")
                .strip();
    }

    boolean doMatch(String s, Pattern pattern) {
        Matcher matcher = pattern.matcher(s);
        return matcher.find();
    }
}

