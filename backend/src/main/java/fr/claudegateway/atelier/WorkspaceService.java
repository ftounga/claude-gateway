package fr.claudegateway.atelier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.runner.audit.RunnerAuditRepository;
import fr.claudegateway.runner.host.HostProjectExistsException;
import fr.claudegateway.runner.host.RunnerProjectPath;

/**
 * Cœur de l'Atelier (F-28) : création d'un workspace à partir d'un zip décompressé de façon sûre
 * (zip-slip + zip-bomb), et lecture/écriture des fichiers. Isolation multi-tenant : tout accès
 * vérifie que le workspace appartient à l'utilisateur courant. Ne dépend que de
 * {@link WorkspaceStorage} (Provider Independence).
 */
@Service
public class WorkspaceService {

    /** Longueur maximale du nom d'un projet : borne de la colonne `workspaces.name`. */
    private static final int MAX_NAME_LENGTH = 255;

    /**
     * Nom du <b>terminal du poste</b> (F-74 / SF-74-01), écrit par la gateway et jamais demandé : le
     * client a déjà été nommé à la connexion du poste. Ce nom apparaît tel quel dans le relevé de
     * consommation par client (F-61), où une ligne de plus sous un client doit se lire sans
     * explication.
     */
    public static final String HOST_TERMINAL_NAME = "Terminal du poste";

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final byte[] DEFAULT_CLAUDE_MD = ("# CLAUDE.md\n\n"
            + "Conventions et contexte de ce projet, à destination de Claude.\n"
            + "Décrivez ici l'architecture, les règles de code et ce qu'il faut savoir.\n")
            .getBytes(StandardCharsets.UTF_8);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceStorage storage;
    private final AtelierProperties properties;
    private final AtelierMessageRepository atelierMessageRepository;
    private final RunnerAuditRepository runnerAudit;
    private final ApplicationEventPublisher events;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceStorage storage,
            AtelierProperties properties, AtelierMessageRepository atelierMessageRepository,
            RunnerAuditRepository runnerAudit, ApplicationEventPublisher events) {
        this.workspaceRepository = workspaceRepository;
        this.storage = storage;
        this.properties = properties;
        this.atelierMessageRepository = atelierMessageRepository;
        this.runnerAudit = runnerAudit;
        this.events = events;
    }

    /**
     * Annonce la création d'un projet (F-51 / SF-51-03).
     *
     * <p>Un seul endroit pour les trois portes d'entrée — archive, dépôt distant, projet local :
     * un quatrième chemin de création oublierait sinon d'embarquer la gouvernance par défaut, et
     * ça ne se verrait pas.</p>
     */
    private Workspace announceCreated(Workspace workspace) {
        events.publishEvent(new WorkspaceCreatedEvent(workspace.getUserId(), workspace.getId()));
        return workspace;
    }

    /** Crée un workspace à partir d'un zip (décompression sécurisée) et renvoie son résultat. */
    @Transactional
    public CreatedWorkspace create(UUID userId, String name, byte[] zipBytes) {
        Map<String, byte[]> files = extract(zipBytes);
        if (!files.containsKey(CLAUDE_MD)) {
            files.put(CLAUDE_MD, DEFAULT_CLAUDE_MD);
        }
        Workspace workspace = workspaceRepository.save(Workspace.builder()
                .userId(userId)
                .name(name == null || name.isBlank() ? "Nouveau projet" : name.trim())
                .build());
        String prefix = prefixOf(userId, workspace.getId());
        for (Map.Entry<String, byte[]> entry : files.entrySet()) {
            storage.putFile(prefix + entry.getKey(), entry.getValue(), "text/plain; charset=utf-8");
        }
        announceCreated(workspace);
        return new CreatedWorkspace(workspace, files.size());
    }

    /**
     * Crée un workspace dont les fichiers proviennent d'un <b>dépôt Git</b> (F-31 / SF-31-02) : rien
     * n'est écrit dans le stockage objet, le dépôt sera cloné par le fournisseur à l'ouverture de la
     * session (ADR-015).
     *
     * <p>Aucun {@code CLAUDE.md} n'est semé, contrairement à un import d'archive : le dépôt appartient
     * à l'utilisateur, y injecter un fichier serait une modification non demandée, qui finirait dans
     * sa pull request.</p>
     *
     * @param userId  propriétaire (isolation multi-tenant)
     * @param name    nom du projet (défaut : nom du dépôt)
     * @param repoUrl URL canonique du dépôt (déjà validée par l'appelant)
     * @param owner   propriétaire du dépôt
     * @param repo    nom du dépôt
     * @param branch  branche montée (déjà résolue par l'appelant)
     * @return le workspace créé
     */
    @Transactional
    public Workspace createFromGit(UUID userId, String name, String repoUrl, String owner, String repo,
            String branch) {
        return announceCreated(workspaceRepository.save(Workspace.builder()
                .userId(userId)
                .name(name == null || name.isBlank() ? repo : name.trim())
                .source(WorkspaceSource.GIT)
                .gitRepoUrl(repoUrl)
                .gitOwner(owner)
                .gitRepo(repo)
                .gitBranch(branch)
                .build()));
    }

    /**
     * Crée un projet dont les fichiers vivent <b>déjà sur la machine</b> de l'utilisateur
     * (F-38 / SF-38-15). Ni archive, ni dépôt, ni préfixe de stockage : rien n'est alloué de ce dont
     * on ne se servira jamais — ce qui rend le refus d'import <b>structurel</b> plutôt que défensif.
     *
     * <p>La cible d'exécution est {@code RUNNER} <b>d'emblée et sans alternative</b> (décision D3) :
     * un projet local en cible {@code SANDBOX} ouvrirait une session sur un dossier vide et laisserait
     * croire que le travail a lieu quelque part. La validation d'action est posée dans le même geste,
     * pour la même raison qu'à la bascule.</p>
     *
     * <p>Aucun chemin n'est demandé au client : un navigateur ne peut pas transmettre un chemin du
     * disque, et la gateway n'a aucune raison de connaître l'arborescence de la machine. Le dossier
     * se désigne au lancement du runner, et c'est lui qui déclare la racine de son POSTE (F-48).</p>
     */
    @Transactional
    public Workspace createLocal(UUID userId, String name) {
        String cleaned = name == null ? "" : name.trim();
        if (cleaned.isEmpty()) {
            throw new InvalidArchiveException("Nom de projet requis.");
        }
        if (cleaned.length() > MAX_NAME_LENGTH) {
            throw new InvalidArchiveException("Nom de projet trop long (255 caractères au maximum).");
        }
        return announceCreated(workspaceRepository.save(Workspace.builder()
                .userId(userId)
                .name(cleaned)
                .source(WorkspaceSource.LOCAL)
                .executionTarget(WorkspaceExecutionTarget.RUNNER)
                // La porte de confirmation est ARMÉE PAR DÉFAUT (F-73 / SF-73-02, décision du PO
                // du 2026-09-12 qui retranche OQ-14 — ADR-019). Elle annule le défaut de SF-47-04,
                // pris quand le confinement du runner paraissait exister : il n'existait pas pour
                // bash, et il est retiré depuis SF-73-01. La porte reste réglable projet par projet
                // depuis l'en-tête du terminal ; le journal d'audit et le coupe-circuit, eux, ne se
                // désactivent pas. Posé explicitement ici, même si l'entité porte le même défaut :
                // c'est le chemin de création du mode runner, celui que cette décision vise.
                .agentAskBeforeBash(true)
                .build()));
    }

    /**
     * <b>Ouvre un projet sur un dossier du poste</b> (F-72 / SF-72-01) : la création et le
     * rattachement, <b>en une seule transaction</b>.
     *
     * <p>Pourquoi ensemble : avec deux appels, un échec au second laissait un projet <b>sans
     * poste</b>, portant le nom du client — exactement la seconde « entité EDENRED » que F-72
     * supprime. Ici, ou les deux sont écrits, ou rien ne l'est.</p>
     *
     * <p><b>Aucun nom n'est demandé</b> (décision D5) : le projet prend le <b>dernier segment</b> du
     * chemin, et le nom du <b>poste</b> quand c'est la racine. Le client a déjà été nommé à la
     * connexion du poste ; le redemander est la question de trop.</p>
     *
     * <p>Le runner n'a <b>pas</b> à être connecté : ouvrir un projet est une écriture en base.
     * Exiger une machine joignable empêcherait de préparer ses projets le soir pour le lendemain.
     * C'est la <b>lecture</b> des dossiers (SF-71-02) qui exige le runner, et elle a son refus.</p>
     *
     * @param hostId   poste, dont l'appartenance a <b>déjà</b> été vérifiée par l'appelant
     * @param rawPath  chemin relatif sous la racine ; {@code null} ou vide = la racine du poste
     * @param hostName nom du poste, qui sert de nom au projet quand c'est la racine
     * @throws fr.claudegateway.runner.host.InvalidProjectPathException chemin inexploitable
     * @throws fr.claudegateway.runner.host.HostProjectExistsException  dossier déjà ouvert
     */
    @Transactional
    public Workspace openOnHost(UUID userId, UUID hostId, String rawPath, String hostName) {
        String path = RunnerProjectPath.normalize(rawPath);
        // Doublon refusé (isolation : on ne lit que les projets de l'appelant). C'est le défaut
        // vécu par le PO — ouvrir deux fois le même dossier produisait deux projets du même nom.
        for (Workspace existing : listByHost(userId, hostId)) {
            String occupied = existing.getProjectPath() == null ? "" : existing.getProjectPath();
            if (occupied.equals(path)) {
                throw new HostProjectExistsException(existing.getName(), path);
            }
        }
        Workspace workspace = createLocal(userId, projectNameFor(path, hostName));
        workspace.setHostId(hostId);
        workspace.setProjectPath(path);
        return workspace;
    }

    /**
     * Nom du projet ouvert sur un dossier : le <b>dernier segment</b> du chemin, ou le nom du poste
     * à la racine. Tronqué à la borne de la colonne plutôt que refusé — un dossier au nom très long
     * est un dossier légitime, et échouer ici ferait échouer un geste que rien n'oblige à refuser.
     */
    private static String projectNameFor(String path, String hostName) {
        int slash = path.lastIndexOf('/');
        String candidate = slash < 0 ? path : path.substring(slash + 1);
        if (candidate.isBlank()) {
            candidate = hostName == null ? "" : hostName.trim();
        }
        if (candidate.isBlank()) {
            // Inatteignable en pratique : un poste porte toujours un nom non vide (SF-48-01). La
            // garde évite qu'un jour une valeur vide fasse échouer la création sur « nom requis ».
            candidate = "Projet";
        }
        return candidate.length() > MAX_NAME_LENGTH
                ? candidate.substring(0, MAX_NAME_LENGTH)
                : candidate;
    }

    /**
     * <b>Rattache</b> un projet à un poste (F-48 / SF-48-01) : la machine qui l'exécute, et le
     * chemin du projet <b>relatif à la racine</b> de cette machine.
     *
     * <p>C'est le geste qui remplace l'appairage par dossier. Le poste est appairé une fois ;
     * ouvrir un projet de plus sous sa racine ne demande plus qu'un rattachement — ni code, ni
     * runner, ni connexion supplémentaires.</p>
     *
     * <p>Le chemin est normalisé et refusé s'il sort de la racine ({@link RunnerProjectPath}). Ce
     * n'est pas la garde de sécurité : celle qui fait foi reste celle du runner (décision n° 2 du
     * cadrage, non réversible). C'est le refus d'écrire en base une valeur qu'aucun runner
     * n'accepterait.</p>
     *
     * @param hostId      poste, déjà vérifié possédé par l'appelant, ou {@code null} pour détacher
     * @param projectPath chemin relatif ; {@code null} ou vide = la racine du poste
     */
    @Transactional
    public Workspace attachToHost(UUID userId, UUID id, UUID hostId, String projectPath) {
        Workspace workspace = requireOwned(userId, id);
        if (hostId == null) {
            workspace.setHostId(null);
            workspace.setProjectPath(null);
            return workspace;
        }
        workspace.setHostId(hostId);
        workspace.setProjectPath(RunnerProjectPath.normalize(projectPath));
        return workspace;
    }

    /**
     * <b>Projets</b> rattachés à un poste (isolation {@code user_id}).
     *
     * <p>Le <b>terminal du poste</b> (F-74 / SF-74-01) n'en fait pas partie : ce n'est pas un
     * projet. C'est ici que l'exclusion est posée, une fois, parce que les trois appelants — la
     * carte du poste, le contrôle de doublon d'{@link #openOnHost} et la garde de suppression de
     * F-69 — veulent tous les trois <b>les projets</b>.</p>
     */
    public List<Workspace> listByHost(UUID userId, UUID hostId) {
        return workspaceRepository.findByUserIdAndHostIdAndHostTerminalFalse(userId, hostId);
    }

    /**
     * <b>Le terminal du poste</b> (F-74 / SF-74-01) : celui qui existe, ou celui qu'on crée.
     *
     * <p>Ce qui manquait : le premier jour d'une mission, la racine est vide — pas de projet, donc
     * pas de terminal, donc aucun moyen de cloner un dépôt depuis le produit. Et au-delà du premier
     * jour, beaucoup de gestes n'appartiennent à aucun projet : {@code git}, un VPN,
     * {@code terraform}, l'installation d'un outil.</p>
     *
     * <p><b>Un terminal comme les autres</b> : c'est une ligne de {@code workspaces}, donc il a sa
     * conversation, son historique, ses réglages, il compte dans le plafond de quatre terminaux
     * vivants (F-70) et il suit la porte de confirmation (F-73). Rien de tout cela n'est écrit
     * ici — tout vient de ce que le terminal d'un projet fait déjà.</p>
     *
     * <p><b>Idempotent</b> : rappeler rend le même terminal. Un second terminal de poste sur la même
     * machine n'aurait aucun sens et couperait la conversation en deux.</p>
     *
     * <p>Créé par {@link #createLocal} : mêmes valeurs initiales qu'un projet local — {@code LOCAL},
     * cible {@code RUNNER}, porte de confirmation armée — et surtout <b>le même événement de
     * création</b>, donc le même héritage de gouvernance depuis le poste (F-75).</p>
     *
     * @param hostId poste, dont l'appartenance a <b>déjà</b> été vérifiée par l'appelant
     */
    @Transactional
    public Workspace openHostTerminal(UUID userId, UUID hostId) {
        return findHostTerminal(userId, hostId).orElseGet(() -> {
            Workspace terminal = createLocal(userId, HOST_TERMINAL_NAME);
            terminal.setHostId(hostId);
            // La RACINE du poste : c'est là qu'on clone, qu'on installe, qu'on configure. Depuis
            // F-73 / SF-73-01 ce chemin n'est plus une borne — c'est un dossier de départ.
            terminal.setProjectPath("");
            terminal.setHostTerminal(true);
            return terminal;
        });
    }

    /** Le terminal d'un poste, s'il a déjà été ouvert (isolation {@code user_id}). */
    public Optional<Workspace> findHostTerminal(UUID userId, UUID hostId) {
        return workspaceRepository.findFirstByUserIdAndHostIdAndHostTerminalTrue(userId, hostId);
    }

    /**
     * Supprime le terminal d'un poste s'il en a un, avec tout ce qui y pend — conversation, journal,
     * fichiers. Sans effet s'il n'y en a pas.
     *
     * <p>Appelé à la <b>suppression du poste</b> (F-69) : un terminal de poste ne désigne plus rien
     * sans sa machine, et le laisser vivre ferait une ligne orpheline qu'aucun écran ne montre.
     * Ce n'est pas une cascade sur les <b>projets</b> — ceux-là continuent de refuser la
     * suppression tant qu'ils sont là.</p>
     */
    @Transactional
    public void deleteHostTerminal(UUID userId, UUID hostId) {
        findHostTerminal(userId, hostId).ifPresent(terminal -> delete(userId, terminal.getId()));
    }


    /**
     * Projets <b>sans poste</b> (F-71 / SF-71-01), isolation {@code user_id}.
     *
     * <p>Ce sont ceux qui vivent chez la gateway plutôt que sur une machine : dépôt GitHub, archive
     * importée, ou projet pas encore rattaché. L'accueil de la Forge les range sous un poste
     * <b>virtuel</b> — une vue, jamais une ligne en base.</p>
     */
    public List<Workspace> listWithoutHost(UUID userId) {
        return workspaceRepository.findByUserIdAndHostIdIsNull(userId);
    }

    /** Workspaces de l'utilisateur (isolation {@code user_id}). */
    public List<Workspace> list(UUID userId) {
        return workspaceRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    /** Workspace possédé par l'utilisateur, ou 404. */
    public Workspace requireOwned(UUID userId, UUID id) {
        return workspaceRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new WorkspaceNotFoundException("Workspace introuvable : " + id));
    }

    /** Arborescence : chemins relatifs des fichiers du workspace, triés. */
    public List<String> tree(UUID userId, UUID id) {
        requireOwned(userId, id);
        String prefix = prefixOf(userId, id);
        List<String> paths = new ArrayList<>();
        for (String key : storage.listKeys(prefix)) {
            paths.add(key.substring(prefix.length()));
        }
        paths.sort(String::compareTo);
        return paths;
    }

    /** Contenu texte d'un fichier du workspace. */
    public String readFile(UUID userId, UUID id, String path) {
        requireOwned(userId, id);
        String rel = normalizeRelPath(path);
        byte[] content = storage.getFile(prefixOf(userId, id) + rel)
                .orElseThrow(() -> new WorkspaceNotFoundException("Fichier introuvable : " + rel));
        return new String(content, StandardCharsets.UTF_8);
    }

    /** Écrit (ou remplace) le contenu texte d'un fichier du workspace. */
    @Transactional
    public void writeFile(UUID userId, UUID id, String path, String content) {
        // Les fichiers d'un projet local vivent sur la machine : les écrire ici créerait une seconde
        // vérité, exactement ce que SF-31-12/13 a coûté trois subfeatures à résoudre sur les projets
        // Git. Refus AVANT toute écriture (F-38 / SF-38-15).
        if (requireOwned(userId, id).isLocal()) {
            throw new LocalWorkspaceException(
                    "Ce projet vit sur votre machine : ses fichiers s'écrivent par le runner, pas ici.");
        }
        Workspace workspace = requireOwned(userId, id);
        String rel = normalizeRelPath(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxFileBytes()) {
            throw new InvalidArchiveException("Fichier trop volumineux.");
        }
        storage.putFile(prefixOf(userId, id) + rel, bytes, "text/plain; charset=utf-8");
        workspaceRepository.save(workspace); // rafraîchit updated_at
    }

    /**
     * Renomme le projet (F-28 / SF-28-16). Isolation d'abord, comme tout accès à un workspace.
     *
     * <p>Le renommage ne touche <b>que</b> l'étiquette : ni les fichiers (rangés sous l'identifiant du
     * workspace, jamais sous son nom), ni la session sandbox, ni l'historique de conversation.</p>
     *
     * @param userId utilisateur propriétaire (isolation)
     * @param id     workspace à renommer
     * @param name   nouveau nom ; élagué, refusé s'il est vide ou trop long
     * @return le workspace renommé
     * @throws InvalidArchiveException si le nom est vide ou dépasse la longueur de la colonne
     */
    @Transactional
    public Workspace renameWorkspace(UUID userId, UUID id, String name) {
        Workspace workspace = requireOwned(userId, id);
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty()) {
            throw new InvalidArchiveException("Le nom du projet ne peut pas être vide.");
        }
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new InvalidArchiveException("Le nom du projet est trop long (255 caractères au plus).");
        }
        workspace.setName(trimmed);
        return workspaceRepository.save(workspace);
    }

    /**
     * Change la <b>cible d'exécution</b> du projet (F-38 / SF-38-05, décision D1) : {@code SANDBOX}
     * (historique) ou {@code RUNNER} (machine de l'utilisateur). Isolation d'abord, comme tout accès.
     *
     * <p>La cible est une dimension <b>indépendante de la source</b> : un dépôt Git travaillé sur la
     * machine de l'utilisateur est un couple {@code GIT} + {@code RUNNER} légitime. Aucun fichier
     * n'est déplacé : basculer la cible change <b>où</b> les outils s'exécutent, pas où le projet a
     * été importé.</p>
     *
     * @param userId propriétaire (isolation)
     * @param id     workspace visé
     * @param target nouvelle cible, obligatoire
     * @return le workspace à jour
     */
    @Transactional
    public Workspace setExecutionTarget(UUID userId, UUID id, WorkspaceExecutionTarget target) {
        Workspace workspace = requireOwned(userId, id);
        if (target == null) {
            throw new InvalidArchiveException("Cible d'exécution requise.");
        }
        // Un projet local n'existe que sur la machine (F-38 / SF-38-15, D3) : le bac à sable n'a pas
        // ce dossier, et une session s'y ouvrirait vide. Un refus vaut mieux qu'une bascule qui ment.
        if (workspace.isLocal() && target != WorkspaceExecutionTarget.RUNNER) {
            throw new LocalWorkspaceException(
                    "Ce projet vit sur votre machine : il s'exécute par le runner, pas dans le bac à sable.");
        }
        workspace.setExecutionTarget(target);
        // La bascule ne touche PLUS au réglage « demander avant d'exécuter » (F-47 / SF-47-04).
        // La décision D7 (F-38 / SF-38-08) l'armait à chaque passage en cible RUNNER ; son motif —
        // `always_allow` acceptable dans un conteneur jetable, pas sur une vraie machine — est
        // précisément celui que le PO a tranché dans l'autre sens le 2026-09-10. La laisser en
        // place rendrait le nouveau défaut inopérant dès la première bascule, et réarmerait la
        // porte DANS LE DOS d'un utilisateur qui l'avait éteinte — au retour d'un coupe-circuit,
        // qui repasse le projet en SANDBOX puis, au ré-appairage, en RUNNER.
        return workspaceRepository.save(workspace);
    }

    /**
     * Supprime le projet <b>côté gateway, et uniquement là</b> (F-69) : les fichiers du stockage
     * objet de la gateway, la <b>conversation</b>, les <b>réglages</b> de gouvernance, le
     * <b>journal</b> du runner, puis la ligne du projet.
     *
     * <p><b>Le dossier sur la machine de l'utilisateur n'est jamais touché</b>, et il ne peut pas
     * l'être : aucune ligne de cette méthode n'ouvre le canal runner, n'émet de commande ni ne lit
     * un chemin de machine. Le {@code project_path} du projet n'est pas relu. C'est une limite de
     * périmètre tranchée par le PO — supprimer les fichiers d'un client depuis une application web
     * serait irréversible et illégitime — et l'écran l'écrit avant de demander confirmation.</p>
     *
     * <p>Les messages ont été ajoutés par SF-11-03 : sans eux, l'historique des sessions d'agent
     * survivait à son workspace, sans plus aucun moyen d'y accéder ni de le purger. Les
     * <b>activations de gouvernance</b> (F-51 / SF-51-02) suivent la même règle et pour la même
     * raison : elles ne désignent plus rien une fois le projet parti. Le <b>journal du runner</b>
     * (F-69 / SF-69-01) la suit à son tour : il porte des commandes exécutées et des chemins lus,
     * et sa seule lecture était celle d'un projet qui n'existe plus.</p>
     *
     * <p><b>Ce qui ne part pas</b> : {@code usage_turns}, le relevé de consommation par tour
     * (F-61). Ce sont des pièces de facturation — la dépense a eu lieu — et
     * {@code UsageByClientService} sait déjà nommer « supprimé » un projet absent. Les effacer
     * ferait rétrécir une consommation déjà facturée.</p>
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Workspace workspace = requireOwned(userId, id);
        storage.deletePrefix(prefixOf(userId, id));
        atelierMessageRepository.deleteByWorkspaceId(id);
        // Plus aucune activation de gouvernance à purger ici : depuis F-75, elles vivent sur le
        // POSTE, et supprimer un dossier ne doit surtout pas éteindre la gouvernance de la machine.
        runnerAudit.deleteByUserIdAndWorkspaceId(userId, id);
        workspaceRepository.delete(workspace);
    }

    /**
     * Supprime un fichier du workspace. Isolation d'abord ({@code user_id}), puis mêmes garde-fous de
     * chemin que la lecture/écriture. Un fichier inexistant lève la même exception « fichier
     * introuvable » que {@link #readFile}.
     */
    @Transactional
    public void deleteFile(UUID userId, UUID id, String path) {
        Workspace workspace = requireOwned(userId, id);
        String rel = normalizeRelPath(path);
        String key = prefixOf(userId, id) + rel;
        if (storage.getFile(key).isEmpty()) {
            throw new WorkspaceNotFoundException("Fichier introuvable : " + rel);
        }
        storage.deleteFile(key);
        workspaceRepository.save(workspace); // rafraîchit updated_at
    }

    /**
     * Renomme (déplace) un fichier du workspace : lit {@code from} (404 si absent), écrit son contenu
     * sous {@code to} (validé par les garde-fous d'écriture), puis supprime {@code from}. Réutilise la
     * logique interne ({@link #readFile}/{@link #writeFile}/{@link #deleteFile}) — aucun garde-fou
     * dupliqué. Un renommage vers la même destination est un no-op sûr (pas d'auto-suppression).
     */
    @Transactional
    public void renameFile(UUID userId, UUID id, String from, String to) {
        requireOwned(userId, id);
        String fromRel = normalizeRelPath(from);
        String toRel = normalizeRelPath(to);
        String content = readFile(userId, id, from);
        writeFile(userId, id, to, content);
        if (!fromRel.equals(toRel)) {
            deleteFile(userId, id, from);
        }
    }

    /**
     * Exporte le workspace entier en archive {@code .zip} (en mémoire). Chaque fichier devient une
     * entrée dont le nom est son chemin relatif (sans le préfixe de stockage) : round-trip cohérent
     * avec {@link #extract} (une réimportation redonne les mêmes chemins).
     */
    public byte[] exportZip(UUID userId, UUID id) {
        requireOwned(userId, id);
        String prefix = prefixOf(userId, id);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(out)) {
            for (String key : storage.listKeys(prefix)) {
                String rel = key.substring(prefix.length());
                byte[] content = storage.getFile(key).orElse(new byte[0]);
                zos.putNextEntry(new ZipEntry(rel));
                zos.write(content);
                zos.closeEntry();
            }
        } catch (IOException ex) {
            throw new InvalidArchiveException("Export de l'archive impossible.");
        }
        return out.toByteArray();
    }

    // ---------------------------------------------------------------- helpers

    private String prefixOf(UUID userId, UUID workspaceId) {
        return properties.prefix() + userId + "/" + workspaceId + "/";
    }

    /**
     * Décompresse le zip en un dictionnaire {chemin relatif -> contenu}, en appliquant les garde-fous :
     * zip-slip (entrée hors racine ignorée), zip-bomb (plafonds nb d'entrées / taille par fichier /
     * taille totale, mesurés sur les octets réellement lus). Dossiers et fichiers vides ignorés.
     */
    private Map<String, byte[]> extract(byte[] zipBytes) {
        if (zipBytes == null || zipBytes.length == 0) {
            throw new InvalidArchiveException("Archive vide.");
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        long total = 0;
        int count = 0;
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(zipBytes))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                try {
                    if (entry.isDirectory()) {
                        continue;
                    }
                    String rel = safeRelativeOrNull(entry.getName());
                    if (rel == null) {
                        continue; // zip-slip ou chemin invalide : ignoré
                    }
                    if (++count > properties.maxEntries()) {
                        throw new InvalidArchiveException("Archive trop volumineuse (trop de fichiers).");
                    }
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    long fileBytes = 0;
                    int read;
                    while ((read = zis.read(buffer)) != -1) {
                        fileBytes += read;
                        total += read;
                        if (fileBytes > properties.maxFileBytes()) {
                            throw new InvalidArchiveException("Un fichier de l'archive est trop volumineux.");
                        }
                        if (total > properties.maxTotalBytes()) {
                            throw new InvalidArchiveException("Archive décompressée trop volumineuse.");
                        }
                        out.write(buffer, 0, read);
                    }
                    if (out.size() > 0) {
                        files.put(rel, out.toByteArray());
                    }
                } finally {
                    zis.closeEntry();
                }
            }
        } catch (IOException ex) {
            throw new InvalidArchiveException("Archive illisible.");
        }
        return files;
    }

    /**
     * Chemin relatif sûr (ou {@code null} si à ignorer). Refuse toute traversée ({@code ..}) et tout
     * chemin absolu ; normalise les séparateurs et supprime les segments {@code .}.
     */
    private String safeRelativeOrNull(String name) {
        if (name == null) {
            return null;
        }
        String normalized = name.replace('\\', '/').trim();
        if (normalized.isEmpty() || normalized.startsWith("/")) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        for (String segment : normalized.split("/")) {
            if (segment.isEmpty() || segment.equals(".")) {
                continue;
            }
            if (segment.equals("..")) {
                return null; // zip-slip
            }
            parts.add(segment);
        }
        return parts.isEmpty() ? null : String.join("/", parts);
    }

    /** Comme {@link #safeRelativeOrNull} mais lève 400 si le chemin est invalide (endpoints fichier). */
    private String normalizeRelPath(String path) {
        String rel = safeRelativeOrNull(path);
        if (rel == null) {
            throw new InvalidFilePathException("Chemin de fichier invalide.");
        }
        return rel;
    }

    /** Résultat d'une création de workspace. */
    public record CreatedWorkspace(Workspace workspace, int fileCount) {
    }
}
