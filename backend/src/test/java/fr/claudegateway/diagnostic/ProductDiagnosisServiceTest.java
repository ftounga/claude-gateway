package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Le verdict : active, dormante, indéterminée (F-156 / SF-156-03).
 *
 * <p>Ce que ces tests tiennent : une table <b>vide</b> prouve la dormance, une table <b>inconnue</b>
 * ne prouve <b>rien</b>, un constat dormant dit <b>où regarder</b>, et les capacités qui tournent
 * sont comptées plutôt que listées.</p>
 */
class ProductDiagnosisServiceTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-24T00:00:00Z");

    private final TableSignalResolver tables = mock(TableSignalResolver.class);
    private final UUID userId = UUID.randomUUID();

    private ProductDiagnosisService service;

    @BeforeEach
    void setUp() {
        service = new ProductDiagnosisService(tables);
    }

    private CapabilityObservation observation(String id, long hits, boolean measurable,
                                              BigDecimal waste) {
        String name = CapabilityMap.byId(id).map(ProductCapability::name).orElse(id);
        return new CapabilityObservation(id, name, hits, hits > 0 ? 1 : 0, 3, waste, measurable);
    }

    private ProductSurvey surveyOf(CapabilityObservation... observations) {
        return new ProductSurvey(FROM, TO, false, 20, 3, new BigDecimal("40.00"),
                List.of(observations));
    }

    private CapabilityFinding finding(ProductDiagnosisService.Diagnosis diagnosis, String id) {
        return diagnosis.findings().stream()
                .filter(f -> f.capabilityId().equals(id))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("signal vu → ACTIVE, et la capacité est COMPTÉE plutôt que listée")
    void aSeenSignalIsActiveAndCounted() {
        ProductDiagnosisService.Diagnosis diagnosis =
                service.diagnose(userId, surveyOf(observation("plan", 4, true, null)));

        assertThat(diagnosis.findings()).isEmpty();
        assertThat(diagnosis.active()).isEqualTo(1);
        assertThat(diagnosis.isClean()).isTrue();
    }

    @Test
    @DisplayName("aucun signal sur une capacité présente → DORMANTE, avec l'endroit ET la condition")
    void noSignalOnAPresentCapabilityIsDormant() {
        ProductDiagnosisService.Diagnosis diagnosis =
                service.diagnose(userId, surveyOf(observation("plan", 0, true, null)));

        CapabilityFinding plan = finding(diagnosis, "plan");
        assertThat(plan.verdict()).isEqualTo(CapabilityVerdict.DORMANTE);
        assertThat(plan.why()).contains("Aucun déclenchement").contains("branchement à vérifier");
        assertThat(plan.where()).as("où aller regarder")
                .contains("backend/src/main/java/fr/claudegateway/atelier/AtelierPlan.java");
        assertThat(plan.check()).as("ce qu'il faut vérifier").contains("set_plan");
    }

    @Test
    @DisplayName("une table VIDE prouve la dormance — c'est ce qui est arrivé trois fois cette semaine")
    void anEmptyTableProvesDormancy() {
        when(tables.count(eq("resolution_memory"), eq(userId))).thenReturn(Optional.of(0L));

        CapabilityFinding memory = finding(
                service.diagnose(userId, surveyOf(observation("memoire-de-resolutions", 0, false, null))),
                "memoire-de-resolutions");

        assertThat(memory.verdict()).isEqualTo(CapabilityVerdict.DORMANTE);
        assertThat(memory.why()).contains("VIDE").contains("jamais été amorcée");
    }

    @Test
    @DisplayName("une table PLEINE dit que la capacité est alimentée")
    void aFilledTableIsActive() {
        when(tables.count(eq("resolution_memory"), eq(userId))).thenReturn(Optional.of(42L));

        ProductDiagnosisService.Diagnosis diagnosis =
                service.diagnose(userId, surveyOf(observation("memoire-de-resolutions", 0, false, null)));

        assertThat(diagnosis.findings()).isEmpty();
        assertThat(diagnosis.active()).isEqualTo(1);
    }

    @Test
    @DisplayName("une table INCONNUE ne prouve RIEN : indéterminée, jamais dormante par défaut")
    void anUnknownTableProvesNothing() {
        when(tables.count(any(), eq(userId))).thenReturn(Optional.empty());

        CapabilityFinding memory = finding(
                service.diagnose(userId, surveyOf(observation("memoire-de-resolutions", 0, false, null))),
                "memoire-de-resolutions");

        assertThat(memory.verdict())
                .as("conclure par défaut ferait annoncer des dormantes qui tournent très bien")
                .isEqualTo(CapabilityVerdict.INDETERMINEE);
        assertThat(memory.why()).contains("on ne conclut pas");
    }

    @Test
    @DisplayName("le gain n'est repris QUE s'il a été calculé — jamais une estimation")
    void theGainIsOnlyCarriedWhenComputed() {
        ProductDiagnosisService.Diagnosis withGain = service.diagnose(userId,
                surveyOf(observation("cache-de-prompt", 0, true, new BigDecimal("12.15"))));
        assertThat(finding(withGain, "cache-de-prompt").gainEur()).isEqualByComparingTo("12.15");

        ProductDiagnosisService.Diagnosis without = service.diagnose(userId,
                surveyOf(observation("plan", 0, true, null)));
        assertThat(finding(without, "plan").gainEur()).isNull();
    }

    @Test
    @DisplayName("une capacité inconnue de la carte ne fait pas conclure : indéterminée")
    void anUnmappedCapabilityIsUndetermined() {
        CapabilityFinding orphan = finding(
                service.diagnose(userId, surveyOf(observation("inventée", 0, true, null))),
                "inventée");

        assertThat(orphan.verdict()).isEqualTo(CapabilityVerdict.INDETERMINEE);
        assertThat(orphan.where()).isEmpty();
    }

    @Test
    @DisplayName("une enquête vide ou nulle ne produit aucun constat, sans exception")
    void anEmptySurveyProducesNothing() {
        assertThat(service.diagnose(userId, null).findings()).isEmpty();
        assertThat(service.diagnose(userId, ProductSurvey.empty(FROM, TO)).isClean()).isTrue();
    }

    @Test
    @DisplayName("ISOLATION — chaque comptage de table porte le user_id")
    void everyCountCarriesTheAccount() {
        when(tables.count(any(), eq(userId))).thenReturn(Optional.of(0L));

        service.diagnose(userId, surveyOf(observation("index-du-depot", 0, false, null)));

        org.mockito.Mockito.verify(tables).count("repo_index_paths", userId);
    }

    @Test
    @DisplayName("un diagnostic mêle constats et comptage : ce qui dort listé, ce qui tourne compté")
    void findingsAndCountsCoexist() {
        when(tables.count(any(), eq(userId))).thenReturn(Optional.of(0L));

        ProductDiagnosisService.Diagnosis diagnosis = service.diagnose(userId, surveyOf(
                observation("plan", 5, true, null),
                observation("compaction", 3, true, null),
                observation("cache-de-prompt", 0, true, new BigDecimal("9.00")),
                observation("index-du-depot", 0, false, null)));

        assertThat(diagnosis.active()).isEqualTo(2);
        assertThat(diagnosis.findings()).extracting(CapabilityFinding::capabilityId)
                .containsExactly("cache-de-prompt", "index-du-depot");
    }
}
