package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.veille.JarvisWatchDiff.Change;
import fr.claudegateway.atelier.veille.JarvisWatchDiff.Kind;
import fr.claudegateway.atelier.veille.JarvisWatchItems.Item;

/** F-230 / SF-230-02 (D3) — ce qui a bougé entre deux passages, élément par élément. */
class JarvisWatchDiffTest {

    private static Item item(String id, String status, String assignee, String comment) {
        return new Item(id, "Titre " + id, status, assignee, "2026-11-01", comment);
    }

    @Test
    void nothingMovedMeansNoChange() {
        List<Item> list = List.of(item("A", "En cours", "PO", ""), item("B", "Bloqué", "Bob", "vu"));
        assertThat(JarvisWatchDiff.between(list, List.copyOf(list))).isEmpty();
    }

    @Test
    void eachKindOfChangeIsNamed() {
        List<Item> before = List.of(item("A", "En cours", "PO", ""), item("B", "En cours", "Bob", ""),
                item("C", "Ready", "Bob", ""), item("D", "En cours", "Bob", ""));
        List<Item> after = List.of(item("A", "Bloqué", "PO", ""), item("B", "En cours", "PO", ""),
                item("C", "Ready", "Bob", "relancé"), item("E", "Ready", "PO", ""));
        List<Change> changes = JarvisWatchDiff.between(before, after);
        assertThat(changes).extracting(Change::kind)
                .containsExactly(Kind.NEW, Kind.STATUS, Kind.ASSIGNEE, Kind.COMMENT, Kind.GONE);
        assertThat(changes.get(1).before()).isEqualTo("En cours");
        assertThat(changes.get(1).after()).isEqualTo("Bloqué");
        assertThat(changes.get(0).id()).isEqualTo("E");
        assertThat(changes.get(4).id()).isEqualTo("D");
    }

    @Test
    void aTitleOrDateChangeAloneIsAnUpdate() {
        Item old = item("A", "En cours", "PO", "");
        Item renamed = new Item("A", "Nouveau titre", "En cours", "PO", "2026-11-02", "");
        assertThat(JarvisWatchDiff.between(List.of(old), List.of(renamed))).extracting(Change::kind)
                .containsExactly(Kind.UPDATED);
    }

    @Test
    void thePlainSummaryCountsByKind() {
        List<Change> changes = List.of(new Change(Kind.NEW, "E", "t", "", "Ready"),
                new Change(Kind.STATUS, "A", "t", "En cours", "Bloqué"),
                new Change(Kind.STATUS, "B", "t", "En cours", "Bloqué"));
        assertThat(JarvisWatchDiff.plainSummary("Agenor", changes))
                .isEqualTo("Agenor : 3 changements — 1 nouveau, 2 changements de statut.");
    }
}
