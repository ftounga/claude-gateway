package fr.claudegateway.atelier.autonomy;

import java.util.List;

import fr.claudegateway.atelier.journey.ChantierPreflight;

/**
 * Le départ (ou l'arrêt) en autonome est refusé (F-219 / SF-219-02) : {@code 409} avec un code stable, une
 * phrase lisible et, pour un chantier pas prêt, les raisons du préflight.
 */
public class AutonomyConflictException extends RuntimeException {

    private final String code;
    private final transient List<ChantierPreflight.Reason> reasons;

    public AutonomyConflictException(String code, String message) {
        this(code, message, List.of());
    }

    public AutonomyConflictException(String code, String message, List<ChantierPreflight.Reason> reasons) {
        super(message);
        this.code = code;
        this.reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    public String code() {
        return code;
    }

    public List<ChantierPreflight.Reason> reasons() {
        return reasons;
    }
}
