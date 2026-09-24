package fr.claudegateway.bilan;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Les bilans, côté écran</b> (F-155 / SF-155-04) : en produire un à la demande, les lister, en
 * ouvrir un.
 *
 * <p>La garde d'administration est posée <b>avant</b>, par le contrôleur, avec la définition unique
 * ({@code AdminService.assertAdmin}). Ici, c'est l'isolation {@code user_id} qui règne.</p>
 */
@Service
public class SessionBilanService {

    private static final Logger log = LoggerFactory.getLogger(SessionBilanService.class);

    private final SessionBilanStore store;
    private final SessionLedgerService ledgers;
    private final SessionSuggestionService suggestions;
    private final WorkspaceService workspaces;
    private final ObjectMapper json;

    public SessionBilanService(SessionBilanStore store, SessionLedgerService ledgers,
                               SessionSuggestionService suggestions, WorkspaceService workspaces,
                               ObjectMapper json) {
        this.store = store;
        this.ledgers = ledgers;
        this.suggestions = suggestions;
        this.workspaces = workspaces;
        this.json = json;
    }

    /**
     * Produit et garde le bilan de la session <b>en cours</b> d'un projet — le « proposé d'un clic ».
     *
     * <p>Une session <b>sans matière</b> ne crée <b>aucun</b> bilan : garder une page vide ferait
     * croire, à la relecture, qu'il s'est passé quelque chose.</p>
     *
     * @return le bilan gardé, ou vide s'il n'y avait rien à dire
     */
    @Transactional
    public java.util.Optional<SessionBilanView> produce(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaces.requireOwned(userId, workspaceId); // 404 — toujours en premier

        OffsetDateTime from = workspace.getChatThreadStartedAt() != null
                ? workspace.getChatThreadStartedAt() : workspace.getCreatedAt();
        SessionLedger ledger = ledgers.of(userId, workspaceId, from, OffsetDateTime.now());
        if (ledger.isEmpty()) {
            return java.util.Optional.empty();
        }
        SessionSuggestionService.Verdict verdict = suggestions.examine(ledger);
        if (verdict.isClean() && verdict.discarded() == 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(SessionBilanView.from(
                store.keep(userId, workspaceId, workspace.getName(), "MANUEL", ledger, verdict)));
    }

    /** Les bilans du compte, les plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<SessionBilanView> list(UUID userId) {
        return store.list(userId).stream().map(SessionBilanView::from).toList();
    }

    /** Un bilan ouvert — ou introuvable. */
    @Transactional(readOnly = true)
    public SessionBilanDetail open(UUID userId, UUID id) {
        SessionBilan bilan = store.require(userId, id);
        return new SessionBilanDetail(SessionBilanView.from(bilan),
                read(bilan.getLedgerJson(), new TypeReference<SessionLedger>() { }),
                readList(bilan.getSuggestionsJson()));
    }

    /**
     * Relit une photographie. Une photographie illisible ne doit pas empêcher d'ouvrir le bilan :
     * les chiffres de tête, eux, sont en colonnes et restent justes.
     */
    private <T> T read(String raw, TypeReference<T> type) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return json.readValue(raw, type);
        } catch (IOException e) {
            log.warn("Photographie de bilan illisible", e);
            return null;
        }
    }

    private List<SessionSuggestion> readList(String raw) {
        List<SessionSuggestion> read = read(raw, new TypeReference<List<SessionSuggestion>>() { });
        return read == null ? List.of() : read;
    }
}
