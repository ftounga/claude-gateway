package fr.claudegateway.runner.update;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.Console;
import fr.claudegateway.runner.FrameSender;
import fr.claudegateway.runner.RunnerBuild;
import fr.claudegateway.runner.launcher.LauncherHome;
import fr.claudegateway.runner.launcher.LauncherPolicy;

/**
 * La <b>commande de mise à jour</b>, côté runner (F-111 / SF-111-04) : un par processus, branché sur le
 * transport du moment (WebSocket ou long-polling).
 *
 * <ol>
 *   <li>refuser ce qui n'a pas de sens : pas de lanceur pour redémarrer, version pas plus récente ;</li>
 *   <li>télécharger et <b>vérifier</b> (SF-111-03) — rien n'est écrit dans {@code versions/} avant ;</li>
 *   <li>attendre le <b>calme</b> : aucune commande, capture, synchro ni téléchargement en cours — ou
 *       « Forcer » ;</li>
 *   <li>écrire {@code next-version}, dire « redémarrage », et <b>sortir en 75</b> : le lanceur démarre
 *       la nouvelle version, qui relit le jeton du poste.</li>
 * </ol>
 *
 * <p>Chaque étape est dite à la gateway ({@code update_status}) : c'est elle qui l'écrit dans le journal
 * et à l'écran.</p>
 */
public final class RunnerUpdater {

    /** Ce qui installe une version vérifiée (en production : {@link UpdateInstaller}). */
    @FunctionalInterface
    public interface Installer {
        java.nio.file.Path install(String id, String expectedSha256) throws UpdateRejectedException;
    }

    /** Refus : ce runner n'a pas de lanceur pour le redémarrer. */
    public static final String NO_LAUNCHER = "no_launcher";
    /** Refus : la version demandée n'est pas plus récente que celle qui tourne. */
    public static final String NOT_NEWER = "not_newer";

    static final Duration DEFAULT_TICK = Duration.ofSeconds(1);
    static final Duration DEFAULT_STATUS_EVERY = Duration.ofSeconds(30);

    private final Installer installer;
    private final LauncherHome home;
    private final boolean underLauncher;
    private final RunnerBuild current;
    private final Supplier<List<String>> busy;
    private final IntConsumer exit;
    private final Console console;
    private final Duration tick;
    private final Duration statusEvery;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicReference<FrameSender> sender = new AtomicReference<>();
    private final AtomicReference<Order> inProgress = new AtomicReference<>();

    public RunnerUpdater(Installer installer, LauncherHome home, boolean underLauncher, RunnerBuild current,
            Supplier<List<String>> busy, IntConsumer exit, Console console) {
        this(installer, home, underLauncher, current, busy, exit, console, DEFAULT_TICK, DEFAULT_STATUS_EVERY);
    }

    RunnerUpdater(Installer installer, LauncherHome home, boolean underLauncher, RunnerBuild current,
            Supplier<List<String>> busy, IntConsumer exit, Console console, Duration tick, Duration statusEvery) {
        this.installer = installer;
        this.home = home;
        this.underLauncher = underLauncher;
        this.current = current;
        this.busy = busy;
        this.exit = exit;
        this.console = console;
        this.tick = tick;
        this.statusEvery = statusEvery;
    }

    /** Branche la file d'émission du transport courant (à chaque (re)connexion). */
    public void attach(FrameSender frameSender) {
        sender.set(frameSender);
    }

    /** Une commande de mise à jour reçue (trame {@code update}). Ne lève jamais ; ne bloque pas. */
    public void onUpdate(JsonNode frame) {
        Order order = Order.of(frame);
        if (order == null) {
            return;
        }
        Order running = inProgress.get();
        if (running != null) {
            if (running.version.equals(order.version) && order.force.get()) {
                running.force.set(true);
                console.info("Mise à jour : « Forcer » demandé — le runner n'attend plus la fin des activités.");
            }
            return;
        }
        if (!underLauncher) {
            status(order, "failed", NO_LAUNCHER, "Ce runner a été lancé sans lanceur (--no-launcher ou ancienne "
                    + "installation) : il ne peut pas redémarrer seul. Mettez-le à jour à la main.", null);
            return;
        }
        RunnerBuild target = RunnerBuild.parseId(order.version).orElse(null);
        if (target == null || target.compareTo(current) <= 0) {
            status(order, "failed", NOT_NEWER, "La version " + order.version + " n'est pas plus récente que "
                    + current.id() + " : rien n'est installé.", null);
            return;
        }
        if (!inProgress.compareAndSet(null, order)) {
            return;
        }
        Thread worker = new Thread(() -> run(order), "runner-update");
        worker.setDaemon(true);
        worker.start();
    }

    /** Le déroulé d'une mise à jour acceptée. Synchrone : c'est lui que les tests exercent. */
    void run(Order order) {
        try {
            console.info("Mise à jour vers " + order.version + " : téléchargement et vérification…");
            status(order, "downloading", null, null, null);
            try {
                installer.install(order.version, order.sha256);
            } catch (UpdateRejectedException e) {
                console.warn("Mise à jour vers " + order.version + " refusée : " + e.getMessage());
                status(order, "failed", e.reason(), e.getMessage(), null);
                return;
            }
            long lastStatus = -1;
            while (true) {
                List<String> activities = busy.get();
                if (activities.isEmpty() || order.force.get()) {
                    break;
                }
                long now = System.nanoTime();
                if (lastStatus < 0 || now - lastStatus >= statusEvery.toNanos()) {
                    console.info("Mise à jour prête : en attente de la fin des activités en cours ("
                            + String.join(", ", activities) + ").");
                    status(order, "waiting", null, null, activities);
                    lastStatus = now;
                }
                if (!sleep(tick)) {
                    return;
                }
            }
            try {
                home.setNextVersion(order.version);
            } catch (IOException | RuntimeException e) {
                status(order, "failed", UpdateRejectedException.INSTALL_FAILED,
                        "next-version non écrit (" + e.getMessage() + ")", null);
                return;
            }
            console.info("Mise à jour vers " + order.version + " : redémarrage du runner.");
            status(order, "restarting", null, null, null);
            // La trame part par une file asynchrone : on lui laisse le temps de sortir avant de quitter.
            sleep(Duration.ofSeconds(1));
            exit.accept(LauncherPolicy.EXIT_UPDATE);
        } finally {
            inProgress.compareAndSet(order, null);
        }
    }

    private void status(Order order, String state, String reason, String message, List<String> activities) {
        FrameSender current = sender.get();
        if (current == null) {
            return;
        }
        ObjectNode frame = mapper.createObjectNode();
        frame.put("type", "update_status");
        if (order.updateId != null) {
            frame.put("updateId", order.updateId);
        }
        frame.put("version", order.version);
        frame.put("state", state);
        if (reason != null) {
            frame.put("reason", reason);
        }
        if (message != null) {
            frame.put("message", message);
        }
        if (activities != null) {
            ArrayNode list = frame.putArray("busy");
            activities.forEach(list::add);
        }
        current.send(frame.toString());
    }

    private static boolean sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Une commande reçue. */
    static final class Order {
        final String updateId;
        final String version;
        final String sha256;
        final AtomicBoolean force;

        private Order(String updateId, String version, String sha256, boolean force) {
            this.updateId = updateId;
            this.version = version;
            this.sha256 = sha256;
            this.force = new AtomicBoolean(force);
        }

        static Order of(JsonNode frame) {
            if (frame == null || !frame.path("version").isTextual()) {
                return null;
            }
            String version = frame.path("version").asText().trim();
            if (version.isEmpty() || version.length() > 64) {
                return null;
            }
            String updateId = frame.path("updateId").isTextual() ? frame.path("updateId").asText() : null;
            String sha = frame.path("sha256").isTextual() ? frame.path("sha256").asText() : null;
            return new Order(updateId, version, sha, frame.path("force").asBoolean(false));
        }
    }
}
