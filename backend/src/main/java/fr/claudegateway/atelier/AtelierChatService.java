package fr.claudegateway.atelier;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentContentBlock;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointContext;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointKind;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointRunner;
import fr.claudegateway.atelier.checkpoint.AtelierCheckpointVerdict;
import fr.claudegateway.atelier.dto.AtelierChatResponse.AtelierAction;
import fr.claudegateway.atelier.permission.AtelierPermissionService;
import fr.claudegateway.atelier.permission.PermissionEffect;
import fr.claudegateway.byok.ByokKeyService;
import fr.claudegateway.quota.QuotaService;
import fr.claudegateway.quota.TurnExtras;
import fr.claudegateway.quota.TurnTokens;
import fr.claudegateway.runner.audit.RunnerAuditOutcome;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerConfirmationGate;
import fr.claudegateway.runner.exec.RunnerTargets;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.relay.RelayInterruptTarget;
import fr.claudegateway.runner.relay.RunnerRelayBroadcaster;

/**
 * Cœur de l'Atelier (F-28 / SF-28-02) : orchestre une boucle <b>tool-use</b> où Claude lit et édite
 * les fichiers d'un workspace via des outils exécutés par le backend (aucune exécution de commande —
 * opérations fichiers uniquement, Phase 1). Gateway-First : le backend orchestre, Claude raisonne ;
 * Provider Independence via {@link AiAgentProvider}. Isolation multi-tenant : tout accès aux fichiers
 * et à la conversation passe par {@code user_id}.
 */
@Service
public class AtelierChatService implements RelayInterruptTarget {

    /**
     * Journal du service (F-39 / SF-39-17). Deux lignes par tour — ouverture, fermeture — au niveau
     * {@code info}, et <b>aucun contenu</b> : ni commande, ni sortie, ni chemin. C'est la règle de
     * l'audit runner (SF-38-08), et elle vaut ici : le journal dit ce qui s'est passé, pas ce qui a
     * été lu. Une ligne par itération noierait le journal sous des dizaines d'entrées par message.
     */
    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(AtelierChatService.class);

    /**
     * Garde-fou anti-boucle : nombre maximal d'allers-retours par message, lu dans la configuration
     * (F-28 / SF-28-19, défaut 30).
     *
     * <p>La valeur d'origine — 12 — coupait <b>31 % des demandes</b> de l'usage réel mesuré, dont la
     * médiane est de 6 outils mais la moyenne de 13,8. Ce n'est pas la borne de dépense : le budget
     * de temps du tour et le quota le sont, et ils s'appliquent d'abord. Celle-ci protège d'une
     * boucle qui tournerait en rond.</p>
     */
    private final int maxIterations;
    /**
     * Plafond de consommation d'un <b>message</b> (F-39 / SF-39-15), en tokens traités. Voir
     * {@link AtelierTurnBudget} pour le raisonnement : le nombre d'itérations borne les
     * allers-retours, le budget de temps borne la durée, et jusqu'ici <b>rien</b> ne bornait ce
     * qu'un seul message pouvait consommer.
     */
    private final long maxTurnTokens;
    /** Plafond d'explorations déléguées par message (F-39 / SF-39-14). */
    private final int maxDelegations;
    /** Coupe-circuit de la cible {@code SANDBOX} de la boucle maison (F-39 / SF-39-16). */
    private final boolean storageExecution;
    /**
     * Appel modèle en <b>flux</b> (F-116 / SF-116-01) : quand il est actif, chaque tour consomme le
     * flux SSE du fournisseur et fait défiler le texte mot à mot dès le premier delta. Le corps de
     * requête, le cache, le retry et le décompte d'usage sont inchangés — seul le moment d'affichage
     * change. Coupe-circuit : à {@code false}, l'appel complet historique (texte en fin de tour).
     */
    private final boolean streaming;
    /**
     * Budget de temps effectif d'un message, en millisecondes (F-118 / SF-118-03), lu de
     * {@code app.atelier.turn-budget} (défaut {@code PT10M}, identique à {@link #TURN_BUDGET_MS}). La
     * deadline du tour s'en déduit ; le réglage se relève sans livraison (60 min en production via
     * {@code APP_ATELIER_TURN_BUDGET=PT60M}).
     */
    private final long turnBudgetMs;
    /**
     * Budget de temps d'un tour (F-38 / SF-38-07). Sans lui, 12 itérations × 120 s de {@code bash}
     * dépassent largement la durée de vie du flux SSE : l'émetteur se clôt, l'écran se fige, et la
     * boucle continue d'exécuter des commandes sur la machine de l'utilisateur. Le budget garantit
     * l'inverse : la boucle rend la main <b>avant</b> que le flux expire.
     *
     * <p>Valeur de <b>repli documentaire</b> depuis F-118 / SF-118-03 : le budget effectif est lu de
     * la configuration ({@code app.atelier.turn-budget}, défaut identique {@code PT10M}) dans {@link
     * #turnBudgetMs}. La production le porte à 60 min par {@code APP_ATELIER_TURN_BUDGET=PT60M} sans
     * livraison.</p>
     */
    static final long TURN_BUDGET_MS = 600_000L;
    /** Longueur de la commande relayée à l'écran comme étape de progression (contrat §3). */
    private static final int STEP_COMMAND_CHARS = 200;
    /** Agrégat de sortie de commande conservé et rendu au modèle (contrat §5), en octets. */
    private static final int MAX_BASH_OUTPUT_BYTES = 131_072;
    static final String INTERRUPTED_REPLY = "J'ai arrêté le travail en cours à ta demande.";
    /**
     * Réponse rendue quand le modèle a été coupé au plafond de sortie (SF-28-18). Elle dit les trois
     * choses que l'utilisateur doit savoir : ce qui s'est passé, que <b>rien n'a été exécuté</b>, et
     * quoi faire ensuite. Sans elle, il ne voyait qu'une phrase d'intention suivie de rien.
     */
    static final String TRUNCATED_REPLY =
            "Ma réponse a dépassé la taille maximale autorisée et a été coupée : rien n'a été exécuté. "
                    + "Demande-moi une modification plus courte, ou de travailler fichier par fichier.";
    /**
     * Réponse de <b>dernier recours</b> (F-125 / SF-125-07), honnête et actionnable, quand un tour se
     * clôt normalement sans qu'aucun texte utilisateur n'ait été produit <b>et</b> que la passe de
     * synthèse forcée n'a rien rendu non plus (erreur API, budget épuisé). Elle remplace l'ancien
     * placeholder muet « Je n'ai pas produit de réponse… » qui, en production, écrasait à l'affichage
     * comme en base une réponse que l'utilisateur avait déjà vue défiler — le pire résultat possible.
     *
     * <p>Non cosmétique : l'API refuse un bloc de texte vide, donc un message vide persisté ici
     * rendrait <b>tous</b> les tours suivants de ce projet impossibles — vérifié, {@code 400 "text
     * content blocks must be non-empty"}. L'historique ne doit jamais pouvoir contenir un tel
     * message.</p>
     */
    static final String LAST_RESORT_REPLY =
            "Je me suis arrêté après plusieurs actions sans conclure. Redemande-moi la synthèse.";
    /**
     * Garde <b>interne</b> (F-125 / SF-125-07) : texte non vide donné au <b>modèle</b> lorsqu'un
     * crochet de fin de tour est rejoué sur un tour au texte vide (l'API refuse un bloc de texte
     * vide). Ce marqueur ne vit que dans la conversation renvoyée au modèle : il n'est <b>jamais</b>
     * persisté ni affiché à l'utilisateur. Volontairement neutre — surtout pas un placeholder de
     * non-réponse.
     */
    private static final String EMPTY_TEXT_MODEL_GUARD = "(pas de texte)";
    /**
     * Consigne de la <b>passe de synthèse forcée</b> (F-125 / SF-125-07), jouée UNE fois quand le
     * tour se clôt sans aucun texte utilisateur. Le modèle est relancé sans outils (« pas de
     * plomberie ») et hors de tout crochet de fin de tour.
     */
    static final String SYNTHESIS_PROMPT =
            "Le tour s'achève. Réponds maintenant, directement, à la dernière demande de "
                    + "l'utilisateur, en t'appuyant sur ce que tu viens de faire/observer. Pas de "
                    + "plomberie.";
    static final String BUDGET_REACHED_REPLY =
            "Le temps imparti à ce message est écoulé ; relance-moi pour continuer.";
    /**
     * Réponse rendue quand le tour s'arrête sur le <b>plafond de consommation</b> du message
     * (F-39 / SF-39-15). Volontairement distincte de {@link #BUDGET_REACHED_REPLY} : les confondre
     * ferait proposer « racheter des tokens » à quelqu'un que la montre a arrêté (décision D-L8-5).
     */
    static final String SPEND_CAP_REPLY =
            "Ce message a atteint son plafond de consommation ; le travail déjà fait est conservé, "
                    + "relance-moi pour continuer.";
    /**
     * Réponse rendue quand la fenêtre du modèle est dépassée <b>malgré</b> la compaction (F-117 /
     * SF-117-02). Le filet réactif a résumé l'historique et relancé une fois, mais la conversation
     * déborde encore : plutôt qu'un échec dur silencieux (le comportement d'avant F-117), un message
     * clair qui dit quoi faire — un « nouveau départ » (SF-117-03).
     */
    static final String PROMPT_TOO_LONG_REPLY =
            "Cette conversation est devenue trop longue pour la fenêtre du modèle : j'ai résumé "
                    + "l'historique, mais elle dépasse encore. Fais un « nouveau départ » pour "
                    + "repartir propre — la conversation reste affichée.";
    /** Garde-fou : longueur max de la consigne système (CLAUDE.md + skills). */
    private static final int SYSTEM_MAX_CHARS = 40_000;
    /**
     * Discipline d'investigation (F-119 / SF-119-02, cadrage Cause 2) : des consignes <b>non
     * négociables</b> ajoutées au rôle, sur les deux cibles. Le prompt d'origine était descriptif
     * (« ne suppose rien sur un fichier sans l'avoir lu ») ; rien ne poussait l'agent à se vérifier
     * avant d'affirmer — d'où 39 % d'erreurs « affirmé/généralisé sans vérifier » en prod. Placée en
     * tête (après le rôle), elle survit à la coupe {@link #SYSTEM_MAX_CHARS}. Texte sobre : quelques
     * centaines de caractères de plus dans le préfixe stable, donc cachés (cache de prompt préservé).
     */
    private static final String INVESTIGATION_DISCIPLINE =
            "Discipline de travail, non négociable :\n"
                    + "- Vérifie avant d'affirmer : teste, relis, ou exécute — ne conclus jamais "
                    + "qu'une tâche est faite sans l'avoir prouvé.\n"
                    + "- Ne généralise jamais à partir d'un seul exemple : un cas qui marche ne "
                    + "prouve pas la règle ; confronte-le à d'autres avant d'en tirer une conclusion.\n"
                    + "- Relis la source avant d'affirmer son contenu ; ne cite pas de mémoire ce que "
                    + "tu peux rouvrir.\n"
                    + "- Corrige tôt : si tu doutes, vérifie tout de suite plutôt que d'affirmer puis "
                    + "de te dédire au tour suivant.\n"
                    + "- Quand un outil échoue ou ne rend rien d'exploitable, dis « non concluant » et "
                    + "réessaie ou change d'approche — n'invente pas un résultat, et ne prends pas un "
                    + "échec pour une réponse négative.\n\n";
    /**
     * Doctrine de retenue (F-120 / SF-120-01, cadrage Cause 1/5) : ajoutée au rôle sur les <b>deux</b>
     * cibles, aux côtés de la discipline d'investigation (SF-119-02). Le rôle ne connaissait que
     * l'<i>action</i> (« tu travailles sur le projet, utilise write_file… ») : 53 % des questions du
     * PO déclenchaient une écriture non demandée. La bonne retenue existait déjà, mais cloisonnée à
     * Radar/Pages — on l'élève ici au rang de règle générale. Elle dit <i>quand</i> agir ; la
     * discipline d'investigation dit <i>comment</i> vérifier quand on agit. Placée en tête, elle
     * survit à la coupe {@link #SYSTEM_MAX_CHARS} ; quelques centaines de caractères de plus dans le
     * préfixe stable, donc cachés (cache de prompt préservé).
     */
    private static final String RESTRAINT_DOCTRINE =
            "Répondre d'abord, agir sur demande — non négociable :\n"
                    + "- Une question n'est pas un ordre : quand l'utilisateur pose une question "
                    + "(« qu'as-tu compris ? », « le plan est-il prêt ? », « as-tu pu lire… ? »), "
                    + "réponds-y en texte. N'écris, ne modifie et n'exécute aucune mutation qu'il n'a "
                    + "pas demandée.\n"
                    + "- Lire pour répondre est permis (lire un fichier, lister, chercher, explorer) ; "
                    + "c'est la MUTATION non demandée — écrire ou éditer un fichier, lancer une commande "
                    + "qui change l'état — qui est proscrite.\n"
                    + "- Sur une demande ambiguë, ne devine pas : propose en une phrase ce que tu ferais "
                    + "et attends (« Veux-tu que je le fasse ? »). Agis parce que l'utilisateur te le "
                    + "demande, pas parce qu'un mot (« plan », « feature ») est apparu.\n\n";
    /**
     * Préambule cadrant le {@code CLAUDE.md} du projet injecté verbatim (F-120 / SF-120-01, cadrage
     * Cause 3). Le {@code CLAUDE.md} est souvent un manuel impératif (« avant d'écrire la moindre
     * ligne, produis la mini-spec… », « REFUS si… ») : injecté tel quel, toute évocation de
     * « plan/feature » dans une simple question amorçait la procédure. Le préambule rappelle que ces
     * conventions encadrent le travail <i>quand on implémente à la demande</i>, pas la réponse à une
     * question.
     */
    private static final String GOVERNANCE_PREAMBLE =
            "Les conventions ci-dessous encadrent le travail quand tu IMPLÉMENTES à la demande de "
                    + "l'utilisateur. Elles ne transforment pas une question en ordre : si l'utilisateur "
                    + "pose une simple question, réponds-y sans dérouler de procédure ni rien modifier.\n\n";
    /**
     * Style de réponse (F-121 / SF-121-03) : ajouté au rôle sur les <b>deux</b> cibles, aux côtés de la
     * discipline d'investigation (SF-119-02) et de la doctrine de retenue (SF-120-01). Le prompt
     * principal était muet sur le style — la bonne consigne n'existait que dans la sous-boucle
     * {@code explore} ({@code AtelierExploration.SYSTEM}) : réponse concise orientée terminal, pas de
     * préambule ni de politesse, markdown léger, sources citées en {@code chemin:ligne}, pas d'émoji.
     * On l'élève ici au rang de règle générale du travail principal, sans écraser les deux autres.
     * Placé en tête du préfixe stable, il survit à la coupe {@link #SYSTEM_MAX_CHARS} et reste caché
     * (cache de prompt préservé).
     */
    private static final String RESPONSE_STYLE =
            "Style de réponse (terminal) :\n"
                    + "- Réponds court et droit au but, comme dans un terminal : pas de préambule ni de "
                    + "formule de politesse, pas de conclusion générale qui répète ce qui précède.\n"
                    + "- Markdown léger seulement quand il aide (listes courtes, `code` en ligne) ; "
                    + "évite titres et tableaux pour une réponse brève.\n"
                    + "- Cite tes sources par `chemin:ligne` quand tu renvoies à du code.\n"
                    + "- Pas d'émoji, sauf si l'utilisateur en emploie ou en demande.\n\n";
    /**
     * Silence de la tenue de carte (F-125 / SF-125-01, cadrage §2) : ajouté au rôle sur les
     * <b>deux</b> cibles, aux côtés de la discipline (SF-119-02), de la doctrine (SF-120-01) et du
     * style (SF-121-03). Le cas réel : à une question de fond, l'agent répondait par de la plomberie
     * interne (promotion, destinations, « hors gouvernance », marqueur de fin de tour). La tenue de la
     * carte est un <b>service</b>, pas un sujet de conversation : elle se fait en coulisse et ne
     * s'affiche jamais. Placé en tête du préfixe stable, il survit à la coupe {@link #SYSTEM_MAX_CHARS}
     * et reste caché (cache de prompt préservé).
     */
    private static final String CARD_SILENCE_DOCTRINE =
            "Tenue de la carte, en silence — non négociable :\n"
                    + "- Réponds D'ABORD à la question de l'utilisateur, en clair et sur le fond. "
                    + "C'est la seule chose qu'il attend de ta réponse.\n"
                    + "- La tenue de la carte du poste (ranger un fait durable, choisir où, l'état de "
                    + "la gouvernance) est un travail de COULISSE : tu le fais sans jamais le raconter "
                    + "dans ta réponse.\n"
                    + "- N'emploie pas dans ta réponse les termes de plomberie interne — « promotion », "
                    + "« fin-de-tour », « hors gouvernance », « libellé », une fiche de carte comme "
                    + "« destination ». Si un rangement a échoué ou reste à faire, garde-le pour toi : "
                    + "ne t'en explique pas à l'utilisateur, qui n'en a que faire.\n\n";
    /**
     * Balisage de la réponse essentielle (F-126 / SF-126-01) : ajouté au rôle sur les <b>deux</b>
     * cibles, aux côtés de la discipline (SF-119-02), de la doctrine (SF-120-01), du style (SF-121-03)
     * et du silence de la carte (SF-125-01). Prolonge « réponds d'abord » : l'essentiel est la réponse
     * <i>directe et courte</i> à la question, le reste est le détail. Le frontend met l'essentiel en
     * avant s'il est balisé, et se replie gracieusement sinon — la consigne rend le balisage habituel.
     *
     * <p>Le marqueur {@code <<essentiel>> … <</essentiel>>} est du <b>contenu à afficher</b>, jamais une
     * métadonnée : il ne collisionne pas avec le retrait du marqueur {@code fin-de-tour} (F-125), qui
     * ne vise que le commentaire HTML {@code <!-- fin-de-tour: … -->}. Placé en tête du préfixe stable,
     * il survit à la coupe {@link #SYSTEM_MAX_CHARS} et reste caché (cache de prompt préservé).</p>
     */
    private static final String ESSENTIAL_ANSWER_DOCTRINE =
            "Mets en avant l'essentiel — non négociable :\n"
                    + "- Commence TOUJOURS ta réponse par l'essentiel : la réponse directe et courte à "
                    + "la question posée, comme si on t'avait demandé d'être très concis (une phrase, "
                    + "deux au plus).\n"
                    + "- Encadre cet essentiel par le marqueur dédié, sur ses propres lignes :\n"
                    + "  <<essentiel>>\n"
                    + "  … la réponse directe et courte …\n"
                    + "  <</essentiel>>\n"
                    + "- Écris ENSUITE le détail (le raisonnement, les preuves, les nuances) sous le "
                    + "marqueur de fermeture, en style normal. Le détail n'est pas répété dans "
                    + "l'essentiel.\n"
                    + "- N'emploie ces marqueurs QUE pour encadrer l'essentiel, une seule fois par "
                    + "réponse, et n'en parle jamais dans le texte : ils sont mis en forme pour le "
                    + "lecteur, pas expliqués.\n\n";
    /**
     * Conseil / décision : tranche, ne range pas (F-125 / SF-125-01, prolongé par SF-125-05,
     * cadrage §3). Cas réel : à une question de conseil (« configurer ce qu'il y a dans cette doc,
     * ou ce qu'on a suffit ? »), l'agent répondait par un <b>statut de rangement</b> de la carte
     * (« aucun fait nouveau, rien à ranger… déjà rangés dans acces.md et reseau.md ») — une
     * non-réponse, sans marqueur essentiel. Les doctrines F-125-01 (carte silencieuse) et F-126-01
     * (balisage de l'essentiel) tiennent la plupart du temps mais <i>glissent sur un tour court</i> :
     * le vieux réflexe « carte » reprend. Cette consigne durcit les deux sans les écraser : sur une
     * question de conseil/décision, l'agent prend position, balise l'essentiel <b>même court</b>, et
     * n'emploie <b>jamais</b> une formule de rangement comme réponse.
     *
     * <p>Additif, prompt-only, aucun classifieur d'intention (F-120 gère déjà question vs action).
     * Placé en tête du préfixe stable, il survit à la coupe {@link #SYSTEM_MAX_CHARS} et reste caché
     * (cache de prompt préservé).</p>
     */
    private static final String ADVICE_DECISION_DOCTRINE =
            "Sur une question de conseil ou de décision, tranche — non négociable :\n"
                    + "- Quand l'utilisateur demande un conseil ou une décision (« dois-je… ? », "
                    + "« est-ce que X suffit ? », « tu conseilles quoi ? », « A ou B ? »), PRENDS "
                    + "POSITION : une recommandation nette, une justification courte, et la réserve "
                    + "éventuelle. Jamais de « ça dépend » sans trancher.\n"
                    + "- Si la question est ambiguë ou qu'il te manque de quoi décider, donne D'ABORD "
                    + "ta meilleure recommandation par défaut, puis demande la précision ou dis ce qui "
                    + "manque — ne te défile pas.\n"
                    + "- Balise l'essentiel MÊME sur un tour court : une phrase de conseil est "
                    + "justement le cas où l'essentiel doit ressortir, entre <<essentiel>> et "
                    + "<</essentiel>>.\n"
                    + "- Ne réponds JAMAIS par un statut de rangement de la carte : « rien à ranger », "
                    + "« aucun fait nouveau », « déjà rangé dans X.md », « ce tour n'était qu'un "
                    + "conseil » ne sont PAS des réponses. Si rien n'est à ranger, n'en parle pas — "
                    + "réponds à la question.\n\n";
    private static final List<String> SKILL_PREFIXES = List.of(".claude/skills/", "skills/");
    /**
     * Nombre de skills annoncés dans la consigne (F-39 / SF-39-02, décision D3). Une borne explicite
     * vaut mieux qu'une coupe au caractère près : le point d'arrêt devient prévisible, donc le
     * préfixe cacheable.
     */
    private static final int MAX_SKILLS_ANNOUNCED = 50;
    /** Longueur d'une description de skill dans le catalogue (F-39 / SF-39-02). */
    private static final int SKILL_DESCRIPTION_CHARS = 200;
    /**
     * Fenêtre de rejeu des trajectoires d'outils <b>par défaut</b> avant F-119 (F-39 / SF-39-03,
     * décision D3). Conservée comme repli documentaire ; la valeur effective vit désormais dans
     * {@code app.atelier.replayed-trace-turns} ({@link #replayedTraceTurns}, défaut 12 depuis
     * SF-119-03). Au-delà de la fenêtre, les tours plus anciens sont rejoués en texte seul.
     */
    private static final int REPLAYED_TRACE_TURNS = 5;
    /**
     * Outils confiés à une sous-boucle d'exploration (F-39 / SF-39-14) : la lecture, et rien
     * d'autre. Ni écriture, ni {@code bash}, ni plan, ni délégation récursive.
     *
     * <p>Cet ensemble est <b>la panoplie</b> de la sous-boucle, plus un filtre appliqué à celle du
     * travail principal (F-39 / SF-39-20, décision D1). La nuance a coûté la capacité entière :
     * dériver l'outillage d'une boucle depuis celui d'une autre le rend tributaire d'une décision
     * étrangère, et le jour où SF-39-05 a retiré {@code list_files} et {@code search_files} de la
     * panoplie principale en cible {@code RUNNER}, l'exploration a perdu les deux tiers de la sienne
     * sans qu'une ligne la concernant soit touchée — il ne lui restait que {@code read_file}, donc
     * la capacité de lire un fichier dont elle connaît déjà le chemin exact, et rien pour le
     * trouver.</p>
     */
    private static final java.util.Set<String> READ_ONLY_TOOLS =
            java.util.Set.of("read_file", "list_files", "search_files", "grep", "glob");

    /**
     * Panoplie autorisée en mode {@link AgentTurnMode#ANSWER_PLAN} (F-120 / SF-120-02) : lecture,
     * exploration, et l'outil d'organisation {@code set_plan} — <b>rien qui mute l'état</b>.
     *
     * <p>Liste <b>blanche</b> appliquée à la panoplie construite par {@link #buildTools} : tout ce qui
     * n'y figure pas est retiré. On retire ainsi {@code write_file}, {@code edit_file}, {@code bash},
     * mais aussi les outils de volet (Teams, Radar, courriel, pages), qui peuvent muter un état
     * externe et sortent du cœur lecture/plan. Le choix d'une liste blanche plutôt que d'une liste
     * noire est délibéré : le mode est <b>opt-in</b> (défaut {@link AgentTurnMode#ACT}), donc une
     * sur-restriction échoue du bon côté — ne pas agir — et un outil mutant ajouté demain reste exclu
     * par défaut sans qu'on ait à y penser. {@code set_plan} reste déclaré : c'est un outil
     * d'organisation, pas d'exécution — le cœur d'un « plan mode ».</p>
     */
    private static final java.util.Set<String> ANSWER_PLAN_TOOLS =
            java.util.Set.of("read_file", "list_files", "search_files", "grep", "glob", "explore",
                    "set_plan");
    /**
     * Consigne de mode ajoutée à la consigne système en {@link AgentTurnMode#ANSWER_PLAN}
     * (F-120 / SF-120-02). Cohérente avec la doctrine de retenue SF-120-01, mais plus forte : ici la
     * retenue n'est pas seulement une règle de conduite, c'est l'état du tour — les outils mutants ne
     * sont même pas déclarés. Placée juste après la doctrine, en tête du préfixe (donc à l'abri de la
     * coupe {@link #SYSTEM_MAX_CHARS}).
     */
    private static final String ANSWER_PLAN_DIRECTIVE =
            "Mode Réponse/Plan (l'utilisateur l'a explicitement choisi) :\n"
                    + "- Tu RÉPONDS à la question, ou tu PROPOSES un plan — tu n'exécutes rien.\n"
                    + "- Aucune mutation : tu ne peux ni écrire, ni éditer un fichier, ni lancer de "
                    + "commande (ces outils ne te sont pas donnés dans ce mode). Tu peux lire, "
                    + "explorer, et poser un plan avec set_plan.\n"
                    + "- Présente ce que tu ferais, puis attends : l'utilisateur passera en mode "
                    + "« Agir » (bouton « Passer à l'exécution ») quand il voudra que tu exécutes.\n\n";

    /** Cible d'audit d'une commande (F-38 / SF-38-08) : la ligne du journal, pas un contenu. */
    private static final int AUDIT_TARGET_CHARS = 1_000;
    /**
     * Contexte d'entrée à partir duquel les résultats d'outils périmés sont écartés
     * (F-39 / SF-39-12). Large devant un tour ordinaire, très en deçà de la fenêtre du modèle : ce
     * qui déborde n'est pas la conversation, c'est l'accumulation des sorties d'outils <b>dans un
     * même tour</b> — une seule sortie de commande pèse jusqu'à {@value #MAX_BASH_OUTPUT_BYTES}
     * octets, et un tour en compte jusqu'à trente.
     */
    private static final int CONTEXT_TRIGGER_INPUT_TOKENS = 200_000;
    /** Résultats d'outils toujours conservés : ce sur quoi l'agent travaille à l'instant. */
    private static final int CONTEXT_KEEP_TOOL_RESULTS = 3;
    /**
     * Plancher d'écartement (F-39 / SF-39-12, décision D-L6-9). Une édition modifie le préfixe et
     * invalide donc le cache de prompt à partir du point édité : sans plancher, on paierait une
     * réécriture complète du cache pour quelques centaines de tokens gagnés.
     */
    private static final int CONTEXT_CLEAR_AT_LEAST_INPUT_TOKENS = 20_000;

    private final WorkspaceService workspaceService;
    private final AtelierMessageRepository messageRepository;
    private final AiAgentProvider agentProvider;
    private final ByokKeyService byokKeyService;
    private final QuotaService quotaService;
    /**
     * Modèle de la boucle maison (F-39 / SF-39-10) : un réglage à elle, et non plus le modèle par
     * défaut du catalogue de <b>chat</b> (F-02) — deux features distinctes ne partagent pas un
     * réglage par hasard.
     */
    private final String model;
    /**
     * Raisonnement du <b>premier</b> tour d'une demande (F-39 / SF-39-10, effort adaptatif
     * F-118 / SF-118-01) : effort normal ({@code app.atelier.effort}). C'est le tour où la réflexion
     * sert à cadrer le travail.
     */
    private final AgentReasoning reasoning;
    /**
     * Raisonnement des <b>étapes de continuation</b> (F-118 / SF-118-01) : effort réduit
     * ({@code app.atelier.step-effort}). Enchaîner un outil ou relire un fichier exécute une
     * trajectoire déjà tracée et n'a pas besoin de « réfléchir fort ».
     */
    private final AgentReasoning stepReasoning;
    /**
     * Drapeau de repli de l'effort adaptatif (F-118 / SF-118-01). Faux : l'effort normal est appliqué
     * à chaque étape (comportement d'avant F-118), réglable par variable d'environnement.
     */
    private final boolean adaptiveEffort;
    /**
     * Raisonnement de la <b>sous-boucle d'exploration</b> (F-119 / SF-119-01) : effort configurable
     * non nul ({@code app.atelier.explore-effort}, défaut {@code low}), au lieu du
     * {@code AgentReasoning.none()} d'origine — une exploration lit et interprète, elle ne doit pas
     * investiguer à raisonnement zéro.
     */
    private final AgentReasoning exploreReasoning;
    /**
     * Ré-escalade de l'effort sur signal de difficulté (F-119 / SF-119-01). Vrai : un tour de
     * continuation dont le tour précédent a produit un signal (résultat d'outil en erreur,
     * {@code bash} en code de sortie ≠ 0, {@code edit_file} raté, timeout/indispo runner,
     * auto-contradiction) repasse à l'effort normal. Coupe-circuit à {@code false} : comportement
     * F-118 strict.
     */
    private final boolean escalateOnSignal;
    /**
     * Fenêtre de rejeu des trajectoires d'outils (F-119 / SF-119-03) : les {@code replayedTraceTurns}
     * derniers tours sont rejoués <b>avec</b> leurs résultats d'outils, au-delà en texte seul. Relevée
     * de 5 (constante {@link #REPLAYED_TRACE_TURNS}) à sa valeur par défaut (12) pour garder le
     * couplage affirmation↔preuve plus longtemps. Réglable par {@code app.atelier.replayed-trace-turns}.
     */
    private final int replayedTraceTurns;
    /**
     * L'effort voyage-t-il <b>dans</b> la conversation plutôt qu'à la racine (F-134 / SF-134-05) ?
     * Le changer à la racine vide tout le cache des messages — or il change à presque chaque étape.
     */
    private final boolean perMessageEffort;
    /**
     * Aide-mémoire d'état de fichier (F-119 / SF-119-05) : quand vrai, une édition d'un fichier que le
     * modèle n'a ni lu ni écrit dans ce fil reçoit un rappel léger de lecture-avant-édition. Jamais un
     * refus dur — le disque évite déjà la corruption. Coupe-circuit à {@code false}.
     */
    private final boolean fileStateHints;
    /**
     * Politique de contexte appliquée à chaque itération d'un tour (F-39 / SF-39-12). Elle dit une
     * intention — écarter les résultats d'outils périmés — que le fournisseur traduit ; le mécanisme
     * lui-même n'existe que dans {@code AnthropicAgentProvider}.
     */
    private final AgentContextPolicy contextPolicy;
    private final fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService;
    private final RunnerToolGateway runnerToolGateway;
    private final fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher;
    private final RunnerConfirmationGate confirmationGate;
    private final RunnerAuditService runnerAuditService;
    /**
     * Diffusion des gestes qui doivent atteindre le pod où tourne la boucle (F-38 / SF-38-13).
     * Inerte tant que le relais n'est pas configuré : le chemin mono-pod reste le chemin par défaut.
     */
    private final RunnerRelayBroadcaster relayBroadcaster;
    /** Postes (F-48 / SF-48-01) : l'interpréteur élu est une propriété de la MACHINE, pas du projet. */
    private final fr.claudegateway.runner.host.RunnerHostService runnerHostService;
    /**
     * Points de contrôle de la boucle (F-50 / SF-50-01). Vide tant que rien n'y est branché : le
     * mécanisme existe, il ne fait rien, et le comportement de la boucle est celui d'avant.
     */
    private final AtelierCheckpointRunner checkpointRunner;
    /**
     * Règles de gouvernance du projet (F-51 / SF-51-04). {@link ProjectRulesSource#NONE} tant que
     * rien n'est actif : la consigne système est alors celle d'avant F-51, à l'octet près.
     */
    private final ProjectRulesSource projectRules;
    /** Ce que la gateway sait déjà du client de ce projet (F-136). */
    private final HostKnowledgeSource hostKnowledge;
    /**
     * Catalogue d'outils du volet Teams (F-89 / SF-89-01), <b>et sa garde</b>. Vide tant que le
     * workspace n'est pas un terminal Teams ou que le droit n'est pas ouvert : la panoplie est alors
     * celle d'avant F-89, à l'identique.
     */
    private final fr.claudegateway.teams.TeamsToolCatalog teamsToolCatalog;
    /**
     * Images des moments (F-89 / SF-89-02). {@code null} pour les formes de service antérieures au
     * volet Teams : un moment qui nomme une image est alors refusé, ce qui est le bon défaut — on
     * ne pose jamais un bloc qui renvoie à une image dont on ne sait rien.
     */
    private final fr.claudegateway.teams.block.TeamsMomentImageService momentImages;
    /**
     * Catalogue des outils Radar (F-104 / SF-104-01) <b>et sa garde</b> : vide hors du terminal Teams d'un
     * client suivi par la Vigie, ou sans le droit Vigie.
     */
    private final fr.claudegateway.radar.RadarToolCatalog radarToolCatalog;
    /** Exécution des outils Radar (F-104 / SF-104-01) ; {@code null} pour les formes historiques. */
    private final fr.claudegateway.radar.RadarToolExecutor radarToolExecutor;
    /**
     * L'outil {@code email_me} et sa garde (F-110 / SF-110-02). Injecté par mutateur pour ne toucher à aucune des
     * formes de constructeur conservées : {@code null} (formes historiques, tests) = l'outil n'existe pas.
     */
    private fr.claudegateway.mail.ClientMailTool clientMailTool;
    /**
     * Compaction automatique du fil (F-117 / SF-117-01). Injecté par mutateur pour ne toucher à aucun
     * des constructeurs conservés : {@code null} (formes historiques, tests antérieurs à F-117) = la
     * compaction est inerte, et le fil est rejoué exactement comme avant.
     */
    private AtelierCompactionService compactionService;
    /**
     * L'outil {@code page_publish} <b>et sa garde</b> (F-109 / SF-109-02) : vide hors d'un terminal sur poste,
     * ou sans le droit de l'espace du terminal.
     */
    private final fr.claudegateway.pages.PageToolCatalog pageToolCatalog;
    /** Exécution de {@code page_publish} (F-109 / SF-109-02) ; {@code null} pour les formes historiques. */
    private final fr.claudegateway.pages.PageToolExecutor pageToolExecutor;

    /**
     * Tours pour lesquels une interruption a été demandée (F-38 / SF-38-07, même geste que F-32).
     * Clef {@code userId:workspaceId} : l'isolation est déjà garantie par {@code requireOwned}, la
     * clef composite évite en plus qu'une marque déborde d'un utilisateur à l'autre. Remise à zéro à
     * l'ouverture de chaque tour, pour qu'une interruption arrivée hors run ne tue pas le suivant.
     */
    private final java.util.Set<String> interruptedTurns = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Tours pour lesquels l'utilisateur a autorisé <b>toutes</b> les commandes (F-38 / SF-38-20).
     *
     * <p>La portée est le <b>tour</b>, jamais le projet : la marque est effacée à l'ouverture de
     * chaque message, comme celle d'interruption. C'est ce qui distingue un raccourci d'un
     * renoncement — on autorise ce qu'on a commencé à voir, pas tout ce qui viendra un jour.</p>
     */
    private final java.util.Set<String> blanketAllowedTurns =
            java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * Ce que chaque tour a constaté de la machine (F-93 / SF-93-04), clef {@code userId:workspaceId}.
     *
     * <p>Remis à zéro à l'ouverture de chaque message, comme les deux marques ci-dessus ; mis à jour
     * par chaque appel runner, <b>le dernier faisant foi</b>. Lu en fin de tour : un contrôle qui
     * exige d'écrire sur la machine ne réclame pas une écriture impossible.</p>
     */
    private final java.util.Map<String, fr.claudegateway.atelier.checkpoint.AtelierMachineReach> machineOfTurn =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Fins de tour qu'un contrôle peut refuser dans un même message (F-50 / SF-50-02).
     *
     * <p>Assez pour une correction, sa vérification et un rattrapage ; trop peu pour qu'un contrôle
     * mal écrit transforme un message en boucle coûteuse. Volontairement une <b>constante</b> et non
     * un réglage : F-50 n'expose rien à la configuration, et le plafond d'étapes comme le budget
     * continuent de s'appliquer par-dessus — le crochet ne relève aucune borne existante.</p>
     */
    static final int MAX_END_OF_TURN_BLOCKS = 3;

    /** Titre du bloc de règles de gouvernance dans la consigne système (F-51 / SF-51-04). */
    static final String GOVERNANCE_HEADER = "--- Règles de gouvernance (paquets actifs) ---";

    /** Libellé du bloc de transcription d'un blocage de fin de tour (F-50 / SF-50-02). */
    static final String CHECKPOINT_BLOCK_LABEL = "point de contrôle";

    /**
     * Forme historique, conservée pour les appelants (et les tests) qui l'attendent : aucun point de
     * contrôle branché, donc le comportement d'avant F-50, à l'identique.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                AtelierCheckpointRunner.none(), ProjectRulesSource.NONE,
                fr.claudegateway.teams.TeamsToolCatalog.none(), null);
    }

    /**
     * Forme de F-50, conservée pour les appelants (et les tests) qui branchent des points de contrôle
     * sans se soucier de la gouvernance : aucune règle ajoutée à la consigne système, donc le
     * comportement d'avant F-51, à l'identique.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                checkpointRunner, ProjectRulesSource.NONE,
                fr.claudegateway.teams.TeamsToolCatalog.none(), null);
    }

    /**
     * Forme de F-51, conservée pour les appelants (et les tests) antérieurs au volet Teams : aucun
     * outil {@code teams_*} n'est jamais donné, donc la panoplie d'avant F-89, à l'identique.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner,
            ProjectRulesSource projectRules) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                checkpointRunner, projectRules, fr.claudegateway.teams.TeamsToolCatalog.none(),
                null);
    }

    /**
     * Forme de F-89, conservée pour les appelants (et les tests) antérieurs au Radar : aucun outil
     * {@code radar_*} n'est jamais donné, donc la panoplie d'avant F-104, à l'identique.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner,
            ProjectRulesSource projectRules,
            fr.claudegateway.teams.TeamsToolCatalog teamsToolCatalog,
            fr.claudegateway.teams.block.TeamsMomentImageService momentImages) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                checkpointRunner, projectRules, teamsToolCatalog, momentImages,
                fr.claudegateway.radar.RadarToolCatalog.none(), null);
    }

    /**
     * Forme de F-104, conservée pour les appelants (et les tests) antérieurs aux pages : l'outil
     * {@code page_publish} n'est jamais donné, donc la panoplie d'avant F-109, à l'identique.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner,
            ProjectRulesSource projectRules,
            fr.claudegateway.teams.TeamsToolCatalog teamsToolCatalog,
            fr.claudegateway.teams.block.TeamsMomentImageService momentImages,
            fr.claudegateway.radar.RadarToolCatalog radarToolCatalog,
            fr.claudegateway.radar.RadarToolExecutor radarToolExecutor) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                checkpointRunner, projectRules, teamsToolCatalog, momentImages, radarToolCatalog,
                radarToolExecutor, fr.claudegateway.pages.PageToolCatalog.none(), null,
                HostKnowledgeSource.NONE);
    }

    /**
     * Forme d'avant F-136, conservée pour les appelants (et les tests) antérieurs au savoir du
     * client : {@link HostKnowledgeSource#NONE}, donc le tour d'avant, à l'octet près.
     */
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner,
            ProjectRulesSource projectRules,
            fr.claudegateway.teams.TeamsToolCatalog teamsToolCatalog,
            fr.claudegateway.teams.block.TeamsMomentImageService momentImages,
            fr.claudegateway.radar.RadarToolCatalog radarToolCatalog,
            fr.claudegateway.radar.RadarToolExecutor radarToolExecutor,
            fr.claudegateway.pages.PageToolCatalog pageToolCatalog,
            fr.claudegateway.pages.PageToolExecutor pageToolExecutor) {
        this(workspaceService, messageRepository, agentProvider, byokKeyService, quotaService,
                gitWorkspaceService, runnerToolGateway, runnerCallDispatcher, confirmationGate,
                runnerAuditService, relayBroadcaster, runnerHostService, atelierProperties,
                checkpointRunner, projectRules, teamsToolCatalog, momentImages, radarToolCatalog,
                radarToolExecutor, pageToolCatalog, pageToolExecutor, HostKnowledgeSource.NONE);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public AtelierChatService(WorkspaceService workspaceService, AtelierMessageRepository messageRepository,
            AiAgentProvider agentProvider, ByokKeyService byokKeyService, QuotaService quotaService,
            fr.claudegateway.atelier.git.GitWorkspaceService gitWorkspaceService,
            RunnerToolGateway runnerToolGateway,
            fr.claudegateway.runner.channel.RunnerCallDispatcher runnerCallDispatcher,
            RunnerConfirmationGate confirmationGate,
            RunnerAuditService runnerAuditService,
            RunnerRelayBroadcaster relayBroadcaster,
            fr.claudegateway.runner.host.RunnerHostService runnerHostService,
            AtelierProperties atelierProperties,
            AtelierCheckpointRunner checkpointRunner,
            ProjectRulesSource projectRules,
            fr.claudegateway.teams.TeamsToolCatalog teamsToolCatalog,
            fr.claudegateway.teams.block.TeamsMomentImageService momentImages,
            fr.claudegateway.radar.RadarToolCatalog radarToolCatalog,
            fr.claudegateway.radar.RadarToolExecutor radarToolExecutor,
            fr.claudegateway.pages.PageToolCatalog pageToolCatalog,
            fr.claudegateway.pages.PageToolExecutor pageToolExecutor,
            HostKnowledgeSource hostKnowledge) {
        this.pageToolCatalog = pageToolCatalog == null
                ? fr.claudegateway.pages.PageToolCatalog.none() : pageToolCatalog;
        this.pageToolExecutor = pageToolExecutor;
        this.radarToolCatalog = radarToolCatalog == null
                ? fr.claudegateway.radar.RadarToolCatalog.none() : radarToolCatalog;
        this.radarToolExecutor = radarToolExecutor;
        this.teamsToolCatalog = teamsToolCatalog == null
                ? fr.claudegateway.teams.TeamsToolCatalog.none() : teamsToolCatalog;
        this.momentImages = momentImages;
        this.checkpointRunner = checkpointRunner;
        this.projectRules = projectRules == null ? ProjectRulesSource.NONE : projectRules;
        // Repli NONE, même geste que les règles : sans le module de gouvernance (les tests de
        // la boucle s'en passent), le tour est celui d'avant F-136, à l'octet près.
        this.hostKnowledge = hostKnowledge == null ? HostKnowledgeSource.NONE : hostKnowledge;
        this.workspaceService = workspaceService;
        this.messageRepository = messageRepository;
        this.agentProvider = agentProvider;
        this.byokKeyService = byokKeyService;
        this.quotaService = quotaService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.runnerToolGateway = runnerToolGateway;
        this.runnerCallDispatcher = runnerCallDispatcher;
        this.confirmationGate = confirmationGate;
        this.runnerAuditService = runnerAuditService;
        this.relayBroadcaster = relayBroadcaster;
        this.runnerHostService = runnerHostService;
        this.maxIterations = atelierProperties.maxIterations();
        this.maxTurnTokens = atelierProperties.maxTurnTokens();
        this.turnBudgetMs = atelierProperties.turnBudget().toMillis();
        this.maxDelegations = atelierProperties.maxDelegations();
        this.storageExecution = atelierProperties.storageExecution();
        this.streaming = !Boolean.FALSE.equals(atelierProperties.streaming());
        this.model = atelierProperties.model();
        this.reasoning = new AgentReasoning(true, atelierProperties.effort());
        this.stepReasoning = new AgentReasoning(true, atelierProperties.stepEffort());
        this.adaptiveEffort = !Boolean.FALSE.equals(atelierProperties.adaptiveEffort());
        this.exploreReasoning = new AgentReasoning(true, atelierProperties.exploreEffort());
        this.escalateOnSignal = !Boolean.FALSE.equals(atelierProperties.escalateOnSignal());
        this.replayedTraceTurns = atelierProperties.replayedTraceTurns();
        this.perMessageEffort = Boolean.TRUE.equals(atelierProperties.perMessageEffort());
        this.fileStateHints = !Boolean.FALSE.equals(atelierProperties.fileStateHints());
        this.contextPolicy = Boolean.TRUE.equals(atelierProperties.contextPruning())
                ? new AgentContextPolicy(true, CONTEXT_TRIGGER_INPUT_TOKENS,
                        CONTEXT_KEEP_TOOL_RESULTS, CONTEXT_CLEAR_AT_LEAST_INPUT_TOKENS)
                : AgentContextPolicy.none();
    }

    /** Branche l'outil {@code email_me} (F-110 / SF-110-02). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setClientMailTool(fr.claudegateway.mail.ClientMailTool clientMailTool) {
        this.clientMailTool = clientMailTool;
    }

    /** Branche la compaction automatique du fil (F-117 / SF-117-01). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCompactionService(AtelierCompactionService compactionService) {
        this.compactionService = compactionService;
    }

    /**
     * Porte les fichiers déposés dans la consigne du tour (F-115 / SF-115-03). Injecté par mutateur
     * ({@code null} pour les formes historiques / tests antérieurs à F-115) : sans lui, la consigne est
     * inchangée — comportement d'avant F-115.
     */
    private fr.claudegateway.atelier.deposit.DepositConsumptionService depositConsumptionService;

    /** Branche l'ajout des fichiers déposés à la consigne du tour (F-115 / SF-115-03). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setDepositConsumptionService(
            fr.claudegateway.atelier.deposit.DepositConsumptionService depositConsumptionService) {
        this.depositConsumptionService = depositConsumptionService;
    }

    /**
     * Politique de permission allow/ask/deny persistée par workspace/user (F-121 / SF-121-02). Injectée
     * par mutateur pour ne toucher à aucun des constructeurs conservés : {@code null} (formes
     * historiques, tests antérieurs à F-121) ⇒ la porte retombe sur son comportement binaire d'avant
     * (bash confirmé selon {@code agent_ask_before_bash}, éditions jamais confirmées).
     */
    private AtelierPermissionService permissionService;

    /**
     * Défaut « demander avant une édition » (F-121 / SF-121-02, équivalent <i>acceptEdits</i>) :
     * quand aucune règle ne couvre {@code edit_file}/{@code write_file}, faut-il demander ? Réglable
     * par {@code app.atelier.ask-before-edit} (défaut {@code false} : les éditions ne sont pas
     * confirmées, comportement d'avant F-121). Une règle persistée l'emporte toujours sur ce défaut.
     */
    @org.springframework.beans.factory.annotation.Value("${app.atelier.ask-before-edit:false}")
    private boolean askBeforeEdit;

    /** Branche la politique de permission persistée (F-121 / SF-121-02). */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPermissionService(AtelierPermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /** Réglage du défaut « demander avant une édition » — exposé pour les tests (F-121 / SF-121-02). */
    void setAskBeforeEdit(boolean askBeforeEdit) {
        this.askBeforeEdit = askBeforeEdit;
    }

    /**
     * Traite un message d'atelier : boucle tool-use jusqu'à la réponse finale, persiste l'échange,
     * comptabilise l'usage. Le workspace est vérifié possédé par l'utilisateur (404 sinon) et le quota
     * contrôlé avant tout appel fournisseur.
     *
     * <p>Volontairement <b>non transactionnel</b> : chaque écriture de fichier ({@code writeFile}) a sa
     * propre transaction. Un outil qui échoue (chemin invalide…) est renvoyé au modèle comme erreur sans
     * empoisonner la conversation (pas de rollback-only propagé). Les persistances (messages, usage) sont
     * atomiques par appel de repository.</p>
     */
    public AtelierChatResult chat(UUID userId, UUID workspaceId, String rawMessage) {
        return runLoop(userId, workspaceId, rawMessage, AgentTurnMode.ACT, AtelierProgressListener.NOOP);
    }

    /**
     * Variante portant le <b>mode</b> du tour (F-120 / SF-120-02). En {@link AgentTurnMode#ANSWER_PLAN},
     * la boucle ne déclare que les outils de lecture/exploration/plan et ajoute la consigne de mode ;
     * en {@link AgentTurnMode#ACT} (ou {@code null}), comportement historique.
     */
    public AtelierChatResult chat(UUID userId, UUID workspaceId, String rawMessage, AgentTurnMode mode) {
        return runLoop(userId, workspaceId, rawMessage, mode, AtelierProgressListener.NOOP);
    }

    /**
     * Variante <b>streaming</b> (SF-28-05) : boucle tool-use identique à {@link #chat}, mais notifie
     * chaque étape (action fichier, commentaire de tour) via le {@code listener} pour un relais SSE au
     * fil de l'eau. Le résultat final ({@link AtelierChatResult}) et la persistance sont identiques —
     * seul le retour d'information intermédiaire diffère (zéro régression sur le mode synchrone).
     */
    public AtelierChatResult chatStreaming(UUID userId, UUID workspaceId, String rawMessage,
            AtelierProgressListener listener) {
        return runLoop(userId, workspaceId, rawMessage, AgentTurnMode.ACT, listener);
    }

    /**
     * Variante <b>streaming</b> portant le {@code mode} du tour (F-120 / SF-120-02). Identique à
     * {@link #chatStreaming(UUID, UUID, String, AtelierProgressListener)}, mais la panoplie et la
     * consigne système suivent le mode. {@code null} ⇒ {@link AgentTurnMode#ACT}.
     */
    public AtelierChatResult chatStreaming(UUID userId, UUID workspaceId, String rawMessage,
            AgentTurnMode mode, AtelierProgressListener listener) {
        return runLoop(userId, workspaceId, rawMessage, mode, listener);
    }

    /**
     * Effort de raisonnement de l'itération, selon l'étape (F-118 / SF-118-01).
     *
     * <p><b>Premier tour d'une demande</b> ({@code iteration == 0}) : effort <b>normal</b> — la
     * réflexion sert à cadrer le travail et à choisir la trajectoire. <b>Étapes de continuation</b>
     * ({@code iteration > 0}, enchaîner un outil, relire un fichier) : effort <b>réduit</b>, la
     * trajectoire étant déjà tracée. Le raisonnement adaptatif reste actif dans les deux cas ; seul le
     * niveau d'effort change.</p>
     *
     * <p>Garder l'effort normal au tour qui <i>planifie</i> est ce qui garantit qu'une vraie tâche de
     * raisonnement n'est jamais dégradée. Le repli {@code adaptiveEffort == false} rétablit l'effort
     * normal à chaque étape, sans livraison.</p>
     *
     * <p><b>Ré-escalade sur signal</b> (F-119 / SF-119-01) : {@code escalate} vrai signale que le tour
     * <i>précédent</i> a rencontré une difficulté (résultat d'outil en erreur, {@code bash} en code de
     * sortie ≠ 0, {@code edit_file} raté, timeout/indispo runner, auto-contradiction du modèle). Ce
     * sont justement les tours d'investigation/correction : l'effort y remonte au normal au lieu de
     * rester à {@code stepEffort}. Une trajectoire qui roule sans incident garde l'effort réduit — le
     * gain de vitesse/coût de F-118 est préservé. Sous coupe-circuit {@code escalateOnSignal == false},
     * le signal est ignoré (comportement F-118 strict).</p>
     */
    AgentReasoning reasoningForIteration(int iteration, boolean escalate) {
        if (adaptiveEffort && iteration > 0 && !(escalate && escalateOnSignal)) {
            return stepReasoning;
        }
        return reasoning;
    }

    /** Forme historique : aucune ré-escalade. Conservée pour les appelants qui l'attendent. */
    AgentReasoning reasoningForIteration(int iteration) {
        return reasoningForIteration(iteration, false);
    }

    /**
     * Vrai si un appel d'outil et son résultat constituent un <b>signal de difficulté</b> qui doit
     * faire remonter l'effort au tour suivant (F-119 / SF-119-01) : un résultat en erreur (échec
     * d'{@code edit_file}, timeout/indispo runner, argument manquant, refus…), ou un {@code bash} dont
     * le code de sortie est non nul — l'appel a réussi mais la commande a échoué, ce que
     * {@code isError} ne capte pas.
     */
    private static boolean isDifficultySignal(AgentToolCall call, ToolOutcome outcome) {
        if (outcome.isError()) {
            return true;
        }
        return "bash".equals(call.name()) && bashExitNonZero(outcome.content());
    }

    /** Motif du code de sortie ajouté par {@link #bashOutcome} : {@code [code de sortie: N]}. */
    private static final java.util.regex.Pattern BASH_EXIT_CODE =
            java.util.regex.Pattern.compile("\\[code de sortie: (\\d+)\\]");

    /**
     * Vrai si le résultat d'un {@code bash} porte un code de sortie <b>non nul</b> (F-119 / SF-119-01).
     * On lit la <b>dernière</b> occurrence du marqueur : c'est celle que la gateway appose en fin de
     * sortie (contrat SF-38-07). Un code « inconnu » ou absent n'est pas un signal — on n'invente pas
     * un échec.
     */
    private static boolean bashExitNonZero(String content) {
        if (content == null || content.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher matcher = BASH_EXIT_CODE.matcher(content);
        int code = 0;
        boolean found = false;
        while (matcher.find()) {
            found = true;
            code = Integer.parseInt(matcher.group(1));
        }
        return found && code != 0;
    }

    /**
     * Marqueurs sobres d'<b>auto-contradiction</b> (F-119 / SF-119-01) : le symptôme décrit par le PO
     * (« il dit "je me suis trompé" et corrige »). Liste fermée, conservatrice — mieux vaut un faux
     * négatif qu'une ré-escalade injustifiée. Comparaison insensible à la casse.
     */
    private static final List<String> SELF_CORRECTION_MARKERS = List.of(
            "je me suis trompé", "je me suis trompée", "je m'étais trompé", "je m'étais trompée",
            "erreur de ma part", "au temps pour moi", "autant pour moi", "c'était faux",
            "je me corrige", "je reviens sur", "je me suis fourvoyé", "je me suis induit en erreur",
            "en fait, non", "mea culpa");

    /** Vrai si le texte du tour porte un marqueur d'auto-contradiction (F-119 / SF-119-01). */
    private static boolean looksLikeSelfCorrection(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        for (String marker : SELF_CORRECTION_MARKERS) {
            if (lower.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private AtelierChatResult runLoop(UUID userId, UUID workspaceId, String rawMessage,
            AgentTurnMode mode, AtelierProgressListener listener) {
        // Le mode (F-120 / SF-120-02) est normalisé ici : un mode absent vaut ACT (comportement
        // d'avant). Il ne change que la panoplie déclarée et la consigne système — jamais l'isolation.
        AgentTurnMode turnMode = mode == null ? AgentTurnMode.ACT : mode;
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 si non possédé (isolation) — TOUJOURS en premier
        // Mode « Assistant » sur un projet Git (F-31 / SF-31-03) : cette boucle lit et édite le
        // stockage objet, vide sur ce type de projet. Répondre quand même reviendrait à commenter un
        // projet inexistant ; le mode Terminal, lui, a le dépôt réellement cloné.
        //
        // Le garde-fou ne vaut QUE pour la cible SANDBOX (F-38 / SF-38-05) : en cible RUNNER, les
        // outils s'exécutent sur la machine de l'utilisateur, où le dépôt est réellement cloné. Un
        // projet Git + RUNNER est donc légitime — le refuser serait un faux positif.
        // Cible SANDBOX sur la boucle maison (F-39 / SF-39-16) : chemin fermé par défaut. Depuis le
        // lot 4, l'écran ne l'emprunte plus — un projet sans runner passe par les Managed Agents.
        // Refus AVANT tout appel fournisseur et avant le contrôle de quota : aucun token consommé,
        // aucun message persisté. Posé APRÈS `requireOwned` (D3) : un projet d'autrui rend 404, et
        // jamais un refus qui révélerait son existence.
        if (!workspace.isRunnerTarget() && !storageExecution) {
            throw new StorageExecutionClosedException(
                    "Ce projet n'a pas de machine connectée : son terminal passe par le bac à sable "
                            + "hébergé.");
        }
        if (!workspace.isRunnerTarget()) {
            gitWorkspaceService.requireArchiveChatMode(workspace);
        }
        // Mode BYOK (clé personnelle active) vs Hosted (clé plateforme) : en BYOK, les tokens sont sur
        // le compte Anthropic de l'utilisateur => aucun contrôle ni comptage du quota plateforme (F-28 /
        // SF-28-06). En Hosted, comportement historique : contrôle avant + comptabilisation après.
        String apiKey = byokKeyService.resolveActiveApiKey(userId).orElse(null);
        boolean hosted = apiKey == null;
        // Plafond de consommation de CE message (F-39 / SF-39-15). En Hosted il est borné par le
        // quota restant de l'utilisateur du contexte de sécurité — un message ne consomme jamais
        // plus que ce qui a été payé, règle de F-36 transposée à la boucle maison. En BYOK, les
        // tokens sont sur le compte du client et aucun quota n'est tenu (SF-28-06) : seul le
        // réglage s'applique.
        AtelierTurnBudget turnBudget;
        if (hosted) {
            quotaService.assertWithinQuota(userId);
            turnBudget = AtelierTurnBudget.hosted(maxTurnTokens,
                    quotaService.currentUsage(userId).remainingTokens());
        } else {
            turnBudget = AtelierTurnBudget.byok(maxTurnTokens);
        }
        // Une interruption demandée alors qu'aucun tour ne tournait ne doit pas tuer celui-ci
        // (même précaution que F-32 SF-32-01).
        interruptedTurns.remove(turnKey(userId, workspaceId));
        // La marque « tout autoriser » ne survit jamais au message qui l'a reçue (SF-38-20).
        blanketAllowedTurns.remove(turnKey(userId, workspaceId));
        // Ni la machine d'hier ni celle d'un tour abandonné ne jugent ce tour-ci (F-93 / SF-93-04).
        machineOfTurn.remove(turnKey(userId, workspaceId));
        long startedAt = System.currentTimeMillis();
        long deadline = startedAt + turnBudgetMs;
        String userText = rawMessage.trim();

        // Compaction automatique du fil (F-117 / SF-117-01), AVANT de bâtir la requête : si le texte
        // rejoué dépasse le seuil de sécurité sous la fenêtre du modèle, les tours anciens sont
        // résumés et la frontière `chatThreadStartedAt` avancée — l'historique rejoué ci-dessous part
        // alors du résumé + des tours récents. Inerte tant que le service n'est pas branché ou que le
        // seuil n'est pas franchi (comportement d'avant F-117). Best-effort : un échec ne casse rien.
        AtelierCompactionService.CompactionOutcome compaction =
                AtelierCompactionService.CompactionOutcome.NONE;
        if (compactionService != null) {
            compaction = compactionService.compactIfOversized(userId, workspace, apiKey);
        }

        // Historique de l'atelier (texte) + nouveau message utilisateur.
        // L'historique rejoué démarre à la frontière du fil (SF-39-04) : après un « nouveau
        // départ », les tours d'avant restent lisibles à l'écran mais ne repartent plus chez le
        // fournisseur.
        // F-115 / SF-115-03 : les fichiers déposés depuis le dernier tour entrent dans la CONSIGNE du
        // tour — chemins seulement, jamais le binaire (comme Claude Code) —, et sont marqués consommés.
        // La note augmente le message ENVOYÉ au modèle ; le message PERSISTÉ reste la parole de
        // l'utilisateur (le fil montre déjà le bloc « fichier déposé », SF-115-02). Inerte si le service
        // n'est pas branché (comportement d'avant F-115). Best-effort : un échec ne casse pas le tour.
        String consigne = userText;
        if (depositConsumptionService != null) {
            try {
                String depositNote = depositConsumptionService.consumeForTurn(userId, workspaceId);
                if (depositNote != null && !depositNote.isBlank()) {
                    consigne = depositNote + "\n\n" + userText;
                }
            } catch (RuntimeException ex) {
                log.debug("Consigne des fichiers déposés ignorée (best-effort) : {}", ex.getMessage());
            }
        }

        // F-137 / SF-137-01 — LES FAITS DÉJÀ CONNUS sur ce que la question mentionne, joints à la
        // CONSIGNE du tour. Jamais à la consigne système : ils dépendent de la question, donc
        // changent à chaque tour, et invalideraient le cache du préfixe à chaque demande (F-134).
        // Même patron que F-115 / SF-115-03 : la consigne ENVOYÉE est augmentée, le message
        // PERSISTÉ reste la parole de l'utilisateur. Best-effort : un échec ne casse pas le tour.
        try {
            String knownFacts = hostKnowledge.factsFor(userId, workspaceId, userText);
            if (knownFacts != null && !knownFacts.isBlank()) {
                consigne = knownFacts + "\n" + consigne;
            }
        } catch (RuntimeException ex) {
            log.debug("Rappel de faits ignoré (best-effort) : {}", ex.getMessage());
        }

        List<AgentMessage> messages = buildReplayMessages(userId, workspace);
        messages.add(AgentMessage.userText(consigne));

        AtelierMessage savedUserMessage = messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("USER").content(userText).build());
        // F-104 / SF-104-01 : la parole de l'utilisateur est la preuve des écritures Radar de ce tour. Une
        // seule preuve par message (idempotente par son identifiant), créée à la première écriture.
        fr.claudegateway.radar.RadarNote turnNote = fr.claudegateway.radar.RadarNote.ofTerminalMessage(
                savedUserMessage != null && savedUserMessage.getId() != null
                        ? savedUserMessage.getId() : UUID.randomUUID(),
                userText, java.time.OffsetDateTime.now());

        String system = buildSystemPrompt(userId, workspace, turnMode);
        List<AgentTool> tools = buildTools(userId, workspace, turnMode);

        // Plan du tour (F-39 / SF-39-13) : local, donc jamais partagé entre utilisateurs.
        java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn =
                new java.util.concurrent.atomic.AtomicReference<>(AtelierPlan.EMPTY);
        List<AtelierAction> actions = new ArrayList<>();
        /** Trajectoire du tour (SF-39-03), rejouée au message suivant. */
        List<AtelierToolTrace.Step> trace = new ArrayList<>();
        /** Transcription rendue à l'écran au rechargement (SF-39-17), bornée à la persistance. */
        List<AtelierTurnReport.Block> transcript = new ArrayList<>();
        /**
         * Blocs riches posés pendant ce tour (F-89 / SF-89-02), par identifiant d'appel. Local au
         * TOUR, jamais au service : celui-ci est un singleton partagé par tous les utilisateurs, et
         * un champ d'instance ferait fuiter le compte rendu de l'un chez l'autre — même parade que
         * pour le plan (SF-39-13).
         */
        java.util.Map<String, fr.claudegateway.teams.block.TeamsBlockCard> cardsOfTurn =
                new java.util.HashMap<>();
        /** Reçus des courriels mis en file pendant ce tour (F-110 / SF-110-02), local au tour. */
        java.util.Map<String, fr.claudegateway.mail.ClientMailReceipt> emailsOfTurn = new java.util.HashMap<>();
        /** Pages publiées pendant ce tour (F-109 / SF-109-03), par appel, local au tour. */
        java.util.Map<String, fr.claudegateway.pages.PageBlock> pagesOfTurn = new java.util.HashMap<>();
        // La compaction (F-117 / SF-117-01) est un appel modèle : sa consommation entre dans les
        // compteurs du tour dès le départ, pour passer par le décompte d'usage existant
        // (`recordUsage`) sans chemin de quota séparé ni double comptage.
        int inputTokens = compaction.inputTokens();
        int outputTokens = compaction.outputTokens();
        /**
         * Ventilation du cache du tour (F-63 / SF-63-02). Ces tokens sont <b>déjà compris</b> dans
         * {@code inputTokens} — le plafond de message et le relevé affiché comptent, comme avant, ce
         * qui a été <b>traité</b>. Ils voyagent à part pour le seul décompte du quota, qui les
         * facture à leur prix : un dixième du tarif d'entrée en lecture, 1,25× en écriture.
         */
        int cacheReadTokens = compaction.cacheReadTokens();
        int cacheWriteTokens = compaction.cacheWriteTokens();
        /**
         * Recherches web du tour (F-133 / SF-133-08). Facturées <b>à la requête</b> — 10 $ les
         * mille — en plus des tokens qu'elles rapportent : aucun compteur de tokens ne les révèle,
         * et l'outil de recherche est déclaré à chaque appel d'agent.
         */
        int webSearchRequests = 0;
        /**
         * Niveau d'effort <b>en vigueur</b> dans la conversation (F-134 / SF-134-05). Une consigne
         * n'est glissée que lorsque le niveau change : un message de plus est un octet de plus dans
         * le ruban, et le ruban est ce qu'on cherche à garder stable. Tableau d'une case parce que
         * la valeur est relue et réécrite depuis la boucle.
         */
        final String[] effortInEffect = {reasoning.effort()};
        /** Plus grosse itération observée dans ce tour : majorant de la suivante (D-L8-2). */
        long largestIterationTokens = 0L;
        boolean interrupted = false;
        boolean spendCapReached = false;
        /**
         * Filet réactif du dépassement de fenêtre (F-117 / SF-117-02) : posé à la première
         * compaction forcée déclenchée par un 400 « prompt too long ». Borne le filet à <b>une</b>
         * compaction + relance par message — si ça déborde encore, on rend un message clair plutôt
         * que de boucler.
         */
        boolean promptTooLongHandled = false;
        /** Explorations déjà déléguées dans ce message (F-39 / SF-39-14). */
        int delegations = 0;
        /**
         * Fins de tour refusées dans ce message (F-50 / SF-50-02). Bornées : un contrôle qui bloque
         * quoi qu'il arrive ferait payer à l'utilisateur le prix d'une règle mal écrite.
         */
        int endOfTurnBlocks = 0;
        /**
         * Chemins écrits pendant le tour, dans l'ordre et sans doublon (F-50 / SF-50-02) : c'est ce
         * qu'un contrôle de fin de tour doit pouvoir regarder. Une écriture <b>bloquée</b> y figure
         * aussi — le fichier a bel et bien été écrit.
         */
        java.util.Set<String> writtenPaths = new java.util.LinkedHashSet<>();
        /**
         * F-89 / SF-89-11 : une lecture Teams a rendu un zéro dans CE tour. Tant que c'est vrai — et
         * que l'utilisateur n'a pas autorisé le repli —, les outils de fond du poste (bash, read_file…)
         * sont refusés : on ne répond pas la question depuis le projet sans un geste explicite.
         */
        boolean teamsReadFailedThisTurn = false;
        /**
         * F-89 / SF-89-11 : l'utilisateur a explicitement autorisé le repli sur le poste (bouton
         * « Chercher dans le projet », ou précision équivalente). Le repli est alors permis, et un
         * bandeau marque la réponse comme venant du projet, pas de Teams.
         */
        boolean fallbackAuthorized = workspace.isTeamsTerminal()
                && fr.claudegateway.teams.block.TeamsReadFailure.authorizesFallback(userText);
        // F-89 / SF-89-11 : le repli autorisé se voit AVANT la réponse — un bandeau « réponse basée
        // sur le projet, pas sur Teams », posé en tête du tour, par la couleur autant que par le texte.
        if (fallbackAuthorized) {
            String bannerId = "project-fallback-" + UUID.randomUUID();
            fr.claudegateway.teams.block.TeamsBlockCard banner =
                    fr.claudegateway.teams.block.TeamsReadFailure.fallbackBanner();
            listener.onCard(bannerId, banner);
            transcript.add(new AtelierTurnReport.Block("teams_project_fallback", null, bannerId, null,
                    "", false, false, false, banner));
        }
        String finalText = "";
        // F-125 / SF-125-07 + SF-125-08 — la réponse conservée est le TEXTE DE FOND du tour, pas le
        // dernier texte. Un bloc <<essentiel>> (F-126) émis tôt ne peut plus être évincé par une note
        // de plomberie de carte émise tard (cas réel CAGIP : essentiel+détail streamés, puis une note
        // « déjà dans acces.md:465 » persistée à leur place). `backgroundText` retient ce texte de fond
        // — l'essentiel en priorité, sinon le dernier texte substantiel qui n'est PAS de la plomberie
        // de carte. Il survit aux itérations postérieures et fait, à la clôture, converger l'affichage
        // (événement `done`) et la base sur la même valeur (SF-125-07).
        String backgroundText = "";
        // Vrai dès qu'un bloc <<essentiel>> a été retenu : un texte postérieur SANS essentiel ne peut
        // plus l'évincer (SF-125-08 §1) ; seul un essentiel plus tardif le remplace (dernier essentiel).
        boolean retainedHasEssential = false;

        log.info("Tour d'atelier ouvert (workspace={}, cible={}, plafond={} étapes)",
                workspaceId, workspace.executionTargetOrDefault(), maxIterations);
        int iterationsUsed = 0;
        // Ré-escalade de l'effort sur signal (F-119 / SF-119-01) : vrai quand le tour PRÉCÉDENT a
        // rencontré une difficulté. Consulté à l'ouverture du tour suivant pour choisir l'effort, puis
        // recalculé après l'exécution des outils de ce tour.
        boolean escalateNextTurn = false;
        // Aide-mémoire d'état de fichier (F-119 / SF-119-05) : les chemins que le modèle a lus ou
        // écrits dans CE fil. Une édition d'un chemin absent de cet ensemble est « à l'aveugle » et
        // reçoit un rappel léger de lecture-avant-édition.
        java.util.Set<String> knownFiles = new java.util.HashSet<>();

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            iterationsUsed = iteration + 1;
            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                finalText = INTERRUPTED_REPLY;
                interrupted = true;
                break;
            }
            if (System.currentTimeMillis() >= deadline) {
                // Frontière sûre : on s'arrête ici plutôt que de laisser tourner des commandes
                // derrière un flux SSE déjà expiré.
                finalText = BUDGET_REACHED_REPLY;
                break;
            }
            // Plafond de consommation du message (F-39 / SF-39-15). On renonce AVANT l'appel qui
            // ferait franchir le plafond, l'itération à venir étant majorée par la plus grosse déjà
            // observée : rien ici ne peut refuser un appel une fois parti, et le constater après
            // coup autoriserait une itération entière au-delà du plafond (D-L8-2). La première
            // itération n'est jamais refusée (D-L8-3).
            if (turnBudget.exceededBy((long) inputTokens + outputTokens, largestIterationTokens)) {
                finalText = SPEND_CAP_REPLY;
                spendCapReached = true;
                break;
            }
            // Précisions déposées pendant le tour (F-39 / SF-39-19, portées au tour vivant par
            // F-84 / SF-84-06) : lues ICI, à la frontière sûre, et jamais au milieu d'un appel —
            // modifier la conversation pendant qu'elle part au fournisseur serait la façon la plus
            // sûre de la corrompre (D2). Prises APRÈS les arrêts subis : une précision annoncée
            // « prise en compte » doit réellement partir au modèle ; celles qui restent sont rendues
            // au tour vivant (tour de suite, ou abandon dit).
            for (AtelierProgressListener.AtelierSteer steer : listener.takeSteers()) {
                messages.add(AgentMessage.userText(steer.text()));
                // Persistée à sa place chronologique, entre la demande et la réponse : le fil
                // rechargé montre ce que l'utilisateur a dit, et quand.
                messageRepository.save(AtelierMessage.builder()
                        .workspaceId(workspaceId).userId(userId).role("USER").content(steer.text())
                        .build());
                listener.onSteerApplied(steer, iteration + 1);
            }
            // Streaming mot à mot (F-116 / SF-116-01) : quand le flux est actif, le texte défile dans
            // la ligne vivante DÈS le premier delta, au lieu d'attendre la fin du tour (~100 % de
            // l'écart de ressenti avec Claude Code). Le sink note s'il a émis du texte, pour ne pas
            // relayer une SECONDE fois le commentaire complet plus bas — le corps de requête, le cache
            // et le décompte d'usage restent, eux, strictement ceux du non streamé.
            //
            // Repli sur débordement de fenêtre (F-117 / SF-117-02) : un 400 « prompt too long » remonte
            // désormais en AgentPromptTooLongException. Au lieu de tuer le tour, on force une compaction
            // (SF-117-01), on rebâtit la conversation depuis la nouvelle frontière + résumé, et on
            // relance UNE fois. Si ça dépasse encore, message clair — plus jamais d'échec dur.
            boolean textAlreadyStreamed = false;
            AgentTurn turn = null;
            boolean promptOverflow = false;
            while (turn == null) {
                boolean[] streamed = {false};
                AgentReasoning wanted = reasoningForIteration(iteration, escalateNextTurn);
                // L'effort voyage DANS la conversation plutôt qu'à la racine de la requête
                // (F-134 / SF-134-05). Le changer à la racine vide tout le cache des messages : or
                // F-118 le baisse dès la 2ᵉ étape et F-119 le remonte sur difficulté, si bien que
                // l'optimisation de vitesse annulait celle du coût. Le niveau EFFECTIF ne change
                // pas d'un iota — seul son véhicule change.
                AgentReasoning sent = wanted;
                if (perMessageEffort && StringUtils.hasText(wanted.effort())
                        && !wanted.effort().equals(effortInEffect[0])) {
                    // AVANT le dernier message, pas après : le niveau prend effet « à partir du
                    // prochain tour utilisateur », et c'est justement ce dernier message —
                    // les résultats d'outils — qui déclenche la réponse à venir. Posée après, la
                    // consigne ne s'appliquerait qu'au tour suivant.
                    //
                    // Cette place est explicitement prévue : un message d'effort au contenu vide
                    // est dispensé des règles de placement et peut se glisser « entre un tour
                    // assistant et le tour utilisateur suivant ».
                    int at = messages.isEmpty() ? 0 : messages.size() - 1;
                    messages.add(at, AgentMessage.effort(wanted.effort()));
                    effortInEffect[0] = wanted.effort();
                }
                if (perMessageEffort) {
                    // À la racine, l'effort reste CONSTANT : c'est ce qui fait tenir le cache.
                    sent = reasoning;
                }
                AgentTurnRequest turnRequest =
                        new AgentTurnRequest(model, system, messages, tools, apiKey, sent,
                                contextPolicy, turnMode);
                try {
                    if (streaming) {
                        turn = agentProvider.nextTurn(turnRequest, delta -> {
                            if (delta != null && !delta.isEmpty()) {
                                streamed[0] = true;
                                listener.onText(delta);
                            }
                        });
                        textAlreadyStreamed = streamed[0];
                    } else {
                        turn = agentProvider.nextTurn(turnRequest);
                    }
                } catch (fr.claudegateway.agent.AgentPromptTooLongException ex) {
                    if (compactionService == null || promptTooLongHandled) {
                        promptOverflow = true;
                        break;
                    }
                    promptTooLongHandled = true;
                    AtelierCompactionService.CompactionOutcome forced =
                            compactionService.compactNow(userId, workspace, apiKey);
                    inputTokens += forced.inputTokens();
                    outputTokens += forced.outputTokens();
                    cacheReadTokens += forced.cacheReadTokens();
                    cacheWriteTokens += forced.cacheWriteTokens();
                    if (!forced.compacted()) {
                        // Rien à réduire (fil déjà court, ou l'appel de résumé a lui-même débordé) :
                        // on ne peut pas relancer utilement, on rend un message clair.
                        promptOverflow = true;
                        break;
                    }
                    // Rebâtir depuis la nouvelle frontière + résumé : le message utilisateur et les
                    // précisions sont déjà persistés, donc relus de la base. Les messages en vol de
                    // cette itération (le cas échéant) sont abandonnés — au pire la 1re itération, où
                    // il n'y en a pas ; remplacer toute la liste évite tout tool_use orphelin.
                    messages = buildReplayMessages(userId, workspace);
                    log.info("Contexte débordé : fil compacté puis tour relancé une fois (workspace={}).",
                            workspaceId);
                }
            }
            if (promptOverflow) {
                finalText = PROMPT_TOO_LONG_REPLY;
                break;
            }
            inputTokens += turn.inputTokens();
            outputTokens += turn.outputTokens();
            cacheReadTokens += turn.cacheReadTokens();
            cacheWriteTokens += turn.cacheWriteTokens();
            webSearchRequests += turn.webSearchRequests();
            largestIterationTokens =
                    Math.max(largestIterationTokens, (long) turn.inputTokens() + turn.outputTokens());
            // Consommation relayée au fil de l'eau : c'est ce qui fait apparaître les tokens dans la
            // ligne vivante du terminal (acquis §4 n°5), jusqu'ici muette sur la boucle maison.
            listener.onProgress((long) inputTokens + outputTokens);

            // Réponse coupée au plafond de sortie (SF-28-18) : ses blocs sont incomplets. On
            // n'exécute AUCUN de ses outils — rien ne distingue de façon fiable un `tool_use` complet
            // d'un `tool_use` coupé au bon endroit, et écrire un fichier au contenu tronqué
            // remplacerait un échec silencieux par un dégât silencieux (décision D3).
            if (turn.truncated()) {
                finalText = TRUNCATED_REPLY;
                break;
            }
            // F-125 / SF-125-07 + SF-125-08 : rétention par SUBSTANCE, pas par ordre. On mémorise le
            // TEXTE DE FOND du tour (non vide après strip), pas simplement le dernier texte. Vaut pour
            // un texte accompagnant des outils comme pour un texte final. Trois cas :
            //  - un bloc <<essentiel>> (F-126) est un signal fort de réponse de fond : on le retient, et
            //    le DERNIER essentiel gagne (plusieurs essentiels dans le tour) ;
            //  - à défaut d'essentiel déjà retenu, on garde le dernier texte SUBSTANTIEL qui n'est pas
            //    une note de plomberie de carte (F-125-05 côté serveur : un statut de rangement n'est
            //    jamais promu en réponse) ;
            //  - sinon (essentiel déjà retenu, ou texte de plomberie) : on ne remplace pas — un texte
            //    postérieur sans essentiel ne peut pas évincer l'essentiel déjà produit.
            String strippedTurnText = stripTurnMetadata(turn.text());
            if (strippedTurnText != null && !strippedTurnText.isBlank()) {
                if (hasEssential(turn.text())) {
                    backgroundText = turn.text();
                    retainedHasEssential = true;
                } else if (!retainedHasEssential && !isCardPlumbing(turn.text())) {
                    backgroundText = turn.text();
                }
            }
            if (turn.finished() || turn.toolCalls().isEmpty()) {
                finalText = turn.text();
                // Second point d'accroche (F-50 / SF-50-02) : le modèle croit avoir fini, un
                // contrôle peut le renvoyer au travail. Posé ICI et nulle part ailleurs — les
                // autres sorties de la boucle sont des arrêts SUBIS (interruption, budget de temps,
                // plafond de consommation, réponse tronquée), et renvoyer au travail un tour arrêté
                // sur son plafond reviendrait à franchir le plafond (décision D3).
                if (endOfTurnBlocks < MAX_END_OF_TURN_BLOCKS
                        && checkpointRunner.hasCheckpoints(AtelierCheckpointKind.END_OF_TURN)) {
                    AtelierCheckpointVerdict verdict = checkpointRunner.run(
                            AtelierCheckpointKind.END_OF_TURN,
                            AtelierCheckpointContext.endOfTurn(userId, workspace.getHostId(),
                                    workspaceId, finalText, List.copyOf(writtenPaths),
                                    machineOfTurn.getOrDefault(turnKey(userId, workspaceId),
                                            fr.claudegateway.atelier.checkpoint.AtelierMachineReach.UNKNOWN)));
                    if (verdict.blocked()) {
                        endOfTurnBlocks++;
                        String correction = AtelierCheckpointRunner.endOfTurnBlockedMessage(verdict);
                        // Le tour bloqué est rejoué tel quel — le modèle doit voir ce qu'il venait
                        // de dire — puis la correction arrive côté UTILISATEUR : il n'y a aucun
                        // appel d'outil auquel la rattacher, et un tool_result orphelin serait
                        // refusé par le fournisseur (décision D1).
                        List<AgentContentBlock> blockedBlocks = new ArrayList<>(turn.reasoning());
                        blockedBlocks.add(new AgentContentBlock.Text(modelGuardText(finalText)));
                        messages.add(AgentMessage.assistant(blockedBlocks));
                        messages.add(AgentMessage.userText(correction));
                        // Visible au rechargement, en erreur : sans cela, l'utilisateur verrait un
                        // tour repartir tout seul sans jamais savoir pourquoi (SF-39-17).
                        transcript.add(new AtelierTurnReport.Block(CHECKPOINT_BLOCK_LABEL, null,
                                null, null, correction, true, true, false));
                        continue;
                    }
                } else if (endOfTurnBlocks >= MAX_END_OF_TURN_BLOCKS) {
                    // La main est rendue : un contrôle qui bloque en boucle ne prend pas le message
                    // en otage. Le journal le dit — sans le motif, qui porte le travail de
                    // l'utilisateur.
                    log.info("Fin de tour rendue au modèle après {} blocage(s) (workspace={})",
                            endOfTurnBlocks, workspaceId);
                }
                // F-125 / SF-125-07 + SF-125-08 — CEINTURE « jamais un tour vide » ET « jamais la
                // plomberie de carte », à la clôture NORMALE du tour (seul point où le modèle rend la
                // main de lui-même ; les autres sorties sont des arrêts subis, avec leur propre
                // message). La rétention par substance (plus haut) a déjà intégré le texte de CE tour
                // final dans `backgroundText`. Deux cas :
                String strippedBackground = stripTurnMetadata(backgroundText);
                if (strippedBackground != null && !strippedBackground.isBlank()) {
                    // 1) Un TEXTE DE FOND existe (essentiel prioritaire, sinon dernier texte substantiel
                    // non-plomberie) : c'est la réponse. Même si ce dernier tour a fini sur une note de
                    // plomberie de carte (cas réel CAGIP), elle ne peut pas l'évincer. Déjà défilé côté
                    // SSE ; le persister ici fait converger l'affichage (`done`) et la base.
                    finalText = backgroundText;
                    break;
                }
                // 2) Aucun texte de fond de tout le tour (rien, ou seulement de la plomberie de carte) :
                // on ne promeut JAMAIS une note de plomberie en réponse (F-125-05 côté serveur). On
                // provoque une passe de synthèse forcée plutôt que d'afficher la plomberie.
                // Passe de synthèse forcée, UNIQUE, sans outils et hors de tout crochet — elle ne peut
                // ni agir ni relancer un blocage.
                AgentTurn synthesis = forceSynthesis(system, messages, apiKey, turnMode);
                inputTokens += synthesis.inputTokens();
                outputTokens += synthesis.outputTokens();
                cacheReadTokens += synthesis.cacheReadTokens();
                cacheWriteTokens += synthesis.cacheWriteTokens();
                webSearchRequests += synthesis.webSearchRequests();
                largestIterationTokens = Math.max(largestIterationTokens,
                        (long) synthesis.inputTokens() + synthesis.outputTokens());
                String synthText = stripTurnMetadata(synthesis.text());
                finalText = (synthText != null && !synthText.isBlank())
                        ? synthesis.text() : LAST_RESORT_REPLY;
                // La synthèse (ou le dernier recours) n'a pas encore défilé : on la relaie une fois,
                // pour que SSE et persistance convergent. Jamais du vide, jamais l'ancien placeholder.
                listener.onProgress((long) inputTokens + outputTokens);
                listener.onText(finalText);
                break;
            }

            // Commentaire du tour (le cas échéant) relayé avant l'exécution de ses outils. En flux, il
            // a DÉJÀ défilé mot à mot via les deltas (F-116) : le relayer entier ici le doublerait.
            if (!textAlreadyStreamed && turn.text() != null && !turn.text().isBlank()) {
                listener.onText(turn.text());
            }

            // Rejoue le message assistant (texte + tool_use) puis exécute chaque outil.
            List<AgentContentBlock> assistantBlocks = new ArrayList<>();
            // Le raisonnement d'abord, tel quel (SF-39-10, décision D-L5-3) : le fournisseur exige de
            // retrouver ses blocs signés, inchangés et dans l'ordre, sur le dernier tour d'assistant
            // quand on lui renvoie les tool_result. Ils vivent le temps du tour et ne sont jamais
            // persistés : d'un message à l'autre, le raisonnement des tours passés n'est plus rejoué.
            assistantBlocks.addAll(turn.reasoning());
            if (turn.text() != null && !turn.text().isBlank()) {
                assistantBlocks.add(new AgentContentBlock.Text(turn.text()));
            }
            List<AgentContentBlock> toolResults = new ArrayList<>();
            List<AtelierToolTrace.Call> tracedCalls = new ArrayList<>();
            // Signal de difficulté de CE tour (F-119 / SF-119-01) : amorcé par l'auto-contradiction
            // éventuelle du texte, complété par chaque résultat d'outil ci-dessous. S'il est vrai, le
            // tour suivant remonte à l'effort normal.
            boolean signalThisTurn = looksLikeSelfCorrection(turn.text());
            for (AgentToolCall call : turn.toolCalls()) {
                // Identifiant de corrélation unique de l'appel (contrat de messages runner §1) : celui
                // du fournisseur, ou un UUID généré s'il manque — et le MÊME partout (bloc tool_use,
                // bloc tool_result, trame runner), sans quoi la réponse ne se rattacherait à rien.
                String callId = correlationId(call);
                assistantBlocks.add(new AgentContentBlock.ToolUse(callId, call.name(), call.input()));
                // Intention d'étape relayée avant exécution (émise même si l'outil échoue ensuite).
                AtelierProgressListener.AtelierStepEvent step = stepFor(call);
                if (step != null) {
                    listener.onAction(step);
                }
                ToolOutcome outcome;
                if (teamsReadFailedThisTurn && !fallbackAuthorized
                        && fr.claudegateway.teams.block.TeamsReadFailure.isProjectAnswerTool(call.name())) {
                    // F-89 / SF-89-11 : le SECOND VERROU. Une lecture Teams a échoué dans ce tour et
                    // l'utilisateur n'a pas choisi le repli : on refuse de répondre la question de
                    // fond depuis le poste (bash, read_file, grep…). La règle est vraie, pas suggérée.
                    outcome = ToolOutcome.error(
                            fr.claudegateway.teams.block.TeamsReadFailure.GATE_MESSAGE);
                } else if ("explore".equals(call.name())) {
                    // Délégation (F-39 / SF-39-14) : bornée en nombre, et sa consommation revient
                    // dans les compteurs du TOUR — déléguer ne doit jamais permettre de passer sous
                    // le plafond par message (D4).
                    if (delegations >= maxDelegations) {
                        outcome = ToolOutcome.error("Limite de délégations atteinte pour ce message ("
                                + maxDelegations + ") : poursuis toi-même.");
                    } else {
                        delegations++;
                        ExplorationOutcome explored =
                                explore(userId, workspace, call, model, apiKey, deadline);
                        inputTokens += explored.inputTokens();
                        outputTokens += explored.outputTokens();
                        cacheReadTokens += explored.cacheReadTokens();
                        cacheWriteTokens += explored.cacheWriteTokens();
                        listener.onProgress((long) inputTokens + outputTokens);
                        outcome = explored.outcome();
                    }
                } else if (fr.claudegateway.radar.RadarToolCatalog.isRadarTool(call.name())) {
                    // F-104 / SF-104-01 : le registre du Radar vit dans la gateway, pas sur la machine.
                    outcome = executeRadarTool(userId, workspace, call, turnNote);
                } else if (fr.claudegateway.mail.ClientMailTool.isEmailTool(call.name())) {
                    // F-110 / SF-110-02 : le courriel part de la gateway, jamais de la machine, sans confirmation.
                    outcome = executeEmailTool(userId, workspace, callId, call, listener, emailsOfTurn);
                } else if (fr.claudegateway.pages.PageToolCatalog.isPageTool(call.name())) {
                    // F-109 : la page est rangée par la gateway ; son bloc est admis dans tout terminal.
                    outcome = applyPagePublish(userId, workspace, callId, call, listener, pagesOfTurn);
                } else {
                    outcome = executeTool(userId, workspace, callId, call, listener, deadline,
                            planOfTurn, cardsOfTurn);
                }
                // Mémoire des écritures du tour (F-50 / SF-50-02), prise AVANT le crochet : un
                // fichier bloqué reste un fichier écrit, et le contrôle de fin de tour doit le voir.
                if (isFileWrite(call.name()) && !outcome.isError()) {
                    String writtenPath = arg(call.input(), "path");
                    if (writtenPath != null && !writtenPath.isBlank()) {
                        writtenPaths.add(writtenPath);
                    }
                }
                // Premier point d'accroche de la boucle (F-50 / SF-50-01) : après une écriture de
                // fichier aboutie, un contrôle peut transformer le résultat en erreur portant
                // l'action corrective. Sans contrôle enregistré, la ligne est transparente.
                outcome = applyWriteCheckpoint(userId, workspaceId, call, outcome);
                if (outcome.action() != null) {
                    actions.add(outcome.action());
                }
                // F-89 / SF-89-11 : une lecture Teams qui n'a rien rendu d'exploitable, APRÈS réseau
                // ET écran (SF-89-06), devient un échec TYPÉ, VISIBLE et BLOQUANT. Le PREMIER VERROU :
                // un bloc riche d'échec est posé dans le fil (couleur portée par le motif), et le
                // modèle reçoit la règle non négociable de s'arrêter. Pas de repli silencieux.
                String modelContent = outcome.content();
                // Aide-mémoire d'état de fichier (F-119 / SF-119-05) : un rappel léger, jamais un
                // refus. Une édition d'un fichier jamais lu ni écrit dans ce fil est « à l'aveugle » ;
                // une lecture ou une écriture, elle, rend le fichier « connu » pour la suite.
                if (fileStateHints && !outcome.isError()) {
                    modelContent = withFileStateHint(call, modelContent, knownFiles);
                }
                if (workspace.isTeamsTerminal()
                        && fr.claudegateway.teams.block.TeamsReadFailure.isReadingTool(call.name())) {
                    java.util.Optional<fr.claudegateway.teams.block.TeamsReadFailure.Reason> readFailure =
                            fr.claudegateway.teams.block.TeamsReadFailure.classify(call.name(),
                                    outcome.content());
                    if (readFailure.isPresent()) {
                        fr.claudegateway.teams.block.TeamsBlockCard failCard =
                                fr.claudegateway.teams.block.TeamsReadFailure.card(readFailure.get());
                        // Le bloc voyage sur l'appel de lecture : relayé au fil de l'eau ET écrit dans
                        // la transcription (comme les autres blocs riches), il survit au rechargement.
                        cardsOfTurn.put(callId, failCard);
                        listener.onCard(callId, failCard);
                        // L'écran garde le résultat BRUT du runner ; le modèle, lui, reçoit l'ordre de
                        // s'arrêter — la règle non négociable, en plus de la garde du second verrou.
                        modelContent = (modelContent == null ? "" : modelContent) + "\n\n"
                                + fr.claudegateway.teams.block.TeamsReadFailure.STOP_INSTRUCTION;
                        teamsReadFailedThisTurn = true;
                    }
                }
                toolResults.add(new AgentContentBlock.ToolResult(callId, modelContent, outcome.isError()));
                // Signal de difficulté (F-119 / SF-119-01) : un résultat en erreur ou un bash en code
                // de sortie ≠ 0 fera remonter l'effort au tour suivant.
                signalThisTurn |= isDifficultySignal(call, outcome);
                // Mémoire du tour (SF-39-03) : l'appel ET son résultat, appariés — le fournisseur
                // refuse un tool_use orphelin au rejeu.
                tracedCalls.add(new AtelierToolTrace.Call(callId, call.name(), call.input(),
                        AtelierToolTrace.boundResult(modelContent), outcome.isError()));
                // Transcription du tour (SF-39-17) : ce que l'écran relit après un rechargement.
                // Elle ne l'était pas, et une coupure de connexion effaçait tout ce qui s'était
                // passé — l'acquis §4 n°7 de F-30 ne valait pas pour le moteur qui exécute.
                transcript.add(new AtelierTurnReport.Block(call.name(), auditTarget(call), callId,
                        null, outcome.content() == null ? "" : outcome.content(),
                        outcome.content() != null, outcome.isError(), false,
                        // Le BLOC RICHE (F-89 / SF-89-02), s'il y en a un : c'est ce qui fait
                        // qu'une carte de réunion survit au rechargement, comme le reste du fil.
                        // `null` partout ailleurs — donc dans tout terminal de projet.
                        cardsOfTurn.get(callId),
                        // Le reçu d'un courriel (F-110 / SF-110-02) : le bloc « Courriel envoyé » survit au
                        // rechargement, dans tous les terminaux.
                        emailsOfTurn.get(callId),
                        // Le bloc « Page publiée » (F-109 / SF-109-03) : il survit au rechargement, partout.
                        pagesOfTurn.get(callId)));
            }
            messages.add(AgentMessage.assistant(assistantBlocks));
            messages.add(AgentMessage.toolResults(toolResults));
            // Le raisonnement du modèle est rangé AVEC l'étape (F-134 / SF-134-04). Sans lui, le
            // tour suivant rejouait un message assistant amputé de son premier bloc : le ruban
            // différait de celui que le fournisseur avait mis en cache, et tout était réécrit.
            trace.add(new AtelierToolTrace.Step(turn.text(), List.copyOf(tracedCalls),
                    thoughtsOf(turn)));
            // Le tour suivant remonte à l'effort normal si ce tour a rencontré une difficulté
            // (F-119 / SF-119-01) : c'est là — après un résultat d'outil — qu'il faut réfléchir le plus.
            escalateNextTurn = signalThisTurn;

            if (interruptedTurns.remove(turnKey(userId, workspaceId))) {
                // Interruption arrivée pendant les outils de ce tour : on s'arrête sans rappeler le
                // fournisseur — l'appel runner en vol a déjà reçu son tool_cancel.
                finalText = INTERRUPTED_REPLY;
                interrupted = true;
                break;
            }
            if (iteration == maxIterations - 1) {
                finalText = (turn.text() == null || turn.text().isBlank())
                        ? "J'ai atteint la limite d'étapes pour ce message ; relance-moi pour continuer."
                        : turn.text();
            }
        }
        // Le constat sur la machine ne survit pas au tour (F-93 / SF-93-04).
        machineOfTurn.remove(turnKey(userId, workspaceId));

        if (hosted) {
            // Le projet et son poste voyagent avec le décompte (F-61 / SF-61-01) : c'est ce qui
            // permettra de dire plus tard combien CE client a coûté. Le poste est celui du moment
            // du tour — déplacer le projet demain ne doit pas déplacer la dépense d'hier.
            // Chaque nature de token à son prix (F-63) : l'entrée au plein tarif, le cache au sien.
            // Le VOLUME enregistré ne bouge pas — `TurnTokens` le recompose — mais ce qui est
            // décompté du quota cesse de facturer au plein tarif des tokens relus au dixième.
            // Le modèle voyage avec le décompte (F-133 / SF-133-01) : à volume égal, un tour
            // d'Opus coûte cinq fois un tour de Haiku. C'est le modèle DEMANDÉ pour la session —
            // la boucle maison ne fait pas remonter celui que le fournisseur rapporte, et les
            // deux ne diffèrent que si le fournisseur substitue, ce qu'il ne fait pas ici.
            quotaService.recordUsage(userId,
                    new TurnTokens(Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens),
                            outputTokens, cacheReadTokens, cacheWriteTokens),
                    // Pas de temps de session ici : la boucle maison n'a pas de bac à sable
                    // facturé, seuls les Managed Agents en ont un (F-133 / SF-133-08).
                    new TurnExtras(webSearchRequests, 0L),
                    null, model, workspaceId, workspace.getHostId());
        }

        // Jamais de message vide dans l'historique (SF-28-18) : il serait relu au tour suivant et
        // refusé par le fournisseur, condamnant le projet.
        log.info("Tour d'atelier terminé : {} étape(s), {} s, {} tokens — arrêt : {}",
                iterationsUsed, Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L),
                inputTokens + outputTokens, stopCause(interrupted, spendCapReached, finalText));
        // F-125 / SF-125-01 : le marqueur de fin de tour parle au produit, pas au lecteur. Les
        // contrôles de fin de tour l'ont déjà lu sur `finalText` (plus haut, et en rejeu sur un
        // blocage) ; on le retire ici, AVANT persistance et renvoi, pour qu'il ne se retrouve jamais
        // dans le fil. Le strip d'abord, le repli ensuite : une réponse réduite au seul marqueur ne
        // doit pas persister vide (contrat SF-28-18).
        String reply = nonEmptyReply(stripTurnMetadata(finalText));
        long activeSeconds = Math.max(0L, (System.currentTimeMillis() - startedAt) / 1000L);
        // Relevé du tour rangé dans la colonne d'affichage existante (F-39 / SF-39-15, D-L8-6) :
        // sans lui, le coût du tour et le motif de son arrêt disparaîtraient au rechargement — et
        // c'est précisément après un rechargement qu'on se demande pourquoi un tour s'est arrêté.
        // Le coût réel du tour (F-133 / SF-133-02), calculé une fois, ici : le relevé le garde en
        // DOLLARS — la monnaie où le fournisseur facture —, et la conversion en euros se fait à la
        // lecture, au taux du moment. Figer un montant converti ferait mentir un vieux relevé dès
        // que le change bouge.
        TurnTokens turnTokens =
                new TurnTokens(Math.max(0, inputTokens - cacheReadTokens - cacheWriteTokens),
                        outputTokens, cacheReadTokens, cacheWriteTokens);
        java.math.BigDecimal costUsd = quotaService.costOf(turnTokens,
                new TurnExtras(webSearchRequests, 0L), model);
        // La part de contexte RELUE plutôt que réécrite (F-134 / SF-134-03) : relire coûte un
        // vingtième d'écrire, si bien que ce seul chiffre dit d'un coup d'œil si le cache fait son
        // travail — et une régression future s'y verra sans avoir à interroger la base.
        Integer reusedPercent = fr.claudegateway.quota.TurnCostView.reusedPercent(turnTokens);
        AtelierTurnReport report = new AtelierTurnReport(inputTokens, outputTokens, activeSeconds,
                interrupted, spendCapReached, planOfTurn.get(), List.copyOf(transcript), costUsd);
        AtelierMessage assistant = messageRepository.save(AtelierMessage.builder()
                .workspaceId(workspaceId).userId(userId).role("ASSISTANT")
                .content(reply)
                .toolTrace(new AtelierToolTrace(List.copyOf(trace)).toJson())
                .terminalJson(report.toJson())
                .build());

        // F-136 / SF-136-01 — la carte du client a pu changer pendant ce tour (promotion). On la
        // relit MAINTENANT, une fois la réponse prête : lire au début d'un tour coûterait six
        // allers-retours vers la machine avant le premier mot du modèle. Ne bloque pas, ne lève pas.
        hostKnowledge.refreshAfterTurn(userId, workspaceId);

        return new AtelierChatResult(reply, actions, assistant.getId(), inputTokens, outputTokens,
                activeSeconds, spendCapReached, costUsd, reusedPercent);
    }

    /**
     * Les blocs de raisonnement d'une itération, sous la forme que la trace sait conserver
     * (F-134 / SF-134-04).
     *
     * <p>Ils sont <b>recopiés, jamais reconstruits</b> : le fournisseur les signe et exige de les
     * retrouver inchangés. Un bloc sans signature ni charge expurgée n'est pas retenu — il
     * n'apporte rien au rejeu et pourrait être refusé.</p>
     */
    private static List<AtelierToolTrace.Thought> thoughtsOf(AgentTurn turn) {
        List<AtelierToolTrace.Thought> thoughts = new ArrayList<>(turn.reasoning().size());
        for (AgentContentBlock block : turn.reasoning()) {
            AtelierToolTrace.Thought thought = switch (block) {
                case AgentContentBlock.Reasoning reasoning ->
                        new AtelierToolTrace.Thought(reasoning.text(), reasoning.signature(), null);
                case AgentContentBlock.RedactedReasoning redacted ->
                        new AtelierToolTrace.Thought(null, null, redacted.data());
                default -> null;
            };
            if (thought != null && !thought.isEmpty()) {
                thoughts.add(thought);
            }
        }
        return List.copyOf(thoughts);
    }

    /**
     * Historique <b>rejouable</b> auprès du fournisseur (SF-28-18) : les messages de l'atelier, moins
     * ceux qu'il refuserait.
     *
     * <p>Deux filtres, et deux seulement :</p>
     * <ul>
     *   <li>les messages au contenu <b>blanc</b> sont écartés — l'API rejette un bloc de texte vide
     *       ({@code 400 "text content blocks must be non-empty"}), et un seul suffit à rendre muet
     *       tout le projet. Ceux déjà écrits en base avant SF-28-18 sont ainsi neutralisés sans
     *       toucher à la base, ni à ce que l'écran montre de l'historique (décision D4) ;</li>
     *   <li>les messages <b>assistant en tête</b> sont écartés — une conversation doit commencer par
     *       un message utilisateur. Le cas ne se produit qu'après le filtre précédent, ou après une
     *       suppression manuelle.</li>
     * </ul>
     *
     * <p>Deux messages {@code user} consécutifs, eux, sont acceptés par le fournisseur (vérifié) :
     * retirer un assistant au milieu ne casse donc pas l'échange.</p>
     */
    /**
     * Historique rejoué d'un fil (F-117 / SF-117-01) : le message de résumé de compaction s'il y en a
     * un, en tête, puis les messages depuis la frontière {@code chatThreadStartedAt}. Sans résumé, le
     * résultat est celui d'avant F-117 — seule la frontière filtre le rejeu (SF-39-04).
     */
    private List<AgentMessage> buildReplayMessages(UUID userId, Workspace workspace) {
        List<AgentMessage> messages = new ArrayList<>();
        AgentMessage summary = AtelierCompactionService.summaryPrefix(workspace);
        if (summary != null) {
            messages.add(summary);
        }
        messages.addAll(replayableHistory(
                workspace.getChatThreadStartedAt() == null
                        ? messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(
                                workspace.getId(), userId)
                        : messageRepository
                                .findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                                        workspace.getId(), userId, workspace.getChatThreadStartedAt()),
                replayedTraceTurns));
        return messages;
    }

    private static List<AgentMessage> replayableHistory(List<AtelierMessage> past, int traceTurns) {
        int traceFrom = firstTracedIndex(past, traceTurns);
        List<AgentMessage> messages = new ArrayList<>(past.size());
        for (int index = 0; index < past.size(); index++) {
            AtelierMessage message = past.get(index);
            String content = message.getContent();
            if (content == null || content.isBlank()) {
                continue;
            }
            boolean assistant = "ASSISTANT".equalsIgnoreCase(message.getRole());
            if (assistant && messages.isEmpty()) {
                continue;
            }
            if (assistant && index >= traceFrom) {
                // Trajectoire du tour (SF-39-03) rejouée AVANT sa réponse finale : l'agent retrouve
                // ce qu'il a fait, au lieu de le refaire. Une trajectoire illisible rend une liste
                // vide et le message retombe sur le texte seul, comme avant.
                messages.addAll(AtelierToolTrace.fromJson(message.getToolTrace()).replay());
            }
            messages.add(new AgentMessage(assistant ? "assistant" : "user",
                    List.of(new AgentContentBlock.Text(content))));
        }
        return messages;
    }

    /**
     * Index à partir duquel un message assistant est rejoué <b>avec</b> sa trajectoire d'outils
     * (SF-39-03) : au moins les {@code traceTurns} derniers tours (F-119 / SF-119-03, défaut 12),
     * la coupure se déplaçant <b>par paliers</b> (F-134 / SF-134-01).
     *
     * <p><b>Pourquoi les paliers.</b> Cette coupure comptait les tours <b>depuis la fin</b> : elle
     * avançait donc d'un cran à <b>chaque</b> tour, et le tour qui cessait d'être tracé
     * <b>changeait de forme</b> — au <b>début</b> du préfixe envoyé au fournisseur. Or l'API met en
     * cache un <b>préfixe</b> : tout ce qui suit un octet modifié doit être réécrit. Le préfixe
     * mutait donc à chaque tour, par construction, et le cache ne pouvait pas prendre. Mesuré en
     * production avant ce correctif : <b>14 à 16 %</b> du contexte relu au-delà du douzième tour
     * (contre 90 % en deçà), et <b>98 % du coût d'un tour</b> en écriture de cache — l'écriture
     * coûtant <b>vingt fois</b> la lecture.</p>
     *
     * <p>La coupure est désormais calculée <b>depuis le début</b> et ne bouge que tous les
     * {@code traceTurns} tours : une mutation sur douze, au lieu d'une par tour.</p>
     *
     * <p><b>La qualité ne peut pas y perdre</b>, et c'est ce qui rend le changement recevable au
     * regard de la contrainte de F-130 : le nombre de tours rejoués avec leurs traces passe de
     * <b>exactement</b> {@code traceTurns} à <b>entre</b> {@code traceTurns} <b>et</b>
     * {@code 2×traceTurns−1}. Il n'est <b>jamais inférieur</b> à ce qu'il était. Le modèle voit
     * autant ou plus de preuves qu'avant, jamais moins.</p>
     *
     * <p><b>Et le contexte supplémentaire coûte moins cher</b> : ces tours sont <b>relus</b> du
     * cache à un dixième du tarif d'entrée, au lieu d'être réécrits au double. Plus de contexte,
     * moins cher — c'est tout l'intérêt d'un cache qui prend.</p>
     */
    // Visible pour les tests : la propriété à garantir — « la coupure ne bouge qu'aux paliers » —
    // est arithmétique, et se vérifie ici bien mieux qu'à travers dix couches de service.
    static int firstTracedIndex(List<AtelierMessage> past, int traceTurns) {
        if (traceTurns <= 0) {
            // Fenêtre absurde : tout est tracé, comme avant que la fenêtre n'existe. Jamais de
            // division par zéro sur un réglage.
            return 0;
        }
        int assistants = 0;
        for (AtelierMessage message : past) {
            if ("ASSISTANT".equalsIgnoreCase(message.getRole())) {
                assistants++;
            }
        }
        if (assistants <= traceTurns) {
            return 0;
        }
        // Le palier : combien de tours, en partant du DÉBUT, sont laissés en texte seul. Il ne
        // change qu'aux multiples de la fenêtre — c'est là, et seulement là, que le préfixe mute.
        int untraced = ((assistants - traceTurns) / traceTurns) * traceTurns;
        if (untraced == 0) {
            return 0;
        }
        int seen = 0;
        for (int index = 0; index < past.size(); index++) {
            if ("ASSISTANT".equalsIgnoreCase(past.get(index).getRole())) {
                seen++;
                if (seen > untraced) {
                    return index;
                }
            }
        }
        return 0;
    }

    /**
     * Cause d'arrêt du tour, en un mot, pour le journal (SF-39-17). Déduite des drapeaux et des
     * réponses de repli — jamais du texte du modèle, qui n'a rien à faire dans un journal serveur.
     */
    private static String stopCause(boolean interrupted, boolean spendCapReached, String finalText) {
        if (interrupted) {
            return "interruption";
        }
        if (spendCapReached) {
            return "plafond de consommation";
        }
        if (BUDGET_REACHED_REPLY.equals(finalText)) {
            return "budget de temps";
        }
        if (TRUNCATED_REPLY.equals(finalText)) {
            return "réponse coupée au plafond de sortie";
        }
        if (PROMPT_TOO_LONG_REPLY.equals(finalText)) {
            return "contexte débordé malgré compaction";
        }
        return "réponse rendue";
    }

    /**
     * Réponse à persister : celle du tour, ou le message de dernier recours. Dernier filet après la
     * résolution SF-125-07 (qui a déjà conservé le texte produit ou joué la synthèse) : un message
     * vide en base condamnerait tout le projet (SF-28-18), il ne doit jamais en rester.
     */
    private static String nonEmptyReply(String finalText) {
        return finalText == null || finalText.isBlank() ? LAST_RESORT_REPLY : finalText;
    }

    /**
     * Texte non vide donné au MODÈLE quand un crochet de fin de tour est rejoué sur un tour au texte
     * vide (F-125 / SF-125-07). Jamais persisté ni affiché — voir {@link #EMPTY_TEXT_MODEL_GUARD}.
     */
    private static String modelGuardText(String finalText) {
        return finalText == null || finalText.isBlank() ? EMPTY_TEXT_MODEL_GUARD : finalText;
    }

    /**
     * Passe de <b>synthèse forcée</b> (F-125 / SF-125-07), jouée UNE seule fois à la clôture d'un
     * tour qui n'a produit <b>aucun</b> texte pour l'utilisateur. Un seul aller-retour, SANS outils
     * (« pas de plomberie ») et HORS de tout crochet de fin de tour : elle ne peut ni exécuter
     * d'action ni relancer un blocage, et sa consommation entre dans les compteurs du tour. Sur échec
     * (API, budget), elle rend le message de dernier recours plutôt que de laisser le tour vide.
     */
    private AgentTurn forceSynthesis(String system, List<AgentMessage> messages, String apiKey,
            AgentTurnMode turnMode) {
        List<AgentMessage> synthMessages = new ArrayList<>(messages);
        synthMessages.add(AgentMessage.userText(SYNTHESIS_PROMPT));
        AgentTurnRequest request = new AgentTurnRequest(model, system, synthMessages,
                List.of(), apiKey, AgentReasoning.none(), contextPolicy, turnMode);
        try {
            return agentProvider.nextTurn(request);
        } catch (RuntimeException ex) {
            log.info("Passe de synthèse de fin de tour en échec ({}) : dernier recours rendu.",
                    ex.getMessage());
            return new AgentTurn(LAST_RESORT_REPLY, List.of(), true, 0, 0);
        }
    }

    /**
     * Le commentaire-marqueur de fin de tour (F-52), lu par le produit et jamais par le lecteur.
     *
     * <p>Casse et espaces libres, corps quelconque jusqu'au {@code -->} fermant, plusieurs
     * occurrences retirées. On ne vise <b>que</b> ce marqueur précis (« fin-de-tour ») : tout autre
     * commentaire HTML du modèle est laissé au rendu Markdown, qui l'ignore déjà.</p>
     */
    private static final java.util.regex.Pattern TURN_METADATA_MARKER =
            java.util.regex.Pattern.compile("<!--\\s*fin-de-tour\\s*:.*?-->",
                    java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.DOTALL);

    /**
     * Retire le marqueur de fin de tour de la réponse rendue à l'utilisateur (F-125 / SF-125-01).
     *
     * <p>La tenue de la carte ne se raconte pas : le marqueur parle au produit, il n'a rien à faire
     * dans le fil. Les blancs laissés par le retrait sont recompactés, sans jamais transformer un
     * texte non vide en texte vide sur autre chose que le marqueur lui-même.</p>
     */
    static String stripTurnMetadata(String reply) {
        if (reply == null || reply.isEmpty()) {
            return reply;
        }
        String stripped = TURN_METADATA_MARKER.matcher(reply).replaceAll("");
        if (stripped.equals(reply)) {
            return reply; // Rien retiré : on ne recompacte pas une réponse qui ne portait pas de marqueur.
        }
        // Le retrait peut laisser des lignes vides en fin de réponse ou une triple coupure au milieu.
        return stripped.replaceAll("\\n{3,}", "\n\n").strip();
    }

    // ----------------------------------------------------------------------------------------------
    // F-125 / SF-125-08 — rétention du TEXTE DE FOND : détection de l'essentiel et de la plomberie.
    // ----------------------------------------------------------------------------------------------

    /** Ouverture du marqueur essentiel (F-126), tolérante à la casse et aux espaces : {@code <<essentiel>>}. */
    private static final java.util.regex.Pattern ESSENTIAL_OPEN =
            java.util.regex.Pattern.compile("<<\\s*essentiel\\s*>>", java.util.regex.Pattern.CASE_INSENSITIVE);
    /** Fermeture du marqueur essentiel (F-126) : {@code <</essentiel>>}. */
    private static final java.util.regex.Pattern ESSENTIAL_CLOSE =
            java.util.regex.Pattern.compile("<<\\s*/\\s*essentiel\\s*>>", java.util.regex.Pattern.CASE_INSENSITIVE);

    /**
     * Un bloc {@code <<essentiel>>} (F-126) exploitable est-il présent dans ce texte ?
     *
     * <p>Miroir <b>serveur</b> de {@code splitEssential} (frontend {@code essential.ts}) : la logique
     * F-126 vit côté client (rendu), inatteignable depuis Java ; on en reprend ici la seule décision
     * dont la rétention a besoin — « y a-t-il un essentiel non vide ? ». Même tolérance (casse, espaces
     * internes) et même repli que le front : ouverture sans fermeture (streaming/troncature) → tout ce
     * qui suit l'ouverture compte comme essentiel ; essentiel vide après trim → pas d'essentiel.</p>
     */
    static boolean hasEssential(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        java.util.regex.Matcher open = ESSENTIAL_OPEN.matcher(raw);
        if (!open.find()) {
            return false;
        }
        String afterOpen = raw.substring(open.end());
        java.util.regex.Matcher close = ESSENTIAL_CLOSE.matcher(afterOpen);
        String essential = close.find() ? afterOpen.substring(0, close.start()) : afterOpen;
        return !essential.strip().isEmpty();
    }

    /**
     * Longueur maximale d'un texte encore susceptible d'être une simple <b>note de plomberie de
     * carte</b>. Au-delà, on refuse de le classer plomberie et on le garde : un texte long est une
     * réponse, jamais un statut de rangement. Le cas réel faisait 212 caractères ; la borne est large.
     */
    private static final int PLUMBING_MAX_CHARS = 400;
    /** Une réponse réduite à une simple référence {@code fichier.md:ligne} — de la plomberie, pas une réponse. */
    private static final java.util.regex.Pattern BARE_MD_REF =
            java.util.regex.Pattern.compile("^[\\w./\\\\-]+\\.md:\\d+$");

    /**
     * Ce texte est-il reconnaissable comme une <b>note de plomberie de carte</b> (F-125-05, cadrage
     * SF-125-08 §2) plutôt que comme une réponse de fond à l'utilisateur ?
     *
     * <p><b>Détection volontairement conservatrice</b> — contrainte PO absolue : ne jamais masquer une
     * vraie réponse. Un faux négatif (on garde un texte de plomberie) est toléré ; un faux positif (on
     * masque une vraie réponse) est interdit. On ne classe donc plomberie qu'un texte qui réunit
     * <b>toutes</b> les conditions suivantes :</p>
     * <ul>
     *   <li>il ne contient <b>aucun</b> bloc essentiel (un essentiel est, par nature, une réponse) ;</li>
     *   <li>il est <b>court</b> (≤ {@link #PLUMBING_MAX_CHARS}) — un texte long est une réponse ;</li>
     *   <li>il correspond à une <b>formule de plomberie connue</b> : une simple référence
     *       {@code fichier.md:ligne} ; « déjà dans la carte » ; « déjà rangé » (accompagné d'un indice
     *       de carte : un {@code .md}, « carte » ou « gouvernance ») ; « vérifié : … déjà … » avec un
     *       {@code .md} ; ou « déjà dans … {@code .md} » (le cas réel CAGIP).</li>
     * </ul>
     */
    static boolean isCardPlumbing(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        String stripped = stripTurnMetadata(raw);
        if (stripped == null) {
            return false;
        }
        String text = stripped.strip();
        if (text.isEmpty() || hasEssential(text) || text.length() > PLUMBING_MAX_CHARS) {
            return false;
        }
        if (BARE_MD_REF.matcher(text).matches()) {
            return true;
        }
        String low = text.toLowerCase(java.util.Locale.ROOT);
        boolean mentionsMd = low.contains(".md");
        boolean mentionsCarte = low.contains("carte");
        boolean mentionsGouvernance = low.contains("gouvernance");
        // « déjà dans la carte » : statut de rangement explicite.
        if (low.matches("(?s).*d[ée]j[àa]\\s+dans\\s+la\\s+carte.*")) {
            return true;
        }
        // « déjà rangé » accompagné d'un indice de carte (fichier .md, « carte » ou « gouvernance »).
        if (low.matches("(?s).*d[ée]j[àa]\\s+rang[ée]e?.*") && (mentionsMd || mentionsCarte || mentionsGouvernance)) {
            return true;
        }
        // « vérifié : … déjà … » renvoyant à un fichier de carte (.md) — la forme exacte du cas réel.
        if (mentionsMd && low.matches("(?s).*v[ée]rifi[ée]e?\\s*:.*d[ée]j[àa].*")) {
            return true;
        }
        // « déjà dans `X.md` » sans passer par « la carte ».
        if (mentionsMd && low.matches("(?s).*d[ée]j[àa]\\s+dans\\b.*")) {
            return true;
        }
        return false;
    }

    /**
     * Retient ce que cet appel runner dit de la machine (F-93 / SF-93-04). Le dernier appel fait foi :
     * un runner revenu en cours de tour repasse joignable.
     *
     * <ul>
     *   <li>refus de transport ({@code runner_unavailable}, {@code runner_not_on_this_node}) → hors
     *       ligne ;</li>
     *   <li>réponse du runner — succès, ou erreur qu'il a émise lui-même → joignable ;</li>
     *   <li>délai dépassé, argument refusé avant émission, outil non annoncé → rien n'est prouvé,
     *       l'état ne bouge pas.</li>
     * </ul>
     */
    private void noteMachine(UUID userId, UUID workspaceId, RunnerCallResult result) {
        if (result == null) {
            return;
        }
        String code = result.errorCode();
        fr.claudegateway.atelier.checkpoint.AtelierMachineReach reach;
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code)) {
            reach = fr.claudegateway.atelier.checkpoint.AtelierMachineReach.OFFLINE;
        } else if (result.ok() || (code != null && !isBackendCode(code))) {
            reach = fr.claudegateway.atelier.checkpoint.AtelierMachineReach.REACHED;
        } else {
            return;
        }
        machineOfTurn.put(turnKey(userId, workspaceId), reach);
    }

    /** Vrai pour un code que la gateway émet elle-même, sans réponse du runner (contrat §4). */
    private static boolean isBackendCode(String code) {
        return RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code)
                || RunnerErrorCodes.RUNNER_PROTOCOL_ERROR.equals(code)
                || RunnerErrorCodes.RUNNER_TIMEOUT.equals(code)
                || RunnerErrorCodes.INVALID_INPUT.equals(code)
                || RunnerErrorCodes.UNSUPPORTED_TOOL.equals(code);
    }

    /**
     * Demande l'interruption du tour en cours sur ce workspace (F-38 / SF-38-07, même geste que
     * F-32 SF-32-01). Deux effets, dans cet ordre :
     * <ol>
     *   <li>un {@code tool_cancel(user_interrupt)} part vers le runner, qui <b>tue la commande</b>
     *       en cours et émet quand même sa trame terminale (contrat §2.5) ;</li>
     *   <li>le tour est marqué : la boucle s'arrête à la <b>frontière sûre</b> suivante plutôt que
     *       de relancer le fournisseur.</li>
     * </ol>
     *
     * <p>Idempotent et silencieux si rien ne tourne : la marque est de toute façon effacée à
     * l'ouverture du prochain tour. Isolation : {@code requireOwned} d'abord, toujours (404 sinon).</p>
     *
     * <p><b>Multi-pod (F-38 / SF-38-13)</b> : ces trois gestes vivent en mémoire, sur le pod qui
     * exécute la boucle et tient le flux SSE — pas forcément celui qui reçoit ce clic. On agit donc
     * d'abord ici, puis on <b>diffuse</b> le même geste aux pods pairs. La diffusion est best-effort :
     * un pair injoignable est journalisé et ne change pas la réponse rendue au navigateur.</p>
     */
    public void interruptChat(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        interruptLocally(userId, workspaceId, "user_interrupt");
        relayBroadcaster.broadcastInterrupt(userId, workspaceId, "user_interrupt");
    }

    /**
     * Les trois gestes d'une interruption, appliqués <b>sur ce pod</b> — appelés par
     * {@link #interruptChat} et par le relais interne, dans le même ordre.
     */
    /**
     * Pose la marque « tout autoriser » <b>sur ce pod</b> (F-38 / SF-38-20). Appelée par le clic
     * local et par le relais interne, pour que la boucle la trouve où qu'elle tourne.
     */
    public void allowAllLocally(UUID userId, UUID workspaceId) {
        blanketAllowedTurns.add(turnKey(userId, workspaceId));
    }

    @Override
    public RelayInterruptOutcome interruptLocally(UUID userId, UUID workspaceId, String reason) {
        interruptedTurns.add(turnKey(userId, workspaceId));
        // Une demande d'autorisation encore en attente bloquerait la boucle jusqu'à son échéance,
        // alors que l'utilisateur vient précisément de demander l'arrêt (F-38 / SF-38-08).
        int released = confirmationGate.cancelWorkspace(workspaceId);
        int cancelled = runnerCallDispatcher.cancelWorkspace(workspaceId, reason);
        return new RelayInterruptOutcome(released, cancelled);
    }

    /**
     * Tranche une demande d'autorisation posée par la boucle en cible {@code RUNNER}
     * (F-38 / SF-38-08, décision D7) : autorise la commande, ou la refuse avec un motif que le
     * modèle recevra.
     *
     * <p>Le tour, lui, attend sur son flux SSE : cette réponse arrive sur une <b>autre requête</b>.
     * Isolation appliquée en premier ({@code requireOwned} : 404 sur un projet d'autrui), et la
     * porte revérifie que la demande appartient bien à ce couple utilisateur/workspace — un
     * identifiant de corrélation deviné n'autorise rien.</p>
     *
     * <p><b>Multi-pod (F-38 / SF-38-13)</b> : la porte qui attend vit sur le pod qui exécute la
     * boucle, alors que ce clic peut atterrir sur n'importe lequel. Si personne n'attend ici, la
     * décision est <b>diffusée</b> aux pairs ; un seul d'entre eux détient la demande et la tranche.
     * Si vraiment personne ne résout, l'erreur d'origine est relancée (409) — et la porte qui
     * attendrait sans être atteinte expirera en refus : le silence ne vaut jamais autorisation.</p>
     *
     * @throws WorkspaceNotFoundException si le workspace n'est pas possédé
     * @throws fr.claudegateway.runner.exec.NoPendingConfirmationException si rien n'attend cette réponse
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String toolUseId, boolean allow,
            String reason) {
        confirmToolUse(userId, workspaceId, toolUseId, allow, reason, false);
    }

    /**
     * Variante qui accepte une décision <b>groupée</b> (F-38 / SF-38-20) : « tout autoriser pour ce
     * message ».
     *
     * <p>La marque est posée <b>avant</b> de résoudre la demande en attente, pour que la commande
     * suivante la trouve déjà là. Elle est aussi <b>diffusée</b> aux pods pairs, par le même chemin
     * que la décision elle-même : la boucle tourne peut-être ailleurs que là où ce clic atterrit
     * (SF-38-13).</p>
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String toolUseId, boolean allow,
            String reason, boolean allowAll) {
        confirmToolUse(userId, workspaceId, toolUseId, allow, reason, allowAll, false);
    }

    /**
     * Variante qui porte aussi « <b>toujours autoriser cette commande</b> » (F-121 / SF-121-02) :
     * quand {@code alwaysAllowCommand} est vrai et que la décision autorise, la porte remonte à la
     * boucle en attente l'ordre d'écrire une <b>règle persistante</b> (la boucle a l'outil et la
     * commande ; la porte, non). Cette persistance est indépendante de « tout autoriser pour ce
     * message » ({@code allowAll}), qui reste borné au tour.
     */
    public void confirmToolUse(UUID userId, UUID workspaceId, String toolUseId, boolean allow,
            String reason, boolean allowAll, boolean alwaysAllowCommand) {
        workspaceService.requireOwned(userId, workspaceId);
        if (allowAll && allow) {
            blanketAllowedTurns.add(turnKey(userId, workspaceId));
            relayBroadcaster.broadcastConfirm(userId, workspaceId, callIdOf(toolUseId), true, reason);
            // La marque est l'essentiel : « tout autoriser » reste valable même si la demande qui
            // l'a déclenchée vient d'expirer, ou si la boucle n'en attendait plus aucune.
            try {
                confirmationGate.resolve(userId, workspaceId, callIdOf(toolUseId), true, reason,
                        alwaysAllowCommand);
            } catch (fr.claudegateway.runner.exec.NoPendingConfirmationException ignored) {
                // Rien n'attendait : la marque vaut pour les commandes à venir.
            }
            return;
        }
        String callId = toolUseId == null ? "" : toolUseId.trim();
        try {
            confirmationGate.resolve(userId, workspaceId, callId, allow, reason, alwaysAllowCommand);
        } catch (fr.claudegateway.runner.exec.NoPendingConfirmationException ex) {
            if (!relayBroadcaster.broadcastConfirm(userId, workspaceId, callId, allow, reason)) {
                throw ex;
            }
        }
    }

    /**
     * Applique un appel {@code set_plan} (F-39 / SF-39-13) : le plan est normalisé, relayé à
     * l'écran, et rendu au modèle sous forme de compte rendu.
     *
     * <p>Aucun chemin d'échec : un plan mal formé est corrigé, jamais refusé (décision D2). Le
     * résultat dit au modèle ce qui a été retenu, ce qui lui permet de se corriger lui-même.</p>
     */
    private ToolOutcome applyPlan(AgentToolCall call, AtelierProgressListener listener,
            java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn) {
        JsonNode stepsNode = call.input() == null ? null : call.input().get("steps");
        AtelierPlan plan = AtelierPlan.from(stepsNode);
        int submitted = stepsNode != null && stepsNode.isArray() ? stepsNode.size() : 0;
        // Le plan vit dans le TOUR, jamais dans le service : celui-ci est un singleton partagé par
        // tous les utilisateurs, et un champ d'instance ferait fuiter le plan de l'un chez l'autre.
        planOfTurn.set(plan);
        listener.onPlan(plan);
        return ToolOutcome.info(plan.acknowledgement(submitted));
    }

    /**
     * Pose un <b>bloc riche</b> dans le fil (F-89 / SF-89-02) : carte de réunion, liste, moments.
     *
     * <h2>Le second verrou de la règle non négociable</h2>
     *
     * <p>Le premier est que ces outils ne sont pas <b>déclarés</b> hors d'un terminal Teams
     * ({@code TeamsToolCatalog}). Celui-ci refuse l'appel <b>même si le modèle nomme l'outil de
     * lui-même</b> — ce qu'il peut faire : la boucle relaie les outils non déclarés au lieu de les
     * refuser d'emblée. Les deux verrous ne sont donc pas redondants, et la règle tient :
     * <i>un terminal de projet reste textuel pour toujours</i>.</p>
     *
     * <h2>Échouer bruyamment</h2>
     *
     * <p>Un bloc sans source, sans fenêtre de lecture ou sans déclaration de ses manques est
     * <b>refusé</b>, et l'agent reçoit le motif — pas un « invalid input », une phrase qui dit quoi
     * corriger. Aucun bloc n'est posé au passage : on n'affiche jamais la moitié d'un compte rendu.</p>
     */
    private ToolOutcome applyTeamsBlock(UUID userId, Workspace workspace, String callId,
            AgentToolCall call, AtelierProgressListener listener,
            java.util.Map<String, fr.claudegateway.teams.block.TeamsBlockCard> cardsOfTurn) {
        if (!workspace.isTeamsTerminal()) {
            return ToolOutcome.error("Ce terminal n'affiche que du texte : les cartes, les moments "
                    + "et les listes n'existent que dans un terminal Teams. Réponds en clair.");
        }
        fr.claudegateway.teams.block.TeamsBlockCard.Kind kind = switch (call.name()) {
            case fr.claudegateway.teams.TeamsToolCatalog.MEETING_CARD ->
                    fr.claudegateway.teams.block.TeamsBlockCard.Kind.MEETING_CARD;
            case fr.claudegateway.teams.TeamsToolCatalog.MOMENTS ->
                    fr.claudegateway.teams.block.TeamsBlockCard.Kind.MOMENTS;
            default -> fr.claudegateway.teams.block.TeamsBlockCard.Kind.LIST;
        };
        fr.claudegateway.teams.block.TeamsBlockCard card;
        try {
            card = fr.claudegateway.teams.block.TeamsBlockCards.read(kind, call.input(),
                    imageId -> momentImages != null
                            && momentImages.exists(userId, workspace.getId(), imageId));
        } catch (fr.claudegateway.teams.block.TeamsBlockRejectedException e) {
            return ToolOutcome.error(e.getMessage());
        }
        cardsOfTurn.put(callId, card);
        listener.onCard(callId, card);
        return ToolOutcome.info(acknowledge(card));
    }

    /**
     * <b>Publier une page</b> (F-109 / SF-109-02).
     *
     * <p><b>Second verrou</b> : l'outil n'est pas déclaré hors garde ({@code PageToolCatalog}), et s'il est
     * nommé quand même, il est refusé ici sans rien lire ni ranger — la boucle relaie les outils non
     * déclarés.</p>
     *
     * <p><b>L'accord d'un clic</b> (cadrage §3.5, décision D1) : la porte d'autorisation existante, que
     * « tout autoriser pour ce message » couvre comme pour les commandes. Refus ou délai : rien n'est
     * publié, et l'agent le sait.</p>
     */
    private ToolOutcome applyPagePublish(UUID userId, Workspace workspace, String callId, AgentToolCall call,
            AtelierProgressListener listener,
            java.util.Map<String, fr.claudegateway.pages.PageBlock> pagesOfTurn) {
        if (pageToolExecutor == null || !pageToolCatalog.isOpenFor(userId, workspace)) {
            return ToolOutcome.error("La publication de pages n'est pas ouverte dans ce terminal. Réponds en clair.");
        }
        String title = fr.claudegateway.pages.PageToolExecutor.auditTarget(call.input());
        if (!blanketAllowedTurns.contains(turnKey(userId, workspace.getId()))) {
            RunnerConfirmationGate.Outcome decision = askPermission(userId, workspace.getId(), callId,
                    call.name(), "Publier la page « " + (title == null ? "sans titre" : title)
                            + " » — privée, visible par vous seul", listener);
            if (!decision.decision().allows()) {
                if (decision.decision() == RunnerConfirmationGate.Decision.TIMEOUT) {
                    return ToolOutcome.error("Publication refusée : aucune autorisation n'a été donnée dans le délai imparti.");
                }
                return ToolOutcome.error(decision.reason() == null || decision.reason().isBlank()
                        ? "Publication refusée par l'utilisateur."
                        : "Publication refusée par l'utilisateur. Motif : " + decision.reason());
            }
        }
        fr.claudegateway.pages.PageToolExecutor.Outcome outcome =
                pageToolExecutor.execute(userId, workspace, callId, call.input());
        if (outcome.error()) {
            return ToolOutcome.error(outcome.content());
        }
        if (outcome.published() != null) {
            // F-109 / SF-109-03 : le bloc « Page publiée », au fil de l'eau et dans la transcription.
            fr.claudegateway.pages.PageBlock block = fr.claudegateway.pages.PageBlock.of(outcome.published());
            pagesOfTurn.put(callId, block);
            listener.onPage(callId, block);
        }
        return ToolOutcome.info(outcome.content());
    }

    /**
     * Ce que le modèle reçoit en retour : <b>ce qui a été retenu</b>, pour qu'il puisse se corriger
     * sans qu'on ait à le deviner à l'écran. Le décompte « explicite / à confirmer » est là pour
     * cela — un bloc entièrement « à confirmer » est un bloc qu'il faut étayer.
     */
    private static String acknowledge(fr.claudegateway.teams.block.TeamsBlockCard card) {
        long explicit = card.allLines().stream()
                .filter(line -> line.certainty()
                        == fr.claudegateway.teams.block.TeamsBlockCard.Certainty.EXPLICITE)
                .count();
        long lines = card.allLines().size();
        StringBuilder text = new StringBuilder("Bloc posé dans le fil : « ")
                .append(card.title()).append(" ».");
        if (lines > 0) {
            text.append(' ').append(lines).append(" ligne(s), dont ").append(explicit)
                    .append(" explicite(s) et ").append(lines - explicit).append(" à confirmer.");
        }
        if (!card.moments().isEmpty()) {
            text.append(' ').append(card.moments().size()).append(" moment(s).");
        }
        text.append(" Fenêtre annoncée : ").append(card.window()).append('.');
        text.append(card.gaps().isEmpty()
                ? " Aucun manque déclaré."
                : " " + card.gaps().size() + " manque(s) déclaré(s).");
        return text.toString();
    }

    /**
     * Exécute une délégation d'exploration (F-39 / SF-39-14) : une sous-boucle en lecture seule dont
     * <b>seule la réponse</b> revient ici. Ce qu'elle a lu reste chez elle — c'est tout l'intérêt.
     *
     * <p>Sa consommation est rendue à l'appelant pour être <b>ajoutée à celle du tour</b> (D4) :
     * elle n'a ni quota propre, ni plafond propre. Une panne dans la sous-boucle devient un résultat
     * d'outil en erreur, jamais un échec du tour principal : c'est une aide, et quand elle échoue,
     * l'agent doit pouvoir faire le travail lui-même.</p>
     */
    private ExplorationOutcome explore(UUID userId, Workspace workspace, AgentToolCall call,
            String model, String apiKey, long deadline) {
        String question = call.input() == null ? null : call.input().path("question").asText(null);
        if (question == null || question.isBlank()) {
            return new ExplorationOutcome(ToolOutcome.error("Question requise pour explorer."), 0, 0, 0, 0);
        }
        String scope = call.input().path("path").asText(null);
        // Outils de la sous-boucle : lecture seule, et cela vaut aussi en cible RUNNER (D2 de
        // SF-39-14). Sa panoplie est CONSTRUITE, jamais dérivée de celle du travail principal
        // (SF-39-20, D1) : elle est la même sur les deux cibles, et n'y perd rien quand la panoplie
        // principale change.
        List<AgentTool> readTools = explorationTools();
        try {
            AtelierExploration.Result result = AtelierExploration.run(agentProvider, model, apiKey,
                    question.trim(), scope, readTools,
                    subCall -> {
                        ToolOutcome outcome = READ_ONLY_TOOLS.contains(subCall.name())
                                ? executeTool(userId, workspace, UUID.randomUUID().toString(), subCall,
                                        AtelierProgressListener.NOOP, deadline,
                                        new java.util.concurrent.atomic.AtomicReference<>(AtelierPlan.EMPTY),
                                        // Une exploration est en LECTURE SEULE : elle ne pose aucun
                                        // bloc dans le fil, et n'a donc nulle part où en ranger un.
                                        java.util.Map.of())
                                : ToolOutcome.error("Outil indisponible en exploration : " + subCall.name());
                        return new AtelierExploration.ExecutedTool(outcome.content(), outcome.isError());
                    },
                    () -> interruptedTurns.contains(turnKey(userId, workspace.getId()))
                            || System.currentTimeMillis() >= deadline,
                    // F-119 / SF-119-01 : la sous-boucle investigue avec un raisonnement adaptatif
                    // (effort configurable non nul), plus jamais AgentReasoning.none().
                    exploreReasoning);
            return new ExplorationOutcome(ToolOutcome.info(result.answer()),
                    result.inputTokens(), result.outputTokens(),
                    result.cacheReadTokens(), result.cacheWriteTokens());
        } catch (RuntimeException ex) {
            return new ExplorationOutcome(
                    ToolOutcome.error("L'exploration a échoué ; poursuis toi-même."), 0, 0, 0, 0);
        }
    }

    /** Issue d'une délégation : le résultat rendu au modèle, et ce qu'elle a consommé. */
    private record ExplorationOutcome(ToolOutcome outcome, int inputTokens, int outputTokens,
            int cacheReadTokens, int cacheWriteTokens) {
    }

    /**
     * Vérifie qu'une précision peut être déposée sur ce projet (F-39 / SF-39-19, F-84 / SF-84-06) :
     * l'appartenance d'abord — un projet qu'on ne possède pas rend 404, jamais un refus qui
     * révélerait son existence.
     *
     * <p>La file elle-même vit dans le <b>tour vivant</b> ({@code LiveTurn}) depuis SF-84-06 : elle
     * n'a de sens que tant qu'un tour existe, et ce service est un singleton partagé.</p>
     *
     * @throws WorkspaceNotFoundException si le workspace n'est pas possédé
     */
    public void requireSteerable(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
    }

    /** Identifiant d'appel normalisé, tel que la porte l'attend. */
    private static String callIdOf(String toolUseId) {
        return toolUseId == null ? "" : toolUseId.trim();
    }

    /** Clef de marque d'interruption : l'utilisateur ET le workspace, jamais l'un sans l'autre. */
    private static String turnKey(UUID userId, UUID workspaceId) {
        return userId + ":" + workspaceId;
    }

    /** Historique des messages de l'atelier (isolation {@code user_id}). */
    public List<AtelierMessage> history(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        return messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspaceId, userId);
    }

    // ----------------------------------------------------------------- outils

    /**
     * Traduit un appel d'outil en étape de progression pour l'UI (SF-28-05), ou {@code null} si l'outil
     * n'a pas d'étape visible. Le chemin/terme est extrait des arguments ({@code path} / {@code query}).
     */
    private AtelierProgressListener.AtelierStepEvent stepFor(AgentToolCall call) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file" -> new AtelierProgressListener.AtelierStepEvent("read", arg(input, "path"));
            case "write_file" -> new AtelierProgressListener.AtelierStepEvent("write", arg(input, "path"));
            // SF-39-06 (D4) : une édition ciblée est une écriture pour l'écran — c'est ce qui
            // déclenche le rafraîchissement du fichier ouvert. Le journal, lui, garde le nom réel.
            case "edit_file" -> new AtelierProgressListener.AtelierStepEvent("write", arg(input, "path"));
            case "list_files" -> new AtelierProgressListener.AtelierStepEvent("list", null);
            case "search_files" -> new AtelierProgressListener.AtelierStepEvent("search", arg(input, "query"));
            // F-121 / SF-121-01 : grep/glob se montrent à l'écran comme une recherche, avec le motif.
            case "grep", "glob" -> new AtelierProgressListener.AtelierStepEvent("search", arg(input, "pattern"));
            // F-38 / SF-38-07 : la commande elle-même est l'information utile à l'écran, tronquée
            // pour qu'un one-liner de 3 000 caractères ne noie pas la liste des étapes (contrat §3).
            case "bash" -> new AtelierProgressListener.AtelierStepEvent("bash",
                    shorten(arg(input, "command"), STEP_COMMAND_CHARS));
            // F-84 / SF-84-04 : une délégation peut durer des minutes — la question part à l'écran
            // AVANT, sans quoi le terminal reste muet tout du long.
            case "explore" -> new AtelierProgressListener.AtelierStepEvent("explore",
                    shorten(arg(input, "question"), STEP_COMMAND_CHARS));
            // Le plan a son propre affichage (SF-39-13) : une étape de plus le dirait deux fois.
            case "set_plan" -> null;
            // Tout autre outil (volet Teams, outils à venir) se montre quand il COMMENCE, avec sa
            // cible d'audit — ce qu'on a demandé, jamais ce qui est revenu (F-88 / SF-88-03, D2).
            default -> call.name() == null || call.name().isBlank()
                    ? null
                    : new AtelierProgressListener.AtelierStepEvent(call.name(), auditTarget(call));
        };
    }

    /** Tronque un texte à {@code max} caractères, sans marqueur : c'est une étiquette, pas un contenu. */
    private static String shorten(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Extrait un argument texte d'un input d'outil (ou {@code null} si absent). */
    private String arg(JsonNode input, String name) {
        return input == null ? null : input.path(name).asText(null);
    }

    /** Identifiant de corrélation d'un appel d'outil : celui du fournisseur, ou un UUID de secours. */
    private String correlationId(AgentToolCall call) {
        return call.id() == null || call.id().isBlank() ? UUID.randomUUID().toString() : call.id();
    }

    /**
     * Point de contrôle <b>après écriture de fichier</b> (F-50 / SF-50-01).
     *
     * <p>C'est le premier point d'accroche de la boucle maison : jusqu'ici, rien ne s'exécutait
     * autour d'un appel d'outil, et aucune règle ne pouvait passer du statut de consigne à celui de
     * verrou. Le geste est celui qui a déjà fait ses preuves avec la porte de confirmation
     * (SF-38-08) : le résultat rendu au modèle devient une <b>erreur</b>, et cette erreur porte
     * <b>l'action corrective</b> — c'est le modèle qui doit corriger, donc le message lui dit quoi
     * faire, pas seulement ce qui ne va pas.</p>
     *
     * <p>Trois bornes, et elles comptent :</p>
     * <ul>
     *   <li>seules les écritures <b>abouties</b> sont contrôlées — une écriture en échec n'a rien
     *       produit qu'on puisse juger, et empiler un second message d'erreur sur le premier
     *       brouillerait la correction attendue ;</li>
     *   <li>l'action fichier destinée à l'écran est <b>conservée</b> : le fichier existe, l'éditeur
     *       ouvert doit se rafraîchir, même si la suite est un blocage ;</li>
     *   <li>sans contrôle enregistré, la méthode rend l'issue inchangée — F-50 livre le mécanisme,
     *       pas son contenu.</li>
     * </ul>
     */
    private ToolOutcome applyWriteCheckpoint(UUID userId, UUID workspaceId, AgentToolCall call,
            ToolOutcome outcome) {
        if (outcome.isError() || !isFileWrite(call.name())
                || !checkpointRunner.hasCheckpoints(AtelierCheckpointKind.AFTER_FILE_WRITE)) {
            return outcome;
        }
        JsonNode input = call.input();
        // Ce que le modèle a DEMANDÉ d'écrire, jamais une relecture du disque : en cible RUNNER le
        // fichier vit sur la machine de l'utilisateur, et la gateway n'y retourne pas pour contrôler.
        String content = "edit_file".equals(call.name())
                ? arg(input, "new_string")
                : arg(input, "content");
        AtelierCheckpointVerdict verdict = checkpointRunner.run(AtelierCheckpointKind.AFTER_FILE_WRITE,
                AtelierCheckpointContext.afterFileWrite(userId, workspaceId, call.name(),
                        arg(input, "path"), content));
        if (!verdict.blocked()) {
            return outcome;
        }
        return new ToolOutcome(AtelierCheckpointRunner.writeBlockedMessage(verdict), true, outcome.action());
    }

    /**
     * Point de contrôle <b>avant commande</b> (F-52 / SF-52-01).
     *
     * <p>F-50 avait laissé {@code bash} de côté, au motif qu'il a « déjà sa porte et son journal ».
     * Le motif ne tient pas pour une vérification <b>mécanique</b> : la porte de confirmation
     * n'inspecte <b>rien</b> du contenu de la commande — elle demande une autorisation — et SF-38-20
     * l'a rendue débrayable par projet. Une règle qui doit refuser un marqueur dans un message de
     * commit n'a donc nulle part où se brancher, jusqu'ici.</p>
     *
     * <p>Deux bornes : le point n'est atteint que pour {@code bash}, et rien n'est lu tant qu'aucun
     * contrôle n'est enregistré ({@code hasCheckpoints}) — l'immense majorité des projets n'en a
     * aucun, et ils ne doivent pas payer une lecture de plus par commande.</p>
     *
     * @return le résultat d'outil à rendre au modèle si la commande est refusée, ou {@code null} si
     *         elle peut suivre son chemin
     */
    private ToolOutcome applyCommandCheckpoint(UUID userId, UUID workspaceId,
            RunnerTarget runnerTarget, String callId, AgentToolCall call, String target) {
        if (!"bash".equals(call.name())
                || !checkpointRunner.hasCheckpoints(AtelierCheckpointKind.BEFORE_COMMAND)) {
            return null;
        }
        JsonNode input = call.input();
        AtelierCheckpointVerdict verdict = checkpointRunner.run(AtelierCheckpointKind.BEFORE_COMMAND,
                AtelierCheckpointContext.beforeCommand(userId, workspaceId, arg(input, "command"),
                        arg(input, "cwd")));
        if (!verdict.blocked()) {
            return null;
        }
        // Refus AVANT émission : rien n'est parti sur la machine. Le journal le dit — sans le motif,
        // qui porte le travail de l'utilisateur (règle SF-38-08 / D11).
        runnerAuditService.recordDenied(userId, runnerTarget, callId, call.name(), target,
                RunnerAuditOutcome.DENIED);
        return ToolOutcome.error(AtelierCheckpointRunner.commandBlockedMessage(verdict));
    }

    /**
     * Exécute un outil Radar (F-104 / SF-104-01).
     *
     * <p><b>Le second verrou</b> : le premier est que ces outils ne sont pas déclarés hors de la garde
     * ({@code RadarToolCatalog}). Celui-ci refuse l'appel même si le modèle nomme l'outil de lui-même — la
     * garde est réévaluée, et rien n'est lu ni écrit. Le périmètre est le poste du terminal, jamais un
     * identifiant venu du modèle.</p>
     */
    private ToolOutcome executeRadarTool(UUID userId, Workspace workspace, AgentToolCall call,
            fr.claudegateway.radar.RadarNote note) {
        if (radarToolExecutor == null || !radarToolCatalog.isOpenFor(userId, workspace)) {
            return ToolOutcome.error("Les outils Radar n'existent que dans le terminal Teams d'un client suivi "
                    + "par la Vigie : réponds sans eux.");
        }
        fr.claudegateway.radar.RadarToolExecutor.Outcome outcome = radarToolExecutor.execute(
                new fr.claudegateway.radar.RadarScope(userId, workspace.getHostId()), call.name(), call.input(), note);
        return outcome.error() ? ToolOutcome.error(outcome.content()) : ToolOutcome.info(outcome.content());
    }

    /**
     * Exécute {@code email_me} (F-110 / SF-110-02) : le destinataire est résolu par la gateway, jamais lu dans
     * l'appel. Un courriel mis en file pose son reçu dans le tour et le relaie à l'écran.
     */
    private ToolOutcome executeEmailTool(UUID userId, Workspace workspace, String callId, AgentToolCall call,
            AtelierProgressListener listener,
            java.util.Map<String, fr.claudegateway.mail.ClientMailReceipt> emailsOfTurn) {
        if (clientMailTool == null) {
            return ToolOutcome.error("L'envoi de courriels n'est pas disponible : réponds sans lui.");
        }
        fr.claudegateway.mail.ClientMailTool.Outcome outcome = clientMailTool.send(userId, workspace, callId,
                call.input());
        if (outcome.receipt() != null) {
            emailsOfTurn.put(callId, outcome.receipt());
            listener.onEmail(callId, outcome.receipt());
        }
        return outcome.error() ? ToolOutcome.error(outcome.content()) : ToolOutcome.info(outcome.content());
    }

    /** Les deux outils qui modifient un fichier du projet, et eux seuls (F-50 / SF-50-01). */
    private static boolean isFileWrite(String tool) {
        return "write_file".equals(tool) || "edit_file".equals(tool);
    }

    /**
     * Aide-mémoire d'état de fichier (F-119 / SF-119-05) : suit les fichiers lus/écrits du fil et, sur
     * une <b>édition à l'aveugle</b> (un {@code edit_file} d'un chemin ni lu ni écrit auparavant dans
     * ce fil), ajoute au résultat un rappel léger de lecture-avant-édition. Jamais un refus — le disque
     * évite déjà la corruption ; c'est le <b>raisonnement</b> sur un contenu supposé qu'on prévient.
     * {@code read_file}, {@code write_file} et {@code edit_file} rendent le chemin « connu » pour la
     * suite du fil.
     */
    private String withFileStateHint(AgentToolCall call, String content,
            java.util.Set<String> knownFiles) {
        String tool = call.name();
        if (!"read_file".equals(tool) && !isFileWrite(tool)) {
            return content;
        }
        String path = arg(call.input(), "path");
        if (path == null || path.isBlank()) {
            return content;
        }
        String hint = null;
        if ("edit_file".equals(tool) && !knownFiles.contains(path)) {
            hint = "Rappel : tu as modifié " + path + " sans l'avoir lu dans ce fil. Relis-le avant de "
                    + "l'éditer si tu n'es pas sûr de son contenu.";
        }
        // Lu ou écrit : désormais connu du fil (une écriture rend le contenu connu du modèle).
        knownFiles.add(path);
        if (hint == null) {
            return content;
        }
        String base = content == null ? "" : content;
        return base.isBlank() ? hint : base + "\n\n" + hint;
    }

    private ToolOutcome executeTool(UUID userId, Workspace workspace, String callId, AgentToolCall call,
            AtelierProgressListener listener, long deadline,
            java.util.concurrent.atomic.AtomicReference<AtelierPlan> planOfTurn,
            java.util.Map<String, fr.claudegateway.teams.block.TeamsBlockCard> cardsOfTurn) {
        // Le plan ne s'exécute nulle part : il ne touche ni la machine, ni le stockage. Il est donc
        // traité AVANT le routage par cible (F-39 / SF-39-13).
        if ("set_plan".equals(call.name())) {
            return applyPlan(call, listener, planOfTurn);
        }
        // Les outils de PRÉSENTATION (F-89 / SF-89-02) non plus : ils ne touchent ni la machine ni
        // le stockage, ils posent un bloc dans le fil. Traités ici, avant le routage par cible.
        if (fr.claudegateway.teams.TeamsToolCatalog.isPresentation(call.name())) {
            return applyTeamsBlock(userId, workspace, callId, call, listener, cardsOfTurn);
        }
        // Les pages (F-109 / SF-109-02) : rangées par la gateway, pas écrites par le runner — qui ne
        // sert qu'à relire un fichier du poste, depuis l'exécuteur.
        if (fr.claudegateway.pages.PageToolCatalog.isPageTool(call.name())) {
            // Traité par la boucle (F-109 / SF-109-03), jamais ici : ce chemin ne porte pas le bloc du tour.
            return ToolOutcome.error("La publication de pages n'est pas ouverte ici. Réponds en clair.");
        }
        if (workspace.isRunnerTarget()) {
            return executeToolOnRunner(userId, workspace, callId, call, listener, deadline);
        }
        return executeToolOnStorage(userId, workspace.getId(), call);
    }

    /**
     * Exécute un outil sur la <b>machine de l'utilisateur</b> (F-38 / SF-38-05). La gateway relaie :
     * elle traduit l'appel en trame {@code tool_call}, attend le {@code tool_result} et le retraduit
     * en résultat d'outil pour le modèle, dans les formats exacts du mode sandbox (contrat §3) — le
     * prompt ne doit pas dériver selon la cible d'exécution.
     *
     * <p>Deux garde-fous s'ajoutent en SF-38-08, dans cet ordre : la <b>validation d'action</b>
     * (D7) — la trame n'est jamais émise avant décision — puis la <b>trace</b> (D11) : une ligne de
     * journal par appel, qu'il ait abouti, échoué ou été refusé.</p>
     */
    private ToolOutcome executeToolOnRunner(UUID userId, Workspace workspace, String callId,
            AgentToolCall call, AtelierProgressListener listener, long deadline) {
        UUID workspaceId = workspace.getId();
        // Cible d'exécution : le POSTE et le chemin du projet sous sa racine (F-48 / SF-48-01).
        RunnerTarget runnerTarget = RunnerTargets.of(workspace);
        String tool = call.name();
        String target = auditTarget(call);
        // Troisième point d'accroche (F-52 / SF-52-01) : AVANT la porte, et avant toute émission.
        // Avant la porte, parce que faire cliquer l'utilisateur sur une commande que la gouvernance
        // va refuser lui ferait payer deux fois le même refus.
        ToolOutcome refused = applyCommandCheckpoint(userId, workspaceId, runnerTarget, callId, call,
                target);
        if (refused != null) {
            return refused;
        }
        // Deux façons de ne plus être interrompu, l'une bornée au message, l'autre au projet
        // (F-38 / SF-38-20). Dans les deux cas, l'audit continue de tout tracer.
        //
        // F-108 / SF-108-02 : une ÉCRITURE dans Microsoft 365 est confirmée à CHAQUE fois (cadrage
        // §4.4). Elle n'est donc jamais couverte par « tout autoriser pour ce message », ni soumise
        // au réglage agent_ask_before_bash (qui ne concerne que bash) : c'est une garde du cadrage,
        // pas une commodité réglable. Le libellé présenté nomme l'action et l'emplacement en clair.
        boolean teamsWrite = fr.claudegateway.teams.TeamsToolCatalog.isWrite(tool);
        boolean blanket = blanketAllowedTurns.contains(turnKey(userId, workspace.getId()));
        if (teamsWrite) {
            // F-108 / SF-108-02 : une écriture Microsoft 365 est confirmée à CHAQUE fois — hors
            // politique de permission et hors « tout autoriser » (garde du cadrage, pas une commodité).
            RunnerConfirmationGate.Outcome decision =
                    askPermission(userId, workspaceId, callId, tool, teamsWriteDetail(call), listener);
            if (!decision.decision().allows()) {
                runnerAuditService.recordDenied(userId, runnerTarget, callId, tool, target,
                        decision.decision() == RunnerConfirmationGate.Decision.TIMEOUT
                                ? RunnerAuditOutcome.TIMEOUT
                                : RunnerAuditOutcome.DENIED);
                return ToolOutcome.error(deniedMessage(decision));
            }
        } else {
            // F-121 / SF-121-02 : la politique de permission allow/ask/deny persistée tranche.
            // DENY refuse d'emblée (rien n'est demandé, rien n'est émis) ; ASK demande une
            // autorisation (sauf « tout autoriser pour ce message ») ; ALLOW exécute sans demander.
            PermissionEffect effect = resolveEffect(userId, workspace, tool, call);
            if (effect == PermissionEffect.DENY) {
                runnerAuditService.recordDenied(userId, runnerTarget, callId, tool, target,
                        RunnerAuditOutcome.DENIED);
                return ToolOutcome.error(denyRuleMessage(tool));
            }
            if (effect == PermissionEffect.ASK && !blanket) {
                RunnerConfirmationGate.Outcome decision =
                        askPermission(userId, workspaceId, callId, tool, target, listener);
                if (!decision.decision().allows()) {
                    // Refus AVANT émission (contrat §6) : rien n'est parti sur la machine, et le
                    // modèle reçoit le motif pour proposer autre chose plutôt que de rester bloqué.
                    runnerAuditService.recordDenied(userId, runnerTarget, callId, tool, target,
                            decision.decision() == RunnerConfirmationGate.Decision.TIMEOUT
                                    ? RunnerAuditOutcome.TIMEOUT
                                    : RunnerAuditOutcome.DENIED);
                    return ToolOutcome.error(deniedMessage(decision));
                }
            }
        }
        // F-121 / SF-121-04 : un échec de TRANSPORT (runner_timeout/unavailable/not_on_this_node/
        // protocol_error) est réessayé, borné, AVANT de rendre la main au modèle — ~17 % des appels
        // échouent derrière le proxy en prod, et un négatif rendu au modèle le fait conclure faux.
        RunnerCallResult result =
                callRunnerWithRetry(userId, workspaceId, runnerTarget, callId, call, listener, deadline);
        if (result == null) {
            return ToolOutcome.error("Outil inconnu : " + tool);
        }
        runnerAuditService.recordCall(userId, runnerTarget, callId, tool, target, result);
        noteMachine(userId, workspaceId, result);
        if (RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(result.errorCode())
                && runnerTarget.hostId() != null) {
            // F-97 / SF-97-02 : le refus est aussi dit à l'écran, pas seulement au modèle.
            listener.onRunnerOffline(runnerTarget.hostId());
        }
        return runnerOutcome(call, result);
    }

    /**
     * Vrai si l'action doit être autorisée par l'utilisateur avant d'être émise (F-38 / SF-38-08,
     * <b>amendé par SF-38-20</b>).
     *
     * <p>SF-38-08 avait rendu la porte non désactivable en cible {@code RUNNER}. Le banc d'essai a
     * montré le prix de cette rigidité : une procédure de treize étapes demande des dizaines de
     * clics, et une garde qu'on subit finit par être contournée plutôt que respectée. Le réglage
     * {@code agent_ask_before_bash} du projet est donc <b>consulté</b> — c'est une décision de
     * l'utilisateur sur sa propre machine, prise en connaissance de cause.</p>
     *
     * <p>Ce qui ne change pas : le <b>journal d'audit</b> trace chaque commande, autorisée ou non,
     * et le <b>coupe-circuit</b> reste immédiat. Ce qui disparaît est le clic, pas la trace.</p>
     *
     * <p>Les écritures de fichier n'y sont toujours pas soumises : c'est l'usage central du mode
     * (l'agent édite le projet), et un clic par écriture pousserait à chercher un contournement.</p>
     */
    private static boolean requiresConfirmation(String tool, Workspace workspace) {
        return "bash".equals(tool) && workspace.isAgentAskBeforeBash();
    }

    /**
     * L'effet allow/ask/deny à appliquer à cet appel (F-121 / SF-121-02) : une <b>règle persistée</b>
     * la plus spécifique l'emporte ; à défaut (ou quand la politique n'est pas branchée — formes
     * historiques), le <b>défaut</b> {@link #defaultEffect} s'applique, qui reproduit exactement la
     * porte binaire d'avant SF-121-02.
     */
    private PermissionEffect resolveEffect(UUID userId, Workspace workspace, String tool, AgentToolCall call) {
        String command = "bash".equals(tool) ? arg(call.input(), "command") : null;
        if (permissionService != null) {
            java.util.Optional<PermissionEffect> rule =
                    permissionService.ruleFor(userId, workspace.getId(), tool, command);
            if (rule.isPresent()) {
                return rule.get();
            }
        }
        return defaultEffect(tool, workspace);
    }

    /**
     * Le défaut quand aucune règle ne couvre l'appel (F-121 / SF-121-02). Il reproduit le comportement
     * d'avant : {@code bash} demande selon {@code agent_ask_before_bash} ; une édition demande selon le
     * réglage {@code app.atelier.ask-before-edit} (défaut : non) ; tout le reste s'exécute sans demander.
     */
    private PermissionEffect defaultEffect(String tool, Workspace workspace) {
        if (requiresConfirmation(tool, workspace)) {
            return PermissionEffect.ASK;
        }
        if (askBeforeEdit && isFileWrite(tool)) {
            return PermissionEffect.ASK;
        }
        return PermissionEffect.ALLOW;
    }

    /** Message rendu au modèle quand une règle {@code DENY} refuse l'outil (F-121 / SF-121-02). */
    private static String denyRuleMessage(String tool) {
        return "L'outil « " + tool + " » est refusé par une règle de permission de ce projet. "
                + "Propose une autre approche.";
    }

    /**
     * <b>Le libellé clair d'une écriture Teams</b> (F-108 / SF-108-02, cadrage §4.4) : ce que
     * l'utilisateur lit avant d'autoriser. L'item et l'emplacement sont extraits des paramètres
     * d'appel ; le phrasé, lui, vit dans {@code TeamsToolCatalog.describeWrite} — une seule source.
     */
    private String teamsWriteDetail(AgentToolCall call) {
        JsonNode input = call.input();
        // F-108 / SF-108-04 : une phrase par écriture — ancien et nouveau nom, fichier local déposé.
        return fr.claudegateway.teams.TeamsToolCatalog.describeWriteCall(call.name(),
                name -> firstArg(input, name));
    }

    /** Le premier argument texte non vide parmi une liste de noms possibles, ou {@code ""}. */
    private String firstArg(JsonNode input, String... names) {
        for (String name : names) {
            String value = arg(input, name);
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    /** Pose la demande d'autorisation à l'écran, attend la décision, puis relaie sa résolution. */
    private RunnerConfirmationGate.Outcome askPermission(UUID userId, UUID workspaceId, String callId,
            String tool, String detail, AtelierProgressListener listener) {
        RunnerConfirmationGate.Outcome outcome = confirmationGate.await(userId, workspaceId, callId,
                () -> listener.onConfirmRequest(new AtelierProgressListener.AtelierConfirmRequest(
                        callId, tool, detail, confirmationGate.timeoutMs(), offersAlwaysAllow(tool))));
        // F-121 / SF-121-02 : « toujours autoriser cette commande » a été coché — on écrit une règle
        // persistante (pour bash, sur le premier mot de la commande ; sinon sur l'outil entier). Le
        // detail porte la commande pour bash (c'est la cible d'audit), il sert de source au préfixe.
        if (outcome.decision().allows() && outcome.persistRule() && permissionService != null) {
            permissionService.alwaysAllowCommand(userId, workspaceId, tool, detail);
        }
        listener.onConfirmResolved(new AtelierProgressListener.AtelierConfirmResolved(
                callId, outcome.decision().label()));
        return outcome;
    }

    /**
     * L'invite doit-elle proposer « <b>toujours autoriser cette commande</b> » (F-121 / SF-121-02) ?
     * Seulement quand la politique persistée est branchée — sans elle, la case n'écrirait rien.
     */
    private boolean offersAlwaysAllow(String tool) {
        return permissionService != null && !fr.claudegateway.teams.TeamsToolCatalog.isWrite(tool);
    }

    /** Message rendu au modèle quand l'action n'a pas été autorisée (jamais un détail technique). */
    private static String deniedMessage(RunnerConfirmationGate.Outcome outcome) {
        if (outcome.decision() == RunnerConfirmationGate.Decision.TIMEOUT) {
            return "Commande refusée : aucune autorisation n'a été donnée dans le délai imparti.";
        }
        return outcome.reason() == null || outcome.reason().isBlank()
                ? "Commande refusée par l'utilisateur."
                : "Commande refusée par l'utilisateur. Motif : " + outcome.reason();
    }

    /**
     * Nombre de <b>réessais</b> d'un appel runner transitoire (F-121 / SF-121-04), réglable par
     * {@code app.atelier.runner-retries} (défaut 2 ; {@code 0} désactive le réessai — coupe-circuit).
     * Un timeout/indispo/mauvais nœud/erreur de protocole est réessayé jusqu'à ce nombre de fois,
     * <b>dans le budget de tour restant</b>, avant d'être rendu au modèle.
     */
    @org.springframework.beans.factory.annotation.Value("${app.atelier.runner-retries:2}")
    private int runnerRetries;

    /**
     * Attente entre deux tentatives d'un appel runner transitoire, en millisecondes (F-121 / SF-121-04),
     * réglable par {@code app.atelier.runner-retry-backoff-ms} (défaut 250). Courte par construction :
     * elle doit tenir dans le budget de tour, jamais le consumer.
     */
    @org.springframework.beans.factory.annotation.Value("${app.atelier.runner-retry-backoff-ms:250}")
    private long runnerRetryBackoffMs;

    /** Réglage du réessai — exposé pour les tests (F-121 / SF-121-04). */
    void setRunnerRetry(int retries, long backoffMs) {
        this.runnerRetries = retries;
        this.runnerRetryBackoffMs = backoffMs;
    }

    /**
     * Émet l'appel runner et le <b>réessaie</b> tant qu'il échoue en <b>transport</b> (F-121 / SF-121-04) :
     * borné par {@link #runnerRetries}, un court {@link #runnerRetryBackoffMs} entre deux, et jamais
     * au-delà du budget de tour restant ni d'une interruption. Ne réessaie <b>pas</b> un vrai refus
     * ({@code unsupported_tool}, {@code invalid_input}, argument manquant) : rien n'y changerait. Si le
     * dernier essai échoue encore en transport, le résultat est <b>tagué « (réessayé) »</b>, ce que le
     * message « non concluant » (SF-119-04) reprend au modèle.
     */
    private RunnerCallResult callRunnerWithRetry(UUID userId, UUID workspaceId, RunnerTarget target,
            String callId, AgentToolCall call, AtelierProgressListener listener, long deadline) {
        RunnerCallResult result = callRunnerOnce(target, callId, call, listener, deadline);
        if (result == null) {
            return null; // outil inconnu : rien à réessayer
        }
        int attempts = 0;
        while (runnerRetries > 0 && attempts < runnerRetries
                && isInconclusiveFailure(result.errorCode())) {
            // Le réessai reste DANS le budget du tour, et cède à une interruption : on ne fait pas
            // patienter un tour déjà arrêté.
            if (deadline - System.currentTimeMillis() <= runnerRetryBackoffMs
                    || interruptedTurns.contains(turnKey(userId, workspaceId))) {
                break;
            }
            try {
                Thread.sleep(runnerRetryBackoffMs);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                break;
            }
            attempts++;
            RunnerCallResult retried = callRunnerOnce(target, callId, call, listener, deadline);
            if (retried == null) {
                return null;
            }
            result = retried;
        }
        if (attempts > 0 && isInconclusiveFailure(result.errorCode())) {
            log.info("Appel runner transitoire réessayé {} fois sans succès (workspace={}, outil={})",
                    attempts, workspaceId, call.name());
            return retriedTag(result);
        }
        return result;
    }

    /** Un appel runner, argument manquant compris (tracé comme une tentative, jamais lancé sans être vu). */
    private RunnerCallResult callRunnerOnce(RunnerTarget target, String callId, AgentToolCall call,
            AtelierProgressListener listener, long deadline) {
        try {
            return callRunner(target, callId, call, listener, deadline);
        } catch (RuntimeException ex) {
            // Argument manquant ou malformé : rien n'est parti, mais la tentative est tracée — le
            // journal doit dire ce que le modèle a essayé, pas seulement ce qui a abouti.
            return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT,
                    ex.getMessage() != null ? ex.getMessage() : "Opération refusée.");
        }
    }

    /**
     * Tague un échec de transport comme « (réessayé) » (F-121 / SF-121-04), en préservant tous les
     * autres champs — dont la sortie partielle {@code streamed} d'un {@code bash} (SF-119-04).
     */
    private static RunnerCallResult retriedTag(RunnerCallResult result) {
        String base = result.errorMessage() == null || result.errorMessage().isBlank()
                ? RunnerErrorCodes.messageFor(result.errorCode())
                : result.errorMessage();
        return new RunnerCallResult(false, result.content(), result.truncated(), result.exitCode(),
                result.durationMs(), result.bytes(), result.errorCode(), base + " (réessayé)",
                result.streamed(), result.streamTruncated());
    }

    /** Émet l'appel vers le runner ; {@code null} si l'outil demandé n'existe pas. */
    private RunnerCallResult callRunner(RunnerTarget target, String callId, AgentToolCall call,
            AtelierProgressListener listener, long deadline) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "list_files" -> runnerToolGateway.listFiles(target, callId);
            case "read_file" -> runnerToolGateway.readFile(target, callId,
                    requiredArg(input, "path"));
            case "edit_file" -> editFileOnRunner(target, callId, input);
            case "write_file" -> runnerToolGateway.writeFile(target, callId,
                    requiredArg(input, "path"), input.path("content").asText(""));
            case "search_files" -> runnerToolGateway.searchFiles(target, callId,
                    requiredArg(input, "query"));
            // Grep/Glob (F-121 / SF-121-01) : le runner parcourt lui-même l'arbre et applique la
            // regex/le motif — la gateway relaie les paramètres bornés, jamais le shell.
            case "grep" -> runnerToolGateway.grep(target, callId, input);
            case "glob" -> runnerToolGateway.glob(target, callId, input);
            // Le délai est ramené au budget de tour restant : une commande ne doit jamais pouvoir
            // survivre au tour qui l'a lancée.
            case "bash" -> runnerToolGateway.bash(target, callId, requiredArg(input, "command"),
                    input.path("cwd").asText(null), deadline - System.currentTimeMillis(),
                    listener::onOutput);
            // Le volet Teams (F-88 / SF-88-03) : un seul relais pour les huit outils, parce qu'ils
            // partagent le même contrat — des paramètres que SEUL le runner sait interpréter, et une
            // enveloppe JSON en retour. La gateway relaie ; elle ne réinterprète ni la période, ni
            // le plafond. Les outils sont donnés ou non par TeamsToolCatalog : si le modèle en
            // nomme un qu'il n'a pas reçu, la capacité du poste le refuse avant émission.
            default -> call.name() != null
                    && call.name().startsWith(fr.claudegateway.teams.TeamsToolCatalog.PREFIX)
                            ? runnerToolGateway.teamsRead(target, callId, call.name(), input)
                            : null;
        };
    }

    /** Traduit l'issue d'un appel runner en résultat d'outil, aux formats du mode sandbox. */
    private ToolOutcome runnerOutcome(AgentToolCall call, RunnerCallResult result) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file" -> readOutcome(result, input);
            case "edit_file" -> result.ok()
                    ? new ToolOutcome(result.content(), false, new AtelierAction("write", arg(input, "path")))
                    : ToolOutcome.error(result.errorMessage());
            // Le `content` renvoyé par le runner est ignoré : seul compte l'aboutissement, et le
            // modèle attend la formulation historique.
            case "write_file" -> result.ok()
                    ? new ToolOutcome("Fichier écrit : " + arg(input, "path"), false,
                            new AtelierAction("write", arg(input, "path")))
                    : ToolOutcome.error(result.errorMessage());
            case "bash" -> bashOutcome(arg(input, "command"), result);
            // Grep/Glob (F-121 / SF-121-01) : contenu verbatim en cas de succès ; un échec de
            // transport est « non concluant » (cohérent SF-119-04), pas une preuve d'absence.
            case "grep", "glob" -> result.ok()
                    ? textOutcome(result, null)
                    : ToolOutcome.error(inconclusiveNote(result));
            default -> textOutcome(result, null);
        };
    }

    /**
     * Cible journalisée d'un appel (F-38 / SF-38-08) : un chemin, un terme recherché ou une commande
     * tronquée — jamais un contenu de fichier ni une sortie de commande.
     */
    String auditTarget(AgentToolCall call) {
        JsonNode input = call.input();
        return switch (call.name()) {
            case "read_file", "write_file", "edit_file" -> arg(input, "path");
            case "search_files" -> arg(input, "query");
            // F-121 / SF-121-01 : on trace le MOTIF cherché, jamais le contenu trouvé.
            case "grep", "glob" -> shorten(arg(input, "pattern"), AUDIT_TARGET_CHARS);
            case "bash" -> shorten(arg(input, "command"), AUDIT_TARGET_CHARS);
            // Teams (F-88 / SF-88-03) : ce qui est tracé est CE QU'ON A DEMANDÉ — un fil, une
            // requête, une réunion —, jamais ce qui est revenu. Un journal d'audit qui porterait
            // le texte des messages d'un client serait précisément l'entrepôt de données sensibles
            // que D2 refuse.
            // F-91 : ce qui CRÉE est tracé autrement de ce qui relit — l'usage et la confirmation
            // déclarée, parce que c'est ce qu'on voudra pouvoir dire six mois plus tard.
            // F-110 / SF-110-02 : un courriel se lit par son objet — jamais son corps ni un destinataire.
            case fr.claudegateway.mail.ClientMailTool.NAME -> shorten("Courriel · "
                    + (arg(input, "subject") == null ? "(sans objet)" : arg(input, "subject").strip()),
                    AUDIT_TARGET_CHARS);
            default -> {
                // F-104 / SF-104-03 : un appel Radar se lit en clair, sans identifiant ni contenu de message.
                if (fr.claudegateway.radar.RadarToolCatalog.isRadarTool(call.name())) {
                    yield shorten(fr.claudegateway.radar.RadarToolCatalog.stepTarget(call.name(), input),
                            AUDIT_TARGET_CHARS);
                }
                // F-109 / SF-109-02 : une page se lit par son titre, jamais par son contenu.
                if (fr.claudegateway.pages.PageToolCatalog.isPageTool(call.name())) {
                    yield shorten(fr.claudegateway.pages.PageToolExecutor.auditTarget(input), AUDIT_TARGET_CHARS);
                }
                if (fr.claudegateway.teams.TeamsToolCatalog.isCapture(call.name())) {
                    yield shorten(teamsCaptureAuditTarget(call), AUDIT_TARGET_CHARS);
                }
                yield call.name() != null
                        && call.name().startsWith(fr.claudegateway.teams.TeamsToolCatalog.PREFIX)
                                ? shorten(teamsAuditTarget(input), AUDIT_TARGET_CHARS)
                                : null;
            }
        };
    }

    /**
     * La cible lisible d'un appel Teams : le fil, la question, la réunion — ou, pour une écriture
     * (F-108), le nom et l'emplacement de ce qui est écrit. Jamais un contenu de fichier ni de
     * message.
     */
    private String teamsAuditTarget(JsonNode input) {
        for (String field : List.of("conversation_id", "meeting_id", "capture_id", "query",
                "target", "name", "file", "location", "destination")) {
            String value = arg(input, field);
            if (value != null && !value.isBlank()) {
                return field + '=' + value;
            }
        }
        return null;
    }

    /**
     * <b>La cible journalisée d'un ENREGISTREMENT LOCAL</b> (F-91 / SF-91-02), et le troisième
     * endroit où la trace voyage.
     *
     * <p>Le filigrane est dans l'image, la mention est en tête du compte rendu — et cette ligne-ci
     * est dans le <b>journal d'audit</b>. Elle porte <b>l'usage</b> (son propre écran / une réunion
     * à plusieurs) et <b>la confirmation qui a été donnée</b>, parce que c'est exactement ce qu'on
     * voudra pouvoir dire six mois plus tard : non pas « il a appelé un outil », mais « il a
     * enregistré une réunion, en déclarant avoir prévenu ».</p>
     *
     * <p>Ce qu'elle ne porte <b>pas</b>, comme toutes les lignes Teams : aucun contenu — ni image,
     * ni parole, ni nom de participant.</p>
     */
    private String teamsCaptureAuditTarget(AgentToolCall call) {
        JsonNode input = call.input();
        StringBuilder target = new StringBuilder("enregistrement local");
        String purpose = arg(input, "purpose");
        if (purpose != null && !purpose.isBlank()) {
            target.append(" usage=").append(purpose.strip());
        }
        if (input != null && input.path("participants_informed").asBoolean(false)) {
            target.append(" participants_prevenus=declare");
        } else if (fr.claudegateway.teams.TeamsToolCatalog.CAPTURE_START.equals(call.name())) {
            target.append(" participants_prevenus=non_declare");
        }
        String captureId = arg(input, "capture_id");
        if (captureId != null && !captureId.isBlank()) {
            target.append(" capture=").append(captureId.strip());
        }
        return target.toString();
    }

    /**
     * Assemble le résultat d'une commande pour le modèle (F-38 / SF-38-07, contrat §3) :
     * {@code "$ <commande>\n<sortie entrelacée>\n[code de sortie: N]"}.
     *
     * <p>La sortie vient des trames {@code tool_stream} — dans leur ordre d'émission, donc avec
     * l'entrelacement réel de {@code stdout} et {@code stderr} — et non du {@code content} du
     * {@code tool_result}, qui est vide pour {@code bash}. Elle est bornée en <b>octets</b> : la
     * tête est conservée, c'est là que se trouve la commande qui a échoué.</p>
     *
     * <p>Un code de sortie non nul reste un <b>succès d'appel</b> : la commande a tourné, son échec
     * est une information que le modèle doit lire, pas une panne de la gateway.</p>
     */
    private ToolOutcome bashOutcome(String command, RunnerCallResult result) {
        if (!result.ok()) {
            // F-119 / SF-119-04 : un bash en erreur/timeout ne jette plus la sortie PARTIELLE déjà
            // captée (le modèle concluait sur « le runner n'a pas répondu » alors qu'il y avait des
            // diagnostics), et un échec de transport (timeout/indispo) est formulé « non concluant »,
            // pas comme un résultat négatif — pour couper les conclusions hâtives.
            String note = inconclusiveNote(result);
            String partial = result.streamed();
            if (partial != null && !partial.isBlank()) {
                boolean partialTruncated = result.streamTruncated();
                String bounded = boundBashBytes(partial);
                if (bounded.length() < partial.length()) {
                    partialTruncated = true;
                }
                StringBuilder text = new StringBuilder("$ ").append(command).append('\n').append(bounded);
                if (partialTruncated) {
                    text.append("\n… (sortie tronquée)");
                }
                text.append("\n\n").append(note);
                return ToolOutcome.error(text.toString());
            }
            return ToolOutcome.error(note);
        }
        boolean truncated = result.streamTruncated() || result.truncated();
        String output = result.streamed() == null ? "" : result.streamed();
        byte[] bytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length > MAX_BASH_OUTPUT_BYTES) {
            output = new String(bytes, 0, MAX_BASH_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
            truncated = true;
        }
        StringBuilder text = new StringBuilder("$ ").append(command).append('\n').append(output);
        if (truncated) {
            text.append("\n… (sortie tronquée)");
        }
        if (text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append("[code de sortie: ")
                .append(result.exitCode() == null ? "inconnu" : result.exitCode())
                .append(']');
        return new ToolOutcome(text.toString(), false, null);
    }

    /** Sortie de commande ramenée à {@link #MAX_BASH_OUTPUT_BYTES}, la tête conservée (F-38 / SF-38-07). */
    private static String boundBashBytes(String output) {
        byte[] bytes = output.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (bytes.length <= MAX_BASH_OUTPUT_BYTES) {
            return output;
        }
        return new String(bytes, 0, MAX_BASH_OUTPUT_BYTES, java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Message d'un échec d'appel runner rendu au modèle (F-119 / SF-119-04). Un échec de <b>transport</b>
     * — le runner n'a pas répondu (timeout), n'est pas là (indispo), a répondu de travers — n'est <b>pas
     * un résultat négatif</b> : c'est un « non concluant ». Le dire explicitement coupe les conclusions
     * hâtives (« la commande a échoué » alors que rien n'a été prouvé), et invite à réessayer ou à le
     * signaler. Les autres échecs (fichier introuvable, argument refusé) gardent leur message tel quel.
     */
    private static String inconclusiveNote(RunnerCallResult result) {
        String base = result.errorMessage() == null || result.errorMessage().isBlank()
                ? RunnerErrorCodes.messageFor(result.errorCode())
                : result.errorMessage();
        if (isInconclusiveFailure(result.errorCode())) {
            return "Résultat non concluant : " + base
                    + " Ce n'est pas un résultat négatif — réessaie, ou dis que tu n'as pas pu conclure ; "
                    + "n'en tire aucune conclusion.";
        }
        return base;
    }

    /** Échec de transport où <b>rien n'est prouvé</b> : « non concluant » plutôt que négatif (SF-119-04). */
    private static boolean isInconclusiveFailure(String code) {
        return RunnerErrorCodes.RUNNER_TIMEOUT.equals(code)
                || RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(code)
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(code)
                || RunnerErrorCodes.RUNNER_PROTOCOL_ERROR.equals(code);
    }

    /**
     * Lecture d'un fichier de la machine, rendue en lignes numérotées et paginées (SF-39-06). Le
     * marqueur de troncature du runner est conservé : une lecture partielle doit rester visible,
     * sans quoi l'agent croirait avoir lu tout le fichier.
     */
    private ToolOutcome readOutcome(RunnerCallResult result, JsonNode input) {
        if (!result.ok()) {
            // F-119 / SF-119-04 : une lecture qui échoue par timeout/indispo runner est « non
            // concluante », pas une preuve que le fichier n'existe pas — le dire coupe la conclusion hâtive.
            return ToolOutcome.error(inconclusiveNote(result));
        }
        String page;
        try {
            page = AtelierFileText.numbered(result.content(), intArg(input, "offset"), intArg(input, "limit"));
        } catch (RuntimeException ex) {
            return ToolOutcome.error(ex.getMessage());
        }
        String content = result.truncated() ? page + "\n… (contenu tronqué)" : page;
        return new ToolOutcome(content, false, new AtelierAction("read", arg(input, "path")));
    }

    /**
     * Édition ciblée sur la machine de l'utilisateur (SF-39-06) : lire, remplacer, réécrire — avec
     * les primitives que le runner expose déjà, donc <b>sans</b> évolution du protocole (D1).
     *
     * <p>Une lecture <b>tronquée</b> arrête l'opération (D2) : appliquer un remplacement sur un
     * fragment puis le réécrire détruirait la fin du fichier, en silence. C'est le seul cas où
     * l'outil refuse ce que le modèle croit possible, et il le dit.</p>
     */
    private RunnerCallResult editFileOnRunner(RunnerTarget target, String callId, JsonNode input) {
        String path = requiredArg(input, "path");
        // Identifiant propre pour la lecture interne : deux trames ne partagent jamais une clef de
        // corrélation (contrat de messages §1). L'appel visible reste l'écriture.
        RunnerCallResult read = runnerToolGateway.readFile(target, UUID.randomUUID().toString(), path);
        if (!read.ok()) {
            return read;
        }
        if (read.truncated()) {
            return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT,
                    "Fichier trop volumineux pour une édition ciblée : la lecture a été tronquée.");
        }
        AtelierFileText.Edit edit;
        try {
            edit = AtelierFileText.replace(read.content(), requiredArg(input, "old_string"),
                    input.path("new_string").asText(""), input.path("replace_all").asBoolean(false));
        } catch (RuntimeException ex) {
            return RunnerCallResult.backendError(RunnerErrorCodes.INVALID_INPUT, ex.getMessage());
        }
        RunnerCallResult written = runnerToolGateway.writeFile(target, callId, path, edit.content());
        if (!written.ok()) {
            return written;
        }
        return new RunnerCallResult(true, editedMessage(path, edit.replacements()), false, null,
                written.durationMs(), written.bytes(), null, null, "", false);
    }

    /** Message rendu au modèle après une édition ciblée : ce qui a changé, et combien de fois. */
    private static String editedMessage(String path, int replacements) {
        return "Fichier modifié : " + path + " (" + replacements + " remplacement"
                + (replacements > 1 ? "s)" : ")");
    }

    /** Extrait un argument entier d'un input d'outil, ou {@code null} s'il est absent/illisible. */
    private static Integer intArg(JsonNode input, String name) {
        if (input == null || !input.path(name).isInt()) {
            return null;
        }
        return input.path(name).asInt();
    }

    /** Traduit une issue d'appel runner en résultat d'outil dont le contenu est rendu verbatim. */
    private ToolOutcome textOutcome(RunnerCallResult result, AtelierAction action) {
        if (!result.ok()) {
            // F-121 / SF-121-04 : un échec de transport (y compris après réessai) est « non concluant »
            // pour tout outil, pas seulement bash/read (SF-119-04) ; les autres refus gardent leur message.
            return ToolOutcome.error(inconclusiveNote(result));
        }
        String content = result.truncated()
                ? result.content() + "\n… (contenu tronqué)"
                : result.content();
        return new ToolOutcome(content, false, action);
    }

    private ToolOutcome executeToolOnStorage(UUID userId, UUID workspaceId, AgentToolCall call) {
        try {
            JsonNode input = call.input();
            return switch (call.name()) {
                case "list_files" -> ToolOutcome.info(String.join("\n", workspaceService.tree(userId, workspaceId)));
                case "read_file" -> {
                    String path = requiredArg(input, "path");
                    String content = workspaceService.readFile(userId, workspaceId, path);
                    yield new ToolOutcome(AtelierFileText.numbered(content, intArg(input, "offset"),
                            intArg(input, "limit")), false, new AtelierAction("read", path));
                }
                case "edit_file" -> {
                    String path = requiredArg(input, "path");
                    AtelierFileText.Edit edit = AtelierFileText.replace(
                            workspaceService.readFile(userId, workspaceId, path),
                            requiredArg(input, "old_string"), input.path("new_string").asText(""),
                            input.path("replace_all").asBoolean(false));
                    workspaceService.writeFile(userId, workspaceId, path, edit.content());
                    yield new ToolOutcome(editedMessage(path, edit.replacements()), false,
                            new AtelierAction("write", path));
                }
                case "write_file" -> {
                    String path = requiredArg(input, "path");
                    String content = input.path("content").asText("");
                    workspaceService.writeFile(userId, workspaceId, path, content);
                    yield new ToolOutcome("Fichier écrit : " + path, false, new AtelierAction("write", path));
                }
                case "search_files" -> ToolOutcome.info(search(userId, workspaceId, requiredArg(input, "query")));
                // Grep/Glob sur l'arbre hébergé (F-121 / SF-121-01) : mêmes formats de sortie que le
                // runner, pour que le prompt du modèle ne dérive pas selon la cible d'exécution.
                case "grep" -> ToolOutcome.info(grepStorage(userId, workspaceId, input));
                case "glob" -> ToolOutcome.info(globStorage(userId, workspaceId, input));
                default -> ToolOutcome.error("Outil inconnu : " + call.name());
            };
        } catch (RuntimeException ex) {
            // Erreur métier (fichier introuvable, chemin invalide, trop volumineux…) : renvoyée à
            // l'assistant comme résultat d'erreur (il peut se corriger), jamais un détail sensible.
            return ToolOutcome.error(ex.getMessage() != null ? ex.getMessage() : "Opération refusée.");
        }
    }

    private String requiredArg(JsonNode input, String name) {
        String value = input == null ? null : input.path(name).asText(null);
        if (value == null || value.isBlank()) {
            throw new InvalidFilePathException("Paramètre requis manquant : " + name);
        }
        return value;
    }

    /** Recherche naïve (sous-chaîne) sur les fichiers texte du workspace ; résultat borné. */
    private String search(UUID userId, UUID workspaceId, String query) {
        StringBuilder result = new StringBuilder();
        String needle = query.toLowerCase();
        for (String path : workspaceService.tree(userId, workspaceId)) {
            String content;
            try {
                content = workspaceService.readFile(userId, workspaceId, path);
            } catch (RuntimeException ignored) {
                continue;
            }
            int line = 0;
            for (String text : content.split("\n", -1)) {
                line++;
                if (text.toLowerCase().contains(needle)) {
                    result.append(path).append(':').append(line).append(": ").append(text.strip()).append('\n');
                    if (result.length() > 8_000) {
                        return result.append("… (résultats tronqués)").toString();
                    }
                }
            }
        }
        return result.length() == 0 ? "Aucun résultat." : result.toString();
    }

    /** Borne du résultat de {@code grep}/{@code search} sur l'arbre hébergé, comme le runner. */
    private static final int STORAGE_SEARCH_MAX_CHARS = 8_000;

    /**
     * <b>Grep</b> par expression régulière sur l'arbre hébergé (F-121 / SF-121-01), au même format que
     * le runner ({@code chemin:ligne: texte}, contexte {@code chemin-ligne- texte}). Une regex
     * invalide lève une {@link RuntimeException} traitée en résultat d'erreur par l'appelant.
     */
    private String grepStorage(UUID userId, UUID workspaceId, JsonNode input) {
        String rawPattern = requiredArg(input, "pattern");
        int flags = input.path("ignore_case").asBoolean(false)
                ? java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE : 0;
        java.util.regex.Pattern pattern;
        try {
            pattern = java.util.regex.Pattern.compile(rawPattern, flags);
        } catch (java.util.regex.PatternSyntaxException ex) {
            throw new IllegalArgumentException("Expression régulière invalide.");
        }
        String scope = input.path("path").asText(null);
        String include = input.path("include").asText(null);
        String mode = grepModeStorage(input);
        int context = boundedInt(input, "context");
        int before = Math.max(boundedInt(input, "before"), context);
        int after = Math.max(boundedInt(input, "after"), context);

        StringBuilder result = new StringBuilder();
        for (String path : workspaceService.tree(userId, workspaceId)) {
            if (scope != null && !scope.isBlank() && !(path.equals(scope) || path.startsWith(scope + "/"))) {
                continue;
            }
            if (include != null && !include.isBlank() && !includeMatchesStorage(path, include)) {
                continue;
            }
            String content;
            try {
                content = workspaceService.readFile(userId, workspaceId, path);
            } catch (RuntimeException ignored) {
                continue;
            }
            String[] lines = content.split("\n", -1);
            java.util.List<Integer> matches = new ArrayList<>();
            for (int i = 0; i < lines.length; i++) {
                if (pattern.matcher(lines[i]).find()) {
                    matches.add(i);
                }
            }
            if (matches.isEmpty()) {
                continue;
            }
            switch (mode) {
                case "files_with_matches" -> result.append(path).append('\n');
                case "count" -> result.append(path).append(':').append(matches.size()).append('\n');
                default -> appendGrepContent(result, path, lines, matches, before, after);
            }
            if (result.length() > STORAGE_SEARCH_MAX_CHARS) {
                return result.append("… (résultats tronqués)").toString();
            }
        }
        return result.length() == 0 ? "Aucun résultat." : result.toString();
    }

    /** Écrit les correspondances et leur contexte (F-121 / SF-121-01), format identique au runner. */
    private static void appendGrepContent(StringBuilder result, String path, String[] lines,
            java.util.List<Integer> matches, int before, int after) {
        java.util.SortedSet<Integer> matchSet = new java.util.TreeSet<>(matches);
        java.util.SortedSet<Integer> emit = new java.util.TreeSet<>();
        for (int m : matches) {
            for (int i = Math.max(0, m - before); i <= Math.min(lines.length - 1, m + after); i++) {
                emit.add(i);
            }
        }
        int previous = -2;
        for (int i : emit) {
            if ((before > 0 || after > 0) && previous >= 0 && i > previous + 1) {
                result.append("--\n");
            }
            char sep = matchSet.contains(i) ? ':' : '-';
            result.append(path).append(sep).append(i + 1).append(sep == ':' ? ": " : "- ")
                    .append(lines[i].strip()).append('\n');
            previous = i;
        }
    }

    /**
     * <b>Glob</b> sur l'arbre hébergé (F-121 / SF-121-01) : les chemins correspondant au motif, un par
     * ligne. Le stockage objet n'expose pas de date de modification fiable — le tri est ici par chemin
     * (déterministe) ; le tri par date reste propre à la cible RUNNER, qui l'a.
     */
    private String globStorage(UUID userId, UUID workspaceId, JsonNode input) {
        String rawPattern = requiredArg(input, "pattern");
        java.nio.file.PathMatcher matcher;
        try {
            matcher = java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + rawPattern);
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Motif glob invalide.");
        }
        String scope = input.path("path").asText(null);
        java.util.List<String> matched = new ArrayList<>();
        for (String path : workspaceService.tree(userId, workspaceId)) {
            if (scope != null && !scope.isBlank() && !(path.equals(scope) || path.startsWith(scope + "/"))) {
                continue;
            }
            if (matcher.matches(java.nio.file.Paths.get(path))) {
                matched.add(path);
            }
        }
        java.util.Collections.sort(matched);
        return matched.isEmpty() ? "Aucun résultat." : String.join("\n", matched);
    }

    private static String grepModeStorage(JsonNode input) {
        String mode = input.path("output_mode").asText(null);
        return ("files_with_matches".equals(mode) || "count".equals(mode)) ? mode : "content";
    }

    private static int boundedInt(JsonNode input, String field) {
        JsonNode value = input.get(field);
        if (value == null || !value.isIntegralNumber()) {
            return 0;
        }
        int n = value.asInt();
        return n < 0 ? 0 : Math.min(n, 100);
    }

    /** Un motif {@code include} sans {@code /} porte sur le nom de fichier, sinon sur le chemin complet. */
    private static boolean includeMatchesStorage(String path, String include) {
        String candidate = include.indexOf('/') >= 0 ? path : path.substring(path.lastIndexOf('/') + 1);
        try {
            return java.nio.file.FileSystems.getDefault().getPathMatcher("glob:" + include)
                    .matches(java.nio.file.Paths.get(candidate));
        } catch (RuntimeException ex) {
            return false;
        }
    }

    /**
     * Outils exposés au modèle. Les quatre outils fichiers sont inconditionnels ; {@code bash}
     * n'apparaît qu'en cible <b>{@code RUNNER}</b> (F-38 / SF-38-07).
     *
     * <p>La condition n'est pas cosmétique : en cible {@code SANDBOX}, il n'existe aucun endroit où
     * exécuter une commande — le backend est une gateway, il n'exécute rien lui-même. Exposer
     * l'outil reviendrait à promettre au modèle une capacité qui n'aboutirait qu'à des erreurs.</p>
     *
     * <p><b>C'est aussi ici que le volet Teams est ouvert ou fermé</b> (F-89 / SF-89-01) : le
     * catalogue {@code teams_*} n'est ajouté que si le workspace est un <b>terminal Teams</b> et que
     * le <b>droit</b> est ouvert. Sans l'un ou l'autre, les outils ne sont pas donnés — l'agent ne
     * refuse pas, il n'a pas la capacité. La règle vit dans {@code TeamsToolCatalog}, à un seul
     * endroit, pour qu'aucun outil ajouté plus tard n'échappe à la garde.</p>
     */
    /** Panoplie du tour en mode {@link AgentTurnMode#ACT} — forme historique conservée. */
    List<AgentTool> buildTools(java.util.UUID userId, Workspace workspace) {
        return buildTools(userId, workspace, AgentTurnMode.ACT);
    }

    /**
     * Panoplie du tour selon le <b>mode</b> (F-120 / SF-120-02). En {@link AgentTurnMode#ANSWER_PLAN},
     * la panoplie complète est construite comme d'habitude puis <b>filtrée</b> par la liste blanche
     * {@link #ANSWER_PLAN_TOOLS} : ne subsistent que lecture, exploration et {@code set_plan} ; les
     * outils mutants ({@code write_file}, {@code edit_file}, {@code bash}) et de volet sont retirés.
     * En {@link AgentTurnMode#ACT} (ou {@code null}), la panoplie est rendue telle quelle
     * (comportement d'avant SF-120-02).
     */
    List<AgentTool> buildTools(java.util.UUID userId, Workspace workspace, AgentTurnMode mode) {
        List<AgentTool> full = buildToolsFull(userId, workspace);
        if (mode == AgentTurnMode.ANSWER_PLAN) {
            return full.stream().filter(tool -> ANSWER_PLAN_TOOLS.contains(tool.name())).toList();
        }
        return full;
    }

    private List<AgentTool> buildToolsFull(java.util.UUID userId, Workspace workspace) {
        Map<String, Object> stringProp = Map.of("type", "string");
        List<AgentTool> tools = new ArrayList<>(fileTools(stringProp, workspace.isRunnerTarget()));
        if (workspace.isRunnerTarget()) {
            tools.add(new AgentTool("bash",
                    "Exécute une commande shell sur la machine connectée (runner), depuis la racine "
                            + "du projet. Renvoie la sortie (stdout et stderr) et le code de sortie.",
                    Map.of("type", "object",
                            "properties", Map.of("command", stringProp, "cwd", stringProp),
                            "required", List.of("command"))));
        }
        // L'exploration est déclarée sur les deux cibles, et seulement si elle est autorisée
        // (F-39 / SF-39-14). Elle absorbe le volume de lecture qui, sinon, remplit le contexte du
        // travail principal.
        if (maxDelegations > 0) {
            tools.add(new AgentTool("explore",
                    "Délègue une exploration en LECTURE SEULE à un agent qui ne voit pas cette "
                            + "conversation : il lit, cherche, et te rend une réponse courte. Utile "
                            + "quand répondre demande de parcourir beaucoup de fichiers dont tu n'as "
                            + "pas besoin ensuite. Il ne peut ni écrire, ni exécuter de commande.",
                    Map.of("type", "object",
                            "properties", Map.of("question", stringProp, "path", stringProp),
                            "required", List.of("question"))));
        }
        // Le plan est déclaré sur les DEUX cibles : c'est un outil d'organisation, pas d'exécution
        // (F-39 / SF-39-13). Rien de ce qu'il fait ne dépend de l'endroit où le code tourne.
        tools.add(new AgentTool("set_plan",
                "Pose ou met à jour ton plan de travail pour ce message. N'établis ou ne mets à jour "
                        + "un plan que si l'utilisateur te demande de planifier ou d'exécuter, ou si tu "
                        + "vas effectivement agir — pas parce que le mot « plan » apparaît dans le "
                        + "message. Envoie la liste COMPLÈTE des étapes à chaque appel : elle remplace "
                        + "la précédente. Marque une seule étape active à la fois, et mets-la à jour dès "
                        + "qu'une étape est terminée. Utile dès que le travail dépasse deux ou trois "
                        + "étapes ; inutile sinon.",
                Map.of("type", "object",
                        "properties", Map.of("steps", Map.of(
                                "type", "array",
                                "items", Map.of("type", "object",
                                        "properties", Map.of(
                                                "title", Map.of("type", "string"),
                                                "status", Map.of("type", "string",
                                                        "enum", List.of("pending", "active", "done"))),
                                        "required", List.of("title")))),
                        "required", List.of("steps"))));
        // Le volet Teams, en dernier : ce qui précède est la panoplie de tout terminal, ce qui suit
        // n'existe que là où Teams a été payé ET où l'on est dans SON terminal (F-89 / SF-89-01).
        tools.addAll(teamsToolCatalog.toolsFor(userId, workspace));
        // Le Radar du client (F-104 / SF-104-01) : seulement dans le terminal Teams d'un poste suivi par la
        // Vigie, et avec le droit Vigie. La règle vit dans RadarToolCatalog, à un seul endroit.
        tools.addAll(radarToolCatalog.toolsFor(userId, workspace));
        // Le courriel du client (F-110 / SF-110-02) : dans tout terminal d'un poste, avec la Forge ou la Vigie.
        // La garde et le destinataire vivent dans ClientMailTool.
        if (clientMailTool != null) {
            clientMailTool.toolFor(userId, workspace).ifPresent(tools::add);
        }
        // Les pages (F-109 / SF-109-02) : sur un poste, avec le droit de l'espace du terminal. La règle vit
        // dans PageToolCatalog, à un seul endroit.
        tools.addAll(pageToolCatalog.toolsFor(userId, workspace));
        return List.copyOf(tools);
    }

    /**
     * Outils fichiers déclarés au modèle (F-39 / SF-39-05, décision D4 du cadrage).
     *
     * <p>Quand {@code bash} est disponible — cible {@code RUNNER} — {@code list_files} et
     * {@code search_files} ne sont <b>pas</b> déclarés : {@code ls}, {@code find} et {@code grep -n}
     * font strictement mieux (filtres, profondeur, expressions régulières, numéros de ligne), et
     * l'usage réel mesuré est déjà à 95 % de {@code bash}. Deux définitions de moins, ce sont deux
     * définitions qu'on ne paie plus à chaque itération dans le préfixe caché.</p>
     *
     * <p>En cible {@code SANDBOX}, elles restent : il n'y a pas de {@code bash} là-bas, et les
     * retirer priverait le modèle de tout moyen d'explorer sans rien lui donner en échange
     * (décision D1 de la subfeature). Leur sort suit celui de la cible elle-même, en SF-39-16.</p>
     */
    private List<AgentTool> fileTools(Map<String, Object> stringProp, boolean bashAvailable) {
        List<AgentTool> tools = new ArrayList<>();
        if (!bashAvailable) {
            tools.add(new AgentTool("list_files", "Liste tous les fichiers du projet (chemins relatifs).",
                    Map.of("type", "object", "properties", Map.of())));
        }
        Map<String, Object> intProp = Map.of("type", "integer");
        tools.add(new AgentTool("read_file",
                "Lit un fichier du projet en lignes numérotées. Pagine avec offset (première ligne, "
                        + "1 par défaut) et limit (2000 au plus).",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "offset", intProp, "limit", intProp),
                        "required", List.of("path"))));
        tools.add(new AgentTool("write_file",
                "Écrit un fichier du projet en ÉCRASANT tout son contenu. Pour modifier un fichier "
                        + "existant, préfère edit_file : write_file remplace le fichier entier et perd "
                        + "ce que tu n'as pas réécrit.",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "content", stringProp),
                        "required", List.of("path", "content"))));
        tools.add(new AgentTool("edit_file",
                "Remplace un passage exact dans un fichier du projet. Copie old_string EXACTEMENT tel "
                        + "qu'il apparaît, indentation et espaces compris ; lis le fichier avant de "
                        + "l'éditer. old_string doit être unique, sinon passe replace_all à true. En "
                        + "cas d'échec, relis le fichier avant de réessayer. À préférer à write_file "
                        + "pour modifier un fichier.",
                Map.of("type", "object",
                        "properties", Map.of("path", stringProp, "old_string", stringProp,
                                "new_string", stringProp, "replace_all", Map.of("type", "boolean")),
                        "required", List.of("path", "old_string", "new_string"))));
        if (!bashAvailable) {
            tools.add(new AgentTool("search_files", "Recherche une chaîne dans les fichiers du projet.",
                    Map.of("type", "object", "properties", Map.of("query", stringProp),
                            "required", List.of("query"))));
        }
        // Grep et Glob (F-121 / SF-121-01) : déclarés sur les DEUX cibles, indépendamment de bash. Ce
        // sont les outils d'investigation de première classe — un vrai grep par regex (au lieu de la
        // sous-chaîne de search_files, et sans dépendre du shell du poste) et un glob trié par date.
        Map<String, Object> boolProp = Map.of("type", "boolean");
        tools.add(new AgentTool("grep",
                "Cherche une EXPRESSION RÉGULIÈRE dans les fichiers du projet, en un appel. "
                        + "pattern (requis) est une regex. Options : path (limite la recherche à un "
                        + "sous-dossier), include (motif de nom de fichier, ex. \"*.java\" ou "
                        + "\"src/**/*.ts\"), ignore_case, output_mode (\"content\" par défaut = lignes "
                        + "au format chemin:ligne: texte ; \"files_with_matches\" = chemins seuls ; "
                        + "\"count\" = nombre par fichier), et before/after/context pour les lignes "
                        + "voisines (-B/-A/-C). Préfère grep à bash pour chercher : c'est plus rapide "
                        + "et indépendant du shell.",
                Map.of("type", "object",
                        "properties", new java.util.LinkedHashMap<>(Map.of(
                                "pattern", stringProp, "path", stringProp, "include", stringProp,
                                "ignore_case", boolProp, "output_mode", stringProp,
                                "before", intProp, "after", intProp, "context", intProp)),
                        "required", List.of("pattern"))));
        tools.add(new AgentTool("glob",
                "Liste les fichiers du projet dont le chemin correspond à un motif glob (ex. "
                        + "\"**/*.java\", \"src/**/*.ts\"), triés du plus récemment modifié au plus "
                        + "ancien. Option path pour limiter à un sous-dossier. Utile pour trouver des "
                        + "fichiers par nom ou extension sans lister tout le projet.",
                Map.of("type", "object",
                        "properties", Map.of("pattern", stringProp, "path", stringProp),
                        "required", List.of("pattern"))));
        return List.copyOf(tools);
    }

    /**
     * Panoplie de la sous-boucle d'exploration (F-39 / SF-39-20) : {@code list_files},
     * {@code read_file}, {@code search_files} — <b>les mêmes sur les deux cibles</b> (décision D2).
     *
     * <p>Elle ne dépend <b>pas</b> du workspace, et c'est tout le correctif. La version précédente
     * filtrait la panoplie du travail principal ; en cible {@code RUNNER}, où SF-39-05 a retiré
     * {@code list_files} et {@code search_files} au profit de {@code bash} — que l'exploration n'a
     * pas le droit d'appeler (D2 de SF-39-14, la porte de confirmation) —, il n'en restait
     * qu'un. La sous-boucle pouvait lire un chemin qu'on lui donnait, jamais en trouver un.</p>
     *
     * <p>Les définitions sont reprises de {@link #fileTools} plutôt que réécrites : la sous-boucle
     * doit lire un fichier exactement comme le travail principal — pagination comprise
     * (SF-39-06) —, et deux descriptions à maintenir en parallèle finiraient par diverger.
     * {@code bashAvailable} est passé à {@code false} parce que la sous-boucle n'a jamais de
     * {@code bash} : ce n'est pas la cible qu'on décrit ici, c'est elle.</p>
     */
    private List<AgentTool> explorationTools() {
        return fileTools(Map.of("type", "string"), false).stream()
                .filter(tool -> READ_ONLY_TOOLS.contains(tool.name()))
                .toList();
    }

    /**
     * Consigne système : conventions du projet (CLAUDE.md) + skills + rôle de l'agent.
     *
     * <p>Les lectures passent par la <b>cible d'exécution</b> du workspace (F-38 / SF-38-05). En cible
     * {@code RUNNER}, le stockage objet est vide : lire là-bas enverrait une consigne sans les
     * conventions du projet, <b>en silence</b> (les lectures optionnelles avalent l'erreur). C'est
     * exactement la panne qu'on ne verrait pas.</p>
     */
    /** Consigne système du tour en mode {@link AgentTurnMode#ACT} — forme historique conservée. */
    String buildSystemPrompt(UUID userId, Workspace workspace) {
        return buildSystemPrompt(userId, workspace, AgentTurnMode.ACT);
    }

    String buildSystemPrompt(UUID userId, Workspace workspace, AgentTurnMode mode) {
        StringBuilder system = new StringBuilder();
        // L'énoncé du rôle suit l'outillage réellement déclaré (SF-39-05) : annoncer des outils qui
        // n'existent pas dans ce projet ne produirait que des appels perdus.
        if (workspace.isRunnerTarget()) {
            // La syntaxe d'exploration suit l'interpréteur que le runner a élu et déclaré
            // (F-38 / SF-38-27). Dicter `ls`/`find`/`grep -n` à un poste qui n'a que `cmd.exe`
            // faisait échouer chaque exploration — et sur cette cible, bash est le SEUL moyen
            // d'explorer, puisque list_files et search_files n'y sont pas déclarés (SF-39-05).
            system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                    .append("sur sa machine. ")
                    .append(RunnerShell.resolve(runnerHostService.declaredShell(workspace.getHostId()))
                            .explorationGuidance())
                    .append(" Utilise read_file pour lire un fichier que tu vas ")
                    .append("utiliser, et write_file pour l'écrire. Ne fais aucune supposition sur un fichier ")
                    .append("sans l'avoir lu. Après une modification, résume clairement ce que tu as changé.\n\n");
        } else {
            system.append("Tu es un assistant de développement qui travaille sur le projet de l'utilisateur, ")
                    .append("dans un espace de travail hébergé. Utilise les outils fournis (list_files, read_file, ")
                    .append("write_file, search_files) pour lire et modifier les fichiers du projet. ")
                    .append("Ne fais aucune supposition sur un fichier sans l'avoir lu. Après une modification, ")
                    .append("résume clairement ce que tu as changé.\n\n");
        }

        // Discipline d'investigation (F-119 / SF-119-02) : ajoutée sur les DEUX cibles, juste après le
        // rôle — c'est ce qui pousse l'agent à se vérifier avant d'affirmer, plutôt que d'improviser
        // et de se rattraper au tour suivant. Placée en tête, elle survit à la coupe SYSTEM_MAX_CHARS.
        system.append(INVESTIGATION_DISCIPLINE);

        // Doctrine de retenue (F-120 / SF-120-01) : « réponds d'abord, agis sur demande », sur les DEUX
        // cibles, juste après la discipline d'investigation — les deux vivent en tête du préfixe stable.
        // La discipline dit COMMENT vérifier quand on agit ; la doctrine dit QUAND agir : une question
        // reçoit une réponse, pas une mutation non demandée.
        system.append(RESTRAINT_DOCTRINE);

        // Style de réponse (F-121 / SF-121-03) : sur les DEUX cibles, en tête du préfixe stable, aux
        // côtés de la discipline (SF-119-02) et de la doctrine (SF-120-01) — la concision orientée
        // terminal existait dans la sous-boucle explore, elle devient une règle du travail principal.
        system.append(RESPONSE_STYLE);

        // Silence de la tenue de carte (F-125 / SF-125-01) : sur les DEUX cibles, en tête du préfixe
        // stable, aux côtés des trois consignes ci-dessus. La carte se tient en coulisse ; elle ne se
        // raconte jamais dans la réponse, et aucun terme de plomberie n'y apparaît.
        system.append(CARD_SILENCE_DOCTRINE);

        // Balisage de la réponse essentielle (F-126 / SF-126-01) : sur les DEUX cibles, en tête du
        // préfixe stable, à la suite des consignes ci-dessus. Prolonge « réponds d'abord » (SF-120-01)
        // et le style (SF-121-03) : l'essentiel est la réponse directe et courte, balisée pour que le
        // frontend la mette en avant ; le marqueur ne collisionne pas avec le strip fin-de-tour (F-125).
        system.append(ESSENTIAL_ANSWER_DOCTRINE);

        // Conseil / décision : tranche, ne range pas (F-125 / SF-125-05) : sur les DEUX cibles, à la
        // suite des consignes ci-dessus. Prolonge la carte silencieuse (SF-125-01) et le balisage de
        // l'essentiel (SF-126-01) sans les écraser : sur une question de conseil, l'agent prend
        // position et balise l'essentiel même court, et ne répond jamais par un statut de rangement.
        system.append(ADVICE_DECISION_DOCTRINE);

        // Mode explicite « Réponse/Plan » (F-120 / SF-120-02) : quand l'utilisateur l'a choisi, on
        // renforce la doctrine par une consigne de mode, en écho au retrait des outils mutants dans
        // buildTools. Placée juste après la doctrine, en tête du préfixe (à l'abri de SYSTEM_MAX_CHARS).
        if (mode == AgentTurnMode.ANSWER_PLAN) {
            system.append(ANSWER_PLAN_DIRECTIVE);
        }

        // F-89 / SF-89-04 : un terminal Teams sans droit le DIT. Sans ce paragraphe, l'agent — privé
        // de ses outils teams_* en silence (SF-89-01) — fouillait la machine comme un terminal de
        // projet. Placé juste après le rôle : c'est ce qui change le sens de tout le reste.
        if (teamsToolCatalog.isClosedFor(userId, workspace)) {
            system.append(fr.claudegateway.teams.TeamsToolCatalog.CLOSED_NOTICE).append("\n\n");
        } else if (teamsToolCatalog.isRemovedFromVigie(userId, workspace)) {
            // F-106 / SF-106-07 : le volet Teams est bien actif sur le compte, mais ce client a été
            // retiré de la Vigie — l'agent n'a plus ses outils teams_*, et il le dit plutôt que de
            // fouiller la machine (même doctrine que SF-89-04).
            system.append(fr.claudegateway.teams.TeamsToolCatalog.REMOVED_FROM_VIGIE_NOTICE).append("\n\n");
        } else if (workspace.isTeamsTerminal()) {
            // F-89 / SF-89-11 : quand les outils Teams SONT donnés (volet ouvert, client dans la
            // Vigie), la règle non négociable de l'échec de lecture voyage aussi dans la consigne
            // système — pas seulement dans les descriptions d'outils. Sur un zéro : bloc d'échec et
            // arrêt, jamais un repli silencieux sur le poste.
            system.append(fr.claudegateway.teams.TeamsToolCatalog.READ_FAILURE_RULE).append("\n\n");
        }
        // F-104 / SF-104-03 : le Radar du client, sous la même garde que ses outils — registre d'abord, et la
        // parole de l'utilisateur pour seule preuve d'une écriture.
        if (radarToolCatalog.isOpenFor(userId, workspace)) {
            system.append(fr.claudegateway.radar.RadarToolCatalog.TERMINAL_NOTICE).append("\n\n");
        }
        // F-109 / SF-109-02 : le guide de conception des pages, sous la même garde que l'outil.
        if (pageToolCatalog.isOpenFor(userId, workspace)) {
            system.append(fr.claudegateway.pages.PageToolCatalog.DESIGN_GUIDE).append("\n\n");
        }

        // Compteurs d'amorçage : ces lectures sont journalisées en UNE ligne (F-38 / SF-38-08).
        // Les tracer une par une noierait le journal sous des dizaines d'entrées que l'utilisateur
        // n'a pas demandées, et masquerait ce qu'il cherche : ce que le modèle a décidé de lire.
        int reads = 0;
        long chars = 0L;

        // F-136 / SF-136-02 — CE QU'ON SAIT DÉJÀ DE CE CLIENT, avant tout ce qui vient du projet.
        // Placé ici, dans le préfixe stable : le bloc ne porte que des TITRES (fichiers, sections),
        // jamais un compte de faits ni une date — ces deux-là changent à chaque tour et
        // reconstruiraient le cache à chaque demande, exactement le défaut que F-134 a corrigé.
        String knowledge = hostOutline(userId, workspace);
        if (knowledge != null) {
            system.append(knowledge);
        }

        java.util.Optional<String> instructions = readOptional(userId, workspace, "CLAUDE.md");
        if (instructions.isPresent()) {
            reads++;
            chars += instructions.get().length();
            // F-120 / SF-120-01 : le préambule cadre le CLAUDE.md injecté verbatim — ces conventions
            // valent quand on IMPLÉMENTE, pas quand l'utilisateur pose une simple question.
            system.append(GOVERNANCE_PREAMBLE)
                    .append("--- Conventions du projet (CLAUDE.md) ---\n")
                    .append(instructions.get()).append("\n\n");
        }

        // Les règles de gouvernance viennent APRÈS les conventions du projet et AVANT les skills
        // (arbitrage C1) : ce que l'utilisateur a écrit pour ce projet précis reste ce qu'on lit en
        // premier ; un paquet le complète, il ne le remplace pas.
        String governance = governanceRules(userId, workspace.getId());
        if (governance != null) {
            system.append(GOVERNANCE_HEADER).append('\n').append(governance).append("\n\n");
        }

        List<String> tree = safeTree(userId, workspace);
        if (!tree.isEmpty()) {
            reads++; // Le listage est lui aussi une action menée sur la machine.
        }
        List<String> skillPaths = tree.stream().filter(AtelierChatService::isSkillPath).toList();
        StringBuilder catalog = new StringBuilder();
        for (String path : skillPaths.stream().limit(MAX_SKILLS_ANNOUNCED).toList()) {
            java.util.Optional<String> skill = readOptional(userId, workspace, path);
            if (skill.isEmpty()) {
                continue; // Skill illisible : ignoré, jamais bloquant pour les autres.
            }
            reads++;
            chars += skill.get().length();
            String description = describeSkill(skill.get());
            catalog.append("- ").append(path);
            if (!description.isEmpty()) {
                catalog.append(" : ").append(description);
            }
            catalog.append('\n');
        }
        if (catalog.length() > 0) {
            system.append("--- Skills du projet (lis le fichier pour le mode d'emploi complet) ---\n")
                    .append(catalog);
            if (skillPaths.size() > MAX_SKILLS_ANNOUNCED) {
                system.append("… et ").append(skillPaths.size() - MAX_SKILLS_ANNOUNCED)
                        .append(" autre(s) skill(s) non listé(s).\n");
            }
            system.append("Ouvre un skill avec read_file au moment où il sert ; ne suppose pas son contenu.\n\n");
        }
        if (workspace.isRunnerTarget()) {
            runnerAuditService.recordBootstrap(userId, RunnerTargets.of(workspace),
                    UUID.randomUUID().toString(), reads, chars);
        }
        String result = system.toString();
        return result.length() > SYSTEM_MAX_CHARS ? result.substring(0, SYSTEM_MAX_CHARS) : result;
    }

    /**
     * Règles de gouvernance du projet, ou {@code null}.
     *
     * <p><b>Repli passant</b> (arbitrage C2, même geste que F-50) : une gouvernance en panne rend un
     * tour SANS règles plutôt qu'un tour raté. Un bogue de gouvernance ne doit pas condamner le
     * travail d'un utilisateur, qui n'a rien pour le débrayer.</p>
     */
    /**
     * Le sommaire de ce que la gateway sait du client de ce projet, ou {@code null} (F-136 /
     * SF-136-02).
     *
     * <p><b>Repli passant</b>, même geste que pour les règles : un magasin en panne rend un tour
     * <b>sans</b> savoir plutôt qu'un tour raté. Ce savoir est un avantage, jamais une condition.</p>
     */
    private String hostOutline(UUID userId, Workspace workspace) {
        try {
            return hostKnowledge.outlineFor(userId, workspace.getId());
        } catch (RuntimeException ex) {
            log.warn("Sommaire de carte ignoré pour ce tour ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    private String governanceRules(UUID userId, UUID workspaceId) {
        try {
            String rules = projectRules.rulesFor(userId, workspaceId);
            return rules == null || rules.isBlank() ? null : rules;
        } catch (RuntimeException ex) {
            log.warn("Règles de gouvernance ignorées pour ce tour ({})", ex.getClass().getSimpleName());
            return null;
        }
    }

    /** Un fichier du projet est-il un skill ? Mêmes préfixes qu'avant SF-39-02. */
    private static boolean isSkillPath(String path) {
        return SKILL_PREFIXES.stream().anyMatch(path::startsWith);
    }

    /**
     * Description d'un skill pour le catalogue (F-39 / SF-39-02) : la clef {@code description} de
     * l'entête YAML si le fichier en a un, sinon sa première ligne utile — titres Markdown et
     * délimiteurs d'entête exclus, car un titre répète le nom du fichier sans rien apprendre.
     *
     * <p>Le résultat est toujours <b>une ligne</b> et borné : le catalogue est le préfixe qu'on
     * cherche à garder court et stable, une description de dix lignes le ruinerait.</p>
     *
     * @return la description, ou une chaîne vide s'il n'y en a pas d'exploitable
     */
    static String describeSkill(String body) {
        if (body == null || body.isBlank()) {
            return "";
        }
        String[] lines = body.split("\n", -1);
        if (lines[0].strip().equals("---")) {
            for (int i = 1; i < lines.length; i++) {
                String line = lines[i];
                if (line.strip().equals("---")) {
                    break; // Fin de l'entête : pas de clef description.
                }
                if (line.regionMatches(true, 0, "description:", 0, "description:".length())) {
                    return flatten(unquote(line.substring("description:".length())));
                }
            }
        }
        for (String line : lines) {
            String stripped = line.strip();
            if (stripped.isEmpty() || stripped.equals("---") || stripped.startsWith("#")) {
                continue;
            }
            return flatten(stripped);
        }
        return "";
    }

    /** Retire les guillemets d'une valeur YAML simple ({@code description: "…"}). */
    private static String unquote(String value) {
        String text = value.strip();
        boolean quoted = text.length() >= 2
                && (text.startsWith("\"") && text.endsWith("\"")
                        || text.startsWith("'") && text.endsWith("'"));
        return quoted ? text.substring(1, text.length() - 1) : text;
    }

    /** Une ligne, espaces normalisés, bornée — avec un « … » quand la coupe a eu lieu. */
    private static String flatten(String text) {
        String line = text.replaceAll("\\s+", " ").strip();
        return line.length() <= SKILL_DESCRIPTION_CHARS
                ? line
                : line.substring(0, SKILL_DESCRIPTION_CHARS) + "…";
    }

    /** Arborescence pour la consigne système, prise là où les fichiers vivent réellement. */
    private List<String> safeTree(UUID userId, Workspace workspace) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.listFiles(
                        RunnerTargets.of(workspace), UUID.randomUUID().toString());
                return result.ok() ? List.of(result.content().split("\n")) : List.of();
            }
            return workspaceService.tree(userId, workspace.getId());
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    /** Lecture optionnelle pour la consigne système, prise là où les fichiers vivent réellement. */
    private java.util.Optional<String> readOptional(UUID userId, Workspace workspace, String path) {
        try {
            if (workspace.isRunnerTarget()) {
                RunnerCallResult result = runnerToolGateway.readFile(
                        RunnerTargets.of(workspace), UUID.randomUUID().toString(), path);
                return result.ok() ? java.util.Optional.of(result.content()) : java.util.Optional.empty();
            }
            return java.util.Optional.of(workspaceService.readFile(userId, workspace.getId(), path));
        } catch (RuntimeException ex) {
            return java.util.Optional.empty();
        }
    }

    /**
     * Résultat d'un tour d'atelier.
     *
     * @param reply         réponse finale rendue à l'utilisateur
     * @param actions       fichiers lus/écrits pendant le tour
     * @param messageId     identifiant du message assistant persisté
     * @param inputTokens   tokens d'entrée du tour, cache compris (F-39 / SF-39-15)
     * @param outputTokens  tokens de sortie du tour
     * @param activeSeconds durée d'horloge du tour, en secondes
     * @param budgetReached le tour s'est arrêté sur le <b>plafond de consommation</b> du message —
     *                      jamais sur le budget de temps, qui dit déjà sa cause dans {@code reply}
     */
    public record AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId,
            long inputTokens, long outputTokens, long activeSeconds, boolean budgetReached,
            java.math.BigDecimal costUsd, Integer reusedPercent) {

        /** Forme historique, conservée pour les appelants (et les tests) qui l'attendent. */
        public AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId) {
            this(reply, actions, messageId, 0L, 0L, 0L, false, null, null);
        }

        /** Forme sans coût (F-133 / SF-133-02 ne concerne que la boucle d'atelier). */
        public AtelierChatResult(String reply, List<AtelierAction> actions, UUID messageId,
                long inputTokens, long outputTokens, long activeSeconds, boolean budgetReached) {
            this(reply, actions, messageId, inputTokens, outputTokens, activeSeconds, budgetReached,
                    null, null);
        }

        /**
         * Le tour s'est arrêté sur une <b>interruption</b> explicite (F-84 / SF-84-06) — lu sur la
         * réponse de repli, seule trace que la boucle en laisse. Une précision restée en file ne
         * rouvre alors pas de tour : l'interruption est le geste qui arrête vraiment.
         */
        public boolean interrupted() {
            return INTERRUPTED_REPLY.equals(reply);
        }
    }

    /** Issue interne d'un outil : contenu renvoyé au modèle, indicateur d'erreur, action pour l'UI. */
    private record ToolOutcome(String content, boolean isError, AtelierAction action) {
        static ToolOutcome info(String content) {
            return new ToolOutcome(content, false, null);
        }

        static ToolOutcome error(String message) {
            return new ToolOutcome(message, true, null);
        }
    }
}
