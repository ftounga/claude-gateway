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
import fr.claudegateway.governance.dto.GovernanceDepositAction;
import fr.claudegateway.governance.dto.GovernanceDepositEntry;
import fr.claudegateway.governance.dto.GovernanceDepositPlan;
import fr.claudegateway.governance.dto.GovernanceFileView;
import fr.claudegateway.governance.dto.GovernanceProjectDepositPlan;

/**
 * L'annonce, puis le dépôt (F-51 / SF-51-03, regrainé par F-75 / SF-75-01).
 *
 * <p><b>L'annonce d'abord.</b> {@link #plan} dit, dossier par dossier et fichier par fichier, le
 * chemin exact et ce qui lui arrivera : créé, ou laissé tel quel. C'est l'exigence explicite de la
 * feature — un paquet écrit sur la machine de l'utilisateur, l'écran doit donc pouvoir le dire avant.
 * L'annonce ne modifie rien.</p>
 *
 * <p><b>Un poste, tous ses dossiers.</b> L'activation vit sur le poste depuis F-75 ; les
 * <b>artefacts</b>, eux, restent par projet — un {@code STATE.md} est le journal d'un dossier, pas
 * d'une machine. Le dépôt parcourt donc tous les dossiers du poste, et un dossier ajouté demain
 * recevra les mêmes fichiers sans que personne ne recoche quoi que ce soit.</p>
 *
 * <p><b>Le dépôt ne détruit rien.</b> {@link #deposit} crée ce qui manque et <b>laisse tel quel</b>
 * tout fichier déjà présent — contenu différent compris. C'est la promesse d'idempotence de la
 * feature, et la seule qui protège le travail de l'utilisateur : écraser un {@code STATE.md} rempli
 * parce qu'un paquet en apporte un vide serait une perte de données déclenchée par une case cochée.
 * Republier un paquet ne réécrit donc rien chez personne.</p>
 *
 * <p><b>Une machine éteinte n'est pas une erreur.</b> Le paquet <i>est</i> actif : ses règles et ses
 * contrôles s'appliquent déjà, ils n'ont besoin d'aucun disque. Seuls ses fichiers attendent, et
 * l'activation reste {@code PENDING} avec un geste « appliquer » offert à l'écran.</p>
 */
@Service
public class GovernanceDepositService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceDepositService.class);

    private final GovernanceActivationRepository activations;
    private final GovernancePackageService packageService;
    private final GovernanceProjectFiles projectFiles;
    private final GovernanceHostScope hostScope;

    public GovernanceDepositService(GovernanceActivationRepository activations,
            GovernancePackageService packageService, GovernanceProjectFiles projectFiles,
            GovernanceHostScope hostScope) {
        this.activations = activations;
        this.packageService = packageService;
        this.projectFiles = projectFiles;
        this.hostScope = hostScope;
    }

    /**
     * Ce qu'un paquet écrirait sur les dossiers de ce poste, et où. <b>N'écrit rien.</b>
     *
     * @throws GovernancePackageNotFoundException si le paquet n'est pas publié
     */
    @Transactional(readOnly = true)
    public GovernanceDepositPlan plan(UUID userId, GovernanceHostRef host, UUID packageId) {
        GovernancePackage pkg = packageService.requirePublished(packageId);
        List<GovernancePackageFile> files = packageService.filesOf(pkg.getId());

        List<GovernanceProjectDepositPlan> projects = new ArrayList<>();
        for (Workspace workspace : hostScope.projectsOf(userId, host)) {
            Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);
            projects.add(new GovernanceProjectDepositPlan(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath(), present.isPresent(), entriesFor(files, present)));
        }
        return describe(pkg, files, userId, host, projects);
    }

    /**
     * Dépose ce qui manque dans <b>chaque</b> dossier du poste, sans jamais écraser.
     *
     * <p>Appelée dans la foulée de l'activation et par le geste « appliquer ». Une activation dont
     * tout est en place, partout, passe {@code APPLIED} et est horodatée ; sinon elle reste
     * {@code PENDING} et pourra être rejouée.</p>
     *
     * @return le plan <b>réalisé</b> : ce qui a été créé, ce qui a été laissé, ce qu'on n'a pas su lire
     */
    @Transactional
    public GovernanceDepositPlan deposit(UUID userId, GovernanceHostRef host, UUID packageId) {
        GovernanceActivation activation = activations
                .findByUserIdAndHostIdAndPackageId(userId, host.hostId(), packageId)
                .orElseThrow(() -> new GovernancePackageNotFoundException(
                        "Ce paquet n'est pas actif sur ce poste. Activez-le avant de l'appliquer."));
        GovernancePackage pkg = packageService.requirePublished(packageId);
        List<GovernancePackageFile> files = packageService.filesOf(pkg.getId());

        List<GovernanceProjectDepositPlan> projects = new ArrayList<>();
        boolean everythingInPlace = true;
        for (Workspace workspace : hostScope.projectsOf(userId, host)) {
            ProjectDeposit done = depositOn(userId, workspace, files);
            everythingInPlace &= done.complete();
            projects.add(new GovernanceProjectDepositPlan(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath(), done.readable(), done.entries()));
        }

        // Un poste sans dossier passe APPLIED : il n'y a rien à attendre, et le premier dossier
        // ajouté demain recevra les fichiers à sa création.
        applyStatus(activation, pkg, everythingInPlace);
        return describe(pkg, files, userId, host, projects);
    }

    /**
     * Dépose les fichiers de <b>tous</b> les paquets actifs sur le poste d'un projet, <b>dans ce
     * projet</b>, sans jamais lever.
     *
     * <p>C'est ce qui tient la promesse de F-75 : un dossier ajouté demain sous un poste déjà
     * gouverné hérite <b>sans qu'on y pense</b>. Réservé aux appels qui ne doivent rien casser — un
     * projet doit se créer même si la gouvernance a un hoquet.</p>
     */
    @Transactional
    public void depositOnNewProjectQuietly(UUID userId, UUID workspaceId) {
        try {
            Workspace workspace = hostScope.projectOf(userId, workspaceId);
            GovernanceHostRef host = hostScope.hostOf(workspace);
            for (GovernanceActivation activation : activations
                    .findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId())) {
                try {
                    GovernancePackage pkg = packageService.requirePublished(
                            activation.getPackageId());
                    ProjectDeposit done = depositOn(userId, workspace,
                            packageService.filesOf(pkg.getId()));
                    if (!done.complete()) {
                        // Le nouveau dossier n'a pas tout reçu : l'activation redevient en attente,
                        // et le geste « appliquer » reste offert. Dire « appliqué » alors qu'un
                        // dossier du poste attend encore serait le seul mensonge impardonnable ici.
                        activation.setStatus(GovernanceActivationStatus.PENDING);
                        activations.save(activation);
                    }
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

    /** Ce qu'un dépôt a donné dans un dossier. */
    private record ProjectDeposit(boolean readable, boolean complete,
            List<GovernanceDepositEntry> entries) {
    }

    /** Le dépôt proprement dit, sur un projet déjà possédé. */
    private ProjectDeposit depositOn(UUID userId, Workspace workspace,
            List<GovernancePackageFile> files) {
        Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);
        if (present.isEmpty()) {
            return new ProjectDeposit(false, false, entriesFor(files, present));
        }
        Set<String> paths = present.get();
        List<GovernanceDepositEntry> done = new ArrayList<>(files.size());
        boolean complete = true;
        for (GovernancePackageFile file : files) {
            // Le chemin est re-normalisé au moment d'écrire : un chemin stocké avant un durcissement
            // de la règle ne doit pas pouvoir sortir du projet.
            String path = GovernancePath.normalizeOrNull(file.getPath());
            if (path == null) {
                complete = false;
                continue;
            }
            if (paths.contains(path)) {
                done.add(entry(path, file, GovernanceDepositAction.KEEP));
                continue;
            }
            boolean written = projectFiles.write(userId, workspace, path, file.getContent());
            done.add(entry(path, file,
                    written ? GovernanceDepositAction.CREATE : GovernanceDepositAction.UNKNOWN));
            complete &= written;
        }
        return new ProjectDeposit(true, complete, List.copyOf(done));
    }

    /** L'annonce pour un dossier, à partir de ce qu'il contient (ou de l'impossibilité de le lire). */
    private static List<GovernanceDepositEntry> entriesFor(List<GovernancePackageFile> files,
            Optional<Set<String>> present) {
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
        return List.copyOf(entries);
    }

    private void applyStatus(GovernanceActivation activation, GovernancePackage pkg,
            boolean everythingInPlace) {
        if (everythingInPlace) {
            activation.setStatus(GovernanceActivationStatus.APPLIED);
            activation.setAppliedAt(OffsetDateTime.now());
            // Le dépôt réaligne la version appliquée : « appliquer » après une republication doit
            // dire la vérité sur ce que le poste porte — sans pour autant réécrire quoi que ce soit.
            activation.setAppliedVersion(pkg.getVersion());
        } else {
            activation.setStatus(GovernanceActivationStatus.PENDING);
        }
        activations.save(activation);
    }

    private GovernanceDepositPlan describe(GovernancePackage pkg, List<GovernancePackageFile> files,
            UUID userId, GovernanceHostRef host, List<GovernanceProjectDepositPlan> projects) {
        List<GovernanceFileView> brought = files.stream()
                .map(file -> new GovernanceFileView(file.getPath(), file.getKind().name()))
                .toList();
        return new GovernanceDepositPlan(pkg.getId(), pkg.getSlug(), pkg.getVersion(), host.ref(),
                hostScope.nameOf(userId, host), List.copyOf(brought), List.copyOf(projects),
                pkg.getRules() != null, pkg.controlIdList().size());
    }

    private static GovernanceDepositEntry entry(String path, GovernancePackageFile file,
            GovernanceDepositAction action) {
        return new GovernanceDepositEntry(path, file.getKind().name(), action);
    }
}
