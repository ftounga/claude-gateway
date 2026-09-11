package fr.claudegateway.quota;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tarifs servant à <b>décompter le quota au coût réel</b> (F-63). Valeurs commerciales et tarifs
 * fournisseur, réversibles, ajustables par environnement sans changement de code — jamais un secret.
 *
 * <p><b>Pourquoi ils existent</b> : jusqu'à F-63 le décompte traitait un token d'entrée et un token
 * de sortie à l'identique, alors que la sortie coûte <b>cinq fois</b> l'entrée chez le fournisseur
 * et qu'une lecture de cache coûte un <b>dixième</b>. La marge dépendait donc du style d'usage du
 * client, c'est-à-dire de rien qu'on maîtrise (voir {@code docs/STRATEGIE-TARIFAIRE.md} §2).</p>
 *
 * <p><b>Elles vivent sous le même préfixe que les plafonds de session</b>
 * ({@code app.atelier.agent.cost}) : les deux familles répondent à la même question — que coûte ce
 * qu'on sert — et les séparer inviterait à les régler séparément, donc à les faire diverger. La
 * valeur du token de quota est lue ici <b>et</b> par {@code AtelierCostProperties} : deux lectures
 * de la même clé, jamais deux valeurs.</p>
 *
 * @param inputCostPerMillionTokens      tarif fournisseur des tokens d'entrée, en dollars par
 *                                       million (défaut {@code 5.00} — Opus 5)
 * @param outputCostPerMillionTokens     tarif fournisseur des tokens de sortie (défaut
 *                                       {@code 25.00} — cinq fois l'entrée, c'est tout le sujet)
 * @param cacheReadCostPerMillionTokens  tarif d'une <b>lecture</b> de cache (défaut {@code 0.50} —
 *                                       0,1× l'entrée, ratio publié par le fournisseur). Les
 *                                       décompter au plein tarif ferait payer au client des tokens
 *                                       qui ne nous coûtent presque rien
 * @param cacheWriteCostPerMillionTokens tarif d'une <b>écriture</b> de cache (défaut {@code 6.25} —
 *                                       1,25× l'entrée pour le TTL de 5 minutes, celui que pose la
 *                                       boucle d'agent)
 * @param quotaTokenCostPerMillionTokens <b>ce que vaut un token de quota</b>, en dollars par million
 *                                       (défaut {@code 9.00}). Remplace l'ancien
 *                                       {@code cost-per-million-tokens}, documenté comme « coût
 *                                       blended » : une fois l'entrée et la sortie distinguées, il
 *                                       n'y a plus de coût moyen à approximer — le paramètre garde
 *                                       sa valeur et change de rôle. Il fixe l'<b>échelle</b> du
 *                                       décompte (les ratios entre natures, eux, viennent des
 *                                       tarifs ci-dessus) et, avec elle, le coût fournisseur maximal
 *                                       d'un quota : {@code quota × cette valeur}, quel que soit le
 *                                       style d'usage. Second levier de marge après le markup, à
 *                                       actionner sciemment
 * @param markup                         multiplicateur appliqué au coût réel lors du décompte
 *                                       (F-36 / SF-36-02). Défaut {@code 1.0} = neutre
 */
@ConfigurationProperties(prefix = "app.atelier.agent.cost")
public record TokenPricingProperties(
        BigDecimal inputCostPerMillionTokens,
        BigDecimal outputCostPerMillionTokens,
        BigDecimal cacheReadCostPerMillionTokens,
        BigDecimal cacheWriteCostPerMillionTokens,
        BigDecimal quotaTokenCostPerMillionTokens,
        BigDecimal markup) {

    static final BigDecimal DEFAULT_INPUT_COST = new BigDecimal("5.00");
    static final BigDecimal DEFAULT_OUTPUT_COST = new BigDecimal("25.00");
    static final BigDecimal DEFAULT_CACHE_READ_COST = new BigDecimal("0.50");
    static final BigDecimal DEFAULT_CACHE_WRITE_COST = new BigDecimal("6.25");
    /** Même défaut que l'ancien {@code cost-per-million-tokens} : aucune valeur ne bouge (F-63). */
    static final BigDecimal DEFAULT_QUOTA_TOKEN_COST = new BigDecimal("9.00");
    static final BigDecimal DEFAULT_MARKUP = BigDecimal.ONE;

    public TokenPricingProperties {
        // Un tarif absent ou absurde retombe sur son défaut documenté. Jamais zéro : facturer zéro
        // (ou, pour la valeur du token de quota, diviser par zéro) serait un incident, pas un réglage.
        inputCostPerMillionTokens = positiveOrDefault(inputCostPerMillionTokens, DEFAULT_INPUT_COST);
        outputCostPerMillionTokens = positiveOrDefault(outputCostPerMillionTokens, DEFAULT_OUTPUT_COST);
        cacheReadCostPerMillionTokens =
                positiveOrDefault(cacheReadCostPerMillionTokens, DEFAULT_CACHE_READ_COST);
        cacheWriteCostPerMillionTokens =
                positiveOrDefault(cacheWriteCostPerMillionTokens, DEFAULT_CACHE_WRITE_COST);
        quotaTokenCostPerMillionTokens =
                positiveOrDefault(quotaTokenCostPerMillionTokens, DEFAULT_QUOTA_TOKEN_COST);
        markup = positiveOrDefault(markup, DEFAULT_MARKUP);
    }

    private static BigDecimal positiveOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null || value.signum() <= 0 ? fallback : value;
    }
}
