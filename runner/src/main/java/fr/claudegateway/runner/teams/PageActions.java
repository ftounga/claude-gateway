package fr.claudegateway.runner.teams;

import java.util.List;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>Les gestes d'action, et leurs gardes</b> (F-108 / SF-108-01, cadrage §4).
 *
 * <p>F-88 avait posé {@code PageGestures} : trois verbes de <i>lecture</i> ({@code scroll},
 * {@code nudge}, {@code show}), tous passés par {@code Runtime.evaluate}, sans jamais piloter la
 * page. F-108 ouvre les gestes qui <b>agissent</b> — naviguer, cliquer, taper, déposer un fichier,
 * diriger un téléchargement — et c'est une décision de sécurité, prise par le PO, assortie de gardes
 * qui ne se négocient pas. Cette classe est l'unique endroit d'où part un geste d'action ; toutes les
 * gardes sont ici, appliquées <b>avant</b> émission.</p>
 *
 * <ol>
 *   <li><b>Domaine (§4.1)</b> : le geste ne part que si l'onglet est sur un domaine Microsoft de la
 *       liste close ({@link MicrosoftDomains}). Une navigation vise une URL autorisée ; tout autre
 *       geste relit l'adresse courante et refuse si l'onglet a quitté la liste (redirection).</li>
 *   <li><b>Identification (§4.2)</b> : jamais un clic ni une saisie sur une page de connexion. Une
 *       session expirée n'appelle pas une tentative de connexion — elle rend le manque « rouvrez
 *       Teams et reconnectez-vous ».</li>
 *   <li><b>Mot de passe (§4.3)</b> : jamais de saisie dans un champ {@code type="password"}, ni de
 *       lecture de sa valeur.</li>
 *   <li><b>Trace (§4.6)</b> : chaque geste est journalisé — outil, domaine, action, cible nommée,
 *       résultat —, jamais le contenu d'un champ saisi.</li>
 *   <li><b>Vue (§4.7)</b> : ce que la navigation a déplacé peut être remis ({@link #restore(String)}),
 *       et ce qui a été fait est rendu dans le résultat.</li>
 * </ol>
 */
public final class PageActions {

    /** Lit l'adresse de l'onglet — la même porte que {@code PageGestures}, déjà autorisée. */
    private static final String CURRENT_URL = "(() => location.href)()";

    private final CdpConnection connection;
    private final BrowserLink.Sleeper sleeper;
    private final Consumer<GestureRecord> journal;
    private final ObjectMapper mapper = new ObjectMapper();

    /** À partir de la liaison — la forme courante en production. */
    public PageActions(BrowserLink link, BrowserLink.Sleeper sleeper, Consumer<GestureRecord> journal) {
        this(link.connection(), sleeper, journal);
    }

    /**
     * À partir de la socket directement — la porte des tests et de tout appelant qui tient déjà la
     * connexion : {@code PageActions} n'a besoin que d'elle pour émettre.
     */
    public PageActions(CdpConnection connection, BrowserLink.Sleeper sleeper,
            Consumer<GestureRecord> journal) {
        this.connection = connection;
        this.sleeper = sleeper;
        this.journal = journal == null ? record -> { } : journal;
    }

    /** L'adresse courante de l'onglet, telle que la page la connaît. */
    public String currentUrl() {
        JsonNode value = evaluate(CURRENT_URL);
        return value == null || !value.isTextual() ? "" : value.asText("");
    }

    /**
     * <b>Navigue</b> l'onglet vers une URL Microsoft autorisée. La cible est jugée <b>avant</b>
     * émission : hors liste ou page d'identification, rien ne part.
     *
     * @return l'adresse atteinte (ce qui a été fait est dit)
     * @throws BrowserLinkException {@code DOMAIN_REFUSED} / {@code SIGN_IN_REFUSED} si la cible est
     *         refusée
     */
    public String navigate(String url) {
        String target = url == null ? "" : url.strip();
        assertDestinationAllowed(target);
        ObjectNode params = mapper.createObjectNode();
        params.put("url", target);
        connection.send(CdpCommands.PAGE_NAVIGATE, params);
        settle();
        String reached = currentUrl();
        record("navigate", MicrosoftDomains.hostOf(target), target, "atteint " + reached);
        return reached;
    }

    /**
     * <b>Clique</b> l'élément désigné par un sélecteur, sur la page courante. Le domaine courant est
     * vérifié avant émission (une redirection hors liste refuse le geste).
     *
     * @return vrai si l'élément a été trouvé et cliqué
     */
    public boolean click(String selector) {
        assertCurrentPageAllowed("click");
        String needle = selector == null ? "" : selector.strip();
        boolean clicked = evaluateBoolean(clickScript(needle));
        settle();
        record("click", currentDomain(), needle, clicked ? "cliqué" : "élément introuvable");
        return clicked;
    }

    /**
     * <b>Tape</b> du texte dans le champ actif de la page — <b>refusé</b> si ce champ est de type
     * mot de passe (§4.3). La valeur d'un tel champ n'est jamais lue.
     *
     * @throws BrowserLinkException {@code PASSWORD_FIELD_REFUSED} si le champ actif est un mot de passe
     */
    public void type(String text) {
        assertCurrentPageAllowed("type");
        if (activeFieldIsPassword()) {
            throw new BrowserLinkException(BrowserLinkException.PASSWORD_FIELD_REFUSED,
                    "Saisie refusée : le champ actif est un champ mot de passe. Le runner ne tape "
                            + "jamais dans un champ de ce type, et n'en lit jamais la valeur.");
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("text", text == null ? "" : text);
        connection.send(CdpCommands.INSERT_TEXT, params);
        // §4.6 : on trace QU'une saisie a eu lieu et sa longueur, jamais son contenu.
        record("type", currentDomain(), "champ actif",
                "saisie de " + (text == null ? 0 : text.length()) + " caractère(s)");
    }

    /**
     * <b>Dépose</b> des fichiers de la machine dans le champ de dépôt désigné (§5.1). Passe par
     * {@code DOM.setFileInputFiles} — jamais par une boîte de dialogue système, qu'on ne saurait
     * piloter. Le domaine courant est vérifié avant émission.
     *
     * @return vrai si le champ de dépôt a été trouvé
     */
    public boolean setFileInputFiles(String selector, List<String> absolutePaths) {
        assertCurrentPageAllowed("drop");
        String needle = selector == null ? "" : selector.strip();
        JsonNode document = connection.send(CdpCommands.GET_DOCUMENT, mapper.createObjectNode());
        int rootNodeId = document == null ? 0 : document.path("root").path("nodeId").asInt(0);
        ObjectNode query = mapper.createObjectNode();
        query.put("nodeId", rootNodeId);
        query.put("selector", needle);
        JsonNode found = connection.send(CdpCommands.QUERY_SELECTOR, query);
        int nodeId = found == null ? 0 : found.path("nodeId").asInt(0);
        if (nodeId <= 0) {
            record("drop", currentDomain(), needle, "champ de dépôt introuvable");
            return false;
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("nodeId", nodeId);
        ArrayNode files = params.putArray("files");
        if (absolutePaths != null) {
            absolutePaths.forEach(files::add);
        }
        connection.send(CdpCommands.SET_FILE_INPUT_FILES, params);
        record("drop", currentDomain(), needle,
                "déposé " + (absolutePaths == null ? 0 : absolutePaths.size()) + " fichier(s)");
        return true;
    }

    /**
     * <b>Dirige les téléchargements</b> vers un dossier de la machine (§5.2). C'est <b>Chrome</b> qui
     * téléchargera ensuite : l'adresse signée d'un enregistrement ne passe jamais par notre code.
     *
     * <p>Ce n'est pas un geste dans la page mais un réglage du navigateur : il n'est donc pas gardé
     * par le domaine courant. Il est tracé comme les autres.</p>
     */
    public void setDownloadDirectory(String absoluteDir) {
        ObjectNode params = mapper.createObjectNode();
        params.put("behavior", "allowAndName");
        params.put("downloadPath", absoluteDir == null ? "" : absoluteDir);
        connection.send(CdpCommands.SET_DOWNLOAD_BEHAVIOR, params);
        record("download_dir", "", absoluteDir == null ? "" : absoluteDir,
                "téléchargements dirigés vers le dossier de travail du volet");
    }

    /** Remet l'onglet sur une adresse antérieure (§4.7), si elle est encore autorisée. */
    public boolean restore(String previousUrl) {
        if (previousUrl == null || previousUrl.isBlank() || !MicrosoftDomains.isAllowed(previousUrl)) {
            return false;
        }
        navigate(previousUrl);
        return true;
    }

    // ------------------------------------------------------------------ gardes

    /** Juge une DESTINATION de navigation : hors liste ou identification, rien ne part. */
    private static void assertDestinationAllowed(String url) {
        if (MicrosoftDomains.isSignIn(url)) {
            throw new BrowserLinkException(BrowserLinkException.SIGN_IN_REFUSED,
                    "Navigation refusée vers une page d'identification Microsoft : le runner ne se "
                            + "connecte jamais. Rouvrez Teams et reconnectez-vous, puis redemandez.");
        }
        if (!MicrosoftDomains.isAllowed(url)) {
            throw new BrowserLinkException(BrowserLinkException.DOMAIN_REFUSED,
                    "Navigation refusée : « " + shorten(url) + " » n'est pas un domaine Microsoft "
                            + "autorisé. Les gestes du runner sont bornés à la liste close du "
                            + "cadrage ; hors d'elle, rien ne part.");
        }
    }

    /** Juge la PAGE COURANTE avant un geste qui n'est pas une navigation. */
    private void assertCurrentPageAllowed(String action) {
        String url = currentUrl();
        if (MicrosoftDomains.isSignIn(url)) {
            throw new BrowserLinkException(BrowserLinkException.SIGN_IN_REFUSED,
                    "Geste « " + action + " » refusé : l'onglet est sur une page d'identification. "
                            + "Rouvrez Teams et reconnectez-vous, puis redemandez.");
        }
        if (!MicrosoftDomains.isAllowed(url)) {
            throw new BrowserLinkException(BrowserLinkException.DOMAIN_REFUSED,
                    "Geste « " + action + " » refusé : l'onglet a quitté les domaines Microsoft "
                            + "autorisés (« " + shorten(url) + " »). Aucun geste hors liste.");
        }
    }

    private String currentDomain() {
        return MicrosoftDomains.hostOf(currentUrl());
    }

    /**
     * Vrai si le champ actif de la page est un champ mot de passe. Le script ne rend <b>que</b> le
     * type du champ actif — jamais sa valeur (§4.3).
     */
    private boolean activeFieldIsPassword() {
        JsonNode value = evaluate(
                "(() => { const el = document.activeElement;"
                        + " return el && el.tagName === 'INPUT'"
                        + " && (el.type || '').toLowerCase() === 'password'; })()");
        return value != null && value.asBoolean(false);
    }

    // ------------------------------------------------------------------ exécution

    private JsonNode evaluate(String expression) {
        ObjectNode params = mapper.createObjectNode();
        params.put("expression", expression);
        params.put("returnByValue", true);
        JsonNode result = connection.send(CdpCommands.EVALUATE, params);
        return result == null ? null : result.path("result").get("value");
    }

    private boolean evaluateBoolean(String expression) {
        JsonNode value = evaluate(expression);
        return value != null && value.asBoolean(false);
    }

    private void settle() {
        if (sleeper != null) {
            sleeper.sleep(BrowserLink.SCROLL_SETTLE_MS);
        }
    }

    private void record(String action, String domain, String target, String result) {
        journal.accept(new GestureRecord(action, domain, target, result));
    }

    /** Le script du clic : trouver l'élément et le cliquer. Le sélecteur est injecté en littéral. */
    private static String clickScript(String selector) {
        return "(() => { const el = document.querySelector(" + TextNode.valueOf(selector).toString()
                + "); if (!el) { return false; } el.click(); return true; })()";
    }

    private static String shorten(String url) {
        String value = url == null ? "" : url.strip();
        return value.length() <= 80 ? value : value.substring(0, 80) + "…";
    }

    /**
     * Une ligne de journal d'un geste d'action (§4.6). Elle porte ce qu'on veut pouvoir relire — quoi,
     * où, sur quelle cible, avec quel résultat — et <b>jamais</b> le contenu d'un champ saisi.
     */
    public record GestureRecord(String action, String domain, String target, String result) {
    }
}
