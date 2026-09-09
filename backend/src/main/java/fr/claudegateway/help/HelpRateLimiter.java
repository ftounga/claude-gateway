package fr.claudegateway.help;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Garde-fou de débit du chatbot d'aide (F-54 / SF-54-01) : au plus {@code maxQuestions} questions
 * par utilisateur sur une fenêtre glissante.
 *
 * <p><b>Pourquoi</b> : l'aide ne consomme pas le quota de l'utilisateur (facturer la question
 * « pourquoi ça ne démarre pas ? » serait hostile, et c'est exactement la question qu'on veut qu'il
 * pose). L'appel reste donc à la charge de la plateforme, et sans borne un compte connecté pourrait
 * boucler. Vingt questions par heure ne gênent aucun usage réel.</p>
 *
 * <p><b>Portée</b> : compteur <b>en mémoire</b>, donc <b>par pod</b>. Sous mise à l'échelle
 * horizontale, le plafond effectif est {@code maxQuestions × pods}. C'est un garde-fou de coût, pas
 * une règle de facturation : la précision ne vaut ni une table ni un cache distribué.</p>
 *
 * <p>L'état est <b>indexé par {@code user_id}</b> : le compteur d'un utilisateur ne borne jamais un
 * autre. Toute la mise à jour d'une fenêtre se fait dans un {@code compute} de la carte concurrente,
 * qui verrouille l'entrée de CET utilisateur — deux utilisateurs ne se bloquent jamais, et aucune
 * question ne peut se perdre entre une purge et un enregistrement.</p>
 */
@Component
public class HelpRateLimiter {

    /**
     * Nombre d'utilisateurs suivis au-delà duquel les fenêtres périmées sont purgées. Sans lui, la
     * carte grossirait indéfiniment sur un pod de longue vie.
     */
    private static final int CLEANUP_THRESHOLD = 1_000;

    private final HelpProperties properties;
    private final Clock clock;
    private final Map<UUID, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public HelpRateLimiter(HelpProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Enregistre une question pour cet utilisateur, ou refuse si la fenêtre est pleine.
     *
     * @param userId utilisateur authentifié (l'état de débit lui est propre)
     * @throws HelpRateLimitExceededException si le plafond de la fenêtre est atteint
     */
    public void acquire(UUID userId) {
        Instant now = Instant.now(clock);
        Instant floor = now.minus(properties.window());

        if (hits.size() > CLEANUP_THRESHOLD) {
            evictStale(floor);
        }

        boolean[] refused = new boolean[1];
        hits.compute(userId, (key, window) -> {
            Deque<Instant> current = window == null ? new ArrayDeque<>() : window;
            purge(current, floor);
            if (current.size() >= properties.maxQuestions()) {
                refused[0] = true;
            } else {
                current.addLast(now);
            }
            return current;
        });

        if (refused[0]) {
            throw new HelpRateLimitExceededException(
                    "Trop de questions à l'aide sur une courte période. Réessayez dans quelques minutes.");
        }
    }

    /** Purge les utilisateurs dont la fenêtre ne contient plus aucune question récente. */
    private void evictStale(Instant floor) {
        for (UUID userId : Map.copyOf(hits).keySet()) {
            hits.computeIfPresent(userId, (key, window) -> {
                purge(window, floor);
                return window.isEmpty() ? null : window;
            });
        }
    }

    /** Retire de la fenêtre les questions antérieures ou égales au plancher. */
    private static void purge(Deque<Instant> window, Instant floor) {
        while (!window.isEmpty() && !window.peekFirst().isAfter(floor)) {
            window.pollFirst();
        }
    }
}
