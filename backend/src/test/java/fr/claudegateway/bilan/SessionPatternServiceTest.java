package fr.claudegateway.bilan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Les motifs qui reviennent (F-155 / SF-155-05).
 *
 * <p>Ce que ces tests tiennent : <b>une anecdote n'est pas un motif</b>, un motif fréquent ailleurs
 * ne s'invite pas dans un bilan où il n'apparaît pas, les bilans d'avant cette subfeature sont
 * ignorés sans erreur, et le comptage ne voit que les bilans <b>du compte</b>.</p>
 */
class SessionPatternServiceTest {

    private final SessionBilanRepository repository = mock(SessionBilanRepository.class);
    private final UUID userId = UUID.randomUUID();

    private SessionPatternService service;

    @BeforeEach
    void setUp() {
        service = new SessionPatternService(repository, SessionBilanProperties.defaults());
    }

    private SessionBilan bilan(String kinds) {
        SessionBilan b = new SessionBilan();
        b.setId(UUID.randomUUID());
        b.setUserId(userId);
        b.setCostEur(BigDecimal.ZERO);
        b.setSuggestionKinds(kinds);
        return b;
    }

    private void history(SessionBilan... past) {
        when(repository.findByUserIdOrderByCreatedAtDesc(eq(userId), any(Pageable.class)))
                .thenReturn(Arrays.asList(past));
    }

    @Test
    @DisplayName("3 fois sur la fenêtre fait un motif, avec son compte et ce que le diagnostic irait chercher")
    void threeOccurrencesMakeAPattern() {
        SessionBilan current = bilan("CACHE_FROID,OUTIL_DOMINANT");
        history(current, bilan("CACHE_FROID"), bilan("CACHE_FROID"), bilan("ECHECS_REPETES"));

        List<SessionPattern> patterns = service.patternsFor(userId, current);

        assertThat(patterns).hasSize(1);
        SessionPattern pattern = patterns.get(0);
        assertThat(pattern.kind()).isEqualTo(SessionSuggestion.Kind.CACHE_FROID);
        assertThat(pattern.seen()).isEqualTo(3);
        assertThat(pattern.window()).isEqualTo(4);
        assertThat(pattern.lead()).contains("consigne système").contains("pas pour un seul projet");
    }

    @Test
    @DisplayName("2 fois n'en est pas un : une anecdote n'est pas un motif")
    void twoOccurrencesAreAnecdote() {
        SessionBilan current = bilan("CACHE_FROID");
        history(current, bilan("CACHE_FROID"), bilan("OUTIL_DOMINANT"));

        assertThat(service.patternsFor(userId, current)).isEmpty();
    }

    @Test
    @DisplayName("un motif fréquent AILLEURS ne s'invite pas dans un bilan où il n'apparaît pas")
    void anAbsentKindIsNotReported() {
        SessionBilan current = bilan("OUTIL_DOMINANT");
        history(current, bilan("CACHE_FROID"), bilan("CACHE_FROID"), bilan("CACHE_FROID"));

        assertThat(service.patternsFor(userId, current))
                .as("CACHE_FROID revient 3 fois, mais pas dans CE bilan")
                .isEmpty();
    }

    @Test
    @DisplayName("le premier bilan ne peut rien répéter")
    void theFirstBilanRepeatsNothing() {
        SessionBilan current = bilan("CACHE_FROID");
        history(current);

        assertThat(service.patternsFor(userId, current)).isEmpty();
    }

    @Test
    @DisplayName("un bilan sans genre ne compte pour rien, et ne lit même pas l'historique")
    void aBilanWithoutKindsReadsNothing() {
        assertThat(service.patternsFor(userId, bilan(null))).isEmpty();
        assertThat(service.patternsFor(userId, bilan("  "))).isEmpty();
        verify(repository, org.mockito.Mockito.never())
                .findByUserIdOrderByCreatedAtDesc(any(), any(Pageable.class));
    }

    @Test
    @DisplayName("les genres inconnus ou vides sont ignorés — un bilan ancien reste lisible")
    void unknownKindsAreIgnored() {
        assertThat(SessionPatternService.kindsOf("CACHE_FROID,DISPARU,,  ,outil_dominant"))
                .containsExactly(SessionSuggestion.Kind.CACHE_FROID,
                        SessionSuggestion.Kind.OUTIL_DOMINANT);
        assertThat(SessionPatternService.kindsOf(null)).isEmpty();
    }

    @Test
    @DisplayName("les genres se gardent en clair, dédoublonnés")
    void kindsAreStoredPlainAndDeduplicated() {
        List<SessionSuggestion> suggestions = List.of(
                SessionSuggestion.ofCost(SessionSuggestion.Kind.CACHE_FROID, "a", "m", 20, null),
                SessionSuggestion.ofCost(SessionSuggestion.Kind.CACHE_FROID, "b", "m", 15, null),
                SessionSuggestion.of(SessionSuggestion.Kind.OUTIL_DOMINANT,
                        SessionSuggestion.Axis.TEMPS, "c", "m", 30));

        assertThat(SessionPatternService.kindsColumn(suggestions))
                .isEqualTo("CACHE_FROID,OUTIL_DOMINANT");
        assertThat(SessionPatternService.kindsColumn(List.of())).isNull();
        assertThat(SessionPatternService.kindsColumn(null)).isNull();
    }

    @Test
    @DisplayName("ISOLATION — le comptage ne lit que les bilans du compte, sur une fenêtre bornée")
    void countsOnlyThisAccount() {
        SessionBilan current = bilan("CACHE_FROID");
        history(current, bilan("CACHE_FROID"), bilan("CACHE_FROID"));

        service.patternsFor(userId, current);

        org.mockito.ArgumentCaptor<Pageable> page =
                org.mockito.ArgumentCaptor.forClass(Pageable.class);
        verify(repository).findByUserIdOrderByCreatedAtDesc(eq(userId), page.capture());
        assertThat(page.getValue().getPageSize())
                .isEqualTo(SessionBilanProperties.defaults().patternWindow());
    }

    @Test
    @DisplayName("les motifs sortent du plus fréquent au moins fréquent")
    void sortedByFrequency() {
        SessionBilan current = bilan("CACHE_FROID,ECHECS_REPETES");
        history(current, bilan("CACHE_FROID,ECHECS_REPETES"), bilan("CACHE_FROID,ECHECS_REPETES"),
                bilan("CACHE_FROID"));

        assertThat(service.patternsFor(userId, current))
                .extracting(SessionPattern::kind)
                .containsExactly(SessionSuggestion.Kind.CACHE_FROID,
                        SessionSuggestion.Kind.ECHECS_REPETES);
    }
}
