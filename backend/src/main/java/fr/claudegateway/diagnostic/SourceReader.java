package fr.claudegateway.diagnostic;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.ProjectFileRead;
import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;

/**
 * <b>Lire le dépôt de l'application</b> (F-157 / SF-157-02), depuis un terminal désigné.
 *
 * <p><b>La reconnaissance est la moitié du travail.</b> Un projet désigné par erreur ferait lire un
 * code sans rapport et conclure n'importe quoi. On reconnaît le dépôt <b>par la carte elle-même</b>
 * — les fichiers que le diagnostic va justement lire — et non par un marqueur qu'on pourrait poser
 * n'importe où.</p>
 *
 * <p><b>Et la lecture est bornée</b> : seuls les chemins déclarés, un plafond d'octets, un plafond
 * de fichiers. Un diagnostic qui lirait 4 000 fichiers coûterait plus cher que ce qu'il ferait
 * économiser — le défaut même qu'il traque.</p>
 */
@Component
public class SourceReader {

    /** Un fichier source au-delà de cette taille n'est pas un fichier source. */
    static final long MAX_BYTES = 512 * 1024;

    /** Au-delà, ce n'est plus un diagnostic ciblé. */
    static final int MAX_FILES = 40;

    /**
     * Part des chemins de la carte qui doit répondre pour qu'on accepte le projet.
     *
     * <p><b>La majorité, pas la totalité</b> : une branche en cours peut avoir déplacé un fichier,
     * et refuser pour autant rendrait la feature inutilisable le jour où elle sert le plus.</p>
     */
    static final double RECOGNITION_RATIO = 0.5;

    private final WorkspaceService workspaceService;
    private final ProjectFileRead files;

    public SourceReader(WorkspaceService workspaceService, ProjectFileRead files) {
        this.workspaceService = workspaceService;
        this.files = files;
    }

    /** Les chemins distincts que la carte déclare — les seuls que ce lecteur acceptera de lire. */
    static List<String> declaredPaths() {
        Set<String> paths = new LinkedHashSet<>();
        for (ProductCapability capability : CapabilityMap.capabilities()) {
            paths.addAll(capability.paths());
        }
        return List.copyOf(paths);
    }

    /**
     * Lit les fichiers déclarés par la carte dans le projet désigné.
     *
     * @throws RepositoryNotRecognizedException si le projet n'est pas le dépôt de l'application
     */
    @Transactional(readOnly = true)
    public Map<String, SourceRead> read(UUID userId, UUID workspaceId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId); // 404 — d'abord

        List<String> paths = declaredPaths();
        Map<String, SourceRead> reads = new LinkedHashMap<>();
        int found = 0;
        int budget = MAX_FILES;

        for (String path : paths) {
            if (budget-- <= 0) {
                reads.put(path, SourceRead.absent(path,
                        "Non lu : plafond de " + MAX_FILES + " fichiers atteint."));
                continue;
            }
            SourceRead read = readOne(userId, workspace, path);
            reads.put(path, read);
            if (read.isRead()) {
                found++;
            }
        }

        // LA RECONNAISSANCE. Elle vient après la lecture parce qu'elle EST la lecture : ce sont les
        // mêmes fichiers. Si la majorité manque, on jette tout et on refuse — plutôt que de
        // diagnostiquer un projet qui n'est pas le nôtre.
        if (paths.isEmpty() || found < Math.ceil(paths.size() * RECOGNITION_RATIO)) {
            throw new RepositoryNotRecognizedException(
                    "Ce projet n'est pas le dépôt de l'application : " + found + " des "
                            + paths.size() + " fichiers attendus seulement y ont été trouvés. "
                            + "Désignez le terminal ouvert sur le dépôt.");
        }
        return Map.copyOf(reads);
    }

    private SourceRead readOne(UUID userId, Workspace workspace, String path) {
        try {
            ProjectFileRead.Read read = files.read(userId, workspace, "diagnostic", path,
                    MAX_BYTES, "read_file");
            if (read.failed()) {
                return SourceRead.absent(path, read.error());
            }
            return SourceRead.read(path, new String(read.content(), StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            // Un fichier illisible n'interrompt rien : le diagnostic reste possible sans lui.
            return SourceRead.absent(path, "Lecture impossible : " + e.getClass().getSimpleName());
        }
    }
}
