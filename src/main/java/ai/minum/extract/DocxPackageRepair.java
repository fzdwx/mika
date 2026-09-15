package ai.minum.extract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Applies one bounded repair that Office itself commonly performs: a missing content-types part. */
final class DocxPackageRepair {
    private static final int MAX_ENTRIES = 10_000;
    private static final long MAX_EXPANDED_BYTES = 256L * 1024 * 1024;
    private static final String CONTENT_TYPES = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
              <Default Extension="xml" ContentType="application/xml"/>
              <Default Extension="png" ContentType="image/png"/>
              <Default Extension="jpg" ContentType="image/jpeg"/>
              <Default Extension="jpeg" ContentType="image/jpeg"/>
              <Default Extension="gif" ContentType="image/gif"/>
              <Default Extension="bmp" ContentType="image/bmp"/>
              <Default Extension="tif" ContentType="image/tiff"/>
              <Default Extension="tiff" ContentType="image/tiff"/>
              <Default Extension="wmf" ContentType="image/x-wmf"/>
              <Default Extension="emf" ContentType="image/x-emf"/>
              <Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>
            </Types>
            """;

    private DocxPackageRepair() {
    }

    static Repair repair(byte[] source) throws IOException {
        // [Content_Types].xml is normally the first ZIP entry. Detect it before applying repair
        // limits so every valid DOCX keeps Tika/POI's established compatibility and avoids a
        // second full decompression pass.
        if (hasContentTypesPart(source)) {
            return new Repair(source, false);
        }

        Set<String> names = new HashSet<>();
        boolean hasDocument = false;
        int entries = 0;
        long expanded = 0;
        ByteArrayOutputStream repaired = new ByteArrayOutputStream(source.length + CONTENT_TYPES.length());
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(repaired)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                String name = entry.getName();
                if (!names.add(name)) {
                    throw new IOException("DOCX package contains duplicate entry: " + name);
                }
                hasDocument |= "word/document.xml".equals(name);
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getTime() >= 0) {
                    copy.setTime(entry.getTime());
                }
                output.putNextEntry(copy);
                expanded = copyBounded(archive, output, expanded);
                output.closeEntry();
            }
            if (!hasDocument) {
                return new Repair(source, false);
            }
            output.putNextEntry(new ZipEntry("[Content_Types].xml"));
            output.write(CONTENT_TYPES.getBytes(StandardCharsets.UTF_8));
            output.closeEntry();
        }
        return new Repair(repaired.toByteArray(), true);
    }

    private static boolean hasContentTypesPart(byte[] source) throws IOException {
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if ("[Content_Types].xml".equals(entry.getName())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static long copyBounded(ZipInputStream input, ZipOutputStream output, long expanded)
            throws IOException {
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) {
            expanded += count;
            if (expanded > MAX_EXPANDED_BYTES) {
                throw new IOException("DOCX expanded package size limit exceeded");
            }
            if (output != null) {
                output.write(buffer, 0, count);
            }
        }
        return expanded;
    }

    record Repair(byte[] bytes, boolean repaired) {
    }
}
