package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le verdict « débranchée » (F-157 / SF-157-03).
 *
 * <p>Ce que ces tests tiennent : <b>la lecture enrichit, elle ne remplace pas</b> — sans fichiers,
 * rien ne change —, un fichier <b>non lu ne prouve rien</b>, et un constat DÉBRANCHÉE <b>ne porte
 * aucun gain chiffré</b> : c'est un défaut à corriger, pas une optimisation à évaluer.</p>
 */
class WiringInspectorTest {

    private final WiringInspector inspector = new WiringInspector();

    /** Le vrai témoin du plan, tel que la carte le déclare. */
    private ProductCapability.Wiring planWiring() {
        return CapabilityMap.byId("plan").orElseThrow().wirings().get(0);
    }

    private CapabilityFinding dormant(String capabilityId) {
        return new CapabilityFinding(capabilityId, capabilityId, CapabilityVerdict.DORMANTE,
                "Aucun déclenchement sur la période.", List.of("un/chemin.java"),
                "la condition attendue", new BigDecimal("12.00"));
    }

    @Test
    @DisplayName("SANS fichiers lus, RIEN ne change — la lecture enrichit, elle ne remplace pas")
    void withoutSourcesNothingChanges() {
        CapabilityFinding before = dormant("plan");

        assertThat(inspector.inspect(before, Map.of())).isSameAs(before);
        assertThat(inspector.inspect(before, null)).isSameAs(before);
    }

    @Test
    @DisplayName("tous les témoins présents → reste DORMANTE, et le constat dit que le branchement est vérifié")
    void allWiringsPresentStaysDormant() {
        ProductCapability.Wiring wiring = planWiring();
        Map<String, SourceRead> sources = Map.of(wiring.path(),
                SourceRead.read(wiring.path(), "du code ... " + wiring.fragment() + " ... suite"));

        CapabilityFinding after = inspector.inspect(dormant("plan"), sources);

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.DORMANTE);
        assertThat(after.why())
                .contains("Branchement vérifié dans le code")
                .contains("c'est une condition qui n'est pas remplie");
        assertThat(after.gainEur()).as("le gain calculé est conservé").isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("un témoin ABSENT → DÉBRANCHÉE, avec le fragment, le fichier et ce qu'il prouvait")
    void aMissingWiringMeansUnwired() {
        ProductCapability.Wiring wiring = planWiring();
        Map<String, SourceRead> sources = Map.of(wiring.path(),
                SourceRead.read(wiring.path(), "du code d'où l'appel a disparu"));

        CapabilityFinding after = inspector.inspect(dormant("plan"), sources);

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.DEBRANCHEE);
        assertThat(after.why())
                .contains("DÉBRANCHÉE")
                .contains(wiring.fragment())
                .contains(wiring.path())
                .contains(wiring.proves())
                .contains("rien n'a cassé, aucun test n'est tombé");
        assertThat(after.where()).containsExactly(wiring.path());
    }

    @Test
    @DisplayName("un constat DÉBRANCHÉE ne porte AUCUN gain — c'est un défaut, pas une optimisation")
    void anUnwiredFindingCarriesNoGain() {
        ProductCapability.Wiring wiring = planWiring();
        Map<String, SourceRead> sources = Map.of(wiring.path(),
                SourceRead.read(wiring.path(), "l'appel a disparu"));

        assertThat(inspector.inspect(dormant("plan"), sources).gainEur())
                .as("chiffrer un défaut reviendrait à chiffrer une hypothèse")
                .isNull();
    }

    @Test
    @DisplayName("un fichier NON LU ne prouve rien : le verdict est inchangé, et le dit")
    void anUnreadFileProvesNothing() {
        ProductCapability.Wiring wiring = planWiring();
        Map<String, SourceRead> sources = Map.of(wiring.path(),
                SourceRead.absent(wiring.path(), "runner injoignable"));

        CapabilityFinding after = inspector.inspect(dormant("plan"), sources);

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.DORMANTE);
        assertThat(after.why()).contains("Branchement non vérifiable");
        assertThat(after.gainEur()).isEqualByComparingTo("12.00");
    }

    @Test
    @DisplayName("une capacité ACTIVE n'est jamais réexaminée : elle se déclenche, donc elle est branchée")
    void anActiveCapabilityIsNeverReexamined() {
        CapabilityFinding active = new CapabilityFinding("plan", "plan", CapabilityVerdict.ACTIVE,
                "Vue 4 fois.", List.of(), null, null);
        ProductCapability.Wiring wiring = planWiring();

        CapabilityFinding after = inspector.inspect(active,
                Map.of(wiring.path(), SourceRead.read(wiring.path(), "l'appel a disparu")));

        assertThat(after).isSameAs(active);
    }

    @Test
    @DisplayName("une capacité sans témoin : verdict inchangé, et le constat dit qu'on n'a pas pu vérifier")
    void aCapabilityWithoutWiringSaysSo() {
        // « sous-agents » ne déclare aucun témoin.
        assertThat(CapabilityMap.byId("sous-agents").orElseThrow().wirings()).isEmpty();

        CapabilityFinding after = inspector.inspect(dormant("sous-agents"),
                Map.of("un/fichier.java", SourceRead.read("un/fichier.java", "du code")));

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.DORMANTE);
        assertThat(after.why()).contains("Branchement non vérifiable");
    }

    /** Le constat tel que le diagnostic le produit pour une capacité non mesurable. */
    private CapabilityFinding unmeasurable(String capabilityId) {
        return new CapabilityFinding(capabilityId, capabilityId, CapabilityVerdict.INDETERMINEE,
                "Rien à mesurer : sa réussite est un événement qui n'a pas lieu.",
                List.of("un/chemin.java"), "la condition attendue", null);
    }

    /** Tous les fichiers d'une capacité, lus avec leurs fragments présents. */
    private Map<String, SourceRead> allWiringsPresent(String capabilityId) {
        return CapabilityMap.byId(capabilityId).orElseThrow().wirings().stream()
                .collect(java.util.stream.Collectors.toMap(
                        ProductCapability.Wiring::path,
                        w -> SourceRead.read(w.path(), "avant " + w.fragment() + " après"),
                        (a, b) -> SourceRead.read(a.path(), a.content() + "\n" + b.content())));
    }

    @Test
    @DisplayName("F-161 : NON MESURABLE + tous les témoins présents → BRANCHÉE, et sort des constats")
    void anUnmeasurableCapabilityWithItsWiringsIsInPlace() {
        assertThat(CapabilityMap.byId("porte-du-runner").orElseThrow().isMeasurable())
                .as("la porte ne peut rien émettre : sa réussite est un tour qui ne s'ouvre pas")
                .isFalse();

        CapabilityFinding after = inspector.inspect(unmeasurable("porte-du-runner"),
                allWiringsPresent("porte-du-runner"));

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.BRANCHEE);
        assertThat(after.isFinding())
                .as("une capacité en place n'est pas un problème à lister")
                .isFalse();
        assertThat(after.why())
                .contains("Branchement vérifié dans le code")
                .contains("Rien à mesurer");
        assertThat(after.gainEur()).isNull();
    }

    @Test
    @DisplayName("F-161 : NON MESURABLE + un témoin absent → DÉBRANCHÉE, comme pour les autres")
    void anUnmeasurableCapabilityUnwiredIsStillUnwired() {
        ProductCapability capability = CapabilityMap.byId("porte-du-runner").orElseThrow();
        ProductCapability.Wiring callSite = capability.wirings().stream()
                .filter(w -> w.path().endsWith("AtelierChatService.java"))
                .findFirst().orElseThrow();
        Map<String, SourceRead> sources = new java.util.HashMap<>(
                allWiringsPresent("porte-du-runner"));
        sources.put(callSite.path(),
                SourceRead.read(callSite.path(), "la boucle du tour, sans l'appel à la porte"));

        CapabilityFinding after = inspector.inspect(unmeasurable("porte-du-runner"), sources);

        assertThat(after.verdict()).isEqualTo(CapabilityVerdict.DEBRANCHEE);
        assertThat(after.isFinding()).isTrue();
        assertThat(after.why()).contains(callSite.fragment()).contains(callSite.proves());
    }

    @Test
    @DisplayName("F-161 : le journal des ruptures est jugé sur SES DEUX transports")
    void theDisconnectJournalIsJudgedOnBothTransports() {
        assertThat(inspector.inspect(unmeasurable("journal-des-ruptures"),
                allWiringsPresent("journal-des-ruptures")).verdict())
                .isEqualTo(CapabilityVerdict.BRANCHEE);

        ProductCapability.Wiring socket = CapabilityMap.byId("journal-des-ruptures").orElseThrow()
                .wirings().stream()
                .filter(w -> w.path().endsWith("RunnerWebSocketHandler.java"))
                .findFirst().orElseThrow();
        Map<String, SourceRead> halfBroken = new java.util.HashMap<>(
                allWiringsPresent("journal-des-ruptures"));
        halfBroken.put(socket.path(), SourceRead.read(socket.path(), "la socket se ferme en silence"));

        assertThat(inspector.inspect(unmeasurable("journal-des-ruptures"), halfBroken).verdict())
                .as("une rupture perdue sur UN SEUL transport suffit à mutiler la mesure")
                .isEqualTo(CapabilityVerdict.DEBRANCHEE);
    }

    @Test
    @DisplayName("NON-RÉGRESSION : une capacité MESURABLE aux témoins présents garde son verdict")
    void aMeasurableCapabilityKeepsItsVerdict() {
        ProductCapability.Wiring wiring = planWiring();
        Map<String, SourceRead> sources = Map.of(wiring.path(),
                SourceRead.read(wiring.path(), "du code ... " + wiring.fragment()));

        assertThat(inspector.inspect(dormant("plan"), sources).verdict())
                .as("BRANCHÉE ne doit jamais masquer une capacité qui, elle, pouvait se mesurer")
                .isEqualTo(CapabilityVerdict.DORMANTE);
    }

    @Test
    @DisplayName("une capacité inconnue de la carte est laissée telle quelle")
    void anUnmappedCapabilityIsUntouched() {
        CapabilityFinding orphan = dormant("inventée");
        assertThat(inspector.inspect(orphan,
                Map.of("x", SourceRead.read("x", "du code")))).isSameAs(orphan);
    }
}
