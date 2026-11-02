package fr.claudegateway.jarvis.pieces;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.atelier.AtelierMessageRepository;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.besoin.AppRequest;
import fr.claudegateway.atelier.besoin.AppRequestAttachment;
import fr.claudegateway.atelier.besoin.AppRequestAttachmentRepository;
import fr.claudegateway.atelier.besoin.AppRequestRepository;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;
import fr.claudegateway.atelier.journey.SubjectJourneyRepository;
import fr.claudegateway.atelier.proof.ProofCapture;
import fr.claudegateway.atelier.proof.ProofCaptureRepository;
import fr.claudegateway.atelier.sends.MessageBlockSend;
import fr.claudegateway.atelier.sends.MessageBlockSendRepository;
import fr.claudegateway.diagrams.DiagramRender;
import fr.claudegateway.diagrams.DiagramRenderService;
import fr.claudegateway.images.GeneratedImage;
import fr.claudegateway.images.GeneratedImageRepository;
import fr.claudegateway.images.GeneratedImageStatus;
import fr.claudegateway.mail.ClientEmail;
import fr.claudegateway.mail.ClientEmailRepository;
import fr.claudegateway.mail.ClientEmailStatus;
import fr.claudegateway.pages.Page;
import fr.claudegateway.pages.PageRepository;
import fr.claudegateway.presentations.Presentation;
import fr.claudegateway.presentations.PresentationRepository;
import fr.claudegateway.runner.channel.RunnerRegistry;
import fr.claudegateway.videos.Video;
import fr.claudegateway.videos.VideoChapter;
import fr.claudegateway.videos.VideoKind;
import fr.claudegateway.videos.VideoRepository;
import fr.claudegateway.videos.VideoSeries;
import fr.claudegateway.videos.VideoSeriesRepository;
import fr.claudegateway.videos.VideoVersion;
import fr.claudegateway.videos.VideoVersionRepository;

/**
 * <b>L'index des pièces</b> (F-237 / SF-237-01, cadrage §4) — un agrégat <b>sans table</b>, en lecture seule, sur les
 * sources déjà en base : dépôts, besoin initial, besoins, captures de preuve, envois marqués, courriels envoyés,
 * pages, images, présentations, vidéos et audio.
 *
 * <p><b>Isolation :</b> chaque source est lue avec {@code user_id} <b>et</b> {@code workspace_id} ; le sujet est
 * d'abord vérifié possédé ({@link WorkspaceService#requireOwned}, 404 sinon). <b>Gateway-First :</b> aucune analyse
 * d'image, aucun appel modèle — seulement des descriptions déjà écrites (cadrage §11).</p>
 */
@Service
public class JarvisPieceService {

    /** Durée de vie du cache par (utilisateur, sujet). */
    static final Duration CACHE_TTL = Duration.ofSeconds(60);
    /** Plafond d'une réponse. */
    public static final int MAX_LIMIT = 200;
    /** Taille par défaut d'une réponse. */
    public static final int DEFAULT_LIMIT = 50;
    /** Sujets parcourus au plus quand aucun n'est désigné. */
    static final int MAX_SUBJECTS = 40;
    /** Entrées du cache au plus (au-delà, le plus ancien sort). */
    private static final int CACHE_ENTRIES = 500;

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.FRENCH);

    /** Une réponse : la page de pièces, le nombre total après filtres, et si elle a été tronquée. */
    public record Result(List<Piece> pieces, int total, boolean truncated) {
    }

    /** Les filtres de lecture — tous facultatifs. */
    public record Filter(Integer chantier, Piece.Category category, Piece.Kind kind, OffsetDateTime since,
            OffsetDateTime until, UUID series, int limit) {

        boolean accepts(Piece p) {
            if (chantier != null && !chantier.equals(p.chantier())) {
                return false;
            }
            if (category != null && category != p.category()) {
                return false;
            }
            if (kind != null && kind != p.kind()) {
                return false;
            }
            if (since != null && p.at() != null && p.at().isBefore(since)) {
                return false;
            }
            if (until != null && p.at() != null && p.at().isAfter(until)) {
                return false;
            }
            if (series != null) {
                Object s = p.meta() == null ? null : p.meta().get("series");
                return series.toString().equals(s);
            }
            return true;
        }
    }

    private record Cached(Instant at, List<Piece> pieces) {
    }

    private record Key(UUID userId, UUID workspaceId) {
    }

    private final WorkspaceService workspaces;
    private final AtelierMessageRepository messages;
    private final SubjectJourneyRepository journeys;
    private final AtelierDepositedFileRepository deposits;
    private final AppRequestRepository requests;
    private final AppRequestAttachmentRepository attachments;
    private final ProofCaptureRepository proofs;
    private final MessageBlockSendRepository sends;
    private final ClientEmailRepository emails;
    private final PageRepository pages;
    private final GeneratedImageRepository images;
    private final PresentationRepository presentations;
    private final VideoRepository videos;
    private final VideoVersionRepository videoVersions;
    private final VideoSeriesRepository videoSeries;
    private final RunnerRegistry runners;
    private final DiagramRenderService diagrams;
    private final Clock clock;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<Key, Cached> cache = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Key, Cached> eldest) {
            return size() > CACHE_ENTRIES;
        }
    };

    @SuppressWarnings("java:S107") // un agrégat lit, par définition, toutes ses sources
    public JarvisPieceService(WorkspaceService workspaces, AtelierMessageRepository messages,
            SubjectJourneyRepository journeys, AtelierDepositedFileRepository deposits, AppRequestRepository requests,
            AppRequestAttachmentRepository attachments, ProofCaptureRepository proofs, MessageBlockSendRepository sends, ClientEmailRepository emails,
            PageRepository pages, GeneratedImageRepository images, PresentationRepository presentations,
            VideoRepository videos, VideoVersionRepository videoVersions, VideoSeriesRepository videoSeries,
            RunnerRegistry runners, DiagramRenderService diagrams, Clock clock) {
        this.workspaces = workspaces;
        this.messages = messages;
        this.journeys = journeys;
        this.deposits = deposits;
        this.requests = requests;
        this.attachments = attachments;
        this.proofs = proofs;
        this.sends = sends;
        this.emails = emails;
        this.pages = pages;
        this.images = images;
        this.presentations = presentations;
        this.videos = videos;
        this.videoVersions = videoVersions;
        this.videoSeries = videoSeries;
        this.runners = runners;
        this.diagrams = diagrams;
        this.clock = clock;
    }

    /**
     * Les pièces du sujet {@code subjectId} (404 si ce n'est pas le sien), ou de tous les sujets du compte s'il est
     * {@code null}, filtrées puis bornées.
     */
    @Transactional(readOnly = true)
    public Result list(UUID userId, UUID subjectId, Filter filter) {
        List<Workspace> scope = subjectId != null
                ? List.of(workspaces.requireOwned(userId, subjectId)) // 404 — TOUJOURS en premier
                : workspaces.list(userId).stream().limit(MAX_SUBJECTS).toList();
        List<Piece> all = new ArrayList<>();
        for (Workspace w : scope) {
            all.addAll(cached(userId, w));
        }
        List<Piece> kept = all.stream().filter(filter::accepts).sorted(ORDER).toList();
        int limit = Math.max(1, Math.min(filter.limit(), MAX_LIMIT));
        return new Result(kept.size() > limit ? kept.subList(0, limit) : kept, kept.size(), kept.size() > limit);
    }

    /** L'ordre : catégorie, besoin initial en tête de ses entrées, puis date croissante. */
    static final Comparator<Piece> ORDER = Comparator.comparing(Piece::category)
            .thenComparing(p -> p.id().startsWith("need:") ? 0 : 1)
            .thenComparing(Piece::at, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(Piece::id);

    private List<Piece> cached(UUID userId, Workspace workspace) {
        Key key = new Key(userId, workspace.getId());
        Instant now = clock.instant();
        synchronized (cache) {
            Cached hit = cache.get(key);
            if (hit != null && Duration.between(hit.at(), now).compareTo(CACHE_TTL) < 0) {
                return hit.pieces();
            }
        }
        List<Piece> built = List.copyOf(build(userId, workspace));
        synchronized (cache) {
            cache.put(key, new Cached(now, built));
        }
        return built;
    }

    /** Oublie le cache d'un sujet (une pièce vient de naître). */
    public void invalidate(UUID userId, UUID workspaceId) {
        synchronized (cache) {
            cache.remove(new Key(userId, workspaceId));
        }
    }

    private List<Piece> build(UUID userId, Workspace workspace) {
        UUID ws = workspace.getId();
        List<AtelierMessage> chantierMessages =
                messages.findTop2000ByWorkspaceIdAndUserIdAndChantierNumberIsNotNullOrderByCreatedAtAsc(ws, userId);
        Integer openChantier = null;
        OffsetDateTime openSince = null;
        var journey = journeys.findByUserIdAndWorkspaceId(userId, ws);
        if (journey.isPresent()) {
            openChantier = journey.get().getChantierNumber();
            openSince = journey.get().getChantierOpenedAt();
        }
        PieceChantierIndex index = new PieceChantierIndex(chantierMessages.stream()
                .map(m -> new PieceChantierIndex.ChantierMessage(m.getId(), m.getChantierNumber(), m.getCreatedAt(),
                        concat(m.getToolTrace(), m.getTerminalJson())))
                .toList(), openChantier, openSince);

        List<Piece> out = new ArrayList<>();
        initialNeeds(ws, chantierMessages, out);
        deposits(userId, workspace, index, out);
        requests(userId, ws, index, out);
        proofs(userId, ws, index, out);
        sends(userId, ws, index, out);
        emails(userId, ws, index, out);
        pages(userId, ws, index, out);
        images(userId, ws, index, out);
        presentations(userId, ws, index, out);
        videos(userId, ws, index, out);
        diagrams(userId, ws, index, out);
        return out;
    }

    /** E2 — le premier message de l'utilisateur de chaque chantier : la demande d'origine. */
    private void initialNeeds(UUID ws, List<AtelierMessage> chantierMessages, List<Piece> out) {
        Map<Integer, AtelierMessage> first = new LinkedHashMap<>();
        for (AtelierMessage m : chantierMessages) {
            if ("USER".equalsIgnoreCase(m.getRole()) && m.getContent() != null && !m.getContent().isBlank()) {
                first.putIfAbsent(m.getChantierNumber(), m);
            }
        }
        for (AtelierMessage m : first.values()) {
            out.add(new Piece("need:" + m.getId(), Piece.Category.ENTREE, Piece.Kind.TEXT,
                    "Besoin initial du chantier " + m.getChantierNumber(),
                    PieceCaptions.clip(m.getContent(), PieceCaptions.MAX_CAPTION), Piece.CaptionSource.MESSAGE,
                    m.getCreatedAt(), ws, m.getChantierNumber(), Piece.ChantierLink.MESSAGE, null, null, null,
                    Piece.Availability.ONLINE));
        }
    }

    /** E1 — les pièces collées ou déposées dans le terminal. */
    private void deposits(UUID userId, Workspace workspace, PieceChantierIndex index, List<Piece> out) {
        UUID ws = workspace.getId();
        List<AtelierDepositedFile> rows = deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, ws);
        if (rows.isEmpty()) {
            return;
        }
        Set<UUID> messageIds = rows.stream().map(AtelierDepositedFile::getMessageId).filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<UUID, String> texts = new HashMap<>();
        if (!messageIds.isEmpty()) {
            for (AtelierMessage m : messages.findByWorkspaceIdAndUserIdAndIdIn(ws, userId, messageIds)) {
                texts.put(m.getId(), m.getContent());
            }
        }
        Map<UUID, Integer> positions = new HashMap<>();
        Piece.Availability available = availability(workspace);
        for (AtelierDepositedFile d : rows) {
            int position = d.getMessageId() == null ? 0 : positions.merge(d.getMessageId(), 1, Integer::sum) - 1;
            String name = fileName(d.getPath());
            PieceCaptions.Caption caption = PieceCaptions.deposit(texts.get(d.getMessageId()), position);
            Piece.Kind kind = kindOf(name);
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("sizeBytes", d.getSizeBytes());
            meta.put("name", name);
            String content = "/workspaces/" + ws + "/deposits/" + d.getId() + "/content";
            PieceChantierIndex.Link link = index.link(d.getMessageId(), d.getCreatedAt(), d.getId().toString());
            out.add(new Piece("dep:" + d.getId(), Piece.Category.ENTREE, kind,
                    PieceCaptions.clip(name + " — " + DATE.format(d.getCreatedAt()), PieceCaptions.MAX_TITLE),
                    caption == null ? null : caption.text(), caption == null ? null : caption.source(),
                    d.getCreatedAt(), ws, link.chantier(), link.link(), null, content, meta, available));
        }
    }

    /** E3 — les besoins notés (F-205), décrits par leur reformulation. */
    private void requests(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        List<AppRequest> rows = requests.findTop50ByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, ws);
        if (rows.isEmpty()) {
            return;
        }
        Map<UUID, List<AppRequestAttachment>> joinedByRequest = attachments
                .findByRequestIdInAndUserIdOrderBySeqAsc(rows.stream().map(AppRequest::getId).toList(), userId)
                .stream().collect(Collectors.groupingBy(AppRequestAttachment::getRequestId));
        for (AppRequest r : rows) {
            PieceCaptions.Caption caption = PieceCaptions.first(
                    PieceCaptions.of(r.getReformulation(), Piece.CaptionSource.REFORMULATION),
                    PieceCaptions.of(r.getDescription(), Piece.CaptionSource.DESCRIPTION),
                    PieceCaptions.of(r.getTitle(), Piece.CaptionSource.TITLE));
            PieceChantierIndex.Link link = index.link(r.getMessageId(), r.getCreatedAt(), r.getId().toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("status", r.getStatus());
            List<AppRequestAttachment> joined = joinedByRequest.getOrDefault(r.getId(), List.of()).stream()
                    .filter(a -> AppRequestAttachment.COPIED.equals(a.getCopyStatus())).toList();
            if (!joined.isEmpty()) {
                meta.put("attachments", joined.stream().map(a -> Map.<String, Object>of("name",
                        a.getName() == null ? "piece" : a.getName(), "open",
                        "/jarvis/pieces/req:" + r.getId() + "/attachments/" + a.getSeq())).toList());
            }
            out.add(new Piece("req:" + r.getId(), Piece.Category.ENTREE, Piece.Kind.TEXT,
                    PieceCaptions.clip(r.getTitle(), PieceCaptions.MAX_TITLE), caption == null ? null : caption.text(),
                    caption == null ? null : caption.source(), r.getCreatedAt(), ws, link.chantier(), link.link(),
                    null, null, meta, Piece.Availability.ONLINE));
        }
    }

    /** P1 — les captures de preuve (F-212) : titre, étape prouvée, bilan des commandes. */
    private void proofs(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (ProofCapture c : proofs.findTop200ByUserIdAndWorkspaceIdOrderByCapturedAtAscPositionAsc(userId, ws)) {
            String hex = c.getId().toString().replace("-", "");
            List<String> statuses = new ArrayList<>();
            List<Integer> codes = new ArrayList<>();
            readCommands(c.getCommandsJson(), statuses, codes);
            PieceCaptions.Caption caption = PieceCaptions.proof(c.getTitle(), c.getStep(), statuses, codes);
            PieceChantierIndex.Link link = index.link(null, c.getCapturedAt(), c.getCallId(), hex);
            String image = "/workspaces/" + ws + "/proofs/" + hex;
            String thumb = thumbOf("proof:" + hex);
            Map<String, Object> meta = new LinkedHashMap<>();
            if (c.getStep() != null) {
                meta.put("step", c.getStep());
            }
            meta.put("position", c.getPosition());
            if (c.getWidth() != null) {
                meta.put("width", c.getWidth());
            }
            if (c.getHeight() != null) {
                meta.put("height", c.getHeight());
            }
            meta.put("commands", statuses.size());
            out.add(new Piece("proof:" + hex, Piece.Category.PREUVE, Piece.Kind.IMAGE,
                    PieceCaptions.clip(c.getTitle() == null ? "Preuve" : c.getTitle(), PieceCaptions.MAX_TITLE),
                    caption.text(), caption.source(), c.getCapturedAt(), ws, link.chantier(), link.link(), thumb, image,
                    meta, Piece.Availability.ONLINE));
        }
    }

    /** P2 — les blocs marqués « envoyé » (F-211). */
    private void sends(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (MessageBlockSend s : sends.findByUserIdAndWorkspaceIdAndCancelledAtIsNullOrderBySentAtAsc(userId, ws)) {
            PieceCaptions.Caption caption = PieceCaptions.sent(s.getSubject(), s.getRecipient(), s.getExcerpt());
            PieceChantierIndex.Link link = index.link(s.getMessageId(), s.getSentAt());
            String title = s.getSubject() != null && !s.getSubject().isBlank() ? "Envoi : " + s.getSubject()
                    : "Envoi marqué";
            out.add(new Piece("send:" + s.getId(), Piece.Category.PREUVE, Piece.Kind.TEXT,
                    PieceCaptions.clip(title, PieceCaptions.MAX_TITLE), caption == null ? null : caption.text(),
                    caption == null ? null : caption.source(), s.getSentAt(), ws, link.chantier(), link.link(), null,
                    null, null, Piece.Availability.ONLINE));
        }
    }

    /** P3 — les courriels envoyés au client : objet, destinataire, début du corps <b>texte</b>. */
    private void emails(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (ClientEmail e : emails.findTop50ByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId, ws,
                ClientEmailStatus.SENT)) {
            OffsetDateTime at = e.getSentAt() != null ? e.getSentAt() : e.getCreatedAt();
            String excerpt = e.getBodyText() == null ? null : PieceCaptions.clip(e.getBodyText(), 200);
            PieceCaptions.Caption caption = PieceCaptions.sent(e.getSubject(), e.getRecipient(), excerpt);
            PieceChantierIndex.Link link = index.link(null, at, e.getId().toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("attachments", e.getAttachmentCount());
            out.add(new Piece("mail:" + e.getId(), Piece.Category.PREUVE, Piece.Kind.TEXT,
                    PieceCaptions.clip("Courriel : " + e.getSubject(), PieceCaptions.MAX_TITLE),
                    caption == null ? null : caption.text(), caption == null ? null : caption.source(), at, ws,
                    link.chantier(), link.link(), null, "/jarvis/pieces/mail:" + e.getId() + "/text", meta,
                    Piece.Availability.ONLINE));
        }
    }

    /** R1 — les pages publiées (F-109). */
    private void pages(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (Page p : pages.findTop20ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(userId, ws)) {
            PieceCaptions.Caption caption = PieceCaptions.first(
                    PieceCaptions.of(p.getDescription(), Piece.CaptionSource.DESCRIPTION),
                    PieceCaptions.of(p.getTitle(), Piece.CaptionSource.TITLE));
            PieceChantierIndex.Link link = index.link(null, p.getCreatedAt(), p.getId().toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", p.getCurrentVersion());
            meta.put("pdf", "/pages/" + p.getId() + "/pdf");
            out.add(new Piece("page:" + p.getId() + "@v" + p.getCurrentVersion(), Piece.Category.PRODUCTION,
                    Piece.Kind.PAGE, PieceCaptions.clip(p.getTitle(), PieceCaptions.MAX_TITLE),
                    caption == null ? null : caption.text(), caption == null ? null : caption.source(),
                    p.getUpdatedAt(), ws, link.chantier(), link.link(), null, "/pages/" + p.getId(), meta,
                    Piece.Availability.ONLINE));
        }
    }

    /** R4 — les images générées prêtes, décrites par leur prompt. */
    private void images(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (GeneratedImage i : images.findTop50ByUserIdAndWorkspaceIdAndStatusOrderByCreatedAtDesc(userId, ws,
                GeneratedImageStatus.READY)) {
            String path = "/generated-images/" + i.getId() + "/image";
            PieceChantierIndex.Link link = index.link(null, i.getCreatedAt(), i.getId().toString());
            out.add(new Piece("img:" + i.getId(), Piece.Category.PRODUCTION, Piece.Kind.IMAGE,
                    PieceCaptions.clip("Image : " + i.getPrompt(), 60),
                    PieceCaptions.clip(i.getPrompt(), PieceCaptions.MAX_PROMPT), Piece.CaptionSource.PROMPT,
                    i.getCreatedAt(), ws, link.chantier(), link.link(), thumbOf("img:" + i.getId()), path, null,
                    Piece.Availability.ONLINE));
        }
    }

    /** R5 — les présentations : la diapo 1 pour vignette. */
    private void presentations(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (Presentation p : presentations.findTop50ByUserIdAndWorkspaceIdOrderByUpdatedAtDesc(userId, ws)) {
            Integer slides = p.getSlideCount();
            PieceCaptions.Caption caption = PieceCaptions.first(
                    PieceCaptions.of(p.getDescription(), Piece.CaptionSource.DESCRIPTION),
                    PieceCaptions.of(slides == null ? p.getTitle() : p.getTitle() + " — " + slides + " diapos",
                            Piece.CaptionSource.TITLE));
            PieceChantierIndex.Link link = index.link(null, p.getCreatedAt(), p.getId().toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            if (slides != null) {
                meta.put("slides", slides);
            }
            String thumb = slides != null && slides > 0 ? thumbOf("slides:" + p.getId()) : null;
            out.add(new Piece("slides:" + p.getId(), Piece.Category.PRODUCTION, Piece.Kind.SLIDES,
                    PieceCaptions.clip(p.getTitle(), PieceCaptions.MAX_TITLE), caption == null ? null : caption.text(),
                    caption == null ? null : caption.source(), p.getUpdatedAt(), ws, link.chantier(), link.link(),
                    thumb, "/presentations/" + p.getId(), meta, Piece.Availability.ONLINE));
        }
    }

    /** R6 / R7 — les vidéos, cours et audios : description, série, épisode, chapitres. */
    private void videos(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        List<Video> rows = videos.findByUserIdAndWorkspaceId(userId, ws);
        if (rows.isEmpty()) {
            return;
        }
        Map<UUID, VideoSeries> series = new HashMap<>();
        for (VideoSeries s : videoSeries.findByUserIdAndWorkspaceId(userId, ws)) {
            series.put(s.getId(), s);
        }
        for (Video v : rows) {
            VideoSeries s = v.getSeriesId() == null ? null : series.get(v.getSeriesId());
            VideoVersion version = videoVersions.findByVideoIdAndUserIdAndVersion(v.getId(), userId,
                    v.getCurrentVersion()).orElse(null);
            List<VideoChapter> chapters = version == null ? List.of() : VideoChapter.fromJson(version.getChaptersJson());
            String fallback = (s == null ? "" : s.getTitle() + (v.getEpisode() == null ? "" : ", épisode "
                    + v.getEpisode()) + " — ") + v.getTitle();
            PieceCaptions.Caption caption = PieceCaptions.first(
                    PieceCaptions.of(v.getDescription(), Piece.CaptionSource.DESCRIPTION),
                    PieceCaptions.of(fallback, Piece.CaptionSource.TITLE));
            PieceChantierIndex.Link link = index.link(null, v.getCreatedAt(), v.getId().toString());
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("version", v.getCurrentVersion());
            if (v.getDurationS() != null) {
                meta.put("durationS", v.getDurationS());
            }
            if (s != null) {
                meta.put("series", s.getId().toString());
                meta.put("seriesTitle", s.getTitle());
            }
            if (v.getEpisode() != null) {
                meta.put("episode", v.getEpisode());
            }
            if (!chapters.isEmpty()) {
                meta.put("chapters", chapters.stream()
                        .map(c -> Map.<String, Object>of("t", c.t(), "title", c.titre())).toList());
            }
            String pieceId = "video:" + v.getId() + "@v" + v.getCurrentVersion();
            String thumb = version != null && version.getPosterKey() != null ? thumbOf(pieceId) : null;
            out.add(new Piece(pieceId, Piece.Category.PRODUCTION,
                    v.getKind() == VideoKind.AUDIO ? Piece.Kind.AUDIO : Piece.Kind.VIDEO,
                    PieceCaptions.clip(v.getTitle(), PieceCaptions.MAX_TITLE), caption == null ? null : caption.text(),
                    caption == null ? null : caption.source(), v.getCreatedAt(), ws, link.chantier(), link.link(),
                    thumb, "/videos/" + v.getId() + "/versions/" + v.getCurrentVersion() + "/media", meta,
                    Piece.Availability.ONLINE));
        }
    }

    /**
     * R3 — les schémas conservés (SF-237-03) : ARCHITECTURE (icônes cloud, draw.io) ou DIAGRAM (Mermaid), décrits par
     * le titre de la spec et ses groupes de premier niveau.
     */
    private void diagrams(UUID userId, UUID ws, PieceChantierIndex index, List<Piece> out) {
        for (DiagramRender d : diagrams.list(userId, ws)) {
            String pieceId = "diag:" + d.getId();
            PieceChantierIndex.Link link = index.link(null, d.getCreatedAt(), d.getCallId(), d.getId().toString());
            if (link.chantier() == null && d.getChantierNumber() != null) {
                link = new PieceChantierIndex.Link(d.getChantierNumber(), Piece.ChantierLink.WINDOW);
            }
            List<String> groups = topGroups(d.getSpecJson());
            PieceCaptions.Caption caption = PieceCaptions.first(
                    PieceCaptions.of(groups.isEmpty() ? null
                            : d.getTitle() + " — " + String.join(", ", groups), Piece.CaptionSource.SPEC),
                    PieceCaptions.of(d.getTitle(), Piece.CaptionSource.TITLE));
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("engine", d.getEngine());
            if (!groups.isEmpty()) {
                meta.put("groups", groups);
            }
            if (d.getSpecJson() != null) {
                meta.put("spec", "/jarvis/pieces/" + pieceId + "/spec");
            }
            String image = d.getSvgKey() != null || d.getPngKey() != null ? "/jarvis/pieces/" + pieceId + "/image"
                    : null;
            out.add(new Piece(pieceId, Piece.Category.PRODUCTION,
                    d.isArchitecture() ? Piece.Kind.ARCHITECTURE : Piece.Kind.DIAGRAM,
                    PieceCaptions.clip(d.getTitle() == null ? "Schéma" : d.getTitle(), PieceCaptions.MAX_TITLE),
                    caption == null ? null : caption.text(), caption == null ? null : caption.source(),
                    d.getCreatedAt(), ws, link.chantier(), link.link(),
                    d.getPngKey() != null ? thumbOf(pieceId) : null, image, meta, Piece.Availability.ONLINE));
        }
    }

    /** Les libellés des groupes de premier niveau (sans parent) d'une spec — au plus 6. */
    List<String> topGroups(String specJson) {
        List<String> labels = new ArrayList<>();
        if (specJson == null || specJson.isBlank()) {
            return labels;
        }
        try {
            JsonNode groups = mapper.readTree(specJson).path("groups");
            if (groups.isArray()) {
                for (JsonNode g : groups) {
                    String parent = g.path("parent").asText("");
                    String label = g.path("label").asText("");
                    if (parent.isBlank() && !label.isBlank() && labels.size() < 6) {
                        labels.add(label.strip());
                    }
                }
            }
        } catch (Exception e) {
            labels.clear(); // spec illisible : le titre suffit
        }
        return labels;
    }

    /** Le chemin passerelle de la vignette réduite (SF-237-02). */
    static String thumbOf(String pieceId) {
        return "/jarvis/pieces/" + pieceId + "/thumb";
    }

    private Piece.Availability availability(Workspace workspace) {
        if (!workspace.isRunnerTarget()) {
            return Piece.Availability.ONLINE;
        }
        UUID host = workspace.getHostId();
        return host != null && runners.isConnected(host) ? Piece.Availability.ONLINE : Piece.Availability.HOST_OFFLINE;
    }

    private void readCommands(String json, List<String> statuses, List<Integer> codes) {
        if (json == null || json.isBlank()) {
            return;
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node != null && node.isArray()) {
                for (JsonNode c : node) {
                    statuses.add(c.path("status").isMissingNode() ? null : c.path("status").asText(null));
                    codes.add(c.path("exitCode").isNumber() ? c.path("exitCode").asInt() : null);
                }
            }
        } catch (Exception e) {
            statuses.clear(); // relevé illisible : la pièce reste, sans le bilan
            codes.clear();
        }
    }

    /** Le type d'un dépôt, par son extension. */
    static Piece.Kind kindOf(String name) {
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        String ext = dot < 0 ? "" : n.substring(dot + 1);
        return switch (ext) {
            case "png", "jpg", "jpeg", "gif", "webp", "svg" -> Piece.Kind.IMAGE;
            case "mp4", "webm", "mov" -> Piece.Kind.VIDEO;
            case "m4a", "mp3", "wav", "ogg" -> Piece.Kind.AUDIO;
            case "pdf" -> Piece.Kind.PDF;
            default -> Piece.Kind.FILE;
        };
    }

    private static String fileName(String path) {
        String clean = path == null ? "piece" : path.replace('\\', '/');
        return clean.substring(clean.lastIndexOf('/') + 1);
    }

    private static String concat(String a, String b) {
        if (a == null) {
            return b;
        }
        return b == null ? a : a + "\n" + b;
    }
}
