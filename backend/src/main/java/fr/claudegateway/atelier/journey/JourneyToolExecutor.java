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
                case JourneyToolCatalog.SET_PLAN -> setPlan(userId, workspace, input);
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

    private Outcome setPlan(UUID userId, Workspace workspace, JsonNode input) {
        JourneyPlan plan = JourneyPlan.fromToolInput(input == null ? null : input.get("steps"));
        SubjectJourneyService.PlanChange change = service.setPlan(userId, workspace.getId(), plan);
        return switch (change.outcome()) {
            case SET -> Outcome.ok("Plan v" + change.version() + " posé (" + plan.steps().size() + " étapes). "
                    + "Il attend la VALIDATION de l'utilisateur, d'un clic : présente-le en quelques lignes "
                    + "et ne modifie rien avant qu'il l'ait validé.");
            case AMENDMENT -> Outcome.ok("Amendement : plan v" + change.version() + " posé. Il remplace le "
                    + "plan validé et doit être REVALIDÉ par l'utilisateur : dis-lui ce qui change et pourquoi, "
                    + "et ne modifie rien avant sa validation.");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre : le plan structuré sert au mode "
                    + "guidé. Utilise set_plan pour organiser ton tour, ou propose le mode guidé.");
            case CLOSED -> Outcome.error("Le sujet est clos : rien à planifier. S'il faut reprendre, "
                    + "l'utilisateur rouvre le sujet en mode guidé.");
            case EMPTY -> Outcome.error("Aucune étape lisible : chaque étape a au moins un title et un risk.");
        };
    }

    static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }
}
