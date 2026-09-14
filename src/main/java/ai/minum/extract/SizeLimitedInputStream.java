package ai.minum.extract;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/** Enforces the same input-byte contract for every extractor without owning the caller's stream. */
final class SizeLimitedInputStream extends FilterInputStream {
    private final long limit;
    private long count;
    private long markedCount;

    SizeLimitedInputStream(InputStream input, long limit) {
        super(input);
        this.limit = limit;
    }

    @Override
    public int read() throws IOException {
        int value = super.read();
        if (value >= 0 && ++count > limit) {
            throw limitExceeded();
        }
        return value;
    }

    @Override
    public int read(byte[] bytes, int offset, int length) throws IOException {
        if (length == 0) {
            return 0;
        }
        long remaining = count < limit ? limit - count : 1;
        int allowed = (int) Math.min(length, Math.min((long) Integer.MAX_VALUE, remaining));
        int read = super.read(bytes, offset, Math.max(1, allowed));
        if (read >= 0 && (count += read) > limit) {
            throw limitExceeded();
        }
        return read;
    }

    void verifyExhausted() throws IOException {
        byte[] buffer = new byte[8192];
        while (read(buffer) >= 0) {
            // Read any parser remainder so the limit describes the whole input, not parser appetite.
        }
    }

    @Override
    public long skip(long length) throws IOException {
        if (length <= 0) {
            return 0;
        }
        long remaining = count < limit ? limit - count : 1;
        long allowed = Math.min(length, remaining);
        long skipped = super.skip(Math.max(1, allowed));
        if ((count += skipped) > limit) {
            throw limitExceeded();
        }
        return skipped;
    }

    @Override
    public synchronized void mark(int readLimit) {
        super.mark(readLimit);
        markedCount = count;
    }

    @Override
    public synchronized void reset() throws IOException {
        super.reset();
        count = markedCount;
    }

    @Override
    public void close() {
        // The public API leaves stream ownership with the caller.
    }

    private IOException limitExceeded() {
        return new IOException("File size limit exceeded: " + limit + " bytes");
    }
}
