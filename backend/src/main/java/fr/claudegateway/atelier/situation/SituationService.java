package fr.claudegateway.atelier.situation;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.actions.TerminalAction;
import fr.claudegateway.atelier.actions.TerminalActionService;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.journey.SubjectJourney;
import fr.claudegateway.atelier.journey.SubjectJourneyResponse;
import fr.claudegateway.atelier.journey.SubjectJourneyService;
import fr.claudegateway.atelier.journey.WaitInfo;
import fr.claudegateway.atelier.sends.MessageBlockSend;
import fr.claudegateway.atelier.sends.MessageBlockSendService;
import fr.claudegateway.atelier.sends.MessageBlocks;

/**
 * <b>Le point de situation d'un terminal</b> (F-225 / SF-225-01) — « où on en était » en une lecture : les chantiers
 * ouverts et leur étape, les attentes, les encarts mail ou message des 7 derniers jours (envoyés ou non), la dernière
 * demande et la dernière réponse, les pièces fournies, les suites proposées.
 *
 * <p><b>Aucun appel modèle</b> : une composition déterministe de lectures existantes (parcours F-176/F-206, attentes
 * F-175, envois F-211, dépôts F-169, suites F-192). <b>Isolation</b> : {@code requireOwned} d'abord (404 chez
 * autrui), puis chaque lecture sous {@code user_id}.</p>
 */
@Service
public class SituationService {

    private static final Logger log = LoggerFactory.getLogger(SituationService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** La fenêtre des encarts mail/message relus. */
    static final int SENDS_DAYS = 7;
    /** Au plus autant d'éléments par liste. */
    static final int MAX_ITEMS = 30;
    /** Au plus autant de caractères de la dernière demande / de la dernière réponse. */
    static final int MAX_TEXT = 4_000;

    private final WorkspaceService workspaces;
    private final SubjectJourneyService journeys;
    private final TerminalActionService actions;
    private final MessageBlockSendService sends;
    private final AtelierMessageRepository messages;
    private final AtelierDepositedFileRepository deposits;
    private final Clock clock;

    public SituationService(WorkspaceService workspaces, SubjectJourneyService journeys, TerminalActionService actions,
            MessageBlockSendService sends, AtelierMessageRepository messages, AtelierDepositedFileRepository deposits,
            Clock clock) {
        this.workspaces = workspaces;
        this.journeys = journeys;
        this.actions = actions;
        this.sends = sends;
        this.messages = messages;
        this.deposits = deposits;
        this.clock = clock;
    }

    /** Le point de situation du terminal {@code workspaceId}. 404 sur le terminal d'autrui. */
    @Transactional(readOnly = true)
    public Situation situation(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        OffsetDateTime now = OffsetDateTime.now(clock);

        List<AtelierMessage> recent = new ArrayList<>(
                messages.findTop12ByWorkspaceIdAndUserIdOrderByCreatedAtDesc(workspaceId, userId));
        OffsetDateTime last = recent.isEmpty() ? null : recent.get(0).getCreatedAt();
        Long idleHours = last == null ? null : Math.max(0L, Duration.between(last, now).toHours());

        SubjectJourney journey = journeys.forTurn(userId, workspaceId);
        Map<String, WaitInfo> waits = journeys.waitInfos(userId, workspace, journey);
        SubjectJourneyResponse view = SubjectJourneyResponse.fromWaits(journey, waits, 0, false);

        return new Situation(now, last, idleHours, view.mode(), chantiers(view), attentes(userId, workspaceId),
                envois(userId, workspaceId, workspace, now), exchange(recent), pieces(userId, workspaceId),
                suites(recent));
    }

    private static List<Situation.ChantierLine> chantiers(SubjectJourneyResponse view) {
        List<Situation.ChantierLine> lines = new ArrayList<>();
        for (SubjectJourneyResponse.OpenChantier c : view.chantiers()) {
            String next = null;
            Integer rank = null;
            if (c.plan() != null) {
                List<SubjectJourneyResponse.Step> steps = c.plan().steps();
                for (int i = 0; i < steps.size(); i++) {
                    String status = steps.get(i).status();
                    if (!"FAIT".equals(status) && !"VERIFIE".equals(status)) {
                        next = steps.get(i).title();
                        rank = i + 1;
                        break;
                    }
                }
            }
            lines.add(new Situation.ChantierLine(c.number(), c.title(), c.phaseLabel(), c.focus(), c.done(),
                    c.total(), rank, next, c.awaitingValidation(), c.diagnosisPending(),
                    c.unblocker() == null ? null : c.unblocker().label(),
                    c.unblocker() == null ? null : c.unblocker().detail(), c.stale()));
        }
        return lines;
    }

    private List<Situation.Attente> attentes(UUID userId, UUID workspaceId) {
        List<Situation.Attente> list = new ArrayList<>();
        for (TerminalAction a : actions.list(userId, workspaceId, true)) {
            if (!a.isOpen() || a.getMergedIntoId() != null || list.size() >= MAX_ITEMS) {
                continue;
            }
            String to = a.getRequestedTo() != null && !a.getRequestedTo().isBlank() ? a.getRequestedTo() : a.getPerson();
            list.add(new Situation.Attente(a.getId(), a.getDescription(), a.getStatus().name(),
                    a.getQui() == null ? null : a.getQui().name(), to,
                    a.getRequestedAt() != null ? a.getRequestedAt() : a.getCreatedAt(), a.getChantierNumber()));
        }
        return list;
    }

    /** Les encarts mail/message des 7 derniers jours, envoyés ou non, avec l'attente liée. Le plus récent d'abord. */
    private List<Situation.Envoi> envois(UUID userId, UUID workspaceId, Workspace workspace, OffsetDateTime now) {
        Map<String, MessageBlockSend> marked = new HashMap<>();
        for (MessageBlockSend s : sends.list(userId, workspaceId)) {
            marked.put(s.getMessageId() + "#" + s.getBlockIndex(), s);
        }
        List<Situation.Envoi> list = new ArrayList<>();
        for (AtelierMessage m : messages.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                workspaceId, userId, now.minusDays(SENDS_DAYS))) {
            if (!"ASSISTANT".equals(m.getRole())) {
                continue;
            }
            for (MessageBlocks.Block block : MessageBlocks.parse(m.getContent())) {
                MessageBlockSend sent = marked.get(m.getId() + "#" + block.index());
                String attenteStatus = null;
                if (block.attenteKey() != null) {
                    try {
                        attenteStatus = actions.statusOfKey(userId, workspace, block.attenteKey())
                                .map(Enum::name).orElse(null);
                    } catch (RuntimeException e) {
                        log.debug("Attente d'un encart illisible ({})", e.getClass().getSimpleName());
                    }
                }
                list.add(new Situation.Envoi(m.getId(), block.index(), block.kind().name(), block.subject(),
                        block.recipient(), m.getCreatedAt(), sent == null ? null : sent.getSentAt(),
                        block.attenteKey(), attenteStatus, excerpt(block.body())));
            }
        }
        Collections.reverse(list);
        return list.size() <= MAX_ITEMS ? list : List.copyOf(list.subList(0, MAX_ITEMS));
    }

    private static Situation.Exchange exchange(List<AtelierMessage> recentDesc) {
        AtelierMessage request = null;
        AtelierMessage reply = null;
        for (AtelierMessage m : recentDesc) {
            if (reply == null && "ASSISTANT".equals(m.getRole())) {
                reply = m;
            }
            if (request == null && "USER".equals(m.getRole())) {
                request = m;
            }
        }
        if (request == null && reply == null) {
            return null;
        }
        return new Situation.Exchange(request == null ? null : cut(request.getContent()),
                request == null ? null : request.getCreatedAt(), reply == null ? null : cut(reply.getContent()),
                reply == null ? null : reply.getCreatedAt());
    }

    private List<Situation.Piece> pieces(UUID userId, UUID workspaceId) {
        List<AtelierDepositedFile> files = deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);
        List<Situation.Piece> list = new ArrayList<>();
        for (int i = files.size() - 1; i >= 0 && list.size() < MAX_ITEMS; i--) {
            AtelierDepositedFile f = files.get(i);
            list.add(new Situation.Piece(f.getId(), f.getPath(), f.getSizeBytes(), f.getCreatedAt(), f.getMessageId()));
        }
        return list;
    }

    /** Les suites proposées par la dernière réponse (F-192), lues dans son relevé. */
    private static List<Situation.Suite> suites(List<AtelierMessage> recentDesc) {
        for (AtelierMessage m : recentDesc) {
            if (!"ASSISTANT".equals(m.getRole())) {
                continue;
            }
            List<Situation.Suite> list = new ArrayList<>();
            if (m.getTerminalJson() != null) {
                try {
                    JsonNode steps = MAPPER.readTree(m.getTerminalJson())
                            .path(fr.claudegateway.atelier.nextsteps.NextStepsBlock.REPORT_FIELD);
                    for (JsonNode s : steps) {
                        String title = s.path("title").asText("");
                        if (!title.isBlank()) {
                            list.add(new Situation.Suite(title, s.path("request").asText(null)));
                        }
                    }
                } catch (com.fasterxml.jackson.core.JsonProcessingException | RuntimeException e) {
                    log.debug("Suites illisibles ({})", e.getClass().getSimpleName());
                }
            }
            return list;
        }
        return List.of();
    }

    /** Le début du texte d'un encart, sur une ligne. */
    static String excerpt(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        String flat = body.strip().replaceAll("\\s+", " ");
        return flat.length() <= 400 ? flat : flat.substring(0, 399) + "…";
    }

    private static String cut(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT - 1) + "…";
    }
}
