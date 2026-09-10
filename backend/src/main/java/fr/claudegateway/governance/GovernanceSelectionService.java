package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.dto.GovernanceSelectionView;

/**
 * Le catalogue <b>personnel</b> (F-51 / SF-51-02) : ce que chaque utilisateur retient du catalogue
 * publié.
 *
 * <p>Retenir un paquet ne l'active nulle part. C'est un geste de bibliothèque : il dit « celui-ci
 * m'intéresse », et il ouvre le droit de l'activer sur ses projets. La seule chose qui agisse toute
 * seule est le drapeau <b>appliqué par défaut</b>, qui fait embarquer le paquet par les projets à
 * venir de <b>cet</b> utilisateur — jamais par ceux d'un autre : rien n'est partagé entre comptes
 * (F-17, V3, hors périmètre).</p>
 *
 * <p><b>Isolation.</b> Toutes les lectures passent par un {@code userId} reçu en paramètre, jamais
 * deviné, et le repository n'expose aucune signature qui permettrait de l'omettre.</p>
 */
@Service
public class GovernanceSelectionService {

    private final GovernanceSelectionRepository selections;
    private final GovernanceActivationRepository activations;
    private final GovernancePackageService packageService;

    public GovernanceSelectionService(GovernanceSelectionRepository selections,
            GovernanceActivationRepository activations, GovernancePackageService packageService) {
        this.selections = selections;
        this.activations = activations;
        this.packageService = packageService;
    }

    /** Mon catalogue, avec pour chaque paquet le nombre de mes projets où il est actif. */
    @Transactional(readOnly = true)
    public List<GovernanceSelectionView> list(UUID userId) {
        List<GovernanceSelection> mine = selections.findByUserIdOrderByCreatedAtAsc(userId);
        List<GovernanceSelectionView> views = new ArrayList<>(mine.size());
        for (GovernanceSelection selection : mine) {
            GovernancePackage pkg = packageService.require(selection.getPackageId());
            views.add(new GovernanceSelectionView(
                    packageService.publicView(pkg, packageService.filesOf(pkg.getId())),
                    selection.isDefaultApplied(),
                    activations.findByUserIdAndPackageId(userId, pkg.getId()).size()));
        }
        return views;
    }

    /**
     * Retient un paquet <b>publié</b>, ou met à jour son drapeau.
     *
     * <p>Idempotent : retenir deux fois le même paquet ne crée pas de doublon, il met le drapeau à
     * jour. Un paquet non publié rend « introuvable » — pour un utilisateur, un brouillon n'existe
     * pas.</p>
     */
    @Transactional
    public GovernanceSelection select(UUID userId, UUID packageId, boolean defaultApplied) {
        GovernancePackage pkg = packageService.requirePublished(packageId);
        GovernanceSelection selection = selections.findByUserIdAndPackageId(userId, pkg.getId())
                .orElseGet(() -> GovernanceSelection.builder()
                        .userId(userId)
                        .packageId(pkg.getId())
                        .build());
        selection.setDefaultApplied(defaultApplied);
        return selections.save(selection);
    }

    /**
     * Retire un paquet de mon catalogue.
     *
     * <p><b>Les activations en cours restent</b> (arbitrage A2). Décocher dans une liste ne doit pas
     * éteindre en silence la gouvernance de projets en cours de travail ; le geste qui éteint un
     * projet est la <b>désactivation</b>, sur ce projet. Idempotent.</p>
     */
    @Transactional
    public void deselect(UUID userId, UUID packageId) {
        selections.deleteByUserIdAndPackageId(userId, packageId);
    }

    /** Mon catalogue brut, dans l'ordre où je l'ai composé. Lecture interne. */
    @Transactional(readOnly = true)
    public List<GovernanceSelection> all(UUID userId) {
        return selections.findByUserIdOrderByCreatedAtAsc(userId);
    }

    /** Mes paquets marqués « appliqué par défaut » — ce qu'un projet neuf embarque. */
    @Transactional(readOnly = true)
    public List<GovernanceSelection> defaults(UUID userId) {
        return selections.findByUserIdAndDefaultAppliedTrue(userId);
    }

    /** Vrai si ce paquet est dans mon catalogue. Condition d'activation (arbitrage A1). */
    @Transactional(readOnly = true)
    public boolean isSelected(UUID userId, UUID packageId) {
        return selections.findByUserIdAndPackageId(userId, packageId).isPresent();
    }
}
