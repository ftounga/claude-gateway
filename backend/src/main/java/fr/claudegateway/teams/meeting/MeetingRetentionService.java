package fr.claudegateway.teams.meeting;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.RadarScope;
import fr.claudegateway.teams.meeting.dto.MeetingResponse;

/**
 * <b>Rétention & purge des médias de réunion</b> (F-128 / SF-128-07).
 *
 * <p>Les médias <b>lourds</b> d'une réunion (audio + images du deck) ne sont conservés que
 * {@code retention_days} (défaut 30). Au-delà, ce service les <b>purge</b> — objets du stockage effacés,
 * pointeurs {@code audio_key}/{@code audio_bytes}/{@code image_count} vidés, {@code media_purged_at}
 * horodaté — tout en <b>conservant</b> l'artefact réunion (titre, dates, sujet) <b>et le transcript</b>
 * (texte léger, exploitable ; l'audio d'où il vient est parti).</p>
 *
 * <p><b>Idempotence.</b> Une réunion déjà purgée ({@code media_purged_at} renseigné) n'est plus candidate
 * ({@link MeetingRepository#findMediaBearing}) : rejouer la purge ne refait rien.</p>
 *
 * <p><b>Best-effort borné.</b> Le cycle traite au plus {@link #PURGE_BATCH} réunions (le suivant continue),
 * et un échec sur une réunion (effacement stockage incomplet) est <b>isolé</b> : elle n'est pas marquée
 * purgée (retentée au cycle suivant), les autres réunions du cycle sont traitées.</p>
 *
 * <p><b>Isolation.</b> Le worker itère des lignes déjà porteuses de {@code user_id}+{@code host_id}, et la
 * clé de stockage effacée porte le triplet ; la purge <b>manuelle</b> résout la réunion par
 * {@code (id, user_id, host_id)} — une réunion d'un autre couple est introuvable (404).</p>
 */
@Service
public class MeetingRetentionService {

    private static final Logger log = LoggerFactory.getLogger(MeetingRetentionService.class);

    /** Nombre maximal de réunions purgées par cycle du worker : le cycle reste borné. */
    static final int PURGE_BATCH = 200;

    private final MeetingRepository repository;
    private final MeetingMediaService media;

    public MeetingRetentionService(MeetingRepository repository, MeetingMediaService media) {
        this.repository = repository;
        this.media = media;
    }

    /**
     * Purge les médias des réunions dont la rétention est <b>dépassée</b> à {@code now}. Best-effort :
     * un échec par réunion est journalisé (sans contenu) et n'interrompt pas le cycle.
     *
     * @return le nombre de réunions dont les médias ont été effectivement purgés
     */
    @Transactional
    public int purgeExpired(OffsetDateTime now) {
        List<Meeting> candidates = repository.findMediaBearing(PageRequest.of(0, PURGE_BATCH));
        int purged = 0;
        for (Meeting meeting : candidates) {
            if (!isExpired(meeting, now)) {
                continue;
            }
            try {
                purgeOne(meeting, now);
                purged++;
            } catch (RuntimeException ex) {
                // Effacement incomplet ou aléa : la réunion n'est pas marquée purgée (retentée au
                // prochain cycle), et le cycle continue avec les autres.
                log.warn("Purge des médias de la réunion {} non aboutie ({})", meeting.getId(),
                        ex.getClass().getSimpleName());
            }
        }
        if (purged > 0) {
            log.info("Rétention réunions : médias purgés pour {} réunion(s)", purged);
        }
        return purged;
    }

    /**
     * Purge <b>manuelle</b> des médias d'une réunion, résolue par le triplet (isolation). Purge
     * immédiate quelle que soit la rétention ; sûre si la réunion n'a déjà plus de média.
     *
     * @throws MeetingNotFoundException si la réunion est inconnue ou hors du couple {@code (user, host)}
     */
    @Transactional
    public MeetingResponse purgeMedia(RadarScope scope, UUID meetingId) {
        Meeting meeting = repository.findByIdAndUserIdAndHostId(meetingId, scope.userId(), scope.hostId())
                .orElseThrow(() -> new MeetingNotFoundException("Réunion introuvable : " + meetingId));
        purgeOne(meeting, OffsetDateTime.now());
        return MeetingResponse.of(meeting);
    }

    // ------------------------------------------------------------------ interne

    /** L'échéance de rétention d'une réunion : sa date de début plus {@code retention_days}. */
    private static boolean isExpired(Meeting meeting, OffsetDateTime now) {
        return meeting.getStartedAt() != null
                && meeting.getStartedAt().plusDays(meeting.getRetentionDays()).isBefore(now);
    }

    /**
     * Efface les médias lourds puis <b>seulement en cas de succès</b> vide les pointeurs et horodate la
     * purge. L'ordre importe : si l'effacement stockage échoue, l'artefact reste marqué « avec média ».
     */
    private void purgeOne(Meeting meeting, OffsetDateTime now) {
        media.deleteMedia(meeting.getUserId(), meeting.getHostId(), meeting.getId());
        meeting.setAudioKey(null);
        meeting.setAudioBytes(null);
        meeting.setImageCount(0);
        meeting.setMediaPurgedAt(now);
        repository.save(meeting);
    }
}
