package fr.claudegateway.runner.teams;

import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>L'onglet, amené sur SharePoint le temps d'un appel</b> (F-108 / SF-108-03).
 *
 * <h2>Pourquoi l'onglet doit bouger</h2>
 *
 * <p>L'arbitrage du PO fait appeler les API web de SharePoint <b>depuis la page</b>, avec la session
 * du navigateur — c'est ce qui évite tout jeton côté runner. Mais un appel {@code fetch} n'emporte la
 * session que vers <b>sa propre origine</b> : depuis {@code teams.microsoft.com}, SharePoint
 * refuserait. L'onglet est donc <b>navigué</b> vers l'origine du site (geste gardé par domaine,
 * SF-108-01), l'appel y est fait, puis <b>la vue est remise</b> où elle était (§4.7).</p>
 *
 * <h2>Deux temps : démarrer, puis relever</h2>
 *
 * <p>Une commande du protocole de débogage est bornée à dix secondes ; un dépôt de fichier, non. Le
 * script <b>démarre</b> l'opération et rend aussitôt un identifiant ; la relève vient ensuite lire le
 * résultat. Ce résultat ne porte <b>que</b> {@code ok}, {@code status}, {@code error} et un corps
 * passé par {@link SharePointProjection} : le digest de formulaire, les cookies et les en-têtes
 * restent dans la page.</p>
 *
 * <p><b>Forme éprouvée sur documentation, à confirmer sur poste réel</b> : API REST SharePoint
 * ({@code /_api/contextinfo}, {@code GetFolderByServerRelativePath}, {@code GetFileByServerRelativePath},
 * {@code Files/AddUsingPath}, {@code MoveTo}, {@code recycle}), telle que documentée publiquement.</p>
 */
public final class SharePointPage {

    /** Attente de l'arrivée sur l'origine du site, par pas. */
    static final int REACH_ATTEMPTS = 30;
    static final long REACH_STEP_MS = 500L;
    /** Pas de la relève d'une opération. */
    static final long POLL_STEP_MS = 300L;

    private final PageActions actions;
    private final BrowserLink.Sleeper sleeper;

    public SharePointPage(PageActions actions, BrowserLink.Sleeper sleeper) {
        this.actions = actions;
        this.sleeper = sleeper;
    }

    /**
     * Amène l'onglet sur l'origine de cet emplacement.
     *
     * @throws Refused si l'onglet atterrit sur une page d'identification ou n'atteint pas le site
     */
    public Visit open(SharePointLocation location) {
        String before = actions.currentUrl();
        if (location.sameOrigin(before)) {
            return new Visit(location, before, false);
        }
        actions.navigate(location.siteUrl() + "/_api/web/title");
        Visit visit = new Visit(location, before, true);
        String reached = "";
        for (int attempt = 0; attempt < REACH_ATTEMPTS; attempt++) {
            reached = actions.currentUrl();
            if (location.sameOrigin(reached)) {
                return visit;
            }
            sleep(REACH_STEP_MS);
        }
        visit.close();
        if (MicrosoftDomains.isSignIn(reached)) {
            throw new Refused(TeamsGapKind.SIGNED_OUT, "Microsoft demande de se reconnecter avant "
                    + "d'ouvrir " + location.label() + ". Le runner ne se connecte jamais : rouvrez "
                    + "Teams (ou ce site) dans le navigateur relié, reconnectez-vous, puis redemandez. "
                    + visit.viewport());
        }
        throw new Refused(TeamsGapKind.LOCATION_UNKNOWN, "L'onglet n'a pas atteint "
                + MicrosoftDomains.hostOf(location.origin()) + " : rien n'a été lu. "
                + visit.viewport());
    }

    /** Un passage de l'onglet sur un site — à refermer, pour remettre la vue. */
    public final class Visit implements AutoCloseable {

        private final SharePointLocation location;
        private final String before;
        private final boolean moved;
        private boolean restored;
        private String viewport = "";

        Visit(SharePointLocation location, String before, boolean moved) {
            this.location = location;
            this.before = before;
            this.moved = moved;
        }

        /**
         * Exécute une opération dans la page et attend son résultat.
         *
         * @param operation nom lisible, pour la trace (« lister les fichiers »)
         * @param body      corps JavaScript d'une fonction {@code async}, qui dispose de
         *                  {@code call(method, path, init)} et de {@code lit(texte)}
         * @param timeoutMs attente maximale du résultat
         */
        public Answer run(String operation, String body, long timeoutMs) {
            return run(operation, location, body, timeoutMs);
        }

        /**
         * Même chose, adressée au site d'un autre emplacement <b>de la même origine</b> (le OneDrive
         * personnel découvert depuis l'hôte, par exemple).
         */
        public Answer run(String operation, SharePointLocation target, String body, long timeoutMs) {
            if (!target.sameOrigin(location.origin())) {
                return Answer.failed(0, "opération adressée à une autre origine que l'onglet");
            }
            String id = "cg" + UUID.randomUUID().toString().replace("-", "");
            JsonNode started = actions.runScript(operation, startScript(id, target.siteUrl(),
                    operation, body));
            if (started == null || !id.equals(started.asText(""))) {
                return Answer.failed(0, "l'opération n'a pas pu démarrer dans la page");
            }
            long waited = 0L;
            while (true) {
                JsonNode value = actions.readScript(pollScript(id));
                if (value != null && value.isObject()) {
                    if (value.path("missing").asBoolean(false)) {
                        return Answer.failed(0, "la page a été rechargée pendant l'opération : "
                                + "son résultat est inconnu");
                    }
                    return Answer.of(value);
                }
                if (waited >= timeoutMs) {
                    return Answer.failed(0, "l'opération n'a pas rendu de résultat dans le délai");
                }
                sleep(POLL_STEP_MS);
                waited += POLL_STEP_MS;
            }
        }

        /** L'origine où l'onglet se trouve pendant ce passage. */
        public SharePointLocation location() {
            return location;
        }

        /** Ce qui a été fait de la vue de l'utilisateur, en clair. */
        public String viewport() {
            return viewport;
        }

        public PageActions actions() {
            return actions;
        }

        @Override
        public void close() {
            if (!moved || restored) {
                return;
            }
            restored = true;
            try {
                if (actions.restore(before)) {
                    viewport = "L'onglet relié a été amené sur " + MicrosoftDomains.hostOf(
                            location.origin()) + " le temps de l'opération, puis remis sur l'adresse "
                            + "où il était.";
                } else {
                    viewport = "L'onglet relié a été amené sur " + MicrosoftDomains.hostOf(
                            location.origin()) + " ; l'adresse d'avant n'était pas remettable : "
                            + "rouvrez Teams dans cet onglet.";
                }
            } catch (RuntimeException e) {
                viewport = "L'onglet relié a été amené sur " + MicrosoftDomains.hostOf(
                        location.origin()) + " et n'a PAS pu être remis : rouvrez Teams dans cet "
                        + "onglet.";
            }
        }
    }

    // ------------------------------------------------------------------ scripts

    /**
     * Le script de démarrage. Tout ce qui authentifie l'appel — la session, le digest — reste dans
     * cette fonction ; ce qui est rangé pour la relève ne porte que {@code ok}, {@code status},
     * {@code error} et un corps projeté.
     */
    static String startScript(String id, String siteUrl, String operation, String body) {
        return "(() => { /*cg-op:" + safeComment(operation) + "*/"
                + " const id = " + literal(id) + ";"
                + " const site = " + literal(siteUrl) + ";"
                + " window.__cgOps = window.__cgOps || {};"
                + " window.__cgOps[id] = { done: false };"
                + " " + SharePointProjection.script()
                + " const lit = (s) => encodeURIComponent(String(s).replace(/'/g, \"''\"));"
                + " const call = async (method, path, init) => {"
                + "  const headers = { 'Accept': 'application/json;odata=nometadata' };"
                + "  if (method !== 'GET') {"
                + "   const c = await fetch(site + '/_api/contextinfo', { method: 'POST',"
                + " credentials: 'same-origin', headers: { 'Accept': 'application/json;odata=nometadata' } });"
                + "   if (!c.ok) { return { ok: false, status: c.status, error: 'contextinfo' }; }"
                + "   const info = await c.json();"
                + "   const digest = info.FormDigestValue || (info.d && info.d.GetContextWebInformation"
                + " && info.d.GetContextWebInformation.FormDigestValue);"
                + "   if (!digest) { return { ok: false, status: c.status, error: 'digest absent' }; }"
                + "   headers['X-RequestDigest'] = digest;"
                + "  }"
                + "  if (init && init.contentType) { headers['Content-Type'] = init.contentType; }"
                + "  const r = await fetch(site + path, { method, credentials: 'same-origin', headers,"
                + " body: init ? init.body : undefined });"
                + "  let json = null;"
                + "  try { json = await r.json(); } catch (e) { json = null; }"
                + "  if (!r.ok) {"
                + "   const err = json && (json['odata.error'] || json.error);"
                + "   const message = err && err.message ? (err.message.value || err.message) : '';"
                + "   return { ok: false, status: r.status, error: String(message).slice(0, 300) };"
                + "  }"
                + "  return { ok: true, status: r.status, body: pick(json) };"
                + " };"
                + " (async () => { let out;"
                + "  try { out = await (async () => { " + body + " })(); }"
                + "  catch (e) { out = { ok: false, status: 0,"
                + " error: String((e && e.message) || e).slice(0, 300) }; }"
                + "  window.__cgOps[id] = { done: true, out: out };"
                + " })();"
                + " return id; })()";
    }

    /** La relève : {@code null} tant que l'opération tourne. */
    static String pollScript(String id) {
        return "(() => { /*cg-poll:" + id + "*/ const ops = window.__cgOps || {};"
                + " const o = ops[" + literal(id) + "]; if (!o) { return { missing: true }; }"
                + " if (!o.done) { return null; } delete ops[" + literal(id) + "]; return o.out; })()";
    }

    /** Un littéral JavaScript sûr : toute valeur venue d'un appel d'outil passe par ici. */
    static String literal(String value) {
        return TextNode.valueOf(value == null ? "" : value).toString();
    }

    /** « lire les métadonnées » → « lire-les-metadonnees » : le nom de l'opération dans le script. */
    static String safeComment(String value) {
        return TeamsTools.fold(value).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private void sleep(long millis) {
        if (sleeper != null) {
            sleeper.sleep(millis);
        }
    }

    /**
     * Le résultat d'une opération, tel que la page l'a rangé — <b>déjà projeté</b>, et reprojeté ici.
     */
    public record Answer(boolean ok, int status, JsonNode body, String error) {

        static Answer of(JsonNode value) {
            String error = value.path("error").asText("");
            return new Answer(value.path("ok").asBoolean(false), value.path("status").asInt(0),
                    SharePointProjection.pick(value.get("body")),
                    error.length() > 300 ? error.substring(0, 300) : error);
        }

        static Answer failed(int status, String error) {
            return new Answer(false, status, null, error);
        }
    }

    /** Un refus nommé : l'opération n'a pas pu avoir lieu, et on sait dire pourquoi. */
    public static final class Refused extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final TeamsGapKind kind;

        public Refused(TeamsGapKind kind, String message) {
            super(message);
            this.kind = kind;
        }

        public TeamsGapKind kind() {
            return kind;
        }
    }
}
