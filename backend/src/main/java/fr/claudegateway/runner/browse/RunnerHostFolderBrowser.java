package fr.claudegateway.runner.browse;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.runner.audit.RunnerAuditService;
import fr.claudegateway.runner.channel.RunnerCallResult;
import fr.claudegateway.runner.channel.RunnerErrorCodes;
import fr.claudegateway.runner.channel.RunnerTarget;
import fr.claudegateway.runner.exec.RunnerToolGateway;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.runner.host.RunnerProjectPath;
import fr.claudegateway.runner.host.dto.HostFoldersResponse;
import fr.claudegateway.runner.host.dto.HostFoldersResponse.HostFolder;

/**
 * Les <b>sous-dossiers d'un poste</b>, lus sur la machine de l'utilisateur (F-71 / SF-71-02).
 *
 * <p>Pourquoi : le dossier d'un nouveau projet se <b>tapait</b>. Une faute de frappe créait un
 * projet vide qui n'échouait qu'au <b>premier usage</b>, quand plus personne ne fait le lien avec la
 * frappe. Décision du PO : le runner liste, et l'on <b>clique</b>.</p>
 *
 * <p><b>Aucun outil runner nouveau</b> (arbitrage A2) : c'est le {@code list_files} de SF-38-17, le
 * même que celui de l'explorateur de projet, dont on <b>dérive</b> les dossiers. Un outil
 * {@code list_dirs} obligerait chaque runner déjà installé à être mis à jour pour que l'écran
 * marche — exactement la friction que F-48 a supprimée.</p>
 *
 * <p><b>Ce qui ne quitte jamais la machine</b> reste décidé par la machine : les exclusions
 * {@code .runnerignore} et la liste de bruit de SF-38-21 sont appliquées par le runner, avant
 * émission. Ce service n'ajoute qu'un filtre d'<b>écran</b> — les dossiers cachés, qui sont de
 * l'outillage et jamais un projet.</p>
 *
 * <p>Chaque lecture est <b>auditée</b> sous un nom d'outil qui lui est propre : le journal doit
 * pouvoir dire ce qui a été lu sur la machine, et pourquoi.</p>
 */
@Service
public class RunnerHostFolderBrowser {

    /**
     * Nom d'outil du journal, distinct de {@code screen_list_files} (l'explorateur d'un projet) et
     * de {@code list_files} (l'agent) : trois raisons de lire, trois lignes différentes.
     */
    static final String SCREEN_LIST_FOLDERS = "screen_list_folders";

    /**
     * Plafond de dossiers rendus. Un sélecteur de 500 entrées est déjà trop long à lire ; en rendre
     * cinq mille ne rendrait service à personne — et la troncature, elle, se <b>dit</b>.
     */
    static final int MAX_FOLDERS = 500;

    private final RunnerToolGateway gateway;
    private final RunnerAuditService auditService;
    private final RunnerHostService hostService;
    private final WorkspaceService workspaceService;

    public RunnerHostFolderBrowser(RunnerToolGateway gateway, RunnerAuditService auditService,
            RunnerHostService hostService, WorkspaceService workspaceService) {
        this.gateway = gateway;
        this.auditService = auditService;
        this.hostService = hostService;
        this.workspaceService = workspaceService;
    }

    /**
     * Sous-dossiers immédiats de {@code rawPath} sous la racine du poste.
     *
     * <p><b>Isolation</b> : l'appartenance du poste est vérifiée <b>avant</b> tout — un
     * identifiant reçu du client ne sert jamais à joindre une machine sans ce contrôle.</p>
     *
     * @param userId  propriétaire, issu du JWT
     * @param hostId  poste à parcourir
     * @param rawPath chemin relatif sous la racine ; vide ou {@code null} = la racine elle-même
     * @throws fr.claudegateway.runner.host.RunnerHostNotFoundException poste non possédé
     * @throws fr.claudegateway.runner.host.InvalidProjectPathException chemin inexploitable
     * @throws RunnerBrowseException runner injoignable, ou lecture refusée par la machine
     */
    public HostFoldersResponse folders(UUID userId, UUID hostId, String rawPath) {
        hostService.requireOwned(userId, hostId);
        // Normalisé AVANT tout appel : un chemin qu'aucun runner n'accepterait n'a pas à traverser
        // le réseau. La garde qui fait foi reste celle du runner (SF-48-02).
        String path = RunnerProjectPath.normalize(rawPath);

        String callId = UUID.randomUUID().toString();
        // workspaceId NUL : c'est un appel de POSTE, aucun projet n'est concerné — et il n'a donc
        // pas à être annulable par l'interruption d'un projet voisin.
        RunnerTarget target = new RunnerTarget(hostId, null, path);
        RunnerCallResult result = gateway.listFiles(target, callId);
        auditService.recordCall(userId, target, callId, SCREEN_LIST_FOLDERS,
                path.isEmpty() ? "la racine" : path, result);
        requireOk(result);

        List<String> names = folderNames(result.content());
        boolean truncated = result.truncated() || names.size() > MAX_FOLDERS;
        List<String> kept = names.size() > MAX_FOLDERS ? names.subList(0, MAX_FOLDERS) : names;

        Set<String> taken = occupiedPaths(userId, hostId);
        List<HostFolder> folders = new ArrayList<>(kept.size());
        for (String name : kept) {
            String full = path.isEmpty() ? name : path + "/" + name;
            folders.add(new HostFolder(name, full, taken.contains(full)));
        }
        return new HostFoldersResponse(path, parentOf(path), List.copyOf(folders), truncated);
    }

    // ------------------------------------------------------------------ interne

    /**
     * Noms des sous-dossiers <b>immédiats</b>, dérivés des chemins de fichiers rendus par le runner :
     * tout chemin qui contient un séparateur commence par un dossier.
     *
     * <p>Conséquence assumée : un dossier <b>entièrement vide</b> — ou dont tout le contenu est
     * exclu — n'apparaît pas, puisqu'aucun fichier ne le nomme. Ouvrir un projet sur un dossier vide
     * n'est pas le geste que F-71 sert ; et « créer un dossier depuis l'application » est
     * explicitement hors périmètre.</p>
     *
     * <p>Les dossiers <b>cachés</b> sont écartés (arbitrage A5) : {@code .git}, {@code .claude},
     * {@code .idea} sont de l'outillage, jamais un projet, et les proposer ferait cliquer dessus.</p>
     */
    static List<String> folderNames(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }
        // Ordre lisible à l'œil (insensible à la casse), mais unicité EXACTE : sous Linux,
        // « Clients » et « clients » sont deux dossiers, et en fondre un dans l'autre ferait
        // disparaître un projet du sélecteur.
        Set<String> names = new TreeSet<>(java.util.Comparator
                .comparing((String name) -> name.toLowerCase(java.util.Locale.ROOT))
                .thenComparing(java.util.Comparator.naturalOrder()));
        for (String line : content.split("\n")) {
            String path = line.strip();
            int slash = path.indexOf('/');
            if (slash <= 0) {
                // Un fichier à la racine du chemin parcouru : ce n'est pas un dossier. Le marqueur
                // de troncature de SF-38-21 n'en contient pas non plus — il ne peut donc pas
                // devenir un faux dossier.
                continue;
            }
            String name = path.substring(0, slash);
            if (name.startsWith(".")) {
                continue;
            }
            names.add(name);
        }
        return List.copyOf(names);
    }

    /** Chemins déjà occupés par un projet de ce poste — on ne propose pas deux fois le même dossier. */
    private Set<String> occupiedPaths(UUID userId, UUID hostId) {
        Set<String> taken = new LinkedHashSet<>();
        for (Workspace workspace : workspaceService.listByHost(userId, hostId)) {
            String path = workspace.getProjectPath();
            if (path != null && !path.isBlank()) {
                taken.add(path);
            }
        }
        return taken;
    }

    /** Dossier parent, ou {@code null} à la racine — c'est ce qui permet de remonter. */
    static String parentOf(String path) {
        if (path == null || path.isEmpty()) {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : path.substring(0, slash);
    }

    /**
     * Traduit un refus du runner. La distinction qui compte pour l'utilisateur est entre « le runner
     * n'est pas connecté » — un état, réparable en le lançant — et « la machine a refusé », qui
     * appelle autre chose.
     *
     * <p><b>Jamais une liste vide</b> dans ces cas (décision du PO) : un sélecteur vide laisserait
     * croire à une racine sans sous-dossier, et renverrait l'utilisateur au champ libre qu'on
     * supprime précisément.</p>
     */
    private static void requireOk(RunnerCallResult result) {
        if (result.ok()) {
            return;
        }
        boolean offline = RunnerErrorCodes.RUNNER_UNAVAILABLE.equals(result.errorCode())
                || RunnerErrorCodes.RUNNER_NOT_ON_THIS_NODE.equals(result.errorCode())
                || RunnerErrorCodes.RUNNER_TIMEOUT.equals(result.errorCode());
        if (offline) {
            throw new RunnerBrowseException("Le runner de ce poste n'est pas connecté : "
                    + "lancez-le sur la machine pour choisir un dossier.");
        }
        throw new RunnerBrowseException(result.errorMessage() == null
                ? "Lecture refusée par la machine."
                : result.errorMessage());
    }
}
