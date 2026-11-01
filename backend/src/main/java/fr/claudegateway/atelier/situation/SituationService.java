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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
import fr.claudegateway.atelier.autonomy.ChantierLanesService;
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
    /** Les derniers messages utilisateur relus pour trouver le dernier écrit par lui (SF-225-03). */
    static final int USER_LOOKBACK = 20;
    /**
     * La marque des demandes AUTOMATIQUES enregistrées au rôle utilisateur : course autonome (F-219,
     * {@code AutonomyTurnRunner.header…}) et routine (F-180, {@code RoutineTurnRunner.demandOf}). Elles ne sont
     * pas une activité de l'utilisateur et ne remettent pas l'inactivité à zéro.
     */
    static final String AUTOMATIC_MARK = "sans écran ouvert";
    /** Ouverture et fermeture de l'essentiel (F-126), tolérantes comme à l'écran. */
    private static final Pattern ESSENTIAL_OPEN = Pattern.compile("<<\\s*essentiel\\s*>>", Pattern.CASE_INSENSITIVE);
    private static final Pattern ESSENTIAL_CLOSE =
            Pattern.compile("<<\\s*/\\s*essentiel\\s*>>", Pattern.CASE_INSENSITIVE);

    private final WorkspaceService workspaces;
    private final SubjectJourneyService journeys;
    private final TerminalActionService actions;
    private final MessageBlockSendService sends;
    private final AtelierMessageRepository messages;
    private final AtelierDepositedFileRepository deposits;
    private final ChantierLanesService lanes;
    private final Clock clock;

    public SituationService(WorkspaceService workspaces, SubjectJourneyService journeys, TerminalActionService actions,
            MessageBlockSendService sends, AtelierMessageRepository messages, AtelierDepositedFileRepository deposits,
            ChantierLanesService lanes, Clock clock) {
        this.workspaces = workspaces;
        this.journeys = journeys;
        this.actions = actions;
        this.sends = sends;
        this.messages = messages;
        this.deposits = deposits;
        this.lanes = lanes;
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
        // SF-225-03 : l'inactivité court depuis le dernier message ÉCRIT PAR L'UTILISATEUR — une course autonome qui
        // a répondu cette nuit ne fait pas croire qu'il était là.
        OffsetDateTime lastUser = lastUserMessage(userId, workspaceId);
        Long idleHours = lastUser == null ? null : Math.max(0L, Duration.between(lastUser, now).toHours());

        SubjectJourney journey = journeys.forTurn(userId, workspaceId);
        Map<String, WaitInfo> waits = journeys.waitInfos(userId, workspace, journey);
        SubjectJourneyResponse view = SubjectJourneyResponse.fromWaits(journey, waits, 0, false);
        Integer focus = journey != null && journey.hasOpenFocus() ? journey.getChantierNumber() : null;

        return new Situation(now, last, idleHours, view.mode(), chantiers(view), attentes(userId, workspaceId),
                envois(userId, workspaceId, workspace, now), exchange(recent), pieces(userId, workspaceId),
                suites(recent), lastUser, displayedLaneLive(userId, workspaceId, focus));
    }

    /** Le dernier message écrit par l'utilisateur, demandes automatiques (courses, routines) écartées. */
    private OffsetDateTime lastUserMessage(UUID userId, UUID workspaceId) {
        for (AtelierMessage m : messages.findTop20ByWorkspaceIdAndUserIdAndRoleOrderByCreatedAtDesc(workspaceId,
                userId, "USER")) {
            if (!isAutomatic(m.getContent())) {
                return m.getCreatedAt();
            }
        }
        return null;
    }

    /** Vrai pour une demande enregistrée au rôle utilisateur mais rédigée par la passerelle (course, routine). */
    static boolean isAutomatic(String content) {
        if (content == null) {
            return false;
        }
        String head = content.length() <= 1_500 ? content : content.substring(0, 1_500);
        return head.contains(AUTOMATIC_MARK);
    }

    /**
     * Un tour tourne dans le fil que l'écran principal montre — le fil commun ou le chantier au focus (tour vivant
     * ici ou chez un pair, course autonome) : la carte ne s'ouvre pas d'elle-même par-dessus (SF-225-03).
     * Best-effort : une lecture en échec rend {@code false}.
     */
    private boolean displayedLaneLive(UUID userId, UUID workspaceId, Integer focus) {
        try {
            for (ChantierLanesService.Lane lane : lanes.lanes(userId, workspaceId).lanes()) {
                if (lane.chantier() == null || lane.chantier().equals(focus)) {
                    return true;
                }
            }
        } catch (RuntimeException e) {
            log.debug("Fils actifs illisibles ({})", e.getClass().getSimpleName());
        }
        return false;
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
                        block.attenteKey(), attenteStatus, excerpt(block.body()), m.getChantierNumber(),
                        m.isViaJarvis()));
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
                request == null ? null : request.getCreatedAt(), reply == null ? null : cutReply(reply.getContent()),
                reply == null ? null : reply.getCreatedAt(), request != null && request.isViaJarvis());
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

    /** Coupe à {@link #MAX_TEXT} caractères, sans jamais séparer une paire de substitution (émoji). */
    static String cut(String text) {
        if (text == null) {
            return null;
        }
        if (text.length() <= MAX_TEXT) {
            return text;
        }
        int end = MAX_TEXT - 1;
        if (Character.isHighSurrogate(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end) + "…";
    }

    /**
     * La dernière réponse coupée à {@link #MAX_TEXT} caractères <b>sans perdre l'essentiel</b> (SF-225-03) : une
     * réponse longue dont le bloc {@code <<essentiel>>} finit au-delà de la coupe est lue à partir de son ouverture —
     * l'écran en tire l'essentiel (F-126) au lieu d'un premier paragraphe de détail ou d'une balise orpheline.
     */
    static String cutReply(String text) {
        if (text == null || text.length() <= MAX_TEXT) {
            return text;
        }
        Matcher open = ESSENTIAL_OPEN.matcher(text);
        if (open.find() && open.start() > 0) {
            Matcher close = ESSENTIAL_CLOSE.matcher(text);
            int closeEnd = close.find(open.end()) ? close.end() : text.length();
            if (closeEnd > MAX_TEXT - 1) {
                return cut(text.substring(open.start()));
            }
        }
        return cut(text);
    }
}
