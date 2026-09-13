package fr.claudegateway.radar;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Le silence ne clôt jamais</b> (F-99 / SF-99-04, cadrage §6) : un sujet ouvert resté sans
 * activité au-delà du seuil (21 jours par défaut) passe <b>en sommeil</b>, et le résumé du matin
 * (F-102) demandera ce qu'il en est.
 *
 * <p><b>Isolation.</b> Le balayage cherche d'abord les <b>périmètres</b> {@code (user_id, host_id)}
 * qui ont des sujets silencieux, puis traite chacun avec des requêtes filtrées sur ce périmètre :
 * aucune écriture ne se fait hors de son poste.</p>
 *
 * <p><b>Plusieurs pods.</b> Idempotent : poser {@code DORMANT} deux fois ne change rien.</p>
 */
@Service
public class RadarDormancyService {

    /** États qu'un silence peut mettre en sommeil. */
    static final List<RadarSubjectState> OPEN_WORK = List.of(RadarSubjectState.NEW, RadarSubjectState.ADVANCING,
            RadarSubjectState.WAITING, RadarSubjectState.BLOCKED);

    static final int DEFAULT_DAYS = 21;

    private final RadarSubjectRepository subjects;
    private final int days;

    public RadarDormancyService(RadarSubjectRepository subjects,
            @Value("${app.radar.dormancy.days:" + DEFAULT_DAYS + "}") int days) {
        this.subjects = subjects;
        this.days = days >= 1 ? days : DEFAULT_DAYS;
    }

    /**
     * Met en sommeil les sujets silencieux.
     *
     * @return le nombre de sujets mis en sommeil
     */
    @Transactional
    public int sweep(OffsetDateTime now) {
        OffsetDateTime threshold = now.minusDays(days);
        int count = 0;
        for (Object[] scope : subjects.findScopesWithSilentSubjects(OPEN_WORK, threshold)) {
            count += sweep(new RadarScope((UUID) scope[0], (UUID) scope[1]), now);
        }
        return count;
    }

    /** Met en sommeil les sujets silencieux d'un seul poste. */
    @Transactional
    public int sweep(RadarScope scope, OffsetDateTime now) {
        OffsetDateTime threshold = now.minusDays(days);
        List<RadarSubject> silent = subjects
                .findByUserIdAndHostIdAndStateInAndMergedIntoIdIsNullAndLastActivityAtBefore(
                        scope.userId(), scope.hostId(), OPEN_WORK, threshold);
        for (RadarSubject subject : silent) {
            subject.setPreviousState(subject.getState());
            subject.setState(RadarSubjectState.DORMANT);
            subject.setDormantSince(now);
        }
        subjects.saveAll(silent);
        return silent.size();
    }
}
