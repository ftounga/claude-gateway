package fr.claudegateway.jarvis.replay;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * <b>Le rejeu d'une séance Jarvis</b> (F-255 / SF-255-02) : ce qui est rendu à l'administrateur, tour par tour. Les
 * contenus (ce qui a été dit, compris, répondu) viennent des sources qui les gardent déjà (captures, messages) ; le
 * journal d'écoute reste sans contenu.
 */
public final class JarvisReplay {

    private JarvisReplay() {
    }

    /** Comment le tour a été rattaché à ses sources. */
    public enum Correlation {
        /** Par l'identifiant de tour commun (SF-255-01). */
        IDENTIFIANT,
        /** Par l'heure, pour les tours d'avant SF-255-01 : approximatif. */
        ESTIMEE
    }

    /** Une séance : des tours séparés de moins de {@link JarvisReplayService#SESSION_GAP}. */
    public record Session(OffsetDateTime from, OffsetDateTime to, int pages, int turns, Bilan bilan) {
    }

    /** Une séance rejouée. */
    public record Replay(OffsetDateTime from, OffsetDateTime to, int pages, List<Turn> turns, Bilan bilan,
            Thresholds thresholds) {
    }

    /** Un tour : une parole entendue (ou perdue), et tout ce qui a suivi jusqu'à la parole suivante. */
    public record Turn(int number, String turnId, String pageId, Correlation correlation, OffsetDateTime at,
            String listenMode, Heard heard, Steps steps, String brain, String speculation, String delegationReason,
            String emptyDiagnostic, Integer saidChars, String reply, Subject screenSubject,
            List<Delegation> delegations, List<Show> shows, List<String> streamFallbacks, boolean streamError,
            boolean measured, List<Verdict> verdicts, List<String> events) {
    }

    /** Ce qui a été entendu : longueur transcrite, refus, textes (flux, retenu, message), audio. */
    public record Heard(Integer chars, String rejected, String streamText, String finalText, String userText,
            UUID captureId, Integer captureSeconds) {
    }

    /** Les étapes du tour, en ms depuis la fin de parole (mesure de l'écran). */
    public record Steps(Integer speechMs, Integer endSilenceMs, Integer transcriptMs, Integer sentMs,
            Integer firstTokenMs, Integer firstSoundMs, Integer doneMs) {
    }

    /** Un sujet du compte. */
    public record Subject(UUID id, String name) {
    }

    /** Une délégation partie pendant le tour, et la réponse rendue dans le sujet cible. */
    public record Delegation(OffsetDateTime at, Subject subject, Integer chantier, String status, String label,
            String reply) {
    }

    /** Un ordre d'écran appliqué ou refusé (journal d'écoute, SF-253-06). */
    public record Show(String action, boolean refused, String reason) {
    }

    /** Un verdict sur un tour (SF-255-03), calculé sans IA. */
    public record Verdict(String code, String detail) {
    }

    /** Le bilan d'une séance (SF-255-03). */
    public record Bilan(int turns, int heard, int lost, Double lostRate, int repeated, int slow,
            Integer firstSoundP50, Integer firstSoundP90, int wrongSubject, int shown, int voiciWithoutShow,
            int delegations, int silentDelegations, int streamFallbacks, Double streamFallbackRate, int unmeasured) {
    }

    /** Les seuils des verdicts, rendus avec le rejeu pour qu'on sache ce qui a été jugé. */
    public record Thresholds(int slowFirstSoundMs, int repeatWindow, long sessionGapMinutes) {
    }
}
