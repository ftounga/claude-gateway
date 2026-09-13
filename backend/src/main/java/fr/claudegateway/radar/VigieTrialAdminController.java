package fr.claudegateway.radar;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Relevé du coût réel des essais Vigie (F-107 / SF-107-04), <b>réservé à l'ADMIN</b> : la garde est appliquée
 * dans {@link VigieTrialMeasureService} par {@code AdminService.assertAdmin()}.
 */
@RestController
@RequestMapping("/admin/vigie-trials")
public class VigieTrialAdminController {

    private final VigieTrialMeasureService service;

    public VigieTrialAdminController(VigieTrialMeasureService service) {
        this.service = service;
    }

    /** Les essais Vigie consommés et le coût de chacune de leurs synchros. */
    @GetMapping
    public List<VigieTrialMeasureService.TrialMeasure> trials() {
        return service.trials();
    }
}
