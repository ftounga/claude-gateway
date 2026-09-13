package fr.claudegateway.pages;

import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * <b>La politique d'une page servie</b> (F-109 / SF-109-01, cadrage §3 — non négociable).
 *
 * <p>Une page exécute du JavaScript écrit par un modèle et contient souvent des données de client. Elle
 * est donc servie :</p>
 * <ol>
 *   <li><b>en origine opaque</b> — {@code sandbox allow-scripts allow-popups}, <b>jamais</b>
 *       {@code allow-same-origin}, {@code allow-forms} ni {@code allow-top-navigation} : elle ne lit ni
 *       les cookies, ni le stockage, ni le jeton de l'application, et ne peut pas l'appeler ;</li>
 *   <li><b>sans sortie réseau</b> hors d'une liste close — {@code connect-src 'none'},
 *       {@code form-action 'none'} ; scripts depuis cdnjs et jsDelivr, polices depuis Google Fonts,
 *       images et médias en {@code data:} ou servis avec la page ({@code 'self'}).</li>
 * </ol>
 *
 * <p>La même politique vaut pour <b>toute</b> réponse de contenu : HTML, pièce jointe, et page d'erreur de
 * la route publique. Une réponse qui l'oublierait serait la seule qui compte.</p>
 */
public final class PageContentPolicy {

    /** Les deux jetons du bac à sable, et eux seuls. */
    public static final String SANDBOX = "sandbox allow-scripts allow-popups";

    /** CDN de scripts de la liste close du cadrage. */
    public static final String SCRIPT_HOSTS = "https://cdnjs.cloudflare.com https://cdn.jsdelivr.net";

    /** La politique entière, en un seul en-tête. */
    public static final String CSP = String.join("; ",
            SANDBOX,
            "default-src 'none'",
            "script-src 'unsafe-inline' " + SCRIPT_HOSTS + " 'self'",
            "style-src 'unsafe-inline' https://fonts.googleapis.com " + SCRIPT_HOSTS + " 'self'",
            "font-src https://fonts.gstatic.com data: 'self'",
            "img-src data: blob: 'self'",
            "media-src data: blob: 'self'",
            "connect-src 'none'",
            "form-action 'none'",
            "frame-src 'none'",
            "worker-src 'none'",
            "object-src 'none'",
            "base-uri 'none'",
            "frame-ancestors 'self'");

    /** Fonctionnalités du navigateur fermées à une page. */
    public static final String PERMISSIONS = "camera=(), microphone=(), geolocation=(), payment=(), usb=(), "
            + "display-capture=(), clipboard-read=()";

    private PageContentPolicy() {
    }

    /** Les en-têtes de la politique, à poser sur toute réponse de contenu. */
    public static HttpHeaders headers(String contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(HttpHeaders.CONTENT_TYPE, contentType);
        headers.set("Content-Security-Policy", CSP);
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set(HttpHeaders.CACHE_CONTROL, "private, no-store");
        headers.set("Cross-Origin-Resource-Policy", "same-origin");
        headers.set("Permissions-Policy", PERMISSIONS);
        return headers;
    }

    /** Une réponse de contenu, avec la politique. */
    public static ResponseEntity<byte[]> ok(byte[] content, String contentType) {
        return new ResponseEntity<>(content, headers(contentType), HttpStatus.OK);
    }

    /**
     * La page d'erreur de la route publique : minimale, sans script, et <b>sous la même politique</b>. Elle
     * ne dit jamais si le lien a expiré, a été révoqué ou n'a jamais existé.
     */
    public static ResponseEntity<byte[]> notFound() {
        String html = "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\"><title>Page introuvable</title>"
                + "<style>body{font-family:system-ui,sans-serif;margin:0;display:grid;place-items:center;"
                + "min-height:100vh;background:#F5F6FA;color:#0F172A}p{color:#64748B}</style></head>"
                + "<body><main><h1>Page introuvable</h1><p>Ce lien n'est pas ou plus valide.</p></main></body></html>";
        return new ResponseEntity<>(html.getBytes(StandardCharsets.UTF_8), headers("text/html; charset=utf-8"),
                HttpStatus.NOT_FOUND);
    }
}
