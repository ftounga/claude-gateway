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
     * Diviseur caractères → tokens de l'estimation (F-117 / SF-117-01). Volontairement une
     * <b>heuristique</b> et non un décompte exact : ce dernier exigerait un appel réseau à chaque
     * tour, précisément ce qu'on cherche à borner. Quatre caractères par token est le rapport usuel,
     * et le seuil garde une marge large sous la fenêtre réelle — l'approximation ne peut pas conduire
     * à un débordement, le filet réactif (SF-117-02) couvrant le résiduel.
     */
    static final int CHARS_PER_TOKEN = 4;

    /**
     * Consigne du résumé (F-117 / SF-117-01) : un compte rendu factuel et compact, orienté <b>reprise
     * du travail</b> — ce qui a été décidé, les fichiers touchés, l'état courant de la tâche — et non
     * une paraphrase bavarde. Le modèle sait résumer ; on lui dit seulement ce qui doit survivre.
     */
    static final String SUMMARY_SYSTEM_PROMPT =
            "Tu résumes la partie ancienne d'une conversation entre un utilisateur et un agent de "
                    + "développement, pour qu'elle puisse être reprise sans relire tout l'historique. "
                    + "Produis un résumé FACTUEL et COMPACT (pas de préambule, pas de conclusion) qui "
                    + "conserve : l'objectif poursuivi, les décisions prises, les fichiers lus ou "
                    + "modifiés, les commandes importantes ET LEUR ISSUE (réussie/échouée, code de "
                    + "sortie, valeurs de sortie notables), et l'état courant de la tâche (ce qui reste "
                    + "à faire). Des lignes « outils utilisés » (préfixées « · ») accompagnent chaque "
                    + "tour de l'agent : appuie-toi dessus pour les faits établis. "
                    + "N'INVENTE RIEN — ne cite aucune valeur, aucun code de sortie, aucun contenu de "
                    + "fichier qui ne figure pas dans ce qui t'est donné ; si une information manque, "
                    + "ne la mentionne pas.";

    /** Libellé du bloc de résumé injecté au rejeu (visible du modèle, marqueur du cadrage §2). */
    static final String SUMMARY_MARKER =
            "[Résumé de la conversation précédente — conversation résumée jusqu'ici]";

    private final AtelierMessageRepository messageRepository;
    private final WorkspaceRepository workspaceRepository;
    private final AiAgentProvider agentProvider;
    private final AtelierCompactionProperties properties;
    private final String model;

    public AtelierCompactionService(AtelierMessageRepository messageRepository,
            WorkspaceRepository workspaceRepository, AiAgentProvider agentProvider,
            AtelierCompactionProperties properties, AtelierProperties atelierProperties) {
        this.messageRepository = messageRepository;
        this.workspaceRepository = workspaceRepository;
        this.agentProvider = agentProvider;
        this.properties = properties;
        this.model = atelierProperties.model();
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
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> replayable = replayable(userId, workspace);
        long estimated = estimateReplayTokens(workspace.getChatThreadSummary(), replayable);
        if (estimated <= properties.triggerTokens()) {
            return CompactionOutcome.NONE;
        }
        return doCompact(workspace, apiKey, replayable, estimated);
    }

    /**
     * Compaction <b>forcée</b>, sans la garde de seuil (F-117 / SF-117-02). Filet réactif du
     * dépassement de fenêtre : quand le fournisseur a réellement refusé le contexte
     * ({@code AgentPromptTooLongException}), l'estimation heuristique a sous-compté — on compacte quand
     * même. Best-effort comme {@link #compactIfOversized} : si même l'appel de résumé déborde, on
     * renvoie {@link CompactionOutcome#NONE} et l'appelant rend un message clair.
     */
    public CompactionOutcome compactNow(UUID userId, Workspace workspace, String apiKey) {
        if (!Boolean.TRUE.equals(properties.enabled())) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> replayable = replayable(userId, workspace);
        return doCompact(workspace, apiKey, replayable,
                estimateReplayTokens(workspace.getChatThreadSummary(), replayable));
    }

    private CompactionOutcome doCompact(Workspace workspace, String apiKey,
            List<AtelierMessage> replayable, long estimated) {
        // On garde les derniers tours entiers ; tout ce qui précède (résumé existant compris) est
        // résumé. S'il n'y a rien d'ancien à résumer, la compaction ne peut rien réduire.
        int splitIndex = Math.max(0, replayable.size() - properties.keepRecentTurns());
        if (splitIndex == 0) {
            return CompactionOutcome.NONE;
        }
        List<AtelierMessage> old = replayable.subList(0, splitIndex);
        OffsetDateTime newBoundary = replayable.get(splitIndex).getCreatedAt();

        try {
            AgentTurn turn = summarize(workspace.getChatThreadSummary(), old, apiKey);
            String summary = turn.text();
            if (summary == null || summary.isBlank()) {
                log.warn("Compaction sans résumé exploitable (workspace={}, poste={}) : fil inchangé.",
                        workspace.getId(), workspace.getHostId());
                return CompactionOutcome.NONE;
            }
            workspace.setChatThreadSummary(summary.strip());
            workspace.setChatThreadStartedAt(newBoundary);
            workspaceRepository.save(workspace);
            log.info("Fil d'Atelier compacté (workspace={}, poste={}) : ~{} tokens rejoués, "
                    + "{} tour(s) résumé(s), {} gardé(s).", workspace.getId(), workspace.getHostId(),
                    estimated, old.size(), replayable.size() - splitIndex);
            return new CompactionOutcome(true, turn.inputTokens(), turn.outputTokens(),
                    turn.cacheReadTokens(), turn.cacheWriteTokens());
        } catch (RuntimeException ex) {
            // Best-effort : l'appel de résumé a échoué (fournisseur indisponible, etc.). On n'écrit
            // rien — le tour partira avec l'historique complet, et le filet réactif (SF-117-02)
            // prendra le relais s'il déborde.
            log.warn("Compaction du fil ignorée (workspace={}, poste={}) : {}",
                    workspace.getId(), workspace.getHostId(), ex.getClass().getSimpleName());
            return CompactionOutcome.NONE;
        }
    }

    /** Un appel modèle dédié, sans outils, borné : il ne fait que produire le texte du résumé. */
    private AgentTurn summarize(String previousSummary, List<AtelierMessage> old, String apiKey) {
        AgentMessage input = AgentMessage.userText(renderForSummary(previousSummary, old));
        AgentTurnRequest request = new AgentTurnRequest(model, SUMMARY_SYSTEM_PROMPT,
                List.of(input), List.of(), apiKey);
        return agentProvider.nextTurn(request);
    }

    /**
     * Rend les tours anciens en un texte à résumer : le résumé précédent d'abord (la compaction est
     * incrémentale — on résume le résumé plus les tours accumulés depuis), puis chaque message ancien
     * préfixé de son rôle.
     */
    static String renderForSummary(String previousSummary, List<AtelierMessage> old) {
        StringBuilder sb = new StringBuilder();
        if (previousSummary != null && !previousSummary.isBlank()) {
            sb.append("Résumé précédent :\n").append(previousSummary.strip()).append("\n\n");
        }
        sb.append("Conversation à résumer :\n");
        for (AtelierMessage message : old) {
            String content = message.getContent();
            boolean assistant = "ASSISTANT".equalsIgnoreCase(message.getRole());
            String digest = assistant ? toolDigest(message.getToolTrace()) : "";
            // Un message assistant sans texte mais avec une trajectoire d'outils a quand même quelque
            // chose à résumer (F-119 / SF-119-03) : ne pas l'écarter sur le seul contenu blanc.
            if ((content == null || content.isBlank()) && digest.isEmpty()) {
                continue;
            }
            sb.append(assistant ? "ASSISTANT : " : "UTILISATEUR : ")
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
     * Estimation du <b>texte rejoué</b> en tokens : résumé courant + contenu des messages depuis la
     * frontière, par l'heuristique {@link #CHARS_PER_TOKEN}. Les trajectoires d'outils ne sont pas
     * comptées ici — elles sont déjà bornées (5 derniers tours, SF-39-03) et c'est le texte qui croît
     * sans borne (constat de l'audit).
     */
    static long estimateReplayTokens(String summary, List<AtelierMessage> messages) {
        long chars = summary == null ? 0L : summary.length();
        for (AtelierMessage message : messages) {
            String content = message.getContent();
            if (content != null) {
                chars += content.length();
            }
        }
        return chars / CHARS_PER_TOKEN;
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
