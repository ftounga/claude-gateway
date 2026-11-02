package fr.claudegateway.jarvis.portrait;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
import org.mockito.ArgumentCaptor;

import fr.claudegateway.byok.LocalAesByokKeyCipher;

/** F-241 / SF-241-04 — la trace : scellée, relue en cache, note du message, règle du jour, sujets récents. */
class JarvisAllusionServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-02T10:00:00Z");
    private final PortraitSeal seal = new PortraitSeal(
            new LocalAesByokKeyCipher("Ynlvay1sb2NhbC10ZXN0LW1hc3Rlci1rZXktMzJieXQ="));

    @Test
    void recordSealsAndRecentReadsBackOnceThenFromCache() {
        JarvisAllusionRepository repo = mock(JarvisAllusionRepository.class);
        List<JarvisAllusion> rows = new ArrayList<>();
        when(repo.save(any())).thenAnswer(inv -> {
            rows.add(0, inv.getArgument(0));
            return inv.getArgument(0);
        });
        when(repo.findTop30ByUserIdAndAtGreaterThanEqualOrderByAtDesc(any(), any())).thenAnswer(inv -> rows);
        JarvisAllusionService service = new JarvisAllusionService(repo, seal,
                Clock.fixed(Instant.parse("2026-11-02T10:00:00Z"), ZoneOffset.UTC));
        UUID alice = UUID.randomUUID();
        service.record(alice, "ALLUSION", "le petit ne dort pas", "Léo vous fait des nuits courtes.", true);
        ArgumentCaptor<JarvisAllusion> saved = ArgumentCaptor.forClass(JarvisAllusion.class);
        verify(repo).save(saved.capture());
        assertThat(saved.getValue().getTopicEnc()).doesNotContain("petit");
        assertThat(saved.getValue().getPhraseEnc()).doesNotContain("Léo");
        assertThat(saved.getValue().getUserId()).isEqualTo(alice);

        List<JarvisAllusionService.Entry> recent = service.recent(alice);
        assertThat(recent).extracting(JarvisAllusionService.Entry::topic).containsExactly("le petit ne dort pas");
        service.recent(alice);
        verify(repo, times(1)).findTop30ByUserIdAndAtGreaterThanEqualOrderByAtDesc(any(), any());
        service.record(alice, "HUMOUR", "le jazz", null, false);
        assertThat(service.recent(alice)).hasSize(2);
        service.record(alice, "ALLUSION", "  ", null, true);
        assertThat(rows).hasSize(2);
    }

    @Test
    void noteListsTheLastTenAndTodaysSpontaneousAllusion() {
        List<JarvisAllusionService.Entry> entries = new ArrayList<>();
        entries.add(new JarvisAllusionService.Entry(NOW.minusHours(1), "ALLUSION", "le petit ne dort pas", null, true));
        entries.add(new JarvisAllusionService.Entry(NOW.minusDays(3), "HUMOUR", "le marathon", null, false));
        for (int i = 0; i < 12; i++) {
            entries.add(new JarvisAllusionService.Entry(NOW.minusDays(10), "ALLUSION", "sujet " + i, null, false));
        }
        String note = JarvisAllusionService.note(entries, NOW);
        assertThat(note).startsWith("[Jarvis — déjà dit récemment, ne pas répéter : « le petit ne dort pas »")
                .contains("« le marathon » (humour)").contains("sujet 7").doesNotContain("sujet 8")
                .endsWith("allusion spontanée déjà faite aujourd'hui : oui]");
        assertThat(JarvisAllusionService.spontaneousToday(entries.subList(1, 3), NOW)).isFalse();
        assertThat(JarvisAllusionService.note(List.of(), NOW)).isNull();
        assertThat(JarvisAllusionService.topicsWithin(entries, NOW)).containsExactly("le petit ne dort pas",
                "le marathon");
        assertThat(JarvisAllusionService.benchNote(List.of("aujourd'hui: le semi-marathon")))
                .contains("« le semi-marathon »").endsWith("déjà faite aujourd'hui : oui]");
        assertThat(JarvisAllusionService.benchNote(List.of())).isNull();
    }
}
