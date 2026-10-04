package fr.claudegateway.atelier.journey;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute les outils du parcours</b> (F-176). La garde est posée <b>avant</b>, par la boucle. Le
 * {@code userId} et le {@code workspace} viennent <b>du tour</b> — aucun identifiant n'est lu dans les
 * paramètres de l'outil.
 *
 * <p>Toute erreur est un <b>résultat d'outil en erreur</b>, jamais une exception : le parcours ne
 * doit pas tuer le tour qu'il organise.</p>
 */
@Component
public class JourneyToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(JourneyToolExecutor.class);

    private final SubjectJourneyService service;

    public JourneyToolExecutor(SubjectJourneyService service) {
        this.service = service;
    }

    /** Le résultat rendu au modèle. */
    public record Outcome(String content, boolean error) {
        static Outcome ok(String content) {
            return new Outcome(content, false);
        }

        static Outcome error(String content) {
            return new Outcome(content, true);
        }
    }

    /** Aiguille un appel d'outil du parcours. */
    public Outcome execute(UUID userId, Workspace workspace, String tool, JsonNode input) {
        try {
            return switch (tool) {
                case JourneyToolCatalog.PROPOSE_GUIDED -> proposeGuided(userId, workspace, input);
                default -> Outcome.error("Outil du parcours inconnu : " + tool);
            };
        } catch (InvalidJourneyException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Outil du parcours en échec ({})", tool, e);
            return Outcome.error("Le parcours du sujet n'a pas pu être mis à jour. Continue sans, et "
                    + "dis-le à l'utilisateur plutôt que de réessayer.");
        }
    }

    private Outcome proposeGuided(UUID userId, Workspace workspace, JsonNode input) {
        SubjectJourneyService.ProposalOutcome outcome =
                service.proposeGuided(userId, workspace.getId(), text(input, "reason"));
        return switch (outcome) {
            case PROPOSED -> Outcome.ok("Proposition affichée à l'utilisateur : [Passer en guidé] [Rester "
                    + "libre]. Le sujet reste en Libre tant qu'il n'a pas choisi : continue à comprendre "
                    + "(lecture libre), sans rien modifier de plus que ce qu'il a explicitement demandé, et "
                    + "dis-lui en une phrase pourquoi tu proposes le mode guidé.");
            case ALREADY_PROPOSED -> Outcome.ok("La proposition attend déjà son choix : n'en reparle pas.");
            case ALREADY_GUIDED -> Outcome.ok("Le sujet est déjà en mode guidé : suis le parcours.");
            case DECLINED -> Outcome.ok("L'utilisateur a choisi de RESTER LIBRE sur ce sujet : ne repropose "
                    + "pas le mode guidé, avance en libre.");
        };
    }

    static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }
}
