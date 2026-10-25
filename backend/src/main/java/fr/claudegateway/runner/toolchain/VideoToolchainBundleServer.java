package fr.claudegateway.runner.toolchain;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.OptionalLong;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRange;
import org.springframework.stereotype.Component;

import jakarta.servlet.http.HttpServletResponse;

/**
 * <b>Diffuser un paquet de la chaîne vidéo</b> (F-215 / SF-215-06) : {@code 200} complet, {@code 206} pour une plage
 * (reprise d'un téléchargement interrompu), {@code 416} hors fichier. Les octets passent du stockage au runner
 * <b>en flux</b>, par tampons de 64 Kio — jamais un paquet de 150 Mo en mémoire.
 *
 * <p>Même mécanique que {@code VideoMediaServer} (F-213), volontairement non partagée : ce flux-là sert un média
 * d'un compte, celui-ci un artefact commun à tous les postes.</p>
 */
@Component
public class VideoToolchainBundleServer {

    private static final int BUFFER = 64 * 1024;

    private final VideoToolchainStore store;

    public VideoToolchainBundleServer(VideoToolchainStore store) {
        this.store = store;
    }

    /**
     * Sert le paquet d'une plateforme.
     *
     * @return {@code false} si ce paquet n'est pas publié (rien n'a été écrit dans la réponse)
     */
    public boolean serve(String version, String platform, String range, HttpServletResponse response)
            throws IOException {
        OptionalLong size = store.bundleSize(version, platform);
        if (size.isEmpty()) {
            return false;
        }
        long length = size.getAsLong();
        response.setHeader(HttpHeaders.ACCEPT_RANGES, "bytes");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, max-age=86400");
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + platform + ".tar.gz\"");
        response.setContentType("application/gzip");
        long start = 0;
        long end = length - 1;
        List<HttpRange> ranges = parse(range);
        if (!ranges.isEmpty()) {
            HttpRange first = ranges.get(0);
            if (unsatisfiable(first, length)) {
                response.setStatus(HttpServletResponse.SC_REQUESTED_RANGE_NOT_SATISFIABLE);
                response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes */" + length);
                response.setContentLengthLong(0);
                return true;
            }
            start = first.getRangeStart(length);
            end = first.getRangeEnd(length);
            response.setStatus(HttpServletResponse.SC_PARTIAL_CONTENT);
            response.setHeader(HttpHeaders.CONTENT_RANGE, "bytes " + start + "-" + end + "/" + length);
        } else {
            response.setStatus(HttpServletResponse.SC_OK);
        }
        response.setContentLengthLong(end - start + 1);
        if (length == 0) {
            return true;
        }
        try (InputStream in = store.openBundle(version, platform, start, end)
                .orElseThrow(() -> new IOException("paquet disparu pendant la lecture"))) {
            OutputStream out = response.getOutputStream();
            byte[] buffer = new byte[BUFFER];
            long remaining = end - start + 1;
            int read;
            while (remaining > 0 && (read = in.read(buffer, 0, (int) Math.min(buffer.length, remaining))) > 0) {
                out.write(buffer, 0, read);
                remaining -= read;
            }
            out.flush();
        }
        return true;
    }

    private static boolean unsatisfiable(HttpRange range, long length) {
        try {
            return length == 0 || range.getRangeStart(length) >= length;
        } catch (IllegalArgumentException e) {
            return true;
        }
    }

    /** Un {@code Range} absent ou mal formé est traité comme « pas de Range » (réponse complète). */
    private static List<HttpRange> parse(String range) {
        if (range == null || range.isBlank()) {
            return List.of();
        }
        try {
            return HttpRange.parseRanges(range);
        } catch (IllegalArgumentException ignored) {
            return List.of();
        }
    }
}
