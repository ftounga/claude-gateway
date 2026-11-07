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

    /** F-249 — les postes (clients) du compte, pour le libellé « Outlook <client> » d'un courrier déposé. */
    private fr.claudegateway.runner.host.RunnerHostRepository hosts;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setHosts(fr.claudegateway.runner.host.RunnerHostRepository hosts) {
        this.hosts = hosts;
    }

    /** Les vues de lignes déjà filtrées par compte. */
    public List<ItemView> views(UUID userId, List<JarvisMailItem> rows) {
        Map<UUID, String> labels = new HashMap<>();
        for (JarvisMailAccount a : accounts.findByUserIdOrderByCreatedAtAsc(userId)) {
            labels.put(a.getId(), a.getLabel());
        }
        boolean anyDeposit = rows.stream().anyMatch(JarvisMailItem::isDeposited);
        Map<UUID, String> clients = anyDeposit ? clientLabels(userId) : Map.of();
        Map<UUID, List<String>> subjects = anyDeposit ? subjectsOf(userId, rows) : Map.of();
        return rows.stream().filter(r -> userId.equals(r.getUserId()))
                .map(r -> view(r, r.isDeposited() ? depositLabel(clients.getOrDefault(r.getHostId(), DEPOSIT_LABEL),
                        subjects.get(r.getId())) : labels.get(r.getAccountId())))
                .toList();
    }

    /** F-249 / SF-249-03 — « Outlook EDENRED · « Alarme Fortec » » : le client, puis les sujets rattachés. */
    static String depositLabel(String client, List<String> subjects) {
        if (subjects == null || subjects.isEmpty()) {
            return client;
        }
        return client + " · " + String.join(", ", subjects.stream().map(n -> "« " + n + " »").toList());
    }

    /** F-249 / SF-249-03 — les sujets rattachés (résolus à la demande : aucune dépendance au démarrage). */
    private org.springframework.beans.factory.ObjectProvider<DepositedMailLabels> depositedLabels;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDepositedLabels(org.springframework.beans.factory.ObjectProvider<DepositedMailLabels> labels) {
        this.depositedLabels = labels;
    }

    private Map<UUID, List<String>> subjectsOf(UUID userId, List<JarvisMailItem> rows) {
        DepositedMailLabels l = depositedLabels == null ? null : depositedLabels.getIfAvailable();
        if (l == null) {
            return Map.of();
        }
        try {
            return l.subjectsOf(userId, rows.stream().filter(JarvisMailItem::isDeposited).map(JarvisMailItem::getId)
                    .toList());
        } catch (RuntimeException e) {
            return Map.of();
        }
    }

    /** Le libellé d'un courrier déposé dont le poste est inconnu. */
    public static final String DEPOSIT_LABEL = "Outlook";

    /** F-249 — « Outlook EDENRED » pour chaque poste du compte. */
    public Map<UUID, String> clientLabels(UUID userId) {
        Map<UUID, String> out = new HashMap<>();
        if (hosts != null && userId != null) {
            hosts.findByUserIdOrderByCreatedAtDesc(userId)
                    .forEach(h -> out.put(h.getId(), DEPOSIT_LABEL + " " + h.getName()));
        }
        return out;
    }

    /** F-249 — le corps scellé d'un courrier déposé (aucun IMAP ne permet de le relire), ou {@code ""}. */
    public String depositedBody(JarvisMailItem item) {
        if (item == null || !item.isDeposited() || item.getBodySealed() == null) {
            return "";
        }
        return seal.open(item.getBodySealed());
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
