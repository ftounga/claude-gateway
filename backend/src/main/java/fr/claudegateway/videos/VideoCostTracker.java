package fr.claudegateway.videos;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.quota.TurnCost;
import fr.claudegateway.quota.UsageRecordedListener;

/**
 * <b>Le coût réel d'une vidéo</b> (F-216 / SF-216-02) : ce que le journal d'usage inscrit pour un sujet pendant
 * qu'un devis y est ouvert est imputé à ce devis, puis rattaché aux versions publiées.
 *
 * <h2>Le rattachement</h2>
 * <ol>
 *   <li><b>Imputation</b> ({@link #onRecorded}) : tout coût du sujet (tours de l'agent, voix, Plume) va aux versions
 *       <b>en attente</b> s'il y en a (le tour qui les a publiées n'est pas fini), sinon au devis ouvert le plus
 *       récent ({@code spent} et {@code unassigned}).</li>
 *   <li><b>Publication</b> ({@link #attach}) : la version reçoit la part non rattachée de son devis et passe en
 *       attente — le coût du tour qui la publie n'est inscrit qu'à sa fin.</li>
 *   <li><b>Fin de tour</b> ({@link #turnEnded}) : l'attente est levée ; un devis vidéo unique ou retouche qui a
 *       publié est clos, un devis de série l'est quand tous ses épisodes sont publiés.</li>
 * </ol>
 *
 * <p><b>Isolation</b> : tout est lu par {@code (user_id, workspace_id)} de la ligne du journal ou du tour ; un coût
 * d'un compte ne touche jamais le devis d'un autre.</p>
 */
@Component
public class VideoCostTracker implements UsageRecordedListener {

    private static final int SCALE = 6;

    private final VideoQuoteRepository quotes;
    private final VideoVersionRepository versions;
    private final VideoRepository videos;
    private final VideoCostProperties coefficients;
    private final Clock clock;

    public VideoCostTracker(VideoQuoteRepository quotes, VideoVersionRepository versions, VideoRepository videos,
            VideoCostProperties coefficients, Clock clock) {
        this.quotes = quotes;
        this.versions = versions;
        this.videos = videos;
        this.coefficients = coefficients;
        this.clock = clock;
    }

    /** Le taux de lecture en euros (F-224 / SF-224-06) ; absent = 0,92. */
    private BigDecimal usdToEur = VideoQuoteService.DEFAULT_USD_TO_EUR;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPricing(fr.claudegateway.quota.ProviderPricingProperties pricing) {
        this.usdToEur = pricing == null ? VideoQuoteService.DEFAULT_USD_TO_EUR : pricing.usdToEur();
    }

    /** Le taux appliqué aux montants en euros des vidéos. */
    public BigDecimal usdToEur() {
        return usdToEur;
    }

    // ------------------------------------------------------------------ imputation

    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRecorded(UUID userId, UUID workspaceId, TurnCost cost) {
        if (userId == null || workspaceId == null || cost == null || cost.amountUsd() == null
                || cost.amountUsd().signum() <= 0) {
            return;
        }
        BigDecimal amount = cost.amountUsd();
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<VideoQuote> open = quotes.lockOpen(userId, workspaceId);
        List<VideoVersion> pending = versions.findPending(userId, workspaceId);
        if (!pending.isEmpty()) {
            // Le tour qui publie : son coût revient, à parts égales, aux versions qu'il a publiées.
            BigDecimal share = amount.divide(BigDecimal.valueOf(pending.size()), SCALE, RoundingMode.HALF_UP);
            for (VideoVersion version : pending) {
                version.setCostUsd(add(version.getCostUsd(), share));
                versions.save(version);
                videos.findByIdAndUserId(version.getVideoId(), userId).ifPresent(video -> {
                    video.setCostUsd(add(video.getCostUsd(), share));
                    videos.save(video);
                });
                if (version.getQuoteId() != null) {
                    quotes.lockOne(version.getQuoteId(), userId).ifPresent(quote -> {
                        quote.setSpentUsd(add(quote.getSpentUsd(), share));
                        quote.setLastActivityAt(now);
                        quotes.save(quote);
                    });
                }
            }
            return;
        }
        VideoQuote target = firstActive(open, now, null);
        if (target == null) {
            return;
        }
        target.setSpentUsd(add(target.getSpentUsd(), amount));
        target.setUnassignedUsd(add(target.getUnassignedUsd(), amount));
        target.setLastActivityAt(now);
        quotes.save(target);
    }

    // ------------------------------------------------------------------ publication

    /**
     * Rattache une version qui vient d'être inscrite (dans la transaction de la publication). Le devis est la retouche
     * ouverte de <b>cette</b> vidéo s'il y en a une, sinon le devis vidéo ou série ouvert le plus récent du sujet.
     * Sans devis ouvert : rien, le coût de la vidéo reste « non mesuré ».
     */
    public void attach(Video video, VideoVersion version) {
        if (video == null || version == null || video.getKind() != VideoKind.VIDEO || video.getWorkspaceId() == null) {
            return;
        }
        UUID userId = video.getUserId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        VideoQuote quote = firstActive(quotes.lockOpen(userId, video.getWorkspaceId()), now, video.getId());
        if (quote == null) {
            return;
        }
        BigDecimal carried = quote.getUnassignedUsd() == null ? BigDecimal.ZERO : quote.getUnassignedUsd();
        quote.setUnassignedUsd(BigDecimal.ZERO);
        quote.setLastActivityAt(now);
        quotes.save(quote);
        version.setQuoteId(quote.getId());
        version.setCostUsd(carried.setScale(SCALE, RoundingMode.HALF_UP));
        version.setCostPending(true);
        versions.save(version);
        video.setCostUsd(add(video.getCostUsd(), carried));
        if (!quote.getId().equals(video.getLastQuoteId())) {
            BigDecimal divisor = quote.getKind() == VideoQuoteKind.SERIES
                    ? BigDecimal.valueOf(Math.max(1, quote.getEpisodes())) : BigDecimal.ONE;
            video.setEstimateLowUsd(add(video.getEstimateLowUsd(),
                    quote.getLowUsd().divide(divisor, 4, RoundingMode.FLOOR)).setScale(4, RoundingMode.HALF_UP));
            video.setEstimateHighUsd(add(video.getEstimateHighUsd(),
                    quote.getHighUsd().divide(divisor, 4, RoundingMode.CEILING)).setScale(4, RoundingMode.HALF_UP));
            video.setLastQuoteId(quote.getId());
        }
        videos.save(video);
    }

    // ------------------------------------------------------------------ fin de tour

    /** Fin d'un tour de l'agent dans ce sujet : l'attente est levée, les devis achevés sont clos. */
    @Transactional
    public void turnEnded(UUID userId, UUID workspaceId) {
        if (userId == null || workspaceId == null) {
            return;
        }
        List<VideoVersion> pending = versions.findPending(userId, workspaceId);
        if (pending.isEmpty()) {
            return;
        }
        Map<UUID, Boolean> touched = new LinkedHashMap<>();
        for (VideoVersion version : pending) {
            version.setCostPending(false);
            versions.save(version);
            if (version.getQuoteId() != null) {
                touched.put(version.getQuoteId(), Boolean.TRUE);
            }
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        for (UUID quoteId : touched.keySet()) {
            quotes.lockOne(quoteId, userId).ifPresent(quote -> {
                if (quote.getStatus() != VideoQuote.Status.OPEN) {
                    return;
                }
                boolean done = quote.getKind() != VideoQuoteKind.SERIES
                        || versions.countVideosOfQuote(userId, quote.getId()) >= quote.getEpisodes();
                if (done) {
                    close(quote, now);
                }
            });
        }
    }

    // ------------------------------------------------------------------ récapitulatif

    /** Ce que le compte a dépensé en vidéos ce mois-ci. */
    public record MonthCost(String month, BigDecimal spentUsd, long quotes, BigDecimal spentEur) {
    }

    /** Le total du mois courant du compte (devis créés ce mois-ci, plans abandonnés compris). */
    @Transactional(readOnly = true)
    public MonthCost month(UUID userId) {
        ZoneId zone = clock.getZone();
        YearMonth month = YearMonth.now(clock);
        OffsetDateTime since = month.atDay(1).atStartOfDay(zone).toOffsetDateTime();
        BigDecimal spent = quotes.spentSince(userId, since);
        BigDecimal usd = spent == null ? BigDecimal.ZERO : spent;
        return new MonthCost(month.toString(), usd.setScale(2, RoundingMode.HALF_UP),
                quotes.countByUserIdAndCreatedAtGreaterThanEqual(userId, since), VideoGalleryResponse.eur(usd, usdToEur));
    }

    // ------------------------------------------------------------------ outils

    /**
     * Le premier devis actif : clôt au passage ceux qui sont inactifs. {@code videoId} non nul (publication) : une
     * retouche ne compte que pour sa vidéo, sinon le devis vidéo/série le plus récent ; nul (imputation) : le plus
     * récent, quel qu'il soit.
     */
    private VideoQuote firstActive(List<VideoQuote> open, OffsetDateTime now, UUID videoId) {
        OffsetDateTime idleLimit = now.minusHours(coefficients.idleCloseHours());
        VideoQuote fallback = null;
        for (VideoQuote quote : open) {
            OffsetDateTime last = quote.getLastActivityAt() != null ? quote.getLastActivityAt() : quote.getCreatedAt();
            if (last != null && last.isBefore(idleLimit)) {
                close(quote, now);
                continue;
            }
            if (videoId == null) {
                return quote;
            }
            if (quote.getKind() == VideoQuoteKind.RETOUCHE) {
                if (videoId.equals(quote.getVideoId())) {
                    return quote;
                }
            } else if (fallback == null) {
                fallback = quote;
            }
        }
        return fallback;
    }

    private void close(VideoQuote quote, OffsetDateTime now) {
        quote.setStatus(VideoQuote.Status.CLOSED);
        quote.setClosedAt(now);
        quotes.save(quote);
    }

    private static BigDecimal add(BigDecimal base, BigDecimal amount) {
        return (base == null ? BigDecimal.ZERO : base).add(amount).setScale(SCALE, RoundingMode.HALF_UP);
    }
}
