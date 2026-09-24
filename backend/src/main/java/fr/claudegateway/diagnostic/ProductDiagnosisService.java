package fr.claudegateway.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>De l'enquête au verdict</b> (F-156 / SF-156-03) : pour chaque capacité, « elle tourne »,
 * « elle dort », ou « je ne sais pas ».
 *
 * <p><b>Une capacité dormante ne demande aucun développement</b> : elle demande qu'on s'en
 * aperçoive. C'est le plus gros gisement du diagnostic — trois fois cette semaine.</p>
 *
 * <p><b>Le diagnostic ne retient pas les capacités actives.</b> Elles sont comptées, pas listées :
 * un rapport qui énumère ce qui va bien noie ce qui ne va pas.</p>
 */
@Service
public class ProductDiagnosisService {

    private final TableSignalResolver tables;
    private final WiringInspector wirings;

    public ProductDiagnosisService(TableSignalResolver tables, WiringInspector wirings) {
        this.tables = tables;
        this.wirings = wirings;
    }

    /**
     * Les constats d'une enquête : ce qui dort et ce qu'on n'a pas su trancher.
     *
     * @return les constats retenus, et le nombre de capacités écartées parce qu'elles tournent
     */
    @Transactional(readOnly = true)
    public Diagnosis diagnose(UUID userId, ProductSurvey survey) {
        return diagnose(userId, survey, java.util.Map.of());
    }

    /**
     * Les mêmes constats, <b>enrichis par le code lu</b> (F-157 / SF-157-03).
     *
     * <p>La lecture <b>enrichit, elle ne remplace pas</b> : avec une carte de sources vide, cette
     * méthode rend exactement ce que rendait la précédente.</p>
     *
     * @param sources les fichiers du dépôt déjà lus, par chemin ; vide si aucun dépôt désigné
     */
    @Transactional(readOnly = true)
    public Diagnosis diagnose(UUID userId, ProductSurvey survey,
                              java.util.Map<String, SourceRead> sources) {
        if (survey == null || survey.isEmpty()) {
            return new Diagnosis(List.of(), 0);
        }

        List<CapabilityFinding> findings = new ArrayList<>();
        int active = 0;
        for (CapabilityObservation observation : survey.observations()) {
            CapabilityFinding finding = wirings.inspect(judge(userId, observation), sources);
            if (finding.isFinding()) {
                findings.add(finding);
            } else {
                active++;
            }
        }
        return new Diagnosis(List.copyOf(findings), active);
    }

    private CapabilityFinding judge(UUID userId, CapabilityObservation observation) {
        ProductCapability capability = CapabilityMap.byId(observation.capabilityId()).orElse(null);
        if (capability == null) {
            return finding(observation, null, CapabilityVerdict.INDETERMINEE,
                    "Capacité inconnue de la carte : rien à conclure.");
        }

        if (observation.hits() > 0) {
            return finding(observation, capability, CapabilityVerdict.ACTIVE,
                    "Vue " + observation.hits() + " fois sur "
                            + observation.projectsWithSignal() + " projet(s).");
        }

        if (observation.measurable()) {
            return finding(observation, capability, CapabilityVerdict.DORMANTE,
                    "Aucun déclenchement sur la période, alors que " + observation.projectsObserved()
                            + " projet(s) ont travaillé. La capacité est présente : c'est un "
                            + "branchement à vérifier, pas un développement.");
        }

        // Signal de table : l'application lit son propre état. Une table VIDE prouve que la
        // capacité n'a jamais été alimentée ; une table inconnue ne prouve rien.
        return fromTables(userId, observation, capability);
    }

    private CapabilityFinding fromTables(UUID userId, CapabilityObservation observation,
                                         ProductCapability capability) {
        List<String> unknown = new ArrayList<>();
        long rows = 0;
        boolean resolvedAny = false;

        for (ProductCapability.Signal signal : capability.signals()) {
            if (signal.kind() != ProductCapability.Signal.Kind.TABLE) {
                continue;
            }
            Optional<Long> count = tables.count(signal.value(), userId);
            if (count.isEmpty()) {
                unknown.add(signal.value());
                continue;
            }
            resolvedAny = true;
            rows += count.get();
        }

        if (!resolvedAny) {
            return finding(observation, capability, CapabilityVerdict.INDETERMINEE,
                    "Rien n'a pu être compté ici" + (unknown.isEmpty() ? "" : " (" + String.join(", ",
                            unknown) + ")") + " : on ne conclut pas.");
        }
        if (rows == 0) {
            return finding(observation, capability, CapabilityVerdict.DORMANTE,
                    "La table qui l'alimente est VIDE pour ce compte : la capacité n'a jamais été "
                            + "amorcée. Aucun développement à faire — un branchement à réparer.");
        }
        return finding(observation, capability, CapabilityVerdict.ACTIVE,
                rows + " ligne(s) enregistrée(s) : la capacité est alimentée.");
    }

    private static CapabilityFinding finding(CapabilityObservation observation,
                                             ProductCapability capability,
                                             CapabilityVerdict verdict, String why) {
        return new CapabilityFinding(
                observation.capabilityId(),
                observation.name(),
                verdict,
                why,
                capability == null ? List.of() : capability.paths(),
                capability == null ? null : capability.activates(),
                // Le gain n'est repris que s'il a été CALCULÉ. Jamais une estimation : c'est
                // exactement ce que le seuil d'impact interdit.
                observation.wasteEur());
    }

    /**
     * Ce que le diagnostic a conclu.
     *
     * @param findings les constats retenus — ce qui dort, ce qu'on n'a pas tranché
     * @param active   combien de capacités tournent : comptées, pas listées, pour ne pas noyer
     *                 ce qui ne va pas
     */
    public record Diagnosis(List<CapabilityFinding> findings, int active) {

        /** Vrai quand tout tourne — une conclusion valide, pas un échec. */
        public boolean isClean() {
            return findings.isEmpty();
        }
    }
}
