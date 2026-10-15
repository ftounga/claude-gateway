package fr.claudegateway.atelier.routine;

import java.util.List;
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

import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRequest;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineRunView;
import fr.claudegateway.atelier.routine.RoutineDtos.RoutineView;
import fr.claudegateway.auth.CurrentUser;

/**
 * <b>Les routines d'un sujet</b> (F-183 / SF-183-01). L'identité vient <b>exclusivement</b> du
 * {@link CurrentUser} ; le service applique {@code requireOwned} puis relit la routine par
 * {@code id + user_id + workspace_id}. Aucune logique ici.
 */
@RestController
@RequestMapping("/workspaces/{workspaceId}/routines")
public class RoutineController {

    private final RoutineService service;
    private final RoutinePlanner planner;
    private final CurrentUser currentUser;

    public RoutineController(RoutineService service, RoutinePlanner planner, CurrentUser currentUser) {
        this.service = service;
        this.planner = planner;
        this.currentUser = currentUser;
    }

    /** Les comptes rendus non lus du sujet, pour la bande en tête du fil (SF-183-04). */
    @GetMapping("/runs/unseen")
    public List<RoutineRunView> unseen(@PathVariable UUID workspaceId) {
        return service.unseen(currentUser.requireId(), workspaceId);
    }

    /** Ferme une ligne de la bande (SF-183-04) ; idempotent. */
    @PostMapping("/runs/{runId}/ack")
    public ResponseEntity<Void> acknowledge(@PathVariable UUID workspaceId, @PathVariable UUID runId) {
        service.acknowledge(currentUser.requireId(), workspaceId, runId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<RoutineView> list(@PathVariable UUID workspaceId) {
        return service.list(currentUser.requireId(), workspaceId);
    }

    @PostMapping
    public ResponseEntity<RoutineView> create(@PathVariable UUID workspaceId, @RequestBody RoutineRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(service.create(currentUser.requireId(), workspaceId, request));
    }

    @PutMapping("/{routineId}")
    public RoutineView update(@PathVariable UUID workspaceId, @PathVariable UUID routineId,
            @RequestBody RoutineRequest request) {
        return service.update(currentUser.requireId(), workspaceId, routineId, request);
    }

    @DeleteMapping("/{routineId}")
    public ResponseEntity<Void> delete(@PathVariable UUID workspaceId, @PathVariable UUID routineId) {
        service.delete(currentUser.requireId(), workspaceId, routineId);
        return ResponseEntity.noContent().build();
    }

    /** « Lancer maintenant » (SF-183-02) : 202, l'exécution part en tâche de fond. */
    @PostMapping("/{routineId}/run")
    public ResponseEntity<RoutineRunView> run(@PathVariable UUID workspaceId, @PathVariable UUID routineId) {
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(planner.runNow(currentUser.requireId(), workspaceId, routineId));
    }

    /** L'historique d'une routine (SF-183-02). */
    @GetMapping("/{routineId}/runs")
    public List<RoutineRunView> runs(@PathVariable UUID workspaceId, @PathVariable UUID routineId) {
        return service.history(currentUser.requireId(), workspaceId, routineId);
    }

    @PostMapping("/{routineId}/accept")
    public RoutineView accept(@PathVariable UUID workspaceId, @PathVariable UUID routineId) {
        return service.accept(currentUser.requireId(), workspaceId, routineId);
    }
}
