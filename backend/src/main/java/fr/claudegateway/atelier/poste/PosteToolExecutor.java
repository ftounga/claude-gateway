package fr.claudegateway.atelier.poste;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * Exécute les lectures du terminal du poste (F-178). <b>Second verrou</b> : la garde du catalogue est
 * réévaluée à chaque appel — un outil nommé par le modèle hors du terminal du poste est refusé, rien n'est
 * lu. Le périmètre (utilisateur, poste) est celui du terminal <b>possédé</b>, jamais un identifiant venu du
 * modèle. Ne lève jamais vers la boucle.
 */
@Component
public class PosteToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(PosteToolExecutor.class);

    /** Le résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
    }

    private final PosteToolCatalog catalog;
    private final SubjectsStateService subjectsState;

    public PosteToolExecutor(PosteToolCatalog catalog, SubjectsStateService subjectsState) {
        this.catalog = catalog;
        this.subjectsState = subjectsState;
    }

    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        if (!catalog.isOpenFor(userId, workspace)) {
            return new Outcome("Cet outil n'existe qu'au terminal du poste : réponds sans lui.", true);
        }
        try {
            if (PosteToolCatalog.SUBJECTS_STATE.equals(tool)) {
                return new Outcome(subjectsState.describe(userId, workspace.getHostId()), false);
            }
            return new Outcome("Outil du poste inconnu : " + tool, true);
        } catch (RuntimeException ex) {
            log.debug("Lecture du poste en échec ({}) : {}", tool, ex.getClass().getSimpleName());
            return new Outcome("Lecture momentanément indisponible : réponds avec ce que tu sais, ou "
                    + "regarde les fichiers du sujet.", true);
        }
    }
}
