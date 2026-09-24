package fr.claudegateway.bilan;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>Garder, lister et relire les bilans</b> (F-155 / SF-155-04).
 *
 * <p><b>Isolation.</b> Toute lecture filtre {@code user_id} ; un bilan d'un autre compte est
 * <b>introuvable</b> — 404, jamais 403.</p>
 */
@Service
public class SessionBilanStore {

    private static final Logger log = LoggerFactory.getLogger(SessionBilanStore.class);

    /** Au-delà, ce n'est plus une liste qu'on compare, c'est une archive. */
    static final int MAX_LISTED = 50;

    private final SessionBilanRepository repository;
    private final ObjectMapper json;

    public SessionBilanStore(SessionBilanRepository repository, ObjectMapper json) {
        this.repository = repository;
        this.json = json;
    }

    /**
     * Garde un bilan.
     *
     * @param origin {@code AUTOMATIQUE} (seuil atteint) ou {@code MANUEL} (demandé d'un clic)
     */
    @Transactional
    public SessionBilan keep(UUID userId, UUID workspaceId, String workspaceName, String origin,
                             SessionLedger ledger, SessionSuggestionService.Verdict verdict) {
        return repository.save(SessionBilan.builder()
                .userId(userId)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .fromAt(ledger.from())
                .toAt(ledger.to())
                .origin(origin)
                .turns(ledger.turns())
                .costEur(ledger.costEur())
                .cacheShare(ledger.cacheShare())
                .suggestionCount(verdict.suggestions().size())
                .discardedCount(verdict.discarded())
                .ledgerJson(write(ledger))
                .suggestionsJson(write(verdict.suggestions()))
                .suggestionKinds(SessionPatternService.kindsColumn(verdict.suggestions()))
                .createdAt(OffsetDateTime.now())
                .build());
    }

    /** Les bilans du compte, les plus récents d'abord. */
    @Transactional(readOnly = true)
    public List<SessionBilan> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, MAX_LISTED));
    }

    /** Un bilan du compte, ou <b>introuvable</b>. */
    @Transactional(readOnly = true)
    public SessionBilan require(UUID userId, UUID id) {
        return repository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new SessionBilanNotFoundException("Bilan introuvable."));
    }

    /** Purge à la suppression du compte. */
    @Transactional
    public int purgeUser(UUID userId) {
        return repository.purgeUser(userId);
    }

    /**
     * Sérialise une photographie.
     *
     * <p>Un échec de sérialisation <b>ne doit pas</b> faire perdre le bilan entier : on garde les
     * chiffres de tête, et la photographie manque. Mieux vaut un bilan incomplet qu'aucun bilan et
     * un nouveau départ en erreur.</p>
     */
    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            log.warn("Photographie du bilan non sérialisable", e);
            return null;
        }
    }
}
