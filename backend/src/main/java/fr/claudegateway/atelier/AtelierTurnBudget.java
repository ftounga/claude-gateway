package fr.claudegateway.atelier;

/**
 * Plafond de consommation d'<b>un message</b> de la boucle maison (F-39 / SF-39-15, lot 8).
 *
 * <p>La boucle portait trois garde-fous — nombre d'itérations, budget de temps, contrôle de quota —
 * et aucun ne bornait la <b>dépense</b> : le quota est vérifié <i>avant</i> le tour et enregistré
 * <i>après</i>, si bien qu'un seul message pouvait dépasser le quota mensuel entier. C'est le défaut
 * que F-36 a corrigé pour le chemin Managed Agents ({@code SessionBudget}) et que la boucle maison
 * n'avait jamais reçu, alors qu'elle est devenue le moteur du terminal (SF-39-08).</p>
 *
 * <h2>Pourquoi des tokens et non des dollars (décision D-L8-1)</h2>
 *
 * <p>La boucle ne dispose que d'un relevé : le compteur de tokens de {@code AgentTurn}, qui — par la
 * décision D3 de SF-39-01 — additionne {@code input_tokens}, {@code cache_creation_input_tokens} et
 * {@code cache_read_input_tokens} — parce que « le quota mesure ce qui a été traité, pas ce que le
 * fournisseur facture ». Le convertir en dollars par le taux mélangé de F-36 (9 $/M) donnerait un
 * chiffre faux d'un <b>ordre de grandeur</b> — un tour de 30 itérations pèse ~1,35 M tokens traités
 * pour ~1,27 $ facturés, l'essentiel étant relu du cache au dixième du tarif. Un plafond de 2 $ ainsi
 * converti couperait vers la huitième itération, c'est-à-dire précisément les tours que le cache de
 * prompt venait de rendre abordables.</p>
 *
 * <p>Le plafond est donc dit dans l'unité que la boucle mesure et que le produit facture déjà
 * (le quota est en tokens).</p>
 *
 * <h2>Projeter plutôt que constater (décision D-L8-2)</h2>
 *
 * <p>Le verrou de F-36 est <i>pré-requête</i> parce qu'il vit chez le fournisseur ; ici la boucle
 * appelle l'API Messages brute et rien ne peut refuser un appel avant qu'il parte. Constater après
 * coup autoriserait une itération entière au-delà du plafond. La boucle projette donc l'itération à
 * venir par la <b>plus grosse déjà observée</b> dans ce tour — un majorant, non une moyenne, le
 * contexte d'un tour croissant à mesure que les résultats d'outils s'empilent.</p>
 *
 * @param maxTokens plafond de tokens traités pour ce message, strictement positif
 */
public record AtelierTurnBudget(long maxTokens) {

    public AtelierTurnBudget {
        if (maxTokens < 1L) {
            maxTokens = 1L;
        }
    }

    /**
     * Plafond du tour en mode <b>Hosted</b> : le minimum entre le réglage de la boucle et le quota
     * restant de l'utilisateur. Un message ne consomme jamais plus que ce qui a été payé — c'est la
     * règle de {@code AtelierSessionService.sessionBudget} (F-36), transposée à la boucle maison.
     *
     * @param configuredMaxTokens plafond configuré ({@code app.atelier.max-turn-tokens})
     * @param remainingTokens     quota restant de l'utilisateur du contexte de sécurité
     * @return le plafond du tour
     */
    public static AtelierTurnBudget hosted(long configuredMaxTokens, long remainingTokens) {
        return new AtelierTurnBudget(Math.min(configuredMaxTokens, Math.max(remainingTokens, 0L)));
    }

    /**
     * Plafond du tour en mode <b>BYOK</b> : le réglage seul. Les tokens sont sur le compte Anthropic
     * de l'utilisateur, où la plateforme ne tient aucun quota (SF-28-06) ; le plafond n'en reste pas
     * moins utile — il borne un tour parti en vrille, quel que soit celui qui paie.
     *
     * @param configuredMaxTokens plafond configuré ({@code app.atelier.max-turn-tokens})
     * @return le plafond du tour
     */
    public static AtelierTurnBudget byok(long configuredMaxTokens) {
        return new AtelierTurnBudget(configuredMaxTokens);
    }

    /**
     * Dit si l'itération suivante doit être renoncée.
     *
     * <p>La <b>première</b> itération est toujours permise (décision D-L8-3) : refuser avant tout
     * appel produirait un tour qui n'a rien fait, rien dit et rien coûté — illisible, et lu comme une
     * panne. C'est le rôle que joue le plancher {@code min-run-cost} chez F-36, obtenu ici sans
     * réglage supplémentaire. Le dépassement possible est borné à une itération, et reste décompté
     * par le quota.</p>
     *
     * @param spentTokens     tokens déjà consommés par ce tour (cache compris)
     * @param projectedTokens majorant du coût de l'itération à venir : la plus grosse itération déjà
     *                        observée. {@code 0} tant qu'aucune n'a été observée
     * @return {@code true} si le plafond serait franchi
     */
    public boolean exceededBy(long spentTokens, long projectedTokens) {
        if (projectedTokens <= 0L) {
            return false;
        }
        return spentTokens + projectedTokens > maxTokens;
    }
}
