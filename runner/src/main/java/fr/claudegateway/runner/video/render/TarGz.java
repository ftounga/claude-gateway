package fr.claudegateway.runner.video.render;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.GZIPInputStream;

/**
 * <b>Ouvrir un paquet {@code .tar.gz} en Java</b> (F-215 / SF-215-06) : le même code sur macOS, Linux et Windows,
 * sans dépendre d'un {@code tar} du système.
 *
 * <p>Sait lire ce que {@code tools/video-toolchain/build-bundles.sh} produit (GNU tar, format PAX) : en-têtes
 * ustar avec préfixe, chemins longs PAX ({@code path}) et GNU ({@code ././@LongLink}), fichiers et dossiers. Refuse
 * le reste — <b>liens</b> symboliques ou physiques compris (un paquet n'en contient pas, et un lien est le moyen
 * classique d'écrire hors du dossier) — et toute entrée qui sortirait du dossier de destination.</p>
 *
 * <p>Le bit d'exécution d'une entrée est reporté sur le fichier (sans effet sous Windows).</p>
 */
final class TarGz {

    private static final int BLOCK = 512;

    private TarGz() {
    }

    /**
     * Extrait {@code archive} dans {@code into} (créé s'il le faut).
     *
     * @return le nombre d'octets de fichiers écrits
     * @throws IOException archive illisible, entrée refusée, disque plein
     */
    static long extract(Path archive, Path into) throws IOException {
        Path root = into.toAbsolutePath().normalize();
        Files.createDirectories(root);
        long written = 0;
        try (InputStream raw = new GZIPInputStream(new BufferedInputStream(Files.newInputStream(archive)), 65_536)) {
            byte[] header = new byte[BLOCK];
            String pendingName = null;
            while (true) {
                if (!readBlock(raw, header)) {
                    return written; // fin sans bloc de garde : toléré
                }
                if (allZero(header)) {
                    return written;
                }
                if (!checksumValid(header)) {
                    throw new IOException("en-tête tar corrompu");
                }
                char type = (char) (header[156] == 0 ? '0' : header[156]);
                long size = octal(header, 124, 12);
                if (size < 0) {
                    throw new IOException("taille d'entrée illisible");
                }
                String name = pendingName != null ? pendingName : headerName(header);
                pendingName = null;
                switch (type) {
                    case 'x' -> pendingName = paxPath(readBytes(raw, size));
                    case 'L' -> pendingName = cString(readBytes(raw, size));
                    case 'g' -> skip(raw, padded(size)); // en-tête PAX global : rien d'utile ici
                    case '5' -> {
                        Files.createDirectories(target(root, name));
                        skip(raw, padded(size));
                    }
                    case '0', '7' -> {
                        Path target = target(root, name);
                        Files.createDirectories(target.getParent());
                        try (OutputStream out = Files.newOutputStream(target)) {
                            copy(raw, out, size);
                        }
                        skip(raw, padded(size) - size);
                        long mode = octal(header, 100, 8);
                        if (mode > 0 && (mode & 0111) != 0) {
                            target.toFile().setExecutable(true, false);
                        }
                        written += size;
                    }
                    case '1', '2' -> throw new IOException("lien refusé dans le paquet : " + name);
                    default -> throw new IOException("type d'entrée tar non pris en charge (" + type + ") : " + name);
                }
            }
        }
    }

    /** La destination d'une entrée, jamais hors du dossier (ni chemin absolu, ni {@code ..}). */
    static Path target(Path root, String name) throws IOException {
        String clean = name;
        while (clean.startsWith("./")) {
            clean = clean.substring(2);
        }
        if (clean.isEmpty() || clean.startsWith("/") || clean.contains("\\") || clean.matches("^[A-Za-z]:.*")) {
            throw new IOException("entrée d'archive refusée : " + name);
        }
        Path target = root.resolve(clean).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("entrée d'archive hors du dossier : " + name);
        }
        return target;
    }

    private static String headerName(byte[] header) {
        String name = cString(slice(header, 0, 100));
        if ("ustar".equals(cString(slice(header, 257, 6)).trim())) {
            String prefix = cString(slice(header, 345, 155));
            if (!prefix.isEmpty()) {
                return prefix + "/" + name;
            }
        }
        return name;
    }

    /** Le {@code path} d'un en-tête PAX ({@code "<longueur> path=<valeur>\n"}…), ou {@code null}. */
    static String paxPath(byte[] records) throws IOException {
        int i = 0;
        String path = null;
        while (i < records.length) {
            int space = i;
            while (space < records.length && records[space] != ' ') {
                space++;
            }
            if (space >= records.length) {
                break;
            }
            int length;
            try {
                length = Integer.parseInt(new String(records, i, space - i, StandardCharsets.US_ASCII));
            } catch (NumberFormatException e) {
                throw new IOException("en-tête PAX illisible");
            }
            if (length <= 0 || i + length > records.length) {
                throw new IOException("en-tête PAX illisible");
            }
            String record = new String(records, space + 1, i + length - space - 2, StandardCharsets.UTF_8);
            int eq = record.indexOf('=');
            if (eq > 0) {
                String key = record.substring(0, eq);
                if (key.equals("path")) {
                    path = record.substring(eq + 1);
                } else if (key.equals("linkpath")) {
                    throw new IOException("lien refusé dans le paquet");
                }
            }
            i += length;
        }
        return path;
    }

    private static boolean checksumValid(byte[] header) {
        long expected = octal(header, 148, 8);
        long sum = 0;
        for (int i = 0; i < BLOCK; i++) {
            sum += (i >= 148 && i < 156) ? ' ' : (header[i] & 0xff);
        }
        return expected == sum;
    }

    /** Un champ octal ustar (terminé par NUL ou espace) ; -1 s'il est illisible. */
    static long octal(byte[] header, int offset, int length) {
        long value = 0;
        boolean any = false;
        for (int i = offset; i < offset + length; i++) {
            byte b = header[i];
            if (b == 0 || b == ' ') {
                if (any) {
                    break;
                }
                continue;
            }
            if (b < '0' || b > '7') {
                return -1;
            }
            value = value * 8 + (b - '0');
            any = true;
        }
        return value;
    }

    private static boolean readBlock(InputStream in, byte[] block) throws IOException {
        int read = in.readNBytes(block, 0, BLOCK);
        if (read == 0) {
            return false;
        }
        if (read < BLOCK) {
            throw new EOFException("archive tronquée");
        }
        return true;
    }

    private static byte[] readBytes(InputStream in, long size) throws IOException {
        if (size > 1_048_576) {
            throw new IOException("en-tête étendu démesuré");
        }
        byte[] data = in.readNBytes((int) size);
        if (data.length < size) {
            throw new EOFException("archive tronquée");
        }
        skip(in, padded(size) - size);
        return data;
    }

    private static void copy(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buffer = new byte[65_536];
        long remaining = size;
        while (remaining > 0) {
            int read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (read < 0) {
                throw new EOFException("archive tronquée");
            }
            out.write(buffer, 0, read);
            remaining -= read;
        }
    }

    private static void skip(InputStream in, long count) throws IOException {
        long remaining = count;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                if (in.read() < 0) {
                    throw new EOFException("archive tronquée");
                }
                skipped = 1;
            }
            remaining -= skipped;
        }
    }

    private static long padded(long size) {
        return (size + BLOCK - 1) / BLOCK * BLOCK;
    }

    private static boolean allZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static byte[] slice(byte[] data, int offset, int length) {
        byte[] out = new byte[length];
        System.arraycopy(data, offset, out, 0, length);
        return out;
    }

    private static String cString(byte[] data) {
        int end = 0;
        while (end < data.length && data[end] != 0) {
            end++;
        }
        return new String(data, 0, end, StandardCharsets.UTF_8);
    }
}
