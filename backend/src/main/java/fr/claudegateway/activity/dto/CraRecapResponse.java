package fr.claudegateway.activity.dto;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import fr.claudegateway.activity.cra.CraService.CraLine;
import fr.claudegateway.activity.cra.CraService.CraLineStatus;
import fr.claudegateway.activity.cra.CraService.CraOutcome;

/**
 * Le <b>récapitulatif</b> d'un message de CRA rendu à l'écran (F-124 / SF-124-03) : ce que la Gateway
 * a compris, ligne par ligne, et le compte de ce qui a été écrit / refusé / non reconnu.
 */
public record CraRecapResponse(List<CraLineResponse> lines, int written, int rejected, int unknown) {

    public static CraRecapResponse from(CraOutcome outcome) {
        List<CraLineResponse> lines = outcome.lines().stream().map(CraLineResponse::from).toList();
        int written = count(outcome, CraLineStatus.WRITTEN);
        int rejected = count(outcome, CraLineStatus.REJECTED);
        int unknown = count(outcome, CraLineStatus.UNKNOWN_HOST);
        return new CraRecapResponse(lines, written, rejected, unknown);
    }

    private static int count(CraOutcome outcome, CraLineStatus status) {
        return (int) outcome.lines().stream().filter(line -> line.status() == status).count();
    }

    /** Une ligne du récap : le nom cité, le poste rapproché (si connu), les jours/mois, le statut. */
    public record CraLineResponse(String cited, UUID hostId, String hostName, BigDecimal days,
            String month, String period, String status, String message) {

        static CraLineResponse from(CraLine line) {
            return new CraLineResponse(line.cited(), line.hostId(), line.hostName(), line.days(),
                    line.month(), line.period(), line.status().name(), line.message());
        }
    }
}
