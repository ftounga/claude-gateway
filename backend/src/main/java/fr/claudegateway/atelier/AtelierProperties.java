package fr.claudegateway.atelier;

import java.time.Duration;

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
 *                       (F-39 / SF-39-14 ; défaut relevé de 3 à 5 par F-148 / SF-148-01, pour qu'une
 *                       investigation multi-zones tienne en un tour). Au-delà, c'est le travail
 *                       principal qu'il faut redécouper — pas la délégation qu'il faut ouvrir.
 * @param streaming     appel modèle en <b>flux</b> de la boucle maison (F-116 / SF-116-01), défaut
 *                      {@code true}. Actif, chaque tour consomme le flux SSE du fournisseur et fait
 *                      défiler le texte mot à mot dans le terminal ; le corps de requête, le cache, le
 *                      retry et le décompte d'usage sont inchangés — seul le <b>moment</b> d'affichage
 *                      change. <b>Coupe-circuit</b> : le passer à {@code false} rétablit l'appel
 *                      complet (texte affiché en fin de tour) par variable d'environnement, sans
 *                      livraison. Un refus ou une coupure du flux replie de toute façon sur l'appel
 *                      complet, tour par tour.
 * @param stepEffort    effort de raisonnement des <b>étapes de continuation</b> de la boucle maison
 *                      (F-118 / SF-118-01) : {@code low} à {@code max}, défaut {@code medium}
 *                      (relevé de {@code low} par F-148 / SF-148-01). Le
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
 * @param turnBudget    budget de <b>temps</b> d'un message de la boucle maison (F-118 / SF-118-03),
 *                      défaut {@code PT10M} (10 min) — le comportement livré, posé ici pour être
 *                      réglable sans livraison. Le plafond d'itérations borne le <b>nombre</b>
 *                      d'allers-retours, ce budget borne la <b>durée</b> : la boucle rend la main
 *                      avant que le flux SSE n'expire. Même stratégie de repli que {@link
 *                      #maxTurnTokens()} : une valeur absente, nulle, de durée nulle ou négative
 *                      retombe sur le défaut, et une valeur au-delà du plafond dur ({@code PT2H}) y
 *                      est ramenée. La production le porte à 60 min par {@code APP_ATELIER_TURN_BUDGET}
 *                      ({@code PT60M}), sans changer la valeur du code
 * @param exploreEffort effort de raisonnement de la <b>sous-boucle d'exploration</b> (F-119 /
 *                      SF-119-01) : {@code low} à {@code max}, défaut {@code low}. Corrige le
 *                      {@code AgentReasoning.none()} d'origine (zéro raisonnement en investigation) :
 *                      la sous-boucle lit et interprète, elle mérite un raisonnement adaptatif à
 *                      effort non nul, mais sobre pour ne pas alourdir chaque lecture déléguée. Même
 *                      repli que {@code effort} : une valeur inconnue retombe sur le défaut
 * @param escalateOnSignal <b>ré-escalade de l'effort sur signal de difficulté</b> (F-119 /
 *                      SF-119-01), défaut {@code true}. Actif, un tour de continuation dont le tour
 *                      précédent a produit un signal (résultat d'outil en erreur, {@code bash} en code
 *                      de sortie ≠ 0, {@code edit_file} raté, timeout/indispo runner,
 *                      auto-contradiction du modèle) repasse à l'effort <b>normal</b> ({@link
 *                      #effort()}) au lieu de {@link #stepEffort()} ; une trajectoire qui roule sans
 *                      incident garde l'effort réduit (gain de vitesse F-118 préservé).
 *                      <b>Coupe-circuit</b> : {@code false} rétablit le comportement F-118 strict
 *                      (réduit sur toutes les continuations), sans livraison. Sans effet quand
 *                      {@code adaptiveEffort} est faux
 * @param replayedTraceTurns nombre de tours rejoués <b>avec</b> leur trajectoire d'outils (F-119 /
 *                      SF-119-03), défaut {@code 12} (relevé de 5). Au-delà, les tours plus anciens
 *                      sont rejoués en texte seul. Élargir la fenêtre garde le <b>couplage
 *                      affirmation↔preuve</b> plus longtemps : l'agent perdait ses résultats d'outils
 *                      avant ses affirmations, d'où des contradictions. Repli comme les autres
 *                      bornes : une valeur absente, nulle ou négative retombe sur le défaut ; bornée à
 *                      un plafond lisible ({@code 40})
 * @param fileStateHints <b>aide-mémoire d'état de fichier</b> (F-119 / SF-119-05), défaut
 *                      {@code true}. Actif, une édition (`edit_file`) d'un fichier que le modèle n'a
 *                      ni lu ni écrit dans ce fil reçoit un rappel léger « relis-le avant si tu n'es
 *                      pas sûr de son contenu » (incitation à la lecture-avant-édition), sans jamais
 *                      refuser l'opération (le disque évite déjà la corruption). Coupe-circuit à
 *                      {@code false}
 * @param exploreParallelism nombre maximal d'explorations menées <b>en parallèle</b> dans un même
 *                      message (F-39 / SF-39-21, défaut 3). Quand un tour émet plusieurs {@code explore}
 *                      indépendants, ils sont exécutés ensemble via un pool borné de cette taille ;
 *                      au-delà, les explorations sont servies <b>par vagues</b>. Le plafond total par
 *                      message reste {@link #maxDelegations()} — le parallélisme ne l'ouvre pas, il ne
 *                      fait que recouvrir le temps de mur (la pensée du modèle) de délégations déjà
 *                      bornées et en lecture seule. Repli comme les autres bornes : une valeur absente,
 *                      nulle ou {@code < 1} retombe sur le défaut, et une valeur déraisonnable est
 *                      ramenée à un plafond lisible ({@code 16})
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
        Boolean adaptiveEffort,
        Duration turnBudget,
        String exploreEffort,
        Boolean escalateOnSignal,
        Integer replayedTraceTurns,
        Boolean fileStateHints,
        Boolean perMessageEffort,
        Integer exploreParallelism) {


    /**
     * L'effort voyage-t-il <b>dans</b> la conversation plutôt qu'à la racine de la requête
     * (F-134 / SF-134-05) ? Défaut <b>vrai</b>.
     *
     * <p>Le changer à la racine <b>vide tout le cache des messages</b> — or F-118 le baisse dès la
     * deuxième étape et F-119 le remonte sur difficulté. Transmis par un message glissé dans la
     * conversation, le même niveau s'applique sans rien invalider.</p>
     *
     * <p><b>Pourquoi le réglage existe</b> : la forme repose sur une bêta du fournisseur, vérifiée
     * sur la clé de production avant livraison. Si elle venait à fermer, ce drapeau rétablit le
     * comportement d'avant — un cache inefficace, jamais un service en panne.</p>
     */
    public static final boolean DEFAULT_PER_MESSAGE_EFFORT = true;

    /** Modèle de la boucle maison à défaut de configuration (F-39 / SF-39-10). */
    public static final String DEFAULT_MODEL = "claude-opus-5";
    /** Effort par défaut : celui du fournisseur, écrit pour être réglable (F-39 / SF-39-10). */
    public static final String DEFAULT_EFFORT = "high";
    /**
     * Effort par défaut des étapes de continuation (F-118 / SF-118-01 ; porté de {@code low} à
     * {@code medium} par F-148 / SF-148-01). {@code low} laissait passer du sous-raisonnement en
     * continuation → auto-corrections → tours de rattrapage (le garde-fou F-119 réagit <i>après</i>) ;
     * {@code medium} raisonne assez pour tenir la trajectoire sans « réfléchir fort » comme le premier
     * tour. Le premier tour garde {@link #effort()}. Réglable via {@code APP_ATELIER_STEP_EFFORT} sans
     * livraison (repli sur {@code low} possible dans les deux sens).
     */
    public static final String DEFAULT_STEP_EFFORT = "medium";
    /**
     * Plafond par défaut d'explorations déléguées par message (F-39 / SF-39-14 ; relevé de 3 à 5 par
     * F-148 / SF-148-01). Cinq laisse une investigation multi-zones se faire en un tour ; au-delà,
     * c'est le travail principal qu'il faut redécouper. Réglable via {@code APP_ATELIER_MAX_DELEGATIONS}
     * sans livraison.
     */
    public static final int DEFAULT_MAX_DELEGATIONS = 5;
    /**
     * Effort par défaut de la sous-boucle d'exploration (F-119 / SF-119-01) : {@code low}. Non nul
     * (correctif du {@code none()}), mais sobre — une exploration lit, elle n'a pas à « réfléchir
     * fort » à chaque fichier. Réglable via {@code APP_ATELIER_EXPLORE_EFFORT} sans livraison.
     */
    public static final String DEFAULT_EXPLORE_EFFORT = "low";
    /**
     * Fenêtre de rejeu des trajectoires d'outils à défaut de configuration (F-119 / SF-119-03) :
     * 12 tours (relevé de 5). C'est là que vit le couplage affirmation↔preuve.
     */
    public static final int DEFAULT_REPLAYED_TRACE_TURNS = 12;
    /** Plafond lisible de la fenêtre de rejeu : au-delà, la compaction aurait tranché de toute façon. */
    public static final int MAX_REPLAYED_TRACE_TURNS = 40;
    /**
     * Parallélisme d'exploration par défaut (F-39 / SF-39-21) : 3. Recouvre le temps de mur de
     * plusieurs {@code explore} indépendants sans jamais ouvrir le plafond par message
     * ({@link #maxDelegations()}, porté à 5 par SF-148-01) : le parallélisme borne combien tournent
     * <i>en même temps</i>, pas combien sont autorisées au total.
     */
    public static final int DEFAULT_EXPLORE_PARALLELISM = 3;
    /**
     * Plafond lisible du parallélisme d'exploration : au-delà, on lancerait plus de sous-boucles que
     * de délégations utiles, pour rien. Même esprit que {@link #MAX_REPLAYED_TRACE_TURNS}.
     */
    public static final int MAX_EXPLORE_PARALLELISM = 16;
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
    /**
     * Budget de temps d'un message à défaut de configuration (F-118 / SF-118-03) : 10 min, la valeur
     * livrée. L'écrire ici ne change rien au comportement, il rend le levier réglable sans livraison.
     */
    public static final Duration DEFAULT_TURN_BUDGET = Duration.ofMinutes(10);
    /**
     * Plafond dur du budget de temps : au-delà, le plafond d'itérations ({@code maxIterations}) et la
     * durée de vie du flux SSE auraient tranché de toute façon. Même règle que {@link
     * #MAX_TURN_TOKENS_CEILING} : mieux vaut une borne lisible qu'un plafond sans effet.
     */
    public static final Duration TURN_BUDGET_CEILING = Duration.ofHours(2);

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
            maxDelegations = DEFAULT_MAX_DELEGATIONS;
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
        // Budget de temps du message (F-118 / SF-118-03) : même repli que `maxTurnTokens`. Une valeur
        // absente, nulle, de durée nulle ou négative retombe sur le défaut (10 min, le comportement
        // livré) — une faute de configuration ne doit ni couper les tours à zéro, ni les rendre infinis.
        if (turnBudget == null || turnBudget.isZero() || turnBudget.isNegative()) {
            turnBudget = DEFAULT_TURN_BUDGET;
        }
        // Au-delà du plafond dur, `maxIterations` et la durée de vie du flux SSE auraient tranché de
        // toute façon : on ramène à une borne lisible plutôt qu'à un plafond sans effet.
        if (turnBudget.compareTo(TURN_BUDGET_CEILING) > 0) {
            turnBudget = TURN_BUDGET_CEILING;
        }
        // Effort de la sous-boucle d'exploration (F-119 / SF-119-01) : même repli que `effort`, une
        // faute de frappe retombe sur le défaut (`low`) au lieu d'arrêter le démarrage.
        if (exploreEffort == null || exploreEffort.isBlank() || !ALLOWED_EFFORTS.contains(exploreEffort)) {
            exploreEffort = DEFAULT_EXPLORE_EFFORT;
        }
        // Absent => ré-escalade sur signal active : un réglage manquant ne change pas le comportement
        // livré par F-119 (plein seulement quand un signal de difficulté apparaît).
        if (escalateOnSignal == null) {
            escalateOnSignal = Boolean.TRUE;
        }
        // Fenêtre de rejeu des trajectoires (F-119 / SF-119-03) : même repli que les autres bornes,
        // une valeur absente/nulle/négative retombe sur le défaut, et une valeur déraisonnable est
        // ramenée à un plafond lisible.
        if (replayedTraceTurns == null || replayedTraceTurns <= 0) {
            replayedTraceTurns = DEFAULT_REPLAYED_TRACE_TURNS;
        }
        if (replayedTraceTurns > MAX_REPLAYED_TRACE_TURNS) {
            replayedTraceTurns = MAX_REPLAYED_TRACE_TURNS;
        }
        // Absent => aide-mémoire d'état de fichier actif : un réglage manquant ne change pas le
        // comportement livré par F-119 / SF-119-05.
        if (fileStateHints == null) {
            fileStateHints = Boolean.TRUE;
        }
        // Absent => l'effort voyage dans la conversation (F-134 / SF-134-05). Le réglage n'existe
        // que pour revenir en arrière si la bêta du fournisseur venait à fermer.
        if (perMessageEffort == null) {
            perMessageEffort = DEFAULT_PER_MESSAGE_EFFORT;
        }
        // Parallélisme d'exploration (F-39 / SF-39-21) : même repli que les autres bornes. Une valeur
        // absente, nulle ou < 1 retombe sur le défaut (3) — une faute de config ne doit pas couper la
        // concurrence à zéro ; une valeur déraisonnable est ramenée à un plafond lisible.
        if (exploreParallelism == null || exploreParallelism < 1) {
            exploreParallelism = DEFAULT_EXPLORE_PARALLELISM;
        }
        if (exploreParallelism > MAX_EXPLORE_PARALLELISM) {
            exploreParallelism = MAX_EXPLORE_PARALLELISM;
        }
    }

    /**
     * Constructeur de compatibilité, sans le parallélisme d'exploration (F-39 / SF-39-21) :
     * {@code exploreParallelism} retombe sur son défaut (3). Conserve la forme F-134 (jusqu'à
     * {@code perMessageEffort}) pour n'obliger aucun appelant à exprimer un réglage qu'il n'a pas.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming, String stepEffort, Boolean adaptiveEffort,
            Duration turnBudget, String exploreEffort, Boolean escalateOnSignal,
            Integer replayedTraceTurns, Boolean fileStateHints, Boolean perMessageEffort) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                stepEffort, adaptiveEffort, turnBudget, exploreEffort, escalateOnSignal,
                replayedTraceTurns, fileStateHints, perMessageEffort, null);
    }

    /**
     * Constructeur de compatibilité, sans les réglages F-119 (SF-119-01) : {@code exploreEffort},
     * {@code escalateOnSignal}, {@code replayedTraceTurns} et {@code fileStateHints} retombent sur
     * leurs défauts ({@code low} / actif / 12 / actif). Évite de réécrire les appelants antérieurs à
     * F-119 (et leurs tests) pour des réglages qu'ils n'expriment pas.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming, String stepEffort, Boolean adaptiveEffort,
            Duration turnBudget) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                stepEffort, adaptiveEffort, turnBudget, null, null, null, null, null, null);
    }

    /**
     * Constructeur de compatibilité, sans {@code replayedTraceTurns} ni {@code fileStateHints}
     * (F-119 / SF-119-03, SF-119-05) : ils retombent sur leurs défauts (12 / actif). Conserve la forme
     * de SF-119-01 (jusqu'à {@code escalateOnSignal}) pour les appelants qui l'expriment déjà.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming, String stepEffort, Boolean adaptiveEffort,
            Duration turnBudget, String exploreEffort, Boolean escalateOnSignal) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                stepEffort, adaptiveEffort, turnBudget, exploreEffort, escalateOnSignal, null, null, null,
                null);
    }

    /**
     * Constructeur de compatibilité, sans {@code fileStateHints} (F-119 / SF-119-05) : l'aide-mémoire
     * retombe sur son défaut (actif). Conserve la forme de SF-119-03 (jusqu'à {@code replayedTraceTurns}).
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming, String stepEffort, Boolean adaptiveEffort,
            Duration turnBudget, String exploreEffort, Boolean escalateOnSignal,
            Integer replayedTraceTurns) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                stepEffort, adaptiveEffort, turnBudget, exploreEffort, escalateOnSignal,
                replayedTraceTurns, null, null, null);
    }

    /**
     * Constructeur de compatibilité, sans le budget de temps (F-118 / SF-118-03) : {@code turnBudget}
     * retombe sur son défaut ({@code PT10M}). Évite de réécrire les appelants antérieurs à SF-118-03
     * (et leurs tests) pour un réglage qu'ils n'expriment pas.
     */
    public AtelierProperties(String storage, String bucket, String prefix, Long maxTotalBytes,
            Integer maxEntries, Long maxFileBytes, Integer maxIterations, String model, String effort,
            Boolean contextPruning, Long maxTurnTokens, Integer maxDelegations,
            Boolean storageExecution, Boolean streaming, String stepEffort, Boolean adaptiveEffort) {
        this(storage, bucket, prefix, maxTotalBytes, maxEntries, maxFileBytes, maxIterations, model,
                effort, contextPruning, maxTurnTokens, maxDelegations, storageExecution, streaming,
                stepEffort, adaptiveEffort, null);
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
