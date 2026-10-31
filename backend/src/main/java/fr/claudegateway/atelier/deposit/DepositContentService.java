package fr.claudegateway.atelier.deposit;

import java.io.IOException;
import java.io.OutputStream;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Le contenu d'une pièce jointe, pour son aperçu</b> (F-228 / SF-228-01). Ne sert QUE les fichiers inscrits dans
 * {@code atelier_deposited_files} du couple ({@code user_id}, terminal) — jamais un chemin libre —, lus là où vit le
 * projet ({@link ProjectFileRead} : poste par tranches, ou hébergé), bornés à {@link #MAX_BYTES}.
 *
 * <p>Provider-First et F-04 respectés : la pièce est servie telle quelle, sans OCR ni indexation. Le type est déduit
 * de l'extension ; un HTML n'est <b>jamais</b> servi comme page (octets à télécharger).</p>
 */
@Service
public class DepositContentService {

    /** Le plafond d'un aperçu : au-delà, la pièce se télécharge depuis le poste. */
    public static final long MAX_BYTES = 20L * 1024 * 1024;

    /**
     * Le plafond d'un téléchargement (F-228 / SF-228-04) : celui d'un dépôt sur le poste
     * ({@code app.atelier.deposit.max-runner-bytes}, 100 Mo par défaut) — aucune pièce déposée ne le dépasse.
     */
    public static final long MAX_DOWNLOAD_BYTES = 100L * 1024 * 1024;

    private static final String OCTETS = "application/octet-stream";

    private static final Map<String, String> TYPES = Map.ofEntries(
            Map.entry("png", "image/png"), Map.entry("jpg", "image/jpeg"), Map.entry("jpeg", "image/jpeg"),
            Map.entry("gif", "image/gif"), Map.entry("webp", "image/webp"), Map.entry("svg", "image/svg+xml"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("mp4", "video/mp4"), Map.entry("webm", "video/webm"), Map.entry("mov", "video/quicktime"),
            Map.entry("m4a", "audio/mp4"), Map.entry("mp3", "audio/mpeg"), Map.entry("wav", "audio/wav"),
            Map.entry("ogg", "audio/ogg"),
            Map.entry("csv", "text/csv"), Map.entry("txt", "text/plain"), Map.entry("md", "text/plain"),
            Map.entry("log", "text/plain"), Map.entry("json", "application/json"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("xls", "application/vnd.ms-excel"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("zip", "application/zip"));

    private final AtelierDepositedFileRepository deposits;
    private final WorkspaceService workspaces;
    private final ProjectFileRead fileRead;

    public DepositContentService(AtelierDepositedFileRepository deposits, WorkspaceService workspaces,
            ProjectFileRead fileRead) {
        this.deposits = deposits;
        this.workspaces = workspaces;
        this.fileRead = fileRead;
    }

    /** Ce qui est servi : les octets, le type, le nom. */
    public record Content(byte[] bytes, String contentType, String fileName) {
    }

    /**
     * Les octets de la pièce {@code depositId} du terminal. 404 (terminal ou pièce d'autrui, inconnue) ; poste hors
     * ligne, pièce disparue ou trop lourde : {@link DepositUnreadableException} au message explicite.
     */
    @Transactional(readOnly = true)
    public Content content(UUID userId, UUID workspaceId, UUID depositId) {
        Workspace workspace = workspaces.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        AtelierDepositedFile deposit = deposits.findByIdAndUserIdAndWorkspaceId(depositId, userId, workspaceId)
                .orElseThrow(DepositNotFoundException::new);
        String path = deposit.getPath();
        if (deposit.getSizeBytes() > MAX_BYTES) {
            throw new DepositUnreadableException("Pièce trop lourde pour l'aperçu (plus de 20 Mo) : "
                    + "ouvrez-la sur le poste.");
        }
        ProjectFileRead.Read read = fileRead.read(userId, workspace, callId("apercu"), path, MAX_BYTES,
                "apercu_piece");
        if (read.failed()) {
            throw new DepositUnreadableException(workspace.isRunnerTarget()
                    ? "Le poste ne répond pas, ou la pièce n'y est plus : " + read.error()
                    : read.error());
        }
        return new Content(read.content(), typeOf(path), nameOf(path));
    }

    /** Où écrire un téléchargement : ouvert à la première tranche lue, avec le nom du fichier. */
    @FunctionalInterface
    public interface DownloadTarget {
        OutputStream open(String fileName) throws IOException;
    }

    /**
     * <b>Télécharger une pièce, en flux</b> (F-228 / SF-228-04) — même isolation que {@link #content} (404 sinon),
     * plafond {@link #MAX_DOWNLOAD_BYTES}. Les tranches sont écrites au fil de la lecture, jamais tout le fichier en
     * mémoire. La première tranche est lue AVANT d'ouvrir la cible : poste hors ligne ou pièce disparue →
     * {@link DepositUnreadableException} (aucun octet parti, réponse 503 lisible). Une rupture APRÈS : {@link
     * IOException} (les en-têtes sont partis ; la connexion est coupée, le navigateur signale l'échec).
     */
    public void download(UUID userId, UUID workspaceId, UUID depositId, DownloadTarget target) throws IOException {
        Workspace workspace = workspaces.requireOwned(userId, workspaceId); // 404 — TOUJOURS en premier
        AtelierDepositedFile deposit = deposits.findByIdAndUserIdAndWorkspaceId(depositId, userId, workspaceId)
                .orElseThrow(DepositNotFoundException::new);
        String path = deposit.getPath();
        if (deposit.getSizeBytes() > MAX_DOWNLOAD_BYTES) {
            throw new DepositUnreadableException("Pièce trop lourde pour être téléchargée ici (plus de 100 Mo) : "
                    + "ouvrez-la sur le poste.");
        }
        OutputStream[] out = new OutputStream[1];
        ProjectFileRead.Read read = fileRead.stream(userId, workspace, callId("telechargement"), path,
                MAX_DOWNLOAD_BYTES, "telechargement_piece", part -> {
                    if (out[0] == null) {
                        out[0] = target.open(nameOf(path));
                    }
                    out[0].write(part);
                });
        if (read.failed()) {
            if (out[0] != null) {
                throw new IOException("Téléchargement interrompu : " + read.error());
            }
            throw new DepositUnreadableException(workspace.isRunnerTarget()
                    ? "Le poste ne répond pas, ou la pièce n'y est plus : " + read.error()
                    : read.error());
        }
        if (out[0] == null) { // fichier vide : aucune tranche, la réponse s'ouvre quand même
            out[0] = target.open(nameOf(path));
        }
        out[0].flush();
    }

    /**
     * Un identifiant d'appel runner <b>aléatoire</b> (F-228 / SF-228-04) : deux lectures simultanées de la même pièce
     * (deux onglets) ne partagent jamais d'identifiant — l'identifiant de dépôt n'est pas unique par appel.
     */
    static String callId(String prefix) {
        return prefix + "-" + UUID.randomUUID();
    }

    /** Le type servi, par l'extension ; un HTML (ou tout inconnu) n'est jamais qu'octets à télécharger. */
    static String typeOf(String path) {
        String name = nameOf(path).toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        return dot < 0 ? OCTETS : TYPES.getOrDefault(name.substring(dot + 1), OCTETS);
    }

    static String nameOf(String path) {
        String clean = path == null ? "piece" : path.replace('\\', '/');
        return clean.substring(clean.lastIndexOf('/') + 1);
    }
}
