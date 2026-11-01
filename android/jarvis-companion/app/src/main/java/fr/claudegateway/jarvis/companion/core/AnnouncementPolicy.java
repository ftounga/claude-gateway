package fr.claudegateway.jarvis.companion.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>Les annonces téléphone verrouillé</b> (F-232 / SF-232-03, D1b), sans Android : ce qui se dit, quand, une seule
 * fois. Pur.
 *
 * <ul>
 *   <li>On ne parle que <b>téléphone verrouillé ou écran éteint</b>, et pas quand l'écran Jarvis vient d'être ouvert
 *   par le compagnon (la PWA dit déjà sa réponse) ; téléphone déverrouillé en main, l'écran fait foi.</li>
 *   <li>Une annonce ne se dit <b>qu'une fois</b> : la gateway recouvre volontairement ses fenêtres, on déduplique
 *   par identifiant (les {@value #MEMORY} derniers).</li>
 *   <li>Après la dernière annonce dite, on écoute {@value #REPLY_WINDOW_MS} ms : le premier mot entendu ouvre Jarvis,
 *   sans mot d'éveil.</li>
 * </ul>
 */
public final class AnnouncementPolicy {

    /** La relève, toutes les 20 secondes. */
    public static final long POLL_MS = 20_000L;
    /** Après une erreur réseau, on espace jusqu'à 2 minutes. */
    public static final long MAX_BACKOFF_MS = 120_000L;
    /** L'écoute de la réponse, sans mot d'éveil. */
    public static final long REPLY_WINDOW_MS = 8_000L;
    static final int MEMORY = 50;

    private final ArrayDeque<String> order = new ArrayDeque<>();
    private final Set<String> seen = new HashSet<>();
    private int failures;

    /** Une annonce reçue : son identifiant et la phrase à dire. */
    public static final class Item {
        public final String id;
        public final String text;

        public Item(String id, String text) {
            this.id = id;
            this.text = text;
        }
    }

    /** Parler maintenant ? Seulement verrouillé ou écran éteint, et pas pendant que Jarvis est ouvert par nous. */
    public static boolean shouldSpeak(boolean locked, boolean screenOn, boolean jarvisOpen) {
        return !jarvisOpen && (locked || !screenOn);
    }

    /**
     * Les annonces encore jamais vues, dans l'ordre reçu. Toutes sont marquées vues — même celles qu'on ne dira pas
     * (téléphone en main) : une annonce consommée ne revient pas.
     */
    public List<Item> fresh(List<Item> received) {
        List<Item> fresh = new ArrayList<>();
        for (Item item : received) {
            if (item == null || item.id == null || item.text == null || item.text.trim().isEmpty()) {
                continue;
            }
            if (seen.add(item.id)) {
                order.addLast(item.id);
                fresh.add(item);
                while (order.size() > MEMORY) {
                    seen.remove(order.removeFirst());
                }
            }
        }
        return fresh;
    }

    /** Le délai avant la prochaine relève, selon le succès de celle-ci. */
    public long nextDelay(boolean ok) {
        if (ok) {
            failures = 0;
            return POLL_MS;
        }
        failures = Math.min(failures + 1, 6);
        return Math.min(MAX_BACKOFF_MS, POLL_MS << (failures - 1));
    }
}
