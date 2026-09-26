package fr.claudegateway.runner.rupture;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * <b>Ce que le journal des ruptures apprend sur une période</b> (F-161 / SF-161-03).
 *
 * <p>Trois découpages, et pas un de plus — chacun répond à une question qu'on se pose vraiment :
 * <b>par cause</b> (quelle part est subie plutôt que saine ?), <b>par poste</b> (est-ce un poste ou
 * le produit ?), <b>par heure</b> (est-ce la veille de la machine ?).</p>
 *
 * @param subies       ruptures hors {@code ARRET_PROPRE} et {@code REMPLACE} — les seules anormales
 * @param avecAppels   ruptures survenues alors que des appels attendaient : celles qui ont coûté
 */
public record RunnerDisconnectReport(
        OffsetDateTime from,
        OffsetDateTime to,
        int total,
        int subies,
        int avecAppels,
        List<CauseLine> parCause,
        List<HostLine> parPoste,
        List<HourLine> parHeure) {

    /** Une cause et son poids. */
    public record CauseLine(String cause, int total, boolean subie) {
    }

    /**
     * Un poste et ses ruptures. {@code medianSilentMs} est la mesure qui pourra un jour trancher la
     * question du réglage : 90 s de tolérance sont-elles généreuses, ou justes ?
     */
    public record HostLine(String hostId, int total, int subies, Long medianSilentMs) {
    }

    /** L'heure de la journée (0-23) et le nombre de ruptures — la veille du poste se voit ici. */
    public record HourLine(int hour, int total) {
    }
}
