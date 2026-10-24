package fr.claudegateway.atelier.proof;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les captures de preuve (F-212 / SF-212-03) — toujours sous {@code user_id}. */
public interface ProofCaptureRepository extends JpaRepository<ProofCapture, UUID> {

    /** Une capture de CE compte et de CE terminal, ou vide (inconnue et étrangère indiscernables). */
    Optional<ProofCapture> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);
}
