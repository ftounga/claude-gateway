package fr.claudegateway.jarvis;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Les journées de Jarvis (F-231 / SF-231-01) — toujours sous {@code user_id}. */
public interface JarvisUsageDayRepository extends JpaRepository<JarvisUsageDay, UUID> {

    Optional<JarvisUsageDay> findByUserIdAndDay(UUID userId, LocalDate day);

    List<JarvisUsageDay> findByUserIdAndDayGreaterThanEqualOrderByDayAsc(UUID userId, LocalDate from);

    /** Le coût de la voix du compte depuis {@code from} (inclus), en dollars ; 0 sans ligne. */
    @Query("select coalesce(sum(d.speechCostUsd), 0) from JarvisUsageDay d where d.userId = :userId and d.day >= :from")
    BigDecimal sumSpeechCostUsdSince(@Param("userId") UUID userId, @Param("from") LocalDate from);
}
