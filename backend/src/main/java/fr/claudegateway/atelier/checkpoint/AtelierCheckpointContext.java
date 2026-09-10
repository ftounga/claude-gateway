package fr.claudegateway.atelier.checkpoint;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Ce qu'un contrôle reçoit au moment où la boucle s'arrête sur un point d'accroche (F-50 / SF-50-01,
 * étendu par SF-50-02, puis par F-52 / SF-52-01).
 *
 * <p><b>Isolation.</b> Le couple {@code (userId, workspaceId)} est celui du tour en cours, obtenu
 * après {@code workspaceService.requireOwned} : un contrôle n'est jamais interrogé pour un projet
 * que l'appelant ne possède pas, et il n'a pas à le revérifier — il a le droit de s'y fier.</p>
 *
 * <p><b>Ce que le contexte n'est pas.</b> Il ne relit rien : {@code content} est ce que le modèle a
 * <i>demandé</i> d'écrire, pas ce que le disque contient après coup, et {@code command} est ce qu'il
 * a demandé d'exécuter, <b>avant</b> toute exécution. En cible {@code RUNNER}, le fichier vit sur la
 * machine de l'utilisateur et la gateway ne le relit pas pour contrôler — elle n'a que l'intention,
 * et c'est suffisant pour le seul usage prévu : juger ce qui vient d'être produit, ou ce qui est sur
 * le point de partir.</p>
 *
 * <p>Les champs sont renseignés <b>selon le point d'accroche</b> : une écriture porte
 * {@code toolName} / {@code path} / {@code content} ; une fin de tour porte {@code replyText} et
 * {@code writtenPaths} ; une commande porte {@code command} et {@code cwd} (F-52 / SF-52-01). Les
 * fabriques ci-dessous sont le seul moyen prévu d'en construire un.</p>
 *
 * @param kind         le point d'accroche qui a déclenché l'appel
 * @param userId       propriétaire du projet (isolation, déjà vérifiée)
 * @param workspaceId  projet concerné
 * @param toolName     outil à l'origine de l'appel ({@code write_file} / {@code edit_file} sur une
 *                     écriture, {@code bash} avant une commande) ; {@code null} en fin de tour
 * @param path         chemin <b>relatif au projet</b> tel que le modèle l'a donné ; {@code null} si
 *                     l'appel n'en portait pas
 * @param content      texte demandé à l'écriture ({@code content}, ou {@code new_string} pour une
 *                     édition ciblée) ; {@code null} si absent
 * @param replyText    réponse finale que le modèle s'apprêtait à rendre (fin de tour) ; {@code null}
 *                     sur une écriture
 * @param writtenPaths chemins écrits pendant le tour, dans l'ordre, sans doublon (fin de tour) ;
 *                     jamais {@code null}, éventuellement vide
 * @param command      commande demandée, telle que le modèle l'a écrite, <b>avant</b> toute
 *                     exécution ; {@code null} sur les autres points d'accroche
 * @param cwd          répertoire de travail demandé pour cette commande ; {@code null} si l'appel
 *                     n'en portait pas
 */
public record AtelierCheckpointContext(AtelierCheckpointKind kind, UUID userId, UUID workspaceId,
        String toolName, String path, String content, String replyText, List<String> writtenPaths,
        String command, String cwd) {

    /**
     * Chemins conservés dans le contexte de fin de tour. Au-delà, ce n'est plus une information mais
     * un inventaire — et il voyagerait dans chaque appel de contrôle.
     */
    public static final int MAX_WRITTEN_PATHS = 200;

    /**
     * Longueur maximale de la commande remise à un contrôle (F-52 / SF-52-01). Un contrôle juge une
     * commande, pas un fichier : au-delà, ce qui passe par {@code bash} est un contenu, et il n'a
     * pas à voyager dans chaque appel de contrôle.
     */
    public static final int MAX_COMMAND_CHARS = 8_000;

    /** Rend la liste immuable et bornée : un contrôle ne doit ni la modifier ni la subir. */
    public AtelierCheckpointContext {
        writtenPaths = boundedCopy(writtenPaths);
        command = bounded(command);
    }

    /** Contexte d'une écriture de fichier aboutie. */
    public static AtelierCheckpointContext afterFileWrite(UUID userId, UUID workspaceId,
            String toolName, String path, String content) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.AFTER_FILE_WRITE, userId,
                workspaceId, toolName, path, content, null, List.of(), null, null);
    }

    /**
     * Contexte d'une commande <b>sur le point d'être exécutée</b> (F-52 / SF-52-01).
     *
     * <p>Rien n'est encore parti vers la machine de l'utilisateur quand un contrôle la reçoit :
     * c'est tout l'intérêt de ce point d'accroche, et c'est ce qui permet à un blocage de porter sur
     * une commande qui n'aura jamais tourné.</p>
     */
    public static AtelierCheckpointContext beforeCommand(UUID userId, UUID workspaceId,
            String command, String cwd) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.BEFORE_COMMAND, userId, workspaceId,
                "bash", null, null, null, List.of(), command, cwd);
    }

    /**
     * Contexte d'une fin de tour : ce que le modèle s'apprêtait à répondre, et ce qu'il a écrit en
     * chemin.
     */
    public static AtelierCheckpointContext endOfTurn(UUID userId, UUID workspaceId, String replyText,
            List<String> writtenPaths) {
        return new AtelierCheckpointContext(AtelierCheckpointKind.END_OF_TURN, userId, workspaceId,
                null, null, null, replyText, writtenPaths, null, null);
    }

    /** Borne la commande sans jamais la refuser : un contrôle juge ce qu'il voit, pas une exception. */
    private static String bounded(String command) {
        if (command == null) {
            return null;
        }
        return command.length() <= MAX_COMMAND_CHARS
                ? command
                : command.substring(0, MAX_COMMAND_CHARS);
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
