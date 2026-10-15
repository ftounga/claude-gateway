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
    private final CurrentUser currentUser;

    public RoutineController(RoutineService service, CurrentUser currentUser) {
        this.service = service;
        this.currentUser = currentUser;
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

    @PostMapping("/{routineId}/accept")
    public RoutineView accept(@PathVariable UUID workspaceId, @PathVariable UUID routineId) {
        return service.accept(currentUser.requireId(), workspaceId, routineId);
    }
}
