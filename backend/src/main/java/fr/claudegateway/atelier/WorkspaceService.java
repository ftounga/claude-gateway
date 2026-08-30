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
import java.util.zip.ZipOutputStream;

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

    /** Longueur maximale du nom d'un projet : borne de la colonne `workspaces.name`. */
    private static final int MAX_NAME_LENGTH = 255;

    private static final String CLAUDE_MD = "CLAUDE.md";
    private static final byte[] DEFAULT_CLAUDE_MD = ("# CLAUDE.md\n\n"
            + "Conventions et contexte de ce projet, à destination de Claude.\n"
            + "Décrivez ici l'architecture, les règles de code et ce qu'il faut savoir.\n")
            .getBytes(StandardCharsets.UTF_8);

    private final WorkspaceRepository workspaceRepository;
    private final WorkspaceStorage storage;
    private final AtelierProperties properties;
    private final AtelierMessageRepository atelierMessageRepository;

    public WorkspaceService(WorkspaceRepository workspaceRepository, WorkspaceStorage storage,
            AtelierProperties properties, AtelierMessageRepository atelierMessageRepository) {
        this.workspaceRepository = workspaceRepository;
        this.storage = storage;
        this.properties = properties;
        this.atelierMessageRepository = atelierMessageRepository;
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
        return workspaceRepository.save(Workspace.builder()
                .userId(userId)
                .name(name == null || name.isBlank() ? repo : name.trim())
                .source(WorkspaceSource.GIT)
                .gitRepoUrl(repoUrl)
                .gitOwner(owner)
                .gitRepo(repo)
                .gitBranch(branch)
                .build());
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
        workspace.setExecutionTarget(target);
        if (target == WorkspaceExecutionTarget.RUNNER) {
            // Décision D7 (F-38 / SF-38-08) : la validation avant exécution devient obligatoire dès
            // que les commandes tournent sur une vraie machine. `always_allow` est acceptable dans
            // un conteneur jetable, pas ici — on la pose donc au moment de la bascule, plutôt que de
            // compter sur un réglage que l'utilisateur n'a jamais activé.
            workspace.setAgentAskBeforeBash(true);
        }
        return workspaceRepository.save(workspace);
    }

    /**
     * Supprime le workspace : fichiers du stockage, <b>messages d'Atelier</b>, puis la ligne.
     *
     * <p>Les messages ont été ajoutés par SF-11-03 : sans eux, l'historique des sessions d'agent
     * survivait à son workspace, sans plus aucun moyen d'y accéder ni de le purger.</p>
     */
    @Transactional
    public void delete(UUID userId, UUID id) {
        Workspace workspace = requireOwned(userId, id);
        storage.deletePrefix(prefixOf(userId, id));
        atelierMessageRepository.deleteByWorkspaceId(id);
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
