package fr.claudegateway.atelier.veille;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.push.PushEvent;
import fr.claudegateway.push.PushNotificationService;

/**
 * <b>Un passage de veille</b> (F-230 / SF-230-02, D3/D6) : la sonde, le diff, l'instantané, le journal — et
 * <b>rien d'autre</b> si rien n'a bougé. Jamais un tour d'agent, jamais d'IA ici.
 *
 * <p>L'état de la veille (dernier passage, échecs d'affilée, panne) s'écrit par un {@code UPDATE} ciblé : une
 * pause posée par le PO pendant le passage n'est jamais écrasée.</p>
 */
@Component
public class JarvisWatchPassage {

    /** Échecs d'affilée qui mettent une veille en panne (D6). */
    public static final int BROKEN_AFTER = 2;

    private static final Logger log = LoggerFactory.getLogger(JarvisWatchPassage.class);

    private final JarvisWatchProbeRunner probes;
    private final JarvisWatchRepository watches;
    private final JarvisWatchSnapshotRepository snapshots;
    private final JarvisWatchRunRepository runs;
    private final JarvisWatchThreadNote threadNote;
    private final TransactionTemplate transactions;
    private final Clock clock;
    private PushNotificationService push;
    private List<JarvisWatchChangeListener> listeners = List.of();

    public JarvisWatchPassage(JarvisWatchProbeRunner probes, JarvisWatchRepository watches,
            JarvisWatchSnapshotRepository snapshots, JarvisWatchRunRepository runs, JarvisWatchThreadNote threadNote,
            PlatformTransactionManager transactionManager, Clock clock) {
        this.probes = probes;
        this.watches = watches;
        this.snapshots = snapshots;
        this.runs = runs;
        this.threadNote = threadNote;
        this.transactions = new TransactionTemplate(transactionManager);
        this.clock = clock;
    }

    @Autowired(required = false)
    public void setPush(PushNotificationService push) {
        this.push = push;
    }

    /** F-247 / SF-247-07 — instantanés → faits du registre (relevés et veilles existantes). Facultatif. */
    private JarvisWatchStateHook stateHook;

    @Autowired(required = false)
    public void setStateHook(JarvisWatchStateHook stateHook) {
        this.stateHook = stateHook;
    }

    @Autowired(required = false)
    public void setListeners(List<JarvisWatchChangeListener> listeners) {
        this.listeners = listeners == null ? List.of() : List.copyOf(listeners);
    }

    /** Lance le passage et rend la ligne de journal close. */
    public JarvisWatchRun run(JarvisWatch watch, Workspace workspace, OffsetDateTime slot, String trigger) {
        return run(watch, workspace, slot, trigger, null);
    }

    /** F-247 / SF-247-09 — un passage au délai de sonde donné ({@code null} : le délai habituel). */
    public JarvisWatchRun run(JarvisWatch watch, Workspace workspace, OffsetDateTime slot, String trigger,
            Long timeoutMs) {
        OffsetDateTime started = OffsetDateTime.now(clock);
        JarvisWatchProbeRunner.Outcome outcome = timeoutMs == null ? probes.run(watch, workspace)
                : probes.run(watch, workspace, timeoutMs);
        OffsetDateTime finished = OffsetDateTime.now(clock);
        JarvisWatchRun run = JarvisWatchRun.builder().userId(watch.getUserId()).workspaceId(watch.getWorkspaceId())
                .watchId(watch.getId()).slotAt(slot).trigger(trigger).startedAt(started).finishedAt(finished)
                .durationMs(outcome.durationMs()).build();
        if (!outcome.ok() && outcome.errorCode().startsWith(JarvisWatchProbeRunner.ERR_YIELDED)) {
            return yielded(watch, run, slot, outcome);
        }
        if (!outcome.ok()) {
            return failed(watch, run, outcome);
        }
        if (stateHook != null) {
            try {
                stateHook.record(watch, workspace, outcome.items(), finished);
            } catch (RuntimeException e) {
                log.warn("Veille : faits non rangés ({})", e.getClass().getSimpleName());
            }
        }
        Optional<JarvisWatchSnapshot> previous = snapshots.findByWatchIdAndUserId(watch.getId(), watch.getUserId());
        List<JarvisWatchDiff.Change> changes = previous.isEmpty() ? List.of()
                : JarvisWatchDiff.between(JarvisWatchItems.fromJson(previous.get().getItemsJson()), outcome.items());
        String status = previous.isEmpty() ? JarvisWatchRun.BASELINE
                : changes.isEmpty() ? JarvisWatchRun.NO_CHANGE : JarvisWatchRun.CHANGED;
        run.setStatus(status);
        run.setChangeCount(changes.size());
        boolean wasBroken = watch.isBroken();
        JarvisWatchRun saved = transactions.execute(tx -> {
            if (previous.isEmpty() || !changes.isEmpty()) {
                JarvisWatchSnapshot snapshot = previous.orElseGet(() -> JarvisWatchSnapshot.builder()
                        .userId(watch.getUserId()).watchId(watch.getId()).build());
                snapshot.setItemsJson(JarvisWatchItems.toJson(outcome.items()));
                snapshot.setItemCount(outcome.items().size());
                snapshot.setTakenAt(finished);
                snapshots.save(snapshot);
            }
            watches.recordOutcome(watch.getId(), watch.getUserId(), finished, status, 0, false);
            return runs.save(run);
        });
        if (watch.isState()) {
            return saved; // un relevé ne parle jamais : ni note au fil, ni alerte, ni IA
        }
        if (wasBroken) {
            threadNote.post(watch, "réparée : la sonde répond de nouveau.");
        }
        if (!changes.isEmpty()) {
            for (JarvisWatchChangeListener listener : listeners) {
                try {
                    listener.onChange(watch, workspace, saved, changes);
                } catch (RuntimeException e) {
                    log.warn("Veille : écouteur de changement en échec ({})", e.getClass().getSimpleName());
                }
            }
        }
        return saved;
    }

    /**
     * F-247 / SF-247-07 — le poste a cédé la place (occupé, ou préempté par le PO) : ni échec, ni panne ; le créneau
     * est rendu pour être repris au passage suivant.
     */
    private JarvisWatchRun yielded(JarvisWatch watch, JarvisWatchRun run, OffsetDateTime slot,
            JarvisWatchProbeRunner.Outcome outcome) {
        run.setStatus(JarvisWatchRun.YIELDED);
        String code = outcome.errorCode().substring(outcome.errorCode().indexOf(':') + 1);
        run.setErrorCode(code.length() > 32 ? code.substring(0, 32) : code);
        return transactions.execute(tx -> {
            if (slot != null) {
                watches.releaseSlot(watch.getId(), slot);
            }
            return runs.save(run);
        });
    }

    /** Un échec : compté ; au 2ᵉ d'affilée, la veille passe en panne, une seule fois notifiée (D6). */
    private JarvisWatchRun failed(JarvisWatch watch, JarvisWatchRun run, JarvisWatchProbeRunner.Outcome outcome) {
        run.setStatus(JarvisWatchRun.FAILED);
        run.setErrorCode(outcome.errorCode());
        int failures = watch.getConsecutiveFailures() + 1;
        boolean broken = failures >= BROKEN_AFTER;
        boolean newlyBroken = broken && !watch.isBroken();
        JarvisWatchRun saved = transactions.execute(tx -> {
            watches.recordOutcome(watch.getId(), watch.getUserId(), run.getFinishedAt(), JarvisWatchRun.FAILED,
                    failures, broken);
            return runs.save(run);
        });
        if (newlyBroken) {
            threadNote.post(watch, "en panne : la sonde a échoué " + failures + " fois de suite ("
                    + outcome.errorMessage() + "). Rien de neuf ne veut donc pas dire « rien n'a bougé ». "
                    + "Demande-moi de la réparer : je corrige la sonde et je te la repropose.");
            if (push != null) {
                try {
                    push.notify(watch.getUserId(), watch.getWorkspaceId(), PushEvent.WATCH_BROKEN);
                } catch (RuntimeException e) {
                    // Best-effort : une notification ne fait jamais échouer un passage.
                }
            }
        }
        return saved;
    }
}
