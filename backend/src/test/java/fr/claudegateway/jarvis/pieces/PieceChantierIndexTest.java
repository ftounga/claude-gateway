package fr.claudegateway.jarvis.pieces;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-237 / SF-237-01 — le rattachement d'une pièce à un chantier : message, fenêtre, aucun. */
class PieceChantierIndexTest {

    private static final OffsetDateTime T0 = OffsetDateTime.of(2026, 11, 2, 9, 0, 0, 0, ZoneOffset.UTC);

    private static PieceChantierIndex.ChantierMessage msg(UUID id, int chantier, int minutes, String trace) {
        return new PieceChantierIndex.ChantierMessage(id, chantier, T0.plusMinutes(minutes), trace);
    }

    @Test
    @DisplayName("MESSAGE : le message lié porte le chantier")
    void byMessageId() {
        UUID m = UUID.randomUUID();
        var index = new PieceChantierIndex(List.of(msg(m, 3, 0, null)), null, null);
        assertThat(index.link(m, T0.plusDays(9))).isEqualTo(new PieceChantierIndex.Link(3,
                Piece.ChantierLink.MESSAGE));
    }

    @Test
    @DisplayName("MESSAGE : l'identifiant de la pièce est cité dans la trace d'un message de chantier")
    void byTraceToken() {
        String id = UUID.randomUUID().toString();
        var index = new PieceChantierIndex(List.of(msg(UUID.randomUUID(), 2, 0, "{\"page\":\"" + id + "\"}")),
                null, null);
        assertThat(index.link(null, T0.plusDays(30), id).link()).isEqualTo(Piece.ChantierLink.MESSAGE);
        assertThat(index.link(null, T0.plusDays(30), id).chantier()).isEqualTo(2);
        // un jeton trop court ne rattache rien
        assertThat(index.link(null, T0.plusDays(30), "page").link()).isEqualTo(Piece.ChantierLink.NONE);
    }

    @Test
    @DisplayName("WINDOW : la date tombe dans la fenêtre d'un seul chantier")
    void byWindow() {
        var index = new PieceChantierIndex(List.of(msg(UUID.randomUUID(), 1, 0, null),
                msg(UUID.randomUUID(), 1, 60, null), msg(UUID.randomUUID(), 2, 120, null),
                msg(UUID.randomUUID(), 2, 180, null)), null, null);
        assertThat(index.link(UUID.randomUUID(), T0.plusMinutes(30)))
                .isEqualTo(new PieceChantierIndex.Link(1, Piece.ChantierLink.WINDOW));
        assertThat(index.link(null, T0.plusMinutes(150)).chantier()).isEqualTo(2);
        assertThat(index.link(null, T0.plusMinutes(90)).link()).isEqualTo(Piece.ChantierLink.NONE);
        assertThat(index.link(null, T0.plusMinutes(300)).link()).isEqualTo(Piece.ChantierLink.NONE);
    }

    @Test
    @DisplayName("NONE : deux chantiers ouverts en même temps — on ne tranche pas")
    void concurrentChantiers() {
        var index = new PieceChantierIndex(List.of(msg(UUID.randomUUID(), 1, 0, null),
                msg(UUID.randomUUID(), 2, 10, null), msg(UUID.randomUUID(), 1, 60, null),
                msg(UUID.randomUUID(), 2, 70, null)), null, null);
        assertThat(index.link(null, T0.plusMinutes(30)).link()).isEqualTo(Piece.ChantierLink.NONE);
        assertThat(index.link(null, T0.plusMinutes(5)).chantier()).isEqualTo(1);
    }

    @Test
    @DisplayName("le chantier courant reste ouvert jusqu'à maintenant")
    void openChantierExtendsToNow() {
        var index = new PieceChantierIndex(List.of(msg(UUID.randomUUID(), 1, 0, null),
                msg(UUID.randomUUID(), 1, 10, null), msg(UUID.randomUUID(), 2, 60, null)), 2, null);
        assertThat(index.link(null, T0.plusDays(3))).isEqualTo(new PieceChantierIndex.Link(2,
                Piece.ChantierLink.WINDOW));
        var fresh = new PieceChantierIndex(List.of(), 4, T0);
        assertThat(fresh.link(null, T0.plusHours(1)).chantier()).isEqualTo(4);
        assertThat(fresh.link(null, T0.minusHours(1)).link()).isEqualTo(Piece.ChantierLink.NONE);
        assertThat(fresh.link(null, null).link()).isEqualTo(Piece.ChantierLink.NONE);
    }
}
