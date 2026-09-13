package fr.claudegateway.billing.seat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.billing.EntitlementSpace;

/**
 * Tient le registre des <b>mois-clients</b> (F-65 / SF-65-01, par espace depuis F-107 / SF-107-05) : la
 * mémoire de ce qui a été facturable pendant un mois, que l'état courant d'un poste ne peut pas porter.
 *
 * <p>Trois écritures, et trois seulement — à la <b>clôture</b> de mission, à la <b>réouverture</b>, et au
 * <b>retrait d'un espace</b> en cours de mois. Aucune à la création ni à l'activation (leur date dit déjà
 * quand le client est entré), aucune périodique : un mois sans ligne se lit « facturable depuis le premier
 * jour ou l'entrée ». <b>Aucun job mensuel ne peut donc manquer, puisqu'aucun n'existe.</b></p>
 *
 * <p><b>Une ligne écrite n'est jamais réécrite</b> : c'est la règle « un mois-client se paie une fois »,
 * désormais <b>par espace</b> — aucune séquence ouvrir/clôturer/rouvrir ou retirer/réactiver ne refacture ni
 * ne remet le compteur à zéro.</p>
 */
@Service
public class SeatLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SeatLedgerService.class);

    private final HostSeatMonthRepository repository;
    private final Clock clock;
    private final SeatSource seatSource;

    /** Registre sans connaissance des espaces : tout poste relève de la Forge (usage de test). */
    public SeatLedgerService(HostSeatMonthRepository repository, Clock clock) {
        this(repository, clock, null);
    }

    @Autowired
    public SeatLedgerService(HostSeatMonthRepository repository, Clock clock, SeatSource seatSource) {
        this.repository = repository;
        this.clock = clock;
        this.seatSource = seatSource;
    }

    /**
     * Note qu'un poste <b>clôturé</b> aujourd'hui a été facturable pendant la période courante, <b>dans
     * chacun de ses espaces</b> : la clôture de mission est un geste unique.
     *
     * @param userId        propriétaire (contexte de sécurité)
     * @param hostId        poste clôturé
     * @param hostCreatedAt création du poste : elle date le début de facturabilité dans la Forge si elle tombe
     *                      dans la période ; dans la Vigie, c'est l'activation qui la date
     */
    @Transactional
    public void noteClosure(UUID userId, UUID hostId, OffsetDateTime hostCreatedAt) {
        LocalDate periodStart = currentPeriodStart();
        for (EntitlementSpace space : spacesOf(userId, hostId)) {
            OffsetDateTime entered = space == EntitlementSpace.FORGE
                    ? hostCreatedAt
                    : seatSource.enteredSpaceAt(userId, hostId, space);
            remember(userId, hostId, space, periodStart, billableFrom(entered, periodStart));
        }
    }

    /**
     * Note qu'un poste <b>clôturé</b> redevient facturable aujourd'hui, dans chacun de ses espaces. Si le poste
     * a déjà une ligne sur la période dans un espace, rien n'y est écrit : le mois-client est déjà compté.
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param hostId poste rouvert
     */
    @Transactional
    public void noteReopening(UUID userId, UUID hostId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        for (EntitlementSpace space : spacesOf(userId, hostId)) {
            remember(userId, hostId, space, today.withDayOfMonth(1), today);
        }
    }

    /**
     * Note qu'un poste <b>quitte un espace</b> en cours de mois (F-107 / SF-107-05) : le mois engagé dans cet
     * espace reste dû jusqu'au bout, et ne l'est plus le mois suivant — le pendant, par espace, de la clôture.
     *
     * @param userId    propriétaire (contexte de sécurité)
     * @param hostId    poste retiré
     * @param space     espace quitté
     * @param enteredAt entrée du poste dans cet espace (création pour la Forge, activation pour la Vigie)
     */
    @Transactional
    public void noteSpaceRemoval(UUID userId, UUID hostId, EntitlementSpace space, OffsetDateTime enteredAt) {
        LocalDate periodStart = currentPeriodStart();
        remember(userId, hostId, space, periodStart, billableFrom(enteredAt, periodStart));
    }

    /**
     * Efface les mois-clients d'un poste <b>supprimé</b>, dans tous les espaces.
     *
     * @param hostId poste supprimé
     */
    @Transactional
    public void forgetHost(UUID hostId) {
        repository.deleteByHostId(hostId);
    }

    private Set<EntitlementSpace> spacesOf(UUID userId, UUID hostId) {
        return seatSource == null ? EnumSet.of(EntitlementSpace.FORGE) : seatSource.spacesOf(userId, hostId);
    }

    private static LocalDate billableFrom(OffsetDateTime entered, LocalDate periodStart) {
        LocalDate enteredOn = entered == null
                ? periodStart
                : entered.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        return enteredOn.isAfter(periodStart) ? enteredOn : periodStart;
    }

    private void remember(UUID userId, UUID hostId, EntitlementSpace space, LocalDate periodStart,
            LocalDate billableFrom) {
        if (repository.findByHostIdAndSpaceAndPeriodStart(hostId, space, periodStart).isPresent()) {
            // Déjà compté sur cette période dans cet espace : la ligne fait foi, on ne la rejoue pas.
            return;
        }
        try {
            repository.save(HostSeatMonth.builder()
                    .userId(userId)
                    .hostId(hostId)
                    .space(space)
                    .periodStart(periodStart)
                    .billableFrom(billableFrom)
                    .build());
        } catch (DataIntegrityViolationException concurrentWrite) {
            log.debug("Mois-client déjà enregistré pour le poste {} ({}) sur la période {}",
                    hostId, space, periodStart);
        }
    }

    /** Premier jour du mois calendaire courant (UTC) — même définition de période que F-10. */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
