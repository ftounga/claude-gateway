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
import fr.claudegateway.governance.dto.GovernanceRootDepositPlan;

/**
 * L'annonce, puis le dépôt (F-51 / SF-51-03, regrainé par F-75 / SF-75-01).
 *
 * <p><b>L'annonce d'abord.</b> {@link #plan} dit, dossier par dossier et fichier par fichier, le
 * chemin exact et ce qui lui arrivera : créé, ou laissé tel quel. C'est l'exigence explicite de la
 * feature — un paquet écrit sur la machine de l'utilisateur, l'écran doit donc pouvoir le dire avant.
 * L'annonce ne modifie rien.</p>
 *
 * <p><b>Un poste, tous ses dossiers — et sa racine.</b> L'activation vit sur le poste depuis F-75 ;
 * les <b>artefacts</b>, eux, restent par projet — un {@code STATE.md} est le journal d'un dossier, pas
 * d'une machine. Le dépôt parcourt donc tous les dossiers du poste, et un dossier ajouté demain
 * recevra les mêmes fichiers sans que personne ne recoche quoi que ce soit.</p>
 *
 * <p><b>Et la carte, elle, se pose une seule fois</b> (F-92 / SF-92-01). Les fichiers de genre
 * {@link GovernanceFileKind#MAP} n'ont rien à faire dans un projet : ils vivent à la <b>racine du
 * poste</b>, à côté des dossiers de projets, et c'est ce qui leur permet d'accumuler ce que chaque
 * projet fait apparaître. Le genre décide donc du point de chute, et un même dépôt écrit à deux
 * endroits de nature différente.</p>
 *
 * <p><b>La carte n'est jamais écrasée, et le doute n'écrit pas.</b> À la racine, la présence d'un
 * fichier est établie <b>par une lecture de ce fichier</b> ({@link GovernanceHostFiles}) et non par
 * l'arborescence : à la racine d'un poste réel, celle-ci est récursive et tronquée (SF-38-21), et
 * conclure « absent » d'une liste incomplète ferait écraser la carte d'un client.</p>
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
    private final GovernanceHostFiles hostFiles;
    private final GovernanceHostScope hostScope;

    public GovernanceDepositService(GovernanceActivationRepository activations,
            GovernancePackageService packageService, GovernanceProjectFiles projectFiles,
            GovernanceHostFiles hostFiles, GovernanceHostScope hostScope) {
        this.activations = activations;
        this.packageService = packageService;
        this.projectFiles = projectFiles;
        this.hostFiles = hostFiles;
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
        List<GovernancePackageFile> mapFiles = filesOfKind(files, GovernanceFileKind.MAP);
        List<GovernancePackageFile> projectFilesOfPackage = projectScopedFiles(files);

        List<GovernanceProjectDepositPlan> projects = new ArrayList<>();
        for (Workspace workspace : hostScope.projectsOf(userId, host)) {
            Optional<Set<String>> present = projectFiles.listPaths(userId, workspace);
            projects.add(new GovernanceProjectDepositPlan(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath(), present.isPresent(),
                    entriesFor(projectFilesOfPackage, present)));
        }
        return describe(pkg, files, userId, host, planRoot(userId, host, mapFiles), projects);
    }

    /**
     * Ce que la carte deviendrait, <b>sans rien écrire</b>. Une lecture par fichier de carte, et
     * jamais de conclusion tirée d'une arborescence tronquée.
     */
    private GovernanceRootDepositPlan planRoot(UUID userId, GovernanceHostRef host,
            List<GovernancePackageFile> mapFiles) {
        return rootPlan(userId, host, mapFiles, false).plan();
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

        List<GovernancePackageFile> mapFiles = filesOfKind(files, GovernanceFileKind.MAP);
        List<GovernancePackageFile> projectFilesOfPackage = projectScopedFiles(files);

        // La carte d'abord : c'est la destination du savoir, et un poste qui ne l'a pas encore n'a
        // nulle part où promouvoir (F-92). Un poste sans racine — « Hébergé » — ne retient rien.
        RootDeposit root = rootPlan(userId, host, mapFiles, true);

        List<GovernanceProjectDepositPlan> projects = new ArrayList<>();
        boolean everythingInPlace = root.complete();
        for (Workspace workspace : hostScope.projectsOf(userId, host)) {
            ProjectDeposit done = depositOn(userId, workspace, projectFilesOfPackage);
            everythingInPlace &= done.complete();
            projects.add(new GovernanceProjectDepositPlan(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath(), done.readable(), done.entries()));
        }

        // Un poste sans dossier passe APPLIED : il n'y a rien à attendre, et le premier dossier
        // ajouté demain recevra les fichiers à sa création.
        applyStatus(activation, pkg, everythingInPlace);
        return describe(pkg, files, userId, host, root.plan(), projects);
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
                    // Un dossier neuf reçoit les gabarits et les skills. Pas la carte : elle vit à
                    // la racine du poste et s'y trouve déjà — la recopier dans chaque projet serait
                    // exactement la duplication que F-92 supprime.
                    ProjectDeposit done = depositOn(userId, workspace,
                            projectScopedFiles(packageService.filesOf(pkg.getId())));
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

    /** Ce qu'un dépôt a donné à la racine du poste : le plan rendu, et s'il ne reste rien à faire. */
    private record RootDeposit(GovernanceRootDepositPlan plan, boolean complete) {
    }

    /**
     * Ce que la carte deviendra (ou vient de devenir) à la racine du poste.
     *
     * @param write vrai pour <b>déposer</b> ; faux pour se contenter d'<b>annoncer</b>
     */
    private RootDeposit rootPlan(UUID userId, GovernanceHostRef host,
            List<GovernancePackageFile> mapFiles, boolean write) {
        if (mapFiles.isEmpty()) {
            // Ce paquet n'apporte pas de carte : il n'y a rien à dire, et rien à attendre.
            return new RootDeposit(
                    new GovernanceRootDepositPlan(hostFiles.supports(host), true, null, List.of()),
                    true);
        }
        if (!hostFiles.supports(host)) {
            // Le poste « Hébergé » n'est pas une machine : pas de racine, donc pas de carte. Ce
            // n'est PAS un échec de dépôt — le retenir en attente laisserait l'activation
            // éternellement « en attente » d'une racine qui n'existera jamais.
            return new RootDeposit(new GovernanceRootDepositPlan(false, false,
                    "Ce poste n'est pas une machine : la carte vit à la racine d'un poste réel. "
                            + "Connectez une machine pour qu'elle ait une carte.",
                    unknownEntries(mapFiles)), true);
        }
        List<GovernanceDepositEntry> done = new ArrayList<>(mapFiles.size());
        boolean complete = true;
        boolean readable = false;
        for (GovernancePackageFile file : mapFiles) {
            // Re-normalisé au moment d'agir : un chemin stocké avant un durcissement de la règle ne
            // doit pas pouvoir sortir de la racine.
            String path = GovernancePath.normalizeOrNull(file.getPath());
            if (path == null) {
                complete = false;
                continue;
            }
            GovernanceHostFiles.Presence presence = hostFiles.presence(userId, host, path);
            switch (presence) {
                case PRESENT -> {
                    readable = true;
                    done.add(entry(path, file, GovernanceDepositAction.KEEP));
                }
                case ABSENT -> {
                    readable = true;
                    if (write) {
                        boolean written = hostFiles.write(userId, host, path, file.getContent());
                        done.add(entry(path, file, written ? GovernanceDepositAction.CREATE
                                : GovernanceDepositAction.UNKNOWN));
                        complete &= written;
                    } else {
                        done.add(entry(path, file, GovernanceDepositAction.CREATE));
                        complete = false; // Annoncer n'écrit pas : il reste quelque chose à faire.
                    }
                }
                // Machine éteinte, droits refusés, chemin occupé par un dossier : on NE SAIT PAS.
                // Et on n'écrit pas — écrire ici signifierait écraser la carte d'un client.
                default -> {
                    done.add(entry(path, file, GovernanceDepositAction.UNKNOWN));
                    complete = false;
                }
            }
        }
        String message = readable ? null
                : "La racine de ce poste n'a pas pu être lue : lancez le runner sur la machine, "
                        + "puis reprenez avec « Appliquer ». Rien n'a été écrit.";
        return new RootDeposit(
                new GovernanceRootDepositPlan(true, readable, message, List.copyOf(done)), complete);
    }

    /** Les fichiers du paquet d'un genre donné, dans l'ordre du paquet. */
    private static List<GovernancePackageFile> filesOfKind(List<GovernancePackageFile> files,
            GovernanceFileKind kind) {
        return files.stream().filter(file -> file.getKind() == kind).toList();
    }

    /**
     * Les fichiers qui se posent <b>dans un projet</b> : tout sauf la carte.
     *
     * <p>Écrit en négatif à dessein : un genre ajouté demain atterrira dans les projets — le
     * comportement historique — plutôt que de disparaître silencieusement du dépôt.</p>
     */
    private static List<GovernancePackageFile> projectScopedFiles(
            List<GovernancePackageFile> files) {
        return files.stream().filter(file -> file.getKind() != GovernanceFileKind.MAP).toList();
    }

    /** Toutes les entrées en « on ne sait pas » : rien n'a été lu, rien ne sera écrit. */
    private static List<GovernanceDepositEntry> unknownEntries(List<GovernancePackageFile> files) {
        return files.stream()
                .map(file -> entry(file.getPath(), file, GovernanceDepositAction.UNKNOWN))
                .toList();
    }

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
            UUID userId, GovernanceHostRef host, GovernanceRootDepositPlan root,
            List<GovernanceProjectDepositPlan> projects) {
        List<GovernanceFileView> brought = files.stream()
                .map(file -> new GovernanceFileView(file.getPath(), file.getKind().name()))
                .toList();
        return new GovernanceDepositPlan(pkg.getId(), pkg.getSlug(), pkg.getVersion(), host.ref(),
                hostScope.nameOf(userId, host), List.copyOf(brought), root, List.copyOf(projects),
                pkg.getRules() != null, pkg.controlIdList().size());
    }

    private static GovernanceDepositEntry entry(String path, GovernancePackageFile file,
            GovernanceDepositAction action) {
        return new GovernanceDepositEntry(path, file.getKind().name(), action);
    }
}
