package fr.claudegateway.jarvis.mail;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Le « oui » vérifié côté serveur</b> (F-242 / SF-242-06, D9 c) — une action qui engage (envoyer, supprimer, archiver
 * en masse) est créée {@code PENDING} par l'outil d'Opus, expire au bout de 10 min, et n'est exécutée <b>que</b> si le
 * <b>message suivant de l'utilisateur dans ce fil Jarvis</b> est un « oui » de la liste fermée
 * ({@link MailConfirmation}). Ni un texte du modèle, ni un résultat d'outil, ni un courriel ne peuvent confirmer : la
 * passerelle ne lit que le message persisté de l'utilisateur. Une seule action en attente à la fois ; toute autre
 * réponse l'annule. Un « oui » répété n'exécute rien deux fois ({@code UPDATE} conditionnel PENDING → CONFIRMED).
 */
@Service
public class JarvisMailActionService implements JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailActionService.class);

    public static final Duration TTL = Duration.ofMinutes(10);

    /** Le contenu d'une action (scellé). */
    public record Payload(JarvisMailWriter.Draft draft, List<MailRef> refs) {
    }

    private final JarvisMailActionRepository actions;
    private final JarvisMailWriter writer;
    private final PortraitSeal seal;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public JarvisMailActionService(JarvisMailActionRepository actions, JarvisMailWriter writer, PortraitSeal seal,
            Clock clock) {
        this.actions = actions;
        this.writer = writer;
        this.seal = seal;
        this.clock = clock;
    }

    /** Propose une action : la précédente en attente de ce fil est annulée (une seule question à la fois). */
    public JarvisMailAction propose(UUID userId, UUID workspaceId, UUID accountId, JarvisMailAction.Kind kind,
            Payload payload, String summary) {
        actions.findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId, workspaceId,
                JarvisMailAction.Status.PENDING).ifPresent(previous -> actions.close(previous.getId(), userId,
                        JarvisMailAction.Status.CANCELLED));
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisMailAction a = new JarvisMailAction();
        a.setUserId(userId);
        a.setWorkspaceId(workspaceId);
        a.setAccountId(accountId);
        a.setKind(kind);
        a.setPayload(seal.seal(write(payload)));
        a.setSummary(summary.length() <= 600 ? summary : summary.substring(0, 600));
        a.setStatus(JarvisMailAction.Status.PENDING);
        a.setCreatedAt(now);
        a.setExpiresAt(now.plus(TTL));
        return actions.save(a);
    }

    /**
     * Le message de l'utilisateur dans le fil Jarvis : s'il suit une action en attente, il la confirme (« oui » net) ou
     * l'annule (tout le reste). Rend la note jointe au message d'Opus, ou {@code ""} sans action en attente.
     */
    public String onUserMessage(UUID userId, UUID workspaceId, UUID messageId, String text) {
        return onUserMessage(userId, workspaceId, messageId, text, false);
    }

    /**
     * F-232 / SF-232-06 (D8) — même règle, et téléphone du compagnon <b>verrouillé</b> : un « oui » net n'exécute rien,
     * l'action reste en attente (son échéance court toujours) et la note fixe le dit. Seul un « oui » d'une session
     * déverrouillée l'exécute.
     */
    public String onUserMessage(UUID userId, UUID workspaceId, UUID messageId, String text, boolean phoneLocked) {
        if (userId == null || workspaceId == null) {
            return "";
        }
        JarvisMailAction pending = actions.findFirstByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId,
                workspaceId, JarvisMailAction.Status.PENDING).orElse(null);
        if (pending == null) {
            return "";
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!pending.getExpiresAt().isAfter(now)) {
            actions.close(pending.getId(), userId, JarvisMailAction.Status.EXPIRED);
            return "[Courrier — l'action en attente (« " + pending.getSummary() + " ») a expiré sans « oui » : rien "
                    + "n'est parti. Propose-la à nouveau si l'utilisateur le veut.]";
        }
        if (MailConfirmation.classify(text) != MailConfirmation.Answer.OUI) {
            actions.close(pending.getId(), userId, JarvisMailAction.Status.CANCELLED);
            return "[Courrier — pas de « oui » net de l'utilisateur : l'action « " + pending.getSummary()
                    + " » est annulée, rien n'est fait. Traite son message tel quel.]";
        }
        if (phoneLocked) {
            return "[Courrier — « oui » reçu téléphone verrouillé : rien n'est fait, l'action « " + pending.getSummary()
                    + " » reste en attente. Dis seulement qu'il faut déverrouiller le téléphone pour confirmer.]";
        }
        if (actions.confirm(pending.getId(), userId, messageId, now) != 1) {
            return "";
        }
        return "[Courrier — confirmé par l'utilisateur : " + execute(pending) + "]";
    }

    /** Exécute une action confirmée (une seule fois). */
    String execute(JarvisMailAction action) {
        JarvisMailAction a = actions.findById(action.getId()).orElse(action);
        try {
            Payload p = read(seal.open(a.getPayload()));
            String done = switch (a.getKind()) {
                case SEND -> {
                    writer.send(a.getUserId(), a.getAccountId(), p.draft());
                    yield "envoyé — " + a.getSummary();
                }
                case DELETE -> writer.move(a.getUserId(), a.getAccountId(), p.refs(), true)
                        + " courriel(s) mis à la corbeille — " + a.getSummary();
                case ARCHIVE_BULK -> writer.move(a.getUserId(), a.getAccountId(), p.refs(), false)
                        + " courriel(s) archivé(s) — " + a.getSummary();
            };
            a.setStatus(JarvisMailAction.Status.EXECUTED);
            a.setExecutedAt(OffsetDateTime.now(clock));
            actions.save(a);
            return done;
        } catch (RuntimeException e) {
            log.info("Courrier : action en échec ({})", e.getClass().getSimpleName());
            a.setStatus(JarvisMailAction.Status.FAILED);
            a.setError(e instanceof JarvisMailExceptions.MailException me ? clip(me.getMessage()) : "échec");
            actions.save(a);
            return "ÉCHEC, rien n'est parti (" + (a.getError() == null ? "échec" : a.getError()) + ") — "
                    + a.getSummary();
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        actions.purgeAccount(userId, accountId);
    }

    @Override
    public void purgeUser(UUID userId) {
        actions.purgeUser(userId);
    }

    private String write(Payload p) {
        try {
            return json.writeValueAsString(p);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("action illisible", e);
        }
    }

    private Payload read(String raw) {
        try {
            return json.readValue(raw, Payload.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("action illisible", e);
        }
    }

    private static String clip(String s) {
        return s == null ? null : s.length() <= 300 ? s : s.substring(0, 300);
    }
}
