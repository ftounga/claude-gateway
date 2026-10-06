package fr.claudegateway.atelier.poste;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * Exécute les lectures du terminal du poste (F-178). <b>Second verrou</b> : la garde du catalogue est
 * réévaluée à chaque appel, outil par outil — un outil nommé par le modèle hors du terminal du poste (ou une
 * lecture Radar sans Radar) est refusé, rien n'est lu. Le périmètre (utilisateur, poste) est celui du
 * terminal <b>possédé</b>, jamais un identifiant venu du modèle. Ne lève jamais vers la boucle.
 */
@Component
public class PosteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PosteToolExecutor.class);

    /** Le résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
    }

    private final PosteToolCatalog catalog;
    private final SubjectsStateService subjectsState;
    private final PosteAppReadService appReads;

    public PosteToolExecutor(PosteToolCatalog catalog, SubjectsStateService subjectsState,
            @Nullable PosteAppReadService appReads) {
        this.catalog = catalog;
        this.subjectsState = subjectsState;
        this.appReads = appReads;
    }

    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        if (!catalog.isOpenFor(userId, workspace, tool)) {
            return new Outcome("Cet outil n'existe pas ici (lectures du terminal du poste ; le Radar exige un "
                    + "poste suivi par la Vigie) : réponds sans lui.", true);
        }
        UUID hostId = workspace.getHostId();
        try {
            return switch (tool) {
                case PosteToolCatalog.SUBJECTS_STATE -> ok(subjectsState.describe(userId, hostId));
                case PosteToolCatalog.RADAR_RESUME -> ok(appReads.radarResume(userId, hostId));
                case PosteToolCatalog.RADAR_SUJETS -> ok(appReads.radarSujets(userId, hostId,
                        input != null && input.path("include_closed").asBoolean(false)));
                case PosteToolCatalog.PAGES_LISTER -> ok(appReads.pagesLister(userId, hostId, text(input, "space")));
                case PosteToolCatalog.PAGE_LIRE -> pageLire(userId, hostId, input);
                case PosteToolCatalog.COMPTE_CONSOMMATION -> ok(appReads.consommation(userId));
                default -> new Outcome("Outil du poste inconnu : " + tool, true);
            };
        } catch (IllegalArgumentException ex) {
            return new Outcome(ex.getMessage() == null ? "Argument invalide." : ex.getMessage(), true);
        } catch (RuntimeException ex) {
            log.debug("Lecture du poste en échec ({}) : {}", tool, ex.getClass().getSimpleName());
            return new Outcome("Lecture momentanément indisponible (ou élément introuvable) : réponds avec ce "
                    + "que tu sais, ou regarde les fichiers du sujet.", true);
        }
    }

    private Outcome pageLire(UUID userId, UUID hostId, JsonNode input) {
        UUID pageId;
        try {
            pageId = UUID.fromString(text(input, "page_id"));
        } catch (RuntimeException ex) {
            return new Outcome("page_id manquant ou invalide (UUID attendu, rendu par pages_lister).", true);
        }
        JsonNode raw = input == null ? null : input.path("version");
        Integer version = null;
        if (raw != null && raw.isNumber()) {
            version = raw.asInt();
        } else if (raw != null && raw.isTextual() && raw.asText().strip().matches("\\d{1,6}")) {
            version = Integer.valueOf(raw.asText().strip());
        }
        return ok(appReads.pageLire(userId, hostId, pageId, version));
    }

    private static Outcome ok(String content) {
        return new Outcome(content, false);
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return null;
        }
        String value = input.get(field).asText("").strip();
        return value.isEmpty() ? null : value;
    }
}
