package fr.claudegateway.billing.seat;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Tient le registre des <b>mois-postes</b> (F-65 / SF-65-01) : la mémoire de ce qui a été
 * facturable pendant un mois, que l'état courant d'un poste ne peut pas porter.
 *
 * <p>Deux écritures, et deux seulement — aucune à la création d'un poste (sa date de création le
 * date déjà), aucune périodique (un mois sans ligne se lit « facturable depuis le premier jour »,
 * ce qui est vrai par construction : toute interruption aurait écrit une ligne). <b>Aucun job
 * mensuel ne peut donc manquer, puisqu'aucun n'existe.</b></p>
 *
 * <p><b>Une ligne écrite n'est jamais réécrite.</b> C'est là que se joue la règle « un mois-poste se
 * paie une fois » : la réouverture retrouve la ligne posée par la clôture et la laisse intacte,
 * si bien qu'aucune séquence ouvrir/clôturer/rouvrir ne refacture ni ne remet le compteur à zéro.</p>
 */
@Service
public class SeatLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SeatLedgerService.class);

    private final HostSeatMonthRepository repository;
    private final Clock clock;

    public SeatLedgerService(HostSeatMonthRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Note qu'un poste <b>clôturé</b> aujourd'hui a été facturable pendant la période courante.
     *
     * <p>Sans cette ligne, clôturer effacerait le mois déjà engagé — et clôturer chaque 30 du mois
     * deviendrait une méthode. Le poste reste donc compté jusqu'au bout du mois, et ne l'est plus
     * le suivant.</p>
     *
     * @param userId        propriétaire (contexte de sécurité)
     * @param hostId        poste clôturé
     * @param hostCreatedAt création du poste : si elle tombe dans la période, c'est elle qui date le
     *                      début de facturabilité, sinon c'est le premier jour du mois
     */
    @Transactional
    public void noteClosure(UUID userId, UUID hostId, OffsetDateTime hostCreatedAt) {
        LocalDate periodStart = currentPeriodStart();
        LocalDate createdOn = hostCreatedAt == null
                ? periodStart
                : hostCreatedAt.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();
        remember(userId, hostId, periodStart, createdOn.isAfter(periodStart) ? createdOn : periodStart);
    }

    /**
     * Note qu'un poste <b>clôturé</b> redevient facturable aujourd'hui.
     *
     * <p>Si le poste a déjà une ligne sur la période, <b>rien n'est écrit</b> : le mois-poste est
     * déjà compté, et le recompter serait le piège à utilisateur que le cadrage refuse. Sinon, la
     * facturabilité part d'aujourd'hui — un poste rouvert le 20 n'a pas travaillé du 1er au 19.</p>
     *
     * @param userId propriétaire (contexte de sécurité)
     * @param hostId poste rouvert
     */
    @Transactional
    public void noteReopening(UUID userId, UUID hostId) {
        LocalDate today = LocalDate.now(clock.withZone(ZoneOffset.UTC));
        remember(userId, hostId, today.withDayOfMonth(1), today);
    }

    /**
     * Efface les mois-postes d'un poste <b>supprimé</b>. Supprimer un poste n'est pas le clôturer :
     * la clôture range et laisse le mois engagé, la suppression détruit la machine de la facturation
     * en même temps que le reste (jetons, appairage, rattachements).
     *
     * @param hostId poste supprimé
     */
    @Transactional
    public void forgetHost(UUID hostId) {
        repository.deleteByHostId(hostId);
    }

    private void remember(UUID userId, UUID hostId, LocalDate periodStart, LocalDate billableFrom) {
        if (repository.findByHostIdAndPeriodStart(hostId, periodStart).isPresent()) {
            // Déjà compté sur cette période : la ligne fait foi, on ne la rejoue pas.
            return;
        }
        try {
            repository.save(HostSeatMonth.builder()
                    .userId(userId)
                    .hostId(hostId)
                    .periodStart(periodStart)
                    .billableFrom(billableFrom)
                    .build());
        } catch (DataIntegrityViolationException concurrentWrite) {
            // Une écriture concurrente a posé la ligne en premier : elle dit la même chose.
            log.debug("Mois-poste déjà enregistré pour le poste {} sur la période {}",
                    hostId, periodStart);
        }
    }

    /** Premier jour du mois calendaire courant (UTC) — même définition de période que F-10. */
    private LocalDate currentPeriodStart() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC)).withDayOfMonth(1);
    }
}
