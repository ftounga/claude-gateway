package fr.claudegateway.atelier;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.ConstructorBinding;

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
 * @param streaming     appel modèle en <b>flux</b> de la boucle maison (F-116 / SF-116-01), défaut
 *                      {@code true}. Actif, chaque tour consomme le flux SSE du fournisseur et fait
 *                      défiler le texte mot à mot dans le terminal ; le corps de requête, le cache, le
 *                      retry et le décompte d'usage sont inchangés — seul le <b>moment</b> d'affichage
 *                      change. <b>Coupe-circuit</b> : le passer à {@code false} rétablit l'appel
 *                      complet (texte affiché en fin de tour) par variable d'environnement, sans
 *                      livraison. Un refus ou une coupure du flux replie de toute façon sur l'appel
 *                      complet, tour par tour.
 * @param stepEffort    effort de raisonnement des <b>étapes de continuation</b> de la boucle maison
 *                      (F-118 / SF-118-01) : {@code low} à {@code max}, défaut {@code low}. Le
 *                      <b>premier</b> tour d'une demande garde l'effort normal ({@link #effort()}) — la
 *                      réflexion y sert à cadrer le travail ; les tours suivants (enchaîner un outil,
 *                      relire un fichier) exécutent une trajectoire déjà tracée et n'ont pas besoin de
 *                      « réfléchir fort ». Même vocabulaire et même repli que {@code effort} : une
 *                      valeur inconnue retombe sur le défaut, elle n'arrête pas les tours. Sans effet
 *                      quand {@code adaptiveEffort} est faux
 * @param adaptiveEffort <b>drapeau de repli</b> de l'effort adaptatif (F-118 / SF-118-01), défaut
 *                      {@code true}. Actif, l'effort suit l'étape (normal au premier tour, réduit
 *                      ensuite). <b>Coupe-circuit</b> : le passer à {@code false} rétablit l'effort
 *                      normal à <i>chaque</i> étape (comportement d'avant F-118) par variable
 *                      d'environnement, sans livraison
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
        Boolean storageExecution,
        Boolean streaming,
        String stepEffort,
        Boolean adaptiveEffort) {

    /** Modèle de la boucle maison à défaut de configuration (F-39 / SF-39-10). */
    public static final String DEFAULT_MODEL = "claude-opus-5";
    /** Effort par défaut : celui du fournisseur, écrit pour être réglable (F-39 / SF-39-10). */
    public static final String DEFAULT_EFFORT = "high";
    /**
     * Effort par défaut des étapes de continuation (F-118 / SF-118-01) : le plancher du vocabulaire.
     * Enchaîner un {@code read_file} ou un {@code ls} n'a pas besoin de « réfléchir fort ». Réglable
     * via {@code APP_ATELIER_STEP_EFFORT} sans livraison si {@code medium} s'avère plus sûr.
     */
    public static final String DEFAULT_STEP_EFFORT = "low";
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

    // Le record porte un second constructeur (compatibilité pré-F-116) : la liaison de configuration
    // doit désigner explicitement le constructeur canonique, sans quoi elle serait ambiguë.
    @ConstructorBinding
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
        // Absent => flux actif : même règle que les autres drapeaux, un réglage manquant ne change pas
        // le comportement livré (F-116 / SF-116-01).
        if (streaming == null) {
            streaming = Boolean.TRUE;
        }
        // Effort des étapes de continuation (F-118 / SF-118-01) : même repli que `effort`, une faute
        // de frappe retombe sur le défaut au lieu d'arrêter le démarrage ou de rendre les tours muets.
        if (stepEffort == null || stepEffort.isBlank() || !ALLOWED_EFFORTS.contains(stepEffort)) {
            stepEffort = DEFAULT_STEP_EFFORT;
        }
        // Absent => effort adaptatif actif : un réglage manquant ne change pas le comportement livré.
        if (adaptiveEffort == null) {
            adaptiveEffort = Boolean.TRUE;
        }
    }

    /**
     * Constructeur de compatibilité, sans l'effort adaptatif (F-118) : effort réduit et drapeau
     * retombent sur leurs défauts ({@code low} / actif). Évite de réécrire les appelants antérieurs à
     * F-118 (et leurs tests) pour des réglages qu'ils n'expriment pas.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                null, null);
    }

    /**
     * Constructeur de compatibilité, sans le drapeau de flux (F-116) : le flux est actif par défaut.
     * Évite de réécrire les appelants antérieurs à F-116 pour un réglage qu'ils n'expriment pas.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, Boolean.TRUE);
    }
}
