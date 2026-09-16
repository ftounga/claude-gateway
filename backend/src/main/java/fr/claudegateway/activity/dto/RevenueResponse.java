package fr.claudegateway.activity.dto;

import java.util.List;
import java.util.UUID;

import fr.claudegateway.activity.ActivityConfigService;
import fr.claudegateway.activity.RevenueService.PosteRevenue;
import fr.claudegateway.activity.RevenueService.RevenueSummary;

/**
 * Le cumul de revenu rendu à l'écran (F-124 / SF-124-02) : le total tous clients (et sa part
 * supposée), le mois de départ et le mois courant, et le détail par poste.
 */
public record RevenueResponse(
        String startMonth,
        String currentMonth,
        long totalCents,
        long totalDeclaredCents,
        long totalSupposedCents,
        List<PosteRevenueResponse> postes) {

    public static RevenueResponse from(RevenueSummary summary) {
        return new RevenueResponse(
                ActivityConfigService.formatMonth(summary.startMonth()),
                ActivityConfigService.formatMonth(summary.currentMonth()),
                summary.totalCents(),
                summary.totalDeclaredCents(),
                summary.totalSupposedCents(),
                summary.postes().stream().map(PosteRevenueResponse::from).toList());
    }

    /** Le cumul d'un poste : TJM, cumul, et la répartition déclaré/supposé (centimes d'euro HT). */
    public record PosteRevenueResponse(UUID hostId, long tjmCents, long cumulCents,
            long declaredCents, long supposedCents) {

        static PosteRevenueResponse from(PosteRevenue poste) {
            return new PosteRevenueResponse(poste.hostId(), poste.tjmCents(), poste.cumulCents(),
                    poste.declaredCents(), poste.supposedCents());
        }
    }
}
