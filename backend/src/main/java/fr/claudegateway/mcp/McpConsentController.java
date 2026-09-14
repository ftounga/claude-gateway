package fr.claudegateway.mcp;

import java.util.Set;
import java.util.TreeSet;

import org.springframework.http.MediaType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.stereotype.Controller;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Écran de consentement OAuth (F-112 / SF-112-02). Page d'<b>infrastructure OAuth</b> rendue par le
 * backend : le serveur d'autorisation y redirige l'utilisateur connecté quand un client MCP demande
 * des périmètres. Elle affiche le nom du client et les périmètres demandés <b>en français clair</b>
 * (cadrage §4), puis renvoie l'accord au serveur d'autorisation.
 *
 * <p>Ce n'est pas un écran produit Angular : l'écran produit « IA connectées » (gestion des
 * connexions) est livré en SF-112-03. La sélection des postes accessibles s'ajoutera au consentement
 * quand les postes seront branchés (SF-112-03).</p>
 */
@Controller
public class McpConsentController {

    public static final String CONSENT_PAGE_URI = "/oauth2/consent";

    private final RegisteredClientRepository registeredClientRepository;

    public McpConsentController(RegisteredClientRepository registeredClientRepository) {
        this.registeredClientRepository = registeredClientRepository;
    }

    @GetMapping(value = CONSENT_PAGE_URI, produces = MediaType.TEXT_HTML_VALUE)
    @ResponseBody
    public String consent(
            @RequestParam("client_id") String clientId,
            @RequestParam("scope") String scope,
            @RequestParam("state") String state,
            HttpServletRequest request) {

        RegisteredClient client = registeredClientRepository.findByClientId(clientId);
        String clientName = client != null && client.getClientName() != null
                ? client.getClientName() : clientId;

        Set<String> requestedScopes = new TreeSet<>();
        for (String s : scope.split(" ")) {
            if (!s.isBlank()) {
                requestedScopes.add(s.trim());
            }
        }

        String action = esc(request.getContextPath() + "/oauth2/authorize");
        StringBuilder checkboxes = new StringBuilder();
        for (String s : requestedScopes) {
            checkboxes.append("<label class=\"scope\"><input type=\"checkbox\" name=\"scope\" value=\"")
                    .append(esc(s)).append("\" checked> ")
                    .append("<span class=\"scope-name\">").append(esc(McpScopes.label(s)))
                    .append("</span> <code>").append(esc(s)).append("</code></label>");
        }

        return "<!doctype html><html lang=\"fr\"><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<title>Autoriser " + esc(clientName) + "</title>"
                + "<style>"
                + "body{font-family:system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;"
                + "background:#0f1e35;color:#0f1e35;margin:0;padding:24px;display:flex;"
                + "justify-content:center;align-items:flex-start}"
                + ".card{background:#fff;max-width:520px;width:100%;border-radius:12px;padding:28px;"
                + "box-shadow:0 8px 30px rgba(0,0,0,.25);margin-top:32px}"
                + "h1{font-size:20px;margin:0 0 4px}p{color:#4a5568;font-size:14px;line-height:1.5}"
                + ".scope{display:flex;align-items:flex-start;gap:8px;padding:10px 0;"
                + "border-top:1px solid #e2e8f0;font-size:14px}"
                + ".scope-name{flex:1}code{background:#f1f5f9;padding:1px 6px;border-radius:4px;"
                + "font-size:12px;color:#475569}"
                + ".actions{display:flex;gap:12px;margin-top:20px}"
                + "button{flex:1;padding:12px;border:0;border-radius:8px;font-size:15px;cursor:pointer}"
                + ".approve{background:#e8590c;color:#fff}.deny{background:#e2e8f0;color:#1a202c}"
                + "</style></head><body><div class=\"card\">"
                + "<h1>Autoriser " + esc(clientName) + "</h1>"
                + "<p>Cette IA demande à agir sur votre compte avec les accès suivants. "
                + "Ce qu'un outil renvoie part chez le fournisseur de l'IA connectée : "
                + "n'accordez que ce qui est nécessaire.</p>"
                + "<form method=\"post\" action=\"" + action + "\">"
                + "<input type=\"hidden\" name=\"client_id\" value=\"" + esc(clientId) + "\">"
                + "<input type=\"hidden\" name=\"state\" value=\"" + esc(state) + "\">"
                + checkboxes
                + "<div class=\"actions\">"
                + "<button type=\"submit\" class=\"approve\">Autoriser</button>"
                + "<button type=\"submit\" class=\"deny\" formnovalidate name=\"\" value=\"\" "
                + "onclick=\"this.form.querySelectorAll('input[name=scope]').forEach(c=>c.checked=false)\">Refuser</button>"
                + "</div></form></div></body></html>";
    }

    /** Échappement HTML minimal (texte et attributs entre guillemets doubles). */
    private static String esc(String value) {
        if (value == null) {
            return "";
        }
        return value
                .replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }
}
