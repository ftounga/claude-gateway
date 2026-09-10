package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.governance.dto.GovernanceActivationView;
import fr.claudegateway.governance.dto.GovernancePackageView;
import fr.claudegateway.governance.dto.GovernanceProjectView;

/**
 * Les paquets <b>actifs sur un projet</b> (F-51 / SF-51-02).
 *
 * <p>C'est ici que la gouvernance passe de la bibliothèque à l'action : tant qu'une activation
 * existe, les règles du paquet rejoignent la consigne système du projet et ses contrôles se branchent
 * sur les crochets de F-50 (SF-51-04).</p>
 *
 * <p><b>On n'active que ce qu'on a retenu</b> (arbitrage A1). Le catalogue personnel est l'étage qui
 * donne son sens à la feature — « chacun compose sa sélection » ; l'activation en le court-circuitant
 * le viderait de son rôle et rendrait le drapeau « appliqué par défaut » incohérent.</p>
 *
 * <p><b>Isolation, deux fois.</b> Le projet est vérifié comme <b>possédé</b>
 * ({@code WorkspaceService.requireOwned}) avant toute écriture — un projet qui n'est pas le sien rend
 * « introuvable », jamais « interdit », qui en révélerait l'existence — et chaque lecture de la table
 * porte en plus le {@code userId}.</p>
 */
@Service
public class GovernanceActivationService {

    private final GovernanceActivationRepository activations;
    private final GovernanceSelectionService selectionService;
    private final GovernancePackageService packageService;
    private final WorkspaceService workspaceService;

    public GovernanceActivationService(GovernanceActivationRepository activations,
            GovernanceSelectionService selectionService, GovernancePackageService packageService,
            WorkspaceService workspaceService) {
        this.activations = activations;
        this.selectionService = selectionService;
        this.packageService = packageService;
        this.workspaceService = workspaceService;
    }

    /** Ce qui s'applique à ce projet, et ce qui pourrait s'y appliquer. */
    @Transactional(readOnly = true)
    public GovernanceProjectView describe(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        List<GovernanceActivation> active =
                activations.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);

        List<GovernanceActivationView> activeViews = new ArrayList<>(active.size());
        Set<UUID> activeIds = new LinkedHashSet<>();
        for (GovernanceActivation activation : active) {
            GovernancePackage pkg = packageService.require(activation.getPackageId());
            activeIds.add(pkg.getId());
            activeViews.add(new GovernanceActivationView(
                    packageService.publicView(pkg, packageService.filesOf(pkg.getId())),
                    activation.getAppliedVersion(),
                    // Le paquet a pu être republié depuis : on le DIT, on ne met rien à jour dans le
                    // dos de l'utilisateur (décision D5 du cadrage).
                    pkg.getVersion() > activation.getAppliedVersion(),
                    activation.getStatus().name(),
                    activation.getAppliedAt()));
        }

        List<GovernancePackageView> available = new ArrayList<>();
        for (GovernanceSelection selection : selectionService.all(userId)) {
            if (activeIds.contains(selection.getPackageId())) {
                continue;
            }
            GovernancePackage pkg = packageService.require(selection.getPackageId());
            available.add(packageService.publicView(pkg, packageService.filesOf(pkg.getId())));
        }
        return new GovernanceProjectView(workspaceId, List.copyOf(activeViews), List.copyOf(available));
    }

    /**
     * Active un paquet retenu sur un projet possédé.
     *
     * <p>Idempotent : réactiver un paquet déjà actif rend l'activation existante sans la dupliquer ni
     * faire régresser la version appliquée. L'état naît <b>{@code PENDING}</b> : le dépôt des
     * fichiers, lui, arrive en SF-51-03.</p>
     *
     * @throws GovernancePackageConflictException si le paquet n'est pas dans le catalogue personnel
     */
    @Transactional
    public GovernanceActivation activate(UUID userId, UUID workspaceId, UUID packageId) {
        workspaceService.requireOwned(userId, workspaceId);
        GovernancePackage pkg = packageService.requirePublished(packageId);
        if (!selectionService.isSelected(userId, pkg.getId())) {
            throw new GovernancePackageConflictException(
                    "Ce paquet n'est pas dans votre catalogue. Retenez-le avant de l'activer.");
        }
        return activations.findByUserIdAndWorkspaceIdAndPackageId(userId, workspaceId, pkg.getId())
                .orElseGet(() -> activations.save(GovernanceActivation.builder()
                        .userId(userId)
                        .workspaceId(workspaceId)
                        .packageId(pkg.getId())
                        .appliedVersion(pkg.getVersion())
                        .status(GovernanceActivationStatus.PENDING)
                        .build()));
    }

    /**
     * Désactive un paquet sur un projet. Idempotent.
     *
     * <p><b>Les fichiers déjà déposés restent</b> (décision D4 du cadrage) : un gabarit appartient au
     * projet dès qu'il y est, et le supprimer serait une suppression de fichier utilisateur
     * déclenchée par un décochage.</p>
     */
    @Transactional
    public void deactivate(UUID userId, UUID workspaceId, UUID packageId) {
        workspaceService.requireOwned(userId, workspaceId);
        activations.deleteByUserIdAndWorkspaceIdAndPackageId(userId, workspaceId, packageId);
    }

    /**
     * Embarque sur un projet les paquets marqués <b>appliqués par défaut</b>.
     *
     * <p>C'est la promesse « tout nouveau projet l'embarque sans rien cocher ». L'opération est
     * <b>idempotente</b> et ne lève pas : un défaut dépublié ou retiré entre-temps est simplement
     * ignoré — échouer la création d'un projet parce qu'un paquet a bougé serait hors de proportion.
     * Son déclenchement à la création est câblé en SF-51-03, avec le dépôt qui l'accompagne.</p>
     *
     * @return les activations en place après l'opération, dans l'ordre du catalogue
     */
    @Transactional
    public List<GovernanceActivation> embarkDefaults(UUID userId, UUID workspaceId) {
        List<GovernanceActivation> embarked = new ArrayList<>();
        for (GovernanceSelection selection : selectionService.defaults(userId)) {
            GovernancePackage pkg;
            try {
                pkg = packageService.requirePublished(selection.getPackageId());
            } catch (GovernancePackageNotFoundException ex) {
                continue; // Paquet dépublié depuis : on n'embarque pas ce qui n'existe plus.
            }
            activations.findByUserIdAndWorkspaceIdAndPackageId(userId, workspaceId, pkg.getId())
                    .ifPresentOrElse(embarked::add,
                            () -> embarked.add(activations.save(GovernanceActivation.builder()
                                    .userId(userId)
                                    .workspaceId(workspaceId)
                                    .packageId(pkg.getId())
                                    .appliedVersion(pkg.getVersion())
                                    .status(GovernanceActivationStatus.PENDING)
                                    .build())));
        }
        return List.copyOf(embarked);
    }

    /** Les activations d'un projet — lecture interne, déjà bornée à l'utilisateur. */
    @Transactional(readOnly = true)
    public List<GovernanceActivation> activeOn(UUID userId, UUID workspaceId) {
        return activations.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId);
    }
}
