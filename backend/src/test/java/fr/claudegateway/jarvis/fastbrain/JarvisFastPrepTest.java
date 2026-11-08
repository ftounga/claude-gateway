package fr.claudegateway.jarvis.fastbrain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.cards.JarvisCard;
import fr.claudegateway.jarvis.cards.JarvisCardCatalog;

/** F-252 / SF-252-01 — la préparation parallèle : vite, même résultat, même repli, mêmes pancartes. */
class JarvisFastPrepTest {

    private final ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void tearDown() {
        JarvisCardCatalog.close();
        pool.shutdownNow();
    }

    private static String slow(String value) {
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return value;
    }

    private static JarvisCard card(String id) {
        return new JarvisCard(id, "ticket", id, null, List.of(), null, null, "tour", null, List.of(), false);
    }

    @Test
    @DisplayName("CA2 — cinq lectures de 100 ms préparées ensemble en moins de 300 ms, rendues dans l'ordre")
    void readsRunTogether() {
        long started = System.nanoTime();
        JarvisFastPrep prep = new JarvisFastPrep(pool, true);
        List<JarvisFastPrep.Early<String>> reads = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String v = "lecture " + i;
            reads.add(prep.early(() -> slow(v), () -> v));
        }
        List<String> got = reads.stream().map(JarvisFastPrep.Early::get).toList();
        long ms = (System.nanoTime() - started) / 1_000_000L;
        assertThat(got).containsExactly("lecture 0", "lecture 1", "lecture 2", "lecture 3", "lecture 4");
        assertThat(ms).isLessThan(300L);
        assertThat(prep.elapsedMs()).isGreaterThanOrEqualTo(100L);
    }

    @Test
    @DisplayName("coupe-circuit : en série, chaque lecture est faite à sa place (rien ne part en avance)")
    void serialWhenOff() {
        AtomicInteger calls = new AtomicInteger();
        JarvisFastPrep prep = new JarvisFastPrep(pool, false);
        JarvisFastPrep.Early<Integer> read = prep.early(calls::incrementAndGet, calls::incrementAndGet);
        assertThat(calls).hasValue(0);
        assertThat(read.get()).isEqualTo(1);
        assertThat(read.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA3 — une lecture en échec est refaite sur le fil de la décision (même repli qu'en série)")
    void failureFallsBackInline() {
        JarvisFastPrep prep = new JarvisFastPrep(pool, true);
        JarvisFastPrep.Early<String> read = prep.early(() -> {
            throw new IllegalStateException("base indisponible");
        }, () -> "repli");
        assertThat(read.get()).isEqualTo("repli");
    }

    @Test
    @DisplayName("les pancartes lues en parallèle reviennent au catalogue de la réplique, dans l'ordre série")
    void cardsComeBackInOrder() {
        JarvisCardCatalog parent = JarvisCardCatalog.open();
        parent.add(card("A"));
        JarvisFastPrep prep = new JarvisFastPrep(pool, true);
        JarvisFastPrep.Early<String> first = prep.early(() -> {
            slow("");
            JarvisCardCatalog.collect(() -> card("B"));
            JarvisCardCatalog.note("Poste éteint");
            return "1";
        }, () -> "1");
        JarvisFastPrep.Early<String> second = prep.early(() -> {
            JarvisCardCatalog.collect(() -> card("C"));
            JarvisCardCatalog.collect(() -> card("A").asTargeted());
            return "2";
        }, () -> "2");
        first.get();
        second.get();
        JarvisCardCatalog.Event event = parent.takeIfChanged();
        assertThat(event.cards()).extracting(JarvisCard::id).containsExactly("A", "B", "C");
        assertThat(event.cards().get(0).targeted()).isTrue();
        assertThat(event.offline()).isEqualTo("Poste éteint");
    }

    @Test
    @DisplayName("jamais plus de lectures simultanées que la borne du pod (le pool de connexions reste disponible)")
    void boundedConcurrency() {
        AtomicInteger running = new AtomicInteger();
        AtomicInteger peak = new AtomicInteger();
        JarvisFastPrep prep = new JarvisFastPrep(pool, true);
        List<JarvisFastPrep.Early<String>> reads = new ArrayList<>();
        for (int i = 0; i < JarvisFastPrep.MAX_CONCURRENT_READS * 2; i++) {
            reads.add(prep.early(() -> {
                peak.accumulateAndGet(running.incrementAndGet(), Math::max);
                slow("");
                running.decrementAndGet();
                return "lu";
            }, () -> "repli"));
        }
        reads.forEach(JarvisFastPrep.Early::get);
        assertThat(peak.get()).isLessThanOrEqualTo(JarvisFastPrep.MAX_CONCURRENT_READS);
        assertThat(reads).allSatisfy(r -> assertThat(r.get()).isEqualTo("lu"));
    }

    @Test
    @DisplayName("sans catalogue ouvert, aucune fiche n'est fabriquée sur le fil parallèle")
    void noCatalogNoCards() {
        JarvisFastPrep prep = new JarvisFastPrep(pool, true);
        JarvisFastPrep.Early<Boolean> read = prep.early(JarvisCardCatalog::active, () -> false);
        assertThat(read.get()).isFalse();
    }
}
