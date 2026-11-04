package fr.claudegateway.jarvis.calendar;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Locale;

import org.springframework.stereotype.Component;

/**
 * <b>La lecture HTTP d'un agenda iCal</b> (F-243 / SF-243-01, D2) — une requête {@code GET} <b>conditionnelle</b>
 * ({@code If-None-Match} / {@code If-Modified-Since} quand Google a fourni {@code ETag} / {@code Last-Modified}).
 *
 * <p><b>Garde anti-SSRF</b> : l'adresse est saisie par l'utilisateur et lue par le serveur. Seuls les hôtes de
 * {@link JarvisCalendarProperties#allowedHosts()} (défaut {@code calendar.google.com}) sont joignables, en HTTPS, sans
 * identifiants dans l'adresse ; une redirection n'est suivie (3 au plus) que vers un hôte admis. Le corps est borné
 * ({@link JarvisCalendarProperties#maxBytes()}). L'adresse n'est jamais journalisée.</p>
 */
@Component
public class JarvisCalendarFetcher {

    static final int MAX_REDIRECTS = 3;
    private static final int MAX_URL = 1024;

    /** Le résultat d'une lecture : {@code body == null} ⇔ 304 (inchangé). */
    public record Fetched(byte[] body, String etag, String lastModified) {
        public boolean notModified() {
            return body == null;
        }
    }

    private final JarvisCalendarProperties properties;
    private final HttpClient http;

    public JarvisCalendarFetcher(JarvisCalendarProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /**
     * Valide et normalise une adresse saisie ({@code webcal://} devient {@code https://}).
     *
     * @throws JarvisCalendarExceptions.InvalidFeedException si l'adresse n'est pas admise
     */
    public URI normalize(String raw) {
        String url = raw == null ? "" : raw.strip();
        if (url.isEmpty() || url.length() > MAX_URL) {
            throw invalid();
        }
        if (url.regionMatches(true, 0, "webcal://", 0, 9)) {
            url = "https://" + url.substring(9);
        }
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            throw invalid();
        }
        if (!admitted(uri)) {
            throw invalid();
        }
        return uri;
    }

    /** Lit l'agenda ; {@code etag}/{@code lastModified} facultatifs (requête conditionnelle). */
    public Fetched fetch(URI uri, String etag, String lastModified) {
        URI current = uri;
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            if (!admitted(current)) {
                throw new JarvisCalendarExceptions.FeedUnavailableException();
            }
            HttpRequest.Builder request = HttpRequest.newBuilder(current).timeout(properties.timeout()).GET()
                    .header("Accept", "text/calendar, */*;q=0.5");
            if (etag != null && !etag.isBlank()) {
                request.header("If-None-Match", etag);
            }
            if (lastModified != null && !lastModified.isBlank()) {
                request.header("If-Modified-Since", lastModified);
            }
            HttpResponse<InputStream> response;
            try {
                response = http.send(request.build(), HttpResponse.BodyHandlers.ofInputStream());
            } catch (IOException e) {
                throw new JarvisCalendarExceptions.FeedUnavailableException();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new JarvisCalendarExceptions.FeedUnavailableException();
            } catch (IllegalArgumentException e) {
                throw invalid();
            }
            int status = response.statusCode();
            if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                close(response);
                String location = response.headers().firstValue("Location").orElse(null);
                if (location == null) {
                    throw new JarvisCalendarExceptions.FeedUnavailableException();
                }
                try {
                    current = current.resolve(new URI(location.strip()));
                } catch (URISyntaxException e) {
                    throw new JarvisCalendarExceptions.FeedUnavailableException();
                }
                continue;
            }
            if (status == 304) {
                close(response);
                return new Fetched(null, etag, lastModified);
            }
            if (status == 404 || status == 410) {
                close(response);
                throw new JarvisCalendarExceptions.FeedGoneException();
            }
            if (status != 200) {
                close(response);
                throw new JarvisCalendarExceptions.FeedUnavailableException();
            }
            byte[] body = read(response.body());
            return new Fetched(body, header(response, "ETag"), header(response, "Last-Modified"));
        }
        throw new JarvisCalendarExceptions.FeedUnavailableException();
    }

    private boolean admitted(URI uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        boolean schemeOk = "https".equals(scheme) || ("http".equals(scheme) && properties.isHttpAllowed());
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        return schemeOk && uri.getRawUserInfo() == null && !host.isEmpty()
                && properties.allowedHosts().contains(host) && uri.getRawPath() != null && uri.getRawPath().length() > 1;
    }

    private byte[] read(InputStream in) {
        long max = properties.maxBytes();
        try (in) {
            byte[] bytes = in.readNBytes((int) Math.min(Integer.MAX_VALUE - 8, max + 1));
            if (bytes.length > max) {
                throw new JarvisCalendarExceptions.NotACalendarException();
            }
            return bytes;
        } catch (IOException e) {
            throw new JarvisCalendarExceptions.FeedUnavailableException();
        }
    }

    private static String header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name).map(String::strip).filter(v -> !v.isEmpty() && v.length() <= 200)
                .orElse(null);
    }

    private static void close(HttpResponse<InputStream> response) {
        try {
            response.body().close();
        } catch (IOException e) {
            // rien à faire
        }
    }

    private static JarvisCalendarExceptions.InvalidFeedException invalid() {
        return new JarvisCalendarExceptions.InvalidFeedException("Adresse invalide : collez l'« adresse secrète au "
                + "format iCal » de l'agenda Google (elle commence par https://calendar.google.com/calendar/ical/).");
    }
}
