package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisAlertView;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchNotFoundException;
import fr.claudegateway.quota.ProviderPricingProperties;

/**
 * <b>Lire les alertes des veilles</b> (F-230 / SF-230-03) : la liste du compte, une alerte, son ouverture. Toute
 * lecture passe par {@code id + user_id} ; le nom de la veille est relu sous le même compte.
 */
@Service
public class JarvisAlertService {

    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final JarvisWatchAlertRepository alerts;
    private final JarvisWatchRepository watches;
    private final JarvisAlertLinks links;
    private final Clock clock;
    private BigDecimal usdToEur = DEFAULT_USD_TO_EUR;

    public JarvisAlertService(JarvisWatchAlertRepository alerts, JarvisWatchRepository watches, JarvisAlertLinks links,
            Clock clock) {
        this.alerts = alerts;
        this.watches = watches;
        this.links = links;
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPricing(ProviderPricingProperties pricing) {
        if (pricing != null && pricing.usdToEur() != null) {
            this.usdToEur = pricing.usdToEur();
        }
    }

    /** Le taux d'affichage en euros (F-224). */
    public BigDecimal usdToEur() {
        return usdToEur;
    }

    @Transactional(readOnly = true)
    public List<JarvisAlertView> recent(UUID userId, Integer limit) {
        int size = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return alerts.findByUserIdOrderByCreatedAtDesc(userId, PageRequest.of(0, size)).stream()
                .map(alert -> view(userId, alert))
                .toList();
    }

    @Transactional(readOnly = true)
    public JarvisAlertView get(UUID userId, UUID alertId) {
        return view(userId, alerts.findByIdAndUserId(alertId, userId).orElseThrow(WatchNotFoundException::new));
    }

    @Transactional
    public void open(UUID userId, UUID alertId) {
        JarvisWatchAlert alert = alerts.findByIdAndUserId(alertId, userId).orElseThrow(WatchNotFoundException::new);
        if (alert.getOpenedAt() == null) {
            alert.setOpenedAt(OffsetDateTime.now(clock));
            alerts.save(alert);
        }
    }

    /** Un montant en dollars, rendu en euros au centime. */
    public BigDecimal eur(BigDecimal usd) {
        return (usd == null ? BigDecimal.ZERO : usd).multiply(usdToEur).setScale(2, RoundingMode.HALF_UP);
    }

    private JarvisAlertView view(UUID userId, JarvisWatchAlert alert) {
        JarvisWatch watch = watches.findByIdAndUserId(alert.getWatchId(), userId).orElse(null);
        return new JarvisAlertView(alert.getId(), alert.getWatchId(), watch == null ? null : watch.getName(),
                watch == null ? null : watch.getPlatform(), alert.getWorkspaceId(), alert.getChantierNumber(),
                alert.getSentence(), changes(alert.getChangesJson()), alert.isAiWritten(),
                alert.getCostUsd() == null ? null : alert.getCostUsd().multiply(usdToEur).setScale(4, RoundingMode.HALF_UP),
                alert.getCreatedAt(), alert.getOpenedAt(), links.linkFor(alert.getId(), alert.getWorkspaceId()));
    }

    private static List<JarvisWatchDiff.Change> changes(String json) {
        try {
            return List.of(MAPPER.readValue(json, JarvisWatchDiff.Change[].class));
        } catch (com.fasterxml.jackson.core.JsonProcessingException | IllegalArgumentException e) {
            return List.of();
        }
    }
}
