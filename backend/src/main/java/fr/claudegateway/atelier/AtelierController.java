package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import fr.claudegateway.atelier.WorkspaceService.CreatedWorkspace;
import fr.claudegateway.atelier.agent.AtelierSessionService;
import fr.claudegateway.atelier.dto.AtelierEngineResponse;
import fr.claudegateway.atelier.dto.AtelierImportLibraryRequest;
import fr.claudegateway.atelier.dto.ExecutionTargetRequest;
import fr.claudegateway.atelier.dto.FileContentResponse;
import fr.claudegateway.atelier.dto.CreateLocalWorkspaceRequest;
import fr.claudegateway.atelier.dto.RenameFileRequest;
import fr.claudegateway.atelier.dto.RenameWorkspaceRequest;
import fr.claudegateway.atelier.dto.WorkspaceDetailResponse;
import fr.claudegateway.atelier.dto.WorkspaceSummaryResponse;
import fr.claudegateway.atelier.dto.WriteFileRequest;
import fr.claudegateway.atelier.git.GitWorkspaceService;
import fr.claudegateway.atelier.git.GitWorkspaceService.WorkspaceContent;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoints de l'Atelier (F-28 / SF-28-01). L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 */
@RestController
@RequestMapping("/workspaces")
public class AtelierController {

    private static final Logger log = LoggerFactory.getLogger(AtelierController.class);

    private final WorkspaceService workspaceService;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;
    private final WorkspaceLibraryImportService libraryImportService;
    private final AtelierSessionService sessionService;
    private final GitWorkspaceService gitWorkspaceService;
    private final AtelierEngineService engineService;
    /** Lecture des fichiers sur la machine de l'utilisateur (F-38 / SF-38-17). */
    private final fr.claudegateway.runner.browse.RunnerWorkspaceBrowser runnerBrowser;

    public AtelierController(WorkspaceService workspaceService, CurrentUser currentUser,
            AtelierAccessService atelierAccess, WorkspaceLibraryImportService libraryImportService,
            AtelierSessionService sessionService, GitWorkspaceService gitWorkspaceService,
            AtelierEngineService engineService,
            fr.claudegateway.runner.browse.RunnerWorkspaceBrowser runnerBrowser) {
        this.workspaceService = workspaceService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
        this.libraryImportService = libraryImportService;
        this.sessionService = sessionService;
        this.gitWorkspaceService = gitWorkspaceService;
        this.engineService = engineService;
        this.runnerBrowser = runnerBrowser;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public WorkspaceDetailResponse create(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "name", required = false) String name) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        CreatedWorkspace created = workspaceService.create(userId, name, readBytes(file));
        return WorkspaceDetailResponse.from(
                created.workspace(), workspaceService.tree(userId, created.workspace().getId()));
    }

    /**
     * Ouvre un projet qui vit <b>déjà sur la machine</b> de l'utilisateur (F-38 / SF-38-15).
     *
     * <p>Le corps ne porte qu'un <b>nom</b> : aucun chemin n'est transmis, et aucun ne le sera. Le
     * dossier se désigne au lancement du runner (<code>--workspace</code>), qui déclarera ensuite sa
     * racine à l'appairage — la gateway n'apprenant au plus que le nom du dossier.</p>
     */
    @PostMapping("/local")
    public WorkspaceDetailResponse createLocal(@Valid @RequestBody CreateLocalWorkspaceRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.createLocal(userId, request.name());
        // Un projet local n'a pas d'arborescence chez nous : elle vit sur la machine, et sera lue à
        // la demande par le runner (SF-38-17). Annoncer autre chose serait mentir.
        return WorkspaceDetailResponse.from(workspace, List.of());
    }

    @GetMapping
    public List<WorkspaceSummaryResponse> list() {
        atelierAccess.requireAccess();
        return workspaceService.list(currentUser.requireId()).stream()
                .map(WorkspaceSummaryResponse::from)
                .toList();
    }

    /**
     * Détail d'un projet : métadonnées + arborescence. Sur un projet Git (F-31 / SF-31-03),
     * l'arborescence vient du dépôt <b>et</b> des fichiers déjà réécrits par la session.
     */
    @GetMapping("/{id}")
    public WorkspaceDetailResponse detail(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        // Cible RUNNER (F-38 / SF-38-17) : les fichiers vivent sur la machine, et le stockage objet
        // est vide par construction. Lire là-bas afficherait un projet inexistant.
        //
        // Une machine éteinte ne rend PAS le projet inaccessible : ce détail porte aussi son nom, sa
        // source et sa cible, qui n'ont rien à voir avec les fichiers. On rend donc l'arborescence
        // vide plutôt qu'une erreur — l'écran sonde déjà l'état du runner (SF-38-06) et sait dire
        // « hors ligne » sans ambiguïté. La lecture d'un fichier, elle, échoue explicitement : là,
        // l'utilisateur a demandé un contenu précis, et le silence serait un mensonge.
        if (workspace.isRunnerTarget()) {
            return WorkspaceDetailResponse.from(workspace, runnerBrowser.treeOrEmpty(workspace));
        }
        WorkspaceContent content = gitWorkspaceService.tree(userId, workspace);
        return WorkspaceDetailResponse.from(workspace, content.files(), content.truncated());
    }

    @GetMapping("/{id}/file")
    public FileContentResponse readFile(@PathVariable UUID id, @RequestParam("path") String path) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        if (workspace.isRunnerTarget()) {
            return new FileContentResponse(path, runnerBrowser.readFile(workspace, path));
        }
        return new FileContentResponse(path, gitWorkspaceService.readFile(userId, workspace, path));
    }

    @PutMapping("/{id}/file")
    public ResponseEntity<Void> writeFile(
            @PathVariable UUID id,
            @RequestParam("path") String path,
            @RequestBody WriteFileRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        // Projet Git compris (SF-31-12) : le stockage porte le travail en cours, de l'écran
        // comme de l'agent, et la branche porte le publié. L'ancien refus est levé.
        workspaceService.requireOwned(userId, id);
        workspaceService.writeFile(userId, id, path, request == null ? "" : request.content());
        return ResponseEntity.noContent().build();
    }

    /**
     * Moteur qui anime le terminal de ce projet (F-39 / SF-39-07, décision D1) : l'écran le
     * <b>lit</b>, il ne le déduit plus. Rend aussi l'état du runner et, le cas échéant, la limite du
     * bac à sable qui justifie de proposer le runner ici et maintenant (D6).
     */
    @GetMapping("/{id}/engine")
    public AtelierEngineResponse engine(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        return AtelierEngineResponse.from(engineService.status(currentUser.requireId(), id));
    }

    /**
     * Bascule la <b>cible d'exécution</b> du projet (F-38 / SF-38-05) : {@code SANDBOX} (historique)
     * ou {@code RUNNER} (les outils s'exécutent sur la machine de l'utilisateur, via le runner). La
     * cible est indépendante de la source : un projet Git peut très bien s'exécuter sur la machine.
     */
    @PutMapping("/{id}/execution-target")
    public WorkspaceDetailResponse setExecutionTarget(
            @PathVariable UUID id, @Valid @RequestBody ExecutionTargetRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.setExecutionTarget(userId, id, request.executionTarget());
        return WorkspaceDetailResponse.from(workspace, workspaceService.tree(userId, id));
    }

    /**
     * Importe le texte de documents de la bibliothèque personnelle (F-08) dans le workspace, sous
     * {@code bibliotheque/<nom>.md}. Isolation appliquée dans le service : workspace possédé requis,
     * documents relus sous double filtre {@code id} + {@code user_id}.
     */
    @PostMapping("/{id}/import-library")
    public WorkspaceDetailResponse importLibrary(
            @PathVariable UUID id, @Valid @RequestBody AtelierImportLibraryRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        gitWorkspaceService.requireWritable(workspaceService.requireOwned(userId, id));
        List<String> tree = libraryImportService.importDocuments(userId, id, request.documentIds());
        return WorkspaceDetailResponse.from(workspaceService.requireOwned(userId, id), tree);
    }

    /**
     * Renomme le projet (F-28 / SF-28-16) et renvoie son détail à jour. Le nom est une étiquette :
     * les fichiers, la session sandbox et l'historique ne bougent pas.
     */
    @PostMapping("/{id}/rename")
    public WorkspaceDetailResponse renameWorkspace(
            @PathVariable UUID id, @Valid @RequestBody RenameWorkspaceRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace renamed = workspaceService.renameWorkspace(userId, id, request.name());
        return WorkspaceDetailResponse.from(renamed, workspaceService.tree(userId, id));
    }

    /**
     * Supprime le workspace (204). Sa session sandbox est terminée d'abord (F-30 SF-30-04) : sans
     * cela, supprimer un projet laisserait une sandbox orpheline détenant son état. La terminaison
     * est <b>best-effort</b> — un fournisseur indisponible ne doit jamais empêcher une suppression
     * demandée par l'utilisateur.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        try {
            sessionService.resetSession(userId, id);
        } catch (WorkspaceNotFoundException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            log.debug("Terminaison de session ignorée avant suppression du workspace (best-effort).");
        }
        workspaceService.delete(userId, id);
        return ResponseEntity.noContent().build();
    }

    /** Supprime un fichier du workspace (204). Fichier inexistant => 404. */
    @DeleteMapping("/{id}/file")
    public ResponseEntity<Void> deleteFile(@PathVariable UUID id, @RequestParam("path") String path) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        gitWorkspaceService.requireWritable(workspaceService.requireOwned(userId, id));
        workspaceService.deleteFile(userId, id, path);
        return ResponseEntity.noContent().build();
    }

    /** Renomme (déplace) un fichier du workspace et renvoie l'arborescence à jour. */
    @PostMapping("/{id}/file/rename")
    public WorkspaceDetailResponse rename(
            @PathVariable UUID id, @Valid @RequestBody RenameFileRequest request) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        gitWorkspaceService.requireWritable(workspaceService.requireOwned(userId, id));
        workspaceService.renameFile(userId, id, request.from(), request.to());
        return WorkspaceDetailResponse.from(
                workspaceService.requireOwned(userId, id), workspaceService.tree(userId, id));
    }

    /** Exporte le workspace entier en archive {@code .zip} téléchargeable. */
    @GetMapping("/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        Workspace workspace = workspaceService.requireOwned(userId, id);
        // Sur un projet Git, la source de vérité est le dépôt : exporter le stockage livrerait
        // quelques fichiers rapatriés en les présentant comme le projet entier.
        gitWorkspaceService.requireWritable(workspace);
        String filename = sanitizeFilename(workspace.getName()) + ".zip";
        byte[] zip = workspaceService.exportZip(userId, id);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .contentType(MediaType.parseMediaType("application/zip"))
                .body(zip);
    }

    /** Assainit le nom de workspace pour un nom de fichier sûr ({@code [^A-Za-z0-9._-] -> _}). */
    private String sanitizeFilename(String name) {
        String base = (name == null || name.isBlank()) ? "workspace" : name.trim();
        return base.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /** Lit les octets du fichier multipart ; un flux illisible => archive invalide (400). */
    private byte[] readBytes(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new InvalidArchiveException("Archive vide.");
        }
        try {
            return file.getBytes();
        } catch (IOException ex) {
            throw new InvalidArchiveException("Archive illisible.");
        }
    }
}
