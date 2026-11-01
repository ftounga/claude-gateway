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

    /**
     * Le coût de la voix du compte depuis {@code from} (inclus), en dollars ; 0 sans ligne. F-233 / SF-233-04 :
     * lecture <b>et</b> écoute en flux.
     */
    @Query("select coalesce(sum(d.speechCostUsd + d.sttStreamCostUsd), 0) from JarvisUsageDay d"
            + " where d.userId = :userId and d.day >= :from")
    BigDecimal sumSpeechCostUsdSince(@Param("userId") UUID userId, @Param("from") LocalDate from);

    /** F-235 / SF-235-01 (D15) — le coût du cerveau rapide du compte depuis {@code from} (inclus), en dollars. */
    @Query("select coalesce(sum(d.fastBrainCostUsd), 0) from JarvisUsageDay d"
            + " where d.userId = :userId and d.day >= :from")
    BigDecimal sumFastBrainCostUsdSince(@Param("userId") UUID userId, @Param("from") LocalDate from);
}
