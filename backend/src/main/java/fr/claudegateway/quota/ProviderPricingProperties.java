package fr.claudegateway.quota;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * La grille <b>de vérité</b> : ce que le fournisseur nous facture réellement, par modèle et par
 * nature de token (F-133 / SF-133-01).
 *
 * <p><b>Pourquoi une seconde grille, alors que {@link TokenPricingProperties} existe déjà.</b> Les
 * deux répondent à deux questions différentes, et les confondre ferait mentir l'une ou l'autre :</p>
 *
 * <table border="1">
 *   <caption>Deux grilles, deux rôles</caption>
 *   <tr><th></th><th>{@code app.atelier.agent.cost}</th><th>{@code app.cost.provider} (ici)</th></tr>
 *   <tr><td>Question</td><td>que décompte-t-on au client ?</td><td>que nous coûte ce tour ?</td></tr>
 *   <tr><td>Écriture de cache 1 h</td><td><b>6,25</b> — volontairement sous-évalué, en faveur du
 *       client (voir {@code application.yml}, F-130)</td><td><b>10,00</b> — le tarif réel</td></tr>
 *   <tr><td>Par modèle</td><td>non : un seul jeu, celui du modèle servi</td><td><b>oui</b></td></tr>
 * </table>
 *
 * <p>Le 6,25 du décompte commercial n'est pas une erreur à corriger : c'est un arbitrage assumé qui
 * protège le client d'une sur-facturation. Mais un suivi de dépense qui s'appuierait dessus
 * <b>sous-estimerait</b> ce qu'on paie. D'où la séparation — et c'est la raison d'être de cette
 * classe.</p>
 *
 * <p><b>Pourquoi par modèle</b> : un tour d'Opus 5 coûte cinq fois un tour de Haiku 4.5 à volume
 * égal. Un tarif unique se tromperait dès qu'un chemin change de modèle, et l'écart passerait
 * inaperçu puisque rien ne le signalerait.</p>
 *
 * <p><b>Pourquoi en configuration, relevée à la main</b> : les prix sont <b>publics</b> et aucune
 * API ne les expose ({@code GET /v1/models} rend les capacités d'un modèle, jamais son prix). Ils
 * sont donc relevés sur la grille officielle et revus chaque trimestre (décision D9 du cadrage
 * F-133). {@link #pricingVersion()} date le relevé et accompagne chaque montant enregistré, pour
 * qu'un coût ancien reste explicable quand les tarifs auront changé.</p>
 *
 * <p><b>Deux dépenses ne sont pas des tokens</b> (SF-133-08) et figurent ici à part : la recherche
 * web, facturée à mille, et le temps de session des Managed Agents, facturé à l'heure. Les ignorer
 * ferait sous-estimer tout tour agentique.</p>
 *
 * @param pricingVersion      date du relevé de la grille (ex. {@code 2026-09-20}), écrite sur chaque
 *                            ligne de coût
 * @param defaultModel        modèle dont les tarifs servent de repli quand le modèle servi est
 *                            inconnu de la grille — jamais d'échec de tour pour un problème de tarif
 * @param models              grille par identifiant de modèle ({@code claude-opus-5}, …)
 * @param webSearchPerThousand tarif de mille recherches web, en dollars (défaut {@code 10.00})
 * @param sessionHour         tarif d'une heure de session {@code running}, en dollars (défaut
 *                            {@code 0.08}). Ne s'applique qu'aux Managed Agents : la boucle maison
 *                            n'a pas de bac à sable facturé
 * @param usdToEur            taux de conversion pour l'<b>affichage</b> (défaut {@code 0.92}). Le
 *                            fournisseur facture en dollars ; la vérité est donc en dollars, et
 *                            l'euro n'est qu'une commodité de lecture (SF-133-02). Convertir en dur
 *                            ferait diverger l'application de la facture d'un écart de change
 *                            invisible
 */
@ConfigurationProperties(prefix = "app.cost.provider")
public record ProviderPricingProperties(
        String pricingVersion,
        String defaultModel,
        Map<String, ModelPricing> models,
        BigDecimal webSearchPerThousand,
        BigDecimal sessionHour,
        BigDecimal usdToEur) {

    static final String DEFAULT_PRICING_VERSION = "2026-09-20";
    static final String DEFAULT_MODEL = "claude-opus-5";
    static final BigDecimal DEFAULT_WEB_SEARCH_PER_THOUSAND = new BigDecimal("10.00");
    static final BigDecimal DEFAULT_SESSION_HOUR = new BigDecimal("0.08");
    static final BigDecimal DEFAULT_USD_TO_EUR = new BigDecimal("0.92");

    // Pas de constructeur de commodité : un second constructeur rendrait la liaison de
    // configuration ambiguë (Spring ne saurait plus lequel utiliser) et le contexte refuserait de
    // démarrer. Les appelants passent les cinq composants, quitte à en laisser à `null`.

    /**
     * Grille par défaut, relevée le 2026-09-20 sur
     * {@code https://platform.claude.com/docs/en/about-claude/pricing}. Elle vit dans le code et non
     * seulement dans {@code application.yml} pour qu'un environnement qui ne la configure pas
     * calcule quand même juste — un coût absent serait plus grave qu'un coût à réviser.
     */
    static Map<String, ModelPricing> defaultModels() {
        Map<String, ModelPricing> grid = new LinkedHashMap<>();
        grid.put("claude-opus-5", ModelPricing.of("5.00", "25.00", "0.50", "10.00"));
        grid.put("claude-opus-4-8", ModelPricing.of("5.00", "25.00", "0.50", "10.00"));
        grid.put("claude-sonnet-5", ModelPricing.of("2.00", "10.00", "0.20", "4.00"));
        grid.put("claude-haiku-4-5", ModelPricing.of("1.00", "5.00", "0.10", "2.00"));
        // Lecture de cache à 0,025x l'entrée sur cette famille, et non 0,1x : la note 1 de la
        // grille officielle. Le recopier sans la lire multiplierait le coût de lecture par quatre.
        grid.put("claude-fable-5-1", ModelPricing.of("10.00", "50.00", "0.25", "20.00"));
        return Map.copyOf(grid);
    }

    public ProviderPricingProperties {
        pricingVersion = pricingVersion == null || pricingVersion.isBlank()
                ? DEFAULT_PRICING_VERSION
                : pricingVersion.trim();
        defaultModel = defaultModel == null || defaultModel.isBlank()
                ? DEFAULT_MODEL
                : defaultModel.trim();
        // Une grille vide n'est pas un réglage : ce serait facturer zéro, donc ne rien mesurer.
        models = models == null || models.isEmpty() ? defaultModels() : Map.copyOf(models);
        // Un tarif d'extra absent retombe sur son défaut publié. Jamais zéro : une recherche web
        // gratuite ferait disparaître du rapport une dépense bien réelle.
        webSearchPerThousand = webSearchPerThousand == null || webSearchPerThousand.signum() <= 0
                ? DEFAULT_WEB_SEARCH_PER_THOUSAND
                : webSearchPerThousand;
        sessionHour = sessionHour == null || sessionHour.signum() <= 0
                ? DEFAULT_SESSION_HOUR
                : sessionHour;
        // Un taux nul ou négatif afficherait zéro euro sur une dépense réelle : c'est un incident,
        // pas un réglage.
        usdToEur = usdToEur == null || usdToEur.signum() <= 0 ? DEFAULT_USD_TO_EUR : usdToEur;
    }

    /**
     * Tarifs d'un modèle, ou {@code null} s'il est absent de la grille — l'appelant décide alors du
     * repli, et le signale.
     *
     * @param model identifiant du modèle, éventuellement {@code null}
     */
    public ModelPricing pricingOf(String model) {
        return model == null || model.isBlank() ? null : models.get(model.trim());
    }

    /** Tarifs de repli. Garantis non nuls : la grille par défaut contient toujours le modèle par défaut. */
    public ModelPricing fallbackPricing() {
        ModelPricing fallback = models.get(defaultModel);
        return fallback != null ? fallback : defaultModels().get(DEFAULT_MODEL);
    }

    /**
     * Les quatre tarifs d'un modèle, en dollars par million de tokens.
     *
     * @param input      entrée au plein tarif (hors cache)
     * @param output     sortie
     * @param cacheRead  lecture de cache
     * @param cacheWrite écriture de cache. <b>Au TTL réellement posé par la boucle</b> : depuis
     *                   F-130 c'est le TTL d'une heure, soit 2× l'entrée, et non 1,25×
     */
    public record ModelPricing(
            BigDecimal input,
            BigDecimal output,
            BigDecimal cacheRead,
            BigDecimal cacheWrite) {

        static ModelPricing of(String input, String output, String cacheRead, String cacheWrite) {
            return new ModelPricing(new BigDecimal(input), new BigDecimal(output),
                    new BigDecimal(cacheRead), new BigDecimal(cacheWrite));
        }

        public ModelPricing {
            // Un tarif absent ou négatif vaut zéro pour sa nature : mieux vaut sous-compter une
            // nature mal configurée que refuser de mesurer le tour entier.
            input = positiveOrZero(input);
            output = positiveOrZero(output);
            cacheRead = positiveOrZero(cacheRead);
            cacheWrite = positiveOrZero(cacheWrite);
        }

        private static BigDecimal positiveOrZero(BigDecimal value) {
            return value == null || value.signum() < 0 ? BigDecimal.ZERO : value;
        }
    }
}
