package fr.claudegateway.jarvis.calendar;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Les appels à Google</b> (F-243 / SF-243-03, D3, D5) — échange du code OAuth (PKCE), rafraîchissement, révocation,
 * création d'un événement. <b>Aucun jeton n'est journalisé</b> ; les erreurs portent un message neutre.
 *
 * <p>La création pose <b>exactement</b> les champs reçus, avec {@code sendUpdates=none} et <b>aucun invité</b> : poser un
 * rendez-vous n'envoie aucun mail à un tiers (D5, D12).</p>
 */
@Component
public class GoogleCalendarApi {

    /** Les jetons rendus par l'échange du code. */
    public record Tokens(String refreshToken, String accessToken, long expiresIn, String email, String scope) {
    }

    /** Un jeton d'accès et sa durée. */
    public record Access(String accessToken, long expiresIn) {
    }

    /** Ce qui est posé : les champs exacts de l'action confirmée. */
    public record EventBody(String title, String startIso, String endIso, String timeZone, String location,
            String description) {
    }

    /** Google refuse le jeton d'accès (401) : rafraîchir puis réessayer une fois. */
    public static class UnauthorizedException extends RuntimeException {
        public UnauthorizedException() {
            super("accès refusé par Google");
        }
    }

    /** Le jeton de rafraîchissement n'est plus valable (révoqué chez Google, expiré). */
    public static class GrantRevokedException extends RuntimeException {
        public GrantRevokedException() {
            super("droit révoqué chez Google");
        }
    }

    /** Google ne répond pas ou refuse la demande. */
    public static class GoogleUnavailableException extends RuntimeException {
        public GoogleUnavailableException(String message) {
            super(message);
        }
    }

    private final JarvisCalendarOAuthProperties properties;
    private final HttpClient http;
    private final ObjectMapper json = new ObjectMapper();

    public GoogleCalendarApi(JarvisCalendarOAuthProperties properties) {
        this.properties = properties;
        this.http = HttpClient.newBuilder().connectTimeout(properties.timeout())
                .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    /** L'adresse de consentement (client Internal, accès hors ligne, PKCE S256). */
    public String authorizationUrl(String state, String challenge) {
        Map<String, String> q = new LinkedHashMap<>();
        q.put("client_id", properties.clientId());
        q.put("redirect_uri", properties.redirectUri());
        q.put("response_type", "code");
        q.put("scope", JarvisCalendarOAuthProperties.SCOPE);
        q.put("access_type", "offline");
        q.put("prompt", "consent");
        q.put("include_granted_scopes", "true");
        q.put("state", state);
        q.put("code_challenge", challenge);
        q.put("code_challenge_method", "S256");
        if (!properties.hostedDomain().isEmpty()) {
            q.put("hd", properties.hostedDomain());
        }
        return properties.authUri() + "?" + form(q);
    }

    /** Échange le code contre les jetons. */
    public Tokens exchange(String code, String verifier) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("code", code);
        f.put("client_id", properties.clientId());
        f.put("client_secret", properties.clientSecret());
        f.put("redirect_uri", properties.redirectUri());
        f.put("grant_type", "authorization_code");
        f.put("code_verifier", verifier);
        JsonNode body = postForm(properties.tokenUri(), f, false);
        String refresh = body.path("refresh_token").asText("");
        String access = body.path("access_token").asText("");
        if (refresh.isEmpty() || access.isEmpty()) {
            throw new GoogleUnavailableException("Google n'a pas rendu de jeton de rafraîchissement.");
        }
        return new Tokens(refresh, access, body.path("expires_in").asLong(3600), email(body.path("id_token").asText("")),
                body.path("scope").asText(""));
    }

    /** Un jeton d'accès neuf. */
    public Access refresh(String refreshToken) {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("client_id", properties.clientId());
        f.put("client_secret", properties.clientSecret());
        f.put("refresh_token", refreshToken);
        f.put("grant_type", "refresh_token");
        JsonNode body = postForm(properties.tokenUri(), f, true);
        String access = body.path("access_token").asText("");
        if (access.isEmpty()) {
            throw new GoogleUnavailableException("Google n'a pas rendu de jeton d'accès.");
        }
        return new Access(access, body.path("expires_in").asLong(3600));
    }

    /** Révoque un jeton chez Google (au mieux : une erreur n'empêche pas l'effacement local). */
    public boolean revoke(String token) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.revokeUri()))
                    .timeout(properties.timeout()).header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("token", token)))).build();
            return http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode() / 100 == 2;
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Crée l'événement dans l'agenda principal : {@code sendUpdates=none}, aucun champ {@code attendees}. Rend
     * l'identifiant Google de l'événement.
     */
    public String insertEvent(String accessToken, EventBody event) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(properties.apiBase()
                        + "/calendars/primary/events?sendUpdates=none"))
                .timeout(properties.timeout()).header("Authorization", "Bearer " + accessToken)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(eventJson(event))).build();
        JsonNode body = send(request);
        String id = body.path("id").asText("");
        if (id.isEmpty()) {
            throw new GoogleUnavailableException("Google n'a pas rendu d'identifiant d'événement.");
        }
        return id;
    }

    /** Le corps JSON de la création : uniquement ces champs (testé : aucun invité, aucun envoi). */
    String eventJson(EventBody e) {
        ObjectNode root = json.createObjectNode();
        root.put("summary", e.title());
        if (e.location() != null && !e.location().isBlank()) {
            root.put("location", e.location());
        }
        root.put("description", e.description() == null ? "" : e.description());
        root.putObject("start").put("dateTime", e.startIso()).put("timeZone", e.timeZone());
        root.putObject("end").put("dateTime", e.endIso()).put("timeZone", e.timeZone());
        root.putObject("reminders").put("useDefault", true);
        root.put("guestsCanInviteOthers", false);
        return root.toString();
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GoogleUnavailableException("Google Agenda ne répond pas.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleUnavailableException("Google Agenda ne répond pas.");
        }
        if (response.statusCode() == 401) {
            throw new UnauthorizedException();
        }
        if (response.statusCode() / 100 != 2) {
            throw new GoogleUnavailableException("Google Agenda refuse la demande (" + response.statusCode() + ").");
        }
        return parse(response.body());
    }

    private JsonNode postForm(String uri, Map<String, String> fields, boolean refreshing) {
        HttpResponse<String> response;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(uri)).timeout(properties.timeout())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(HttpRequest.BodyPublishers.ofString(form(fields))).build();
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new GoogleUnavailableException("Google ne répond pas.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoogleUnavailableException("Google ne répond pas.");
        }
        if (response.statusCode() / 100 != 2) {
            JsonNode err = parse(response.body());
            if (refreshing && "invalid_grant".equals(err.path("error").asText(""))) {
                throw new GrantRevokedException();
            }
            throw new GoogleUnavailableException("Google refuse la demande (" + response.statusCode() + ").");
        }
        return parse(response.body());
    }

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? json.createObjectNode() : json.readTree(body);
        } catch (IOException e) {
            return json.createObjectNode();
        }
    }

    /**
     * L'adresse du compte, lue dans le jeton d'identité rendu <b>directement</b> par Google sur TLS (canal sûr : la
     * signature n'a pas à être revérifiée pour cet usage d'affichage).
     */
    String email(String idToken) {
        String[] parts = idToken == null ? new String[0] : idToken.split("\\.");
        if (parts.length < 2) {
            return "";
        }
        try {
            JsonNode claims = json.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return claims.path("email").asText("");
        } catch (IOException | IllegalArgumentException e) {
            return "";
        }
    }

    static String form(Map<String, String> fields) {
        return fields.entrySet().stream().map(e -> URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8) + "="
                + URLEncoder.encode(e.getValue() == null ? "" : e.getValue(), StandardCharsets.UTF_8))
                .collect(Collectors.joining("&"));
    }
}
