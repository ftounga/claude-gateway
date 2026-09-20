package fr.claudegateway.quota;

/**
 * Les dépenses d'un tour que <b>aucun token ne montre</b> (F-133 / SF-133-08).
 *
 * <p>La grille du fournisseur facture deux choses en dehors des tokens : la <b>recherche web</b>, à
 * 10 $ pour mille requêtes, et le <b>temps de session</b> des Managed Agents, à 0,08 $ l'heure. Un
 * coût reconstitué des seuls tokens les ignore, et sous-estime donc systématiquement — d'autant
 * plus que l'usage est agentique, c'est-à-dire précisément le profil de l'Atelier et de la
 * Vigie.</p>
 *
 * <p>Les deux étaient à portée de main avant cette subfeature : les secondes de session sont
 * comptées depuis F-30 ({@code UsageCounter.sandboxSeconds}) mais n'ont jamais été tarifées, et les
 * recherches sont rapportées par le fournisseur dans {@code usage.server_tool_use} — que pas une
 * ligne du dépôt ne lisait, alors que l'outil de recherche est déclaré à chaque tour d'agent.</p>
 *
 * @param webSearchRequests recherches web facturées sur le tour
 * @param sandboxSeconds    secondes de session {@code running} imputées au tour
 */
public record TurnExtras(long webSearchRequests, long sandboxSeconds) {

    /** Tour sans aucune dépense hors tokens — la forme de {@code /chat}, {@code /ask} et du Radar. */
    public static final TurnExtras NONE = new TurnExtras(0L, 0L);

    public TurnExtras {
        // Un compteur négatif n'existe pas. Un fournisseur dont le cumul recule ne nous doit pas un
        // crédit : il nous doit zéro.
        webSearchRequests = Math.max(0L, webSearchRequests);
        sandboxSeconds = Math.max(0L, sandboxSeconds);
    }

    /** Vrai quand le tour n'a rien dépensé hors tokens : il n'y a alors rien à ajouter au coût. */
    public boolean isEmpty() {
        return webSearchRequests == 0L && sandboxSeconds == 0L;
    }
}
