package fr.claudegateway.atelier.actions;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute {@code record_blocker}</b> (F-151 / SF-151-02) : inscrit dans le terminal l'action que
 * l'utilisateur seul peut faire.
 *
 * <p>La garde est posée <b>avant</b>, par la boucle : cet exécuteur ne décide de rien. Le
 * {@code userId} et le {@code workspace} viennent <b>du tour</b> — aucun identifiant n'est lu dans
 * les paramètres de l'outil, jamais.</p>
 *
 * <p>Toute erreur est un <b>résultat d'outil en erreur</b>, pas une exception : un blocage mal
 * énoncé ne doit pas tuer le tour dans lequel il a été rencontré.</p>
 */
@Component
public class TerminalActionToolExecutor {

    private static final Logger log = LoggerFactory.getLogger(TerminalActionToolExecutor.class);

    private final TerminalActionService service;

    public TerminalActionToolExecutor(TerminalActionService service) {
        this.service = service;
    }

    /**
     * Inscrit le blocage décrit par l'appel.
     *
     * @param userId    propriétaire du terminal (celui du tour)
     * @param workspace terminal du tour, déjà vérifié comme possédé
     * @param input     paramètres de l'outil
     */
    public Outcome execute(UUID userId, Workspace workspace, JsonNode input) {
        String description = text(input, "description");
        if (description.isEmpty()) {
            return Outcome.error("description est requise : ce que l'utilisateur doit faire, "
                    + "à l'impératif.");
        }

        TerminalActionKind kind;
        try {
            String raw = text(input, "kind");
            kind = raw.isEmpty()
                    ? TerminalActionKind.ACTION
                    : TerminalActionKind.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Outcome.error("kind vaut ACTION ou MESSAGE.");
        }

        try {
            TerminalActionService.Recording recording = service.record(userId, workspace.getId(), null,
                    description, text(input, "blocks"), text(input, "person"), kind,
                    text(input, "key"));
            return new Outcome(message(recording), false, recording.action());
        } catch (InvalidTerminalActionException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            // Base indisponible, contention sur la clé unique : le tour continue, sans l'action.
            log.warn("Inscription d'une action de terminal impossible", e);
            return Outcome.error("L'action n'a pas pu être inscrite. Dis-le à l'utilisateur dans ta "
                    + "réponse plutôt que de réessayer.");
        }
    }

    /** Ce que l'agent lit — et qui doit suffire à décider s'il en reparle ou non. */
    private static String message(TerminalActionService.Recording recording) {
        String what = "« " + recording.action().getDescription() + " »";
        return switch (recording.outcome()) {
            case RECORDED -> "Action inscrite dans le terminal : " + what
                    + ". L'utilisateur la verra dans son menu. Continue ton tour : fais tout ce qui "
                    + "ne dépend pas de ce blocage, puis dis ce qui reste suspendu à lui.";
            case ALREADY_OPEN -> "Cette action attend déjà dans le terminal : " + what
                    + ". N'en inscris pas une seconde et n'en refais pas la demande.";
            case REFUSED_BY_USER -> "L'utilisateur a ANNULÉ cette action : " + what
                    + ". Ne la redemande pas. Contourne le blocage, ou explique clairement ce qui "
                    + "restera impossible sans elle.";
            case ALREADY_DONE -> "Cette action a déjà été faite : " + what
                    + ". Si le blocage persiste, c'est qu'il a une autre cause — cherche-la.";
        };
    }

    private static String text(JsonNode input, String field) {
        if (input == null || !input.hasNonNull(field)) {
            return "";
        }
        return input.get(field).asText("").strip();
    }

    /** Le résultat rendu à la boucle : le texte lu par l'agent, l'erreur, et l'action concernée. */
    public record Outcome(String content, boolean error, TerminalAction action) {

        static Outcome error(String message) {
            return new Outcome(message, true, null);
        }
    }
}
