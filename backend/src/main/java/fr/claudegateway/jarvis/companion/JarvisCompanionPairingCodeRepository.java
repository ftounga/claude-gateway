package fr.claudegateway.jarvis.companion;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les codes d'appairage des téléphones (F-232 / SF-232-03). */
public interface JarvisCompanionPairingCodeRepository extends JpaRepository<JarvisCompanionPairingCode, UUID> {

    Optional<JarvisCompanionPairingCode> findByCodeHash(String codeHash);
}
