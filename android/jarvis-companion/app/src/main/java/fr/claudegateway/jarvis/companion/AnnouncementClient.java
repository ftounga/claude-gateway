package fr.claudegateway.jarvis.companion;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import fr.claudegateway.jarvis.companion.core.AnnouncementPolicy;

/**
 * <b>Les deux seules routes que le compagnon appelle</b> (F-232 / SF-232-03) : l'appairage
 * ({@code POST /api/runner/jarvis/pair}) et la relève des annonces ({@code GET /api/runner/jarvis/announcements},
 * jeton dans l'en-tête {@value #TOKEN_HEADER}). HTTPS seulement (adresse normalisée par {@code JarvisLink}). Le jeton
 * n'est jamais journalisé. À appeler hors du fil principal.
 */
final class AnnouncementClient {

    static final String TOKEN_HEADER = "X-Jarvis-Token";
    private static final int TIMEOUT_MS = 15_000;

    /** Le jeton est refusé (révoqué ou expiré) : il faut réappairer. */
    static final class Unauthorized extends IOException {
        Unauthorized() {
            super("Jeton refusé");
        }
    }

    /** Ce que rend la relève : les annonces et le curseur suivant. */
    static final class Feed {
        final String cursor;
        final List<AnnouncementPolicy.Item> items;

        Feed(String cursor, List<AnnouncementPolicy.Item> items) {
            this.cursor = cursor;
            this.items = items;
        }
    }

    private AnnouncementClient() {
    }

    /** Échange le code affiché dans l'écran Jarvis contre le jeton dédié. */
    static String pair(String gateway, String code, String label) throws IOException {
        JSONObject body = new JSONObject();
        try {
            body.put("code", code);
            body.put("label", label);
        } catch (JSONException e) {
            throw new IOException("Requête illisible", e);
        }
        HttpURLConnection http = open(gateway + "/api/runner/jarvis/pair");
        try {
            http.setRequestMethod("POST");
            http.setDoOutput(true);
            http.setRequestProperty("Content-Type", "application/json");
            try (OutputStream out = http.getOutputStream()) {
                out.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = http.getResponseCode();
            if (status == 401 || status == 400) {
                throw new Unauthorized();
            }
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            return new JSONObject(read(http.getInputStream())).getString("token");
        } catch (JSONException e) {
            throw new IOException("Réponse illisible", e);
        } finally {
            http.disconnect();
        }
    }

    /** Les annonces depuis {@code cursor} ({@code null} au premier appel : aucune, seulement le curseur). */
    static Feed fetch(String gateway, String token, String cursor) throws IOException {
        String url = gateway + "/api/runner/jarvis/announcements"
                + (cursor == null ? "" : "?cursor=" + URLEncoder.encode(cursor, "UTF-8"));
        HttpURLConnection http = open(url);
        try {
            http.setRequestProperty(TOKEN_HEADER, token);
            int status = http.getResponseCode();
            if (status == 401) {
                throw new Unauthorized();
            }
            if (status != 200) {
                throw new IOException("HTTP " + status);
            }
            JSONObject json = new JSONObject(read(http.getInputStream()));
            JSONArray array = json.optJSONArray("items");
            List<AnnouncementPolicy.Item> items = new ArrayList<>();
            for (int i = 0; array != null && i < array.length(); i++) {
                JSONObject item = array.getJSONObject(i);
                items.add(new AnnouncementPolicy.Item(item.optString("id", null), item.optString("text", null)));
            }
            return new Feed(json.optString("cursor", cursor), items);
        } catch (JSONException e) {
            throw new IOException("Réponse illisible", e);
        } finally {
            http.disconnect();
        }
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection http = (HttpURLConnection) new URL(url).openConnection();
        http.setConnectTimeout(TIMEOUT_MS);
        http.setReadTimeout(TIMEOUT_MS);
        http.setInstanceFollowRedirects(false);
        http.setRequestProperty("Accept", "application/json");
        return http;
    }

    private static String read(InputStream in) throws IOException {
        try (InputStream input = in; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) > 0) {
                out.write(buffer, 0, n);
                if (out.size() > 256 * 1024) {
                    throw new IOException("Réponse trop longue");
                }
            }
            return out.toString("UTF-8");
        }
    }
}
