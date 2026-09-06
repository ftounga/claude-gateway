package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Réglages de l'Atelier (F-28). Externalisés pour être ajustables sans changement de code.
 *
 * @param storage       fournisseur de stockage : {@code in-memory} (dev/tests, défaut) ou {@code s3} (cluster)
 * @param bucket        bucket S3 (mode {@code s3}) ; fourni par l'environnement
 * @param prefix        préfixe racine des objets ({@code atelier/} par défaut)
 * @param maxTotalBytes taille décompressée totale maximale d'un zip (anti zip-bomb)
 * @param maxEntries    nombre d'entrées maximal d'un zip (anti zip-bomb)
 * @param maxFileBytes  taille maximale d'un fichier décompressé (anti zip-bomb)
 * @param maxIterations plafond d'allers-retours d'un message dans la boucle d'agent (F-28 / SF-28-19).
 *                      Calibré sur l'usage réel : à 12, un tiers des demandes était coupé en chemin.
 *                      Configurable pour se baisser sans livraison, tant que le cache de prompt (R6)
 *                      n'a pas changé l'arbitrage de coût.
 * @param model         modèle de la <b>boucle maison</b> (F-39 / SF-39-10, défaut
 *                      {@code claude-opus-5}). Réglage à elle : il était jusqu'ici emprunté au
 *                      catalogue du <b>chat</b> (F-02), si bien que changer le modèle proposé aux
 *                      utilisateurs changeait en silence celui qui exécute des commandes sur leur
 *                      machine. Volontairement <b>non</b> validé contre {@code ModelCatalog}, qui dit
 *                      ce que le chat propose, pas ce que le harnais exécute
 * @param effort        effort de raisonnement de la boucle maison (F-39 / SF-39-10) : {@code low} à
 *                      {@code max}, défaut {@code high} — le défaut du fournisseur, posé
 *                      explicitement pour être réglable sans livraison. {@code xhigh} attend le
 *                      lot 6 : la boucle appelle en non-streamé, et monter l'effort avant d'avoir
 *                      câblé timeout et retry échangerait de la profondeur contre des tours coupés
 *                      au budget de temps
 * @param contextPruning écartement des résultats d'outils périmés d'un tour long
 *                      (F-39 / SF-39-12), défaut {@code true}. <b>Coupe-circuit</b> : le mécanisme
 *                      repose sur une capacité <i>beta</i> du fournisseur ; si elle était retirée,
 *                      chaque tour de l'Atelier échouerait. Le passer à {@code false} rétablit le
 *                      service par variable d'environnement, sans livraison
 * @param storageExecution coupe-circuit de la cible {@code SANDBOX} de la boucle maison
 *                         (F-39 / SF-39-16). <b>Fermé par défaut</b> : depuis le lot 4, l'écran
 *                         n'emprunte plus ce chemin — un projet sans runner passe par les Managed
 *                         Agents. Ouvrable par variable d'environnement, sans livraison.
 * @param maxDelegations nombre maximal d'explorations déléguées dans un même message
 *                       (F-39 / SF-39-14, défaut 3). Au-delà, c'est le travail principal qu'il faut
 *                       redécouper — pas la délégation qu'il faut ouvrir.
 * @param maxTurnTokens plafond de consommation d'un <b>message</b> de la boucle maison
 *                      (F-39 / SF-39-15), en tokens traités — cache compris, comme le compteur de
 *                      quota (SF-39-01). Défaut {@code 1 500 000}, calibré sur l'usage réel du
 *                      cadrage : contexte maximal observé 900 519 tokens, tour de 30 itérations
 *                      estimé à ~1,35 M tokens d'entrée. Un tour ordinaire ne le voit jamais ; un
 *                      tour parti en vrille s'y arrête. Exprimé en tokens et non en dollars
 *                      (décision D-L8-1) : le compteur additionne les tokens servis par le cache,
 *                      qu'un taux mélangé sur-facturerait d'un ordre de grandeur
 */
@ConfigurationProperties(prefix = "app.atelier")
public record AtelierProperties(
        String storage,
        String bucket,
        String prefix,
        Long maxTotalBytes,
        Integer maxEntries,
        Long maxFileBytes,
        Integer maxIterations,
        String model,
        String effort,
        Boolean contextPruning,
        Long maxTurnTokens,
        Integer maxDelegations,
        Boolean storageExecution) {

    /** Modèle de la boucle maison à défaut de configuration (F-39 / SF-39-10). */
    public static final String DEFAULT_MODEL = "claude-opus-5";
    /** Effort par défaut : celui du fournisseur, écrit pour être réglable (F-39 / SF-39-10). */
    public static final String DEFAULT_EFFORT = "high";
    /** Niveaux d'effort acceptés — même vocabulaire que le chemin Managed Agents (SF-28-17). */
    private static final java.util.Set<String> ALLOWED_EFFORTS =
            java.util.Set.of("low", "medium", "high", "xhigh", "max");
    /** Plafond de consommation d'un message à défaut de configuration (F-39 / SF-39-15). */
    /**
     * Plafond par défaut, <b>relevé de 1,5 M à 4 M le 2026-09-06</b> (F-38 / SF-38-20).
     *
     * <p>1,5 M avait été calibré sur le contexte maximal <b>observé</b> dans l'usage mesuré au
     * cadrage — 900 519 tokens. Le banc d'essai a montré qu'un vrai travail de construction le
     * dépasse largement : la construction d'une application fullstack a été coupée à mi-parcours,
     * après sept étapes de procédure sur treize.</p>
     *
     * <p>Ce compteur additionne les tokens <b>traités</b>, cache compris (SF-39-01, D3) : sur un
     * tour long, l'essentiel est relu du cache au dixième du tarif. Relever le plafond ne multiplie
     * donc pas la facture dans les mêmes proportions — et le quota reste la borne qui, elle,
     * mesure ce que l'utilisateur a payé.</p>
     */
    public static final long DEFAULT_MAX_TURN_TOKENS = 4_000_000L;
    /**
     * Borne haute du plafond de message : au-delà, {@code maxIterations} et le budget de temps
     * auraient tranché de toute façon. Mieux vaut une borne lisible qu'un plafond qui n'a jamais
     * l'occasion de s'appliquer — même règle que {@code maxIterations}.
     */
    public static final long MAX_TURN_TOKENS_CEILING = 10_000_000L;

    public AtelierProperties {
        if (storage == null || storage.isBlank()) {
            storage = "in-memory";
        }
        if (prefix == null || prefix.isBlank()) {
            prefix = "atelier/";
        }
        if (maxTotalBytes == null || maxTotalBytes <= 0) {
            maxTotalBytes = 50L * 1024 * 1024; // 50 Mo
        }
        if (maxEntries == null || maxEntries <= 0) {
            maxEntries = 2000;
        }
        if (maxFileBytes == null || maxFileBytes <= 0) {
            maxFileBytes = 2L * 1024 * 1024; // 2 Mo
        }
        if (maxIterations == null || maxIterations <= 0) {
            maxIterations = 30;
        }
        // Au-delà, le budget de temps du tour (10 min) aurait tranché de toute façon : mieux vaut
        // une borne lisible qu'un plafond qui n'a jamais l'occasion de s'appliquer.
        if (maxIterations > 100) {
            maxIterations = 100;
        }
        if (model == null || model.isBlank()) {
            model = DEFAULT_MODEL;
        }
        // Une faute de frappe en configuration ne doit pas faire échouer les tours (même règle que
        // SF-28-17) : un effort inconnu retombe sur le défaut, il n'arrête pas le démarrage.
        if (effort == null || effort.isBlank() || !ALLOWED_EFFORTS.contains(effort)) {
            effort = DEFAULT_EFFORT;
        }
        if (contextPruning == null) {
            contextPruning = Boolean.TRUE;
        }
        // Un plafond absent, nul ou négatif retombe sur le défaut : une faute de configuration ne
        // doit ni ouvrir la vanne, ni couper tous les tours au premier appel.
        if (storageExecution == null) {
            storageExecution = false;
        }
        if (maxDelegations == null || maxDelegations < 0) {
            maxDelegations = 3;
        }
        if (maxTurnTokens == null || maxTurnTokens <= 0L) {
            maxTurnTokens = DEFAULT_MAX_TURN_TOKENS;
        }
        if (maxTurnTokens > MAX_TURN_TOKENS_CEILING) {
            maxTurnTokens = MAX_TURN_TOKENS_CEILING;
        }
    }
}
