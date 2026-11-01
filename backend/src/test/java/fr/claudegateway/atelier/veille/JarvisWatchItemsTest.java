package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

/** F-230 / SF-230-01 (D2) — la liste normalisée d'une sonde : lecture tolérante, bornes, résumé. */
class JarvisWatchItemsTest {

    private static final String LIST = """
            [{"id":"AG-1","titre":"Alarme","statut":"En cours","assigne":"PO","mis_a_jour":"2026-11-01",
              "dernier_commentaire":"vu"},
             {"id":"AG-2","titre":"Batch","statut":"Bloqué","assigné":"Bob","mis_à_jour":"2026-10-31"},
             {"id":"AG-3","titre":"Front","statut":"en cours"}]""";

    @Test
    void readsACleanList() {
        JarvisWatchItems.Parsed parsed = JarvisWatchItems.parse(LIST);
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.items()).hasSize(3);
        assertThat(parsed.items().get(1).assignee()).isEqualTo("Bob");
        assertThat(parsed.items().get(1).updatedAt()).isEqualTo("2026-10-31");
        assertThat(parsed.items().get(2).lastComment()).isEmpty();
    }

    @Test
    void toleratesNoiseAroundTheJson() {
        JarvisWatchItems.Parsed parsed = JarvisWatchItems.parse("warning: proxy\n" + LIST + "\n");
        assertThat(parsed.ok()).isTrue();
        assertThat(parsed.items()).hasSize(3);
    }

    @Test
    void refusesWhatIsNotAList() {
        assertThat(JarvisWatchItems.parse("").ok()).isFalse();
        assertThat(JarvisWatchItems.parse("{\"id\":1}").error()).contains("liste JSON");
        assertThat(JarvisWatchItems.parse("[{\"titre\":\"sans id\"}]").error()).contains("id");
        assertThat(JarvisWatchItems.parse("[1,2]").error()).contains("objet");
    }

    @Test
    void refusesMoreThanTheCap() {
        StringBuilder big = new StringBuilder("[");
        for (int i = 0; i <= JarvisWatchItems.MAX_ITEMS; i++) {
            big.append(i == 0 ? "" : ",").append("{\"id\":").append(i).append('}');
        }
        assertThat(JarvisWatchItems.parse(big.append(']').toString()).error()).contains("resserre");
    }

    @Test
    void summarisesByStatusMostFrequentFirst() {
        List<JarvisWatchItems.Item> items = JarvisWatchItems.parse(LIST).items();
        assertThat(JarvisWatchItems.summary(items)).isEqualTo("3 éléments : 2 en cours, 1 bloqué");
        assertThat(JarvisWatchItems.sample(items)).containsExactly("AG-1 Alarme", "AG-2 Batch", "AG-3 Front");
        assertThat(JarvisWatchItems.summary(List.of())).contains("Aucun élément");
    }

    @Test
    void snapshotsRoundTrip() {
        List<JarvisWatchItems.Item> items = JarvisWatchItems.parse(LIST).items();
        assertThat(JarvisWatchItems.fromJson(JarvisWatchItems.toJson(items))).isEqualTo(items);
        assertThat(JarvisWatchItems.fromJson("pas du json")).isEmpty();
    }
}
