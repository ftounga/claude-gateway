package fr.claudegateway.atelier;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.chat.DocumentNotReadyException;
import fr.claudegateway.ocr.Document;
import fr.claudegateway.ocr.DocumentNotFoundException;
import fr.claudegateway.ocr.DocumentRepository;

/**
 * Import de documents de la bibliothèque personnelle (F-08) dans un workspace de l'Atelier (F-28 /
 * SF-28-13). Relais Provider-First : on écrit le <b>texte déjà extrait</b> par l'OCR (aucun
 * ré-traitement). Isolation multi-tenant : le workspace doit appartenir à l'utilisateur
 * ({@link WorkspaceService#requireOwned}) et chaque document est relu sous double filtre
 * {@code id} + {@code user_id} (mêmes garde-fous que le contexte de chat F-24).
 */
@Service
public class WorkspaceLibraryImportService {

    /** Plafond de troncature du texte importé, aligné sur le contexte de chat F-24. */
    private static final int DOCUMENT_TEXT_MAX = 100_000;

    /** Dossier de destination des documents importés dans le workspace. */
    private static final String LIBRARY_FOLDER = "bibliotheque/";

    private final WorkspaceService workspaceService;
    private final DocumentRepository documentRepository;

    public WorkspaceLibraryImportService(WorkspaceService workspaceService,
            DocumentRepository documentRepository) {
        this.workspaceService = workspaceService;
        this.documentRepository = documentRepository;
    }

    /**
     * Importe le texte des documents choisis dans le workspace, sous {@code bibliotheque/<nom>.md}.
     * L'isolation est vérifiée <b>avant</b> toute lecture de document : le workspace doit être possédé
     * par l'utilisateur, sinon 404.
     *
     * @param userId      utilisateur courant (isolation)
     * @param workspaceId workspace destinataire (doit être possédé)
     * @param documentIds identifiants des documents de bibliothèque à importer
     * @return arborescence à jour du workspace (chemins relatifs triés)
     * @throws WorkspaceNotFoundException si le workspace n'appartient pas à l'utilisateur (404)
     * @throws DocumentNotFoundException  si un document est inconnu ou appartient à un autre user (404)
     * @throws DocumentNotReadyException  si un document n'a pas encore de texte exploitable (409)
     */
    public List<String> importDocuments(UUID userId, UUID workspaceId, List<UUID> documentIds) {
        // Isolation d'abord : refuse tout accès à un workspace non possédé avant de lire des documents.
        workspaceService.requireOwned(userId, workspaceId);
        for (UUID documentId : documentIds) {
            Document document = documentRepository.findByIdAndUserId(documentId, userId)
                    .orElseThrow(() -> new DocumentNotFoundException("Document introuvable : " + documentId));
            String text = document.getExtractedText();
            if (text == null || text.isBlank()) {
                throw new DocumentNotReadyException(
                        "Le document « " + document.getFilename() + " » n'a pas encore de texte exploitable.");
            }
            if (text.length() > DOCUMENT_TEXT_MAX) {
                text = text.substring(0, DOCUMENT_TEXT_MAX);
            }
            String path = LIBRARY_FOLDER + safeName(document.getFilename()) + ".md";
            workspaceService.writeFile(userId, workspaceId, path, text);
        }
        return workspaceService.tree(userId, workspaceId);
    }

    /**
     * Aplatit le nom d'un document en nom de fichier sûr : tout caractère hors {@code [A-Za-z0-9._-]}
     * (dont le séparateur {@code /}) est remplacé par {@code _}. Un nom vide retombe sur
     * {@code document}.
     */
    private String safeName(String filename) {
        if (filename == null || filename.isBlank()) {
            return "document";
        }
        String cleaned = filename.trim().replaceAll("[^A-Za-z0-9._-]", "_");
        return cleaned.isBlank() ? "document" : cleaned;
    }
}
