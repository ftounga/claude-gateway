package fr.claudegateway.agent;

/**
 * <b>Forme d'un appel</b> d'agent (F-235 / SF-235-01). Neutre vis-à-vis du fournisseur : elle dit ce que l'appel
 * <b>veut</b>, pas comment l'API l'écrit.
 *
 * <p>Le tour du terminal emploie la forme {@link #standard()} — rien ne change pour lui. Le cerveau rapide de Jarvis
 * emploie {@link #compact(int)} : une réponse courte, <b>aucun</b> outil exécuté par le fournisseur (pas de recherche
 * web : il n'a qu'un outil, déléguer), et des outils <b>stricts</b> (arguments toujours conformes au schéma).</p>
 *
 * @param maxTokens   plafond de sortie propre à l'appel, ou {@code null} pour celui du fournisseur ; {@code 0}
 *                    est permis (pré-chauffage du cache : rien n'est généré)
 * @param serverTools vrai si les outils exécutés chez le fournisseur (recherche, lecture web) sont déclarés
 * @param strictTools vrai si les outils déclarés doivent être appelés avec des arguments conformes au schéma
 * @param cacheLastMessage F-239 / SF-239-11 — vrai (défaut) : le dernier message porte le marqueur de cache ; faux :
 *                    l'avant-dernier le porte, et le dernier — volatil, propre à l'appel — est lu sans être écrit
 */
public record AgentCallShape(Integer maxTokens, boolean serverTools, boolean strictTools, boolean cacheLastMessage) {

    private static final AgentCallShape STANDARD = new AgentCallShape(null, true, false, true);

    public AgentCallShape {
        maxTokens = maxTokens == null ? null : Math.max(0, maxTokens);
    }

    public AgentCallShape(Integer maxTokens, boolean serverTools, boolean strictTools) {
        this(maxTokens, serverTools, strictTools, true);
    }

    /** La forme de toujours : plafond du fournisseur, outils serveur déclarés, outils non stricts. */
    public static AgentCallShape standard() {
        return STANDARD;
    }

    /**
     * Un appel court, sans outil serveur, aux outils stricts (F-235, D1/D7). F-239 / SF-239-11 : son dernier message
     * (contexte volatil + parole) n'est jamais écrit en cache — seul le préfixe stable (consigne, univers, échanges)
     * l'est, et il est relu d'un appel à l'autre.
     */
    public static AgentCallShape compact(int maxTokens) {
        return new AgentCallShape(maxTokens, false, true, false);
    }
}
