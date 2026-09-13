package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.Objects;

/**
 * L'issue d'une analyse (F-101 / SF-101-01).
 *
 * @param kind             ce que la file doit faire du lot
 * @param tokens           ce que l'analyse a consommé, compté quelle que soit l'issue
 * @param writer           les écritures au registre, jouées dans la transaction qui efface le brut
 *                         ({@code DONE} seulement)
 * @param retainedCount    échanges retenus par le tri
 * @param subjectsAttached rattachements à un sujet existant
 * @param subjectsCreated  sujets créés
 * @param code             motif court d'un {@code RETRY} / {@code DEFER}, sans contenu
 * @param retryAt          échéance d'un {@code DEFER} ; {@code null} = la file choisit
 */
public record RadarAnalysisOutcome(Kind kind, RadarAnalysisTokens tokens, Writer writer,
        int retainedCount, int subjectsAttached, int subjectsCreated, String code, OffsetDateTime retryAt) {

    /** Ce que la file fait du lot. */
    public enum Kind {
        DONE,
        RETRY,
        DEFER
    }

    /** Les écritures d'une analyse réussie. */
    @FunctionalInterface
    public interface Writer {
        /** Écrit au registre ; une exception annule tout, brut compris. */
        void write();
    }

    public RadarAnalysisOutcome {
        Objects.requireNonNull(kind, "kind");
        tokens = tokens == null ? RadarAnalysisTokens.NONE : tokens;
        writer = writer == null ? () -> { } : writer;
    }

    /** Analyse réussie. */
    public static RadarAnalysisOutcome done(RadarAnalysisTokens tokens, int retained, int attached,
            int created, Writer writer) {
        return new RadarAnalysisOutcome(Kind.DONE, tokens, writer, retained, attached, created, null, null);
    }

    /** À retenter. */
    public static RadarAnalysisOutcome retry(RadarAnalysisTokens tokens, String code) {
        return new RadarAnalysisOutcome(Kind.RETRY, tokens, null, 0, 0, 0, code, null);
    }

    /** À reporter, sans échec. */
    public static RadarAnalysisOutcome defer(RadarAnalysisTokens tokens, String code, OffsetDateTime retryAt) {
        return new RadarAnalysisOutcome(Kind.DEFER, tokens, null, 0, 0, 0, code, retryAt);
    }
}
