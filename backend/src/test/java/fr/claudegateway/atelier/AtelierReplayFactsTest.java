package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** La fenêtre de rejeu a-t-elle avancé depuis le tour précédent ? (F-229 / SF-229-01) */
class AtelierReplayFactsTest {

    private static List<AtelierMessage> thread(int turns) {
        List<AtelierMessage> messages = new ArrayList<>();
        for (int i = 0; i < turns; i++) {
            messages.add(AtelierMessage.builder().role("USER").content("q" + i).build());
            messages.add(AtelierMessage.builder().role("ASSISTANT").content("r" + i).build());
        }
        return messages;
    }

    @Test
    void theWindowMovesOnlyAtItsSteps() {
        assertThat(AtelierChatService.replayWindowMoved(thread(12), 12)).isFalse();
        assertThat(AtelierChatService.replayWindowMoved(thread(13), 12)).isFalse();
        assertThat(AtelierChatService.replayWindowMoved(thread(24), 12)).isTrue();
        assertThat(AtelierChatService.replayWindowMoved(thread(25), 12)).isFalse();
        assertThat(AtelierChatService.replayWindowMoved(thread(36), 12)).isTrue();
        assertThat(AtelierChatService.replayWindowMoved(thread(0), 12)).isFalse();
        assertThat(AtelierChatService.replayWindowMoved(thread(5), 0)).isFalse();
    }

    @Test
    void theStepIsTheOneTheReplayUses() {
        for (int turns = 0; turns < 40; turns++) {
            int untraced = AtelierChatService.untracedTurns(turns, 12);
            int firstTraced = AtelierChatService.firstTracedIndex(thread(turns), 12);
            // Le premier assistant tracé est le (untraced+1)-ième : index 2*untraced+1, ou 0 s'il n'y a pas de palier.
            assertThat(firstTraced).isEqualTo(untraced == 0 ? 0 : 2 * untraced + 1);
        }
    }
}
