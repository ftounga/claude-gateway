package fr.claudegateway.atelier.poste;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import fr.claudegateway.admin.AdminService;
import fr.claudegateway.auth.CurrentUser;
import fr.claudegateway.shared.error.ErrorResponse;

/**
 * <b>La mesure du terminal central</b> (F-178 / SF-178-04) — <b>administrateur uniquement</b>, même patron
 * que la mesure du parcours (F-176) : garde {@link AdminService#assertAdmin()} (403 sinon), et l'isolation
 * {@code user_id} s'applique en plus — l'administrateur mesure SES terminaux du poste.
 */
@RestController
@RequestMapping("/admin/poste-tools")
public class PosteToolsMeasureController {

    static final int DEFAULT_DAYS = 7;

    private final PosteToolsMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public PosteToolsMeasureController(PosteToolsMeasureService service, AdminService adminService,
            CurrentUser currentUser) {
        this.service = service;
        this.adminService = adminService;
        this.currentUser = currentUser;
    }

    /** Avant / après {@code pivot} (date ISO, défaut : il y a {@code days} jours). */
    @GetMapping("/measure")
    public ResponseEntity<?> measure(@RequestParam(required = false) String pivot,
            @RequestParam(required = false) Integer days) {
        adminService.assertAdmin();
        int window = days == null || days <= 0 ? DEFAULT_DAYS : Math.min(days, 90);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        OffsetDateTime start;
        if (pivot == null || pivot.isBlank()) {
            start = now.minusDays(window);
        } else {
            try {
                start = LocalDate.parse(pivot.strip()).atStartOfDay().atOffset(ZoneOffset.UTC);
            } catch (DateTimeParseException ex) {
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(new ErrorResponse(
                        "invalid_pivot", "Date pivot illisible : attendu AAAA-MM-JJ."));
            }
        }
        return ResponseEntity.ok(service.compare(currentUser.requireId(), start, window, now));
    }
}
