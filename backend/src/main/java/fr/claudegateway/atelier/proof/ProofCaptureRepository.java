package fr.claudegateway.atelier.proof;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les captures de preuve (F-212 / SF-212-03) — toujours sous {@code user_id}. */
public interface ProofCaptureRepository extends JpaRepository<ProofCapture, UUID> {

    /** Une capture de CE compte et de CE terminal, ou vide (inconnue et étrangère indiscernables). */
    Optional<ProofCapture> findByIdAndUserIdAndWorkspaceId(UUID id, UUID userId, UUID workspaceId);

    /** F-237 / SF-237-01 — les captures d'un sujet, pour l'index des pièces (isolation {@code user_id}). */
    java.util.List<ProofCapture> findTop200ByUserIdAndWorkspaceIdOrderByCapturedAtAscPositionAsc(UUID userId,
            UUID workspaceId);


    /** F-237 / SF-237-02 — une capture du compte, quel que soit son sujet (vignette de l'index). */
    Optional<ProofCapture> findByIdAndUserId(UUID id, UUID userId);
}
