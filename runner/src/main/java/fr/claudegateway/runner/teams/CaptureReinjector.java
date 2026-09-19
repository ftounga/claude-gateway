package fr.claudegateway.runner.teams;

import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.runner.diag.RunnerDiag;

/**
 * <b>Le ré-armement de la capture d'onglet</b> (F-128 / SF-128-12) — ce qui rend l'enregistrement
 * <b>robuste à la navigation</b> de Teams.
 *
 * <h2>Le défaut corrigé</h2>
 * <p>Le capteur de {@link MeetingTabCapture} vit dans un global de la page —
 * {@code window.__cgMeetingCapture}. Teams web est une <b>SPA</b> qui <b>recharge la page</b> lors de
 * la transition pré-jonction → en réunion : le contexte JS est détruit, le global disparaît, et
 * l'arrêt de capture ne trouve plus rien ({@code no_active_capture}, {@code audio_bytes=0},
 * {@code image_count=0}) — exactement le symptôme observé en prod CAGIP (2026-09-19).</p>
 *
 * <h2>Le remède</h2>
 * <p>Le runner pilote déjà la page par CDP ; il active le domaine {@code Page} et s'abonne à ses
 * événements de cycle de vie ({@code Page.loadEventFired}, {@code Page.frameNavigated} du cadre
 * principal) pour savoir <b>quand</b> une navigation a eu lieu. À chaque chargement, on
 * <b>re-résout la cible CDP courante</b> ({@link Reattacher}, SF-128-13) et on s'y rattache
 * <b>à neuf</b> — car l'onglet de réunion, après la navigation Teams, est une <b>nouvelle cible
 * CDP</b> : réutiliser le lien capturé au démarrage échouerait (socket fermé) alors que le
 * navigateur, lui, est joignable. Sur cette cible fraîche, on <b>sonde</b> l'enregistrement
 * ({@link MeetingTabCapture#ACTIVE_PROBE_SCRIPT}) ; s'il n'est plus actif, on <b>ré-injecte</b>
 * {@link MeetingTabCapture#START_SCRIPT} avec un <b>geste utilisateur simulé</b> — sans lequel
 * {@code getDisplayMedia} refuse — l'auto-accept (SF-122-05) évitant la boîte de dialogue. Une
 * fois la page en réunion stabilisée, le dernier ré-armement persiste jusqu'à l'arrêt.</p>
 *
 * <h2>La cause corrigée par SF-128-13</h2>
 * <p>SF-128-12 supposait que « le socket de débogage survit à une navigation dans le même onglet ».
 * Le diagnostic F-132 (prod CAGIP 2026-09-19) a montré le contraire : la ré-injection concluait
 * {@code browser_unreachable} <b>alors que la boucle Vigie atteignait le Chrome au même instant</b>
 * ({@code reachable:true}) — parce qu'elle <b>re-résout</b> sa cible à chaque tick. On aligne donc
 * la ré-injection sur le <b>même chemin de résolution</b> que la Vigie
 * ({@link BrowserLink#attach} / {@link BrowserTargets#teamsTab}, via {@link TeamsSession#link()}) :
 * elle ne conclut à l'injoignabilité <b>que si aucune cible Teams n'est réellement joignable</b>.</p>
 *
 * <h2>Best-effort strict</h2>
 * <p>Aucune exception ne remonte de la boucle d'événements : une ré-attache sans cible est
 * <b>diagnostiquée</b> (F-132, {@code capture/reattach}), une injection qui échoue sur une cible
 * pourtant retrouvée l'est aussi ({@code capture/reinject}) — jamais fatal. Le garde de décision est
 * pur ({@link MeetingTabCapture#shouldReinject}).</p>
 *
 * <p><b>DRAPEAU « À VALIDER SUR CALL RÉEL ».</b> Le rechargement réel de la page de réunion et le
 * comportement de {@code getDisplayMedia} après ré-injection ne se vérifient qu'en réunion réelle ;
 * ici, seules la décision, la re-résolution de cible et le câblage CDP (contre un faux) sont éprouvés.</p>
 */
final class CaptureReinjector {

    /** L'événement CDP « le document a fini de charger » : une navigation/rechargement a eu lieu. */
    static final String LOAD_EVENT = "Page.loadEventFired";

    /** L'événement CDP « un cadre a navigué » — on n'agit que sur le cadre <b>principal</b>. */
    static final String FRAME_NAVIGATED = "Page.frameNavigated";

    /** Comment (re)parler à la page : évaluer un script, éventuellement avec geste utilisateur. */
    @FunctionalInterface
    interface Eval {
        JsonNode eval(String script, boolean userGesture);
    }

    /**
     * <b>La re-résolution de la cible</b> (SF-128-13) : après une navigation, retrouve l'onglet
     * Teams/réunion <b>courant</b> et rend un {@link Eval} rattaché <b>à neuf</b> à cette cible — ou
     * {@code null} si aucun onglet Teams n'est réellement joignable. Injectée pour réutiliser le
     * chemin de résolution de la boucle Vigie sans dépendre d'un navigateur dans les tests.
     */
    @FunctionalInterface
    interface Reattacher {
        Eval reattach();
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final CdpConnection connection;
    private final Reattacher reattacher;

    private volatile boolean armed;
    private int reinjections;

    CaptureReinjector(CdpConnection connection, Reattacher reattacher) {
        this.connection = connection;
        this.reattacher = reattacher;
    }

    /**
     * Arme le ré-injecteur : active le domaine {@code Page} et s'abonne aux événements de chargement.
     * Best-effort — un {@code Page.enable} qui échoue n'empêche pas l'abonnement, et n'est jamais fatal
     * pour la capture déjà démarrée.
     */
    void arm() {
        try {
            connection.send(CdpCommands.PAGE_ENABLE, mapper.createObjectNode());
        } catch (RuntimeException e) {
            // Page.enable a pu déjà être activé (NetworkObserver) ou échouer : sans conséquence, on
            // s'abonne quand même — l'événement arrivera si le domaine est actif d'une façon ou d'une autre.
        }
        connection.onEvent(LOAD_EVENT, event -> onNavigation());
        connection.onEvent(FRAME_NAVIGATED, this::onFrameNavigated);
        armed = true;
    }

    /** Désarme : les événements de chargement reçus après coup sont ignorés (arrêt/nettoyage). */
    void disarm() {
        armed = false;
    }

    /** Combien de ré-injections ont été faites (diagnostic / tests). */
    int reinjections() {
        return reinjections;
    }

    boolean armed() {
        return armed;
    }

    /** Un cadre a navigué : on ignore les sous-cadres (iframes tierces), on n'agit que sur la racine. */
    void onFrameNavigated(JsonNode event) {
        if (event != null && event.path("frame").hasNonNull("parentId")) {
            return;
        }
        onNavigation();
    }

    /**
     * Le cœur (SF-128-13) : après une navigation, <b>re-résoudre la cible CDP courante</b> et s'y
     * rattacher à neuf, puis — si le ré-armement est armé et qu'aucun enregistrement n'est actif —
     * ré-injecter le capteur sur cette cible fraîche. Best-effort strict.
     *
     * <p>On ne réutilise <b>jamais</b> le lien capturé au démarrage : après la navigation Teams,
     * l'onglet de réunion est une nouvelle cible CDP. La re-résolution ({@link Reattacher}) suit le
     * chemin de la boucle Vigie ; elle ne conclut à l'injoignabilité que si <b>aucune</b> cible Teams
     * n'est joignable.</p>
     */
    void onNavigation() {
        if (!armed) {
            return;
        }
        // Re-résolution de la cible courante (à NEUF), exactement comme la boucle Vigie.
        Eval eval;
        try {
            eval = reattacher.reattach();
        } catch (RuntimeException e) {
            eval = null;
        }
        if (eval == null) {
            // Aucune cible Teams réellement joignable : le SEUL cas d'injoignabilité (F-132).
            RunnerDiag.warn("capture", "reattach", null,
                    Map.of("result", "error", "reason", "no_teams_tab"));
            return;
        }
        // Diagnostic F-132 : la cible a été retrouvée et rattachée à neuf — pour le VOIR sur call réel.
        RunnerDiag.info("capture", "reattach", null, Map.of("result", "ok"));
        boolean active;
        try {
            JsonNode probe = eval.eval(MeetingTabCapture.ACTIVE_PROBE_SCRIPT, false);
            active = probe != null && probe.path("active").asBoolean(false);
        } catch (RuntimeException e) {
            reinjectFailed();
            return;
        }
        if (!MeetingTabCapture.shouldReinject(armed, active)) {
            return;
        }
        try {
            eval.eval(MeetingTabCapture.START_SCRIPT, true);
            reinjections++;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("result", "ok");
            fields.put("count", reinjections);
            // Diagnostic F-132 : une ré-injection a eu lieu — un compte, jamais le média (cadrage §3).
            RunnerDiag.info("capture", "reinject", null, fields);
        } catch (RuntimeException e) {
            reinjectFailed();
        }
    }

    private void reinjectFailed() {
        // Cible retrouvée mais devenue injoignable pendant la sonde/l'injection : diagnostiqué, jamais
        // fatal pour la boucle. (L'absence totale de cible, elle, est diagnostiquée en capture/reattach.)
        RunnerDiag.warn("capture", "reinject", null,
                Map.of("result", "error", "reason", "browser_unreachable"));
    }
}
