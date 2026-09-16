package fr.claudegateway.activity.cra;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.Normalizer;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.activity.CraEntry;
import fr.claudegateway.activity.CraEntryRepository;
import fr.claudegateway.activity.InvalidActivityConfigException;
import fr.claudegateway.activity.WorkdayCalendar;
import fr.claudegateway.ai.AIProvider;
import fr.claudegateway.ai.ChatCompletionRequest;
import fr.claudegateway.ai.ChatCompletionResult;
import fr.claudegateway.ai.ChatMessage;
import fr.claudegateway.ai.ChatRole;
import fr.claudegateway.ai.ModelCatalog;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * Le <b>CRA par message</b> (F-124 / SF-124-03) : l'utilisateur écrit en langage naturel, le
 * <b>modèle extrait</b> {@code {poste → jours, mois}} (Provider-First, via {@link AIProvider}), et la
 * Gateway <b>rapproche</b> chaque nom à un poste possédé, <b>valide</b> (poste connu, mois valide,
 * jours ≤ jours ouvrés du mois, 0,5 admis), <b>persiste</b> les déclarés (écrase pour un mois) et
 * <b>récapitule</b> ce qu'elle a compris.
 *
 * <p>La Gateway ne réimplémente aucun NLP : elle oriente le modèle (consigne système), puis ne fait
 * confiance qu'à sa propre validation. Un nom non reconnu est <b>rendu tel quel</b> dans le récap
 * (« précisez le poste »), jamais rapproché au hasard.</p>
 *
 * <p>Isolation : le rapprochement ne voit que les postes du user courant, et la persistance filtre
 * {@code (user_id, host_id)}.</p>
 */
@Service
public class CraService {

    /** Borne du message : au-delà, c'est un collage, pas un CRA. */
    public static final int MAX_MESSAGE_LENGTH = 4000;

    private static final DateTimeFormatter MONTH_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM");
    private static final BigDecimal TWO = BigDecimal.valueOf(2);

    private final AIProvider aiProvider;
    private final ModelCatalog modelCatalog;
    private final QuotaService quotaService;
    private final RunnerHostService hostService;
    private final CraEntryRepository craRepository;
    private final CraExtractionParser parser = new CraExtractionParser();

    public CraService(AIProvider aiProvider, ModelCatalog modelCatalog, QuotaService quotaService,
            RunnerHostService hostService, CraEntryRepository craRepository) {
        this.aiProvider = aiProvider;
        this.modelCatalog = modelCatalog;
        this.quotaService = quotaService;
        this.hostService = hostService;
        this.craRepository = craRepository;
    }

    /** Interprète un message de CRA pour le mois courant (horloge système) par défaut. */
    @Transactional
    public CraOutcome submit(UUID userId, String message) {
        return submit(userId, message, YearMonth.now());
    }

    /**
     * Interprète un message de CRA. Le mois par défaut (quand le message n'en précise pas) est
     * {@code defaultMonth} — paramètre pour rendre le calcul déterministe et testable.
     */
    @Transactional
    public CraOutcome submit(UUID userId, String rawMessage, YearMonth defaultMonth) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            throw new InvalidActivityConfigException("Le message de CRA est vide.");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }

        // Quota AVANT l'appel fournisseur (F-10) : à quota atteint, aucun appel réseau.
        quotaService.assertWithinQuota(userId);

        ChatCompletionResult completion = aiProvider.complete(new ChatCompletionRequest(
                modelCatalog.fastModel(),
                List.of(new ChatMessage(ChatRole.USER, message)),
                List.of(),
                null,               // clé plateforme (utilitaire de la Gateway)
                systemPrompt(defaultMonth),
                512));
        quotaService.recordUsage(userId, completion.turnTokens(), null, null, null);

        List<CraExtraction> extracted = parser.parse(completion.content());

        Map<String, RunnerHost> byName = hostsByNormalizedName(userId);
        List<CraLine> lines = new ArrayList<>();
        for (CraExtraction line : extracted) {
            lines.add(resolveAndPersist(userId, line, defaultMonth, byName));
        }
        return new CraOutcome(lines);
    }

    private CraLine resolveAndPersist(UUID userId, CraExtraction extraction, YearMonth defaultMonth,
            Map<String, RunnerHost> byName) {
        String cited = extraction.client();
        RunnerHost host = match(byName, cited);
        if (host == null) {
            return CraLine.unknown(cited);
        }
        YearMonth month = extraction.month() != null ? YearMonth.parse(extraction.month(), MONTH_FORMAT)
                : defaultMonth;

        BigDecimal days = normalizeDays(extraction.days());
        if (days == null || days.signum() <= 0) {
            return CraLine.rejected(cited, host, "Nombre de jours manquant ou invalide.");
        }
        int businessDays = WorkdayCalendar.businessDaysInMonth(month);
        if (days.compareTo(BigDecimal.valueOf(businessDays)) > 0) {
            return CraLine.rejected(cited, host, "Plus de jours (" + strip(days) + ") que de jours ouvrés ("
                    + businessDays + ") en " + month + ".");
        }

        String monthKey = month.format(MONTH_FORMAT);
        craRepository.findByUserIdAndHostIdAndYearMonth(userId, host.getId(), monthKey)
                .ifPresentOrElse(
                        existing -> existing.setDays(days),                // écrase pour ce mois
                        () -> craRepository.save(CraEntry.builder()
                                .userId(userId).hostId(host.getId()).yearMonth(monthKey).days(days)
                                .build()));
        return CraLine.written(cited, host, days, monthKey);
    }

    /** Les postes du user, indexés par nom normalisé (minuscules, sans accents). */
    private Map<String, RunnerHost> hostsByNormalizedName(UUID userId) {
        Map<String, RunnerHost> byName = new HashMap<>();
        for (RunnerHost host : hostService.list(userId)) {
            byName.putIfAbsent(normalize(host.getName()), host);
        }
        return byName;
    }

    /**
     * Rapproche un nom cité d'un poste : égalité normalisée d'abord, puis <b>inclusion</b> si un seul
     * poste correspond. Ambigu (plusieurs) ou aucun → {@code null} : on ne devine jamais.
     */
    private static RunnerHost match(Map<String, RunnerHost> byName, String cited) {
        String needle = normalize(cited);
        if (needle.isEmpty()) {
            return null;
        }
        RunnerHost exact = byName.get(needle);
        if (exact != null) {
            return exact;
        }
        RunnerHost found = null;
        for (Map.Entry<String, RunnerHost> entry : byName.entrySet()) {
            if (entry.getKey().contains(needle) || needle.contains(entry.getKey())) {
                if (found != null) {
                    return null; // ambigu : on demande, on ne devine pas
                }
                found = entry.getValue();
            }
        }
        return found;
    }

    /** Arrondit à la demi-journée la plus proche, à une décimale. {@code null} reste {@code null}. */
    private static BigDecimal normalizeDays(BigDecimal days) {
        if (days == null) {
            return null;
        }
        // Arrondi à 0,5 près : round(days * 2) / 2.
        return days.multiply(TWO).setScale(0, RoundingMode.HALF_UP).divide(TWO).setScale(1);
    }

    private static String strip(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private static String normalize(String value) {
        if (value == null) {
            return "";
        }
        String noAccents = Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}+", "");
        return noAccents.toLowerCase().trim();
    }

    /**
     * La consigne système : le modèle rend un tableau JSON strict. Le mois par défaut est nommé pour
     * qu'une ligne sans mois vise le mois courant (la Gateway l'applique aussi, défense en profondeur).
     */
    private static String systemPrompt(YearMonth defaultMonth) {
        return "Tu extrais un compte rendu d'activité (CRA) d'un message en langage naturel. "
                + "Réponds UNIQUEMENT par un tableau JSON, sans texte autour, de la forme "
                + "[{\"client\":\"<nom cité>\",\"days\":<nombre>,\"month\":\"YYYY-MM\"}]. "
                + "\"client\" est le nom du client/poste tel qu'il est écrit dans le message. "
                + "\"days\" est le nombre de jours travaillés (les demi-journées valent 0.5). "
                + "\"month\" est le mois visé au format YYYY-MM ; s'il n'est pas précisé pour une ligne, "
                + "utilise " + defaultMonth.format(MONTH_FORMAT) + ". "
                + "N'invente aucun client absent du message. Si le message ne contient aucun CRA, "
                + "réponds par un tableau vide [].";
    }

    // ----------------------------------------------------------------- résultat

    /** Le statut d'une ligne de CRA après rapprochement et validation. */
    public enum CraLineStatus { WRITTEN, REJECTED, UNKNOWN_HOST }

    /**
     * Une ligne du récap : ce que le modèle a cité, le poste rapproché (si connu), les jours et le
     * mois retenus, et le statut. Un nom inconnu porte {@code hostId == null} et est <b>demandé</b>.
     */
    public record CraLine(String cited, UUID hostId, String hostName, BigDecimal days, String month,
            CraLineStatus status, String message) {

        static CraLine written(String cited, RunnerHost host, BigDecimal days, String month) {
            return new CraLine(cited, host.getId(), host.getName(), days, month,
                    CraLineStatus.WRITTEN, null);
        }

        static CraLine rejected(String cited, RunnerHost host, String message) {
            return new CraLine(cited, host.getId(), host.getName(), null, null,
                    CraLineStatus.REJECTED, message);
        }

        static CraLine unknown(String cited) {
            return new CraLine(cited, null, null, null, null, CraLineStatus.UNKNOWN_HOST,
                    "Client non reconnu : précisez le poste.");
        }
    }

    /** Le récapitulatif d'un message de CRA : ce qui a été compris et écrit. */
    public record CraOutcome(List<CraLine> lines) {
    }
}
