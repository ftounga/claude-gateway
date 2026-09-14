package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

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
 * L'annonce, puis le dépôt (F-51 / SF-51-03, regrainé par F-75 / SF-75-01, <b>doté de la mise à
 * jour par F-96 / SF-96-01</b>).
 *
 * <p><b>L'annonce d'abord.</b> {@link #plan} dit, dossier par dossier et fichier par fichier, le
 * chemin exact et ce qui lui arrivera : créé, mis à jour, laissé tel quel, ou conservé parce qu'il a
 * été modifié localement. C'est l'exigence explicite de la feature — un paquet écrit sur la machine
 * de l'utilisateur, l'écran doit donc pouvoir le dire avant. L'annonce ne modifie rien.</p>
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
 * <h2>Ce que F-96 ajoute — et ce qu'il ne touche pas</h2>
 *
 * <p><b>Le défaut réparé.</b> Le dépôt n'avait que deux issues : créer ce qui manque, laisser tel
 * quel tout le reste — <b>contenu différent compris</b>. Un skill corrigé n'atteignait donc jamais
 * un poste qui avait déjà l'ancienne version : un client restait sur la gouvernance du jour de son
 * activation, pour toujours, et le produit publiait un catalogue qu'il ne pouvait pas faire
 * évoluer.</p>
 *
 * <p><b>La distinction qui manquait</b> — elle était déjà dans le prompt d'origine
 * ({@code gouv-bootstrap}, §9-4) : les scripts et les skills <i>sont écrasés, ce sont des artefacts
 * générés, pas du contenu utilisateur</i>. Un paquet <b>déclare</b> donc ses artefacts
 * ({@link GovernancePackageFile#isGenerated()}), et ceux-là — et eux seuls — peuvent être mis à
 * jour.</p>
 *
 * <p><b>Et seulement s'ils n'ont pas été touchés.</b> Un fichier que l'utilisateur a modifié
 * <b>redevient du contenu utilisateur</b> : on ne l'écrase pas, et l'annonce le dit
 * ({@link GovernanceDepositAction#KEEP_LOCAL}). On le reconnaît à l'<b>empreinte de ce qu'on avait
 * déposé</b> ({@link GovernanceDepositedFile}) : empreinte identique, c'est notre artefact, intact ;
 * empreinte différente ou inconnue, c'est du contenu utilisateur. <b>Écraser du contenu utilisateur
 * est hors périmètre, dans quelque cas que ce soit</b> — il n'existe aucun geste « forcer ».</p>
 *
 * <p><b>Et rien ne se met à jour tout seul</b> : {@link GovernanceDepositMode#CREATE_ONLY} borne les
 * dépôts automatiques à ce qui manque. Seul un geste de l'utilisateur peut remplacer un fichier.</p>
 *
 * <p><b>Une machine éteinte n'est pas une erreur.</b> Le paquet <i>est</i> actif : ses règles et ses
 * contrôles s'appliquent déjà, ils n'ont besoin d'aucun disque. Seuls ses fichiers attendent, et
 * l'activation reste {@code PENDING} avec un geste « appliquer » offert à l'écran.</p>
 */
@Service
public class GovernanceDepositService {

    private static final Logger log = LoggerFactory.getLogger(GovernanceDepositService.class);

    /**
     * Nombre maximal de <b>lectures</b> qu'un dépôt s'autorise pour décider des mises à jour.
     *
     * <p>Décider « cet artefact est-il resté intact ? » coûte un aller-retour vers la machine, et un
     * poste peut porter beaucoup de dossiers. Le cas courant, lui, reste <b>gratuit</b> : quand
     * l'empreinte retenue est déjà celle du contenu apporté, rien ne changerait et rien n'est lu.
     * Au-delà de cette borne, les fichiers restants sont annoncés <b>indéterminés</b> — jamais
     * écrits.</p>
     */
    public static final int MAX_UPDATE_READS = 200;

    private final GovernanceActivationRepository activations;
    private final GovernancePackageService packageService;
    private final GovernanceProjectFiles projectFiles;
    private final GovernanceHostFiles hostFiles;
    private final GovernanceHostScope hostScope;
    private final GovernanceDepositedFileRepository deposited;

    public GovernanceDepositService(GovernanceActivationRepository activations,
            GovernancePackageService packageService, GovernanceProjectFiles projectFiles,
            GovernanceHostFiles hostFiles, GovernanceHostScope hostScope,
            GovernanceDepositedFileRepository deposited) {
        this.activations = activations;
        this.packageService = packageService;
        this.projectFiles = projectFiles;
        this.hostFiles = hostFiles;
        this.hostScope = hostScope;
        this.deposited = deposited;
    }

    /**
     * Ce qu'un paquet écrirait sur les dossiers de ce poste, et où. <b>N'écrit rien.</b>
     *
     * @throws GovernancePackageNotFoundException si le paquet n'est pas publié
     */
    @Transactional(readOnly = true)
    public GovernanceDepositPlan plan(UUID userId, GovernanceHostRef host, UUID packageId) {
        GovernancePackage pkg = packageService.requirePublished(packageId);
        Run run = new Run(userId, host, pkg, false, GovernanceDepositMode.FULL);
        return run(run).plan();
    }

    /**
     * Dépose ce qui manque et met à jour ce qui le peut, dans <b>chaque</b> dossier du poste.
     *
     * <p>Appelée dans la foulée de l'activation et par le geste « appliquer » — deux gestes
     * <b>explicites</b> : c'est ce qui autorise la mise à jour d'un artefact. Une activation dont
     * tout est en place, partout, passe {@code APPLIED} et est horodatée ; sinon elle reste
     * {@code PENDING} et pourra être rejouée.</p>
     *
     * @return le plan <b>réalisé</b> : ce qui a été créé, mis à jour, laissé, conservé, ou non lu
     */
    @Transactional
    public GovernanceDepositPlan deposit(UUID userId, GovernanceHostRef host, UUID packageId) {
        GovernanceActivation activation = activations
                .findByUserIdAndHostIdAndPackageId(userId, host.hostId(), packageId)
                .orElseThrow(() -> new GovernancePackageNotFoundException(
                        "Ce paquet n'est pas actif sur ce poste. Activez-le avant de l'appliquer."));
        GovernancePackage pkg = packageService.requirePublished(packageId);

        Run run = new Run(userId, host, pkg, true, GovernanceDepositMode.FULL);
        Done done = run(run);

        // Un poste sans dossier passe APPLIED : il n'y a rien à attendre, et le premier dossier
        // ajouté demain recevra les fichiers à sa création.
        applyStatus(activation, pkg, done.complete());
        return done.plan();
    }

    /**
     * Dépose les fichiers de <b>tous</b> les paquets actifs sur le poste d'un projet, <b>dans ce
     * projet</b>, sans jamais lever.
     *
     * <p>C'est ce qui tient la promesse de F-75 : un dossier ajouté demain sous un poste déjà
     * gouverné hérite <b>sans qu'on y pense</b>. Réservé aux appels qui ne doivent rien casser — un
     * projet doit se créer même si la gouvernance a un hoquet.</p>
     *
     * <p><b>{@link GovernanceDepositMode#CREATE_ONLY}</b> : ce dépôt n'est le geste de personne. Il
     * crée ce qui manque et ne remplace <b>rien</b>, même un artefact périmé — un dossier créé sur
     * un répertoire existant ne doit pas voir ses fichiers réécrits dans son dos.</p>
     */
    @Transactional
    public void depositOnNewProjectQuietly(UUID userId, UUID workspaceId) {
        try {
            Workspace workspace = hostScope.projectOf(userId, workspaceId);
            if (isTerminal(workspace)) {
                // Un TERMINAL n'est pas un projet (F-74 / F-89) : il n'a pas de dossier de projet
                // réel, il s'ouvre à la RACINE du poste (F-92 / SF-96-04). Y déposer les gabarits et
                // les skills poserait du contenu de projet à côté de la carte — exactement la
                // confusion « un fichier de la racine pris pour un projet » que F-92 écarte — et
                // pire : le terminal ne recevant jamais un dépôt complet, il retiendrait l'activation
                // « en attente » à sa création, et le geste « Appliquer » ne pourrait plus l'éteindre.
                // Le dépôt de la carte (racine) et des vrais projets reste, lui, concerné.
                return;
            }
            GovernanceHostRef host = hostScope.hostOf(workspace);
            for (GovernanceActivation activation : activations
                    .findByUserIdAndHostIdOrderByCreatedAtAsc(userId, host.hostId())) {
                try {
                    GovernancePackage pkg = packageService.requirePublished(
                            activation.getPackageId());
                    // Un dossier neuf reçoit les gabarits et les skills. Pas la carte : elle vit à
                    // la racine du poste et s'y trouve déjà — la recopier dans chaque projet serait
                    // exactement la duplication que F-92 supprime.
                    Run run = new Run(userId, host, pkg, true, GovernanceDepositMode.CREATE_ONLY);
                    ProjectDeposit done = depositOn(run, workspace,
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

    /**
     * Oublie ce qu'un poste avait reçu : les empreintes partent avec la machine.
     *
     * <p>Une empreinte sans poste ne répond plus à aucune question, et resterait à jamais. Le
     * pendant exact de l'effacement des activations (F-75).</p>
     */
    @Transactional
    public void forgetHost(UUID userId, UUID hostId) {
        deposited.deleteByUserIdAndHostId(userId, hostId);
    }

    // -------------------------------------------------------------- internes

    /** Ce qu'un parcours complet a produit : le plan rendu, et s'il ne reste rien à faire. */
    private record Done(GovernanceDepositPlan plan, boolean complete) {
    }

    /** Le parcours complet d'un paquet sur un poste : la racine, puis chaque dossier. */
    private Done run(Run run) {
        List<GovernancePackageFile> files = packageService.filesOf(run.pkg.getId());
        List<GovernancePackageFile> mapFiles = filesOfKind(files, GovernanceFileKind.MAP);
        List<GovernancePackageFile> projectFilesOfPackage = projectScopedFiles(files);

        // La carte d'abord : c'est la destination du savoir, et un poste qui ne l'a pas encore n'a
        // nulle part où promouvoir (F-92). Un poste sans racine — « Hébergé » — ne retient rien.
        RootDeposit root = rootPlan(run, mapFiles);

        List<GovernanceProjectDepositPlan> projects = new ArrayList<>();
        boolean everythingInPlace = root.complete();
        for (Workspace workspace : hostScope.projectsOf(run.userId, run.host)) {
            if (isTerminal(workspace)) {
                // Défense en profondeur (F-96 / SF-96-04) : un terminal n'est pas un projet et ne
                // reçoit pas de dépôt de projet. Un terminal glissé dans le périmètre retiendrait
                // l'activation « en attente » pour toujours — le geste « Appliquer » n'éteindrait
                // jamais le bandeau « une version plus récente existe ».
                continue;
            }
            ProjectDeposit done = depositOn(run, workspace, projectFilesOfPackage);
            everythingInPlace &= done.complete();
            projects.add(new GovernanceProjectDepositPlan(workspace.getId(), workspace.getName(),
                    workspace.getProjectPath(), done.readable(), done.entries()));
        }
        return new Done(describe(run, files, root.plan(), projects), everythingInPlace);
    }

    /** Ce qu'un dépôt a donné à la racine du poste : le plan rendu, et s'il ne reste rien à faire. */
    private record RootDeposit(GovernanceRootDepositPlan plan, boolean complete) {
    }

    /**
     * Ce que la carte deviendra (ou vient de devenir) à la racine du poste.
     *
     * <p><b>Une seule lecture sert les deux questions</b> : le fichier est-il là, et est-il resté
     * celui qu'on avait déposé ? {@link GovernanceHostFiles#read} rend déjà les deux — décider une
     * mise à jour à la racine ne coûte donc <b>aucun appel supplémentaire</b>.</p>
     */
    private RootDeposit rootPlan(Run run, List<GovernancePackageFile> mapFiles) {
        if (mapFiles.isEmpty()) {
            // Ce paquet n'apporte pas de carte : il n'y a rien à dire, et rien à attendre.
            return new RootDeposit(
                    new GovernanceRootDepositPlan(hostFiles.supports(run.host), true, null, List.of()),
                    true);
        }
        if (!hostFiles.supports(run.host)) {
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
            GovernanceHostFiles.HostFileRead read = hostFiles.read(run.userId, run.host, path);
            switch (read.presence()) {
                case PRESENT -> {
                    readable = true;
                    // Un contenu TRONQUÉ ne prouve rien : son empreinte ne ressemble à rien, et le
                    // prendre pour le fichier entier ferait écraser la carte d'un client.
                    String current = read.truncated() ? null : read.contentOrEmpty();
                    GovernanceDepositAction action = decideExisting(run,
                            GovernanceDepositedFile.ROOT_SCOPE, file, path, () -> current);
                    if (action == GovernanceDepositAction.UPDATE) {
                        // Une mise à jour annoncée mais pas encore faite laisse quelque chose à
                        // faire ; une mise à jour refusée par la machine aussi. Un fichier PRÉSENT
                        // qu'on n'a pas su relire, lui, ne retient rien : il est là, on n'y touche
                        // pas, et retenir l'activation « en attente » pour ça serait un mensonge.
                        boolean written = run.write
                                && hostFiles.write(run.userId, run.host, path, contentOf(file));
                        if (written) {
                            run.remember(GovernanceDepositedFile.ROOT_SCOPE, path, file);
                        } else if (run.write) {
                            action = GovernanceDepositAction.UNKNOWN;
                        }
                        complete &= written;
                    }
                    done.add(entry(path, file, action));
                }
                case ABSENT -> {
                    readable = true;
                    if (run.write) {
                        boolean written = hostFiles.write(run.userId, run.host, path,
                                contentOf(file));
                        if (written) {
                            run.remember(GovernanceDepositedFile.ROOT_SCOPE, path, file);
                        }
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

    /** Ce qu'un dépôt a donné dans un dossier. */
    private record ProjectDeposit(boolean readable, boolean complete,
            List<GovernanceDepositEntry> entries) {
    }

    /** Le dépôt proprement dit, sur un projet déjà possédé. */
    private ProjectDeposit depositOn(Run run, Workspace workspace,
            List<GovernancePackageFile> files) {
        Optional<Set<String>> present = projectFiles.listPaths(run.userId, workspace);
        if (present.isEmpty()) {
            // Dossier illisible — machine éteinte, refus : on ne prétend NI qu'un fichier manque, NI
            // qu'il est là. Rien n'est écrit, et le geste « appliquer » reste offert.
            return new ProjectDeposit(false, false, unknownEntries(files));
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
                GovernanceDepositAction action = decideExisting(run, workspace.getId(), file, path,
                        () -> projectFiles.readExact(run.userId, workspace, path).orElse(null));
                if (action == GovernanceDepositAction.UPDATE) {
                    // Seule une mise à jour NON FAITE laisse quelque chose à faire — annoncée sans
                    // être écrite, ou refusée par la machine. Un fichier présent qu'on n'a pas su
                    // relire est là : il ne retient pas l'activation en attente.
                    boolean written = run.write
                            && projectFiles.write(run.userId, workspace, path, contentOf(file));
                    if (written) {
                        run.remember(workspace.getId(), path, file);
                    } else if (run.write) {
                        action = GovernanceDepositAction.UNKNOWN;
                    }
                    complete &= written;
                }
                done.add(entry(path, file, action));
                continue;
            }
            if (!run.write) {
                done.add(entry(path, file, GovernanceDepositAction.CREATE));
                complete = false; // Annoncer n'écrit pas : il reste quelque chose à faire.
                continue;
            }
            boolean written = projectFiles.write(run.userId, workspace, path, contentOf(file));
            if (written) {
                run.remember(workspace.getId(), path, file);
            }
            done.add(entry(path, file,
                    written ? GovernanceDepositAction.CREATE : GovernanceDepositAction.UNKNOWN));
            complete &= written;
        }
        return new ProjectDeposit(true, complete, List.copyOf(done));
    }

    /**
     * <b>Le cœur de F-96</b> : ce qu'il faut faire d'un fichier <b>déjà présent</b>.
     *
     * <p>Quatre issues, et une seule autorise à écrire. L'ordre des questions <b>est</b> la
     * protection :</p>
     * <ol>
     *   <li>Le paquet ne déclare pas ce fichier comme un artefact → c'est du <b>contenu
     *       utilisateur</b>, on le laisse, et on ne lit même pas la machine.</li>
     *   <li>L'empreinte retenue est déjà celle du contenu apporté → <b>rien ne changerait</b>. C'est
     *       le cas courant, et il ne coûte aucune lecture.</li>
     *   <li>Le dépôt n'est le geste de personne ({@link GovernanceDepositMode#CREATE_ONLY}) → on ne
     *       remplace rien.</li>
     *   <li>Sinon on lit. Contenu identique → rien à faire. Contenu <b>égal à l'empreinte de ce
     *       qu'on avait déposé</b>, ou <b>à l'une des empreintes que le produit a publiées à ce
     *       chemin</b> (SF-96-02) → c'est notre artefact, intact : <b>mise à jour</b>. Contenu
     *       différent, ou origine inconnue → <b>modifié localement, conservé</b>.</li>
     * </ol>
     *
     * <p>Illisible, tronqué, ou budget de lecture épuisé → on ne sait pas, et le doute n'écrit
     * jamais.</p>
     */
    private GovernanceDepositAction decideExisting(Run run, UUID scopeId,
            GovernancePackageFile file, String path, Supplier<String> reader) {
        if (!file.isGenerated()) {
            // Contenu utilisateur par déclaration : le paquet l'a posé une fois, il n'y revient pas.
            return GovernanceDepositAction.KEEP;
        }
        String brought = contentOf(file);
        String broughtDigest = GovernanceDigest.of(brought);
        GovernanceDepositedFile print = run.print(scopeId, path);
        if (print != null && broughtDigest.equals(print.getDigest())) {
            // On a déposé exactement ce contenu ici, et le paquet n'en apporte pas d'autre.
            return GovernanceDepositAction.KEEP;
        }
        if (run.mode == GovernanceDepositMode.CREATE_ONLY) {
            return GovernanceDepositAction.KEEP;
        }
        if (!run.spendRead()) {
            // Budget de lecture épuisé : on n'affirme rien, et surtout on n'écrit pas.
            return GovernanceDepositAction.UNKNOWN;
        }
        String current = reader.get();
        if (current == null) {
            return GovernanceDepositAction.UNKNOWN;
        }
        String currentDigest = GovernanceDigest.of(current);
        if (currentDigest.equals(broughtDigest)) {
            // Déjà exactement ce que le paquet apporte : rien à faire, mais on le RETIENT — la
            // prochaine version saura qu'elle a affaire à notre artefact et non à un inconnu.
            run.remember(scopeId, path, file);
            return GovernanceDepositAction.KEEP;
        }
        if (print != null && currentDigest.equals(print.getDigest())) {
            return GovernanceDepositAction.UPDATE;
        }
        if (file.knownDigestList().contains(currentDigest)) {
            // RATTRAPAGE (F-96 / SF-96-02) : ce contenu est, mot pour mot, une version que le
            // produit a publiée à ce chemin — il n'a donc été touché par personne, même si aucune
            // empreinte de dépôt ne le dit. C'est ce qui fait entrer dans le périmètre les postes
            // activés AVANT F-96, c'est-à-dire ceux qui portent la dette.
            return GovernanceDepositAction.UPDATE;
        }
        // Modifié localement, ou d'origine inconnue : ce fichier est du contenu utilisateur. On ne
        // le touche pas, et on le DIT — un fichier conservé parce qu'il a été modifié n'est pas la
        // même chose qu'un fichier conservé parce qu'il était déjà bon.
        return GovernanceDepositAction.KEEP_LOCAL;
    }

    /**
     * Vrai si ce workspace est un <b>terminal</b> (du poste F-74, ou Teams F-89) et non un projet.
     *
     * <p>Reconnu par sa <b>nature</b> (les drapeaux du modèle {@link Workspace}), jamais par son nom :
     * un projet réellement nommé « Terminal » resterait un projet, et un terminal renommé resterait
     * un terminal. Un terminal n'a pas de dossier de projet réel — il vit à la racine de la machine —
     * et n'entre donc pas dans le périmètre de dépôt de la gouvernance de projet.</p>
     */
    private static boolean isTerminal(Workspace workspace) {
        return workspace.isHostTerminal() || workspace.isTeamsTerminal();
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

    private void applyStatus(GovernanceActivation activation, GovernancePackage pkg,
            boolean everythingInPlace) {
        if (everythingInPlace) {
            activation.setStatus(GovernanceActivationStatus.APPLIED);
            activation.setAppliedAt(OffsetDateTime.now());
            // Le dépôt réaligne la version appliquée : « appliquer » après une republication doit
            // dire la vérité sur ce que le poste porte.
            activation.setAppliedVersion(pkg.getVersion());
        } else {
            activation.setStatus(GovernanceActivationStatus.PENDING);
        }
        activations.save(activation);
    }

    private GovernanceDepositPlan describe(Run run, List<GovernancePackageFile> files,
            GovernanceRootDepositPlan root, List<GovernanceProjectDepositPlan> projects) {
        List<GovernanceFileView> brought = files.stream()
                .map(file -> new GovernanceFileView(file.getPath(), file.getKind().name()))
                .toList();
        return new GovernanceDepositPlan(run.pkg.getId(), run.pkg.getSlug(), run.pkg.getVersion(),
                run.host.ref(), hostScope.nameOf(run.userId, run.host), List.copyOf(brought), root,
                List.copyOf(projects), run.pkg.getRules() != null,
                run.pkg.controlIdList().size());
    }

    private static String contentOf(GovernancePackageFile file) {
        return file.getContent() == null ? "" : file.getContent();
    }

    private static GovernanceDepositEntry entry(String path, GovernancePackageFile file,
            GovernanceDepositAction action) {
        return new GovernanceDepositEntry(path, file.getKind().name(), action);
    }

    /**
     * Un dépôt en cours : qui, où, quel paquet, et ce qu'on s'autorise.
     *
     * <p>Les empreintes du poste sont lues <b>une fois</b> : un dépôt traite N fichiers sur M
     * dossiers, et une requête par case ferait payer un aller-retour SQL par ligne d'annonce. Le
     * budget de lecture, lui, est porté ici parce qu'il vaut pour <b>tout</b> le dépôt — le borner
     * par dossier reviendrait à ne pas le borner.</p>
     */
    private final class Run {

        private final UUID userId;
        private final GovernanceHostRef host;
        private final GovernancePackage pkg;
        private final boolean write;
        private final GovernanceDepositMode mode;
        private final Map<String, GovernanceDepositedFile> prints = new HashMap<>();
        private int readsLeft = MAX_UPDATE_READS;

        private Run(UUID userId, GovernanceHostRef host, GovernancePackage pkg, boolean write,
                GovernanceDepositMode mode) {
            this.userId = userId;
            this.host = host;
            this.pkg = pkg;
            this.write = write;
            this.mode = mode;
            for (GovernanceDepositedFile print : deposited.findByUserIdAndHostIdAndPackageId(userId,
                    host.hostId(), pkg.getId())) {
                prints.put(key(print.getWorkspaceId(), print.getPath()), print);
            }
        }

        /** L'empreinte retenue pour cette destination, ou {@code null} si on n'y a jamais déposé. */
        private GovernanceDepositedFile print(UUID scopeId, String path) {
            return prints.get(key(scopeId, path));
        }

        /** Vrai s'il reste du budget pour une lecture. Décrémente. */
        private boolean spendRead() {
            if (readsLeft <= 0) {
                return false;
            }
            readsLeft--;
            return true;
        }

        /**
         * Retient l'empreinte de ce qui vient d'être écrit (ou de ce qu'on a reconnu intact).
         *
         * <p><b>N'écrit rien en mode annonce</b> : {@link #plan} ne doit laisser aucune trace, sans
         * quoi une simple ouverture d'écran changerait ce que le dépôt suivant décide.</p>
         */
        private void remember(UUID scopeId, String path, GovernancePackageFile file) {
            if (!write) {
                return;
            }
            String digest = GovernanceDigest.of(contentOf(file));
            GovernanceDepositedFile print = prints.get(key(scopeId, path));
            if (print == null) {
                print = GovernanceDepositedFile.builder()
                        .userId(userId).hostId(host.hostId()).workspaceId(scopeId)
                        .packageId(pkg.getId()).path(path).digest(digest)
                        .packageVersion(pkg.getVersion())
                        .build();
            } else {
                print.setDigest(digest);
                print.setPackageVersion(pkg.getVersion());
            }
            prints.put(key(scopeId, path), deposited.save(print));
        }

        private String key(UUID scopeId, String path) {
            return scopeId + " " + path;
        }
    }
}
