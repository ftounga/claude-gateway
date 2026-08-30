package fr.claudegateway.runner.relay;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * Preuve d'<b>inatteignabilité depuis l'ingress</b> du relais interne (F-38 / SF-38-12).
 *
 * <p>Trois barrières indépendantes protègent {@code /internal/**}, et chacune est vérifiée ici, parce
 * que derrière cette route il y a de l'exécution de commandes sur la machine de l'utilisateur :</p>
 * <ol>
 *   <li><b>réseau</b> — le port du relais n'est publié que par un Service headless visé par aucun
 *       Ingress (vérifié par les manifests, pas par un test) ;</li>
 *   <li><b>application</b> — T1 : reçue sur le port public, la route n'existe pas (404, corps vide),
 *       <i>même avec le bon secret</i> ;</li>
 *   <li><b>secret</b> — T2/T3 : sans secret valide, 401 corps vide, sans indice sur l'existence de la
 *       route.</li>
 * </ol>
 *
 * <p>T5 verrouille l'invariant qui rend cette preuve durable : aucun autre contrôleur du projet ne
 * publie de chemin {@code /internal}.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "app.runner.relay.secret=secret-de-relais-de-test-32-octets!!",
                // 0 : Tomcat choisit un port libre pour le connecteur interne (isolation des tests).
                "app.runner.relay.port=0"
        })
@ActiveProfiles("test")
class RunnerRelayApiIntegrationTest {

    private static final String PATH = "/api/internal/runner/call";
    private static final String SECRET = "secret-de-relais-de-test-32-octets!!";
    /** Même longueur que le vrai : la comparaison en temps constant ne doit pas la distinguer. */
    private static final String WRONG_SECRET = "secret-de-relais-de-test-32-octets??";

    @LocalServerPort
    private int publicPort;

    @Autowired
    private RunnerRelayConnectorCustomizer relayConnector;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private RequestMappingHandlerMapping handlerMapping;

    private ResponseEntity<String> post(int port, String secret) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_NDJSON));
        if (secret != null) {
            headers.set(RunnerRelayAuthFilter.SECRET_HEADER, secret);
        }
        String body = """
                {"workspaceId":"%s","callId":"toolu_1","tool":"list_files","input":{},"timeoutMs":30000}
                """.formatted(UUID.randomUUID());
        return restTemplate.exchange("http://localhost:" + port + PATH, HttpMethod.POST,
                new HttpEntity<>(body, headers), String.class);
    }

    @Test
    void t1_publicPortDoesNotExposeTheRelayEvenWithAValidSecret() {
        ResponseEntity<String> response = post(publicPort, SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(404);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void t2_relayPortWithoutSecretIsRefusedWithoutRevealingTheRoute() {
        ResponseEntity<String> response = post(relayConnector.relayPort(), null);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNull();
        assertThat(response.getHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE)).isNull();
    }

    @Test
    void t3_relayPortWithAWrongSecretOfTheSameLengthIsRefused() {
        ResponseEntity<String> response = post(relayConnector.relayPort(), WRONG_SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(401);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void t4_relayPortWithTheRightSecretServesTheNdjsonEnvelope() {
        ResponseEntity<String> response = post(relayConnector.relayPort(), SECRET);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType().toString())
                .startsWith(MediaType.APPLICATION_NDJSON_VALUE);
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-store");

        String[] lines = response.getBody().strip().split("\n");
        // Aucun runner connecté dans ce contexte : une seule ligne, l'issue, et elle est la dernière.
        assertThat(lines[lines.length - 1]).contains("\"type\":\"result\"")
                .contains("\"errorCode\":\"runner_unavailable\"");
    }

    @Test
    void t5_noControllerOutsideTheRelayPackageDeclaresAnInternalPath() {
        for (Map.Entry<RequestMappingInfo, HandlerMethod> entry : handlerMapping
                .getHandlerMethods().entrySet()) {
            String patterns = entry.getKey().toString();
            if (patterns.contains("/internal")) {
                assertThat(entry.getValue().getBeanType().getPackageName())
                        .as("mapping %s", patterns)
                        .isEqualTo("fr.claudegateway.runner.relay");
            }
        }
    }
}
