package fr.claudegateway.activity;

import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Configuration du suivi d'activité et de revenu (F-124 / SF-124-01) : le <b>TJM par poste</b> et le
 * <b>mois de départ</b> du cumul par utilisateur.
 *
 * <p>Toute lecture et toute écriture filtrent {@code user_id} ; le TJM d'un poste passe d'abord par
 * {@link RunnerHostService#requireOwned(UUID, UUID)} — un identifiant de poste venu du client ne
 * suffit jamais à écrire dessous.</p>
 */
@Service
public class ActivityConfigService {

    /** Mois de départ par défaut, quand l'utilisateur n'en a réglé aucun (décision PO : septembre 2025). */
    public static final YearMonth DEFAULT_START_MONTH = YearMonth.of(2025, 9);

    /** Bornes du mois de départ : une valeur hors de cette fenêtre est une faute de saisie. */
    private static final YearMonth MIN_START_MONTH = YearMonth.of(2000, 1);
    private static final YearMonth MAX_START_MONTH = YearMonth.of(2100, 12);

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final Pattern MONTH_PATTERN = Pattern.compile("^\\d{4}-(0[1-9]|1[0-2])$");

    private final PosteBillingRepository billingRepository;
    private final ActivitySettingsRepository settingsRepository;
    private final RunnerHostService hostService;

    public ActivityConfigService(PosteBillingRepository billingRepository,
            ActivitySettingsRepository settingsRepository, RunnerHostService hostService) {
        this.billingRepository = billingRepository;
        this.settingsRepository = settingsRepository;
        this.hostService = hostService;
    }

    // ------------------------------------------------------------------ Mois de départ (par user)

    /** Mois de départ du cumul pour cet utilisateur, ou le défaut applicatif si rien n'est réglé. */
    @Transactional(readOnly = true)
    public YearMonth startMonth(UUID userId) {
        return settingsRepository.findByUserId(userId)
                .map(settings -> parseMonth(settings.getStartMonth()))
                .orElse(DEFAULT_START_MONTH);
    }

    /**
     * Fixe le mois de départ du cumul. Le format {@code YYYY-MM} et les bornes sont validés ici, au
     * niveau service : une valeur mal formée est refusée avant toute écriture.
     */
    @Transactional
    public YearMonth setStartMonth(UUID userId, String rawMonth) {
        YearMonth month = requireValidMonth(rawMonth);
        String value = month.format(MONTH_FORMAT);
        settingsRepository.findByUserId(userId)
                .ifPresentOrElse(
                        settings -> settings.setStartMonth(value),
                        () -> settingsRepository.save(ActivitySettings.builder()
                                .userId(userId)
                                .startMonth(value)
                                .build()));
        return month;
    }

    // ------------------------------------------------------------------ TJM (par user + host)

    /** Tous les TJM de l'utilisateur (un par poste ayant un TJM réglé). */
    @Transactional(readOnly = true)
    public List<PosteBilling> rates(UUID userId) {
        return billingRepository.findByUserId(userId);
    }

    /** Les TJM de l'utilisateur, indexés par identifiant de poste — pour le calcul du cumul (SF-124-02). */
    @Transactional(readOnly = true)
    public Map<UUID, Long> ratesByHost(UUID userId) {
        return billingRepository.findByUserId(userId).stream()
                .collect(Collectors.toMap(PosteBilling::getHostId, PosteBilling::getDailyRateCents,
                        (a, b) -> a));
    }

    /** Le TJM d'un poste possédé, ou vide s'il n'a pas de TJM réglé. */
    @Transactional(readOnly = true)
    public java.util.Optional<PosteBilling> rate(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        return billingRepository.findByUserIdAndHostId(userId, hostId);
    }

    /**
     * Fixe le TJM (en centimes d'euro HT) d'un poste possédé. L'appartenance est vérifiée d'abord
     * ({@code requireOwned} → 404 pour un poste d'autrui), puis les bornes du montant.
     */
    @Transactional
    public PosteBilling setRate(UUID userId, UUID hostId, long dailyRateCents) {
        hostService.requireOwned(userId, hostId);
        requireValidRate(dailyRateCents);
        return billingRepository.findByUserIdAndHostId(userId, hostId)
                .map(existing -> {
                    existing.setDailyRateCents(dailyRateCents);
                    return existing;
                })
                .orElseGet(() -> billingRepository.save(PosteBilling.builder()
                        .userId(userId)
                        .hostId(hostId)
                        .dailyRateCents(dailyRateCents)
                        .build()));
    }

    /** Retire le TJM d'un poste possédé. Idempotent : rien à retirer n'est pas une erreur. */
    @Transactional
    public void clearRate(UUID userId, UUID hostId) {
        hostService.requireOwned(userId, hostId);
        billingRepository.findByUserIdAndHostId(userId, hostId)
                .ifPresent(billingRepository::delete);
    }

    // ------------------------------------------------------------------ validation

    private static void requireValidRate(long dailyRateCents) {
        if (dailyRateCents < 0) {
            throw new InvalidActivityConfigException("Le TJM ne peut pas être négatif.");
        }
        if (dailyRateCents > PosteBilling.MAX_DAILY_RATE_CENTS) {
            throw new InvalidActivityConfigException(
                    "Le TJM dépasse la borne autorisée (1 000 000 € HT/jour).");
        }
    }

    private static YearMonth requireValidMonth(String rawMonth) {
        String trimmed = rawMonth == null ? "" : rawMonth.trim();
        if (!MONTH_PATTERN.matcher(trimmed).matches()) {
            throw new InvalidActivityConfigException(
                    "Le mois de départ doit être au format YYYY-MM.");
        }
        YearMonth month = parseMonth(trimmed);
        if (month.isBefore(MIN_START_MONTH) || month.isAfter(MAX_START_MONTH)) {
            throw new InvalidActivityConfigException(
                    "Le mois de départ est hors de la plage autorisée.");
        }
        return month;
    }

    private static YearMonth parseMonth(String value) {
        return YearMonth.parse(value, MONTH_FORMAT);
    }

    /** Petit utilitaire réutilisé par les DTO/services pour formater un mois en {@code YYYY-MM}. */
    public static String formatMonth(YearMonth month) {
        return month.format(MONTH_FORMAT);
    }
}
