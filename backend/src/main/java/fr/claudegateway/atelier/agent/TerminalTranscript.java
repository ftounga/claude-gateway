package fr.claudegateway.atelier.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * Transcription d'un tour d'exécution (F-30 SF-30-09) : les commandes lancées et ce qu'elles ont
 * produit, accumulées <b>depuis les événements du fournisseur</b> pendant le run.
 *
 * <p>Reconstruire ici plutôt que de faire confiance au client est délibéré : le navigateur pourrait
 * envoyer sa propre version, mais l'historique deviendrait alors ce que l'écran affirme, et non ce
 * que le fournisseur a réellement produit.</p>
 *
 * <p>L'appariement suit les mêmes règles que l'affichage : priorité au {@code toolUseId}, repli sur
 * la dernière commande encore sans sortie, et bloc <b>orphelin</b> si aucune ne convient — une sortie
 * n'est jamais perdue.</p>
 */
public class TerminalTranscript {

    /**
     * Une commande et la sortie qu'elle a produite. {@code threadId} porte le <b>fil d'exécution</b>
     * dont elle vient (F-35 / SF-35-02) : {@code null} pour un run séquentiel, c'est-à-dire pour tous
     * les tours d'avant F-35.
     */
    public record Block(String tool, String command, String toolUseId, String output, boolean hasOutput,
            boolean error, String threadId) {
    }

    private final List<Block> blocks = new ArrayList<>();

    /** Ouvre un bloc pour une commande relayée (fil inconnu : run séquentiel). */
    public void addCommand(String tool, String toolUseId, String detail) {
        addCommand(tool, toolUseId, detail, null);
    }

    /**
     * Ouvre un bloc pour une commande relayée, en conservant le <b>fil d'exécution</b> dont elle vient
     * (F-35 / SF-35-02).
     *
     * @param threadId identifiant opaque du fil, ou {@code null} pour un run séquentiel
     */
    public void addCommand(String tool, String toolUseId, String detail, String threadId) {
        blocks.add(new Block(tool, detail, toolUseId, "", false, false, threadId));
    }

    /** Rattache une sortie à sa commande, ou crée un bloc orphelin (fil inconnu). */
    public void addOutput(String tool, String toolUseId, String output, boolean error) {
        addOutput(tool, toolUseId, output, error, null);
    }

    /**
     * Rattache une sortie à sa commande, ou crée un bloc orphelin si aucune ne correspond, en
     * conservant le <b>fil d'exécution</b> (F-35 / SF-35-02).
     *
     * <p>Le fil du bloc l'emporte sur celui de la sortie quand les deux sont connus : c'est la
     * commande qui dit d'où vient le travail. Une commande sans fil qui reçoit une sortie avec fil
     * adopte celui-ci — mieux vaut un fil tardif qu'aucun.</p>
     *
     * @param threadId identifiant opaque du fil, ou {@code null} pour un run séquentiel
     */
    public void addOutput(String tool, String toolUseId, String output, boolean error, String threadId) {
        int index = -1;
        if (toolUseId != null && !toolUseId.isBlank()) {
            for (int i = 0; i < blocks.size(); i++) {
                if (toolUseId.equals(blocks.get(i).toolUseId())) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            for (int i = blocks.size() - 1; i >= 0; i--) {
                if (!blocks.get(i).hasOutput()) {
                    index = i;
                    break;
                }
            }
        }
        if (index < 0) {
            blocks.add(new Block(tool, null, toolUseId, output == null ? "" : output, true, error,
                    threadId));
            return;
        }
        Block target = blocks.get(index);
        // Plusieurs sorties pour une même commande : concaténées dans l'ordre d'arrivée.
        String merged = target.hasOutput() && !target.output().isEmpty()
                ? target.output() + "\n" + (output == null ? "" : output)
                : (output == null ? "" : output);
        blocks.set(index, new Block(target.tool(), target.command(), target.toolUseId() == null
                ? toolUseId : target.toolUseId(), merged, true, target.error() || error,
                target.threadId() == null ? threadId : target.threadId()));
    }

    /** Vrai si le tour n'a lancé aucune commande (rien à persister). */
    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    /**
     * Blocs retenus pour la persistance, bornés par {@code maxChars} : au-delà, les blocs excédentaires
     * sont omis. Un tour qui installe un projet entier ne doit pas faire gonfler l'historique sans
     * limite. Le nombre de blocs omis est renvoyé à part pour être mentionné explicitement.
     */
    public Bounded bounded(int maxChars) {
        List<Block> kept = new ArrayList<>();
        int total = 0;
        for (Block block : blocks) {
            int size = block.output().length() + (block.command() == null ? 0 : block.command().length());
            if (total + size > maxChars && !kept.isEmpty()) {
                return new Bounded(kept, blocks.size() - kept.size());
            }
            kept.add(block);
            total += size;
        }
        return new Bounded(kept, 0);
    }

    /** Blocs retenus + nombre de blocs omis par la borne. */
    public record Bounded(List<Block> blocks, int omitted) {
    }
}
