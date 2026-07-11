package fr.claudegateway.atelier;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

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
import fr.claudegateway.atelier.dto.AtelierImportLibraryRequest;
import fr.claudegateway.atelier.dto.FileContentResponse;
import fr.claudegateway.atelier.dto.WorkspaceDetailResponse;
import fr.claudegateway.atelier.dto.WorkspaceSummaryResponse;
import fr.claudegateway.atelier.dto.WriteFileRequest;
import fr.claudegateway.auth.CurrentUser;
import jakarta.validation.Valid;

/**
 * Endpoints de l'Atelier (F-28 / SF-28-01). L'identité provient exclusivement du {@link CurrentUser}
 * (JWT) : l'isolation {@code user_id} est appliquée dans le service, jamais depuis un paramètre client.
 */
@RestController
@RequestMapping("/workspaces")
public class AtelierController {

    private final WorkspaceService workspaceService;
    private final CurrentUser currentUser;
    private final AtelierAccessService atelierAccess;
    private final WorkspaceLibraryImportService libraryImportService;

    public AtelierController(WorkspaceService workspaceService, CurrentUser currentUser,
            AtelierAccessService atelierAccess, WorkspaceLibraryImportService libraryImportService) {
        this.workspaceService = workspaceService;
        this.currentUser = currentUser;
        this.atelierAccess = atelierAccess;
        this.libraryImportService = libraryImportService;
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

    @GetMapping
    public List<WorkspaceSummaryResponse> list() {
        atelierAccess.requireAccess();
        return workspaceService.list(currentUser.requireId()).stream()
                .map(WorkspaceSummaryResponse::from)
                .toList();
    }

    @GetMapping("/{id}")
    public WorkspaceDetailResponse detail(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return WorkspaceDetailResponse.from(
                workspaceService.requireOwned(userId, id), workspaceService.tree(userId, id));
    }

    @GetMapping("/{id}/file")
    public FileContentResponse readFile(@PathVariable UUID id, @RequestParam("path") String path) {
        atelierAccess.requireAccess();
        UUID userId = currentUser.requireId();
        return new FileContentResponse(path, workspaceService.readFile(userId, id, path));
    }

    @PutMapping("/{id}/file")
    public ResponseEntity<Void> writeFile(
            @PathVariable UUID id,
            @RequestParam("path") String path,
            @RequestBody WriteFileRequest request) {
        atelierAccess.requireAccess();
        workspaceService.writeFile(currentUser.requireId(), id, path,
                request == null ? "" : request.content());
        return ResponseEntity.noContent().build();
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
        List<String> tree = libraryImportService.importDocuments(userId, id, request.documentIds());
        return WorkspaceDetailResponse.from(workspaceService.requireOwned(userId, id), tree);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        atelierAccess.requireAccess();
        workspaceService.delete(currentUser.requireId(), id);
        return ResponseEntity.noContent().build();
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
