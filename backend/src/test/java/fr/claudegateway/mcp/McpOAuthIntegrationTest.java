package fr.claudegateway.mcp;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Serveur d'autorisation et de ressources OAuth 2.1 (F-112 / SF-112-02) : découverte des métadonnées
 * (RFC 9728 / 8414), défi {@code WWW-Authenticate}, et <b>tests d'attaque</b> du cadrage §9
 * (PKCE absent, redirection non déclarée, périmètre élargi). L'audience et l'isolation sont testées
 * bout à bout dans {@code McpServerConformanceIntegrationTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class McpOAuthIntegrationTest {

    private static final String CHALLENGE = "E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM";

    @Autowired
    private MockMvc mockMvc;

    @Test
    void protectedResourceMetadataIsPublic() throws Exception {
        mockMvc.perform(get("/api/.well-known/oauth-protected-resource").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.resource", Matchers.containsString("/api/mcp")))
                .andExpect(jsonPath("$.authorization_servers", Matchers.hasSize(1)))
                .andExpect(jsonPath("$.scopes_supported", Matchers.hasItem("postes:lire")));
    }

    @Test
    void authorizationServerMetadataIsServed() throws Exception {
        mockMvc.perform(get("/api/.well-known/oauth-authorization-server").contextPath("/api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.issuer", Matchers.notNullValue()))
                .andExpect(jsonPath("$.authorization_endpoint", Matchers.notNullValue()))
                .andExpect(jsonPath("$.token_endpoint", Matchers.notNullValue()))
                .andExpect(jsonPath("$.code_challenge_methods_supported", Matchers.hasItem("S256")));
    }

    @Test
    void mcpWithoutTokenReturns401WithResourceMetadataChallenge() throws Exception {
        mockMvc.perform(get("/api/mcp").contextPath("/api"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate",
                        Matchers.containsString("resource_metadata=")));
    }

    @Test
    void authorizeWithoutPkceIsRejected() throws Exception {
        mockMvc.perform(get("/api/oauth2/authorize").contextPath("/api")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", AuthorizationServerConfig.DEMO_CLIENT_ID)
                        .queryParam("redirect_uri", AuthorizationServerConfig.DEMO_REDIRECT_URI)
                        .queryParam("scope", "compte:lire")
                        .queryParam("state", "xyz")
                        .with(user("alice@example.com").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.containsString("error=invalid_request")));
    }

    @Test
    void authorizeWithUndeclaredRedirectIsRejected() throws Exception {
        mockMvc.perform(get("/api/oauth2/authorize").contextPath("/api")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", AuthorizationServerConfig.DEMO_CLIENT_ID)
                        .queryParam("redirect_uri", "https://evil.example.com/callback")
                        .queryParam("scope", "compte:lire")
                        .queryParam("state", "xyz")
                        .queryParam("code_challenge", CHALLENGE)
                        .queryParam("code_challenge_method", "S256")
                        .with(user("alice@example.com").roles("USER")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void authorizeWithBroadenedScopeIsRejected() throws Exception {
        mockMvc.perform(get("/api/oauth2/authorize").contextPath("/api")
                        .queryParam("response_type", "code")
                        .queryParam("client_id", AuthorizationServerConfig.DEMO_CLIENT_ID)
                        .queryParam("redirect_uri", AuthorizationServerConfig.DEMO_REDIRECT_URI)
                        .queryParam("scope", "compte:lire scope_inexistant")
                        .queryParam("state", "xyz")
                        .queryParam("code_challenge", CHALLENGE)
                        .queryParam("code_challenge_method", "S256")
                        .with(user("alice@example.com").roles("USER")))
                .andExpect(status().is3xxRedirection())
                .andExpect(header().string("Location", Matchers.containsString("error=invalid_scope")));
    }
}
