package fr.claudegateway.atelier;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * Cœur de l'Atelier (F-28) : création d'un workspace à partir d'un zip décompressé de façon sûre
 * (zip-slip + zip-bomb), et lecture/écriture des fichiers. Isolation multi-tenant : tout accès
 * vérifie que le workspace appartient à l'utilisateur courant. Ne dépend que de
 * {@link WorkspaceStorage} (Provider Independence).
 */
@Service
public class WorkspaceService {

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final byte[] DEFAULT_CLAUDE_MD = ("# CLAUDE.md\n\n"
            + "Conventions et contexte de ce projet, à destination de Claude.\n"
            + "Décrivez ici l'architecture, les règles de code et ce qu'il faut savoir.\n")
            .getBytes(StandardCharsets.UTF_8);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceStorage storage;
    private final AtelierProperties properties;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceStorage storage,
            AtelierProperties properties) {
        this.workspaceRepository = workspaceRepository;
        this.storage = storage;
        this.properties = properties;
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
        return new CreatedWorkspace(workspace, files.size());
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
        Workspace workspace = requireOwned(userId, id);
        String rel = normalizeRelPath(path);
        byte[] bytes = (content == null ? "" : content).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > properties.maxFileBytes()) {
            throw new InvalidArchiveException("Fichier trop volumineux.");
        }
        storage.putFile(prefixOf(userId, id) + rel, bytes, "text/plain; charset=utf-8");
        workspaceRepository.save(workspace); // rafraîchit updated_at
    }

    /** Supprime le workspace (fichiers + ligne). */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Workspace workspace = requireOwned(userId, id);
        storage.deletePrefix(prefixOf(userId, id));
        workspaceRepository.delete(workspace);
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
