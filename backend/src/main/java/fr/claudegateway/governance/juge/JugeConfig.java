package fr.claudegateway.governance.juge;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Ce qui fait vivre le juge indépendant (F-94 / SF-94-02).
 *
 * <p><b>Un exécuteur dédié, et petit.</b> L'appel du juge est attendu avec un délai propre — celui
 * de la gouvernance, pas celui du chat (120 s). Cette attente demande un fil qui ne soit pas celui
 * du tour, et un pool <b>borné</b> : si tous les fils sont pris, le juge s'abstient plutôt que de
 * faire la queue derrière d'autres audits. Un filet qui attend son tour n'est plus un filet.</p>
 *
 * <p>Les fils sont <b>démons</b> : ils ne retiennent jamais l'arrêt de l'application. Un appel resté
 * en vol quand le délai a été dépassé finit sa vie seul, sans empêcher quoi que ce soit.</p>
 */
@Configuration
@EnableConfigurationProperties(JugeProperties.class)
public class JugeConfig {

    /** Nom du bean d'exécuteur, pour que l'injection ne dépende pas d'un type trop commun. */
    public static final String EXECUTOR = "jugeIndependantExecutor";

    /** Appels de juge menés de front. Au-delà, s'abstenir coûte moins cher qu'attendre. */
    public static final int THREADS = 4;

    @Bean(name = EXECUTOR, destroyMethod = "shutdownNow")
    public ExecutorService jugeIndependantExecutor() {
        // File d'attente NULLE et refus assumé : au-delà de THREADS appels de front, la soumission
        // est rejetée, le service rend « indisponible », et le tour se termine. Une file ferait
        // attendre le tour d'un utilisateur derrière l'audit d'un autre.
        return new ThreadPoolExecutor(0, THREADS, 60L, TimeUnit.SECONDS, new SynchronousQueue<>(),
                factory(), new ThreadPoolExecutor.AbortPolicy());
    }

    private static ThreadFactory factory() {
        AtomicInteger counter = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(runnable, "juge-independant-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
