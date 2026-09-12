package fr.claudegateway.runner.teams;

import java.time.Instant;

/**
 * Une réplique de transcription (F-87 / SF-87-01) : qui a parlé, quand, et ce qui a été dit.
 *
 * <p>L'horodatage n'est pas un détail : c'est <b>lui</b> qui permettra à F-90 de poser une image à
 * côté de la phrase prononcée pendant qu'elle était affichée. Une transcription sans horodatage
 * exact ne vaut pas grand-chose.</p>
 *
 * @param at                 instant du début de la réplique, en UTC
 * @param durationMs         durée, ou {@code -1} si elle n'est pas connue
 * @param speakerId          identifiant du locuteur, ou {@code ""}
 * @param speakerDisplayName nom du locuteur, ou {@code ""}
 * @param text               texte prononcé
 */
public record TeamsTranscriptCue(Instant at, long durationMs, String speakerId,
        String speakerDisplayName, String text) {

    public TeamsTranscriptCue {
        durationMs = durationMs < 0 ? -1 : durationMs;
        speakerId = speakerId == null ? "" : speakerId.strip();
        speakerDisplayName = speakerDisplayName == null ? "" : speakerDisplayName.strip();
        text = text == null ? "" : text.strip();
    }

    public boolean isReadable() {
        return at != null && !text.isEmpty();
    }
}
