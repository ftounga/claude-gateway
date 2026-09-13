package fr.claudegateway.billing.seat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.EntitlementSpace;

/**
 * Compte les <b>mois-clients</b> d'un utilisateur et en déduit la part de quota que les clients
 * supplémentaires apportent (F-65 / SF-65-01), <b>par espace</b> depuis F-107 / SF-107-05.
 *
 * <p><b>La règle, en une phrase</b> : l'abonnement couvre un client par espace ; au-delà, chaque client
 * facturable ajoute un supplément — dans la <b>Forge</b>, il apporte sa part de jetons, proratisée sur ce qui
 * reste du mois ; dans la <b>Vigie</b>, aucun jeton de conversation (sa réserve de synchro est par client).
 * Aucun montant n'est écrit ici : tout vient de {@link SeatProperties}.</p>
 *
 * <p><b>Les jetons suivent la facturation</b> (F-107 / SF-107-05) : tant que le price du supplément Forge
 * n'est pas branché, il n'apporte rien — la grille décidée est affichée, le quota est inchangé.</p>
 *
 * <p><b>Ce que « compté » veut dire</b> : les clients facturables aujourd'hui dans l'espace, <b>plus</b> ceux
 * qui ont une ligne de mois-client sur la période dans cet espace — facturables plus tôt dans le mois, puis
 * clôturés ou retirés. Un mois engagé est dû jusqu'au bout, dans les deux sens.</p>
 *
 * <p>Isolation : toutes les lectures partent du {@code userId} du contexte de sécurité.</p>
 */
@Service
public class SeatQuotaService {

    private final SeatSource seatSource;
    private final HostSeatMonthRepository repository;
    private final SeatProperties properties;
    private final Clock clock;

    public SeatQuotaService(
            SeatSource seatSource,
            HostSeatMonthRepository repository,
            SeatProperties properties,
            Clock clock) {
        this.seatSource = seatSource;
        this.repository = repository;
        this.properties = properties;
        this.clock = clock;
    }

    /**
     * Jetons apportés au quota de la période courante par les clients supplémentaires de la <b>Forge</b>.
     *
     * <p>Chemin chaud du pré-vol de quota : quand le mécanisme n'apporte aucun jeton — aucune part configurée,
     * ou supplément non facturé — il rend {@code 0} <b>sans aucune lecture en base</b>.</p>
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return jetons apportés, jamais négatif
     */
    @Transactional(readOnly = true)
    public long grantedTokens(UUID userId) {
        if (isInert()) {
            return 0L;
        }
        return describe(userId, EntitlementSpace.FORGE, true).grantedTokens();
    }

    /**
     * État des mois-clients de la Forge (comportement d'avant F-107 / SF-107-05).
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'état, jamais {@code null}
     */
    @Transactional(readOnly = true)
    public SeatUsage describe(UUID userId) {
        return describe(userId, EntitlementSpace.FORGE, true);
    }

    /**
     * État complet des mois-clients de la période courante <b>dans un espace</b>.
     *
     * @param userId      utilisateur authentifié (contexte de sécurité)
     * @param space       espace compté
     * @param tokensApply faux si le compte ne reçoit aucun jeton plateforme (BYOK) : les montants restent
     *                    affichés, aucune part n'est comptée
     * @return l'état, jamais {@code null}
     */
    @Transactional(readOnly = true)
    public SeatUsage describe(UUID userId, EntitlementSpace space, boolean tokensApply) {
        LocalDate periodStart = currentPeriodStart();
        List<Seat> counted = countedSeats(userId, space, periodStart);
        boolean forge = space == EntitlementSpace.FORGE;
        boolean billed = forge ? properties.isBilled() : properties.vigie().isBilled();
        boolean grantsTokens = forge && tokensApply;

        int included = forge ? properties.includedSeats() : properties.vigie().includedSeats();
        List<SeatUsage.Seat> detail = new ArrayList<>(counted.size());
        long granted = 0L;
        int rank = 0;
        for (int index = 0; index < counted.size(); index++) {
            Seat seat = counted.get(index);
            boolean coveredByPlan = index < included;
            long tokens = 0L;
            String price = "";
            if (!coveredByPlan) {
                rank++;
                price = forge ? properties.displayPriceForExtraSeat(rank) : properties.vigie().displayPrice();
                if (grantsTokens && billed) {
                    tokens = proratedTokens(rank, seat.billableFrom(), periodStart);
                    granted += tokens;
                }
            }
            detail.add(new SeatUsage.Seat(
                    seat.hostId(),
                    seat.name(),
                    seat.billableFrom(),
                    coveredByPlan,
                    coveredByPlan ? 0 : rank,
                    tokens,
                    seat.closed(),
                    price));
        }

        return new SeatUsage(
                included,
                counted.size(),
                Math.max(0, counted.size() - included),
                granted,
                billed,
                forge ? properties.firstExtraSeatDisplayPrice() : properties.vigie().displayPrice(),
                periodStart,
                periodStart.plusMonths(1),
                List.copyOf(detail),
                space,
                grantsTokens);
    }

    /**
     * Clients comptés dans l'espace pour la période, <b>du plus ancien facturable au plus récent</b> : le ou
     * les clients couverts par l'abonnement sont les plus anciens, les suppléments les plus récents.
     */
    private List<Seat> countedSeats(UUID userId, EntitlementSpace space, LocalDate periodStart) {
        Map<UUID, HostSeatMonth> months = new LinkedHashMap<>();
        repository.findByUserIdAndPeriodStart(userId, periodStart).stream()
                .filter(month -> spaceOf(month) == space)
                .forEach(month -> months.put(month.getHostId(), month));

        Map<UUID, Seat> seats = new LinkedHashMap<>();
        List<SeatSource.BillableSeat> billable = space == EntitlementSpace.FORGE
                ? seatSource.billableSeats(userId)
                : seatSource.billableSeats(userId, space);
        for (SeatSource.BillableSeat seat : billable) {
            HostSeatMonth month = months.get(seat.hostId());
            LocalDate from = month != null
                    ? month.getBillableFrom()
                    : startOfBillability(seat.createdAt(), periodStart);
            seats.put(seat.hostId(), new Seat(seat.hostId(), seat.name(), from, false, seat.createdAt()));
        }
        // Les clients clôturés ou retirés DEPUIS le début du mois : leur ligne existe, le mois est engagé.
        months.forEach((hostId, month) -> seats.computeIfAbsent(hostId, id -> new Seat(
                id, seatSource.seatName(userId, id), month.getBillableFrom(), true, month.getCreatedAt())));

        return seats.values().stream()
                .sorted(Comparator.comparing(Seat::billableFrom)
                        .thenComparing(Seat::since, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(seat -> seat.hostId().toString()))
                .toList();
    }

    /** Une ligne écrite avant F-107 / SF-107-05 relève de la Forge. */
    private static EntitlementSpace spaceOf(HostSeatMonth month) {
        return month.getSpace() == null ? EntitlementSpace.FORGE : month.getSpace();
    }

    private LocalDate startOfBillability(OffsetDateTime createdAt, LocalDate periodStart) {
        if (createdAt == null) {
            return periodStart;
        }
        LocalDate createdOn = createdAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        return createdOn.isAfter(periodStart) ? createdOn : periodStart;
    }

    /**
     * Jetons apportés par le supplément de rang {@code rank}, proratisés sur les jours du mois qui restaient
     * quand il est devenu facturable. Troncature vers le bas, jamais d'arrondi vers le haut.
     */
    private long proratedTokens(int rank, LocalDate billableFrom, LocalDate periodStart) {
        long tokens = properties.tokensForExtraSeat(rank);
        if (tokens <= 0 || properties.proration() == SeatProration.NONE) {
            return Math.max(0L, tokens);
        }
        int daysInMonth = periodStart.lengthOfMonth();
        int firstDay = billableFrom.isAfter(periodStart) ? billableFrom.getDayOfMonth() : 1;
        long remainingDays = Math.max(0, daysInMonth - firstDay + 1);
        return tokens * remainingDays / daysInMonth;
    }

    /**
     * Vrai si le mécanisme n'apporte de jetons à personne : aucune part configurée, ou supplément Forge non
     * facturé (F-107 / SF-107-05 : les jetons suivent la facturation).
     */
    private boolean isInert() {
        return (properties.tokensPerExtraSeat() == 0 && properties.quotaTiers().isEmpty())
                || !properties.isBilled();
    }

    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }

    private record Seat(
            UUID hostId, String name, LocalDate billableFrom, boolean closed, OffsetDateTime since) {
    }
}
