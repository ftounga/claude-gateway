package fr.claudegateway.diagnostic;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.bilan.SessionBilanProperties;

/**
 * <b>Le diagnostic du produit, de bout en bout</b> (F-156 / SF-156-05) : enquête → verdicts →
 * parité → seuil → lignes de spec.
 *
 * <p><b>Le seuil s'applique là où il a un sens.</b> Un constat qui porte un <b>gain calculé</b> doit
 * le dépasser, sinon il est écarté et compté. Un constat <b>sans gain</b> — une capacité dormante —
 * est gardé : le vérifier ne coûte rien, et c'est précisément le gisement. Mais il n'annonce
 * <b>aucun chiffre</b>. On ne chiffre pas ce qu'on n'a pas mesuré, et on ne jette pas ce qui est
 * gratuit à vérifier.</p>
 *
 * <p><b>L'auto-modification est écartée</b> : le rapport <b>écrit la ligne</b>, l'administrateur la
 * colle. Une machine qui modifie son propre code sans décision humaine n'est pas un gain de
 * productivité, c'est une perte de contrôle.</p>
 */
@Service
public class ProductDiagnosticService {

    /** Bornes de la durée demandée. En deçà il n'y a rien à voir, au-delà c'est un export. */
    static final int MIN_DAYS = 1;
    static final int MAX_DAYS = 31;
    static final int DEFAULT_DAYS = 7;

    private final ProductSurveyService surveys;
    private final ProductDiagnosisService diagnoses;
    private final ParityService parity;
    private final SessionBilanProperties settings;
    private final SourceReader sources;
    private final ReasonedReader reasoned;

    public ProductDiagnosticService(ProductSurveyService surveys, ProductDiagnosisService diagnoses,
                                    ParityService parity, SessionBilanProperties settings,
                                    SourceReader sources, ReasonedReader reasoned) {
        this.surveys = surveys;
        this.diagnoses = diagnoses;
        this.parity = parity;
        this.settings = settings;
        this.sources = sources;
        this.reasoned = reasoned;
    }

    /** La durée demandée, ramenée aux bornes. */
    static int boundedDays(Integer days) {
        if (days == null) {
            return DEFAULT_DAYS;
        }
        return Math.max(MIN_DAYS, Math.min(MAX_DAYS, days));
    }

    /** Le diagnostic d'une période, à la demande. */
    @Transactional(readOnly = true)
    public DiagnosticReport run(UUID userId, Integer requestedDays) {
        return run(userId, requestedDays, null);
    }

    /**
     * Le diagnostic, éventuellement <b>enrichi par la lecture du code</b> (F-157 / SF-157-05).
     *
     * <p><b>Un refus de lecture ne prive pas du diagnostic gratuit</b> : si le projet désigné n'est
     * pas le dépôt, le rapport est rendu quand même, avec le mot qui le dit.</p>
     *
     * @param repositoryWorkspaceId le terminal ouvert sur le dépôt, ou {@code null}
     */
    @Transactional(readOnly = true)
    public DiagnosticReport run(UUID userId, Integer requestedDays, UUID repositoryWorkspaceId) {
        int days = boundedDays(requestedDays);
        boolean corrected = requestedDays != null && requestedDays != days;

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minus(Duration.ofDays(days));

        // La lecture du code d'abord : elle cadre les verdicts. Son échec est AVALÉ et DIT.
        Map<String, SourceRead> code = Map.of();
        String sourceNote = null;
        if (repositoryWorkspaceId != null) {
            try {
                code = sources.read(userId, repositoryWorkspaceId);
                sourceNote = code.values().stream().filter(SourceRead::isRead).count()
                        + " fichiers du dépôt lus : les constats sont enrichis.";
            } catch (RepositoryNotRecognizedException e) {
                sourceNote = e.getMessage();
            }
        }
        boolean read = !code.isEmpty();

        ProductSurvey survey = surveys.survey(userId, from, to);
        if (survey.isEmpty()) {
            return DiagnosticReport.nothingToObserve(from, to, parity.measure(null, survey).rows())
                    .withSource(read, sourceNote);
        }

        ProductDiagnosisService.Diagnosis diagnosis = diagnoses.diagnose(userId, survey, code);
        ParityService.Parity table = parity.measure(diagnosis, survey);

        List<CapabilityFinding> kept = new ArrayList<>();
        int discarded = 0;
        for (CapabilityFinding finding : diagnosis.findings()) {
            if (finding.gainEur() == null) {
                kept.add(finding); // gratuit à vérifier : on ne jette pas, et on n'annonce rien
                continue;
            }
            if (passesThreshold(finding.gainEur(), survey.costEur())) {
                kept.add(finding);
            } else {
                discarded++;
            }
        }

        return new DiagnosticReport(survey.from(), survey.to(),
                survey.truncated() || corrected,
                survey.turns(), survey.projects(), survey.costEur(),
                List.copyOf(kept), discarded, diagnosis.active(), table.rows(),
                specLines(kept, survey))
                .withSource(read, sourceNote);
    }

    /**
     * Une <b>hypothèse</b> sur une capacité, tirée de la lecture de son code (F-157 / SF-157-04).
     *
     * <p><b>C'est la seule opération du diagnostic qui consomme des jetons.</b> Une capacité à la
     * fois, à la demande.</p>
     *
     * @return l'hypothèse, ou vide — dépôt non reconnu, rien à lire, ou fournisseur indisponible
     */
    @Transactional(readOnly = true)
    public java.util.Optional<SourceHypothesis> explain(UUID userId, UUID repositoryWorkspaceId,
                                                        String capabilityId) {
        Map<String, SourceRead> code = sources.read(userId, repositoryWorkspaceId);
        CapabilityFinding finding = CapabilityMap.byId(capabilityId)
                .map(c -> new CapabilityFinding(c.id(), c.name(), CapabilityVerdict.INDETERMINEE,
                        "Lecture demandée par l'administrateur.", c.paths(), c.activates(), null))
                .orElse(null);
        if (finding == null) {
            return java.util.Optional.empty();
        }
        return reasoned.read(finding, code);
    }

    /** Le gain calculé pèse-t-il assez, rapporté au coût de la période ? */
    private boolean passesThreshold(BigDecimal gain, BigDecimal periodCost) {
        if (periodCost == null || periodCost.signum() <= 0) {
            return false; // aucun dénominateur : on ne peut rien affirmer
        }
        int pct = gain.multiply(BigDecimal.valueOf(100))
                .divide(periodCost, 0, RoundingMode.HALF_UP)
                .intValue();
        return pct >= settings.impactThresholdPct();
    }

    /**
     * Les lignes prêtes à coller dans {@code PRODUCT_SPEC.md}, au statut {@code Candidate},
     * <b>avec leur preuve chiffrée</b>. L'application propose ; le PO décide.
     */
    private static List<String> specLines(List<CapabilityFinding> findings, ProductSurvey survey) {
        List<String> lines = new ArrayList<>();
        for (CapabilityFinding finding : findings) {
            StringBuilder line = new StringBuilder("| F-??? | ")
                    .append(finding.name())
                    .append(" — ")
                    .append(finding.verdict() == CapabilityVerdict.DORMANTE
                            ? "capacité présente mais jamais déclenchée"
                            : "à trancher")
                    .append(" | **Proposé par le diagnostic du produit** (F-156), période du ")
                    .append(survey.from().toLocalDate()).append(" au ").append(survey.to().toLocalDate())
                    .append(" : ").append(finding.why());
            if (finding.gainEur() != null) {
                line.append(" **Gain calculé : ").append(finding.gainEur()).append(" € sur la période**, ")
                        .append("pour un coût total de ").append(survey.costEur()).append(" €.");
            } else {
                line.append(" **Aucun gain chiffré** : le constat est qualitatif, sa vérification est "
                        + "gratuite.");
            }
            if (finding.check() != null) {
                line.append(" À vérifier : ").append(finding.check());
            }
            if (!finding.where().isEmpty()) {
                line.append(" Où : ").append(String.join(", ", finding.where())).append(".");
            }
            line.append(" | **Candidate** — proposée par le diagnostic, non cadrée |");
            lines.add(line.toString());
        }
        return List.copyOf(lines);
    }
}
