package fr.claudegateway.diagnostic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.bilan.SessionBilanProperties;

/**
 * Le diagnostic de bout en bout (F-156 / SF-156-05).
 *
 * <p>Ce que ces tests tiennent : <b>le seuil s'applique là où il a un sens</b> — un constat chiffré
 * doit le dépasser, un constat sans chiffre est gardé mais n'annonce rien —, la durée est bornée
 * <b>et le rapport le dit</b>, et la ligne de spec sort au statut <b>Candidate</b> avec sa preuve.</p>
 */
class ProductDiagnosticServiceTest {

    private final ProductSurveyService surveys = mock(ProductSurveyService.class);
    private final ProductDiagnosisService diagnoses = mock(ProductDiagnosisService.class);
    private final ParityService parity = new ParityService();
    private final SourceReader sources = mock(SourceReader.class);
    private final ReasonedReader reasoned = mock(ReasonedReader.class);

    private final UUID userId = UUID.randomUUID();

    private ProductDiagnosticService service;

    @BeforeEach
    void setUp() {
        service = new ProductDiagnosticService(surveys, diagnoses, parity,
                SessionBilanProperties.defaults(), sources, reasoned); // seuil 10 %
    }

    private ProductSurvey survey(String costEur, boolean truncated) {
        OffsetDateTime to = OffsetDateTime.now();
        return new ProductSurvey(to.minusDays(7), to, truncated, 40, 3, new BigDecimal(costEur),
                List.of());
    }

    private CapabilityFinding finding(String id, CapabilityVerdict verdict, String gainEur) {
        return new CapabilityFinding(id, id, verdict, "constat mesuré",
                List.of("backend/src/main/java/fr/claudegateway/atelier/AtelierPlan.java"),
                "la condition attendue",
                gainEur == null ? null : new BigDecimal(gainEur));
    }

    private void given(ProductSurvey survey, CapabilityFinding... findings) {
        when(surveys.survey(eq(userId), any(), any())).thenReturn(survey);
        when(diagnoses.diagnose(eq(userId), eq(survey), any()))
                .thenReturn(new ProductDiagnosisService.Diagnosis(List.of(findings), 4));
    }

    @Test
    @DisplayName("un constat CHIFFRÉ sous le seuil est écarté ET COMPTÉ ; au-dessus il est retenu")
    void theThresholdAppliesToComputedGains() {
        given(survey("100.00", false),
                finding("cache-de-prompt", CapabilityVerdict.DORMANTE, "25.00"),  // 25 % → retenu
                finding("index-du-depot", CapabilityVerdict.DORMANTE, "3.00"));   // 3 %  → écarté

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.findings()).extracting(CapabilityFinding::capabilityId)
                .containsExactly("cache-de-prompt");
        assertThat(report.discarded()).isEqualTo(1);
    }

    @Test
    @DisplayName("un constat SANS chiffre est gardé — gratuit à vérifier — mais n'annonce AUCUN gain")
    void anUncomputedFindingIsKeptWithoutANumber() {
        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.findings()).hasSize(1);
        assertThat(report.findings().get(0).gainEur()).isNull();
        assertThat(report.discarded()).isZero();
        assertThat(report.specLines().get(0)).contains("Aucun gain chiffré");
    }

    @Test
    @DisplayName("sans dénominateur, aucun constat chiffré ne peut être affirmé")
    void withoutADenominatorNothingIsAffirmed() {
        given(survey("0.00", false), finding("cache-de-prompt", CapabilityVerdict.DORMANTE, "5.00"));

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.findings()).isEmpty();
        assertThat(report.discarded()).isEqualTo(1);
    }

    @Test
    @DisplayName("la ligne de spec sort au statut Candidate, avec sa preuve chiffrée et l'endroit")
    void theSpecLineCarriesItsProof() {
        given(survey("100.00", false),
                finding("cache-de-prompt", CapabilityVerdict.DORMANTE, "25.00"));

        String line = service.run(userId, 7).specLines().get(0);

        assertThat(line)
                .contains("**Candidate** — proposée par le diagnostic, non cadrée")
                .contains("Proposé par le diagnostic du produit")
                .contains("capacité présente mais jamais déclenchée")
                .contains("**Gain calculé : 25.00 € sur la période**")
                .contains("À vérifier : la condition attendue")
                .contains("AtelierPlan.java");
    }

    @Test
    @DisplayName("la durée est bornée, et une correction est DITE dans le rapport")
    void theDurationIsBoundedAndSaysSo() {
        assertThat(ProductDiagnosticService.boundedDays(null))
                .isEqualTo(ProductDiagnosticService.DEFAULT_DAYS);
        assertThat(ProductDiagnosticService.boundedDays(0))
                .isEqualTo(ProductDiagnosticService.MIN_DAYS);
        assertThat(ProductDiagnosticService.boundedDays(999))
                .isEqualTo(ProductDiagnosticService.MAX_DAYS);
        assertThat(ProductDiagnosticService.boundedDays(-4))
                .isEqualTo(ProductDiagnosticService.MIN_DAYS);

        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));
        assertThat(service.run(userId, 999).truncated()).isTrue();
        assertThat(service.run(userId, 7).truncated()).isFalse();
    }

    @Test
    @DisplayName("une période sans matière ne produit AUCUN constat, et rend quand même la parité")
    void anEmptyPeriodInventsNothing() {
        OffsetDateTime to = OffsetDateTime.now();
        when(surveys.survey(eq(userId), any(), any()))
                .thenReturn(ProductSurvey.empty(to.minusDays(7), to));

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.findings()).isEmpty();
        assertThat(report.specLines()).isEmpty();
        assertThat(report.isClean()).isTrue();
        assertThat(report.parity())
                .as("la parité reste lisible : toutes les références, en « non observée »")
                .hasSameSizeAs(ParityReference.references());
    }

    @Test
    @DisplayName("le rapport porte le dénominateur et le compte des capacités qui tournent")
    void theReportCarriesTheDenominator() {
        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.turns()).isEqualTo(40);
        assertThat(report.projects()).isEqualTo(3);
        assertThat(report.costEur()).isEqualByComparingTo("100.00");
        assertThat(report.active()).as("comptées, pas listées").isEqualTo(4);
    }

    // --- F-157 / SF-157-05 : la lecture du code, facultative -----------------------------------

    @Test
    @DisplayName("SANS projet désigné, le rapport est celui de F-156 — aucune lecture, aucun coût")
    void withoutARepositoryNothingIsRead() {
        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));

        DiagnosticReport report = service.run(userId, 7);

        assertThat(report.sourceRead()).isFalse();
        assertThat(report.sourceNote()).isNull();
        org.mockito.Mockito.verify(sources, org.mockito.Mockito.never()).read(any(), any());
    }

    @Test
    @DisplayName("un dépôt NON RECONNU ne prive pas du diagnostic gratuit : le rapport est rendu, avec le mot")
    void anUnrecognizedRepositoryStillYieldsTheReport() {
        UUID repo = UUID.randomUUID();
        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));
        when(sources.read(userId, repo))
                .thenThrow(new RepositoryNotRecognizedException("Ce projet n'est pas le dépôt."));

        DiagnosticReport report = service.run(userId, 7, repo);

        assertThat(report.findings()).hasSize(1);
        assertThat(report.sourceRead()).isFalse();
        assertThat(report.sourceNote()).isEqualTo("Ce projet n'est pas le dépôt.");
    }

    @Test
    @DisplayName("avec un dépôt lu, le code est transmis au diagnostic et le rapport le dit")
    void withARepositoryTheCodeReachesTheDiagnosis() {
        UUID repo = UUID.randomUUID();
        java.util.Map<String, SourceRead> code =
                java.util.Map.of("a.java", SourceRead.read("a.java", "du code"));
        given(survey("100.00", false), finding("plan", CapabilityVerdict.DORMANTE, null));
        when(sources.read(userId, repo)).thenReturn(code);

        DiagnosticReport report = service.run(userId, 7, repo);

        assertThat(report.sourceRead()).isTrue();
        assertThat(report.sourceNote()).contains("1 fichiers du dépôt lus");
        org.mockito.Mockito.verify(diagnoses).diagnose(eq(userId), any(), eq(code));
    }

    @Test
    @DisplayName("l'hypothèse passe par le lecteur raisonné, sur la capacité demandée")
    void theHypothesisGoesThroughTheReasonedReader() {
        UUID repo = UUID.randomUUID();
        java.util.Map<String, SourceRead> code =
                java.util.Map.of("a.java", SourceRead.read("a.java", "du code"));
        when(sources.read(userId, repo)).thenReturn(code);
        when(reasoned.read(any(), eq(code))).thenReturn(java.util.Optional.of(
                new SourceHypothesis("plan", "il manque X", false, "claude-opus-5", 100, 20)));

        assertThat(service.explain(userId, repo, "plan"))
                .get()
                .extracting(SourceHypothesis::text)
                .isEqualTo("il manque X");
    }

    @Test
    @DisplayName("une capacité inconnue ne lance AUCUNE lecture raisonnée — donc aucun coût")
    void anUnknownCapabilityCostsNothing() {
        UUID repo = UUID.randomUUID();
        when(sources.read(userId, repo)).thenReturn(java.util.Map.of());

        assertThat(service.explain(userId, repo, "inventée")).isEmpty();
        org.mockito.Mockito.verify(reasoned, org.mockito.Mockito.never()).read(any(), any());
    }

    @Test
    @DisplayName("une troncature venue de l'enquête est reportée telle quelle")
    void aSurveyTruncationIsCarried() {
        given(survey("100.00", true), finding("plan", CapabilityVerdict.DORMANTE, null));
        assertThat(service.run(userId, 7).truncated()).isTrue();
    }
}
