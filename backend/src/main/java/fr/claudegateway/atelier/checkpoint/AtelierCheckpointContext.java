package fr.claudegateway.atelier.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un contrôle reçoit au moment où la boucle s'arrête sur un point d'accroche (F-50 / SF-50-01,
 * étendu par SF-50-02).
 *
 * <p><b>Isolation.</b> Le couple {@code (userId, workspaceId)} est celui du tour en cours, obtenu
 * après {@code workspaceService.requireOwned} : un contrôle n'est jamais interrogé pour un projet
 * que l'appelant ne possède pas, et il n'a pas à le revérifier — il a le droit de s'y fier.</p>
 *
 * <p><b>Ce que le contexte n'est pas.</b> Il ne relit rien : {@code content} est ce que le modèle a
 * <i>demandé</i> d'écrire, pas ce que le disque contient après coup. En cible {@code RUNNER}, le
 * fichier vit sur la machine de l'utilisateur et la gateway ne le relit pas pour contrôler — elle
 * n'a que l'intention, et c'est suffisant pour le seul usage prévu : juger ce qui vient d'être
 * produit.</p>
 *
 * <p>Les champs sont renseignés <b>selon le point d'accroche</b> : une écriture porte
 * {@code toolName} / {@code path} / {@code content} ; une fin de tour porte {@code replyText} et
 * {@code writtenPaths}. Les fabriques ci-dessous sont le seul moyen prévu d'en construire un.</p>
 *
 * @param kind         le point d'accroche qui a déclenché l'appel
 * @param userId       propriétaire du projet (isolation, déjà vérifiée)
 * @param workspaceId  projet concerné
 * @param toolName     outil à l'origine de l'écriture ({@code write_file} / {@code edit_file}) ;
 *                     {@code null} en fin de tour
 * @param path         chemin <b>relatif au projet</b> tel que le modèle l'a donné ; {@code null} si
 *                     l'appel n'en portait pas
 * @param content      texte demandé à l'écriture ({@code content}, ou {@code new_string} pour une
 *                     édition ciblée) ; {@code null} si absent
 * @param replyText    réponse finale que le modèle s'apprêtait à rendre (fin de tour) ; {@code null}
 *                     sur une écriture
 * @param writtenPaths chemins écrits pendant le tour, dans l'ordre, sans doublon (fin de tour) ;
 *                     jamais {@code null}, éventuellement vide
 */
public record AtelierCheckpointContext(AtelierCheckpointKind kind, UUID userId, UUID workspaceId,
        String toolName, String path, String content, String replyText, List<String> writtenPaths) {

    /**
     * Chemins conservés dans le contexte de fin de tour. Au-delà, ce n'est plus une information mais
     * un inventaire — et il voyagerait dans chaque appel de contrôle.
     */
    public static final int MAX_WRITTEN_PATHS = 200;

    /** Rend la liste immuable et bornée : un contrôle ne doit ni la modifier ni la subir. */
    public AtelierCheckpointContext {
        writtenPaths = boundedCopy(writtenPaths);
    }

    /** Contexte d'une écriture de fichier aboutie. */
    public static AtelierCheckpointContext afterFileWrite(UUID userId, UUID workspaceId,
            String toolName, String path, String content) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.AFTER_FILE_WRITE, userId,
                workspaceId, toolName, path, content, null, List.of());
    }

    /**
     * Contexte d'une fin de tour : ce que le modèle s'apprêtait à répondre, et ce qu'il a écrit en
     * chemin.
     */
    public static AtelierCheckpointContext endOfTurn(UUID userId, UUID workspaceId, String replyText,
            List<String> writtenPaths) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.END_OF_TURN, userId, workspaceId,
                null, null, null, replyText, writtenPaths);
    }

    private static List<String> boundedCopy(List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return List.of();
        }
        List<String> bounded = new ArrayList<>(Math.min(paths.size(), MAX_WRITTEN_PATHS));
        for (String path : paths) {
            if (bounded.size() >= MAX_WRITTEN_PATHS) {
                break;
            }
            bounded.add(path);
        }
        return List.copyOf(bounded);
    }
}
