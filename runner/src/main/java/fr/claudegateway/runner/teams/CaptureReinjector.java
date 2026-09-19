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
 * <p>Le runner pilote déjà la page par CDP ; le socket de débogage <b>survit</b> à une navigation
 * dans le <b>même onglet</b>. On active donc le domaine {@code Page} et on s'abonne à ses événements
 * de cycle de vie ({@code Page.loadEventFired}, {@code Page.frameNavigated} du cadre principal). À
 * chaque chargement, on <b>sonde</b> l'enregistrement ({@link MeetingTabCapture#ACTIVE_PROBE_SCRIPT}) ;
 * s'il n'est plus actif, on <b>ré-injecte</b> {@link MeetingTabCapture#START_SCRIPT} avec un
 * <b>geste utilisateur simulé</b> — sans lequel {@code getDisplayMedia} refuse — l'auto-accept
 * (SF-122-05) évitant la boîte de dialogue. Une fois la page en réunion stabilisée, le dernier
 * ré-armement persiste jusqu'à l'arrêt.</p>
 *
 * <h2>Best-effort strict</h2>
 * <p>Aucune exception ne remonte de la boucle d'événements : un Chrome injoignable pendant un
 * ré-armement est <b>diagnostiqué</b> (F-132, {@code capture/reinject}) puis avalé — jamais fatal.
 * Le garde de décision est pur ({@link MeetingTabCapture#shouldReinject}).</p>
 *
 * <p><b>DRAPEAU « À VALIDER SUR CALL RÉEL ».</b> Le rechargement réel de la page de réunion et le
 * comportement de {@code getDisplayMedia} après ré-injection ne se vérifient qu'en réunion réelle ;
 * ici, seules la décision et le câblage CDP (contre un faux) sont éprouvés.</p>
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

    private final ObjectMapper mapper = new ObjectMapper();
    private final CdpConnection connection;
    private final Eval eval;

    private volatile boolean armed;
    private int reinjections;

    CaptureReinjector(CdpConnection connection, Eval eval) {
        this.connection = connection;
        this.eval = eval;
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
     * Le cœur : après une navigation, si le ré-armement est armé et qu'aucun enregistrement n'est
     * actif, ré-injecter le capteur. Best-effort strict.
     */
    void onNavigation() {
        if (!armed) {
            return;
        }
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
        // Chrome injoignable pendant la sonde ou la ré-injection : diagnostiqué, jamais fatal pour la boucle.
        RunnerDiag.warn("capture", "reinject", null,
                Map.of("result", "error", "reason", "browser_unreachable"));
    }
}
