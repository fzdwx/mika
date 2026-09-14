package ai.minum.extract;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

public class MarkdownExtract implements Extractor {
    @Override
    public boolean support(String mimeType) {
        return mimeType.equals("text/markdown") || mimeType.equals("text/x-markdown") || mimeType.equals("markdown");
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        ExtractResult result = ExtractResult.of();
        byte[] bytes = stream.readAllBytes();
        String text = new String(bytes, StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) {
            text = text.substring(1);
        }
        result.addPage(0L, text);
        return result;
    }
}
