package fr.claudegateway.chat;

import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Exécuteur dédié au relais SSE des flux en streaming (SF-02-04) : les flux longs tournent hors du
 * pool de threads servlet pour ne pas l'épuiser.
 *
 * <p><b>Pourquoi une file de capacité 0</b> (F-70 / SF-70-01). La configuration précédente —
 * {@code core = 2}, {@code queue = 50}, {@code max = 8} — se lisait comme « huit flux en
 * parallèle ». Elle n'en autorisait que <b>deux</b> : un {@code ThreadPoolExecutor} crée des threads
 * jusqu'à {@code core}, puis <b>remplit la file</b>, et ne monte vers {@code max} qu'une fois la
 * file pleine. Avec cinquante places d'attente, le 3ᵉ flux était mis en file — l'{@code SseEmitter}
 * était bien rendu, la connexion ouverte, l'écran affichait « en cours », et <b>rien ne
 * démarrait</b> tant qu'un des deux premiers n'avait pas fini.</p>
 *
 * <p>C'est exactement ce que F-70 refuse : un agent qu'on croit actif et qui dort. Le PO a tranché
 * quatre terminaux <b>réellement</b> vivants ; une file d'attente invisible rendait la promesse
 * intenable. La capacité 0 donne une {@code SynchronousQueue} : chaque flux part <b>tout de suite</b>
 * sur un thread, jusqu'à {@code max}. Au-delà, la soumission est <b>refusée</b> — et un refus se dit
 * ({@code error: stream_busy} dans le flux), là où une attente se subit.</p>
 *
 * <p>Les threads au repos sont recyclés au bout de soixante secondes, cœur compris : un pool
 * dimensionné pour la pointe ne doit pas garder trente-deux threads vivants la nuit.</p>
 */
@Configuration
class ChatStreamConfig {

    @Bean("chatStreamExecutor")
    Executor chatStreamExecutor(
            @Value("${app.chat.stream.core-threads:8}") int coreThreads,
            @Value("${app.chat.stream.max-threads:32}") int maxThreads,
            @Value("${app.chat.stream.queue-capacity:0}") int queueCapacity) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreThreads));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreThreads), maxThreads));
        executor.setQueueCapacity(Math.max(0, queueCapacity));
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("chat-sse-");
        executor.initialize();
        return executor;
    }

    /**
     * Exécuteur des <b>rebranchements</b> sur un tour en cours (F-84 / SF-84-02).
     *
     * <p>Volontairement <b>distinct</b> de {@code chatStreamExecutor}. Celui-là compte des flux
     * <b>émetteurs</b> : chacun ouvre un tour, consomme des tokens et se facture, et c'est pourquoi
     * un refus s'y dit. Une vue rouverte, elle, est une <b>lectrice</b> : elle n'ouvre aucun tour et
     * ne coûte rien. Les faire concourir refuserait un rebranchement ({@code stream_busy}) au moment
     * précis où l'on veut revoir le travail déjà en cours — c'est-à-dire le contraire de ce que F-84
     * livre.</p>
     *
     * <p>Même forme que son voisin : file de capacité nulle, donc aucun rebranchement muet en
     * attente, et threads recyclés au repos.</p>
     */
    @Bean("turnAttachExecutor")
    Executor turnAttachExecutor(
            @Value("${app.chat.attach.core-threads:4}") int coreThreads,
            @Value("${app.chat.attach.max-threads:32}") int maxThreads) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(Math.max(1, coreThreads));
        executor.setMaxPoolSize(Math.max(Math.max(1, coreThreads), maxThreads));
        executor.setQueueCapacity(0);
        executor.setKeepAliveSeconds(60);
        executor.setAllowCoreThreadTimeOut(true);
        executor.setThreadNamePrefix("turn-attach-");
        executor.initialize();
        return executor;
    }
}
