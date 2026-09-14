package ai.minum.extract;

import org.apache.tika.metadata.Metadata;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

import java.io.BufferedInputStream;
import java.io.InputStream;

/** Uses Tika's Word structure instead of flattening table cells and field contents. */
public class DocExtract extends TikaExtractor {
    @Override
    public boolean support(String mimeType) {
        return "doc".equals(mimeType)
                || "dot".equals(mimeType)
                || "application/msword".equals(mimeType)
                || "application/vnd.ms-word".equals(mimeType)
                || "application/vnd.ms-word.template".equals(mimeType)
                || "application/x-dot".equals(mimeType);
    }

    @Override
    public ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception {
        InputStream checkedStream = stream.markSupported() ? stream : new BufferedInputStream(stream);
        checkedStream.mark(4);
        byte[] signature = checkedStream.readNBytes(4);
        checkedStream.reset();
        if (Word2Extract.supports(signature)) {
            return Word2Extract.extract(checkedStream);
        }
        return super.doExtract(config, checkedStream);
    }

    @Override
    protected void cleanDocument(Document document, Metadata metadata) {
        // HWPF comments start with Word's 0x05 annotation marker. Tika's XML serializer turns
        // that illegal XML control into U+FFFD; it is not part of the author's comment text.
        for (var paragraph : document.select("p")) {
            for (Node child : paragraph.childNodes()) {
                if (!(child instanceof TextNode text)) {
                    break;
                }
                String value = text.getWholeText();
                if (value.isBlank()) {
                    continue;
                }
                int marker = 0;
                while (marker < value.length() && Character.isWhitespace(value.charAt(marker))) {
                    marker++;
                }
                if (marker < value.length() && value.charAt(marker) == '\uFFFD') {
                    text.text(value.substring(0, marker) + value.substring(marker + 1));
                }
                break;
            }
        }
    }
}
