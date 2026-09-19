package fr.claudegateway.teams.meeting;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

/**
 * Accès aux artefacts réunion (F-128 / SF-128-01).
 *
 * <p>Toutes les recherches sont <b>filtrées par {@code user_id} ET {@code host_id}</b> : aucun finder
 * par {@code id} seul n'est exposé, pour qu'aucun appelant ne puisse contourner l'isolation.</p>
 */
public interface MeetingRepository extends JpaRepository<Meeting, UUID> {

    List<Meeting> findByUserIdAndHostIdOrderByStartedAtDesc(UUID userId, UUID hostId);

    Optional<Meeting> findByIdAndUserIdAndHostId(UUID id, UUID userId, UUID hostId);

    /** Les réunions dans un état de transcription donné — sert au worker STT (F-128 / SF-128-04). */
    List<Meeting> findByTranscriptStatus(TranscriptStatus transcriptStatus);

    /**
     * Les réunions <b>encore porteuses de médias</b> et <b>non déjà purgées</b> — les seules candidates à
     * la purge de rétention (F-128 / SF-128-07). Une réunion purgée ({@code media_purged_at} renseigné,
     * pointeurs vidés) sort de ce jeu : la purge est ainsi idempotente. Bornée par {@link Pageable} pour
     * que chaque cycle du worker reste borné.
     */
    @Query("select m from Meeting m where m.mediaPurgedAt is null "
            + "and (m.audioKey is not null or m.imageCount > 0)")
    List<Meeting> findMediaBearing(Pageable pageable);
}
