package fr.claudegateway.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Assainit une séquence de messages d'agent pour qu'elle soit <b>structurellement valide</b> avant
 * tout envoi au fournisseur (F-117 / SF-117-08). <b>Neutre</b> vis-à-vis du fournisseur : c'est une
 * contrainte de forme partagée par les API de conversation, pas une option Anthropic — le domaine ne
 * dépend donc de personne (Provider Independence).
 *
 * <p><b>Pourquoi.</b> Le rejeu d'un fil ({@code AtelierChatService}) produisait un message par ligne
 * de la base, en conservant l'ordre et le rôle. Le code s'appuyait sur l'idée que « deux messages
 * {@code user} consécutifs sont acceptés » — <b>faux</b> : sur {@code claude-opus-5}, deux messages de
 * même rôle consécutifs déclenchent un {@code 400 invalid_request}. Or un tour échoué persistait un
 * {@code USER} sans réponse assistant : dès qu'il en restait deux d'affilée, <b>tout</b> rejeu suivant
 * envoyait des {@code user} consécutifs, ce qui garantissait le {@code 400} et empilait la panne (fil
 * « agenor » en production, incident du 2026-09).</p>
 *
 * <p><b>Ce que garantit {@link #sanitize(List)}</b>, de façon <b>déterministe</b> et <b>sans perte de
 * contenu</b> (correctif de <b>structure</b>, jamais de contenu) :</p>
 * <ul>
 *   <li>la séquence <b>commence par un message {@code user}</b> (les messages de tête d'un autre rôle
 *       sont écartés) ;</li>
 *   <li><b>alternance stricte</b> {@code user}/{@code assistant} : deux messages de même rôle
 *       consécutifs sont <b>fusionnés</b> (concaténation fidèle de leurs blocs), jamais émis en
 *       double ;</li>
 *   <li><b>aucun bloc de contenu vide</b> : un bloc {@link AgentContentBlock.Text} blanc est retiré,
 *       et un message vidé de tout bloc est écarté ;</li>
 *   <li>le couplage {@code tool_use} ↔ {@code tool_result} est <b>préservé</b> : la fusion ne concerne
 *       que des messages de <b>même</b> rôle, donc ne sépare jamais un {@code tool_use} (assistant) de
 *       son {@code tool_result} (user), et ne retire ni ne réordonne aucun bloc ;</li>
 *   <li>les <b>consignes d'effort</b> (message {@code system} à contenu vide, F-134 / SF-134-05) sont
 *       <b>préservées telles quelles</b> — le fournisseur les exempte des règles de placement — et ne
 *       sont <b>jamais fusionnées</b> ; une consigne d'effort <b>en tête</b>, qui n'a aucun tour à
 *       cadencer, est écartée.</li>
 * </ul>
 *
 * <p>L'assainissement étant déterministe et n'altérant pas une séquence déjà valide, le <b>préfixe
 * stable</b> reste stable : le cache de prompt n'est pas cassé plus que nécessaire.</p>
 */
public final class AgentMessageSanitizer {

    private AgentMessageSanitizer() {
    }

    /**
     * Renvoie une séquence structurellement valide équivalente à {@code messages}, sans perte de
     * contenu. Idempotente : {@code sanitize(sanitize(x)).equals(sanitize(x))}.
     */
    public static List<AgentMessage> sanitize(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<AgentMessage> result = new ArrayList<>(messages.size());
        boolean started = false; // vrai dès qu'un message user/assistant a été retenu
        for (AgentMessage message : messages) {
            if (message.isEffortDirective()) {
                // Consigne d'effort (contenu vide) : préservée telle quelle, jamais fusionnée. En
                // tête (avant tout user), elle n'a aucun tour à cadencer et le fournisseur refuse un
                // premier message qui n'est pas `user` : on l'écarte.
                if (started) {
                    result.add(message);
                }
                continue;
            }
            List<AgentContentBlock> blocks = nonEmptyBlocks(message.content());
            if (blocks.isEmpty()) {
                // Message vidé de tout bloc (ex. un unique bloc texte blanc) : le fournisseur le
                // refuserait. Écarté sans toucher au reste.
                continue;
            }
            // Une conversation doit commencer par un message `user`.
            if (!started && !"user".equals(message.role())) {
                continue;
            }
            AgentMessage previous = result.isEmpty() ? null : result.get(result.size() - 1);
            if (previous != null && !previous.isEffortDirective()
                    && previous.role().equals(message.role())) {
                // Même rôle consécutif : FUSION (concaténation fidèle) plutôt que deux messages
                // d'affilée, que le fournisseur refuse. Aucun bloc n'est retiré ni réordonné.
                List<AgentContentBlock> merged = new ArrayList<>(previous.content());
                merged.addAll(blocks);
                result.set(result.size() - 1, new AgentMessage(message.role(), merged));
            } else {
                result.add(new AgentMessage(message.role(), blocks));
            }
            started = true;
        }
        return result;
    }

    /**
     * Vrai si la séquence est structurellement valide (contrat testable). Utilisé par les tests pour
     * prouver qu'une séquence fautive est invalide <b>avant</b> assainissement et valide <b>après</b>.
     */
    public static boolean isValidSequence(List<AgentMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return false;
        }
        // Doit commencer par un message `user` (une consigne d'effort en tête est refusée).
        AgentMessage first = messages.get(0);
        if (first.isEffortDirective() || !"user".equals(first.role())) {
            return false;
        }
        for (int i = 0; i < messages.size(); i++) {
            AgentMessage message = messages.get(i);
            if (message.isEffortDirective()) {
                // Une consigne d'effort doit rester à contenu vide.
                if (!message.content().isEmpty()) {
                    return false;
                }
                continue;
            }
            if (message.content().isEmpty() || hasEmptyBlock(message.content())) {
                return false;
            }
            // Pas deux messages de même rôle (user/assistant) consécutifs.
            if (i > 0) {
                AgentMessage previous = messages.get(i - 1);
                if (!previous.isEffortDirective() && previous.role().equals(message.role())) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Blocs du message débarrassés des blocs texte blancs ; les autres blocs sont conservés. */
    private static List<AgentContentBlock> nonEmptyBlocks(List<AgentContentBlock> content) {
        List<AgentContentBlock> kept = new ArrayList<>(content.size());
        for (AgentContentBlock block : content) {
            if (!isEmptyBlock(block)) {
                kept.add(block);
            }
        }
        return kept;
    }

    private static boolean hasEmptyBlock(List<AgentContentBlock> content) {
        for (AgentContentBlock block : content) {
            if (isEmptyBlock(block)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Un bloc « vide » = un bloc texte au contenu blanc, que le fournisseur refuse ({@code text
     * content blocks must be non-empty}). Les autres blocs — {@code tool_use}, {@code tool_result},
     * raisonnement (signé, texte souvent vide), image, document — ne sont <b>jamais</b> considérés
     * vides : les retirer casserait la séquence ou le couplage.
     */
    private static boolean isEmptyBlock(AgentContentBlock block) {
        return block instanceof AgentContentBlock.Text text
                && (text.text() == null || text.text().isBlank());
    }
}
