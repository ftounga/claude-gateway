package fr.claudegateway.runner;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Cycle de vie des <b>worktrees git isolés</b> de la sous-boucle {@code task} (F-150 / SF-150-01).
 *
 * <p>Trois opérations, routées comme n'importe quel outil ({@code tool_call}) : {@link #CREATE}
 * matérialise un worktree du projet cible sous {@code <racine>/.atelier-worktrees/<taskId>},
 * {@link #REMOVE} le démonte (idempotent), {@link #REAP} supprime les orphelins. C'est
 * l'<b>enabler</b> de F-150 : la sous-boucle {@code task} (SF-150-02) y agira, sans jamais toucher la
 * copie de travail réelle de l'utilisateur.</p>
 *
 * <p><b>Invariant absolu</b> : le worktree vit <b>toujours</b> sous la racine du poste — sans quoi
 * {@link ProjectScopes} rejetterait tout appel de la sous-boucle en {@code path_outside_root}. Le
 * chemin est recalculé et revérifié ici, jamais reçu tel quel.</p>
 *
 * <p><b>Refus propre sans git</b> (décision PO) : si le projet cible n'est pas un dépôt git — ou si
 * {@code git} est absent du poste — {@link #CREATE} répond {@code not_git} et n'écrit rien. Pas de
 * repli copie en V1 ; {@code task} guidera alors l'utilisateur vers {@code explore} ou vers
 * l'initialisation de git.</p>
 *
 * <p><b>Rétro-compatibilité</b> : un runner antérieur ne connaît pas ces outils ; il les route vers
 * {@link FileTools} qui répond {@code unsupported_tool}. La gateway remonte ce code et {@code task}
 * refuse proprement. Aucun nouveau transport, aucun nouveau champ d'enveloppe.</p>
 */
public final class WorktreeTool {

    /** Préfixe commun des opérations de worktree. */
    static final String PREFIX = "worktree_";
    static final String CREATE = "worktree_create";
    static final String REMOVE = "worktree_remove";
    static final String REAP = "worktree_reap";
    /** Restitution (F-150 / SF-150-05) : committe le worktree sur sa branche et rend le diff résumé. */
    static final String FINALIZE = "worktree_finalize";
    /** Borne du diff résumé remonté : au-delà, il n'informe plus, il encombre. */
    static final int MAX_DIFFSTAT_CHARS = 4_000;

    /** Dossier, <b>sous la racine du poste</b>, où vivent les worktrees isolés des sous-tâches. */
    static final String WORKTREES_DIR = ".atelier-worktrees";
    /** Préfixe de la branche du worktree : SF-150-05 en fera la référence de restitution. */
    static final String BRANCH_PREFIX = "atelier/task/";
    static final int MAX_TASK_ID = 64;

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1," + MAX_TASK_ID + "}");

    private final Path hostRoot;
    private final Path projectFolder;
    private final GitCli git;

    /**
     * @param hostRoot      racine <b>canonique</b> du poste (celle de {@link ProjectScopes})
     * @param projectFolder dossier du projet de cet appel — dépôt git dont on tire le worktree
     * @param git           invocation git bornée (SF-150-01)
     */
    public WorktreeTool(Path hostRoot, Path projectFolder, GitCli git) {
        this.hostRoot = hostRoot;
        this.projectFolder = projectFolder;
        this.git = git;
    }

    /** Vrai si {@code tool} est une opération de worktree. */
    static boolean handles(String tool) {
        return tool != null && tool.startsWith(PREFIX);
    }

    /** Ne lève jamais : toute erreur devient un {@link ToolOutcome} porteur d'un code du contrat. */
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        try {
            return switch (tool) {
                case CREATE -> create(input);
                case REMOVE -> remove(input);
                case REAP -> reap(input);
                case FINALIZE -> finalizeWorktree(input);
                default -> ToolOutcome.error("unsupported_tool", "Opération de worktree inconnue.");
            };
        } catch (ToolException e) {
            return ToolOutcome.error(e);
        } catch (RuntimeException e) {
            return ToolOutcome.error("internal", "Erreur interne de gestion du worktree.");
        }
    }

    // ------------------------------------------------------------------ create

    private ToolOutcome create(JsonNode input) {
        String taskId = requireTaskId(input);
        Path worktreeDir = worktreeDirFor(taskId);

        // Un seul appel détecte à la fois « git absent » et « pas un dépôt git » : les deux mènent au
        // refus propre `not_git` (décision PO — pas de repli copie en V1).
        GitCli.Result inside = git.run(projectFolder, "rev-parse", "--is-inside-work-tree");
        if (!inside.present()) {
            return ToolOutcome.error("not_git",
                    "`task` requiert git : le binaire git est introuvable sur ce poste. "
                            + "Utilise `explore` pour lire, ou installe git.");
        }
        if (!inside.ok()) {
            return ToolOutcome.error("not_git",
                    "`task` requiert un projet git : ce dossier n'est pas un dépôt git. "
                            + "Utilise `explore` pour lire, ou initialise git (git init).");
        }

        // Élaguer d'abord les entrées mortes d'un tour précédent, puis nettoyer un dossier résiduel :
        // un `worktree add` échoue si la cible existe déjà.
        git.run(projectFolder, "worktree", "prune");
        deleteRecursively(worktreeDir);

        String branch = BRANCH_PREFIX + taskId;
        // -B crée ou réinitialise la branche : idempotent même si un tour précédent a laissé la branche.
        GitCli.Result add = git.run(projectFolder, "worktree", "add", "-B", branch,
                worktreeDir.toString(), "HEAD");
        if (!add.ok()) {
            deleteRecursively(worktreeDir);
            return ToolOutcome.error("io_error",
                    "Impossible de créer le worktree (dépôt sans commit initial ?).");
        }

        ObjectNode result = MAPPER.createObjectNode();
        result.put("worktreePath", WORKTREES_DIR + "/" + taskId);
        result.put("branch", branch);
        return ToolOutcome.ok(result.toString());
    }

    // ------------------------------------------------------------------ remove

    private ToolOutcome remove(JsonNode input) {
        String taskId = requireTaskId(input);
        Path worktreeDir = worktreeDirFor(taskId);
        // Best-effort : git d'abord (retire la métadonnée), puis suppression du dossier, puis prune.
        // L'ensemble est idempotent — un worktree déjà absent est un succès.
        git.run(projectFolder, "worktree", "remove", "--force", worktreeDir.toString());
        deleteRecursively(worktreeDir);
        git.run(projectFolder, "worktree", "prune");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("removed", true);
        return ToolOutcome.ok(result.toString());
    }

    // -------------------------------------------------------------------- reap

    private ToolOutcome reap(JsonNode input) {
        Set<String> keep = new HashSet<>();
        JsonNode keepNode = input == null ? null : input.get("keep");
        if (keepNode != null && keepNode.isArray()) {
            keepNode.forEach(node -> {
                if (node.isTextual() && TASK_ID.matcher(node.asText()).matches()) {
                    keep.add(node.asText());
                }
            });
        }
        int reaped = reapOrphans(hostRoot, keep);
        // Élaguer la métadonnée du dépôt courant (best-effort) : les autres dépôts s'élagueront au
        // prochain `create` (qui commence par un `worktree prune`).
        git.run(projectFolder, "worktree", "prune");
        ObjectNode result = MAPPER.createObjectNode();
        result.put("reaped", reaped);
        return ToolOutcome.ok(result.toString());
    }

    // ---------------------------------------------------------------- finalize

    /**
     * <b>Restitution</b> (F-150 / SF-150-05) : stage tout le worktree, committe sur sa branche s'il y a
     * des changements, et rend {@code {branch, committed, hasChanges, diffStat}}. La branche <b>reste</b>
     * dans le dépôt après le démontage du worktree — la reprise (merge/cherry-pick) dans la copie de
     * travail réelle est un <b>acte explicite ultérieur</b> (jamais un merge aveugle).
     */
    private ToolOutcome finalizeWorktree(JsonNode input) {
        String taskId = requireTaskId(input);
        Path worktreeDir = worktreeDirFor(taskId);
        if (!Files.isDirectory(worktreeDir)) {
            return ToolOutcome.error("not_found", "Worktree introuvable pour cette tâche.");
        }
        String branch = BRANCH_PREFIX + taskId;
        git.run(worktreeDir, "add", "-A");
        GitCli.Result status = git.run(worktreeDir, "status", "--porcelain");
        boolean hasChanges = status.ok() && !status.stdout().isBlank();
        boolean committed = false;
        String diffStat = "";
        if (hasChanges) {
            JsonNode messageNode = input == null ? null : input.get("message");
            String message = messageNode != null && messageNode.isTextual()
                    && !messageNode.asText().isBlank()
                    ? messageNode.asText()
                    : "atelier task " + taskId;
            committed = git.run(worktreeDir, "commit", "-m", message).ok();
            if (committed) {
                GitCli.Result diff = git.run(worktreeDir, "diff", "--stat", "HEAD~1", "HEAD");
                if (diff.ok() && !diff.stdout().isBlank()) {
                    diffStat = diff.stdout();
                    if (diffStat.length() > MAX_DIFFSTAT_CHARS) {
                        diffStat = diffStat.substring(0, MAX_DIFFSTAT_CHARS) + "\n… (résumé tronqué)";
                    }
                }
            }
        }
        ObjectNode result = MAPPER.createObjectNode();
        result.put("branch", branch);
        result.put("committed", committed);
        result.put("hasChanges", hasChanges);
        result.put("diffStat", diffStat);
        return ToolOutcome.ok(result.toString());
    }

    // ------------------------------------------------------------------- outils

    private String requireTaskId(JsonNode input) {
        JsonNode value = input == null ? null : input.get("taskId");
        String taskId = value == null || !value.isTextual() ? "" : value.asText().trim();
        if (!TASK_ID.matcher(taskId).matches()) {
            throw new ToolException("invalid_input", "Identifiant de tâche invalide.");
        }
        return taskId;
    }

    /**
     * Dossier du worktree, recalculé et revérifié sous la racine du poste. Le {@code taskId} est déjà
     * borné à {@code [A-Za-z0-9_-]} ; ce contrôle est la ceinture-et-bretelles de l'invariant absolu.
     */
    private Path worktreeDirFor(String taskId) {
        Path dir = hostRoot.resolve(WORKTREES_DIR).resolve(taskId).normalize();
        if (!dir.startsWith(hostRoot)) {
            throw new ToolException("path_outside_root",
                    "Le worktree doit rester sous la racine du poste.");
        }
        return dir;
    }

    /**
     * Supprime les worktrees orphelins sous {@code <racine>/.atelier-worktrees/}, sauf ceux listés
     * dans {@code keep}. Best-effort ; ne lève jamais.
     *
     * @return le nombre de worktrees supprimés
     */
    static int reapOrphans(Path hostRoot, Set<String> keep) {
        Path base = hostRoot.resolve(WORKTREES_DIR);
        if (!Files.isDirectory(base)) {
            return 0;
        }
        int[] reaped = {0};
        try (Stream<Path> children = Files.list(base)) {
            children.forEach(child -> {
                Path name = child.getFileName();
                if (name != null && !keep.contains(name.toString()) && deleteRecursively(child)) {
                    reaped[0]++;
                }
            });
        } catch (IOException e) {
            // Dossier illisible : rien à réaper, ce n'est pas une panne.
        }
        return reaped[0];
    }

    /**
     * <b>Reap au démarrage du runner</b> (D9) : « le tour vit dans le flux » — une déconnexion tue le
     * processus et laisse un worktree orphelin. Au démarrage, on supprime tout ce qui traîne sous
     * {@code .atelier-worktrees/} (best-effort, jamais bloquant).
     */
    public static void reapAtStartup(Path hostRoot, Console console) {
        try {
            int reaped = reapOrphans(hostRoot, Set.of());
            if (reaped > 0 && console != null) {
                console.info("Worktrees `task` orphelins nettoyés au démarrage : " + reaped + ".");
            }
        } catch (RuntimeException e) {
            // Best-effort : un nettoyage impossible ne doit jamais empêcher le runner de démarrer.
        }
    }

    /** Suppression récursive best-effort. Rend {@code true} si le dossier existait et a été retiré. */
    private static boolean deleteRecursively(Path path) {
        if (!Files.exists(path, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try (Stream<Path> walk = Files.walk(path)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    // Fichier verrouillé : on continue, l'essentiel du dossier est retiré.
                }
            });
        } catch (IOException e) {
            return false;
        }
        return true;
    }
}
