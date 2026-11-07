package fr.claudegateway.jarvis.clientmail;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/** Accès aux réglages (F-249) ; clé = {@code user_id}. */
@Repository
public interface ClientMailSettingsRepository extends JpaRepository<ClientMailSettings, UUID> {

    @Modifying
    @Transactional
    @Query("delete from ClientMailSettings s where s.userId = :userId")
    int purgeUser(@Param("userId") UUID userId);
}
