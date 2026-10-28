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
                null, 0, 1, null, mark, false, List.of(), null, idle, idle >= 2, false, null, null, null, false);
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

    // ------------------------------------------------------------------ SF-220-07

    private static fr.claudegateway.atelier.actions.TerminalAction action(
            fr.claudegateway.atelier.actions.TerminalActionStatus status,
            fr.claudegateway.atelier.actions.TerminalActionQui qui, String person, String to) {
        return fr.claudegateway.atelier.actions.TerminalAction.builder().id(UUID.randomUUID()).status(status)
                .qui(qui).person(person).requestedTo(to)
                .createdAt(java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"))
                .requestedAt(to == null ? null : java.time.OffsetDateTime.parse("2026-10-20T09:00:00Z")).build();
    }

    @Test
    @DisplayName("qui débloque une attente hors chantier : geste à moi, demande à faire, demande partie")
    void looseMark() {
        var A = fr.claudegateway.atelier.actions.TerminalActionStatus.A_FAIRE;
        var D = fr.claudegateway.atelier.actions.TerminalActionStatus.DEMANDE;
        var moi = fr.claudegateway.atelier.actions.TerminalActionQui.MOI;
        var client = fr.claudegateway.atelier.actions.TerminalActionQui.CLIENT;

        assertThat(ClientBoardService.markOf(action(D, moi, null, null)).who()).isEqualTo(ChantierUnblocker.Who.MOI);
        ChantierUnblocker.Mark toAsk = ClientBoardService.markOf(action(A, client, "Gino", null));
        assertThat(toAsk.who()).isEqualTo(ChantierUnblocker.Who.MOI);
        assertThat(toAsk.detail()).isEqualTo("À demander à Gino.");
        assertThat(ClientBoardService.markOf(action(A, null, null, null)).detail()).isEqualTo("À demander.");

        ChantierUnblocker.Mark asked = ClientBoardService.markOf(action(D, client, "Gino", "Gino Rossi"));
        assertThat(asked.who()).isEqualTo(ChantierUnblocker.Who.AUTRUI);
        assertThat(asked.waitingOn()).isEqualTo("Gino Rossi");
        assertThat(asked.since()).isEqualTo(java.time.OffsetDateTime.parse("2026-10-20T09:00:00Z"));
        ChantierUnblocker.Mark noOne = ClientBoardService.markOf(action(D, null, " ", null));
        assertThat(noOne.waitingOn()).isNull();
        assertThat(noOne.since()).isEqualTo(java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"));
    }

    private static ClientBoard.LooseTask task(String name, ChantierUnblocker.Who who, long idle) {
        ChantierUnblocker.Mark mark = new ChantierUnblocker.Mark(who, who.label(), "d", null, null, null);
        return new ClientBoard.LooseTask(UUID.randomUUID(), UUID.randomUUID(), "ws", false, name, null, "A_FAIRE",
                null, null, null, null, null, java.time.OffsetDateTime.parse("2026-10-01T09:00:00Z"), mark, null,
                idle, idle >= 2, false);
    }

    @Test
    @DisplayName("hors chantier : ma décision avant quelqu'un, le plus dormant d'abord ; comptées dans l'en-tête")
    void looseOrderAndCounts() {
        List<ClientBoard.LooseTask> tasks = new ArrayList<>(List.of(
                task("a", ChantierUnblocker.Who.AUTRUI, 9),
                task("b", ChantierUnblocker.Who.MOI, 1),
                task("c", ChantierUnblocker.Who.MOI, 4)));
        tasks.sort(ClientBoardService.LOOSE_ORDER);
        assertThat(tasks).extracting(ClientBoard.LooseTask::description).containsExactly("c", "b", "a");

        ClientBoard.Counts counts = ClientBoardService.counts(
                List.of(card("x", 1, ChantierUnblocker.Who.MOI, 0)), tasks);
        assertThat(counts.moi()).isEqualTo(3);
        assertThat(counts.autrui()).isEqualTo(1);
        assertThat(counts.dormant()).isEqualTo(2);
        assertThat(counts.horsChantier()).isEqualTo(3);
    }
}
