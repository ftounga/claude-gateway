package fr.claudegateway.atelier.besoin;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.deposit.AtelierDepositedFile;
import fr.claudegateway.atelier.deposit.AtelierDepositedFileRepository;

/**
 * <b>Le registre des besoins de l'application</b> (F-205 / SF-205-01).
 *
 * <p>{@link #propose} — l'outil {@code signaler_besoin} — range le besoin au statut {@code PROPOSE} et
 * rend la carte : rien n'entre au registre dans le dos de l'utilisateur. {@link #record} — le clic
 * [Enregistrer] — le passe {@code EN_ATTENTE} et confie la copie des pièces au
 * {@link AppRequestAttachmentCopier}, après validation de la transaction.</p>
 *
 * <p><b>Isolation</b> : côté terminal, le projet vient de {@code requireOwned} et le besoin se relit par
 * {@code id + user_id + workspace_id} ; côté administration, par {@code id + user_id} de l'appelant. Les
 * pièces ne peuvent être que des <b>dépôts</b> de ce terminal (filtrés {@code user_id + workspace_id}) :
 * l'outil ne sert pas à emporter un fichier quelconque. Le contenu n'est jamais journalisé.</p>
 */
@Service
public class AppRequestService {

    static final int MAX_TITLE_CHARS = 200;
    static final int MAX_TEXT_CHARS = 8_000;
    static final int MAX_PIECES = 10;
    static final Pattern FEATURE_REF = Pattern.compile("^F-\\d{1,4}$");
    /** Statuts qu'un administrateur peut poser. {@code PROPOSE} reste réservé à la carte. */
    static final Set<String> ADMIN_STATUSES = Set.of(AppRequest.EN_ATTENTE, AppRequest.CADRE, AppRequest.REJETE,
            AppRequest.LIVRE);
    static final Set<String> ALL_STATUSES = Set.of(AppRequest.PROPOSE, AppRequest.EN_ATTENTE, AppRequest.CADRE,
            AppRequest.REJETE, AppRequest.LIVRE);

    private final AppRequestRepository requests;
    private final AppRequestAttachmentRepository attachments;
    private final AtelierDepositedFileRepository deposits;
    private final WorkspaceService workspaceService;
    private final AppRequestAttachmentCopier copier;

    public AppRequestService(AppRequestRepository requests, AppRequestAttachmentRepository attachments,
            AtelierDepositedFileRepository deposits, WorkspaceService workspaceService,
            AppRequestAttachmentCopier copier) {
        this.requests = requests;
        this.attachments = attachments;
        this.deposits = deposits;
        this.workspaceService = workspaceService;
        this.copier = copier;
    }

    // ------------------------------------------------------------------ proposer (outil)

    /**
     * Range un besoin {@code PROPOSE} et rend sa carte.
     *
     * @param workspace le terminal du tour, déjà chargé par {@code requireOwned}
     * @param messageId le message utilisateur du tour, ou {@code null}
     * @throws AppRequestInvalidException avec le message destiné à l'agent
     */
    @Transactional
    public AppRequestBlock propose(UUID userId, Workspace workspace, UUID messageId, String rawKind,
            String rawTitle, String rawDescription, String rawReformulation, String rawContext,
            List<String> rawPieces) {
        String kind = rawKind == null ? "" : rawKind.strip().toUpperCase(Locale.ROOT);
        if (!AppRequest.BUG.equals(kind) && !AppRequest.EVOLUTION.equals(kind)) {
            throw new AppRequestInvalidException("type doit valoir BUG ou EVOLUTION.");
        }
        String title = strip(rawTitle);
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS) {
            throw new AppRequestInvalidException("titre obligatoire, " + MAX_TITLE_CHARS + " caractères au plus.");
        }
        String description = strip(rawDescription);
        if (description.isEmpty()) {
            throw new AppRequestInvalidException("description obligatoire : les mots de l'utilisateur, tels quels.");
        }
        if (description.length() > MAX_TEXT_CHARS) {
            throw new AppRequestInvalidException("description trop longue (" + MAX_TEXT_CHARS
                    + " caractères au plus).");
        }
        List<AtelierDepositedFile> pieces = resolvePieces(userId, workspace.getId(), rawPieces);

        OffsetDateTime now = OffsetDateTime.now();
        AppRequest saved = requests.save(AppRequest.builder()
                .userId(userId).workspaceId(workspace.getId()).messageId(messageId)
                .kind(kind).title(title).description(description)
                .reformulation(optional(rawReformulation)).observedContext(optional(rawContext))
                .status(AppRequest.PROPOSE).createdAt(now).updatedAt(now)
                .build());
        List<AppRequestBlock.Piece> shown = new ArrayList<>();
        int seq = 1;
        for (AtelierDepositedFile deposit : pieces) {
            String name = fileName(deposit.getPath());
            String contentType = contentTypeOf(name);
            attachments.save(AppRequestAttachment.builder()
                    .requestId(saved.getId()).userId(userId).seq(seq).name(name).contentType(contentType)
                    .sizeBytes(deposit.getSizeBytes()).sourcePath(deposit.getPath()).createdAt(now)
                    .build());
            shown.add(new AppRequestBlock.Piece(seq, name, contentType, deposit.getSizeBytes()));
            seq++;
        }
        return new AppRequestBlock(saved.getId(), kind, title, description, saved.getReformulation(),
                saved.getObservedContext(), List.copyOf(shown));
    }

    /**
     * Les dépôts désignés, dans l'ordre donné, sans doublon. Un chemin peut être donné complet
     * ({@code entrees/x.png}, {@code .atelier/entrees/x.png}) ou par son seul nom.
     */
    private List<AtelierDepositedFile> resolvePieces(UUID userId, UUID workspaceId, List<String> rawPieces) {
        if (rawPieces == null || rawPieces.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> wanted = new LinkedHashSet<>();
        for (String raw : rawPieces) {
            String path = strip(raw);
            while (path.startsWith("./")) {
                path = path.substring(2);
            }
            if (!path.isEmpty()) {
                wanted.add(path);
            }
        }
        if (wanted.size() > MAX_PIECES) {
            throw new AppRequestInvalidException("pieces : " + MAX_PIECES + " fichiers au plus.");
        }
        if (wanted.isEmpty()) {
            return List.of();
        }
        List<AtelierDepositedFile> all = deposits.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);
        Map<String, AtelierDepositedFile> byPath = all.stream()
                .collect(Collectors.toMap(AtelierDepositedFile::getPath, Function.identity(), (a, b) -> b));
        List<AtelierDepositedFile> out = new ArrayList<>();
        List<String> unknown = new ArrayList<>();
        Set<String> seen = new java.util.HashSet<>();
        for (String path : wanted) {
            AtelierDepositedFile found = byPath.get(path);
            if (found == null && !path.contains("/")) {
                found = all.stream().filter(d -> fileName(d.getPath()).equals(path)).reduce((a, b) -> b)
                        .orElse(null);
            }
            if (found == null) {
                unknown.add(path);
            } else if (seen.add(found.getPath())) {
                out.add(found);
            }
        }
        if (!unknown.isEmpty()) {
            throw new AppRequestInvalidException("pieces : " + String.join(", ", unknown)
                    + " n'est pas un fichier déposé dans ce terminal. Seuls les dépôts (captures, vidéos "
                    + "glissées dans le terminal) peuvent être joints.");
        }
        return out;
    }

    // ------------------------------------------------------------------ décider (terminal)

    @Transactional(readOnly = true)
    public AppRequestView get(UUID userId, UUID workspaceId, UUID requestId) {
        workspaceService.requireOwned(userId, workspaceId);
        return view(requireInWorkspace(userId, workspaceId, requestId));
    }

    /** [Enregistrer] : le besoin entre au registre ; les pièces sont copiées en arrière-plan. */
    @Transactional
    public AppRequestView record(UUID userId, UUID workspaceId, UUID requestId) {
        workspaceService.requireOwned(userId, workspaceId);
        AppRequest request = requireInWorkspace(userId, workspaceId, requestId);
        requireProposed(request);
        OffsetDateTime now = OffsetDateTime.now();
        request.setStatus(AppRequest.EN_ATTENTE);
        request.setRecordedAt(now);
        request.setUpdatedAt(now);
        requests.save(request);
        List<AppRequestAttachment> pieces = attachments.findByRequestIdAndUserIdOrderBySeqAsc(requestId, userId);
        for (AppRequestAttachment piece : pieces) {
            piece.setCopyStatus(AppRequestAttachment.PENDING);
            attachments.save(piece);
        }
        if (!pieces.isEmpty()) {
            afterCommit(() -> copier.copyAsync(userId, requestId));
        }
        return AppRequestView.of(request, pieces);
    }

    /** [Non] : le besoin est écarté, rien n'est copié. */
    @Transactional
    public AppRequestView dismiss(UUID userId, UUID workspaceId, UUID requestId) {
        workspaceService.requireOwned(userId, workspaceId);
        AppRequest request = requireInWorkspace(userId, workspaceId, requestId);
        requireProposed(request);
        request.setStatus(AppRequest.REJETE);
        request.setUpdatedAt(OffsetDateTime.now());
        requests.save(request);
        return view(request);
    }

    // ------------------------------------------------------------------ administration

    /**
     * Les besoins de l'appelant, plus récents d'abord. Sans statut : tout sauf {@code PROPOSE}.
     *
     * @throws AppRequestInvalidException statut inconnu
     */
    @Transactional(readOnly = true)
    public List<AppRequestView> list(UUID userId, String rawStatus) {
        Set<String> statuses;
        if (rawStatus == null || rawStatus.isBlank()) {
            statuses = ADMIN_STATUSES;
        } else {
            String status = rawStatus.strip().toUpperCase(Locale.ROOT);
            if (!ALL_STATUSES.contains(status)) {
                throw new AppRequestInvalidException("statut inconnu : " + status);
            }
            statuses = Set.of(status);
        }
        List<AppRequest> found = requests.findByUserIdAndStatusInOrderByCreatedAtDesc(userId, statuses);
        if (found.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AppRequestAttachment>> byRequest = attachments
                .findByRequestIdInAndUserIdOrderBySeqAsc(found.stream().map(AppRequest::getId).toList(), userId)
                .stream().collect(Collectors.groupingBy(AppRequestAttachment::getRequestId));
        return found.stream().map(r -> AppRequestView.of(r, byRequest.getOrDefault(r.getId(), List.of()))).toList();
    }

    @Transactional(readOnly = true)
    public AppRequestView detail(UUID userId, UUID requestId) {
        return view(require(userId, requestId));
    }

    /**
     * Change le statut et/ou la référence de feature. {@code null} = inchangé ; {@code featureRef} vide =
     * effacée.
     */
    @Transactional
    public AppRequestView update(UUID userId, UUID requestId, String rawStatus, String rawFeatureRef) {
        AppRequest request = require(userId, requestId);
        if (AppRequest.PROPOSE.equals(request.getStatus())) {
            throw new AppRequestConflictException("Ce besoin n'a pas encore été enregistré depuis le terminal.");
        }
        if (rawStatus != null) {
            String status = rawStatus.strip().toUpperCase(Locale.ROOT);
            if (!ADMIN_STATUSES.contains(status)) {
                throw new AppRequestInvalidException("statut : EN_ATTENTE, CADRE, REJETE ou LIVRE.");
            }
            request.setStatus(status);
        }
        if (rawFeatureRef != null) {
            String ref = rawFeatureRef.strip().toUpperCase(Locale.ROOT);
            if (ref.isEmpty()) {
                request.setFeatureRef(null);
            } else if (FEATURE_REF.matcher(ref).matches()) {
                request.setFeatureRef(ref);
            } else {
                throw new AppRequestInvalidException("featureRef : de la forme F-123.");
            }
        }
        request.setUpdatedAt(OffsetDateTime.now());
        requests.save(request);
        return view(request);
    }

    /** Une pièce copiée et ses octets. */
    public record AttachmentContent(String name, String contentType, byte[] bytes) {
    }

    /**
     * Les octets de la pièce {@code position} d'un besoin de l'appelant.
     *
     * @throws AppRequestNotFoundException besoin ou pièce inconnus, ou pièce pas (encore) copiée
     */
    @Transactional(readOnly = true)
    public AttachmentContent attachment(UUID userId, UUID requestId, int position) {
        require(userId, requestId);
        AppRequestAttachment piece = attachments.findByRequestIdAndUserIdAndSeq(requestId, userId, position)
                .orElseThrow(() -> new AppRequestNotFoundException("Pièce introuvable."));
        if (!AppRequestAttachment.COPIED.equals(piece.getCopyStatus()) || piece.getStorageKey() == null) {
            throw new AppRequestNotFoundException("Pièce pas encore copiée ("
                    + (piece.getCopyStatus() == null ? "besoin non enregistré" : piece.getCopyStatus()) + ").");
        }
        byte[] bytes = copier.read(piece.getStorageKey())
                .orElseThrow(() -> new AppRequestNotFoundException("Pièce absente du stockage."));
        return new AttachmentContent(piece.getName(), piece.getContentType(), bytes);
    }

    /**
     * Suppression du compte : les pièces copiées (au mieux), puis les pièces et les besoins. Appelé dans
     * la transaction de {@code AccountService.deleteAccount}.
     */
    @Transactional
    public void purgeUser(UUID userId) {
        copier.deleteCopies(requests.findIdsByUserId(userId));
        attachments.purgeUser(userId);
        requests.purgeUser(userId);
    }

    // ------------------------------------------------------------------ internes

    private AppRequest requireInWorkspace(UUID userId, UUID workspaceId, UUID requestId) {
        return requests.findByIdAndUserIdAndWorkspaceId(requestId, userId, workspaceId)
                .orElseThrow(() -> new AppRequestNotFoundException("Besoin introuvable."));
    }

    private AppRequest require(UUID userId, UUID requestId) {
        return requests.findByIdAndUserId(requestId, userId)
                .orElseThrow(() -> new AppRequestNotFoundException("Besoin introuvable."));
    }

    private static void requireProposed(AppRequest request) {
        if (!AppRequest.PROPOSE.equals(request.getStatus())) {
            throw new AppRequestConflictException("Ce besoin est déjà "
                    + (AppRequest.REJETE.equals(request.getStatus()) ? "écarté." : "enregistré."));
        }
    }

    private AppRequestView view(AppRequest request) {
        return AppRequestView.of(request,
                attachments.findByRequestIdAndUserIdOrderBySeqAsc(request.getId(), request.getUserId()));
    }

    /** Après validation de la transaction courante (ou tout de suite, hors transaction). */
    private static void afterCommit(Runnable action) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    action.run();
                }
            });
        } else {
            action.run();
        }
    }

    private static String strip(String raw) {
        return raw == null ? "" : raw.strip();
    }

    private static String optional(String raw) {
        String value = strip(raw);
        if (value.isEmpty()) {
            return null;
        }
        return value.length() > MAX_TEXT_CHARS ? value.substring(0, MAX_TEXT_CHARS) : value;
    }

    static String fileName(String path) {
        String p = path == null ? "" : path;
        int slash = p.lastIndexOf('/');
        String name = slash >= 0 ? p.substring(slash + 1) : p;
        return name.length() > 255 ? name.substring(name.length() - 255) : name;
    }

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("heic", "image/heic"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
            Map.entry("m4v", "video/mp4"), Map.entry("pdf", "application/pdf"),
            Map.entry("txt", "text/plain"), Map.entry("log", "text/plain"), Map.entry("md", "text/markdown"),
            Map.entry("json", "application/json"), Map.entry("csv", "text/csv"));

    /** Type déduit de l'extension ; inconnu ⇒ {@code application/octet-stream}. */
    static String contentTypeOf(String name) {
        int dot = name == null ? -1 : name.lastIndexOf('.');
        if (dot < 0) {
            return "application/octet-stream";
        }
        return TYPES.getOrDefault(name.substring(dot + 1).toLowerCase(Locale.ROOT), "application/octet-stream");
    }
}
