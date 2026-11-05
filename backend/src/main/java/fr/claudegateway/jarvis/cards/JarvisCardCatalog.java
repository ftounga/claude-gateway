package fr.claudegateway.jarvis.cards;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <b>Le catalogue des faits lus pour une réponse</b> (F-248 / SF-248-02, cadrage §4.2, D2, D10) — tenu par la passerelle,
 * jamais par le modèle.
 *
 * <p>Ouvert par le relais d'une réplique sur <b>son</b> fil d'exécution ({@link #open}), il reçoit les fiches des lignes
 * <b>déjà lues</b> par les blocs du contexte et les outils ({@link #collect}) : aucune lecture de plus, et rien du tout
 * quand il n'est pas ouvert (coupe-circuit K7, présentation). Une fiche n'entre qu'une fois (par {@code id}) ; une
 * lecture ciblée la promeut. Au plus {@link #MAX_CARDS} fiches, les ciblées d'abord.</p>
 *
 * <p>Ce qui en sort ({@link #takeIfChanged}) part dans l'événement SSE {@code cards}, <b>après</b> la première phrase
 * (D10) : c'est un ordre d'envoi, pas un délai.</p>
 */
public final class JarvisCardCatalog {

    private static final Logger log = LoggerFactory.getLogger(JarvisCardCatalog.class);
    public static final int MAX_CARDS = 24;
    private static final ThreadLocal<JarvisCardCatalog> CURRENT = new ThreadLocal<>();

    private final UUID responseId = UUID.randomUUID();
    private final Map<String, JarvisCard> cards = new LinkedHashMap<>();
    private int version;
    private int sent = -1;

    /** Ouvre le catalogue de la réplique sur le fil courant ; {@link #close} le referme. */
    public static JarvisCardCatalog open() {
        JarvisCardCatalog c = new JarvisCardCatalog();
        CURRENT.set(c);
        return c;
    }

    /** Referme le catalogue du fil courant. */
    public static void close() {
        CURRENT.remove();
    }

    /** Le catalogue ouvert sur ce fil, ou {@code null}. */
    public static JarvisCardCatalog current() {
        return CURRENT.get();
    }

    /**
     * Range une fiche si un catalogue est ouvert sur ce fil (rien n'est fabriqué sinon). Ne lève jamais : une fiche en
     * échec est omise, la réplique n'est pas touchée.
     */
    public static void collect(Supplier<JarvisCard> card) {
        JarvisCardCatalog c = CURRENT.get();
        if (c == null) {
            return;
        }
        try {
            c.add(card.get());
        } catch (RuntimeException e) {
            log.debug("Pancartes : fiche omise ({})", e.getClass().getSimpleName());
        }
    }

    /** Range plusieurs fiches (même règle). */
    public static void collectAll(Supplier<List<JarvisCard>> list) {
        JarvisCardCatalog c = CURRENT.get();
        if (c == null) {
            return;
        }
        try {
            list.get().forEach(c::add);
        } catch (RuntimeException e) {
            log.debug("Pancartes : fiches omises ({})", e.getClass().getSimpleName());
        }
    }

    /** Vrai si un catalogue est ouvert (une source peut éviter de préparer ses lignes sinon). */
    public static boolean active() {
        return CURRENT.get() != null;
    }

    public synchronized void add(JarvisCard card) {
        if (card == null || card.id() == null) {
            return;
        }
        JarvisCard known = cards.get(card.id());
        if (known != null) {
            if (card.targeted() && !known.targeted()) {
                cards.put(card.id(), known.asTargeted());
                version++;
            }
            return;
        }
        if (cards.size() >= MAX_CARDS) {
            if (!card.targeted()) {
                return;
            }
            String drop = cards.values().stream().filter(c -> !c.targeted()).reduce((a, b) -> b).map(JarvisCard::id)
                    .orElse(null);
            if (drop == null) {
                return;
            }
            cards.remove(drop);
        }
        cards.put(card.id(), card);
        version++;
    }

    /** Les fiches, ciblées d'abord (ordre de lecture conservé dans chaque groupe). */
    public synchronized List<JarvisCard> cards() {
        List<JarvisCard> out = new ArrayList<>();
        cards.values().stream().filter(JarvisCard::targeted).forEach(out::add);
        cards.values().stream().filter(c -> !c.targeted()).forEach(out::add);
        return out;
    }

    public UUID responseId() {
        return responseId;
    }

    /** L'événement à envoyer si le catalogue a changé depuis le dernier envoi (et n'est pas vide), sinon {@code null}. */
    public synchronized Event takeIfChanged() {
        if (cards.isEmpty() || version == sent) {
            return null;
        }
        sent = version;
        List<JarvisCard> list = cards();
        return new Event(responseId, list.stream().anyMatch(JarvisCard::targeted), list);
    }

    /** L'événement SSE {@code cards} : le jeu complet de la réponse (il remplace le précédent du même {@code responseId}). */
    public record Event(UUID responseId, boolean targeted, List<JarvisCard> cards) {
    }
}
