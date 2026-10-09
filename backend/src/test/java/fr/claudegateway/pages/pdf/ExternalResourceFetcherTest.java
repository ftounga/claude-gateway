package fr.claudegateway.pages.pdf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** La récupération des ressources externes d'une page : liste fermée, sans redirection, bornée, en cache. */
class ExternalResourceFetcherTest {

    private static final URI CHART = URI.create("https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js");

    private HttpClient client;
    private ExternalResourceFetcher fetcher;

    @BeforeEach
    void setUp() {
        client = mock(HttpClient.class);
        fetcher = new ExternalResourceFetcher(client);
    }

    @SuppressWarnings("unchecked")
    private void respond(int status, byte[] body) throws Exception {
        HttpResponse<InputStream> response = mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(status);
        when(response.body()).thenAnswer(inv -> new ByteArrayInputStream(body));
        when(response.headers()).thenReturn(HttpHeaders.of(Map.of("Content-Type", List.of("text/javascript")), (a, b) -> true));
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn((HttpResponse) response);
    }

    @Test
    @DisplayName("nominal : la ressource et son type ; User-Agent d'un navigateur récent")
    @SuppressWarnings("unchecked")
    void fetches() throws Exception {
        respond(200, "/*chart*/".getBytes());

        ExternalResourceFetcher.Fetched fetched = fetcher.fetch(CHART).orElseThrow();

        assertThat(new String(fetched.body())).isEqualTo("/*chart*/");
        assertThat(fetched.contentType()).isEqualTo("text/javascript");
        ArgumentCaptor<HttpRequest> request = ArgumentCaptor.forClass(HttpRequest.class);
        verify(client).send(request.capture(), any(HttpResponse.BodyHandler.class));
        assertThat(request.getValue().headers().firstValue("User-Agent")).get().asString().contains("Chrome/");
    }

    @Test
    @DisplayName("CA4 — hors liste ou en clair : jamais appelé")
    @SuppressWarnings("unchecked")
    void neverCallsOutsideTheList() throws Exception {
        assertThat(fetcher.fetch(URI.create("https://evil.example/x.js"))).isEmpty();
        assertThat(fetcher.fetch(URI.create("http://cdn.jsdelivr.net/npm/a.js"))).isEmpty();
        assertThat(fetcher.fetch(URI.create("https://169.254.169.254/latest/meta-data"))).isEmpty();
        assertThat(fetcher.fetch(null)).isEmpty();
        verify(client, never()).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("CA4 — une redirection n'est pas suivie : la ressource manque")
    void redirectIsNotFollowed() throws Exception {
        respond(302, new byte[0]);
        assertThat(fetcher.fetch(CHART)).isEmpty();
    }

    @Test
    @DisplayName("CA6 — au-delà de 5 Mo : ignorée")
    void tooHeavy() throws Exception {
        respond(200, new byte[ExternalResourceFetcher.MAX_RESOURCE_BYTES + 1]);
        assertThat(fetcher.fetch(CHART)).isEmpty();
    }

    @Test
    @DisplayName("CA6 — le cache évite un second téléchargement")
    @SuppressWarnings("unchecked")
    void cached() throws Exception {
        respond(200, "/*chart*/".getBytes());
        fetcher.fetch(CHART);
        fetcher.fetch(URI.create(CHART + "#frag"));
        verify(client, times(1)).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("injoignable : vide, sans exception")
    @SuppressWarnings("unchecked")
    void unreachable() throws Exception {
        when(client.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenThrow(new java.io.IOException("connexion refusée"));
        assertThat(fetcher.fetch(CHART)).isEmpty();
    }
}
