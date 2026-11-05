package fr.claudegateway.jarvis.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-248 / SF-248-02 — le catalogue : rien sans ouverture (K7), une fiche une fois, ciblées d'abord, 24 au plus. */
class JarvisCardCatalogTest {

    @AfterEach
    void close() {
        JarvisCardCatalog.close();
    }

    private static JarvisCard card(String id) {
        return JarvisCardTemplates.card(id, "TICKET", id, "En revue", Map.of(), null, OffsetDateTime.now(), "tour",
                null, List.of());
    }

    @Test
    @DisplayName("fermé : aucune fiche n'est fabriquée (coupe-circuit, présentation)")
    void nothingWhenClosed() {
        AtomicInteger made = new AtomicInteger();
        JarvisCardCatalog.collect(() -> {
            made.incrementAndGet();
            return card("fait:a");
        });
        assertThat(made).hasValue(0);
        assertThat(JarvisCardCatalog.active()).isFalse();
    }

    @Test
    @DisplayName("ouvert : une fiche une fois ; une lecture ciblée la promeut ; l'événement ne part que s'il a changé")
    void dedupeAndPromote() {
        JarvisCardCatalog c = JarvisCardCatalog.open();
        JarvisCardCatalog.collect(() -> card("fait:a"));
        JarvisCardCatalog.collect(() -> card("fait:b"));
        JarvisCardCatalog.collect(() -> card("fait:a"));
        JarvisCardCatalog.Event first = c.takeIfChanged();
        assertThat(first.cards()).extracting(JarvisCard::id).containsExactly("fait:a", "fait:b");
        assertThat(first.targeted()).isFalse();
        assertThat(c.takeIfChanged()).isNull();

        JarvisCardCatalog.collect(() -> card("fait:b").asTargeted());
        JarvisCardCatalog.Event second = c.takeIfChanged();
        assertThat(second.targeted()).isTrue();
        assertThat(second.responseId()).isEqualTo(first.responseId());
        assertThat(second.cards()).extracting(JarvisCard::id).containsExactly("fait:b", "fait:a");
    }

    @Test
    @DisplayName("24 au plus : une fiche ciblée remplace la dernière d'office ; une fiche en échec est omise")
    void bounded() {
        JarvisCardCatalog c = JarvisCardCatalog.open();
        for (int i = 0; i < 30; i++) {
            int n = i;
            JarvisCardCatalog.collect(() -> card("fait:" + n));
        }
        assertThat(c.cards()).hasSize(JarvisCardCatalog.MAX_CARDS);
        JarvisCardCatalog.collect(() -> card("fait:cible").asTargeted());
        assertThat(c.cards()).hasSize(JarvisCardCatalog.MAX_CARDS).first().extracting(JarvisCard::id)
                .isEqualTo("fait:cible");
        JarvisCardCatalog.collect(() -> {
            throw new IllegalStateException("panne");
        });
        assertThat(c.cards()).hasSize(JarvisCardCatalog.MAX_CARDS);
        assertThat(new JarvisCardCatalog().takeIfChanged()).isNull();
    }
}
