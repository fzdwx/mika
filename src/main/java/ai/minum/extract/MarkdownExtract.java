package ai.minum.extract;

import java.io.InputStream;

public class MarkdownExtract implements Extractor {
    @Override
    public boolean support(String mimeType) {
        return mimeType.equals("text/markdown") || mimeType.equals("text/x-markdown") || mimeType.equals("markdown");
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        byte[] bytes = stream.readAllBytes();
        String text = new String(bytes);
        result.addPage(0L, text);
        return result;
    }
}
