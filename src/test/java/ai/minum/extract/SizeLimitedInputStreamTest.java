package ai.minum.extract;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SizeLimitedInputStreamTest {
    @Test
    void unlimitedSizeDoesNotOverflowBulkReadAllowance() throws Exception {
        byte[] source = new byte[16 * 1024];
        CountingInputStream input = new CountingInputStream(source);
        SizeLimitedInputStream limited = new SizeLimitedInputStream(input, Long.MAX_VALUE);

        assertArrayEquals(source, limited.readAllBytes());
        assertTrue(input.bulkReads < 10, "Long.MAX_VALUE must retain buffered reads");
    }

    private static final class CountingInputStream extends ByteArrayInputStream {
        private int bulkReads;

        private CountingInputStream(byte[] buffer) {
            super(buffer);
        }

        @Override
        public synchronized int read(byte[] bytes, int offset, int length) {
            bulkReads++;
            return super.read(bytes, offset, length);
        }
    }
}
