package fr.claudegateway.atelier;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * <b>Ce que voit un fil</b> (F-221 / SF-221-01) — règle pure, la même pour le rejeu au modèle et pour
 * l'historique affiché : ce que l'écran montre est exactement ce que le modèle relit.
 *
 * <p>Le fil <b>commun</b> ({@code chantier == null}) voit tout le sujet, comme avant F-221. Le fil du
 * <b>chantier N</b> voit :</p>
 * <ul>
 *   <li>le <b>passé commun</b> : tout message antérieur à l'ouverture du chantier N, quel qu'en soit le
 *       fil — c'est là qu'est la conversation qui a ouvert N ;</li>
 *   <li>les messages du fil commun ;</li>
 *   <li>les messages de N.</li>
 * </ul>
 * <p>Jamais un message d'un autre chantier postérieur à l'ouverture de N : c'est le défaut prouvé en
 * production le 28/10 (la planification du chantier 8 relue par la course du chantier 7).</p>
 *
 * <p><b>Ordre stable</b> ({@link #byTurn}) : deux fils qui tournent en même temps entrelacent leurs
 * messages dans le temps (demande 7, demande 8, réponse 7, réponse 8). Le rejeu les regroupe par
 * <b>tour</b> — messages consécutifs d'un même fil, de la demande à la réponse — ordonnés par leur premier
 * message. Sur un fil unique, c'est l'identité : le préfixe mis en cache des sujets existants ne bouge
 * pas d'un octet (F-134).</p>
 */
public final class ChantierThread {

    private static final String ASSISTANT = "ASSISTANT";

    private ChantierThread() {
    }

    /**
     * Les messages que voit le fil {@code chantier}, dans l'ordre reçu.
     *
     * @param all      le fil du sujet, chronologique
     * @param chantier le fil ; {@code null} = commun (tout)
     * @param openedAt ouverture du chantier ; {@code null} = inconnue (le fil voit le commun et le sien)
     */
    public static List<AtelierMessage> visibleTo(List<AtelierMessage> all, Integer chantier,
            OffsetDateTime openedAt) {
        if (all == null || all.isEmpty()) {
            return List.of();
        }
        if (chantier == null) {
            return all;
        }
        List<AtelierMessage> visible = new ArrayList<>(all.size());
        for (AtelierMessage message : all) {
            Integer own = message.getChantierNumber();
            boolean before = openedAt != null && message.getCreatedAt() != null
                    && message.getCreatedAt().isBefore(openedAt);
            if (own == null || own.equals(chantier) || before) {
                visible.add(message);
            }
        }
        return visible;
    }

    /**
     * Regroupe par tour : un message utilisateur qui suit une réponse de son fil ouvre un tour neuf ; tout
     * le reste (précisions, réponse) prolonge le tour en cours de son fil. Les tours sont rendus dans l'ordre
     * de leur premier message.
     */
    public static List<AtelierMessage> byTurn(List<AtelierMessage> messages) {
        if (messages == null || messages.size() < 2) {
            return messages == null ? List.of() : messages;
        }
        List<List<AtelierMessage>> turns = new ArrayList<>();
        Map<Integer, List<AtelierMessage>> current = new HashMap<>();
        boolean reordered = false;
        for (AtelierMessage message : messages) {
            Integer lane = laneKey(message);
            List<AtelierMessage> turn = current.get(lane);
            boolean user = !ASSISTANT.equalsIgnoreCase(message.getRole());
            if (turn == null || (user && ASSISTANT.equalsIgnoreCase(turn.get(turn.size() - 1).getRole()))) {
                turn = new ArrayList<>();
                turns.add(turn);
                current.put(lane, turn);
            } else if (turn != turns.get(turns.size() - 1)) {
                reordered = true;
            }
            turn.add(message);
        }
        if (!reordered) {
            return messages;
        }
        List<AtelierMessage> ordered = new ArrayList<>(messages.size());
        turns.forEach(ordered::addAll);
        return ordered;
    }

    /** Le fil commun a sa propre clef ({@code 0}) : un numéro de chantier est toujours ≥ 1. */
    private static Integer laneKey(AtelierMessage message) {
        return Objects.requireNonNullElse(message.getChantierNumber(), 0);
    }
}
