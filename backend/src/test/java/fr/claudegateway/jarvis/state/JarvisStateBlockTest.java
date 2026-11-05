package fr.claudegateway.jarvis.state;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * F-247 / SF-247-10 — le bloc « État connu » : borné quel que soit le volume (Z9), pertinence, fraîcheur dite (D8),
 * poste connecté / déconnecté. Les 12 cas déterministes du banc {@code ETAT} (sans modèle) y sont rejoués.
 */
class JarvisStateBlockTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T08:00:00+01:00");
    private final UUID cagip = UUID.randomUUID();
    private final UUID bnp = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    private static JarvisStateRegistry.Fact fact(UUID host, UUID ws, String key, String attr, String value,
            String previous, OffsetDateTime at) {
        return new JarvisStateRegistry.Fact(UUID.randomUUID(), host, ws, "TICKET", key, attr, value, previous,
                previous == null ? null : at.minusHours(1), at, previous == null ? null : at, "TOUR", "HAUTE");
    }

    private JarvisStateBlock.Client client(UUID host, String name, boolean connected, int n, OffsetDateTime at) {
        List<JarvisStateRegistry.Fact> facts = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            facts.add(fact(host, null, "JIRA:P-" + i, "status", "Statut numéro " + i, null, at.minusMinutes(i)));
        }
        return new JarvisStateBlock.Client(host, name, connected, connected ? null : NOW.minusHours(14),
                connected ? null : "ARRET_PROPRE", 0, JarvisStateBlock.lines(facts, NOW));
    }

    @ParameterizedTest
    @ValueSource(ints = {10, 10_000, 100_000})
    @DisplayName("Z9 — 20 faits et 2 000 caractères au plus, à 10, 10 000 et 100 000 faits")
    void boundedWhateverTheVolume(int n) {
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(client(cagip, "CAGIP", false, n,
                NOW.minusHours(2))));
        JarvisStateBlock.Selected s = JarvisStateBlock.select(p, cagip, null, NOW);
        assertThat(s.lines().size()).isLessThanOrEqualTo(JarvisStateBlock.MAX_FACTS);
        assertThat(s.text().length()).isLessThanOrEqualTo(JarvisStateBlock.MAX_CHARS);
        assertThat(s.text()).startsWith("## État connu — client CAGIP").contains("Poste déconnecté depuis hier 18:00")
                .contains("(arrêt propre)");
    }

    @Test
    @DisplayName("ETAT-01..04 — éteint : frais, ancien (« ancien » au-delà de 24 h), changé (« avant : »), absent")
    void offlineLines() {
        List<JarvisStateRegistry.Fact> facts = List.of(
                fact(cagip, null, "JIRA:PROJ-123", "status", "En revue", "En cours", NOW.minusHours(14)),
                fact(cagip, null, "JIRA:PROJ-123", "title", "Migration SSO", null, NOW.minusHours(14)),
                fact(cagip, null, "JIRA:PROJ-9", "status", "Fait", null, NOW.minusDays(3)));
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(new JarvisStateBlock.Client(cagip,
                "CAGIP", false, NOW.minusHours(14), "ARRET_PROPRE", 3, JarvisStateBlock.lines(facts, NOW))));
        String text = JarvisStateBlock.select(p, cagip, null, NOW).text();
        assertThat(text).contains("- PROJ-123 « Migration SSO » : En revue (vu hier 18:00, tour) — avant : En cours");
        assertThat(text).contains("- PROJ-9 : Fait (vu le 2/11 08:00, tour) — ancien");
        assertThat(text).contains("3 attentes ouvertes (état à 08:00)");
        assertThat(text).doesNotContain("PROJ-456");
    }

    @Test
    @DisplayName("ETAT-05..06 — allumé : seuls les faits de plus de 15 min entrent ; autre client allumé : absent")
    void connectedKeepsOnlyOlderThan15min() {
        List<JarvisStateRegistry.Fact> facts = List.of(
                fact(cagip, null, "JIRA:NEW-1", "status", "Ouvert", null, NOW.minusMinutes(3)),
                fact(cagip, null, "JIRA:OLD-1", "status", "Fait", null, NOW.minusMinutes(40)));
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(
                new JarvisStateBlock.Client(cagip, "CAGIP", true, null, null, 0, JarvisStateBlock.lines(facts, NOW)),
                client(bnp, "BNP", true, 3, NOW.minusHours(1))));
        String text = JarvisStateBlock.select(p, cagip, null, NOW).text();
        assertThat(text).contains("OLD-1").doesNotContain("NEW-1").doesNotContain("BNP");
        assertThat(text).contains("une question qui exige le frais se délègue");
    }

    @Test
    @DisplayName("ETAT-07..08 — deux clients : le courant d'abord, puis l'autre client au poste déconnecté")
    void relevanceAcrossClients() {
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(
                client(bnp, "BNP", false, 2, NOW.minusHours(3)), client(cagip, "CAGIP", false, 2, NOW.minusHours(3))));
        String text = JarvisStateBlock.select(p, cagip, null, NOW).text();
        assertThat(text.indexOf("client CAGIP")).isLessThan(text.indexOf("client BNP"));
        assertThat(text).contains("client BNP");
    }

    @Test
    @DisplayName("ETAT-09 — le sujet en vue passe d'abord, puis le changé et le bloqué")
    void relevanceWithinClient() {
        List<JarvisStateRegistry.Fact> facts = List.of(
                fact(cagip, null, "JIRA:A-1", "status", "Ouvert", null, NOW.minusMinutes(20)),
                fact(cagip, subject, "JIRA:S-1", "status", "Ouvert", null, NOW.minusHours(5)),
                fact(cagip, null, "JIRA:C-1", "status", "Fait", "Ouvert", NOW.minusHours(4)));
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(new JarvisStateBlock.Client(cagip,
                "CAGIP", false, null, null, 0, JarvisStateBlock.lines(facts, NOW))));
        List<JarvisStateBlock.Line> lines = JarvisStateBlock.select(p, cagip, subject, NOW).lines();
        assertThat(lines).extracting(JarvisStateBlock.Line::objectKey).containsExactly("JIRA:S-1", "JIRA:C-1",
                "JIRA:A-1");
    }

    @Test
    @DisplayName("ETAT-10..11 — D8 : un fait de plus d'1 h dit sans heure est daté ; dit avec une heure, inchangé")
    void freshnessGuard() {
        List<JarvisStateBlock.Line> lines = List.of(new JarvisStateBlock.Line(cagip, null, "JIRA:PROJ-123", "PROJ-123",
                "-", NOW.minusHours(14), false, false), new JarvisStateBlock.Line(cagip, null, "JIRA:NEW-2", "NEW-2", "-",
                        NOW.minusMinutes(10), false, false));
        assertThat(JarvisStateBlock.withFreshness("PROJ-123 est en revue.", lines, NOW))
                .isEqualTo("PROJ-123 est en revue (relevé d'hier 18:00).");
        assertThat(JarvisStateBlock.withFreshness("PROJ-123 était en revue hier à 18 h.", lines, NOW))
                .isEqualTo("PROJ-123 était en revue hier à 18 h.");
        assertThat(JarvisStateBlock.withFreshness("NEW-2 est ouvert.", lines, NOW)).isEqualTo("NEW-2 est ouvert.");
        assertThat(JarvisStateBlock.freshnessMark(NOW.minusMinutes(90), NOW)).isEqualTo("relevé de 06:30");
    }

    @Test
    @DisplayName("ETAT-12 — rien de connu : bloc vide ; poste éteint sans fait : seule la ligne de déconnexion")
    void emptyCases() {
        assertThat(JarvisStateBlock.select(new JarvisStateBlock.Prepared(NOW, List.of()), cagip, null, NOW).text())
                .isEmpty();
        JarvisStateBlock.Prepared p = new JarvisStateBlock.Prepared(NOW, List.of(new JarvisStateBlock.Client(cagip,
                "CAGIP", false, NOW.minusHours(14), "INACTIVITE", 0, List.of())));
        assertThat(JarvisStateBlock.select(p, cagip, null, NOW).text())
                .isEqualTo("## État connu — client CAGIP\nPoste déconnecté depuis hier 18:00 (inactivité).");
    }
}
