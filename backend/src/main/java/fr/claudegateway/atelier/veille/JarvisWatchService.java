package fr.claudegateway.atelier.veille;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceService;
import fr.claudegateway.atelier.routine.RoutineService;
import fr.claudegateway.atelier.routine.RoutineSlots;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchTestView;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.JarvisWatchView;
import fr.claudegateway.atelier.veille.JarvisWatchDtos.ProposeRequest;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.InvalidWatchException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.UnsafeProbeException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchConflictException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchNotFoundException;
import fr.claudegateway.atelier.veille.JarvisWatchExceptions.WatchUnsupportedException;
import fr.claudegateway.runner.RunnerLiveness;

/**
 * <b>Poser une veille</b> (F-230 / SF-230-01, D1/D2/D5) : la proposition de l'agent, le test devant le PO,
 * l'activation par le PO, la suppression.
 *
 * <p><b>Rien d'automatique</b> : l'agent propose, la veille ne tourne pas ; le PO <b>teste</b> la sonde (un clic
 * = son consentement à la commande qu'il lit), puis l'<b>active</b>.</p>
 *
 * <p><b>Isolation.</b> Chaque geste de l'écran commence par {@code requireOwned(userId, workspaceId)} (404
 * indiscernable pour un sujet d'autrui), puis relit la veille par {@code id + user_id + workspace_id}. La
 * proposition de l'agent vérifie que le terminal du tour appartient au compte du tour.</p>
 */
@Service
public class JarvisWatchService {

    public static final int MAX_PER_SUBJECT = 10;
    public static final int MAX_NAME = 80;
    public static final int MAX_PLATFORM = 40;
    public static final int MAX_SCOPE = 500;
    public static final int MAX_COMMAND = 2000;
    public static final int MAX_SCRIPT_PATH = 300;
    public static final String DEFAULT_TIME_ZONE = RoutineService.DEFAULT_TIME_ZONE;
    /** 5 € par mois et par veille, au taux d'affichage 0,92 (SF-230-03). */
    public static final BigDecimal DEFAULT_MONTHLY_BUDGET_USD = new BigDecimal("5.44");

    private final WorkspaceService workspaceService;
    private final JarvisWatchRepository watches;
    private final JarvisWatchProbeRunner probes;
    private final RunnerLiveness liveness;
    private final Clock clock;

    public JarvisWatchService(WorkspaceService workspaceService, JarvisWatchRepository watches,
            JarvisWatchProbeRunner probes, RunnerLiveness liveness, Clock clock) {
        this.workspaceService = workspaceService;
        this.watches = watches;
        this.probes = probes;
        this.liveness = liveness;
        this.clock = clock;
    }

    /** Les veilles du sujet, dans l'ordre de leur pose. */
    @Transactional(readOnly = true)
    public List<JarvisWatchView> list(UUID userId, UUID workspaceId) {
        workspaceService.requireOwned(userId, workspaceId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        return watches.findByUserIdAndWorkspaceIdOrderByCreatedAtAsc(userId, workspaceId).stream()
                .map(watch -> view(watch, now))
                .toList();
    }

    /**
     * La proposition de l'agent (outil {@code veille_poser}) : validée, contrôlée (D5), rangée <b>inactive</b>.
     * Une veille du même nom dans le sujet est <b>remplacée</b> et redevient une proposition non testée — le
     * chemin de réparation d'une veille en panne (D6).
     */
    @Transactional
    public JarvisWatchView propose(UUID userId, Workspace workspace, ProposeRequest request) {
        if (workspace == null || userId == null || !userId.equals(workspace.getUserId())) {
            throw new WatchNotFoundException();
        }
        requireCapable(workspace);
        if (request == null) {
            throw new InvalidWatchException("Requête vide.");
        }
        String name = text(request.name(), "Le nom", MAX_NAME);
        String platform = text(request.platform(), "La plateforme", MAX_PLATFORM);
        String scope = text(request.scope(), "Le périmètre", MAX_SCOPE);
        String command = text(request.command(), "La commande", MAX_COMMAND);
        String runTimes = runTimes(request.runTimes());
        Integer chantier = request.chantierNumber();
        if (chantier != null && chantier < 1) {
            throw new InvalidWatchException("Le chantier est un numéro à partir de 1.");
        }
        JarvisWatchProbeGuard.problemIn(command, "la commande").ifPresent(problem -> {
            throw new UnsafeProbeException(problem);
        });
        String scriptPath = scriptPath(request.scriptPath(), command);
        String scriptHash = null;
        if (scriptPath != null) {
            String script = probes.readScript(userId, workspace, scriptPath)
                    .orElseThrow(() -> new InvalidWatchException("Le script de sonde « " + scriptPath
                            + " » est introuvable dans le dossier du sujet : écris-le là (pas dans le worktree "
                            + "d'un chantier), puis repropose la veille."));
            JarvisWatchProbeGuard.problemIn(script, "le script").ifPresent(problem -> {
                throw new UnsafeProbeException(problem);
            });
            scriptHash = JarvisWatchProbeRunner.sha256(script);
        }
        Optional<JarvisWatch> existing = watches.findFirstByUserIdAndWorkspaceIdAndNameIgnoreCase(userId,
                workspace.getId(), name);
        if (existing.isEmpty() && watches.countByUserIdAndWorkspaceId(userId, workspace.getId()) >= MAX_PER_SUBJECT) {
            throw new WatchConflictException("watch_limit",
                    "Ce sujet a déjà " + MAX_PER_SUBJECT + " veilles : supprimez-en une d'abord.");
        }
        JarvisWatch watch = existing.orElseGet(() -> JarvisWatch.builder()
                .userId(userId).workspaceId(workspace.getId()).origin(JarvisWatch.ORIGIN_AGENT)
                .timeZone(DEFAULT_TIME_ZONE).build());
        watch.setName(name);
        watch.setPlatform(platform);
        watch.setScope(scope);
        watch.setProbeCommand(command);
        watch.setProbeScriptPath(scriptPath);
        watch.setProbeScriptHash(scriptHash);
        watch.setRunTimes(runTimes);
        watch.setChantierNumber(chantier);
        // Une proposition — neuve ou réparée — ne tourne pas tant que le PO ne l'a pas retestée et activée.
        watch.setProposed(true);
        watch.setActive(false);
        watch.setTestedAt(null);
        watch.setTestItemCount(null);
        watch.setTestSummary(null);
        watch.setBroken(false);
        watch.setConsecutiveFailures(0);
        onReproposed(watch);
        return save(watch);
    }

    /**
     * <b>Tester la sonde devant le PO</b> : un clic, une exécution. Le résultat est montré au PO et retenu ; seul
     * un test réussi permet l'activation. Sans transaction englobante : la sonde peut durer une minute, aucune
     * connexion à la base n'est tenue pendant ce temps.
     */
    public JarvisWatchTestView test(UUID userId, UUID workspaceId, UUID watchId) {
        Workspace workspace = workspaceService.requireOwned(userId, workspaceId);
        JarvisWatch watch = require(userId, workspaceId, watchId);
        requireCapable(workspace);
        if (!liveness.isAlive(userId, workspace.getHostId())) {
            throw new WatchConflictException("watch_host_offline",
                    "Le poste est hors ligne : la sonde ne peut pas être testée maintenant.");
        }
        JarvisWatchProbeRunner.Outcome outcome = probes.run(watch, workspace);
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!outcome.ok()) {
            watch.setTestedAt(null);
            watch.setTestItemCount(null);
            watch.setTestSummary(null);
            watch.setUpdatedAt(now);
            watches.save(watch);
            return new JarvisWatchTestView(false, null, null, List.of(), outcome.errorMessage());
        }
        String summary = JarvisWatchItems.summary(outcome.items());
        watch.setTestedAt(now);
        watch.setTestItemCount(outcome.items().size());
        watch.setTestSummary(summary);
        watch.setUpdatedAt(now);
        watches.save(watch);
        return new JarvisWatchTestView(true, outcome.items().size(), summary, JarvisWatchItems.sample(outcome.items()),
                null);
    }

    /** Le PO active une veille testée : elle partira au <b>prochain</b> créneau, jamais pour un créneau passé. */
    @Transactional
    public JarvisWatchView accept(UUID userId, UUID workspaceId, UUID watchId) {
        workspaceService.requireOwned(userId, workspaceId);
        JarvisWatch watch = require(userId, workspaceId, watchId);
        if (!watch.isProposed()) {
            throw new WatchConflictException("watch_conflict", "Cette veille est déjà validée.");
        }
        if (watch.getTestedAt() == null) {
            throw new WatchConflictException("watch_untested",
                    "Testez la sonde avant d'activer la veille : vous validez ce que vous avez vu.");
        }
        watch.setProposed(false);
        watch.setActive(true);
        watch.setLastSlotAt(JarvisWatchSlots.dueSlot(watch, OffsetDateTime.now(clock)).orElse(null));
        watch.setMissedSlotAt(null);
        return save(watch);
    }

    /** Supprime une veille — et refuse ainsi une proposition de l'agent. */
    @Transactional
    public void delete(UUID userId, UUID workspaceId, UUID watchId) {
        workspaceService.requireOwned(userId, workspaceId);
        watches.delete(require(userId, workspaceId, watchId));
    }

    /** La veille de ce propriétaire et de ce sujet, ou 404. */
    public JarvisWatch require(UUID userId, UUID workspaceId, UUID watchId) {
        return watches.findByIdAndUserIdAndWorkspaceId(watchId, userId, workspaceId)
                .orElseThrow(WatchNotFoundException::new);
    }

    /** Une veille tourne sur le poste d'un <b>sujet</b> — même garde que les routines. */
    public static boolean isWatchCapable(Workspace workspace) {
        return RoutineService.isRoutineCapable(workspace);
    }

    /** Une veille reproposée repart d'un instantané vierge : sa sonde a pu changer de forme (SF-230-02). */
    void onReproposed(JarvisWatch watch) {
        if (snapshots != null && watch.getId() != null) {
            snapshots.deleteForWatch(watch.getId(), watch.getUserId());
        }
    }

    /** Les instantanés et le journal des passages (SF-230-02) ; absents des formes historiques / tests unitaires. */
    private JarvisWatchSnapshotRepository snapshots;
    private JarvisWatchRunRepository runs;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setPassageStores(JarvisWatchSnapshotRepository snapshots, JarvisWatchRunRepository runs) {
        this.snapshots = snapshots;
        this.runs = runs;
    }

    /** Les 20 derniers passages d'une veille (SF-230-02). */
    @Transactional(readOnly = true)
    public List<JarvisWatchDtos.JarvisWatchRunView> history(UUID userId, UUID workspaceId, UUID watchId) {
        workspaceService.requireOwned(userId, workspaceId);
        JarvisWatch watch = require(userId, workspaceId, watchId);
        if (runs == null) {
            return List.of();
        }
        return runs.findByWatchIdAndUserIdOrderByStartedAtDesc(watch.getId(), userId,
                        org.springframework.data.domain.PageRequest.of(0, HISTORY)).stream()
                .map(JarvisWatchDtos.JarvisWatchRunView::of)
                .toList();
    }

    /** Passages rendus par l'historique. */
    public static final int HISTORY = 20;

    JarvisWatchView view(JarvisWatch watch, OffsetDateTime now) {
        return JarvisWatchView.of(watch, now);
    }

    private JarvisWatchView save(JarvisWatch watch) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (watch.getCreatedAt() == null) {
            watch.setCreatedAt(now);
        }
        watch.setUpdatedAt(now);
        return view(watches.save(watch), now);
    }

    private static void requireCapable(Workspace workspace) {
        if (!isWatchCapable(workspace)) {
            throw new WatchUnsupportedException(
                    "Une veille tourne sur le poste d'un sujet : ce terminal ne peut pas en porter.");
        }
    }

    /** 1 à 6 heures {@code HH:mm}, distinctes, triées, rangées {@code "09:00,14:00"}. */
    static String runTimes(List<String> values) {
        if (values == null || values.isEmpty()) {
            throw new InvalidWatchException("Donne au moins une heure de passage (HH:mm), heure du poste.");
        }
        TreeSet<String> times = new TreeSet<>();
        for (String value : values) {
            // « 9:00 », « 9h » ou « 9h30 », comme le PO les dit, deviennent « 09:00 », « 09:30 ».
            String spoken = value == null ? null : value.strip().toLowerCase(java.util.Locale.ROOT)
                    .replaceFirst("^(\\d{1,2})h$", "$1:00").replaceFirst("^(\\d{1,2})h(\\d{2})$", "$1:$2")
                    .replaceFirst("^(\\d):", "0$1:");
            String time = RoutineSlots.parseTime(spoken).map(JarvisWatchSlots::format)
                    .orElseThrow(() -> new InvalidWatchException("L'heure s'écrit HH:mm (ex. 09:00)."));
            times.add(time);
        }
        if (times.size() > JarvisWatchSlots.MAX_TIMES) {
            throw new InvalidWatchException("Une veille passe au plus " + JarvisWatchSlots.MAX_TIMES + " fois par jour.");
        }
        return String.join(",", times);
    }

    /**
     * Le script de sonde : exigé si la commande appelle un fichier de script, présent dans la commande, relatif et
     * sans remontée.
     */
    static String scriptPath(String declared, String command) {
        String path = declared == null || declared.isBlank() ? null : declared.strip();
        if (path == null) {
            if (JarvisWatchProbeGuard.referencesScript(command)) {
                throw new UnsafeProbeException("La commande appelle un script : déclare-le (champ « script ») pour "
                        + "qu'il soit relu et épinglé.");
            }
            return null;
        }
        if (path.length() > MAX_SCRIPT_PATH || path.startsWith("/") || path.startsWith("~") || path.contains("..")
                || path.matches("^[A-Za-z]:.*")) {
            throw new InvalidWatchException("Le script est un chemin relatif au dossier du sujet, sans « .. ».");
        }
        String normalized = path.replace('\\', '/');
        String bare = normalized.startsWith("./") ? normalized.substring(2) : normalized;
        if (!command.replace('\\', '/').contains(bare)) {
            throw new UnsafeProbeException("Le script déclaré n'est pas celui que la commande appelle.");
        }
        return bare;
    }

    private static String text(String value, String label, int max) {
        if (value == null || value.isBlank()) {
            throw new InvalidWatchException(label + " est obligatoire.");
        }
        String v = value.strip();
        if (v.length() > max) {
            throw new InvalidWatchException(label + " fait au plus " + max + " caractères.");
        }
        return v;
    }
}
