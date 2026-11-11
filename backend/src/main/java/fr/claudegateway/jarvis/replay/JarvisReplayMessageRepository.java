package fr.claudegateway.jarvis.replay;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import fr.claudegateway.atelier.AtelierMessage;

/**
 * <b>Les messages du rejeu</b> (F-255 / SF-255-02) — lecture seule, toujours sous {@code user_id} : ce qui a été dit à
 * Jarvis et répondu ({@code via = JARVIS}) et les délégations ({@code LINK}) d'une fenêtre de temps.
 */
public interface JarvisReplayMessageRepository extends Repository<AtelierMessage, UUID> {

    @Query("select m from AtelierMessage m where m.userId = :userId and m.createdAt >= :from and m.createdAt < :to"
            + " and (m.via = 'JARVIS' or m.role = 'LINK') order by m.createdAt asc")
    List<AtelierMessage> findJarvisExchanges(@Param("userId") UUID userId, @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to);
}
