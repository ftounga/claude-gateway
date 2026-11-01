package fr.claudegateway.jarvis;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.sends.MessageBlockSend;
import fr.claudegateway.atelier.sends.MessageBlockSendRepository;
import fr.claudegateway.atelier.sends.MessageBlocks;

/**
 * <b>De retour au terminal</b> (F-231 / SF-231-06, D10, D11) — rien à réconcilier : Jarvis écrit dans le fil du
 * sujet. Ce service ne fait que <b>relire</b> ce qui est né à la voix pour le montrer d'un coup d'œil :
 *
 * <ul>
 *   <li>l'encart « Pendant ton absence, via Jarvis » d'un terminal : les demandes dites à la voix depuis la dernière
 *   visite, la phrase de chaque réponse, les attentes créées pendant ce temps et <b>les messages à envoyer</b> ;</li>
 *   <li>le compteur « À envoyer (n) » du tableau du client : les encarts mail ou message de tous ses sujets, pas encore
 *   marqués envoyés (F-211).</li>
 * </ul>
 *
 * <p><b>Aucun appel modèle</b>, aucune écriture. <b>Jarvis n'envoie jamais rien à un tiers</b> : il prépare, le PO
 * envoie (D11). Isolation : {@code requireOwned} d'abord (404 chez autrui), puis chaque lecture sous
 * {@code user_id}.</p>
 */
@Service
public class JarvisReturnService {

    /** Au plus loin, l'encart remonte à autant de jours (la dernière visite peut être ancienne, ou absente). */
    static final int MAX_ABSENCE_DAYS = 14;
    /** Sans dernière visite connue, l'encart regarde les dernières 24 heures. */
    static final int DEFAULT_ABSENCE_HOURS = 24;
    /** La fenêtre du compteur « À envoyer », la même que le point de situation (F-225). */
    static final int TO_SEND_DAYS = 7;
    static final int MAX_ITEMS = 30;
    static final int MAX_TEXT = 300;
    /** L'encart d'absence rend le corps entier (borné) : [Copier] doit copier le message, pas un extrait. */
    static final int MAX_BODY = 8_000;

    private final WorkspaceService workspaces;
    private final WorkspaceRepository workspaceRepository;
    private final AtelierMessageRepository messages;
    private final MessageBlockSendRepository sends;
    private final TerminalActionService actions;
    private final Clock clock;

    public JarvisReturnService(WorkspaceService workspaces, WorkspaceRepository workspaceRepository,
            AtelierMessageRepository messages, MessageBlockSendRepository sends, TerminalActionService actions,
            Clock clock) {
        this.workspaces = workspaces;
        this.workspaceRepository = workspaceRepository;
        this.messages = messages;
        this.sends = sends;
        this.actions = actions;
        this.clock = clock;
    }

    /**
     * Ce que Jarvis a fait sur ce terminal depuis {@code since} (la dernière visite de l'appareil). Vide (aucune
     * demande) si Jarvis n'y a rien fait : l'écran n'affiche alors rien.
     */
    @Transactional(readOnly = true)
    public Absence absence(UUID userId, UUID workspaceId, OffsetDateTime since) {
        Workspace workspace = workspaces.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime from = boundedSince(since, now);
        List<AtelierMessage> recent = messages
                .findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(workspace.getId(), userId, from);

        List<Turn> turns = new ArrayList<>();
        List<ToSend> toSend = new ArrayList<>();
        Map<String, MessageBlockSend> marked = activeSends(userId, List.of(workspace.getId()));
        OffsetDateTime firstVoice = null;
        for (int i = 0; i < recent.size(); i++) {
            AtelierMessage m = recent.get(i);
            if (!m.isViaJarvis()) {
                continue;
            }
            if ("USER".equals(m.getRole())) {
                if (firstVoice == null) {
                    firstVoice = m.getCreatedAt();
                }
                AtelierMessage reply = replyTo(recent, i);
                if (turns.size() < MAX_ITEMS) {
                    turns.add(new Turn(m.getId(), cut(m.getContent()), m.getCreatedAt(), m.getChantierNumber(),
                            reply == null ? null : reply.getId(),
                            reply == null ? null : JarvisReplies.oralLine(reply.getContent())));
                }
            } else if ("ASSISTANT".equals(m.getRole())) {
                toSend.addAll(blocksOf(m, workspace, marked, MAX_BODY));
            }
        }
        if (turns.isEmpty()) {
            return new Absence(from, List.of(), List.of(), List.of());
        }
        List<Attente> attentes = new ArrayList<>();
        for (TerminalAction a : actions.list(userId, workspace.getId(), false)) {
            if (a.getCreatedAt() != null && !a.getCreatedAt().isBefore(firstVoice) && attentes.size() < MAX_ITEMS) {
                attentes.add(new Attente(a.getId(), a.getDescription(), a.getStatus().name(), a.getCreatedAt()));
            }
        }
        return new Absence(from, turns, attentes, cap(toSend));
    }

    /**
     * Les encarts mail ou message des sujets d'un poste (tous, terminal du poste compris), proposés ces 7 derniers
     * jours et <b>pas marqués envoyés</b> — le plus récent d'abord. {@code hostId} absent : tous les sujets du compte.
     */
    @Transactional(readOnly = true)
    public ToSendList toSend(UUID userId, UUID hostId) {
        List<Workspace> subjects = hostId == null ? workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId)
                : workspaceRepository.findByUserIdAndHostId(userId, hostId);
        if (subjects.isEmpty()) {
            return new ToSendList(0, List.of());
        }
        Map<UUID, Workspace> byId = subjects.stream().collect(Collectors.toMap(Workspace::getId, Function.identity()));
        Map<String, MessageBlockSend> marked = activeSends(userId, byId.keySet());
        OffsetDateTime from = OffsetDateTime.now(clock).minusDays(TO_SEND_DAYS);
        List<ToSend> pending = new ArrayList<>();
        for (AtelierMessage m : messages.findByUserIdAndWorkspaceIdInAndRoleAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                userId, byId.keySet(), "ASSISTANT", from)) {
            for (ToSend item : blocksOf(m, byId.get(m.getWorkspaceId()), marked, MAX_TEXT)) {
                if (item.sentAt() == null) {
                    pending.add(item);
                }
            }
        }
        Collections.reverse(pending);
        return new ToSendList(pending.size(), cap(pending));
    }

    private Map<String, MessageBlockSend> activeSends(UUID userId, java.util.Collection<UUID> workspaceIds) {
        Map<String, MessageBlockSend> marked = new HashMap<>();
        for (MessageBlockSend s : sends.findByUserIdAndWorkspaceIdInAndCancelledAtIsNull(userId, workspaceIds)) {
            marked.put(s.getMessageId() + "#" + s.getBlockIndex(), s);
        }
        return marked;
    }

    private static List<ToSend> blocksOf(AtelierMessage m, Workspace workspace, Map<String, MessageBlockSend> marked,
            int maxBody) {
        List<ToSend> list = new ArrayList<>();
        for (MessageBlocks.Block block : MessageBlocks.parse(m.getContent())) {
            MessageBlockSend sent = marked.get(m.getId() + "#" + block.index());
            list.add(new ToSend(m.getWorkspaceId(), workspace == null ? null : workspace.getName(), m.getId(),
                    block.index(), block.kind().name(), block.subject(), block.recipient(), m.getCreatedAt(),
                    sent == null ? null : sent.getSentAt(), body(block.body(), maxBody), m.getChantierNumber(),
                    m.isViaJarvis()));
        }
        return list;
    }

    /** La réponse de l'agent qui suit la demande {@code index}, dans le même fil, ou {@code null}. */
    private static AtelierMessage replyTo(List<AtelierMessage> recent, int index) {
        Integer lane = recent.get(index).getChantierNumber();
        for (int j = index + 1; j < recent.size(); j++) {
            AtelierMessage next = recent.get(j);
            if (!java.util.Objects.equals(lane, next.getChantierNumber())) {
                continue;
            }
            if ("USER".equals(next.getRole())) {
                return null;
            }
            if ("ASSISTANT".equals(next.getRole())) {
                return next;
            }
        }
        return null;
    }

    static OffsetDateTime boundedSince(OffsetDateTime since, OffsetDateTime now) {
        OffsetDateTime floor = now.minusDays(MAX_ABSENCE_DAYS);
        if (since == null) {
            return now.minusHours(DEFAULT_ABSENCE_HOURS);
        }
        if (since.isBefore(floor)) {
            return floor;
        }
        return since.isAfter(now) ? now : since;
    }

    private static <T> List<T> cap(List<T> list) {
        return list.size() <= MAX_ITEMS ? list : List.copyOf(list.subList(0, MAX_ITEMS));
    }

    /** Le corps d'un encart : intact (sauts de ligne compris) jusqu'à {@code max}, sinon un extrait replié. */
    private static String body(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max > MAX_TEXT) {
            String kept = text.strip();
            return kept.length() <= max ? kept : kept.substring(0, max - 1).stripTrailing() + "…";
        }
        return cut(text);
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        String flat = text.strip().replaceAll("\\s+", " ");
        return flat.length() <= MAX_TEXT ? flat : flat.substring(0, MAX_TEXT - 1).stripTrailing() + "…";
    }

    /** L'encart « Pendant ton absence, via Jarvis ». {@code turns} vide : Jarvis n'a rien fait ici. */
    public record Absence(OffsetDateTime since, List<Turn> turns, List<Attente> attentes, List<ToSend> toSend) {
    }

    /** Une demande dite à la voix, et la phrase de sa réponse. */
    public record Turn(UUID messageId, String request, OffsetDateTime at, Integer chantierNumber, UUID replyId,
            String reply) {
    }

    /** Une attente créée depuis la première demande dite à la voix. */
    public record Attente(UUID id, String description, String status, OffsetDateTime createdAt) {
    }

    /**
     * Un encart mail ou message préparé par l'agent. {@code sentAt} {@code null} : pas marqué envoyé — à envoyer.
     * {@code viaJarvis} : préparé dans un tour né à la voix.
     */
    public record ToSend(UUID workspaceId, String workspaceName, UUID messageId, int blockIndex, String kind,
            String subject, String recipient, OffsetDateTime proposedAt, OffsetDateTime sentAt, String excerpt,
            Integer chantierNumber, boolean viaJarvis) {
    }

    /** Le compteur « À envoyer (n) » et ses encarts (au plus 30, le plus récent d'abord). */
    public record ToSendList(int count, List<ToSend> items) {
    }
}
