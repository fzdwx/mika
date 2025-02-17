package ai.minum.extract;

import java.io.InputStream;

public interface Extractor {

    boolean support(String mimeType);

    ExtractResult doExtract(ExtractConfig config, InputStream stream) throws Exception;


    default ExtractResult extract(ExtractConfig config, InputStream stream) {
        try {
            return doExtract(config, stream);
        } catch (Exception e) {
            return ExtractResult.error(e);
        } finally {
            close(stream);
        }
    }

    default void close(InputStream stream) {
        try {
            stream.close();
        } catch (Exception ignore) {
        }
    }
}
