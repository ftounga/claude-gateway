package fr.claudegateway.atelier.checkpoint;

import java.util.UUID;

/**
 * Ce qu'un contrôle reçoit au moment où la boucle s'arrête sur un point d'accroche (F-50 / SF-50-01).
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
 * @param kind        le point d'accroche qui a déclenché l'appel
 * @param userId      propriétaire du projet (isolation, déjà vérifiée)
 * @param workspaceId projet concerné
 * @param toolName    outil à l'origine de l'écriture ({@code write_file} / {@code edit_file})
 * @param path        chemin <b>relatif au projet</b> tel que le modèle l'a donné ; {@code null} si
 *                    l'appel n'en portait pas
 * @param content     texte demandé à l'écriture ({@code content}, ou {@code new_string} pour une
 *                    édition ciblée) ; {@code null} si absent
 */
public record AtelierCheckpointContext(AtelierCheckpointKind kind, UUID userId, UUID workspaceId,
        String toolName, String path, String content) {

    /** Contexte d'une écriture de fichier aboutie. */
    public static AtelierCheckpointContext afterFileWrite(UUID userId, UUID workspaceId,
            String toolName, String path, String content) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.AFTER_FILE_WRITE, userId,
                workspaceId, toolName, path, content);
    }
}
