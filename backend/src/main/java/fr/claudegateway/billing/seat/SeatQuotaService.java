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

/**
 * Compte les <b>mois-postes</b> d'un utilisateur et en déduit la part de quota que les postes
 * supplémentaires apportent (F-65 / SF-65-01).
 *
 * <p><b>La règle, en une phrase</b> : l'abonnement couvre un poste ; au-delà, chaque poste
 * facturable apporte sa part de jetons, proratisée sur ce qui reste du mois quand il arrive en
 * cours de route. Aucun montant n'est écrit ici : tout vient de {@link SeatProperties}, dont les
 * défauts rendent le mécanisme <b>inerte</b>.</p>
 *
 * <p><b>Ce que « compté » veut dire</b> : l'ensemble des postes facturables aujourd'hui, <b>plus</b>
 * ceux qui ont une ligne de mois-poste sur la période — c'est-à-dire ceux qui ont été facturables
 * plus tôt dans le mois et qui sont clôturés depuis. Un mois engagé est dû jusqu'au bout, dans les
 * deux sens : on ne le rembourse pas, et on ne le refacture pas non plus si le poste rouvre.</p>
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
     * Jetons apportés à la période courante par les postes supplémentaires de l'utilisateur.
     *
     * <p>Appelé sur le chemin chaud du pré-vol de quota : quand le mécanisme n'apporte aucun jeton —
     * le défaut, tant que le PO n'a rien configuré — il rend {@code 0} <b>sans aucune lecture en
     * base</b>. Le comportement d'avant F-65 est alors préservé jusque dans son coût.</p>
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return jetons apportés, jamais négatif
     */
    @Transactional(readOnly = true)
    public long grantedTokens(UUID userId) {
        if (isInert()) {
            return 0L;
        }
        return describe(userId).grantedTokens();
    }

    /**
     * État complet des mois-postes de la période courante : postes comptés, poste couvert par
     * l'abonnement, jetons apportés, et si le supplément est réellement facturé.
     *
     * @param userId utilisateur authentifié (contexte de sécurité)
     * @return l'état, jamais {@code null}
     */
    @Transactional(readOnly = true)
    public SeatUsage describe(UUID userId) {
        LocalDate periodStart = currentPeriodStart();
        List<Seat> counted = countedSeats(userId, periodStart);

        int included = properties.includedSeats();
        List<SeatUsage.Seat> detail = new ArrayList<>(counted.size());
        long granted = 0L;
        int rank = 0;
        for (int index = 0; index < counted.size(); index++) {
            Seat seat = counted.get(index);
            boolean coveredByPlan = index < included;
            long tokens = 0L;
            if (!coveredByPlan) {
                rank++;
                tokens = proratedTokens(rank, seat.billableFrom(), periodStart);
                granted += tokens;
            }
            detail.add(new SeatUsage.Seat(
                    seat.hostId(),
                    seat.name(),
                    seat.billableFrom(),
                    coveredByPlan,
                    coveredByPlan ? 0 : rank,
                    tokens,
                    seat.closed()));
        }

        return new SeatUsage(
                included,
                counted.size(),
                Math.max(0, counted.size() - included),
                granted,
                properties.isBilled(),
                properties.displayPrice(),
                periodStart,
                periodStart.plusMonths(1),
                List.copyOf(detail));
    }

    /**
     * Postes comptés pour la période, <b>du plus ancien facturable au plus récent</b>.
     *
     * <p>L'ordre porte une décision : le ou les postes couverts par l'abonnement sont les
     * <b>plus anciens</b>, les suppléments sont donc les plus récents. C'est ce que l'intuition
     * attend (« le premier poste, c'est celui que j'avais »), et c'est aussi ce que proratise un
     * fournisseur de paiement quand la quantité augmente : le poste marginal est le dernier
     * arrivé.</p>
     */
    private List<Seat> countedSeats(UUID userId, LocalDate periodStart) {
        Map<UUID, HostSeatMonth> months = new LinkedHashMap<>();
        repository.findByUserIdAndPeriodStart(userId, periodStart)
                .forEach(month -> months.put(month.getHostId(), month));

        Map<UUID, Seat> seats = new LinkedHashMap<>();
        for (SeatSource.BillableSeat billable : seatSource.billableSeats(userId)) {
            HostSeatMonth month = months.get(billable.hostId());
            LocalDate from = month != null
                    ? month.getBillableFrom()
                    : startOfBillability(billable.createdAt(), periodStart);
            seats.put(billable.hostId(),
                    new Seat(billable.hostId(), billable.name(), from, false, billable.createdAt()));
        }
        // Les postes clôturés DEPUIS le début du mois : leur ligne existe, le mois est engagé.
        months.forEach((hostId, month) -> seats.computeIfAbsent(hostId, id -> new Seat(
                id, seatSource.seatName(userId, id), month.getBillableFrom(), true,
                month.getCreatedAt())));

        // À date de facturabilité égale — le cas courant, tous les postes courant depuis le 1er —
        // c'est l'ancienneté qui départage : « le premier poste, c'est celui que j'avais ».
        return seats.values().stream()
                .sorted(Comparator.comparing(Seat::billableFrom)
                        .thenComparing(Seat::since, Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(seat -> seat.hostId().toString()))
                .toList();
    }

    /**
     * Début de facturabilité d'un poste sans ligne sur la période : le premier jour du mois, ou sa
     * création si elle est plus tardive. Un poste sans ligne n'a pas été interrompu — toute
     * interruption en aurait écrit une.
     */
    private LocalDate startOfBillability(OffsetDateTime createdAt, LocalDate periodStart) {
        if (createdAt == null) {
            return periodStart;
        }
        LocalDate createdOn = createdAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        return createdOn.isAfter(periodStart) ? createdOn : periodStart;
    }

    /**
     * Jetons apportés par le supplément de rang {@code rank}, proratisés sur les jours du mois qui
     * restaient quand il est devenu facturable.
     *
     * <p>Troncature vers le bas, jamais d'arrondi vers le haut : un quota qu'on arrondit en faveur
     * du client est un quota qu'on lui a promis sans le vendre.</p>
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

    /** Vrai si aucune part de jetons n'est configurée : le mécanisme n'apporte rien à personne. */
    private boolean isInert() {
        return properties.tokensPerExtraSeat() == 0 && properties.quotaTiers().isEmpty();
    }

    /** Premier jour du mois calendaire courant (UTC) — même définition de période que F-10. */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }

    /**
     * Poste compté, avant d'être classé (couvert par le plan / supplément de rang n).
     *
     * @param since ancienneté servant à départager deux postes facturables du même jour : la
     *              création du poste, ou l'écriture de son mois-poste s'il est déjà clôturé
     */
    private record Seat(
            UUID hostId, String name, LocalDate billableFrom, boolean closed, OffsetDateTime since) {
    }
}
