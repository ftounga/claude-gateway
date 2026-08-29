package fr.claudegateway.atelier.agent;

import java.net.URI;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.git.GitProperties;

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
    private final GitProperties gitProperties;

    public AtelierAgentBootstrapService(ManagedAgentProvider provider,
            AtelierAgentConfigRepository repository, AtelierAgentProperties properties,
            GitProperties gitProperties) {
        this.provider = provider;
        this.repository = repository;
        this.properties = properties;
        this.gitProperties = gitProperties;
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
                new EnvironmentSpec(properties.environmentName(), properties.allowPackageManagers(),
                        mcpAllowedHosts()));
        ManagedAgentDefinition agent = provider.createAgent(
                new AgentSpec(properties.agentName(), properties.model(), AgentSystemPrompt.platform()));

        AtelierAgentConfig config = AtelierAgentConfig.builder()
                .environmentId(environment.id())
                .agentId(agent.id())
                .agentVersion(agent.version())
                .build();
        return Optional.of(repository.save(config));
    }

    /**
     * Hôtes que la politique réseau de l'environnement doit laisser joindre : celui du serveur MCP
     * GitHub, <b>dérivé</b> de {@code app.git.mcp-server-url} (F-31 / SF-31-07).
     *
     * <p>Dérivé, et non configuré à part : une seconde propriété devrait rester cohérente avec l'URL
     * MCP à la main, et c'est exactement cette duplication qui a produit le défaut — SF-31-05 a
     * déclaré le serveur côté session sans que la politique de l'environnement suive, si bien que
     * toute session Git était refusée en {@code 400}.</p>
     *
     * <p>Une URL illisible ne fait <b>pas</b> échouer le bootstrap : un environnement non provisionné
     * serait pire qu'un environnement sans MCP.</p>
     *
     * @return l'hôte du serveur MCP, ou une liste vide si l'URL n'en porte pas
     */
    private List<String> mcpAllowedHosts() {
        String url = gitProperties.mcpServerUrl();
        if (url == null || url.isBlank()) {
            return List.of();
        }
        try {
            String host = URI.create(url).getHost();
            return host == null || host.isBlank() ? List.of() : List.of(host);
        } catch (IllegalArgumentException malformed) {
            log.warn("URL du serveur MCP illisible : aucun hôte autorisé sur l'environnement.");
            return List.of();
        }
    }
}
