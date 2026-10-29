package fr.claudegateway.atelier;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** F-221 / SF-221-01 — ce que voit un fil, et l'ordre stable par tour. */
class ChantierThreadTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-10-28T21:00:00Z");

    private static AtelierMessage msg(String role, Integer chantier, int minute, String content) {
        return AtelierMessage.builder().id(UUID.randomUUID()).role(role).chantierNumber(chantier)
                .createdAt(T0.plusMinutes(minute)).content(content).build();
    }

    private static List<String> contents(List<AtelierMessage> messages) {
        return messages.stream().map(AtelierMessage::getContent).toList();
    }

    @Test
    @DisplayName("le fil commun voit tout ; le fil N voit le passé commun, le commun et le sien — jamais l'autre chantier")
    void visibility() {
        List<AtelierMessage> all = List.of(
                msg("USER", null, 0, "cadrage"),
                msg("ASSISTANT", null, 1, "cadrage-réponse"),
                msg("USER", 7, 2, "ch7-avant-ouverture-8"),
                msg("ASSISTANT", 7, 3, "ch7-réponse-avant"),
                // le chantier 8 s'ouvre à la minute 4
                msg("USER", 7, 5, "ch7-course"),
                msg("USER", 8, 6, "ch8-planification"),
                msg("ASSISTANT", 7, 7, "ch7-course-réponse"),
                msg("ASSISTANT", 8, 8, "ch8-réponse"),
                msg("USER", null, 9, "commun-après"));

        assertThat(ChantierThread.visibleTo(all, null, null)).isSameAs(all);
        assertThat(contents(ChantierThread.visibleTo(all, 8, T0.plusMinutes(4)))).containsExactly(
                "cadrage", "cadrage-réponse", "ch7-avant-ouverture-8", "ch7-réponse-avant",
                "ch8-planification", "ch8-réponse", "commun-après");
        // Ouverture inconnue : le commun et le sien seulement.
        assertThat(contents(ChantierThread.visibleTo(all, 7, null))).containsExactly(
                "cadrage", "cadrage-réponse", "ch7-avant-ouverture-8", "ch7-réponse-avant", "ch7-course",
                "ch7-course-réponse", "commun-après");
    }

    @Test
    @DisplayName("sur un fil unique, le regroupement par tour est l'identité (préfixe en cache inchangé)")
    void singleThreadIsIdentity() {
        List<AtelierMessage> single = List.of(
                msg("USER", null, 0, "q1"), msg("ASSISTANT", null, 1, "r1"),
                msg("USER", null, 2, "q2"), msg("USER", null, 3, "précision"), msg("ASSISTANT", null, 4, "r2"),
                msg("ASSISTANT", null, 5, "r2-bis"), msg("USER", null, 6, "q3"));
        assertThat(ChantierThread.byTurn(single)).isSameAs(single);
        assertThat(ChantierThread.byTurn(List.of())).isEmpty();
    }

    @Test
    @DisplayName("deux fils entrelacés dans le temps sont rendus tour par tour, dans l'ordre de leur demande")
    void interleavedTurnsAreRegrouped() {
        List<AtelierMessage> interleaved = List.of(
                msg("USER", null, 0, "commun-q"),
                msg("USER", 8, 1, "ch8-q"),
                msg("ASSISTANT", null, 2, "commun-r"),
                msg("USER", 8, 3, "ch8-précision"),
                msg("ASSISTANT", 8, 4, "ch8-r"),
                msg("USER", null, 5, "commun-q2"));
        assertThat(contents(ChantierThread.byTurn(interleaved))).containsExactly(
                "commun-q", "commun-r", "ch8-q", "ch8-précision", "ch8-r", "commun-q2");
    }
}
