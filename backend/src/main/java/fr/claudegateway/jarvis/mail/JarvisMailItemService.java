package fr.claudegateway.jarvis.mail;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Le cache du courrier, en lecture</b> (F-242 / SF-242-02) — les courriers du compte, en-têtes descellés. Filtre
 * {@code user_id} sur toute lecture ; aucune lecture d'autrui possible. Purge des courriers d'une boîte révoquée et
 * du compte supprimé ({@link JarvisMailPurge}).
 */
@Service
public class JarvisMailItemService implements JarvisMailPurge {

    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** Un courrier en cache, tel que l'écran et les outils le voient. */
    public record ItemView(UUID id, UUID accountId, String account, String from, String fromName, String subject,
            String snippet, OffsetDateTime receivedAt, String triage, String triageReason, boolean hasCalendar,
            boolean bulk, String flags) {
    }

    private final JarvisMailItemRepository items;
    private final JarvisMailAccountRepository accounts;
    private final PortraitSeal seal;
    private final ObjectMapper json = new ObjectMapper();

    public JarvisMailItemService(JarvisMailItemRepository items, JarvisMailAccountRepository accounts,
            PortraitSeal seal) {
        this.items = items;
        this.accounts = accounts;
        this.seal = seal;
    }

    /** Les courriers du compte, les plus récents d'abord ; {@code triage} facultatif. */
    @Transactional(readOnly = true)
    public List<ItemView> list(UUID userId, String triage, Integer limit) {
        int n = limit == null || limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<JarvisMailItem> rows = triage == null || triage.isBlank()
                ? items.findByUserIdOrderByReceivedAtDesc(userId, PageRequest.of(0, n))
                : items.findByUserIdAndTriageOrderByReceivedAtDesc(userId, parseTriage(triage), PageRequest.of(0, n));
        return views(userId, rows);
    }

    /** Un courrier du compte, ou 404. */
    @Transactional(readOnly = true)
    public JarvisMailItem require(UUID userId, UUID itemId) {
        if (userId == null || itemId == null) {
            throw new JarvisMailExceptions.MailNotFoundException();
        }
        return items.findByIdAndUserId(itemId, userId).orElseThrow(JarvisMailExceptions.MailNotFoundException::new);
    }

    /** Les en-têtes descellés d'un courrier. */
    public MailHeaders headers(JarvisMailItem item) {
        try {
            return json.readValue(seal.open(item.getSealed()), MailHeaders.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("scellé illisible", e);
        }
    }

    /** Les vues de lignes déjà filtrées par compte. */
    public List<ItemView> views(UUID userId, List<JarvisMailItem> rows) {
        Map<UUID, String> labels = new HashMap<>();
        for (JarvisMailAccount a : accounts.findByUserIdOrderByCreatedAtAsc(userId)) {
            labels.put(a.getId(), a.getLabel());
        }
        return rows.stream().filter(r -> userId.equals(r.getUserId())).map(r -> view(r, labels.get(r.getAccountId())))
                .toList();
    }

    public ItemView view(JarvisMailItem r, String accountLabel) {
        MailHeaders h = headers(r);
        return new ItemView(r.getId(), r.getAccountId(), accountLabel, h.from(), h.fromName(), h.subject(), h.snippet(),
                r.getReceivedAt(), r.getTriage().name(), r.getTriageReason(), r.isHasCalendar(), r.isBulk(),
                r.getFlags());
    }

    static JarvisMailItem.Triage parseTriage(String raw) {
        try {
            return JarvisMailItem.Triage.valueOf(raw.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Tri inconnu.");
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        items.purgeAccount(userId, accountId);
    }

    @Override
    public void purgeUser(UUID userId) {
        items.purgeUser(userId);
    }
}
