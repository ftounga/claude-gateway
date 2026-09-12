package fr.claudegateway.governance;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.governance.dto.GovernanceActivationView;
import fr.claudegateway.governance.dto.GovernanceHostProjectView;
import fr.claudegateway.governance.dto.GovernanceHostSummary;
import fr.claudegateway.governance.dto.GovernanceHostView;
import fr.claudegateway.governance.dto.GovernancePackageView;

/**
 * Les paquets <b>actifs sur un poste</b> (F-51 / SF-51-02, regrainé par F-75 / SF-75-01).
 *
 * <p>C'est ici que la gouvernance passe de la bibliothèque à l'action : tant qu'une activation
 * existe, les règles du paquet rejoignent la consigne système de <b>chaque projet du poste</b> et ses
 * contrôles se branchent sur les crochets de F-50 (SF-51-04).</p>
 *
 * <p><b>Le poste, et rien d'autre.</b> Activer une fois sur un client vaut pour tous ses dossiers,
 * y compris ceux qui n'existent pas encore — c'est l'intérêt même d'un bootstrap idempotent, et ce
 * que l'activation par projet interdisait. Il n'y a <b>aucune dérogation par dossier</b> : le PO l'a
 * tranché, et ce service n'expose aucun moyen d'en fabriquer une.</p>
 *
 * <p><b>On n'active que ce qu'on a retenu</b> (arbitrage A1). Le catalogue personnel est l'étage qui
 * donne son sens à la feature — « chacun compose sa sélection » ; l'activation en le court-circuitant
 * le viderait de son rôle et rendrait le drapeau « appliqué par défaut » incohérent.</p>
 *
 * <p><b>Isolation, deux fois.</b> Le poste est vérifié comme <b>possédé</b>
 * ({@link GovernanceHostScope#require}) avant toute écriture — un poste qui n'est pas le sien rend
 * « introuvable », jamais « interdit », qui en révélerait l'existence — et chaque lecture de la table
 * porte en plus le {@code userId}.</p>
 */
@Service
public class GovernanceActivationService {

    private final GovernanceActivationRepository activations;
    private final GovernanceSelectionService selectionService;
    private final GovernancePackageService packageService;
    private final GovernanceHostScope hostScope;

    public GovernanceActivationService(GovernanceActivationRepository activations,
            GovernanceSelectionService selectionService, GovernancePackageService packageService,
            GovernanceHostScope hostScope) {
        this.activations = activations;
        this.selectionService = selectionService;
        this.packageService = packageService;
        this.hostScope = hostScope;
    }

    /** Les postes gouvernables et, pour chacun, ce qui y est actif. */
    @Transactional(readOnly = true)
    public List<GovernanceHostSummary> hosts(UUID userId) {
        List<GovernanceHostSummary> summaries = new ArrayList<>();
        for (GovernanceHostRef host : hostScope.governable(userId)) {
            summaries.add(new GovernanceHostSummary(
                    host.ref(),
                    host.publicId(),
                    hostScope.nameOf(userId, host),
                    host.hosted(),
                    hostScope.projectsOf(userId, host).size(),
                    activations.findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId())
                            .size()));
        }
        return List.copyOf(summaries);
    }

    /** Ce qui s'applique à ce poste, ce qui pourrait s'y appliquer, et les projets concernés. */
    @Transactional(readOnly = true)
    public GovernanceHostView describe(UUID userId, GovernanceHostRef host) {
        List<GovernanceActivation> active =
                activations.findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId());

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

        List<GovernanceHostProjectView> projects = new ArrayList<>();
        for (Workspace workspace : hostScope.projectsOf(userId, host)) {
            projects.add(new GovernanceHostProjectView(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath()));
        }

        return new GovernanceHostView(host.ref(), host.publicId(), hostScope.nameOf(userId, host),
                host.hosted(), List.copyOf(projects), List.copyOf(activeViews),
                List.copyOf(available));
    }

    /**
     * Active un paquet retenu sur un poste possédé.
     *
     * <p>Idempotent : réactiver un paquet déjà actif rend l'activation existante sans la dupliquer ni
     * faire régresser la version appliquée. L'état naît <b>{@code PENDING}</b> ; le dépôt des
     * fichiers dans chacun des projets du poste suit, et c'est lui qui peut le faire passer
     * {@code APPLIED}.</p>
     *
     * @throws GovernancePackageConflictException si le paquet n'est pas dans le catalogue personnel
     */
    @Transactional
    public GovernanceActivation activate(UUID userId, GovernanceHostRef host, UUID packageId) {
        GovernancePackage pkg = packageService.requirePublished(packageId);
        if (!selectionService.isSelected(userId, pkg.getId())) {
            throw new GovernancePackageConflictException(
                    "Ce paquet n'est pas dans votre catalogue. Retenez-le avant de l'activer.");
        }
        return activations.findByUserIdAndHostIdAndPackageId(userId, host.hostId(), pkg.getId())
                .orElseGet(() -> activations.save(GovernanceActivation.builder()
                        .userId(userId)
                        .hostId(host.hostId())
                        .packageId(pkg.getId())
                        .appliedVersion(pkg.getVersion())
                        .status(GovernanceActivationStatus.PENDING)
                        .build()));
    }

    /**
     * Désactive un paquet sur un poste. Idempotent.
     *
     * <p><b>Les fichiers déjà déposés restent</b> (décision D4 du cadrage) : un gabarit appartient au
     * projet dès qu'il y est, et le supprimer serait une suppression de fichier utilisateur
     * déclenchée par un décochage — sur la machine d'un client, de surcroît.</p>
     */
    @Transactional
    public void deactivate(UUID userId, GovernanceHostRef host, UUID packageId) {
        activations.deleteByUserIdAndHostIdAndPackageId(userId, host.hostId(), packageId);
    }

    /**
     * Embarque sur un poste les paquets marqués <b>appliqués par défaut</b>.
     *
     * <p>C'est la promesse « tout nouveau poste l'embarque sans rien cocher ». L'opération est
     * <b>idempotente</b> et ne lève pas : un défaut dépublié ou retiré entre-temps est simplement
     * ignoré — échouer la création d'un poste parce qu'un paquet a bougé serait hors de proportion.</p>
     *
     * @return les activations en place après l'opération, dans l'ordre du catalogue
     */
    @Transactional
    public List<GovernanceActivation> embarkDefaults(UUID userId, GovernanceHostRef host) {
        List<GovernanceActivation> embarked = new ArrayList<>();
        for (GovernanceSelection selection : selectionService.defaults(userId)) {
            GovernancePackage pkg;
            try {
                pkg = packageService.requirePublished(selection.getPackageId());
            } catch (GovernancePackageNotFoundException ex) {
                continue; // Paquet dépublié depuis : on n'embarque pas ce qui n'existe plus.
            }
            activations.findByUserIdAndHostIdAndPackageId(userId, host.hostId(), pkg.getId())
                    .ifPresentOrElse(embarked::add,
                            () -> embarked.add(activations.save(GovernanceActivation.builder()
                                    .userId(userId)
                                    .hostId(host.hostId())
                                    .packageId(pkg.getId())
                                    .appliedVersion(pkg.getVersion())
                                    .status(GovernanceActivationStatus.PENDING)
                                    .build())));
        }
        return List.copyOf(embarked);
    }

    /** Les activations d'un poste — lecture interne, déjà bornée à l'utilisateur. */
    @Transactional(readOnly = true)
    public List<GovernanceActivation> activeOn(UUID userId, GovernanceHostRef host) {
        return activations.findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId());
    }

    /**
     * Les activations qui gouvernent un <b>projet</b> : celles de son poste.
     *
     * <p>C'est le seul chemin par lequel un projet apprend ce qui s'applique à lui. Il n'existe
     * aucune activation portée par un projet : le grain est le poste, sans exception.</p>
     */
    @Transactional(readOnly = true)
    public List<GovernanceActivation> activeOnWorkspace(UUID userId, UUID workspaceId) {
        return activeOn(userId, hostScope.hostOf(userId, workspaceId));
    }

    /** Efface les activations d'un poste supprimé : la gouvernance ne survit pas à la machine. */
    @Transactional
    public void forgetHost(UUID userId, UUID hostId) {
        activations.deleteByUserIdAndHostId(userId, hostId);
    }
}
