package fr.claudegateway.atelier.agent;

import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bootstrap idempotent des Managed Agents de l'Atelier (F-28 / Phase 2, ADR-013). Provisionne une
 * seule fois l'environnement puis l'agent, persiste leurs identifiants, et réutilise ensuite la
 * config (« créé une fois, réutilisé »).
 *
 * <p><b>Inerte par défaut</b> : ce service n'est jamais invoqué au démarrage (aucun
 * {@code ApplicationRunner}/{@code @PostConstruct}). Tant que {@code app.atelier.agent.enabled} est
 * faux et qu'aucune config n'existe, {@link #ensureBootstrapped()} ne fait aucun appel réseau.</p>
 */
@Service
public class AtelierAgentBootstrapService {

    private static final Logger log = LoggerFactory.getLogger(AtelierAgentBootstrapService.class);

    private final ManagedAgentProvider provider;
    private final AtelierAgentConfigRepository repository;
    private final AtelierAgentProperties properties;

    public AtelierAgentBootstrapService(ManagedAgentProvider provider,
            AtelierAgentConfigRepository repository, AtelierAgentProperties properties) {
        this.provider = provider;
        this.repository = repository;
        this.properties = properties;
    }

    /**
     * Garantit qu'une configuration Managed Agents existe, sans jamais la recréer :
     * <ul>
     *   <li>config déjà en base → renvoyée telle quelle (aucun appel fournisseur) ;</li>
     *   <li>sinon, si le flag {@code enabled} est actif → environnement puis agent créés chez le
     *       fournisseur, une seule ligne complète persistée, config renvoyée ;</li>
     *   <li>sinon (désactivé, pas de config) → {@link Optional#empty()} (aucun appel fournisseur).</li>
     * </ul>
     *
     * @return la configuration disponible, ou vide si désactivé et non encore provisionné
     */
    @Transactional
    public Optional<AtelierAgentConfig> ensureBootstrapped() {
        Optional<AtelierAgentConfig> existing = repository.findFirstByOrderByCreatedAtAsc();
        if (existing.isPresent()) {
            return existing;
        }
        if (!properties.enabled()) {
            // Flag off + aucune config : dormant, aucun appel réseau ni coût runtime.
            return Optional.empty();
        }

        log.info("Bootstrap Managed Agents (F-28) : provisionnement environnement + agent.");
        ManagedEnvironment environment = provider.createEnvironment(
                new EnvironmentSpec(properties.environmentName(), properties.allowPackageManagers()));
        ManagedAgentDefinition agent = provider.createAgent(
                new AgentSpec(properties.agentName(), properties.model(), systemPrompt()));

        AtelierAgentConfig config = AtelierAgentConfig.builder()
                .environmentId(environment.id())
                .agentId(agent.id())
                .agentVersion(agent.version())
                .build();
        return Optional.of(repository.save(config));
    }

    /** Prompt système de base de l'agent Atelier. Neutre, sans secret. */
    private static String systemPrompt() {
        return "Tu es l'agent de l'Atelier claude-gateway. Tu opères dans un bac à sable cloud "
                + "pour lire et modifier les fichiers du workspace de l'utilisateur.";
    }
}
