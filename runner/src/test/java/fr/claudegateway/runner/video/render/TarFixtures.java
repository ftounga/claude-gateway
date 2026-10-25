package fr.claudegateway.runner.video.render;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPOutputStream;

/** Fabrique de petits {@code .tar.gz} pour les tests (ustar, chemins longs en PAX, liens à la demande). */
final class TarFixtures {

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    private record Entry(char type, byte[] content, int mode, String link) {
    }

    TarFixtures file(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8), 0644);
    }

    TarFixtures executable(String name, String content) {
        return file(name, content.getBytes(StandardCharsets.UTF_8), 0755);
    }

    TarFixtures file(String name, byte[] content, int mode) {
        entries.put(name, new Entry('0', content, mode, ""));
        return this;
    }

    TarFixtures dir(String name) {
        entries.put(name, new Entry('5', new byte[0], 0755, ""));
        return this;
    }

    TarFixtures symlink(String name, String target) {
        entries.put(name, new Entry('2', new byte[0], 0777, target));
        return this;
    }

    /** Le paquet complet attendu par le runner, pour une plateforme Linux. */
    static TarFixtures linuxBundle(String version) {
        return new TarFixtures()
                .file("bundle.json", "{\"kind\":\"video-toolchain\",\"version\":\"" + version
                        + "\",\"platform\":\"linux-x64\"}")
                .executable("node/bin/node", "#!/bin/sh\necho v22.22.1\n")
                .file("node/LICENSE", "MIT")
                .executable("hyperframes/node_modules/hyperframes/bin/hyperframes.mjs", "// hyperframes")
                .file("hyperframes/node_modules/hyperframes/package.json", "{\"version\":\"0.8.193\"}")
                .file("hyperframes/node_modules/gsap/dist/gsap.min.js", "/* GSAP 3.13.0 */")
                .executable("ffmpeg/ffmpeg", "#!/bin/sh\necho ffmpeg version 6.1.1\n")
                .executable("ffmpeg/ffprobe", "#!/bin/sh\necho ffprobe version 6.1.1\n");
    }

    byte[] tarGz() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(bytes)) {
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                String name = e.getKey();
                Entry entry = e.getValue();
                if (name.getBytes(StandardCharsets.UTF_8).length > 100) {
                    byte[] record = pax("path", name);
                    gz.write(header("PaxHeaders/long", 'x', record.length, 0644, ""));
                    gz.write(record);
                    gz.write(new byte[pad(record.length)]);
                    name = name.substring(name.length() - 90);
                }
                gz.write(header(name, entry.type(), entry.content().length, entry.mode(), entry.link()));
                gz.write(entry.content());
                gz.write(new byte[pad(entry.content().length)]);
            }
            gz.write(new byte[1024]);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        return bytes.toByteArray();
    }

    private static byte[] pax(String key, String value) {
        String body = " " + key + "=" + value + "\n";
        int length = body.getBytes(StandardCharsets.UTF_8).length;
        int total = length + String.valueOf(length).length();
        if (String.valueOf(total).length() != String.valueOf(length).length()) {
            total++;
        }
        return (total + body).getBytes(StandardCharsets.UTF_8);
    }

    private static int pad(int size) {
        return (512 - size % 512) % 512;
    }

    private static byte[] header(String name, char type, long size, int mode, String link) {
        byte[] h = new byte[512];
        put(h, 0, 100, name);
        put(h, 100, 8, String.format("%07o", mode));
        put(h, 108, 8, "0000000");
        put(h, 116, 8, "0000000");
        put(h, 124, 12, String.format("%011o", size));
        put(h, 136, 12, String.format("%011o", 0));
        for (int i = 148; i < 156; i++) {
            h[i] = ' ';
        }
        h[156] = (byte) type;
        put(h, 157, 100, link);
        put(h, 257, 6, "ustar");
        put(h, 263, 2, "00");
        long sum = 0;
        for (byte b : h) {
            sum += b & 0xff;
        }
        put(h, 148, 8, String.format("%06o", sum) + "\0 ");
        return h;
    }

    private static void put(byte[] h, int offset, int length, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, h, offset, Math.min(length, bytes.length));
    }
}
