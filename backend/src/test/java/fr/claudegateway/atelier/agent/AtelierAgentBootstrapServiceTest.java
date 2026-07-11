package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Vérifie l'idempotence et l'inertie de {@link AtelierAgentBootstrapService} : config existante →
 * aucun appel fournisseur ; activé sans config → env+agent créés et persistés une fois ; désactivé
 * sans config → vide sans appel réseau.
 */
@ExtendWith(MockitoExtension.class)
class AtelierAgentBootstrapServiceTest {

    @Mock
    private ManagedAgentProvider provider;

    @Mock
    private AtelierAgentConfigRepository repository;

    private AtelierAgentProperties enabledProps() {
        return new AtelierAgentProperties(true, "env-name", "agent-name", "claude-opus-4-8", true);
    }

    private AtelierAgentProperties disabledProps() {
        return new AtelierAgentProperties(false, "env-name", "agent-name", "claude-opus-4-8", true);
    }

    @Test
    void existingConfigReturnedWithoutProviderCall() {
        AtelierAgentConfig existing = AtelierAgentConfig.builder()
                .id(UUID.randomUUID())
                .environmentId("env_1").agentId("agent_1").agentVersion("v1")
                .createdAt(OffsetDateTime.now())
                .build();
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.of(existing));

        AtelierAgentBootstrapService service =
                new AtelierAgentBootstrapService(provider, repository, enabledProps());

        Optional<AtelierAgentConfig> result = service.ensureBootstrapped();

        assertThat(result).containsSame(existing);
        verifyNoInteractions(provider);
        verify(repository, never()).save(any());
    }

    @Test
    void enabledWithoutConfigCreatesEnvAndAgentAndPersistsOnce() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());
        when(provider.createEnvironment(any())).thenReturn(new ManagedEnvironment("env_new"));
        when(provider.createAgent(any())).thenReturn(new ManagedAgentDefinition("agent_new", "v3"));
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        AtelierAgentBootstrapService service =
                new AtelierAgentBootstrapService(provider, repository, enabledProps());

        Optional<AtelierAgentConfig> result = service.ensureBootstrapped();

        assertThat(result).isPresent();
        AtelierAgentConfig saved = result.get();
        assertThat(saved.getEnvironmentId()).isEqualTo("env_new");
        assertThat(saved.getAgentId()).isEqualTo("agent_new");
        assertThat(saved.getAgentVersion()).isEqualTo("v3");

        verify(provider, times(1)).createEnvironment(any());
        verify(provider, times(1)).createAgent(any());
        verify(repository, times(1)).save(any());

        // Vérifie que les specs portent bien les valeurs de configuration.
        ArgumentCaptor<EnvironmentSpec> envSpec = ArgumentCaptor.forClass(EnvironmentSpec.class);
        verify(provider).createEnvironment(envSpec.capture());
        assertThat(envSpec.getValue().name()).isEqualTo("env-name");
        assertThat(envSpec.getValue().allowPackageManagers()).isTrue();

        ArgumentCaptor<AgentSpec> agentSpec = ArgumentCaptor.forClass(AgentSpec.class);
        verify(provider).createAgent(agentSpec.capture());
        assertThat(agentSpec.getValue().name()).isEqualTo("agent-name");
        assertThat(agentSpec.getValue().model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void secondCallIsIdempotentAndDoesNotCallProviderAgain() {
        AtelierAgentConfig persisted = AtelierAgentConfig.builder()
                .id(UUID.randomUUID())
                .environmentId("env_new").agentId("agent_new").agentVersion("v3")
                .createdAt(OffsetDateTime.now())
                .build();
        // 1er appel : pas de config → création ; 2e appel : config présente → réutilisation.
        when(repository.findFirstByOrderByCreatedAtAsc())
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(persisted));
        when(provider.createEnvironment(any())).thenReturn(new ManagedEnvironment("env_new"));
        when(provider.createAgent(any())).thenReturn(new ManagedAgentDefinition("agent_new", "v3"));
        when(repository.save(any())).thenReturn(persisted);

        AtelierAgentBootstrapService service =
                new AtelierAgentBootstrapService(provider, repository, enabledProps());

        service.ensureBootstrapped();
        Optional<AtelierAgentConfig> second = service.ensureBootstrapped();

        assertThat(second).containsSame(persisted);
        // Le fournisseur n'est sollicité qu'une seule fois au total.
        verify(provider, times(1)).createEnvironment(any());
        verify(provider, times(1)).createAgent(any());
        verify(repository, times(1)).save(any());
    }

    @Test
    void disabledWithoutConfigReturnsEmptyWithoutProviderCall() {
        when(repository.findFirstByOrderByCreatedAtAsc()).thenReturn(Optional.empty());

        AtelierAgentBootstrapService service =
                new AtelierAgentBootstrapService(provider, repository, disabledProps());

        Optional<AtelierAgentConfig> result = service.ensureBootstrapped();

        assertThat(result).isEmpty();
        verifyNoInteractions(provider);
        verify(repository, never()).save(any());
    }
}
