package fr.claudegateway.atelier.actions;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.atelier.Workspace;

/**
 * <b>Exécute {@code record_blocker}</b> (F-154 / SF-154-02) : inscrit dans le terminal l'action que
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
        return record(userId, workspace, input);
    }

    /**
     * <b>Propose de fermer</b> l'attente dont l'utilisateur vient de parler (F-175 / SF-175-02 —
     * évolution de F-154 / SF-154-04, qui fermait d'autorité).
     *
     * <p>L'attente reste ouverte : l'utilisateur voit la proposition et répond [Confirmer] ou
     * [Pas encore]. Rien ne sort de sa liste sans son geste.</p>
     */
    public Outcome close(UUID userId, Workspace workspace, JsonNode input) {
        String key = text(input, "key");
        String id = text(input, "id");
        if (key.isEmpty() && id.isEmpty()) {
            return Outcome.error("key (ou id) est requise : celle que montre la liste des attentes.");
        }
        boolean cancelled = input != null && input.hasNonNull("cancelled")
                && input.get("cancelled").asBoolean(false);
        try {
            TerminalActionService.AgentChange change = service.proposeClose(
                    userId, workspace, key, id, text(input, "reason"), cancelled);
            return new Outcome(proposalMessage(change, key.isEmpty() ? id : key, cancelled), false,
                    change.action());
        } catch (InvalidTerminalActionException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Proposition de fermeture d'une attente impossible", e);
            return Outcome.error("La fermeture n'a pas pu être proposée. Dis-le à l'utilisateur dans ta "
                    + "réponse plutôt que de réessayer.");
        }
    }

    /**
     * <b>Fait avancer une attente</b> entre « À faire » et « Demandé » (F-175 / SF-175-02) — après
     * avoir envoyé la demande, ou quand l'utilisateur dit qu'il faut la refaire.
     */
    public Outcome update(UUID userId, Workspace workspace, JsonNode input) {
        String key = text(input, "key");
        String id = text(input, "id");
        if (key.isEmpty() && id.isEmpty()) {
            return Outcome.error("key (ou id) est requise : celle que montre la liste des attentes.");
        }
        TerminalActionStatus status;
        try {
            status = TerminalActionStatus.valueOf(text(input, "status").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Outcome.error("status vaut A_FAIRE ou DEMANDE.");
        }
        try {
            TerminalActionService.AgentChange change = service.agentUpdate(userId, workspace, key, id,
                    status, text(input, "requested_to"), text(input, "channel"));
            return new Outcome(updateMessage(change, key.isEmpty() ? id : key), false, change.action());
        } catch (InvalidTerminalActionException e) {
            return Outcome.error(e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Mise à jour d'une attente impossible", e);
            return Outcome.error("L'attente n'a pas pu être mise à jour. Dis-le à l'utilisateur dans ta "
                    + "réponse plutôt que de réessayer.");
        }
    }

    /** Ce que l'agent lit après une proposition de fermeture. */
    private static String proposalMessage(TerminalActionService.AgentChange change, String ref,
                                          boolean cancelled) {
        return switch (change.outcome()) {
            case PROPOSED -> "Fermeture PROPOSÉE à l'utilisateur" + (cancelled ? " (n'avait pas lieu d'être)" : "")
                    + " : « " + change.action().getDescription() + " ». Elle reste OUVERTE tant qu'il n'a pas "
                    + "confirmé ; il voit [Confirmer] [Pas encore]. Ne dis pas qu'elle est fermée.";
            case ALREADY_CLOSED -> "Cette attente était déjà fermée : « "
                    + change.action().getDescription() + " ». Rien n'a changé.";
            case UNKNOWN -> unknown(ref);
            case CHANGED -> "Rien n'a changé.";
        };
    }

    /** Ce que l'agent lit après un changement d'état. */
    private static String updateMessage(TerminalActionService.AgentChange change, String ref) {
        return switch (change.outcome()) {
            case CHANGED -> change.action().getStatus() == TerminalActionStatus.DEMANDE
                    ? "Attente passée à « Demandé » : « " + change.action().getDescription() + " »"
                            + requestedSuffix(change.action()) + ". On attend la réponse ; ne redemande pas."
                    : "Attente revenue à « À faire » : « " + change.action().getDescription() + " ».";
            case ALREADY_CLOSED -> "Cette attente est fermée : « " + change.action().getDescription()
                    + " ». Rien n'a changé ; si le blocage revient, dis-le à l'utilisateur.";
            case UNKNOWN -> unknown(ref);
            case PROPOSED -> "Rien n'a changé.";
        };
    }

    private static String unknown(String ref) {
        return "Aucune attente de ce terminal ni de son poste ne répond à « " + ref + " ». "
                + "N'insiste pas : reprends la clé ou l'id exactement tels que la liste des attentes les montre.";
    }


    private Outcome record(UUID userId, Workspace workspace, JsonNode input) {
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
            case ALREADY_REQUESTED -> "C'est DÉJÀ DEMANDÉ : " + what + requestedSuffix(recording.action())
                    + ". N'en inscris pas une seconde et ne redemande pas : on attend la réponse. "
                    + "Si l'attente dure, propose une relance à l'utilisateur.";
            case REFUSED_BY_USER -> "L'utilisateur a ANNULÉ cette action : " + what
                    + ". Ne la redemande pas. Contourne le blocage, ou explique clairement ce qui "
                    + "restera impossible sans elle.";
            case ALREADY_DONE -> "Cette action a déjà été faite : " + what
                    + ". Si le blocage persiste, c'est qu'il a une autre cause — cherche-la.";
        };
    }

    /** « — demandé à Zahi le 30/09 par Teams » : ce qui évite de redemander. */
    static String requestedSuffix(TerminalAction action) {
        StringBuilder out = new StringBuilder();
        if (action.getRequestedTo() != null) {
            out.append(" — demandé à ").append(action.getRequestedTo());
        } else {
            out.append(" — demandé");
        }
        if (action.getRequestedAt() != null) {
            out.append(" le ").append(action.getRequestedAt()
                    .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM")));
        }
        if (action.getChannel() != null) {
            out.append(" par ").append(action.getChannel());
        }
        return out.toString();
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
