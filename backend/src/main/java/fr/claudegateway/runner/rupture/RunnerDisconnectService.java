package fr.claudegateway.runner.rupture;

import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Ce que les ruptures disent</b> (F-161 / SF-161-03).
 *
 * <p><b>Ce service ne corrige rien et ne conseille rien.</b> Il compte. Le cadrage F-161 §6 refuse
 * de deviner la cause des déconnexions ; en tirer un conseil reviendrait à la deviner quand même,
 * avec l'autorité d'un écran en plus.</p>
 */
@Service
public class RunnerDisconnectService {

    /**
     * Les ruptures <b>subies</b>. Un arrêt propre est sain ; un canal remplacé signale un poste qui
     * <b>revient</b>, pas un poste qui part. Les compter avec les autres gonflerait le total de
     * bruit et masquerait la seule question qui compte : combien de fois le poste a-t-il disparu
     * sans le vouloir ?
     */
    private static final java.util.Set<RunnerDisconnectCause> SUBIES = java.util.EnumSet.of(
            RunnerDisconnectCause.INACTIVITE,
            RunnerDisconnectCause.SOCKET_FERMEE,
            RunnerDisconnectCause.SOCKET_MUETTE);

    private final RunnerDisconnectRepository repository;

    public RunnerDisconnectService(RunnerDisconnectRepository repository) {
        this.repository = repository;
    }

    /** Le rapport du compte courant sur une période. Isolation : lecture filtrée {@code user_id}. */
    @Transactional(readOnly = true)
    public RunnerDisconnectReport report(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        List<RunnerDisconnect> ruptures =
                repository.findByUserIdAndCreatedAtBetweenOrderByCreatedAtDesc(userId, from, to);
        return new RunnerDisconnectReport(from, to,
                ruptures.size(),
                (int) ruptures.stream().filter(r -> SUBIES.contains(r.getCause())).count(),
                (int) ruptures.stream().filter(r -> r.getCallsInFlight() > 0).count(),
                parCause(ruptures), parPoste(ruptures), parHeure(ruptures));
    }

    private static List<RunnerDisconnectReport.CauseLine> parCause(List<RunnerDisconnect> ruptures) {
        Map<RunnerDisconnectCause, Integer> counts = new EnumMap<>(RunnerDisconnectCause.class);
        for (RunnerDisconnect rupture : ruptures) {
            counts.merge(rupture.getCause(), 1, Integer::sum);
        }
        // Toutes les causes sont rendues, y compris à zéro : « aucune socket muette » est une
        // information, et une ligne absente se lirait comme une mesure manquante.
        List<RunnerDisconnectReport.CauseLine> lines = new ArrayList<>();
        for (RunnerDisconnectCause cause : RunnerDisconnectCause.values()) {
            lines.add(new RunnerDisconnectReport.CauseLine(
                    cause.name(), counts.getOrDefault(cause, 0), SUBIES.contains(cause)));
        }
        return lines;
    }

    private static List<RunnerDisconnectReport.HostLine> parPoste(List<RunnerDisconnect> ruptures) {
        Map<UUID, List<RunnerDisconnect>> byHost = new LinkedHashMap<>();
        for (RunnerDisconnect rupture : ruptures) {
            byHost.computeIfAbsent(rupture.getHostId(), host -> new ArrayList<>()).add(rupture);
        }
        return byHost.entrySet().stream()
                .map(entry -> new RunnerDisconnectReport.HostLine(
                        entry.getKey().toString(),
                        entry.getValue().size(),
                        (int) entry.getValue().stream()
                                .filter(r -> SUBIES.contains(r.getCause())).count(),
                        medianSilence(entry.getValue())))
                .sorted(Comparator.comparingInt(RunnerDisconnectReport.HostLine::subies).reversed())
                .toList();
    }

    /**
     * La <b>médiane</b> du silence, pas la moyenne : une seule socket oubliée six heures tirerait
     * une moyenne au point de la rendre illisible, alors que la question posée est « combien de
     * temps, d'habitude, le poste se tait-il avant qu'on le déclare parti ? ».
     */
    private static Long medianSilence(List<RunnerDisconnect> ruptures) {
        List<Long> silences = ruptures.stream()
                .map(RunnerDisconnect::getSilentMs)
                .filter(java.util.Objects::nonNull)
                .sorted()
                .toList();
        if (silences.isEmpty()) {
            return null;
        }
        return silences.get(silences.size() / 2);
    }

    private static List<RunnerDisconnectReport.HourLine> parHeure(List<RunnerDisconnect> ruptures) {
        int[] hours = new int[24];
        for (RunnerDisconnect rupture : ruptures) {
            // Heure LOCALE du serveur : la question posée est « la veille du poste, le soir ? »,
            // et une heure UTC décalée la rendrait fausse une partie de l'année.
            hours[rupture.getCreatedAt().atZoneSameInstant(ZoneId.systemDefault()).getHour()]++;
        }
        List<RunnerDisconnectReport.HourLine> lines = new ArrayList<>(24);
        for (int hour = 0; hour < 24; hour++) {
            lines.add(new RunnerDisconnectReport.HourLine(hour, hours[hour]));
        }
        return lines;
    }
}
