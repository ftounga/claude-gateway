package fr.claudegateway.jarvis.pieces;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * <b>Une pièce montrable</b> (F-237 / SF-237-01, cadrage §4.1) : ce que Jarvis peut afficher — une entrée, une preuve,
 * une production. Jamais de binaire ni d'URL S3 : {@code thumb} et {@code open} sont des chemins <b>passerelle</b>
 * (sous JWT ou ticket existant).
 *
 * @param id            identifiant typé ({@code dep:<uuid>}, {@code proof:<hex>}, {@code page:<uuid>@v3}…)
 * @param caption       description <b>déjà en base</b> (cadrage §11), ≤ 600 caractères, {@code null} si aucune
 * @param captionSource d'où vient la description ({@code null} si aucune)
 * @param chantier      numéro du chantier rattaché, {@code null} si {@code chantierLink = NONE}
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Piece(String id, Category category, Kind kind, String title, String caption,
        CaptionSource captionSource, OffsetDateTime at, UUID subjectId, Integer chantier, ChantierLink chantierLink,
        String thumb, String open, Map<String, Object> meta, Availability available) {

    /** La catégorie dite : « ce que vous m'aviez fourni », « ce qui prouve », « ce que j'ai produit ». */
    public enum Category {
        ENTREE, PREUVE, PRODUCTION
    }

    /** Le type de pièce — chacun aura sa visionneuse (SF-237-04). */
    public enum Kind {
        IMAGE, VIDEO, AUDIO, PDF, PAGE, SLIDES, ARCHITECTURE, DIAGRAM, TEXT, FILE
    }

    /** La source de la description (cadrage §11). */
    public enum CaptionSource {
        TITLE, DESCRIPTION, MESSAGE, STEP, CHAPTERS, REFORMULATION, SUBJECT, PROMPT, SPEC
    }

    /** Comment la pièce est rattachée au chantier (cadrage §4.2) — dit par Jarvis quand c'est déduit. */
    public enum ChantierLink {
        MESSAGE, WINDOW, NONE
    }

    /** Lisible maintenant, ou sur un poste hors ligne, ou purgée. */
    public enum Availability {
        ONLINE, HOST_OFFLINE, PURGED
    }
}
