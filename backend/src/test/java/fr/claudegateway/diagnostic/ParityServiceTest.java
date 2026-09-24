package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * La parité comme mesure (F-156 / SF-156-04).
 *
 * <p>Ce que ces tests tiennent : une référence <b>écartée volontairement</b> n'est jamais comptée
 * comme un manque (sinon le diagnostic la proposerait à chaque rapport), une capacité portée mais
 * <b>dormante</b> ne se confond pas avec une capacité <b>absente</b>, et sans observation on écrit
 * « non observée », jamais « absente ».</p>
 */
class ParityServiceTest {

    private static final OffsetDateTime FROM = OffsetDateTime.parse("2026-09-17T00:00:00Z");
    private static final OffsetDateTime TO = OffsetDateTime.parse("2026-09-24T00:00:00Z");

    private final ParityService service = new ParityService();

    private ProductSurvey observedSurvey() {
        return new ProductSurvey(FROM, TO, false, 20, 3, new BigDecimal("40.00"), List.of());
    }

    private ProductDiagnosisService.Diagnosis diagnosisOf(CapabilityFinding... findings) {
        return new ProductDiagnosisService.Diagnosis(List.of(findings), 0);
    }

    private CapabilityFinding finding(String capabilityId, CapabilityVerdict verdict) {
        return new CapabilityFinding(capabilityId, capabilityId, verdict, "peu importe",
                List.of(), null, null);
    }

    private ParityRow row(ParityService.Parity parity, String referenceId) {
        return parity.rows().stream()
                .filter(r -> r.referenceId().equals(referenceId))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("portée et absente des constats → TENUE : le diagnostic ne liste que ce qui ne va pas")
    void carriedAndAbsentFromFindingsIsHeld() {
        ParityService.Parity parity = service.measure(diagnosisOf(), observedSurvey());

        assertThat(row(parity, "planifier").state()).isEqualTo(ParityRow.State.TENUE);
        assertThat(row(parity, "planifier").note()).isEqualTo("Portée et vue à l'œuvre.");
    }

    @Test
    @DisplayName("portée mais DORMANTE ne se confond pas avec ABSENTE — un branchement, pas une feature")
    void dormantIsNotMissing() {
        ParityService.Parity parity = service.measure(
                diagnosisOf(finding("index-du-depot", CapabilityVerdict.DORMANTE)), observedSurvey());

        ParityRow index = row(parity, "indexer");
        assertThat(index.state()).isEqualTo(ParityRow.State.DORMANTE);
        assertThat(index.isRealGap()).as("dormante n'appelle AUCUN développement").isFalse();
        assertThat(index.note()).contains("branchement à réparer, pas un développement");
    }

    @Test
    @DisplayName("une référence ÉCARTÉE porte sa raison et n'est JAMAIS comptée comme un manque")
    void anExcludedReferenceIsNeverAGap() {
        ParityService.Parity parity = service.measure(diagnosisOf(), observedSurvey());

        ParityRow hooks = row(parity, "hooks");
        assertThat(hooks.state()).isEqualTo(ParityRow.State.ECARTEE);
        assertThat(hooks.isRealGap()).isFalse();
        assertThat(hooks.note()).contains("écartée du périmètre par F-39");
        assertThat(parity.gaps()).as("aucun manque réel dans la liste actuelle").isZero();
    }

    @Test
    @DisplayName("une référence indéterminée devient « non observée », jamais « absente »")
    void anUndeterminedReferenceIsNotMissing() {
        ParityService.Parity parity = service.measure(
                diagnosisOf(finding("memoire-de-resolutions", CapabilityVerdict.INDETERMINEE)),
                observedSurvey());

        ParityRow memory = row(parity, "memoire");
        assertThat(memory.state()).isEqualTo(ParityRow.State.NON_OBSERVEE);
        assertThat(memory.isRealGap()).isFalse();
        assertThat(memory.note()).contains("n'a pas su trancher");
    }

    @Test
    @DisplayName("sans observation, tout ce qui est porté est « non observé » — on ne conclut pas")
    void withoutObservationNothingIsConcluded() {
        ParityService.Parity parity = service.measure(null, ProductSurvey.empty(FROM, TO));

        assertThat(parity.of(ParityRow.State.NON_OBSERVEE))
                .as("toutes les références portées")
                .hasSize((int) ParityReference.references().stream()
                        .filter(ReferenceCapability::isCarried).count());
        assertThat(parity.of(ParityRow.State.ABSENTE)).isEmpty();
        assertThat(row(parity, "hooks").state())
                .as("une exclusion reste une exclusion, observée ou non")
                .isEqualTo(ParityRow.State.ECARTEE);
    }

    @Test
    @DisplayName("un manque réel est compté, et c'est le chiffre qui appelle des features")
    void arealGapIsCounted() {
        ReferenceCapability missing = ReferenceCapability.missing("inventee", "Inventée", "rien");

        assertThat(missing.isCarried()).isFalse();
        assertThat(missing.isExcluded()).isFalse();

        // La liste livrée n'a aucun manque : c'est un résultat, pas un oubli.
        assertThat(service.measure(diagnosisOf(), observedSurvey()).gaps()).isZero();
    }

    @Test
    @DisplayName("la table couvre toute la liste de référence, dans un ordre stable")
    void everyReferenceIsCovered() {
        ParityService.Parity parity = service.measure(diagnosisOf(), observedSurvey());

        assertThat(parity.rows()).hasSameSizeAs(ParityReference.references());
        assertThat(parity.rows()).extracting(ParityRow::referenceId)
                .containsExactlyElementsOf(
                        ParityReference.references().stream().map(ReferenceCapability::id).toList());
    }
}
