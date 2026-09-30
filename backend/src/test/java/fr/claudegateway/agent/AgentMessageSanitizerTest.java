package fr.claudegateway.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Assainissement de la séquence de messages (F-117 / SF-117-08). Reproduit la séquence fautive de
 * l'incident « agenor » et prouve qu'AVANT correctif elle est invalide, APRÈS elle est assainie —
 * sans perte de contenu.
 */
class AgentMessageSanitizerTest {

    private static AgentMessage user(String text) {
        return AgentMessage.userText(text);
    }

    private static AgentMessage assistant(String text) {
        return AgentMessage.assistant(List.of(new AgentContentBlock.Text(text)));
    }

    /** Le cœur du bug : plusieurs USER consécutifs (tours échoués sans réponse assistant). */
    @Test
    void mergesConsecutiveUsersFromAbandonedTurns() {
        // Une histoire (user, assistant du dernier tour réussi) puis USER, USER, USER (23:30, 23:31,
        // 23:32) laissés sans réponse par des tours échoués — exactement le fil « agenor ».
        List<AgentMessage> raw = List.of(
                user("q0"), assistant("ok"), user("23:30"), user("23:31"), user("23:32"));

        // AVANT : la séquence est invalide (USER consécutifs) — c'est ce que le fournisseur refuse.
        assertThat(AgentMessageSanitizer.isValidSequence(raw)).isFalse();

        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(raw);

        // APRÈS : valide, et aucun rôle consécutif dupliqué.
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean).hasSize(3); // user, assistant, puis un seul user fusionné
        assertThat(clean.get(0).role()).isEqualTo("user");
        assertThat(clean.get(1).role()).isEqualTo("assistant");
        assertThat(clean.get(2).role()).isEqualTo("user");
        // Contenu PRÉSERVÉ : les trois messages utilisateur sont concaténés fidèlement, aucun perdu.
        List<AgentContentBlock> mergedBlocks = clean.get(2).content();
        assertThat(mergedBlocks).hasSize(3);
        assertThat(mergedBlocks).allSatisfy(b -> assertThat(b).isInstanceOf(AgentContentBlock.Text.class));
        assertThat(mergedBlocks.stream()
                .map(b -> ((AgentContentBlock.Text) b).text()).toList())
                .containsExactly("23:30", "23:31", "23:32");
    }

    /** Assistant à tool_use → tool_result (user) → nouveau user : fusionné, couplage préservé. */
    @Test
    void mergesToolResultUserWithFollowingUserKeepingToolCoupling() {
        AgentMessage assistantToolUse = AgentMessage.assistant(List.of(
                new AgentContentBlock.ToolUse("call-1", "bash", MissingNode.getInstance())));
        AgentMessage toolResult = AgentMessage.toolResults(List.of(
                new AgentContentBlock.ToolResult("call-1", "sortie", false)));
        AgentMessage nextUser = user("et maintenant ?");
        List<AgentMessage> raw = List.of(user("lance"), assistantToolUse, toolResult, nextUser);

        assertThat(AgentMessageSanitizer.isValidSequence(raw)).isFalse(); // toolResult(user) + user

        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(raw);

        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        // user, assistant(tool_use), user(tool_result + texte) — le tool_use reste dans l'assistant,
        // le tool_result dans le user : le couplage n'est jamais cassé.
        assertThat(clean).hasSize(3);
        assertThat(clean.get(1).content().get(0)).isInstanceOf(AgentContentBlock.ToolUse.class);
        List<AgentContentBlock> mergedUser = clean.get(2).content();
        assertThat(mergedUser).hasSize(2);
        assertThat(mergedUser.get(0)).isInstanceOf(AgentContentBlock.ToolResult.class);
        assertThat(mergedUser.get(1)).isInstanceOf(AgentContentBlock.Text.class);
    }

    @Test
    void mergesConsecutiveAssistants() {
        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(List.of(
                user("salut"), assistant("un"), assistant("deux")));
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean).hasSize(2);
        assertThat(clean.get(1).role()).isEqualTo("assistant");
        assertThat(clean.get(1).content()).hasSize(2);
    }

    @Test
    void dropsLeadingNonUserMessages() {
        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(List.of(
                assistant("en tête, à écarter"), user("vraie première parole"), assistant("réponse")));
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean.get(0).role()).isEqualTo("user");
        assertThat(((AgentContentBlock.Text) clean.get(0).content().get(0)).text())
                .isEqualTo("vraie première parole");
    }

    @Test
    void removesEmptyTextBlocksAndDropsEmptiedMessages() {
        AgentMessage blankAssistant = AgentMessage.assistant(List.of(new AgentContentBlock.Text("  ")));
        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(List.of(
                user("bonjour"), blankAssistant, user("toujours là ?")));
        // L'assistant blanc est écarté ; les deux user redeviennent consécutifs → fusionnés.
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean).hasSize(1);
        assertThat(clean.get(0).role()).isEqualTo("user");
        assertThat(clean.get(0).content()).hasSize(2);
    }

    @Test
    void preservesEffortDirectivesWithoutMerging() {
        AgentMessage effort = AgentMessage.effort("high");
        // assistant, effort (system, contenu vide), user : placement valide, effort conservé tel quel.
        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(List.of(
                user("demande"), assistant("je réfléchis"), effort, user("continue")));
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean).hasSize(4);
        assertThat(clean.get(2).isEffortDirective()).isTrue();
        assertThat(clean.get(2).content()).isEmpty();
    }

    @Test
    void dropsLeadingEffortDirectiveThatHasNothingToPace() {
        List<AgentMessage> clean = AgentMessageSanitizer.sanitize(List.of(
                AgentMessage.effort("high"), user("bonjour")));
        assertThat(AgentMessageSanitizer.isValidSequence(clean)).isTrue();
        assertThat(clean).hasSize(1);
        assertThat(clean.get(0).role()).isEqualTo("user");
    }

    @Test
    void isIdempotent() {
        List<AgentMessage> raw = List.of(assistant("ok"), user("a"), user("b"), user("c"));
        List<AgentMessage> once = AgentMessageSanitizer.sanitize(raw);
        List<AgentMessage> twice = AgentMessageSanitizer.sanitize(once);
        assertThat(twice).isEqualTo(once);
    }

    @Test
    void anAlreadyValidSequenceIsUnchanged() {
        List<AgentMessage> raw = List.of(user("a"), assistant("b"), user("c"));
        assertThat(AgentMessageSanitizer.isValidSequence(raw)).isTrue();
        assertThat(AgentMessageSanitizer.sanitize(raw)).isEqualTo(raw);
    }

    @Test
    void emptyOrNullYieldsEmpty() {
        assertThat(AgentMessageSanitizer.sanitize(null)).isEmpty();
        assertThat(AgentMessageSanitizer.sanitize(List.of())).isEmpty();
        assertThat(AgentMessageSanitizer.isValidSequence(List.of())).isFalse();
    }
}
