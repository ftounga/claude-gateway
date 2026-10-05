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
                case JourneyToolCatalog.SUBMIT_DIAGNOSIS -> submitDiagnosis(userId, workspace, input);
                case JourneyToolCatalog.UPDATE_STEP -> updateStep(userId, workspace, input);
                case JourneyToolCatalog.REOPEN -> reopen(userId, workspace, input);
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
            case NEEDS_DIAGNOSIS -> Outcome.error("Le sujet est en Investigation : pose d'abord ton diagnostic "
                    + "(submit_diagnosis, avec tes preuves et ta confiance). L'utilisateur confirme le passage "
                    + "au plan ; ensuite seulement, set_subject_plan.");
        };
    }

    private Outcome submitDiagnosis(UUID userId, Workspace workspace, JsonNode input) {
        SubjectJourneyService.DiagnosisOutcome outcome = service.submitDiagnosis(userId, workspace.getId(),
                text(input, "diagnosis"), text(input, "evidence"), text(input, "confidence"));
        return switch (outcome) {
            case PROPOSED -> Outcome.ok("Diagnostic posé. L'utilisateur voit « Prêt à planifier » : [Planifier] "
                    + "[Continuer l'investigation]. Résume-lui le diagnostic et tes preuves en quelques lignes, "
                    + "et attends son choix avant de poser le plan.");
            case NOT_INVESTIGATING -> Outcome.error("Le sujet n'est pas en Investigation : le diagnostic est déjà "
                    + "posé. S'il est contredit, utilise reopen_investigation.");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre : le diagnostic sert au mode guidé.");
        };
    }

    private Outcome updateStep(UUID userId, Workspace workspace, JsonNode input) {
        int step = input == null ? 0 : input.path("step").asInt(0);
        SubjectJourneyService.StepChange change = service.updateStep(userId, workspace.getId(), step,
                text(input, "status"), text(input, "evidence"));
        return switch (change.outcome()) {
            case UPDATED -> Outcome.ok("Étape " + step + " à jour.");
            case TO_VERIFICATION -> Outcome.ok("Étape " + step + " à jour. Plus aucune étape à exécuter : le sujet "
                    + "passe en VÉRIFICATION. Vérifie chaque étape comme le plan le dit, preuves à l'appui ; on ne "
                    + "modifie plus.");
            case ALL_VERIFIED -> Outcome.ok("Toutes les étapes sont vérifiées. L'utilisateur voit la proposition "
                    + "de clore le sujet : résume-lui ce qui a été fait et vérifié.");
            case FAILED -> Outcome.ok("Étape " + step + " en ÉCHEC. Ne force pas : si la cause est comprise, "
                    + "amende le plan (set_subject_plan) ; sinon reopen_investigation.");
            case NOT_EXECUTING -> Outcome.error("Aucun plan validé en cours d'exécution : rien à faire avancer.");
            case NO_SUCH_STEP -> Outcome.error("Il n'y a pas d'étape " + step + " dans le plan (numérotées à "
                    + "partir de 1).");
            case NOT_GUIDED -> Outcome.error("Le sujet est en mode Libre.");
        };
    }

    private Outcome reopen(UUID userId, Workspace workspace, JsonNode input) {
        boolean reopened = service.reopenInvestigation(userId, workspace.getId(), text(input, "reason"));
        return reopened
                ? Outcome.ok("Retour en INVESTIGATION. Le plan est gardé ; rien ne se modifie avant un nouveau "
                        + "diagnostic et un plan validé. Dis à l'utilisateur ce qui a contredit le diagnostic.")
                : Outcome.error("Le sujet n'est pas en mode guidé (ou il est clos) : rien à rouvrir.");
    }

    static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }
}
