package fr.claudegateway.jarvis.stt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.jarvis.universe.JarvisUniverse;
import fr.claudegateway.jarvis.universe.JarvisUniverseService;

/** Le vocabulaire de l'écoute (F-239 / SF-239-04, D2) : ordre, nettoyage, bornes du fournisseur, cache. */
class JarvisVocabularyTest {

    private static JarvisUniverse universe() {
        JarvisUniverse.Chantier ch = new JarvisUniverse.Chantier(3, "LDIC-223 rotation du jeton GitLab", "RUN", "En cours",
                1, 4, new JarvisUniverse.Step(2, "Valider WAF-12 avec René"), false, false, List.of());
        JarvisUniverse.Chantier longTitle = new JarvisUniverse.Chantier(4,
                "Un titre de chantier beaucoup trop long pour servir de mot-clé", "RUN", "En cours", 0, 1, null, false,
                false, List.of());
        JarvisUniverse.Subject agenor = new JarvisUniverse.Subject(UUID.randomUUID(), "agenor", false, false,
                List.of(ch, longTitle));
        JarvisUniverse.Subject waf = new JarvisUniverse.Subject(UUID.randomUUID(), "waf<{script}>", false, false,
                List.of());
        JarvisUniverse.Client cagip = new JarvisUniverse.Client(UUID.randomUUID(), "CAGIP", false, true, null,
                List.of(agenor, waf), List.of(new JarvisUniverse.Person("René Martin", List.of(), "RSSI")), List.of(),
                false);
        JarvisUniverse.Client edenred = new JarvisUniverse.Client(UUID.randomUUID(), "EDENRED", false, true, null,
                List.of(new JarvisUniverse.Subject(UUID.randomUUID(), "Agénor", false, false, List.of())), List.of(),
                List.of(), false);
        return new JarvisUniverse(OffsetDateTime.now(), List.of(cagip, edenred), 0);
    }

    @Test
    void termsComeInPriorityOrderCleanedAndDeduplicated() {
        List<String> terms = JarvisVocabulary.build(universe(), 100);

        assertThat(terms).startsWith("Jarvis", "CAGIP", "EDENRED", "agenor");
        assertThat(terms).contains("waf script", "René Martin", "LDIC-223", "WAF-12");
        // « Agénor » est « agenor » sans accent : un seul terme.
        assertThat(terms).filteredOn(t -> t.equalsIgnoreCase("agenor") || t.equals("Agénor")).hasSize(1);
        // Les bornes du fournisseur : ≤ 50 caractères, ≤ 5 mots, aucun < > { } [ ] \.
        assertThat(terms).allSatisfy(t -> {
            assertThat(t.length()).isLessThan(50);
            assertThat(t.split(" ")).hasSizeLessThanOrEqualTo(5);
            assertThat(t).doesNotContain("<", ">", "{", "}", "[", "]", "\\");
        });
        assertThat(terms).doesNotContain("Un titre de chantier beaucoup trop long pour servir de mot-clé");
    }

    @Test
    void neverMoreThanTheMaximum() {
        List<JarvisUniverse.Subject> many = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            many.add(new JarvisUniverse.Subject(UUID.randomUUID(), "sujet-" + i, false, false, List.of()));
        }
        JarvisUniverse big = new JarvisUniverse(OffsetDateTime.now(), List.of(new JarvisUniverse.Client(
                UUID.randomUUID(), "CAGIP", false, true, null, many, List.of(), List.of(), false)), 0);

        assertThat(JarvisVocabulary.build(big, 100)).hasSize(100).startsWith("Jarvis", "CAGIP");
        assertThat(JarvisVocabulary.build(null, 100)).containsExactly("Jarvis");
    }

    @Test
    void readUnderTheAccountKeptFiveMinutesAndNeverThrows() {
        JarvisUniverseService service = mock(JarvisUniverseService.class);
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        when(service.universe(alice)).thenReturn(universe());
        when(service.universe(bob)).thenThrow(new IllegalStateException("panne"));
        JarvisVocabulary vocabulary = new JarvisVocabulary(service,
                new JarvisUtteranceProperties(null, null, null, null, null, null),
                Clock.fixed(Instant.parse("2026-11-02T09:00:00Z"), ZoneOffset.UTC));

        assertThat(vocabulary.of(alice)).contains("CAGIP");
        vocabulary.of(alice);
        verify(service, times(1)).universe(alice);
        assertThat(vocabulary.of(bob)).containsExactly("Jarvis");
    }
}
