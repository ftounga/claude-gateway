package fr.claudegateway.pages.pdf;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * <b>La gateway va chercher, pour le moteur PDF, ce que la page charge dehors</b> (F-184 / SF-184-02).
 *
 * <p>Le service de rendu reste hors ligne ; la gateway, elle, sort déjà sur Internet. Elle récupère donc
 * les scripts et polices d'une page — mais <b>seulement</b> sur la liste fermée de
 * {@link PageLotScanner#ALLOWED_HOSTS}, en HTTPS, <b>sans suivre de redirection</b> (une redirection
 * pourrait mener hors de la liste). Chaque ressource est bornée ; un échec n'empêche jamais le PDF : la
 * ressource manque, et le moteur le dit.</p>
 *
 * <p>Les adresses de CDN sont épinglées (versions exactes, guide F-109) : leur contenu ne change pas, d'où
 * un cache LRU en mémoire, borné en octets.</p>
 */
@Component
public class ExternalResourceFetcher {

    /** Borne d'une ressource : un script ou une police dépasse rarement quelques centaines de Ko. */
    public static final int MAX_RESOURCE_BYTES = 5 * 1024 * 1024;
    static final long CACHE_MAX_BYTES = 64L * 1024 * 1024;
    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Google Fonts sert du woff2, et commente ses sous-ensembles, à un navigateur récent. */
    static final String USER_AGENT = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 "
            + "(KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";

    private static final Logger log = LoggerFactory.getLogger(ExternalResourceFetcher.class);

    /** Une ressource récupérée. */
    public record Fetched(URI uri, String contentType, byte[] body) {
    }

    private final HttpClient httpClient;
    private final Map<String, Fetched> cache = new LinkedHashMap<>(64, 0.75f, true);
    private long cachedBytes;

    /** Le constructeur de Spring — annoté : le second sert aux tests, qui injectent leur client. */
    @Autowired
    public ExternalResourceFetcher() {
        this(HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(TIMEOUT)
                .build());
    }

    ExternalResourceFetcher(HttpClient httpClient) {
        this.httpClient = httpClient;
    }

    /**
     * La ressource, ou vide si l'adresse n'est pas autorisée, si la récupération échoue, ou si elle
     * dépasse {@link #MAX_RESOURCE_BYTES}.
     */
    public Optional<Fetched> fetch(URI uri) {
        Optional<URI> checked = uri == null ? Optional.empty() : PageLotScanner.allowed(uri.toString());
        if (checked.isEmpty()) {
            return Optional.empty();
        }
        URI target = checked.get();
        String key = target.toString();
        synchronized (cache) {
            Fetched hit = cache.get(key);
            if (hit != null) {
                return Optional.of(hit);
            }
        }
        try {
            HttpRequest request = HttpRequest.newBuilder(target)
                    .timeout(TIMEOUT)
                    .header("User-Agent", USER_AGENT)
                    .GET()
                    .build();
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = response.body()) {
                if (response.statusCode() != 200) {
                    // Y compris 3xx : on ne suit pas, la ressource manquera.
                    return Optional.empty();
                }
                byte[] body = in.readNBytes(MAX_RESOURCE_BYTES + 1);
                if (body.length > MAX_RESOURCE_BYTES) {
                    log.info("Ressource externe trop lourde pour le PDF, ignorée : {}", target.getHost());
                    return Optional.empty();
                }
                String type = response.headers().firstValue("Content-Type").orElse("application/octet-stream");
                Fetched fetched = new Fetched(target, type, body);
                remember(key, fetched);
                return Optional.of(fetched);
            }
        } catch (IOException e) {
            log.info("Ressource externe injoignable pour le PDF ({}) : {}", target.getHost(), e.getMessage());
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private void remember(String key, Fetched fetched) {
        synchronized (cache) {
            Fetched previous = cache.put(key, fetched);
            if (previous != null) {
                cachedBytes -= previous.body().length;
            }
            cachedBytes += fetched.body().length;
            Iterator<Map.Entry<String, Fetched>> eldest = cache.entrySet().iterator();
            while (cachedBytes > CACHE_MAX_BYTES && eldest.hasNext()) {
                cachedBytes -= eldest.next().getValue().body().length;
                eldest.remove();
            }
        }
    }
}
