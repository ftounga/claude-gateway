package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.governance.dto.GovernanceDepositAction;
import fr.claudegateway.governance.dto.GovernanceDepositEntry;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;

/**
 * L'annonce, puis le dépôt (F-51 / SF-51-03).
 *
 * <p><b>L'annonce d'abord.</b> {@link #plan} dit, fichier par fichier, le chemin exact et ce qui va
 * lui arriver : créé, ou laissé tel quel. C'est l'exigence explicite de la feature — un paquet écrit
 * sur la machine de l'utilisateur, l'écran doit donc pouvoir le dire avant. L'annonce ne modifie
 * rien.</p>
 *
 * <p><b>Le dépôt ensuite, et il ne détruit rien.</b> {@link #deposit} crée ce qui manque et
 * <b>laisse tel quel</b> tout fichier déjà présent — contenu différent compris. C'est la promesse
 * d'idempotence de la feature, et la seule qui protège le travail de l'utilisateur : écraser un
 * {@code STATE.md} rempli parce qu'un paquet en apporte un vide serait une perte de données
 * déclenchée par une case cochée. Republier un paquet ne réécrit donc rien chez personne.</p>
 *
 * <p><b>Une machine éteinte n'est pas une erreur.</b> Le paquet <i>est</i> actif : ses règles et ses
 * contrôles s'appliquent déjà, ils n'ont besoin d'aucun disque. Seuls ses fichiers attendent, et
 * l'activation reste {@code PENDING} avec un geste « appliquer » offert à l'écran. Rendre une erreur
 * laisserait croire que rien n'a pris.</p>
 */
@Service
public class GovernanceDepositService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceDepositService.class);

    private final GovernanceActivationRepository activations;
    private final GovernancePackageService packageService;
    private final GovernanceProjectFiles projectFiles;
    private final WorkspaceService workspaceService;

    public GovernanceDepositService(GovernanceActivationRepository activations,
            GovernancePackageService packageService, GovernanceProjectFiles projectFiles,
            WorkspaceService workspaceService) {
        this.activations = activations;
        this.packageService = packageService;
        this.projectFiles = projectFiles;
        this.workspaceService = workspaceService;
    }

    /**
     * Ce qu'un paquet écrirait sur ce projet, et où. <b>N'écrit rien.</b>
     *
     * @throws fr.claudegateway.atelier.WorkspaceNotFoundException si le projet n'est pas possédé
     * @throws GovernancePackageNotFoundException                  si le paquet n'est pas publié
     */
    @Transactional(readOnly = true)
    public GovernanceDepositPlan plan(UUID userId, UUID workspaceId, UUID packageId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        GovernancePackage pkg = packageService.requirePublished(packageId);
        Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);
        return planFrom(pkg, present);
    }

    /**
     * Dépose ce qui manque, sans jamais écraser.
     *
     * <p>Appelée dans la foulée de l'activation (SF-51-02) et par le geste « appliquer ». Une
     * activation dont tout est en place passe {@code APPLIED} et est horodatée ; sinon elle reste
     * {@code PENDING} et pourra être rejouée.</p>
     *
     * @return le plan <b>réalisé</b> : ce qui a été créé, ce qui a été laissé, ce qu'on n'a pas su lire
     */
    @Transactional
    public GovernanceDepositPlan deposit(UUID userId, UUID workspaceId, UUID packageId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        GovernanceActivation activation = activations
                .findByUserIdAndWorkspaceIdAndPackageId(userId, workspaceId, packageId)
                .orElseThrow(() -> new GovernancePackageNotFoundException(
                        "Ce paquet n'est pas actif sur ce projet. Activez-le avant de l'appliquer."));
        GovernancePackage pkg = packageService.requirePublished(packageId);
        return depositOn(userId, workspace, activation, pkg);
    }

    /**
     * Dépose les fichiers de <b>tous</b> les paquets actifs sur un projet, sans jamais lever.
     *
     * <p>Réservé aux appels qui ne doivent rien casser : l'embarquement de la sélection par défaut à
     * la création d'un projet. Un projet doit se créer même si la gouvernance a un hoquet.</p>
     */
    @Transactional
    public void depositAllQuietly(UUID userId, UUID workspaceId) {
        try {
            Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
            for (GovernanceActivation activation : activations
                    .findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId)) {
                try {
                    depositOn(userId, workspace, activation,
                            packageService.requirePublished(activation.getPackageId()));
                } catch (RuntimeException ex) {
                    // Un paquet dépublié ou illisible n'empêche pas les autres de se poser.
                    log.debug("Dépôt de gouvernance ignoré pour un paquet ({})",
                            ex.getClass().getSimpleName());
                }
            }
        } catch (RuntimeException ex) {
            log.debug("Dépôt de gouvernance ignoré pour le projet ({})", ex.getClass().getSimpleName());
        }
    }

    // -------------------------------------------------------------- internes

    /** Le dépôt proprement dit, sur une activation déjà résolue et un projet déjà possédé. */
    private GovernanceDepositPlan depositOn(UUID userId, Workspace workspace,
            GovernanceActivation activation, GovernancePackage pkg) {
        List<GovernancePackageFile> files = packageService.filesOf(pkg.getId());
        Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);

        List<GovernanceDepositEntry> done = new ArrayList<>(files.size());
        boolean everythingInPlace = present.isPresent();
        if (present.isPresent()) {
            Set<String> paths = present.get();
            for (GovernancePackageFile file : files) {
                // Le chemin est re-normalisé au moment d'écrire : un chemin stocké avant un
                // durcissement de la règle ne doit pas pouvoir sortir du projet.
                String path = GovernancePath.normalizeOrNull(file.getPath());
                if (path == null) {
                    everythingInPlace = false;
                    continue;
                }
                if (paths.contains(path)) {
                    done.add(entry(path, file, GovernanceDepositAction.KEEP));
                    continue;
                }
                boolean written = projectFiles.write(userId, workspace, path, file.getContent());
                done.add(entry(path, file, written
                        ? GovernanceDepositAction.CREATE
                        : GovernanceDepositAction.UNKNOWN));
                everythingInPlace &= written;
            }
        } else {
            for (GovernancePackageFile file : files) {
                done.add(entry(file.getPath(), file, GovernanceDepositAction.UNKNOWN));
            }
        }

        if (everythingInPlace) {
            // Un paquet sans fichier passe APPLIED immédiatement : il n'y a rien à attendre.
            activation.setStatus(GovernanceActivationStatus.APPLIED);
            activation.setAppliedAt(OffsetDateTime.now());
            // Le dépôt réaligne la version appliquée : « appliquer » après une republication doit
            // dire la vérité sur ce que le projet porte — sans pour autant réécrire quoi que ce soit.
            activation.setAppliedVersion(pkg.getVersion());
        } else {
            activation.setStatus(GovernanceActivationStatus.PENDING);
        }
        activations.save(activation);

        return new GovernanceDepositPlan(pkg.getId(), pkg.getSlug(), pkg.getVersion(),
                present.isPresent(), List.copyOf(done), pkg.getRules() != null,
                pkg.controlIdList().size());
    }

    /** L'annonce, à partir de ce que le projet contient (ou de l'impossibilité de le lire). */
    private GovernanceDepositPlan planFrom(GovernancePackage pkg, Optional<Set<String>> present) {
        List<GovernancePackageFile> files = packageService.filesOf(pkg.getId());
        List<GovernanceDepositEntry> entries = new ArrayList<>(files.size());
        for (GovernancePackageFile file : files) {
            GovernanceDepositAction action;
            if (present.isEmpty()) {
                action = GovernanceDepositAction.UNKNOWN;
            } else {
                String path = GovernancePath.normalizeOrNull(file.getPath());
                action = path != null && present.get().contains(path)
                        ? GovernanceDepositAction.KEEP
                        : GovernanceDepositAction.CREATE;
            }
            entries.add(entry(file.getPath(), file, action));
        }
        return new GovernanceDepositPlan(pkg.getId(), pkg.getSlug(), pkg.getVersion(),
                present.isPresent(), List.copyOf(entries), pkg.getRules() != null,
                pkg.controlIdList().size());
    }

    private static GovernanceDepositEntry entry(String path, GovernancePackageFile file,
            GovernanceDepositAction action) {
        return new GovernanceDepositEntry(path, file.getKind().name(), action);
    }
}
