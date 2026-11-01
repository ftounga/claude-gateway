package fr.claudegateway.jarvis.companion.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * <b>Les deux appels du compagnon à la gateway</b> (F-232 / SF-232-03), en Java pur ({@link HttpURLConnection}) :
 * l'appairage par code et la réception des annonces par attente longue. Rien d'autre ne part du téléphone — et
 * rien du micro.
 */
public final class CompanionApi {

    static final String TOKEN_HEADER = "X-Companion-Token";
    /** Attente demandée à la gateway ; le délai de lecture la dépasse pour laisser la réponse arriver. */
    public static final int WAIT_SECONDS = 25;
    private static final int CONNECT_TIMEOUT_MS = 15_000;
    private static final int READ_TIMEOUT_MS = (WAIT_SECONDS + 20) * 1000;
    private static final int MAX_BODY = 256 * 1024;

    private CompanionApi() {
    }

    /** Le jeton n'est plus accepté (révoqué depuis l'application) : il faut l'oublier. */
    public static final class Unauthorized extends Exception {
        public Unauthorized() {
            super("companion_token_invalid");
        }
    }

    /** La gateway a refusé la demande (code refusé, trop de téléphones…) ; {@link #code} est son code d'erreur. */
    public static final class Refused extends Exception {
        public final String code;

        public Refused(String code) {
            super(code);
            this.code = code;
        }
    }

    /** Le téléphone appairé. */
    public static final class Paired {
        public final String deviceId;
        public final String token;

        public Paired(String deviceId, String token) {
            this.deviceId = deviceId;
            this.token = token;
        }
    }

    /** Une annonce à dire. {@code link} : chemin de l'application (« /jarvis?sujet=… »). */
    public static final class Announcement {
        public final String id;
        public final String kind;
        public final String text;
        public final String link;

        public Announcement(String id, String kind, String text, String link) {
            this.id = id;
            this.kind = kind;
            this.text = text;
            this.link = link;
        }
    }

    /** Ce que rend une attente : le curseur à repasser et les annonces (peut être vide). */
    public static final class Feed {
        public final String cursor;
        public final List<Announcement> items;

        public Feed(String cursor, List<Announcement> items) {
            this.cursor = cursor;
            this.items = items;
        }
    }

    /** Échange le code affiché par l'application contre le jeton dédié. */
    public static Paired pair(String gateway, String code, String name) throws IOException, Refused {
        String body = "{\"code\":" + MiniJson.quote(code) + ",\"name\":" + MiniJson.quote(name) + "}";
        HttpURLConnection c = open(gateway + "/api/companion/pair");
        c.setRequestMethod("POST");
        c.setDoOutput(true);
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        try (OutputStream out = c.getOutputStream()) {
            out.write(body.getBytes(StandardCharsets.UTF_8));
        }
        int status = c.getResponseCode();
        String text = read(c, status);
        if (status != 200) {
            throw new Refused(errorCode(text, status));
        }
        Map<?, ?> json = asMap(MiniJson.parse(text));
        String token = str(json.get("token"));
        if (token == null || token.isEmpty()) {
            throw new IOException("Réponse d'appairage sans jeton");
        }
        return new Paired(str(json.get("deviceId")), token);
    }

    /**
     * Attend les annonces postérieures à {@code cursor} (vide au premier appel : la gateway rend « maintenant » et
     * rien). {@link Unauthorized} : le téléphone a été révoqué.
     */
    public static Feed announcements(String gateway, String token, String cursor)
            throws IOException, Unauthorized {
        StringBuilder url = new StringBuilder(gateway).append("/api/companion/announcements?wait=").append(WAIT_SECONDS);
        if (cursor != null && !cursor.isEmpty()) {
            url.append("&after=").append(URLEncoder.encode(cursor, "UTF-8"));
        }
        HttpURLConnection c = open(url.toString());
        c.setRequestProperty(TOKEN_HEADER, token);
        int status = c.getResponseCode();
        String text = read(c, status);
        if (status == 401) {
            throw new Unauthorized();
        }
        if (status != 200) {
            throw new IOException("Annonces : HTTP " + status);
        }
        return parseFeed(text);
    }

    /** Lit la réponse des annonces. Pur. */
    public static Feed parseFeed(String text) throws IOException {
        Map<?, ?> json;
        try {
            json = asMap(MiniJson.parse(text));
        } catch (IllegalArgumentException e) {
            throw new IOException("Annonces illisibles", e);
        }
        List<Announcement> items = new ArrayList<>();
        Object raw = json.get("items");
        if (raw instanceof List) {
            for (Object o : (List<?>) raw) {
                if (!(o instanceof Map)) {
                    continue;
                }
                Map<?, ?> m = (Map<?, ?>) o;
                String text2 = str(m.get("text"));
                if (text2 == null || text2.isEmpty()) {
                    continue;
                }
                items.add(new Announcement(str(m.get("id")), str(m.get("kind")), text2, str(m.get("link"))));
            }
        }
        return new Feed(str(json.get("cursor")), Collections.unmodifiableList(items));
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(CONNECT_TIMEOUT_MS);
        c.setReadTimeout(READ_TIMEOUT_MS);
        c.setUseCaches(false);
        c.setRequestProperty("Accept", "application/json");
        return c;
    }

    private static String read(HttpURLConnection c, int status) throws IOException {
        InputStream in = status >= 400 ? c.getErrorStream() : c.getInputStream();
        if (in == null) {
            return "";
        }
        try (InputStream body = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = body.read(buf)) != -1) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BODY) {
                    throw new IOException("Réponse trop longue");
                }
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }

    private static String errorCode(String text, int status) {
        try {
            String code = str(asMap(MiniJson.parse(text)).get("error"));
            return code == null ? "http_" + status : code;
        } catch (IllegalArgumentException | IOException e) {
            return "http_" + status;
        }
    }

    private static Map<?, ?> asMap(Object o) throws IOException {
        if (!(o instanceof Map)) {
            throw new IOException("Objet JSON attendu");
        }
        return (Map<?, ?>) o;
    }

    private static String str(Object o) {
        return o instanceof String ? (String) o : null;
    }
}
