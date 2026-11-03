package fr.claudegateway.jarvis.budget;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/** Les plafonds de Jarvis (F-244 / SF-244-03) — clé = {@code user_id}, jamais lus pour un autre compte. */
public interface JarvisBudgetRepository extends JpaRepository<JarvisBudget, UUID> {
}
