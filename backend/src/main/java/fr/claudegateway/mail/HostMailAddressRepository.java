package fr.claudegateway.mail;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Adresses de réception (F-110 / SF-110-01). Toute méthode porte {@code user_id} et {@code host_id}. */
public interface HostMailAddressRepository extends JpaRepository<HostMailAddress, UUID> {

    Optional<HostMailAddress> findByUserIdAndHostId(UUID userId, UUID hostId);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from HostMailAddress a where a.userId = :userId and a.hostId = :hostId")
    int deleteScope(@Param("userId") UUID userId, @Param("hostId") UUID hostId);
}
