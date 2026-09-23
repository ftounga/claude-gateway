package fr.claudegateway.atelier;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Détection d'un fichier <b>média</b> lisible par le fournisseur (F-121 / SF-121-15) : image
 * (PNG/JPEG/GIF/WebP) ou PDF. Logique pure, sans I/O ni dépendance fournisseur — l'assemblage du
 * bloc {@code image}/{@code document} et l'appel au poste vivent dans {@code AtelierChatService},
 * le mapping API dans {@code AnthropicAgentProvider} (Provider Independence).
 *
 * <p><b>Politique (décision D5).</b> La détection combine <b>l'extension</b> (elle borne le coût : on
 * ne renifle pas chaque {@code read_file}) et les <b>octets d'en-tête</b> (magic bytes : une extension
 * peut mentir). Il faut les deux pour conclure « média » ; sinon on retombe sur la lecture texte
 * normale, jamais sur un échec.</p>
 */
public final class AtelierMediaRead {

    /** Borne stricte d'une image lue comme média (F-121 / SF-121-15). */
    public static final long IMAGE_MAX_BYTES = 5L * 1024 * 1024;

    /**
     * Borne stricte d'un PDF lu comme média. Plafonnée en pratique par le cap dur du runner
     * {@code read_file_bytes} ({@code FileTools.MAX_BYTES_FILE} = 10 Mio), rappelé ici pour la clarté.
     */
    public static final long PDF_MAX_BYTES = 10L * 1024 * 1024;

    /** Octets d'en-tête suffisants pour reconnaître tous les formats supportés. */
    public static final int MAGIC_SNIFF_BYTES = 16;

    /** Type de média supporté : son type MIME, sa borne, et s'il s'agit d'un document (vs image). */
    public enum Kind {
        PNG("image/png", IMAGE_MAX_BYTES, false),
        JPEG("image/jpeg", IMAGE_MAX_BYTES, false),
        GIF("image/gif", IMAGE_MAX_BYTES, false),
        WEBP("image/webp", IMAGE_MAX_BYTES, false),
        PDF("application/pdf", PDF_MAX_BYTES, true);

        private final String mediaType;
        private final long maxBytes;
        private final boolean document;

        Kind(String mediaType, long maxBytes, boolean document) {
            this.mediaType = mediaType;
            this.maxBytes = maxBytes;
            this.document = document;
        }

        public String mediaType() {
            return mediaType;
        }

        public long maxBytes() {
            return maxBytes;
        }

        /** Vrai pour un document (PDF), faux pour une image — décide du bloc {@code document}/{@code image}. */
        public boolean isDocument() {
            return document;
        }
    }

    private AtelierMediaRead() {
    }

    /**
     * Type déduit de la seule <b>extension</b> du chemin, ou vide si l'extension n'est pas supportée.
     * Premier filtre, peu coûteux : il évite de renifler les octets de tout {@code read_file}.
     */
    public static Optional<Kind> fromExtension(String path) {
        if (path == null) {
            return Optional.empty();
        }
        String lower = path.toLowerCase(Locale.ROOT);
        int dot = lower.lastIndexOf('.');
        if (dot < 0) {
            return Optional.empty();
        }
        return switch (lower.substring(dot + 1)) {
            case "png" -> Optional.of(Kind.PNG);
            case "jpg", "jpeg" -> Optional.of(Kind.JPEG);
            case "gif" -> Optional.of(Kind.GIF);
            case "webp" -> Optional.of(Kind.WEBP);
            case "pdf" -> Optional.of(Kind.PDF);
            default -> Optional.empty();
        };
    }

    /**
     * Vrai si les octets d'en-tête portent bien la signature du type attendu (magic bytes). Défense
     * contre une extension trompeuse : un {@code .png} qui n'en est pas retombe sur la lecture texte.
     */
    public static boolean matchesMagic(Kind kind, byte[] head) {
        if (head == null) {
            return false;
        }
        return switch (kind) {
            // 89 50 4E 47 0D 0A 1A 0A
            case PNG -> startsWith(head, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A);
            // FF D8 FF
            case JPEG -> startsWith(head, 0xFF, 0xD8, 0xFF);
            // "GIF8" (GIF87a / GIF89a)
            case GIF -> startsWith(head, 0x47, 0x49, 0x46, 0x38);
            // "RIFF" .... "WEBP"
            case WEBP -> head.length >= 12
                    && startsWith(head, 0x52, 0x49, 0x46, 0x46)
                    && head[8] == (byte) 0x57 && head[9] == (byte) 0x45
                    && head[10] == (byte) 0x42 && head[11] == (byte) 0x50;
            // "%PDF-"
            case PDF -> startsWith(head, 0x25, 0x50, 0x44, 0x46, 0x2D);
        };
    }

    /**
     * Type <b>confirmé</b> d'un média : extension supportée <b>et</b> magic bytes reconnus. Vide
     * sinon — l'appelant retombe alors sur la lecture texte normale.
     */
    public static Optional<Kind> detect(String path, byte[] head) {
        Optional<Kind> byExtension = fromExtension(path);
        if (byExtension.isEmpty()) {
            return Optional.empty();
        }
        return matchesMagic(byExtension.get(), head) ? byExtension : Optional.empty();
    }

    private static boolean startsWith(byte[] data, int... prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((data[i] & 0xFF) != (prefix[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    /** Les {@link #MAGIC_SNIFF_BYTES} premiers octets (ou moins), pour la reconnaissance de type. */
    public static byte[] head(byte[] bytes) {
        if (bytes == null) {
            return new byte[0];
        }
        return Arrays.copyOf(bytes, Math.min(bytes.length, MAGIC_SNIFF_BYTES));
    }
}
