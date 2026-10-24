package fr.claudegateway.videos;

import java.util.Locale;
import java.util.Optional;

/**
 * <b>Les types de média admis</b> (F-213 / SF-213-01) : une liste fermée, contrôlée par l'extension
 * <b>et</b> par la signature des premiers octets — un fichier renommé en {@code .mp4} n'est pas une vidéo.
 */
public final class VideoMediaTypes {

    private VideoMediaTypes() {
    }

    /**
     * Un type admis.
     *
     * @param kind      vidéo ou son
     * @param extension extension canonique (celle du nom rangé)
     * @param mime      type servi
     */
    public record MediaType(VideoKind kind, String extension, String mime) {

        /** Le nom de l'objet rangé : {@code video.mp4}, {@code audio.mp3}… */
        public String fileName() {
            return (kind == VideoKind.AUDIO ? "audio." : "video.") + extension;
        }
    }

    /** Le type d'un chemin, d'après son extension, s'il est admis pour ce genre. */
    public static Optional<MediaType> of(String path, VideoKind kind) {
        String ext = extension(path);
        MediaType type = switch (ext) {
            case "mp4" -> new MediaType(VideoKind.VIDEO, "mp4", "video/mp4");
            case "webm" -> new MediaType(VideoKind.VIDEO, "webm", "video/webm");
            case "mp3" -> new MediaType(VideoKind.AUDIO, "mp3", "audio/mpeg");
            case "wav" -> new MediaType(VideoKind.AUDIO, "wav", "audio/wav");
            case "m4a" -> new MediaType(VideoKind.AUDIO, "m4a", "audio/mp4");
            default -> null;
        };
        return type != null && type.kind() == kind ? Optional.of(type) : Optional.empty();
    }

    /** Le type d'une image de couverture ({@code png, jpg, jpeg, webp}), s'il est admis. */
    public static Optional<String> posterMime(String path) {
        return switch (extension(path)) {
            case "png" -> Optional.of("image/png");
            case "jpg", "jpeg" -> Optional.of("image/jpeg");
            case "webp" -> Optional.of("image/webp");
            default -> Optional.empty();
        };
    }

    /** L'extension d'objet d'une couverture, d'après son type. */
    public static String posterExtension(String mime) {
        return switch (mime) {
            case "image/png" -> "png";
            case "image/webp" -> "webp";
            default -> "jpg";
        };
    }

    /** Vrai si les premiers octets portent la signature attendue pour ce type. */
    public static boolean matches(MediaType type, byte[] head) {
        if (head == null) {
            return false;
        }
        return switch (type.extension()) {
            // ISO BMFF : « ftyp » aux octets 4–7 (mp4, m4a).
            case "mp4", "m4a" -> head.length >= 8 && head[4] == 'f' && head[5] == 't' && head[6] == 'y'
                    && head[7] == 'p';
            // Matroska / WebM : en-tête EBML.
            case "webm" -> startsWith(head, 0x1A, 0x45, 0xDF, 0xA3);
            // MP3 : étiquette ID3, ou trame MPEG (11 bits de synchronisation).
            case "mp3" -> startsWith(head, 'I', 'D', '3')
                    || (head.length >= 2 && (head[0] & 0xFF) == 0xFF && (head[1] & 0xE0) == 0xE0);
            case "wav" -> head.length >= 12 && startsWith(head, 'R', 'I', 'F', 'F') && head[8] == 'W'
                    && head[9] == 'A' && head[10] == 'V' && head[11] == 'E';
            default -> false;
        };
    }

    /** Vrai si les premiers octets sont ceux d'une image du type annoncé. */
    public static boolean matchesPoster(String mime, byte[] head) {
        if (head == null) {
            return false;
        }
        return switch (mime) {
            case "image/png" -> startsWith(head, 0x89, 'P', 'N', 'G');
            case "image/jpeg" -> startsWith(head, 0xFF, 0xD8, 0xFF);
            case "image/webp" -> head.length >= 12 && startsWith(head, 'R', 'I', 'F', 'F') && head[8] == 'W'
                    && head[9] == 'E' && head[10] == 'B' && head[11] == 'P';
            default -> false;
        };
    }

    private static boolean startsWith(byte[] head, int... signature) {
        if (head.length < signature.length) {
            return false;
        }
        for (int i = 0; i < signature.length; i++) {
            if ((head[i] & 0xFF) != (signature[i] & 0xFF)) {
                return false;
            }
        }
        return true;
    }

    static String extension(String path) {
        if (path == null) {
            return "";
        }
        String name = path.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
