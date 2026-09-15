package ai.minum.extract;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/** Applies bounded DOCX compatibility rewrites before Tika and POI parse the package. */
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
    // Tika 4 eagerly parses EMF object icons only to recover a display name. Mika processes images
    // in its own bounded second pass, so give those parts a private type and identify them by their
    // extension. This avoids malformed vector previews aborting otherwise readable DOCX files.
    private static final byte[] TIKA_EMF_TYPE = "image/x-emf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] MIKA_EMF_TYPE = "application/x-mika-emf".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] STRICT_WORD_NAMESPACE =
            "http://purl.oclc.org/ooxml/wordprocessingml/main".getBytes(StandardCharsets.US_ASCII);
    private static final byte[][] STRICT_NAMESPACES = {
            bytes("http://purl.oclc.org/ooxml/wordprocessingml/main"),
            bytes("http://purl.oclc.org/ooxml/officeDocument/relationships"),
            bytes("http://purl.oclc.org/ooxml/officeDocument/math"),
            bytes("http://purl.oclc.org/ooxml/drawingml/main"),
            bytes("http://purl.oclc.org/ooxml/drawingml/chart"),
            bytes("http://purl.oclc.org/ooxml/drawingml/diagram"),
            bytes("http://purl.oclc.org/ooxml/drawingml/wordprocessingDrawing"),
            bytes("http://purl.oclc.org/ooxml/drawingml/picture"),
            bytes("http://purl.oclc.org/ooxml/spreadsheetml/main")
    };
    private static final byte[][] TRANSITIONAL_NAMESPACES = {
            bytes("http://schemas.openxmlformats.org/wordprocessingml/2006/main"),
            bytes("http://schemas.openxmlformats.org/officeDocument/2006/relationships"),
            bytes("http://schemas.openxmlformats.org/officeDocument/2006/math"),
            bytes("http://schemas.openxmlformats.org/drawingml/2006/main"),
            bytes("http://schemas.openxmlformats.org/drawingml/2006/chart"),
            bytes("http://schemas.openxmlformats.org/drawingml/2006/diagram"),
            bytes("http://schemas.openxmlformats.org/drawingml/2006/wordprocessingDrawing"),
            bytes("http://schemas.openxmlformats.org/drawingml/2006/picture"),
            bytes("http://schemas.openxmlformats.org/spreadsheetml/2006/main")
    };

    private DocxPackageRepair() {
    }

    static Repair repair(byte[] source) throws IOException {
        source = normalizeStrictNamespaces(source);
        // [Content_Types].xml is normally the first ZIP entry. Detect it before applying repair
        // limits so every valid DOCX keeps Tika/POI's established compatibility and avoids a
        // second full decompression pass.
        if (hasContentTypesPart(source)) {
            return new Repair(rewriteEmfContentType(source), false);
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

    private static byte[] normalizeStrictNamespaces(byte[] source) throws IOException {
        if (!hasStrictWordNamespace(source)) {
            return source;
        }
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream(source.length + 4096);
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(rewritten)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("DOCX package contains duplicate entry: " + entry.getName());
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getTime() >= 0) {
                    copy.setTime(entry.getTime());
                }
                output.putNextEntry(copy);
                if (entry.getName().endsWith(".xml") || entry.getName().endsWith(".rels")) {
                    byte[] bytes = archive.readNBytes((int) MAX_EXPANDED_BYTES + 1);
                    if ((long) bytes.length + expanded > MAX_EXPANDED_BYTES) {
                        throw new IOException("DOCX expanded package size limit exceeded");
                    }
                    expanded += bytes.length;
                    for (int index = 0; index < STRICT_NAMESPACES.length; index++) {
                        bytes = replace(bytes, STRICT_NAMESPACES[index], TRANSITIONAL_NAMESPACES[index]);
                    }
                    output.write(bytes);
                } else {
                    expanded = copyBounded(archive, output, expanded);
                }
                output.closeEntry();
            }
        }
        return rewritten.toByteArray();
    }

    private static boolean hasStrictWordNamespace(byte[] source) throws IOException {
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("DOCX package contains duplicate entry: " + entry.getName());
                }
                if ("word/document.xml".equals(entry.getName())) {
                    ByteArrayOutputStream document = new ByteArrayOutputStream();
                    copyBounded(archive, document, expanded);
                    return indexOf(document.toByteArray(), STRICT_WORD_NAMESPACE) >= 0;
                }
                expanded = copyBounded(archive, null, expanded);
            }
        }
        return false;
    }

    private static byte[] rewriteEmfContentType(byte[] source) throws IOException {
        if (!hasEmfContentType(source)) {
            return source;
        }
        ByteArrayOutputStream rewritten = new ByteArrayOutputStream(source.length + 64);
        boolean changed = false;
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source));
             ZipOutputStream output = new ZipOutputStream(rewritten)) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("DOCX package contains duplicate entry: " + entry.getName());
                }
                ZipEntry copy = new ZipEntry(entry.getName());
                if (entry.getTime() >= 0) {
                    copy.setTime(entry.getTime());
                }
                output.putNextEntry(copy);
                if ("[Content_Types].xml".equals(entry.getName())) {
                    byte[] bytes = archive.readNBytes((int) MAX_EXPANDED_BYTES + 1);
                    if (bytes.length > MAX_EXPANDED_BYTES) {
                        throw new IOException("DOCX expanded package size limit exceeded");
                    }
                    byte[] safe = replace(bytes, TIKA_EMF_TYPE, MIKA_EMF_TYPE);
                    changed |= safe != bytes;
                    output.write(safe);
                    expanded += bytes.length;
                } else {
                    expanded = copyBounded(archive, output, expanded);
                }
                output.closeEntry();
            }
        }
        return changed ? rewritten.toByteArray() : source;
    }

    private static boolean hasEmfContentType(byte[] source) throws IOException {
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("DOCX package contains duplicate entry: " + entry.getName());
                }
                if ("[Content_Types].xml".equals(entry.getName())) {
                    ByteArrayOutputStream contentTypes = new ByteArrayOutputStream();
                    copyBounded(archive, contentTypes, expanded);
                    return indexOf(contentTypes.toByteArray(), TIKA_EMF_TYPE) >= 0;
                }
                expanded = copyBounded(archive, null, expanded);
            }
        }
        return false;
    }

    private static byte[] replace(byte[] source, byte[] expected, byte[] replacement) {
        int match = indexOf(source, expected);
        if (match < 0) {
            return source;
        }
        ByteArrayOutputStream result = new ByteArrayOutputStream(
                source.length + replacement.length - expected.length);
        int copied = 0;
        while (match >= 0) {
            result.write(source, copied, match - copied);
            result.writeBytes(replacement);
            copied = match + expected.length;
            match = indexOf(source, expected, copied);
        }
        result.write(source, copied, source.length - copied);
        return result.toByteArray();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    private static int indexOf(byte[] source, byte[] expected) {
        return indexOf(source, expected, 0);
    }

    private static int indexOf(byte[] source, byte[] expected, int start) {
        outer:
        for (int index = Math.max(0, start); index <= source.length - expected.length; index++) {
            for (int offset = 0; offset < expected.length; offset++) {
                if (source[index + offset] != expected[offset]) {
                    continue outer;
                }
            }
            return index;
        }
        return -1;
    }

    private static boolean hasContentTypesPart(byte[] source) throws IOException {
        int entries = 0;
        long expanded = 0;
        Set<String> names = new HashSet<>();
        try (ZipInputStream archive = new ZipInputStream(new ByteArrayInputStream(source))) {
            ZipEntry entry;
            while ((entry = archive.getNextEntry()) != null) {
                if (++entries > MAX_ENTRIES) {
                    throw new IOException("DOCX package has too many entries");
                }
                if (!names.add(entry.getName())) {
                    throw new IOException("DOCX package contains duplicate entry: " + entry.getName());
                }
                if ("[Content_Types].xml".equals(entry.getName())) {
                    return true;
                }
                expanded = copyBounded(archive, null, expanded);
            }
        }
        return false;
    }

    private static long copyBounded(ZipInputStream input, OutputStream output, long expanded)
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
