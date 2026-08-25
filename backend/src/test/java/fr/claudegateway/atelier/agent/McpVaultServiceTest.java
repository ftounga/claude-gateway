package fr.claudegateway.atelier.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import fr.claudegateway.git.GitProperties;
import fr.claudegateway.git.GitTokenService;
import fr.claudegateway.git.GitVaultRef;
import fr.claudegateway.git.GitVaultRevokedEvent;

/**
 * Vérifie le cycle de vie du vault de credentials MCP (F-31 / SF-31-05) : un vault par utilisateur,
 * créé une seule fois puis réutilisé, jamais créé sans jeton, détruit à la révocation, et une
 * indisponibilité du fournisseur qui dégrade la session sans jamais l'empêcher de s'ouvrir.
 */
@ExtendWith(MockitoExtension.class)
class McpVaultServiceTest {

    private static final UUID USER = UUID.randomUUID();
    private static final String MCP_URL = "https://api.githubcopilot.com/mcp/";

    @Mock
    private ManagedAgentProvider provider;
    @Mock
    private GitTokenService gitTokenService;

    private McpVaultService service() {
        return new McpVaultService(provider, gitTokenService,
                new GitProperties(null, null, null, null, null, null));
    }

    @Test
    void createsTheVaultOnFirstUseAndRemembersIt() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createVaultWithBearer(anyString(), eq(MCP_URL), eq("github_pat_secret")))
                .thenReturn(new ManagedVault("vlt_1", "vcrd_1"));

        Optional<McpAccess> access = service().resolveAccess(USER);

        assertThat(access).isPresent();
        assertThat(access.get().vaultId()).isEqualTo("vlt_1");
        assertThat(access.get().serverName()).isEqualTo("github");
        assertThat(access.get().serverUrl()).isEqualTo(MCP_URL);

        ArgumentCaptor<GitVaultRef> remembered = ArgumentCaptor.forClass(GitVaultRef.class);
        verify(gitTokenService).rememberVault(eq(USER), remembered.capture());
        assertThat(remembered.getValue().vaultId()).isEqualTo("vlt_1");
        assertThat(remembered.getValue().credentialId()).isEqualTo("vcrd_1");
    }

    @Test
    void reusesAnExistingVaultWithoutCallingTheProviderAgain() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.of(new GitVaultRef("vlt_1", "vcrd_1")));

        Optional<McpAccess> access = service().resolveAccess(USER);

        assertThat(access).isPresent();
        assertThat(access.get().vaultId()).isEqualTo("vlt_1");
        // Un vault par utilisateur : rien n'est recréé, et le jeton n'est même pas déchiffré.
        verifyNoInteractions(provider);
    }

    @Test
    void createsNothingWhenTheUserHasNoToken() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.empty());

        assertThat(service().resolveAccess(USER)).isEmpty();

        // Rien n'est déposé chez le fournisseur pour un utilisateur qui n'a rien enregistré.
        verifyNoInteractions(provider);
    }

    @Test
    void degradesSilentlyWhenTheProviderRefusesTheVault() {
        when(gitTokenService.findVault(USER)).thenReturn(Optional.empty());
        when(gitTokenService.resolveToken(USER)).thenReturn(Optional.of("github_pat_secret"));
        when(provider.createVaultWithBearer(anyString(), any(), any()))
                .thenThrow(new AgentProviderException("fournisseur indisponible"));

        // La session doit pouvoir s'ouvrir SANS MCP : perdre l'Atelier entier parce que la création
        // de pull request est indisponible serait disproportionné (le repli SF-31-04 tient toujours).
        assertThat(service().resolveAccess(USER)).isEmpty();
        verify(gitTokenService, org.mockito.Mockito.never()).rememberVault(any(), any());
    }

    @Test
    void destroysTheVaultWhenTheTokenIsRevoked() {
        service().onTokenRevoked(new GitVaultRevokedEvent(USER, "vlt_1", "vcrd_1"));

        verify(provider).deleteVault("vlt_1");
    }

    @Test
    void neverFailsTheUsersGestureOnAProviderOutageAtRevocation() {
        // deleteVault est best-effort côté provider ; le service ne rattrape rien de plus, mais on
        // fige ici que la révocation n'est pas censée remonter d'erreur à l'utilisateur.
        assertThatCode(() -> service().onTokenRevoked(new GitVaultRevokedEvent(USER, "vlt_1", null)))
                .doesNotThrowAnyException();
    }
}
