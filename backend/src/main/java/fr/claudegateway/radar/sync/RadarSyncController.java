package fr.claudegateway.radar.sync;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.radar.RadarRunnerUnavailableException;
import fr.claudegateway.radar.RadarScopeResolver;
import fr.claudegateway.radar.RadarSync;
import fr.claudegateway.radar.RadarSyncTrigger;
import fr.claudegateway.runner.RunnerLiveness;
import fr.claudegateway.teams.TeamsAccessService;

/**
 * <b>La synchro du soir</b> d'un poste (F-100) : vérification guidée (SF-100-01), planification et
 * « Synchroniser maintenant » (SF-100-02).
 *
 * <p><b>Droit</b> : celui du Radar, provisoirement le droit Teams (comme {@code RadarController}).
 * <b>Isolation</b> : le poste est vérifié comme possédé avant tout appel au runner ; un poste d'autrui
 * rend « introuvable » et le runner n'est jamais appelé.</p>
 */
@RestController
@RequestMapping("/radar/hosts/{hostId}")
public class RadarSyncController {

    private final RadarVerificationService verificationService;
    private final RadarScheduleService scheduleService;
    private final RadarSyncLauncher launcher;
    private final RadarScopeResolver scopeResolver;
    private final TeamsAccessService teamsAccess;
    private final CurrentUser currentUser;
    private final RunnerLiveness liveness;
    private final RadarSyncControlService control;

    public RadarSyncController(RadarVerificationService verificationService, RadarScheduleService scheduleService,
            RadarSyncLauncher launcher, RadarScopeResolver scopeResolver, TeamsAccessService teamsAccess,
            CurrentUser currentUser, RunnerLiveness liveness, RadarSyncControlService control) {
        this.verificationService = verificationService;
        this.scheduleService = scheduleService;
        this.launcher = launcher;
        this.scopeResolver = scopeResolver;
        this.teamsAccess = teamsAccess;
        this.currentUser = currentUser;
        this.liveness = liveness;
        this.control = control;
    }

    // ------------------------------------------------------------ planification (SF-100-02)

    @GetMapping("/schedule")
    public RadarScheduleService.ScheduleView schedule(@PathVariable UUID hostId) {
        return scheduleService.view(scope(hostId));
    }

    @PutMapping("/schedule")
    public RadarScheduleService.ScheduleView updateSchedule(@PathVariable UUID hostId,
            @RequestBody(required = false) RadarScheduleService.ScheduleRequest request) {
        return scheduleService.update(scope(hostId), request);
    }

    /** « Synchroniser maintenant » : 202, la synchro tourne sur la machine. */
    @PostMapping("/syncs")
    public ResponseEntity<SyncStarted> syncNow(@PathVariable UUID hostId) {
        RadarScope scope = scope(hostId);
        if (!liveness.isAlive(scope.userId(), scope.hostId())) {
            // Hors ligne : rien n'est créé. Une synchro qui ne peut pas partir n'a pas à laisser de trace.
            throw new RadarRunnerUnavailableException("Poste hors ligne : lancez le runner, puis recommencez.");
        }
        RadarSync sync = launcher.start(scope, RadarSyncTrigger.MANUAL, null);
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(new SyncStarted(sync.getId(), sync.getTriggerKind(), sync.getStartedAt()));
    }

    // ------------------------------------------------------------ couverture et pilotage (SF-100-04)

    /** Annule une synchro en cours ; ce qui a été lu est conservé. */
    @PostMapping("/syncs/{syncId}/cancel")
    public SyncStarted cancel(@PathVariable UUID hostId, @PathVariable UUID syncId) {
        RadarSync sync = control.cancel(scope(hostId), syncId);
        return new SyncStarted(sync.getId(), sync.getTriggerKind(), sync.getStartedAt());
    }

    @GetMapping("/thread-rules")
    public java.util.List<RadarSyncControlService.RuleView> threadRules(@PathVariable UUID hostId) {
        return control.rules(scope(hostId));
    }

    /** <i>Ignorer ce fil</i> ou <i>lire ce canal</i> : une correction souveraine, annulable. */
    @PostMapping("/thread-rules")
    public RadarSyncControlService.RuleView addThreadRule(@PathVariable UUID hostId,
            @RequestBody(required = false) RadarSyncControlService.RuleRequest request) {
        return control.addRule(scope(hostId), request);
    }

    @DeleteMapping("/thread-rules/{ruleId}")
    public ResponseEntity<Void> removeThreadRule(@PathVariable UUID hostId, @PathVariable UUID ruleId) {
        control.removeRule(scope(hostId), ruleId);
        return ResponseEntity.noContent().build();
    }

    /** La synchro lancée. */
    public record SyncStarted(UUID syncId, RadarSyncTrigger trigger, OffsetDateTime startedAt) {
    }

    // ------------------------------------------------------------ vérification guidée (SF-100-01)

    @PostMapping("/verification")
    public VerificationResponse verify(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.verify(scope(hostId)));
    }

    @GetMapping("/verification")
    public VerificationResponse verification(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.current(scope(hostId)));
    }

    @DeleteMapping("/verification")
    public VerificationResponse resetVerification(@PathVariable UUID hostId) {
        return VerificationResponse.of(verificationService.reset(scope(hostId)));
    }

    private RadarScope scope(UUID hostId) {
        teamsAccess.requireAccess();
        // F-106 / SF-106-01 : une API de la Vigie ne lit que les clients activés dans la Vigie.
        return scopeResolver.requireInVigie(currentUser.requireId(), hostId);
    }

    /** La vérification telle que l'écran la rend : quatre cases et « tout est vu ». */
    public record VerificationResponse(boolean complete, OffsetDateTime verifiedAt, RadarVerification.Check session,
            RadarVerification.Check conversations, RadarVerification.Check meetings,
            RadarVerification.Check transcripts) {

        static VerificationResponse of(RadarVerification v) {
            return new VerificationResponse(v.complete(), v.verifiedAt(), v.session(), v.conversations(),
                    v.meetings(), v.transcripts());
        }
    }
}
