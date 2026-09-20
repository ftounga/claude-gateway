package fr.claudegateway.teams.meeting;

/**
 * Le format d'origine d'une <b>transcription externe</b> (client) rattachée à une réunion
 * (F-128 / SF-128-20a). Purement informatif : la transcription est stockée <b>telle quelle</b> en
 * texte ; ce marqueur dit seulement d'où elle vient, pour l'afficher et, plus tard, aider la
 * consolidation (SF-128-20b) à la lire.
 */
public enum ExternalTranscriptFormat {

    /** Texte collé, ou fichier {@code .txt} : stocké sans transformation. */
    TEXT,

    /** Fichier de sous-titres {@code .vtt} (WebVTT) : stocké tel quel (horodatages + « Nom : … »). */
    VTT,

    /** Document Word {@code .docx} : le texte a été extrait sur la machine ({@code DocxTextExtractor}). */
    DOCX
}
