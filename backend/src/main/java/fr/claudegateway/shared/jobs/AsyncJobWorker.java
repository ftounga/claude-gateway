package fr.claudegateway.shared.jobs;

import java.lang.management.ManagementFactory;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import jakarta.annotation.PreDestroy;

/**
 * <b>Les fils de travail de la file durable</b> (F-247 / SF-247-03) : pour chaque type, un pool de démons <b>à
 * priorité basse</b> borné à sa concurrence ; une scrutation courte prend ce qui attend, un faucheur rend les baux
 * expirés, une purge nocturne vide les travaux finis. Rien n'est jamais exécuté dans le fil d'un appelant.
 */
@Component
public class AsyncJobWorker {

    private static final Logger log = LoggerFactory.getLogger(AsyncJobWorker.class);

    private final AsyncJobQueue queue;
    private final List<AsyncJobHandler> handlers;
    private final boolean enabled;
    private final String owner;
    private final Map<String, ExecutorService> pools = new ConcurrentHashMap<>();
    private final Map<String, Semaphore> slots = new ConcurrentHashMap<>();

    public AsyncJobWorker(AsyncJobQueue queue, List<AsyncJobHandler> handlers,
            @Value("${app.jobs.enabled:true}") boolean enabled) {
        this.queue = queue;
        this.handlers = List.copyOf(handlers);
        this.enabled = enabled;
        this.owner = ownerName();
        for (AsyncJobHandler h : this.handlers) {
            queue.setPendingCap(h.kind(), h.pendingCap());
        }
    }

    /** Scrute la file (toutes les 2 s) : prend autant de travaux que de fils libres, par type. */
    @Scheduled(fixedDelayString = "${app.jobs.poll-ms:2000}", initialDelayString = "${app.jobs.initial-delay-ms:15000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        for (AsyncJobHandler h : handlers) {
            try {
                if (h.active()) {
                    drain(h);
                }
            } catch (RuntimeException e) {
                log.warn("File durable : scrutation {} en échec ({})", h.kind(), e.getClass().getSimpleName());
            }
        }
    }

    /** Prend et lance ce que les fils libres de ce type peuvent porter. Visible des tests. */
    int drain(AsyncJobHandler h) {
        Semaphore free = slots.computeIfAbsent(h.kind(), k -> new Semaphore(Math.max(1, h.concurrency())));
        int available = free.availablePermits();
        if (available <= 0) {
            return 0;
        }
        List<AsyncJobQueue.Job> jobs = queue.claim(h.kind(), available, owner);
        ExecutorService pool = pools.computeIfAbsent(h.kind(), k -> pool(k, Math.max(1, h.concurrency())));
        for (AsyncJobQueue.Job job : jobs) {
            free.acquireUninterruptibly();
            pool.execute(() -> {
                try {
                    run(h, job);
                } finally {
                    free.release();
                }
            });
        }
        return jobs.size();
    }

    /** Exécute un travail et range son issue. Jamais d'exception. */
    void run(AsyncJobHandler h, AsyncJobQueue.Job job) {
        long t0 = System.nanoTime();
        try {
            AsyncJobHandler.Outcome out = h.handle(job);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            if (out == null || out.kind() == AsyncJobHandler.Outcome.Kind.DONE) {
                queue.complete(job.id(), owner, ms, out == null ? BigDecimal.ZERO : out.costUsd());
            } else if (out.kind() == AsyncJobHandler.Outcome.Kind.DROP) {
                queue.drop(job.id(), owner, out.reason());
            } else {
                queue.fail(job.id(), owner, out.reason(), false, ms, out.costUsd());
            }
        } catch (RuntimeException e) {
            long ms = (System.nanoTime() - t0) / 1_000_000;
            log.warn("File durable : travail {} en échec ({})", h.kind(), e.getClass().getSimpleName());
            queue.fail(job.id(), owner, e.getClass().getSimpleName(), true, ms, BigDecimal.ZERO);
        }
    }

    @Scheduled(fixedDelayString = "${app.jobs.reap-ms:30000}", initialDelayString = "${app.jobs.initial-delay-ms:15000}")
    public void reap() {
        if (!enabled) {
            return;
        }
        try {
            queue.reap();
        } catch (RuntimeException e) {
            log.warn("File durable : faucheur en échec ({})", e.getClass().getSimpleName());
        }
    }

    @Scheduled(cron = "${app.jobs.purge-cron:0 20 4 * * *}", zone = "Europe/Paris")
    public void purge() {
        try {
            queue.purge();
        } catch (RuntimeException e) {
            log.warn("File durable : purge en échec ({})", e.getClass().getSimpleName());
        }
    }

    private static ExecutorService pool(String kind, int threads) {
        AtomicInteger n = new AtomicInteger();
        return Executors.newFixedThreadPool(threads, r -> {
            Thread t = new Thread(r, "job-" + kind.toLowerCase(java.util.Locale.ROOT) + "-" + n.incrementAndGet());
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });
    }

    private static String ownerName() {
        String host = System.getenv().getOrDefault("HOSTNAME", ManagementFactory.getRuntimeMXBean().getName());
        String name = host + "-" + UUID.randomUUID().toString().substring(0, 8);
        return name.length() > 64 ? name.substring(name.length() - 64) : name;
    }

    String owner() {
        return owner;
    }

    @PreDestroy
    void stop() {
        pools.values().forEach(ExecutorService::shutdownNow);
    }
}
