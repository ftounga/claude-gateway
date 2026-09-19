package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.TextNode;

/**
 * <b>Entrer réellement en réunion</b> (F-128 / SF-128-16) — les scripts injectés dans l'onglet Teams
 * du Chrome managé pour <b>cliquer « Rejoindre maintenant »</b> puis <b>détecter le vrai état
 * in-call</b>, et les fonctions pures qui les entourent.
 *
 * <h2>Pourquoi</h2>
 * <p>« Rejoindre » navigue vers l'URL et tombe sur l'écran de <b>pré-jonction</b> Teams (aperçu +
 * bouton de jonction). Le Chrome managé étant hors écran, personne ne clique le bouton ; et si l'on
 * démarre l'enregistrement là, la transition pré-join → in-call navigue et tue le capteur. On clique
 * donc le bouton nous-mêmes (best-effort) puis on n'entre « en réunion » qu'une fois un <b>signal
 * réel</b> observé — la présence des <b>contrôles d'appel</b> (« Quitter »/« Raccrocher »/hangup),
 * signal <b>distinctif</b> de l'in-call (le pré-join, lui, n'a que le bouton de jonction et un aperçu
 * de sa propre caméra ; « média actif » seul ne distingue donc pas). Un repli « média actif » (piste
 * audio live) est tenté ensuite.</p>
 *
 * <h2>DRAPEAU « À VALIDER SUR CALL RÉEL »</h2>
 * <p>Les libellés et sélecteurs DOM réels du bouton de jonction et des contrôles d'appel dépendent de
 * la version de Teams web ({@link #SELECTORS_FLAG}) : <b>fragiles</b>, à confirmer sur poste réel. Ici,
 * seules les fonctions <b>pures</b> (présence des sélecteurs/libellés dans les scripts, parseurs des
 * résultats) sont testées ; le comportement navigateur est validé en réunion réelle par le PO.</p>
 */
final class MeetingPresence {

    private MeetingPresence() {
    }

    /** Marque la fragilité « v2 » des sélecteurs/libellés (dépendants de la version Teams web). */
    static final String SELECTORS_FLAG = "v2 fragile — à valider sur call réel (2026-09-19)";

    /** Sélecteurs du bouton « Rejoindre maintenant » de la pré-jonction (data-tid Teams). */
    static final String[] JOIN_SELECTORS = {
            "[data-tid=\"prejoin-join-button\"]",
            "#prejoin-join-button",
            "button[data-tid=\"prejoin-join-button\"]"
    };

    /** Libellés (FR/EN, minuscules) du bouton de jonction — repli quand le data-tid a changé. */
    static final String[] JOIN_LABELS = {
            "rejoindre maintenant",
            "join now",
            "rejoindre l'appel",
            "demander à rejoindre",
            "ask to join",
            "rejoindre",
            "join"
    };

    /** Sélecteurs des contrôles d'appel présents uniquement <b>en réunion</b> (pas au pré-join). */
    static final String[] LEAVE_SELECTORS = {
            "[data-tid=\"hangup-button\"]",
            "[data-tid=\"call-hangup\"]",
            "#hangup-button",
            "[data-tid=\"calling-toolbar\"]",
            "[data-tid=\"call-controls\"]",
            "#call-controls",
            "[data-tid=\"roster-button\"]"
    };

    /** Libellés (FR/EN, minuscules) des contrôles de sortie d'appel — signal distinctif de l'in-call. */
    static final String[] LEAVE_LABELS = {
            "quitter",
            "raccrocher",
            "leave",
            "hang up"
    };

    /**
     * <b>Clique « Rejoindre maintenant »</b> (best-effort). Rend
     * {@code {clicked:true, reason:'clicked', matched:<sel>}} si un bouton de jonction a été trouvé et
     * cliqué ; {@code {clicked:false, reason:'already_in_call'}} si les contrôles d'appel sont déjà là
     * (rien à faire) ; {@code {clicked:false, reason:'absent'}} sinon. Synchrone, ne lève jamais.
     */
    static final String JOIN_NOW_SCRIPT = "(function(){try{"
            + "var leaveSel=" + jsArray(LEAVE_SELECTORS) + ";"
            + "var leaveLabels=" + jsArray(LEAVE_LABELS) + ";"
            + "var joinSel=" + jsArray(JOIN_SELECTORS) + ";"
            + "var joinLabels=" + jsArray(JOIN_LABELS) + ";"
            + "function vis(el){if(!el)return false;var r=el.getBoundingClientRect();"
            + "return !!(el.offsetParent!==null||r.width||r.height);}"
            + "function txt(el){return (((el.getAttribute&&el.getAttribute('aria-label'))||el.textContent)||'')"
            + ".trim().toLowerCase();}"
            + "function anyMatch(el,labels){var t=txt(el);for(var i=0;i<labels.length;i++){"
            + "if(t.indexOf(labels[i])>=0)return true;}return false;}"
            + "for(var i=0;i<leaveSel.length;i++){if(document.querySelector(leaveSel[i]))"
            + "return {clicked:false,reason:'already_in_call'};}"
            + "var btns=Array.prototype.slice.call(document.querySelectorAll('button,[role=button]'));"
            + "for(var j=0;j<btns.length;j++){if(vis(btns[j])&&anyMatch(btns[j],leaveLabels))"
            + "return {clicked:false,reason:'already_in_call'};}"
            + "for(var k=0;k<joinSel.length;k++){var el=document.querySelector(joinSel[k]);"
            + "if(el&&!el.disabled){el.click();return {clicked:true,reason:'clicked',matched:joinSel[k]};}}"
            + "for(var m=0;m<btns.length;m++){var b=btns[m];if(vis(b)&&!b.disabled&&anyMatch(b,joinLabels)){"
            + "b.click();return {clicked:true,reason:'clicked',matched:'label'};}}"
            + "return {clicked:false,reason:'absent'};"
            + "}catch(e){return {clicked:false,reason:'absent'};}})()";

    /**
     * <b>Sonde le VRAI état in-call</b>. Rend {@code {inCall:true, by:'controls'}} si les contrôles
     * d'appel (sortie/roster) sont présents ; {@code {inCall:true, by:'media'}} en repli si un média a
     * une piste audio <b>live</b> ; {@code {inCall:false, by:'none'}} sinon (typiquement au pré-join).
     * Synchrone, ne lève jamais, sûre à ré-évaluer à chaque créneau.
     */
    static final String IN_CALL_PROBE_SCRIPT = "(function(){try{"
            + "var leaveSel=" + jsArray(LEAVE_SELECTORS) + ";"
            + "var leaveLabels=" + jsArray(LEAVE_LABELS) + ";"
            + "function txt(el){return (((el.getAttribute&&el.getAttribute('aria-label'))||el.textContent)||'')"
            + ".trim().toLowerCase();}"
            + "for(var i=0;i<leaveSel.length;i++){if(document.querySelector(leaveSel[i]))"
            + "return {inCall:true,by:'controls'};}"
            + "var btns=Array.prototype.slice.call(document.querySelectorAll('button,[role=button]'));"
            + "for(var j=0;j<btns.length;j++){var t=txt(btns[j]);for(var k=0;k<leaveLabels.length;k++){"
            + "if(t.indexOf(leaveLabels[k])>=0)return {inCall:true,by:'controls'};}}"
            + "var media=Array.prototype.slice.call(document.querySelectorAll('audio,video'));"
            + "for(var m=0;m<media.length;m++){var el=media[m];var so=el.srcObject;"
            + "if(so&&so.getAudioTracks){var tr=so.getAudioTracks();for(var n=0;n<tr.length;n++){"
            + "if(tr[n].readyState==='live'&&tr[n].enabled)return {inCall:true,by:'media'};}}}"
            + "return {inCall:false,by:'none'};"
            + "}catch(e){return {inCall:false,by:'none'};}})()";

    // ------------------------------------------------------------------ parseurs purs

    /** Le motif du clic de jonction ({@code clicked}/{@code already_in_call}/{@code absent}). */
    static String clickReason(JsonNode result) {
        if (result == null) {
            return "absent";
        }
        return result.path("reason").asText("absent");
    }

    /** Vrai si le bouton de jonction a réellement été cliqué. */
    static boolean clicked(JsonNode result) {
        return result != null && result.path("clicked").asBoolean(false);
    }

    /** Vrai si la sonde a vu un signal réel d'in-call. */
    static boolean inCall(JsonNode result) {
        return result != null && result.path("inCall").asBoolean(false);
    }

    /** Par quel signal l'in-call a été détecté ({@code controls}/{@code media}/{@code none}). */
    static String detectedBy(JsonNode result) {
        if (result == null) {
            return "none";
        }
        return result.path("by").asText("none");
    }

    /** Encode une liste de chaînes en littéral tableau JS sûr (chaque élément échappé comme JSON). */
    private static String jsArray(String... items) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < items.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(TextNode.valueOf(items[i]).toString());
        }
        return sb.append(']').toString();
    }
}
