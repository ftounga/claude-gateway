package fr.claudegateway.atelier.dashboard;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.journey.ChantierUnblocker;

/** F-220 / SF-220-02 — l'ordre du tableau et la lecture de la question en suspens. */
class ClientBoardServiceTest {

    private static ClientBoard.Chantier card(String ws, int number, ChantierUnblocker.Who who, long idle) {
        ChantierUnblocker.Mark mark = new ChantierUnblocker.Mark(who, who.label(), "d", null, null, null);
        return new ClientBoard.Chantier(UUID.randomUUID(), ws, false, number, "t", "EXECUTION", "Exécution", true,
                null, 0, 1, null, mark, false, List.of(), null, idle, idle >= 2, false, null, null, null);
    }

    @Test
    @DisplayName("Outil, puis Ma décision, puis Quelqu'un ; le plus dormant d'abord dans chaque groupe")
    void order() {
        List<ClientBoard.Chantier> cards = new ArrayList<>(List.of(
                card("a", 1, ChantierUnblocker.Who.AUTRUI, 9),
                card("b", 1, ChantierUnblocker.Who.MOI, 1),
                card("c", 1, ChantierUnblocker.Who.OUTIL, 0),
                card("d", 1, ChantierUnblocker.Who.OUTIL, 5),
                card("e", 1, ChantierUnblocker.Who.MOI, 3)));
        cards.sort(ClientBoardService.ORDER);
        assertThat(cards).extracting(ClientBoard.Chantier::workspaceName).containsExactly("d", "c", "e", "b", "a");
    }

    @Test
    @DisplayName("la question en suspens : le texte de la première, sinon son titre ; JSON illisible → rien")
    void firstQuestion() {
        assertThat(ClientBoardService.firstQuestion("[{\"header\":\"Ns\",\"question\":\"Quel namespace ?\"}]"))
                .isEqualTo("Quel namespace ?");
        assertThat(ClientBoardService.firstQuestion("[{\"header\":\"Namespace\"}]")).isEqualTo("Namespace");
        assertThat(ClientBoardService.firstQuestion("[]")).isNull();
        assertThat(ClientBoardService.firstQuestion("pas du json")).isNull();
        assertThat(ClientBoardService.firstQuestion(null)).isNull();
    }
}
