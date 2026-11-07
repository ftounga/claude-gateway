package fr.claudegateway.jarvis.mail;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * F-249 / SF-249-03 — les sujets auxquels des courriers déposés sont rattachés : le courrier ne dépend pas du dépôt, il
 * demande seulement des noms à montrer. Chaque appel porte le {@code user_id} du compte.
 */
public interface DepositedMailLabels {

    Map<UUID, List<String>> subjectsOf(UUID userId, Collection<UUID> itemIds);
}
