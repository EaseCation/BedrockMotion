package net.easecation.bedrockmotion.pack.content;

import com.google.gson.JsonObject;
import org.cube.converter.util.GsonUtil;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

// Taken from ViaBedrock!
public class Content {
    private final Map<String, byte[]> content;
    private final Map<String, Map<String, String>> langCache;

    public Content() {
        this(false);
    }

    public Content(final boolean concurrent) {
        if (concurrent) {
            this.content = new ConcurrentHashMap<>();
            this.langCache = new ConcurrentHashMap<>();
        } else {
            this.content = new HashMap<>();
            this.langCache = new HashMap<>();
        }
    }

    public Content(final byte[] zipData) throws IOException {
        this(false);

        final ZipInputStream zipInputStream = new ZipInputStream(new ByteArrayInputStream(zipData));
        ZipEntry zipEntry;
        int len;
        final byte[] buf = new byte[4096];
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        while ((zipEntry = zipInputStream.getNextEntry()) != null) {
            if (zipEntry.isDirectory()) continue;
            while ((len = zipInputStream.read(buf)) > 0) {
                baos.write(buf, 0, len);
            }
            this.content.put(zipEntry.getName(), baos.toByteArray());
            baos.reset();
        }
    }

    public List<String> getFilesShallow(final String path, final String extension) {
        return this.content.keySet().stream().filter(file -> file.startsWith(path) && !file.substring(path.length()).contains("/") && file.endsWith(extension)).collect(Collectors.toList());
    }

    public List<String> getFilesDeep(final String path, final String extension) {
        return this.content.keySet().stream().filter(file -> file.startsWith(path) && file.endsWith(extension)).collect(Collectors.toList());
    }

    public String getFullPath(final String shortNamePath, final String... extensions) {
        if (this.contains(shortNamePath)) {
            return shortNamePath;
        }
        for (final String extension : extensions) {
            final String path = shortNamePath + "." + extension;
            if (this.contains(path)) {
                return path;
            }
        }
        return null;
    }

    public boolean contains(final String path) {
        return this.content.containsKey(path);
    }

    public byte[] get(final String path) {
        return this.content.get(path);
    }

    public boolean put(final String path, final byte[] data) {
        return this.content.put(path, data) != null;
    }

    public String getString(final String path) {
        final byte[] bytes = this.get(path);
        if (bytes == null) {
            return null;
        }

        return new String(bytes, StandardCharsets.UTF_8);
    }

    public boolean putString(final String path, final String string) {
        return this.put(path, string.getBytes(StandardCharsets.UTF_8));
    }

    public List<String> getLines(final String path) {
        final String string = this.getString(path);
        if (string == null) {
            return null;
        }

        return List.of(string.split("\\n"));
    }

    public boolean putLines(final String path, final List<String> lines) {
        return this.putString(path, String.join("\\n", lines));
    }

    public Map<String, String> getLang(final String path) {
        return this.langCache.computeIfAbsent(path, k -> {
            final List<String> lines = this.getLines(k);
            return Collections.unmodifiableMap(lines.stream()
                    .filter(line -> !line.startsWith("##"))
                    .filter(line -> line.contains("="))
                    .map(line -> line.contains("##") ? line.substring(0, line.indexOf("##")) : line)
                    .map(String::trim)
                    .map(line -> line.split("=", 2))
                    .collect(Collectors.toMap(parts -> parts[0], parts -> parts[1], (o, n) -> n)));
        });
    }

    public JsonObject getJson(final String path) {
        final String string = this.getString(path);
        if (string == null) {
            return null;
        }

        return GsonUtil.getGson().fromJson(string.trim(), JsonObject.class);
    }

    public boolean putJson(final String path, final JsonObject json) {
        return this.putString(path, GsonUtil.getGson().toJson(json));
    }

    public LazyImage getShortnameImage(final String path) {
        return this.getImage(this.getFullPath(path, "png", "jpg"));
    }

    public LazyImage getImage(final String path) {
        final byte[] bytes = this.get(path);
        if (bytes == null) {
            return null;
        }

        final boolean isPng = bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == (byte) 0x50 && bytes[2] == (byte) 0x4E && bytes[3] == (byte) 0x47 && bytes[4] == (byte) 0x0D && bytes[5] == (byte) 0x0A && bytes[6] == (byte) 0x1A && bytes[7] == (byte) 0x0A;
        final boolean isJpg = bytes.length > 2 && bytes[0] == (byte) 0xFF && bytes[1] == (byte) 0xD8 && bytes[2] == (byte) 0xFF;
        if (!isPng && !isJpg) {
            return null;
        }

        return new LazyImage(bytes, isPng ? "png" : "jpg");
    }

    public boolean putPngImage(final String path, final LazyImage image) {
        return this.put(path, image.getPngBytes());
    }

    public boolean putPngImage(final String path, final BufferedImage image) {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, "png", baos);
        } catch (final IOException e) {
            throw new RuntimeException(e);
        }
        return this.put(path, baos.toByteArray());
    }

    public void copyFrom(final Content content, final String sourcePath, final String targetPath) {
        this.put(targetPath, content.get(sourcePath));
    }

    public byte[] toZip() throws IOException {
        final ByteArrayOutputStream baos = new ByteArrayOutputStream(1024 * 1024 * 4);
        final ZipOutputStream zipOutputStream = new ZipOutputStream(baos);
        for (final Map.Entry<String, byte[]> entry : this.content.entrySet()) {
            zipOutputStream.putNextEntry(new ZipEntry(entry.getKey()));
            zipOutputStream.write(entry.getValue());
            zipOutputStream.closeEntry();
        }
        zipOutputStream.close();
        return baos.toByteArray();
    }

    public int size() {
        return this.content.size();
    }

    public static class LazyImage {
        private final byte[] bytes;
        private final String format;
        private BufferedImage image;

        public LazyImage(final byte[] bytes, final String format) {
            this.bytes = bytes;
            this.format = format;
        }

        public BufferedImage getImage() {
            if (this.image == null) {
                try {
                    this.image = ImageIO.read(new ByteArrayInputStream(this.bytes));
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
            }
            return this.image;
        }

        public byte[] getPngBytes() {
            return this.getPngBytes(false);
        }

        public byte[] getPngBytes(final boolean forceWrite) {
            if (this.format.equals("png") && !forceWrite) {
                return this.bytes;
            } else {
                final BufferedImage image = this.getImage();
                final ByteArrayOutputStream baos = new ByteArrayOutputStream();
                try {
                    ImageIO.write(image, "png", baos);
                } catch (final IOException e) {
                    throw new RuntimeException(e);
                }
                return baos.toByteArray();
            }
        }
    }
}
