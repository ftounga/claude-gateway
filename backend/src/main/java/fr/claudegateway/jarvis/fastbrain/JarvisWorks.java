package fr.claudegateway.jarvis.fastbrain;

import java.time.Instant;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.claudegateway.jarvis.JarvisReplies;

/**
 * <b>Les travaux de Jarvis, vus de l'écran</b> (F-270 / SF-270-01) — une ligne par travail en fond (délégation, tour
 * direct né à la voix, course autonome), avec un <b>nom court</b> (jamais une phrase : la scène l'étiquette) et sa phase.
 */
public final class JarvisWorks {

    /** D'où vient le travail. */
    public enum Origin { DELEGATION, DIRECT, COURSE }

    /**
     * Un travail : {@code id} (identifiant de délégation, ou {@code course:<id>}), sujet, chantier, nom court, libellé dit,
     * phase ({@link JarvisWorkPhase}), départ et fin (ou {@code null}).
     */
    public record Work(String id, Origin origin, UUID subjectId, String subjectName, Integer chantier, String name,
            String label, JarvisWorkPhase phase, Instant since, Instant endedAt, Course course) {

        /** Un travail sans course (délégation, tour direct). */
        public Work(String id, Origin origin, UUID subjectId, String subjectName, Integer chantier, String name,
                String label, JarvisWorkPhase phase, Instant since, Instant endedAt) {
            this(id, origin, subjectId, subjectName, chantier, name, label, phase, since, endedAt, null);
        }
    }

    /**
     * F-271 / SF-271-04 — ce qu'une course autonome dit d'elle : étapes faites sur le total, issue brute
     * ({@code RUNNING}, {@code DONE}, {@code PARTIAL}, {@code BLOCKED}, {@code FAILED}, {@code STOPPED}), et sa raison
     * d'arrêt courte (dite par Jarvis, jamais affichée), {@code byUser} quand l'utilisateur a repris la main.
     */
    public record Course(int stepsDone, int stepsTotal, String status, String reason, boolean byUser) {
    }

    /** La raison d'arrêt d'une course, en une phrase dite : première phrase, {@value #MAX_REASON} caractères au plus. */
    static final int MAX_REASON = 160;

    public static String shortReason(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String flat = raw.replaceAll("[`*_#>]+", " ").replaceAll("\\s+", " ").replaceAll(" ([.,])", "$1").strip();
        java.util.regex.Matcher end = java.util.regex.Pattern.compile("[.!?](\\s|$)").matcher(flat);
        if (end.find()) {
            flat = flat.substring(0, end.start() + 1);
        }
        if (flat.length() > MAX_REASON) {
            int cut = flat.lastIndexOf(' ', MAX_REASON - 1);
            flat = flat.substring(0, cut > 40 ? cut : MAX_REASON - 1).strip() + "…";
        }
        return flat;
    }

    /** Une clé de ticket (« LDIC-325 ») : majuscules, tiret, chiffres. */
    static final Pattern TICKET = Pattern.compile("\\b([A-Z][A-Z0-9]{1,9}-\\d{1,6})\\b");
    static final int MAX_NAME = 24;

    private JarvisWorks() {
    }

    /**
     * Le nom court d'un travail : la clé de ticket de son libellé ou de son titre, sinon « chantier N », sinon le nom du
     * sujet, sinon les premiers mots du libellé.
     */
    public static String shortName(String label, Integer chantier, String subjectName) {
        String text = label == null ? "" : label;
        Matcher ticket = TICKET.matcher(text);
        if (ticket.find()) {
            return ticket.group(1);
        }
        if (chantier != null && chantier > 0) {
            return "chantier " + chantier;
        }
        if (subjectName != null && !subjectName.isBlank()) {
            return clip(subjectName.strip());
        }
        String words = String.join(" ", java.util.Arrays.stream(text.strip().split("\\s+")).limit(3).toList());
        return words.isBlank() ? "Jarvis" : clip(words);
    }

    /** Vrai si la ligne dite de la réponse finit par une demande de confirmation (« Vous confirmez ? »). */
    public static boolean asksConfirmation(String reply) {
        String line = JarvisReplies.oralLine(reply);
        if (line == null) {
            return false;
        }
        String flat = line.strip().toLowerCase(Locale.ROOT);
        return flat.matches("(?s).*(vous confirmez|tu confirmes)\\s*\\?\\s*$");
    }

    private static String clip(String s) {
        return s.length() <= MAX_NAME ? s : s.substring(0, MAX_NAME - 1) + "…";
    }
}
