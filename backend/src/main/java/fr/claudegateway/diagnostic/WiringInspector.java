package fr.claudegateway.diagnostic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

/**
 * <b>Confronter les témoins de branchement au code lu</b> (F-157 / SF-157-03).
 *
 * <p><b>La lecture enrichit, elle ne remplace pas.</b> Sans fichiers lus, les verdicts de F-156
 * restent exactement ce qu'ils étaient — c'est la garantie qu'ajouter la lecture du code ne peut
 * pas dégrader un diagnostic qui marchait.</p>
 */
@Component
public class WiringInspector {

    /**
     * Réexamine un constat à la lumière du code.
     *
     * @param sources les fichiers lus, par chemin ; vide quand aucun dépôt n'a été désigné
     * @return le constat, enrichi ou inchangé
     */
    public CapabilityFinding inspect(CapabilityFinding finding, Map<String, SourceRead> sources) {
        if (sources == null || sources.isEmpty()) {
            return finding; // sans lecture : rien ne change
        }
        // Une capacité qui SE DÉCLENCHE n'a pas de question de branchement : elle est branchée.
        if (finding.verdict() == CapabilityVerdict.ACTIVE) {
            return finding;
        }
        ProductCapability capability = CapabilityMap.byId(finding.capabilityId()).orElse(null);
        if (capability == null || capability.wirings().isEmpty()) {
            return capability == null ? finding : notVerifiable(finding);
        }

        List<ProductCapability.Wiring> broken = new ArrayList<>();
        int checked = 0;
        for (ProductCapability.Wiring wiring : capability.wirings()) {
            SourceRead source = sources.get(wiring.path());
            if (source == null || !source.isRead()) {
                continue; // un fichier absent ne prouve RIEN — on ne conclut pas dessus
            }
            checked++;
            if (!source.contains(wiring.fragment())) {
                broken.add(wiring);
            }
        }

        if (checked == 0) {
            return notVerifiable(finding);
        }
        if (broken.isEmpty()) {
            return verified(finding);
        }
        return unwired(finding, broken);
    }

    /** Le branchement est vérifié : la capacité ne se déclenche pas pour une AUTRE raison. */
    private static CapabilityFinding verified(CapabilityFinding finding) {
        return new CapabilityFinding(finding.capabilityId(), finding.name(), finding.verdict(),
                finding.why() + " Branchement vérifié dans le code : ce n'est pas un appel qui "
                        + "manque, c'est une condition qui n'est pas remplie.",
                finding.where(), finding.check(), finding.gainEur());
    }

    /** Le branchement n'a pas pu être vérifié — on le dit plutôt que de laisser croire. */
    private static CapabilityFinding notVerifiable(CapabilityFinding finding) {
        return new CapabilityFinding(finding.capabilityId(), finding.name(), finding.verdict(),
                finding.why() + " Branchement non vérifiable : aucun témoin lisible pour cette "
                        + "capacité.",
                finding.where(), finding.check(), finding.gainEur());
    }

    /**
     * Un témoin manque : la capacité est <b>débranchée</b>.
     *
     * <p><b>Sans gain chiffré</b> : c'est un défaut à corriger, pas une optimisation à évaluer.
     * Annoncer un montant reviendrait à chiffrer une hypothèse.</p>
     */
    private static CapabilityFinding unwired(CapabilityFinding finding,
                                             List<ProductCapability.Wiring> broken) {
        StringBuilder why = new StringBuilder("DÉBRANCHÉE : ");
        for (ProductCapability.Wiring wiring : broken) {
            why.append("« ").append(wiring.fragment()).append(" » est absent de ")
                    .append(wiring.path()).append(" — ce fragment prouvait que ")
                    .append(wiring.proves()).append(". ");
        }
        why.append("Un remaniement l'a détachée : rien n'a cassé, aucun test n'est tombé.");
        return new CapabilityFinding(finding.capabilityId(), finding.name(),
                CapabilityVerdict.DEBRANCHEE, why.toString().strip(),
                broken.stream().map(ProductCapability.Wiring::path).distinct().toList(),
                finding.check(),
                null);
    }
}
