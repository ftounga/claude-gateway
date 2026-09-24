package fr.claudegateway.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

/**
 * <b>La parité comme mesure</b> (F-156 / SF-156-04) : pour chaque capacité de référence,
 * <i>présente ?</i> et <i>déclenchée ?</i> — <b>le diagnostic naît de l'écart entre ces deux
 * colonnes</b>.
 *
 * <p>Elle ne relit rien : elle <b>croise</b> une liste déclarée avec un diagnostic déjà produit
 * sous isolation (SF-156-03).</p>
 */
@Service
public class ParityService {

    /**
     * La parité, ligne par ligne.
     *
     * @param diagnosis le diagnostic de la période, ou {@code null} quand rien n'a été observé
     */
    public Parity measure(ProductDiagnosisService.Diagnosis diagnosis, ProductSurvey survey) {
        Map<String, CapabilityVerdict> verdicts = diagnosis == null ? Map.of()
                : diagnosis.findings().stream().collect(Collectors.toMap(
                        CapabilityFinding::capabilityId, CapabilityFinding::verdict,
                        (a, b) -> a));
        boolean observed = survey != null && !survey.isEmpty();

        List<ParityRow> rows = new ArrayList<>();
        for (ReferenceCapability reference : ParityReference.references()) {
            rows.add(row(reference, verdicts, observed));
        }
        int gaps = (int) rows.stream().filter(ParityRow::isRealGap).count();
        return new Parity(List.copyOf(rows), gaps);
    }

    private static ParityRow row(ReferenceCapability reference,
                                 Map<String, CapabilityVerdict> verdicts, boolean observed) {
        if (reference.isExcluded()) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.ECARTEE, reference.excludedBecause());
        }
        if (!reference.isCarried()) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.ABSENTE,
                    "Le produit ne porte pas cette capacité : c'est une feature à créer.");
        }
        if (!observed) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.NON_OBSERVEE,
                    "Rien n'a été observé sur la période : on ne conclut pas.");
        }

        // Une capacité portée qui n'apparaît PAS dans les constats est une capacité qui tourne :
        // le diagnostic ne retient que ce qui dort ou ce qu'il n'a pas tranché (SF-156-03).
        CapabilityVerdict verdict = verdicts.get(reference.capabilityId());
        if (verdict == null || verdict == CapabilityVerdict.ACTIVE) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.TENUE, "Portée et vue à l'œuvre.");
        }
        // F-157 / SF-157-03 : DÉBRANCHÉE demande du CODE, pas un branchement à régler. C'est donc
        // un manque réel au sens de la parité — le seul cas qui appelle un développement.
        if (verdict == CapabilityVerdict.DEBRANCHEE) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.ABSENTE,
                    "Portée mais DÉBRANCHÉE dans le code : il manque une ligne, pas un réglage.");
        }
        if (verdict == CapabilityVerdict.DORMANTE) {
            return new ParityRow(reference.id(), reference.name(), reference.gives(),
                    ParityRow.State.DORMANTE,
                    "Portée mais jamais déclenchée : un branchement à réparer, pas un développement.");
        }
        return new ParityRow(reference.id(), reference.name(), reference.gives(),
                ParityRow.State.NON_OBSERVEE, "Le diagnostic n'a pas su trancher.");
    }

    /**
     * Ce que la parité mesure.
     *
     * @param rows la table complète
     * @param gaps le nombre de <b>manques réels</b> — le chiffre qui appelle des features
     */
    public record Parity(List<ParityRow> rows, int gaps) {

        /** Les lignes d'un état donné, pour l'écran. */
        public List<ParityRow> of(ParityRow.State state) {
            return rows.stream().filter(r -> r.state() == state).toList();
        }
    }

    /** Le croisement complet, pour les appelants qui n'ont qu'un identifiant de référence. */
    public static Function<String, ReferenceCapability> byId() {
        return id -> ParityReference.references().stream()
                .filter(r -> r.id().equals(id))
                .findFirst()
                .orElse(null);
    }
}
