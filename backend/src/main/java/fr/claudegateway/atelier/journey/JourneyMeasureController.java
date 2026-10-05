package fr.claudegateway.atelier.journey;

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
 * <b>La mesure du parcours du sujet</b> (F-176 / SF-176-06) — <b>administrateur uniquement</b>.
 *
 * <p>Même patron que la mesure de la carte (F-174) : garde {@link AdminService#assertAdmin()} (403
 * sinon) ; l'isolation {@code user_id} s'applique en plus — l'administrateur mesure SES terminaux.</p>
 */
@RestController
@RequestMapping("/admin/journeys")
public class JourneyMeasureController {

    static final int DEFAULT_DAYS = 14;

    private final JourneyMeasureService service;
    private final AdminService adminService;
    private final CurrentUser currentUser;

    public JourneyMeasureController(JourneyMeasureService service, AdminService adminService,
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
