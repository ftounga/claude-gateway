package fr.claudegateway.governance;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.GovernanceMapGrowthService.Observation;

/**
 * L'<b>écriture</b> de ce qu'une lecture de carte a vu (F-93 / SF-93-02).
 *
 * <p><b>Pourquoi une classe à part.</b> Cette écriture doit vivre dans sa <b>propre transaction</b>
 * ({@link Propagation#REQUIRES_NEW}) : le relevé de carte est une lecture {@code readOnly}, et y
 * rattacher un {@code save} la ferait échouer. Or une annotation transactionnelle ne s'applique pas
 * à un appel qu'un objet se fait à lui-même — le proxy n'est pas traversé. Séparer l'écriture de la
 * décision est donc la seule façon d'obtenir réellement la transaction annoncée, et non de croire
 * l'avoir.</p>
 *
 * <p>Le second bénéfice est d'isoler la panne : {@link GovernanceMapGrowthService} absorbe ce qui
 * échoue ici, et le relevé reste rendu. <b>Un journal de croissance ne doit jamais empêcher de lire
 * une carte.</b></p>
 *
 * <p><b>Isolation.</b> Les lignes sont lues et écrites par {@code user_id} <b>et</b>
 * {@code host_id}.</p>
 */
@Service
public class GovernanceMapGrowthRecorder {

    private final GovernanceMapGrowthRepository growth;

    public GovernanceMapGrowthRecorder(GovernanceMapGrowthRepository growth) {
        this.growth = growth;
    }

    /**
     * Applique les observations et rend les lignes à jour.
     *
     * @return les lignes de ce poste concernées par ces observations, dans l'ordre reçu
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<GovernanceMapGrowth> record(UUID userId, GovernanceHostRef host,
            List<Observation> observations) {
        Map<String, GovernanceMapGrowth> known = new HashMap<>();
        for (GovernanceMapGrowth row : growth.findByUserIdAndHostId(userId, host.hostId())) {
            known.put(row.getPath(), row);
        }
        OffsetDateTime now = OffsetDateTime.now();
        List<GovernanceMapGrowth> rows = new ArrayList<>(observations.size());
        for (Observation observation : observations) {
            if (observation.path() == null
                    || observation.path().length() > GovernanceMapGrowth.MAX_PATH_LENGTH) {
                continue;
            }
            rows.add(growth.save(
                    merge(known.get(observation.path()), userId, host, observation, now)));
        }
        return rows;
    }

    /**
     * Applique une observation à la ligne existante, ou pose la <b>référence</b>.
     *
     * <p><b>Une diminution ne rend pas un gain négatif</b> : une carte qu'on élague n'a pas perdu un
     * savoir, elle a été rangée — et un chiffre négatif découragerait le ménage. Le compte est mis à
     * jour, le dernier gain reste ce qu'il était.</p>
     */
    private static GovernanceMapGrowth merge(GovernanceMapGrowth existing, UUID userId,
            GovernanceHostRef host, Observation observation, OffsetDateTime now) {
        if (existing == null) {
            // Première observation : une référence, et AUCUN gain. Une carte déjà pleine le premier
            // jour n'a rien gagné — elle était là, et la présenter comme un gain serait faux.
            return GovernanceMapGrowth.builder()
                    .userId(userId).hostId(host.hostId()).path(observation.path())
                    .facts(observation.facts()).observedAt(now)
                    .firstFacts(observation.facts()).firstSeenAt(now)
                    .build();
        }
        int gained = observation.facts() - existing.getFacts();
        if (gained > 0) {
            existing.setLastGain(gained);
            existing.setLastGainAt(now);
        }
        existing.setFacts(observation.facts());
        existing.setObservedAt(now);
        return existing;
    }
}
