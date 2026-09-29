package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;

/**
 * Compaction automatique de l'historique d'un fil d'Atelier (F-117 / SF-117-01).
 *
 * <p>Le constat de l'audit : dans la boucle maison, le <b>texte</b> de la conversation repart en
 * entier au fournisseur à chaque tour, sans borne — seuls les résultats d'outils et les trajectoires
 * étaient élagués. Un terminal toujours ouvert finit donc par dépasser la fenêtre du modèle. Ce
 * service pose la première des trois défenses : quand le texte rejoué franchit un seuil de sécurité
 * <b>sous</b> la fenêtre réelle, les tours anciens sont <b>résumés</b> en un bloc compact (un appel
 * modèle dédié, borné, via {@link AiAgentProvider}), les tours récents gardés <b>intégralement</b>, et
 * seul ce résumé + les tours récents repartent au fournisseur.</p>
 *
 * <p><b>L'affichage garde tout</b> : aucun message n'est supprimé en base — on réutilise la frontière
 * de fil {@code chatThreadStartedAt} (SF-39-04), qui délimite déjà ce qui repart, en la posant
 * <b>automatiquement</b> avec un résumé injecté au lieu d'un reset sec.</p>
 *
 * <p><b>Cache de prompt</b> : le résumé devient un préfixe stable, injecté après la consigne système
 * (elle-même cachée). Il ne change qu'à la compaction suivante — entre-temps, le cache reste chaud.</p>
 *
 * <p><b>Isolation multi-tenant</b> : ne lit que les messages du fil filtrés par {@code workspace_id}
 * + {@code user_id} (le {@code requireOwned} reste en amont, dans {@code AtelierChatService}). Le
 * journal mentionne le poste ({@code host_id}) sans aucun contenu.</p>
 */
@Service
public class AtelierCompactionService {

    private static final Logger log = LoggerFactory.getLogger(AtelierCompactionService.class);

    /**
     * Diviseur caractères → tokens de l'estimation (F-117 / SF-117-01, ratio révisé par
     * F-121 / SF-121-18). Volontairement une <b>heuristique</b> et non un décompte exact : ce dernier
     * exigerait un appel réseau à chaque tour, précisément ce qu'on cherche à borner.
     *
     * <p><b>Pourquoi 3,5 et non 4.</b> Quatre caractères par token est le rapport usuel de la
     * <b>prose</b>. Ce qui est rejoué ici n'en est pas : chemins de fichiers, diffs, sorties de
     * commandes et JSON de trajectoires d'outils (jusqu'à douze tours depuis SF-119-03, comptés
     * depuis F-121-00). Ce matériau se tokenise plus densément — ponctuation, identifiants
     * {@code camelCase}, séparateurs de chemin et indentation coupent les tokens court —, si bien
     * qu'à 4 l'estimation était <b>systématiquement basse d'environ 14 %</b> : le seuil était franchi
     * en réalité alors que l'estimation le croyait tenu, et c'est le filet réactif « prompt too
     * long » (SF-117-02) qui rattrapait après coup, au prix d'un tour relancé.</p>
     *
     * <p>L'erreur n'est pas symétrique : surestimer ne coûte qu'une compaction un peu plus tôt,
     * sous-estimer coûte un tour perdu. L'arrondi va donc vers le pessimisme.</p>
     */
    static final double CHARS_PER_TOKEN = 3.5;

    /**
     * Titres du <b>gabarit sectionné</b> du résumé (F-121 / SF-121-09), dans l'ordre imposé. Le
     * résumé n'est plus de la prose libre : il porte toujours les mêmes rubriques, à la même place.
     *
     * <p><b>Pourquoi un gabarit</b> : le résumé est réinjecté en tête du rejeu ({@link
     * #summaryPrefix}) et la compaction est <b>incrémentale</b> (on résume le résumé précédent plus
     * les tours accumulés depuis). En prose libre, chaque passe reformule la précédente et
     * l'information dérive — « ce qui reste à faire » est la première victime de la compression.
     * Avec un gabarit, chaque passe <b>fusionne section par section</b> et la reprise sait où
     * regarder.</p>
     */
    static final List<String> SUMMARY_SECTIONS = List.of(
            "## Objectif",
            "## Fichiers",
            "## Décisions et faits établis",
            "## État courant",
            "## Prochaines étapes");

    /**
     * Consigne du résumé (F-117 / SF-117-01, gabarit F-121 / SF-121-09) : un compte rendu factuel et
     * compact, orienté <b>reprise du travail</b> — ce qui a été décidé, les fichiers touchés, l'état
     * courant de la tâche — et non une paraphrase bavarde. Le modèle sait résumer ; on lui dit
     * seulement ce qui doit survivre et <b>sous quelle forme</b>.
     *
     * <p>Consigne d'un appel <b>dédié</b> (hors boucle principale) : la faire évoluer n'invalide pas
     * le préfixe caché du tour (F-134). Elle ne dépend d'aucune entrée — tout ce qui varie d'une
     * compaction à l'autre est dit dans le message ({@link #renderForSummary}).</p>
     */
    static final String SUMMARY_SYSTEM_PROMPT =
            "Tu résumes la partie ancienne d'une conversation entre un utilisateur et un agent de "
                    + "développement, pour qu'elle puisse être reprise sans relire tout l'historique. "
                    + "Produis un résumé FACTUEL et COMPACT (pas de préambule, pas de conclusion), "
                    + "structuré EXACTEMENT selon ce gabarit, dans cet ordre, en reprenant les titres "
                    + "tels quels :\n"
                    + "## Objectif\n"
                    + "Ce que l'utilisateur cherche à obtenir, et les contraintes qu'il a posées.\n"
                    + "## Fichiers\n"
                    + "Les fichiers lus, créés ou modifiés, avec pour chacun ce qui y a été fait.\n"
                    + "## Décisions et faits établis\n"
                    + "Les choix arrêtés et leur motif ; les commandes importantes ET LEUR ISSUE "
                    + "(réussie/échouée, code de sortie, valeurs de sortie notables) ; les pistes "
                    + "écartées et pourquoi.\n"
                    + "## État courant\n"
                    + "Où en est la tâche à l'instant du résumé : ce qui marche, ce qui est cassé, ce "
                    + "qui est en cours.\n"
                    + "## Prochaines étapes\n"
                    + "Ce qui reste à faire, dans l'ordre, y compris ce qui était annoncé mais pas "
                    + "encore exécuté.\n"
                    + "Garde TOUJOURS les cinq sections : si l'une est vide, écris « — » dessous "
                    + "plutôt que de la supprimer. "
                    + "Des lignes « outils utilisés » (préfixées « · ») accompagnent chaque "
                    + "tour de l'agent : appuie-toi dessus pour les faits établis. "
                    + "ANCRE chaque point : les tours ci-dessous sont préfixés de leur numéro d'origine "
                    + "« [tour N] » ; pour chaque décision, fait, fichier ou consigne que tu retiens, "
                    + "indique entre parenthèses son ou ses numéros de tour d'origine — « (tour N) » — "
                    + "et conserve les TERMES DISTINCTIFS (noms, identifiants, chemins, valeurs) qui "
                    + "permettront de le retrouver plus tard. Ces ancres restent BORNÉES : c'est un "
                    + "résumé, pas une copie — n'ancre que ce que tu retiens, ne recopie pas les tours. "
                    + "N'INVENTE RIEN — ne cite aucune valeur, aucun code de sortie, aucun contenu de "
                    + "fichier qui ne figure pas dans ce qui t'est donné ; si une information manque, "
                    + "ne la mentionne pas.";

    /**
     * Consigne de <b>fusion incrémentale</b> (F-121 / SF-121-09), posée dans le message et non dans
     * la consigne système : elle ne vaut que pour les compactions qui ont un résumé précédent, donc
     * elle dépend de l'entrée — et rien de volatil n'a sa place dans un préfixe stable.
     */
    static final String MERGE_INSTRUCTION =
            "Le résumé précédent suit déjà ce gabarit : FUSIONNE-le section par section avec la "
                    + "conversation ci-dessous, et rends un seul résumé au même gabarit. N'imbrique "
                    + "pas un résumé dans un résumé, ne crée pas de section « résumé précédent ».";

    /**
     * Libellé du bloc de résumé injecté au rejeu (visible du modèle, marqueur du cadrage §2).
     *
     * <p><b>Pont vers {@code recall}</b> (F-162 / SF-162-02) : le libellé rappelle au modèle que
     * chaque point du résumé renvoie à son tour d'origine et que, pour un détail non listé ici, l'outil
     * {@code recall} le retrouve dans l'historique complet. Une <b>constante</b> : préfixe stable, rien
     * de volatil (le cache de prompt n'est pas cassé). La consigne système principale ne mentionne pas
     * {@code recall} (SF-162-01 n'a ajouté qu'une description d'outil) — pas de duplication.</p>
     */
    static final String SUMMARY_MARKER =
            "[Résumé de la conversation précédente — conversation résumée jusqu'ici. Chaque point "
                    + "renvoie à son tour d'origine ; pour un détail non listé ici, utilise l'outil "
                    + "recall pour le retrouver dans l'historique complet.]";

    private final AtelierMessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AiAgentProvider agentProvider;
    private final AtelierCompactionProperties properties;
    private final String model;
    /**
     * Fenêtre de rejeu des trajectoires d'outils (F-119 / SF-119-03) : les traces des
     * {@code replayedTraceTurns} derniers tours repartent AUSSI au fournisseur — l'estimateur de
     * seuil doit donc les compter (parité), sinon le déclenchement se fait après le débordement réel.
     */
    private final int replayedTraceTurns;

    public AtelierCompactionService(AtelierMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository, AiAgentProvider agentProvider,
            AtelierCompactionProperties properties, AtelierProperties atelierProperties) {
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.agentProvider = agentProvider;
        this.properties = properties;
        this.model = atelierProperties.model();
        this.replayedTraceTurns = atelierProperties.replayedTraceTurns();
    }

    /**
     * Consommation d'un appel de compaction (F-117 / SF-117-01), à agréger aux compteurs du tour :
     * la compaction passe ainsi par le décompte d'usage existant, sans chemin de quota séparé ni
     * double comptage. {@link #NONE} signale qu'aucune compaction n'a eu lieu.
     */
    public record CompactionOutcome(boolean compacted, int inputTokens, int outputTokens,
            int cacheReadTokens, int cacheWriteTokens) {

        static final CompactionOutcome NONE = new CompactionOutcome(false, 0, 0, 0, 0);
    }

    /**
     * Message de résumé à injecter <b>en tête</b> du rejeu, ou {@code null} si le fil n'en porte pas.
     * Un message {@code user} de contexte : deux messages {@code user} consécutifs sont acceptés par
     * le fournisseur (vérifié dans {@code replayableHistory}), et le résumé, posé après la consigne
     * système cachée, forme un préfixe stable — le cache de prompt n'est pas cassé.
     */
    public static AgentMessage summaryPrefix(Workspace workspace) {
        String summary = workspace.getChatThreadSummary();
        if (summary == null || summary.isBlank()) {
            return null;
        }
        return AgentMessage.userText(SUMMARY_MARKER + "\n" + summary.strip());
    }

    /**
     * Compacte le fil <b>si</b> le texte rejoué dépasse le seuil configuré ; sinon ne fait rien.
     *
     * <p>Best-effort : un appel de résumé en échec n'écrit rien et laisse le tour partir avec
     * l'historique complet — la compaction est une optimisation de contexte, pas une étape obligatoire
     * du tour.</p>
     *
     * @param userId    propriétaire (isolation) — le workspace a déjà été vérifié possédé en amont
     * @param workspace le workspace du fil, mis à jour en place (résumé + frontière) si compacté
     * @param apiKey    clé fournisseur (BYOK) ou {@code null} ⇒ clé plateforme
     * @return la consommation de l'appel de compaction, ou {@link CompactionOutcome#NONE}
     */
    public CompactionOutcome compactIfOversized(UUID userId, Workspace workspace, String apiKey) {
        return compactIfOversized(userId, workspace, apiKey, AtelierProgressListener.NOOP);
    }

    /**
     * Comme {@link #compactIfOversized(UUID, Workspace, String)}, mais relaie à {@code listener} les
     * transitions de compaction (F-162 / SF-162-03 : « démarrée » puis « terminée · N tours ») pour
     * les rendre visibles dans le terminal. La visibilité est <b>de l'affichage</b> : elle ne change
     * rien à ce qui repart au modèle, et son émission est best-effort (elle ne fait jamais échouer la
     * compaction). La surcharge historique reste valide (elle passe {@link AtelierProgressListener#NOOP}).
     */
    public CompactionOutcome compactIfOversized(UUID userId, Workspace workspace, String apiKey,
            AtelierProgressListener listener) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> replayable = replayable(userId, workspace);
        long estimated =
                estimateReplayTokens(workspace.getChatThreadSummary(), replayable, replayedTraceTurns);
        if (estimated <= properties.triggerTokens()) {
            return CompactionOutcome.NONE;
        }
        return doCompact(userId, workspace, apiKey, replayable, estimated, listener);
    }

    /**
     * Compaction <b>forcée</b>, sans la garde de seuil (F-117 / SF-117-02). Filet réactif du
     * dépassement de fenêtre : quand le fournisseur a réellement refusé le contexte
     * ({@code AgentPromptTooLongException}), l'estimation heuristique a sous-compté — on compacte quand
     * même. Best-effort comme {@link #compactIfOversized} : si même l'appel de résumé déborde, on
     * renvoie {@link CompactionOutcome#NONE} et l'appelant rend un message clair.
     */
    public CompactionOutcome compactNow(UUID userId, Workspace workspace, String apiKey) {
        return compactNow(userId, workspace, apiKey, AtelierProgressListener.NOOP);
    }

    /**
     * Comme {@link #compactNow(UUID, Workspace, String)}, mais relaie à {@code listener} les transitions
     * de compaction (F-162 / SF-162-03). Best-effort comme la surcharge historique.
     */
    public CompactionOutcome compactNow(UUID userId, Workspace workspace, String apiKey,
            AtelierProgressListener listener) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> replayable = replayable(userId, workspace);
        return doCompact(userId, workspace, apiKey, replayable,
                estimateReplayTokens(workspace.getChatThreadSummary(), replayable, replayedTraceTurns),
                listener);
    }

    private CompactionOutcome doCompact(UUID userId, Workspace workspace, String apiKey,
            List<AtelierMessage> replayable, long estimated, AtelierProgressListener listener) {
        // On garde les derniers tours entiers ; tout ce qui précède (résumé existant compris) est
        // résumé. S'il n'y a rien d'ancien à résumer, la compaction ne peut rien réduire.
        int splitIndex = Math.max(0, replayable.size() - properties.keepRecentTurns());
        if (splitIndex == 0) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> old = replayable.subList(0, splitIndex);
        OffsetDateTime newBoundary = replayable.get(splitIndex).getCreatedAt();
        // Offset de base du numéro de tour (F-162 / SF-162-02) : nombre de tours USER antérieurs à la
        // fenêtre rejouée. La compaction est incrémentale — la frontière avance —, donc le premier tour
        // de `old` n'est pas le tour 1 : on numérote à la suite pour que « tour N » désigne le même tour
        // que `recall`. Filtré workspace_id + user_id (isolation).
        long baseTurns = messageRepository.countByWorkspaceIdAndUserIdAndRoleAndCreatedAtLessThan(
                workspace.getId(), userId, "USER", replayable.get(0).getCreatedAt());
        // Nombre de TOURS résumés (F-162 / SF-162-03) : les messages USER de la tranche résumée — même
        // notion de « tour » que recall et SF-162-02. C'est ce N que l'écran affiche.
        int summarizedTurns = countUserTurns(old);

        // Visibilité (F-162 / SF-162-03) : on ne signale le DÉMARRAGE qu'ici, une fois certain qu'une
        // compaction a bien lieu (la garde `splitIndex == 0` ci-dessus a déjà écarté « rien à réduire »).
        // Best-effort : l'affichage ne fait jamais échouer la compaction (F-117).
        safeStarted(listener);
        try {
            AgentTurn turn = summarize(workspace.getChatThreadSummary(), old, baseTurns, apiKey);
            String summary = turn.text();
            if (summary == null || summary.isBlank()) {
                log.warn("Compaction sans résumé exploitable (workspace={}, poste={}) : fil inchangé.",
                        workspace.getId(), workspace.getHostId());
                // Rien n'a été écrit : la barre disparaît, sans marqueur (N = 0).
                safeDone(listener, 0);
                return CompactionOutcome.NONE;
            }
            if (log.isDebugEnabled() && !looksSectioned(summary)) {
                // Observation, jamais un refus (F-121 / SF-121-09, D3) : ne pas compacter un fil qui
                // déborde serait pire qu'un résumé mal formé, et reformater coûterait un second appel.
                log.debug("Résumé de compaction hors gabarit (workspace={}, poste={}) : conservé tel "
                        + "quel.", workspace.getId(), workspace.getHostId());
            }
            workspace.setChatThreadSummary(summary.strip());
            workspace.setChatThreadStartedAt(newBoundary);
            workspaceRepository.save(workspace);
            log.info("Fil d'Atelier compacté (workspace={}, poste={}) : ~{} tokens rejoués, "
                    + "{} tour(s) résumé(s), {} gardé(s).", workspace.getId(), workspace.getHostId(),
                    estimated, summarizedTurns, replayable.size() - splitIndex);
            // Terminée : l'écran retire la barre et pose le marqueur « Conversation compactée · N tours ».
            safeDone(listener, summarizedTurns);
            return new CompactionOutcome(true, turn.inputTokens(), turn.outputTokens(),
                    turn.cacheReadTokens(), turn.cacheWriteTokens());
        } catch (RuntimeException ex) {
            // Best-effort : l'appel de résumé a échoué (fournisseur indisponible, etc.). On n'écrit
            // rien — le tour partira avec l'historique complet, et le filet réactif (SF-117-02)
            // prendra le relais s'il déborde.
            log.warn("Compaction du fil ignorée (workspace={}, poste={}) : {}",
                    workspace.getId(), workspace.getHostId(), ex.getClass().getSimpleName());
            // La barre disparaît sans marqueur (N = 0) : rien n'a été compacté.
            safeDone(listener, 0);
            return CompactionOutcome.NONE;
        }
    }

    /** Nombre de messages {@code USER} (= de tours) dans une tranche de messages. */
    private static int countUserTurns(List<AtelierMessage> messages) {
        int turns = 0;
        for (AtelierMessage message : messages) {
            if (!"ASSISTANT".equalsIgnoreCase(message.getRole())) {
                turns++;
            }
        }
        return turns;
    }

    /**
     * Relaie « compaction démarrée » sans jamais lever (F-162 / SF-162-03) : l'affichage est
     * best-effort, un listener qui échoue ne doit pas casser la compaction (F-117).
     */
    private static void safeStarted(AtelierProgressListener listener) {
        try {
            listener.onCompactionStarted();
        } catch (RuntimeException ex) {
            log.debug("Signal de compaction (démarrée) ignoré : {}", ex.getClass().getSimpleName());
        }
    }

    /** Relaie « compaction terminée · N tours » sans jamais lever (même best-effort que {@link #safeStarted}). */
    private static void safeDone(AtelierProgressListener listener, int summarizedTurns) {
        try {
            listener.onCompactionDone(summarizedTurns);
        } catch (RuntimeException ex) {
            log.debug("Signal de compaction (terminée) ignoré : {}", ex.getClass().getSimpleName());
        }
    }

    /**
     * Le résumé porte-t-il le gabarit attendu (F-121 / SF-121-09) ? <b>Observation seule</b> : la
     * conformité n'est pas exigée, la compaction reste best-effort (F-117). Vraie dès que les cinq
     * titres sont présents, dans l'ordre — l'ordre importe, c'est ce qui rend la lecture prévisible.
     */
    static boolean looksSectioned(String summary) {
        if (summary == null || summary.isBlank()) {
            return false;
        }
        int cursor = 0;
        for (String section : SUMMARY_SECTIONS) {
            int found = summary.indexOf(section, cursor);
            if (found < 0) {
                return false;
            }
            cursor = found + section.length();
        }
        return true;
    }

    /** Un appel modèle dédié, sans outils, borné : il ne fait que produire le texte du résumé. */
    private AgentTurn summarize(String previousSummary, List<AtelierMessage> old, long baseTurns,
            String apiKey) {
        AgentMessage input = AgentMessage.userText(renderForSummary(previousSummary, old, baseTurns));
        AgentTurnRequest request = new AgentTurnRequest(model, SUMMARY_SYSTEM_PROMPT,
                List.of(input), List.of(), apiKey);
        return agentProvider.nextTurn(request);
    }

    /**
     * Rend les tours anciens en un texte à résumer : le résumé précédent d'abord (la compaction est
     * incrémentale — on résume le résumé plus les tours accumulés depuis), puis chaque message ancien
     * préfixé de son rôle.
     *
     * <p>Quand un résumé précédent existe, la consigne de <b>fusion</b> (F-121 / SF-121-09) est posée
     * juste avant lui : sans elle, le modèle a tendance à recopier l'ancien résumé en bloc puis à
     * ajouter les tours récents à la suite, ce qui empile deux structures au lieu d'en tenir une.</p>
     *
     * <p>Forme historique <b>sans numéro de tour</b> (surcharge à deux arguments) : conservée pour les
     * appelants qui n'ont pas d'offset à donner. La compaction, elle, passe par la surcharge à trois
     * arguments qui <b>ancre</b> chaque tour (F-162 / SF-162-02).</p>
     */
    static String renderForSummary(String previousSummary, List<AtelierMessage> old) {
        return renderForSummary(previousSummary, old, 0L);
    }

    /**
     * Rend les tours anciens en préfixant chacun de son <b>numéro de tour d'origine</b> « [tour N] »
     * (F-162 / SF-162-02), pour que le résumé produit puisse <b>ancrer</b> chaque décision au tour où
     * elle a été prise et que {@code recall} vise juste.
     *
     * <p><b>Numérotation cohérente avec {@code recall}</b> : « tour N » = nombre de messages
     * {@code USER} du fil dont {@code createdAt <= createdAt} du message. On part de {@code baseTurns}
     * (les tours {@code USER} déjà résumés, antérieurs à la fenêtre — la compaction est incrémentale)
     * et on incrémente sur chaque message {@code USER}. Un message {@code ASSISTANT} porte le numéro du
     * dernier tour {@code USER} vu, exactement comme {@code recall} l'étiquette (le tour utilisateur
     * auquel il répond).</p>
     */
    static String renderForSummary(String previousSummary, List<AtelierMessage> old, long baseTurns) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append(MERGE_INSTRUCTION).append("\n\n");
            sb.append("Résumé précédent :\n").append(previousSummary.strip()).append("\n\n");
        }
        sb.append("Conversation à résumer :\n");
        long turn = baseTurns;
        for (AtelierMessage message : old) {
            String content = message.getContent();
            boolean assistant = "ASSISTANT".equalsIgnoreCase(message.getRole());
            if (!assistant) {
                turn++; // un tour = un message utilisateur (même notion que recall)
            }
            String digest = assistant ? toolDigest(message.getToolTrace()) : "";
            // Un message assistant sans texte mais avec une trajectoire d'outils a quand même quelque
            // chose à résumer (F-119 / SF-119-03) : ne pas l'écarter sur le seul contenu blanc.
            if ((content == null || content.isBlank()) && digest.isEmpty()) {
                continue;
            }
            sb.append("[tour ").append(turn).append("] ")
                    .append(assistant ? "ASSISTANT : " : "UTILISATEUR : ")
                    .append(content == null ? "" : content.strip()).append('\n');
            if (!digest.isEmpty()) {
                sb.append(digest);
            }
        }
        return sb.toString();
    }

    /** Longueur max d'un digest d'outils par tour (F-119 / SF-119-03) : structuré, pas exhaustif. */
    static final int MAX_DIGEST_CHARS_PER_TURN = 1_500;
    /** Longueur max d'un extrait de sortie cité dans le digest — une valeur, pas un fichier entier. */
    static final int MAX_DIGEST_OUTCOME_CHARS = 160;

    /**
     * Digest <b>structuré</b> des résultats d'outils d'un tour (F-119 / SF-119-03, cadrage Cause 3) :
     * une ligne par appel — l'outil, sa cible (fichier/commande/requête) et son <b>issue</b> (réussite,
     * code de sortie, extrait de sortie). La compaction résumait le texte seul et jetait par conception
     * les sorties d'outils, si bien que l'agent raisonnait ensuite sur un digest sans preuves et
     * réinventait des valeurs. Borné par tour ({@link #MAX_DIGEST_CHARS_PER_TURN}) — c'est un
     * aide-mémoire, pas la trace complète. Une trajectoire illisible rend une chaîne vide.
     */
    static String toolDigest(String toolTraceJson) {
        AtelierToolTrace trace = AtelierToolTrace.fromJson(toolTraceJson);
        if (trace.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (AtelierToolTrace.Step step : trace.steps()) {
            if (step == null || step.calls() == null) {
                continue;
            }
            for (AtelierToolTrace.Call call : step.calls()) {
                if (call == null || call.name() == null || call.name().isBlank()) {
                    continue;
                }
                if (sb.length() >= MAX_DIGEST_CHARS_PER_TURN) {
                    sb.append("  · … (autres appels d'outils non listés)\n");
                    return sb.toString();
                }
                sb.append("  · ").append(call.name());
                String arg = digestArg(call);
                if (!arg.isEmpty()) {
                    sb.append(' ').append(arg);
                }
                sb.append(" → ").append(digestOutcome(call)).append('\n');
            }
        }
        return sb.toString();
    }

    /** Cible lisible d'un appel pour le digest : le chemin, la commande ou la requête. */
    private static String digestArg(AtelierToolTrace.Call call) {
        com.fasterxml.jackson.databind.JsonNode input = call.input();
        if (input == null) {
            return "";
        }
        String path = input.path("path").asText("");
        if (!path.isBlank()) {
            return path;
        }
        String command = input.path("command").asText("");
        if (!command.isBlank()) {
            return "« " + oneLine(command, 120) + " »";
        }
        String query = input.path("query").asText("");
        if (!query.isBlank()) {
            return "« " + oneLine(query, 120) + " »";
        }
        String question = input.path("question").asText("");
        if (!question.isBlank()) {
            return "« " + oneLine(question, 120) + " »";
        }
        return "";
    }

    /** Marqueur de code de sortie apposé par {@code bashOutcome} : {@code [code de sortie: N]}. */
    private static final java.util.regex.Pattern EXIT_CODE_MARKER =
            java.util.regex.Pattern.compile("\\[code de sortie: [^\\]]+\\]");

    /**
     * Issue lisible d'un appel : pour une commande, le <b>code de sortie</b> (l'issue qui compte) ;
     * sinon un extrait borné de la sortie ; « échec » si l'appel est en erreur.
     */
    private static String digestOutcome(AtelierToolTrace.Call call) {
        String exit = exitMarker(call.result());
        if (!exit.isEmpty()) {
            return call.error() ? "échec " + exit : exit;
        }
        String snippet = oneLine(call.result(), MAX_DIGEST_OUTCOME_CHARS);
        if (call.error()) {
            return snippet.isEmpty() ? "échec" : "échec : " + snippet;
        }
        return snippet.isEmpty() ? "ok" : snippet;
    }

    /** Le dernier {@code [code de sortie: N]} d'un résultat (celui apposé en fin), ou {@code ""}. */
    private static String exitMarker(String result) {
        if (result == null || result.isEmpty()) {
            return "";
        }
        java.util.regex.Matcher matcher = EXIT_CODE_MARKER.matcher(result);
        String marker = "";
        while (matcher.find()) {
            marker = matcher.group();
        }
        return marker;
    }

    /** Première ligne non vide d'un texte, bornée — pour ne citer qu'une valeur, jamais un fichier. */
    private static String oneLine(String text, int max) {
        if (text == null) {
            return "";
        }
        String line = text.strip();
        int newline = line.indexOf('\n');
        if (newline >= 0) {
            line = line.substring(0, newline).strip();
        }
        return line.length() > max ? line.substring(0, max) + "…" : line;
    }

    /**
     * Estimation du texte rejoué seul (forme historique, sans les trajectoires d'outils). Conservée
     * pour les appelants qui ne connaissent pas la fenêtre de rejeu.
     */
    static long estimateReplayTokens(String summary, List<AtelierMessage> messages) {
        return estimateReplayTokens(summary, messages, 0);
    }

    /**
     * Estimation du <b>contexte rejoué</b> en tokens : résumé courant + contenu des messages depuis la
     * frontière, <b>plus</b> les trajectoires d'outils des {@code traceTurns} derniers tours assistant,
     * par l'heuristique {@link #CHARS_PER_TOKEN}.
     *
     * <p><b>Parité avec le rejeu réel</b> (F-119 / SF-119-03) : la boucle rejoue les résultats d'outils
     * des {@code traceTurns} derniers tours ({@code firstTracedIndex}). Avant que la fenêtre ne soit
     * élargie de 5 à 12, ne pas les compter sous-estimait modérément ; élargie, l'écart devient de
     * plusieurs dizaines de milliers de tokens — le seuil de compaction serait franchi <b>sans se
     * déclencher</b>, et seul le filet réactif « prompt too long » (400) rattraperait après coup. On
     * compte donc les caractères des traces (déjà bornées à la sérialisation) des derniers tours.</p>
     *
     * <p><b>La même fenêtre que la boucle, pas une fenêtre qui lui ressemble</b> (F-121 / SF-121-00).
     * Depuis F-134 / SF-134-01, la coupure du rejeu est calculée <b>depuis le début</b> et ne bouge que
     * <b>par paliers</b> (pour ne pas muter le préfixe mis en cache) : le nombre de tours rejoués avec
     * leur trajectoire vaut <b>entre {@code traceTurns} et {@code 2 × traceTurns − 1}</b>. Compter
     * « les {@code traceTurns} derniers » laissait donc jusqu'à {@code traceTurns − 1} tours de traces
     * partir au fournisseur <b>sans être comptés</b> — à 40 000 caractères la trajectoire, plus que le
     * seuil lui-même. L'estimateur appelle donc {@link AtelierChatService#firstTracedIndex} : une seule
     * règle, celle du rejeu, jamais une copie qui dérive.</p>
     */
    static long estimateReplayTokens(String summary, List<AtelierMessage> messages, int traceTurns) {
        long chars = summary == null ? 0L : summary.length();
        for (AtelierMessage message : messages) {
            String content = message.getContent();
            if (content != null) {
                chars += content.length();
            }
        }
        // Les trajectoires d'outils repartent AUSSI : exactement celles que la boucle rejoue, donc
        // exactement les tours qu'elle trace — la coupure est demandée à la boucle elle-même, pas
        // recalculée ici (SF-121-00). `traceTurns <= 0` garde la forme historique « texte seul » de la
        // surcharge à deux arguments ; en production la propriété se replie toujours sur son défaut.
        if (traceTurns > 0) {
            int tracedFrom = AtelierChatService.firstTracedIndex(messages, traceTurns);
            for (int index = tracedFrom; index < messages.size(); index++) {
                AtelierMessage message = messages.get(index);
                if (!"ASSISTANT".equalsIgnoreCase(message.getRole())) {
                    continue;
                }
                String trace = message.getToolTrace();
                if (trace != null) {
                    chars += trace.length();
                }
            }
        }
        return (long) (chars / CHARS_PER_TOKEN);
    }

    /** Messages que le prochain tour rejouera : tout le fil, ou ce qui suit la frontière. */
    private List<AtelierMessage> replayable(UUID userId, Workspace workspace) {
        OffsetDateTime since = workspace.getChatThreadStartedAt();
        return since == null
                ? messageRepository.findByWorkspaceIdAndUserIdOrderByCreatedAtAsc(workspace.getId(), userId)
                : messageRepository.findByWorkspaceIdAndUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAsc(
                        workspace.getId(), userId, since);
    }
}
