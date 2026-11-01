package fr.claudegateway.jarvis;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.atelier.AtelierAccessService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>La mesure d'adoption du mode Jarvis</b> (F-231 / SF-231-05, D9). L'écran déclare ses événements
 * ({@code POST /api/jarvis/usage}) ; l'administrateur lit la mesure sur <b>ses</b> journées
 * ({@code GET /api/admin/jarvis/measure}, 403 sinon — même patron que la mesure des veilles F-230).
 */
@RestController
public class JarvisAdoptionController {

    static final int DEFAULT_DAYS = 7;

    private final JarvisAdoptionService service;
    private final AtelierAccessService atelierAccess;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JarvisAdoptionController(JarvisAdoptionService service, AtelierAccessService atelierAccess,
            AdminService adminService, CurrentUser currentUser) {
        this.service = service;
        this.atelierAccess = atelierAccess;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    @PostMapping("/jarvis/usage")
    public ResponseEntity<?> record(@RequestBody(required = false) UsageEvent body) {
        atelierAccess.requireAccess();
        try {
            service.record(currentUser.requireId(), body == null ? null : body.event(),
                    body == null ? null : body.seconds(), body == null ? null : body.scenes(),
                    body == null ? null : body.lit(), body == null ? null : body.shows());
            return ResponseEntity.noContent().build();
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("validation_error", e.getMessage()));
        }
    }

    @GetMapping("/admin/jarvis/measure")
    public JarvisAdoptionService.Measure measure(@RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        return service.measure(currentUser.requireId(), days == null || days <= 0 ? DEFAULT_DAYS : days);
    }

    /**
     * Corps de {@code POST /api/jarvis/usage} : {@code SESSION}, {@code SPOKEN} (+ secondes), {@code WAKE},
     * {@code IDLE_END} (SF-233-11) ; F-234 / SF-234-11 : {@code SCENES} (+ {@code scenes}, {@code lit},
     * {@code shows}), {@code TOUCH}, {@code SOBER}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UsageEvent(String event, Long seconds, java.util.Map<String, Integer> scenes, Integer lit,
            Integer shows) {

        /** Forme d'avant F-234 (sans scènes). */
        public UsageEvent(String event, Long seconds) {
            this(event, seconds, null, null, null);
        }
    }
}
