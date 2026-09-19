package fr.claudegateway.runner.teams;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Les scripts d'entrée en réunion (F-128 / SF-128-16) — parties <b>pures</b> : présence des sélecteurs
 * et libellés FR/EN attendus dans les scripts injectés, drapeau de fragilité, et parseurs des
 * résultats. Le comportement navigateur réel (clic, détection) reste « À VALIDER SUR CALL RÉEL ».
 */
class MeetingPresenceTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("le script de clic cherche le bouton de jonction par sélecteurs ET libellés FR/EN")
    void joinScriptContainsSelectorsAndLabels() {
        String script = MeetingPresence.JOIN_NOW_SCRIPT;
        assertTrue(script.contains("prejoin-join-button"), "le data-tid du bouton de jonction");
        assertTrue(script.contains("rejoindre maintenant"), "libellé FR");
        assertTrue(script.contains("join now"), "libellé EN");
        assertTrue(script.contains(".click()"), "il clique réellement l'élément trouvé");
        assertTrue(script.contains("already_in_call"), "déjà in-call → on ne re-clique pas");
        assertTrue(script.contains("absent"), "aucun bouton → absent");
    }

    @Test
    @DisplayName("le script de sonde in-call cherche les contrôles d'appel puis le média actif")
    void inCallProbeContainsCallControlsAndMediaFallback() {
        String script = MeetingPresence.IN_CALL_PROBE_SCRIPT;
        assertTrue(script.contains("hangup-button"), "contrôle d'appel (hangup)");
        assertTrue(script.contains("quitter"), "libellé de sortie FR");
        assertTrue(script.contains("leave"), "libellé de sortie EN");
        assertTrue(script.contains("getAudioTracks"), "repli : média actif (piste audio live)");
        assertTrue(script.contains("'controls'") && script.contains("'media'"),
                "la sonde dit PAR QUEL signal l'in-call a été détecté");
    }

    @Test
    @DisplayName("le drapeau de fragilité v2 est présent")
    void fragilityFlagIsPresent() {
        assertFalse(MeetingPresence.SELECTORS_FLAG.isBlank(), "SELECTORS_FLAG marque la fragilité v2");
    }

    @Test
    @DisplayName("parseur du clic : reason et clicked")
    void parsesClickResult() {
        ObjectNode clicked = mapper.createObjectNode();
        clicked.put("clicked", true).put("reason", "clicked");
        assertEquals("clicked", MeetingPresence.clickReason(clicked));
        assertTrue(MeetingPresence.clicked(clicked));

        ObjectNode absent = mapper.createObjectNode();
        absent.put("clicked", false).put("reason", "absent");
        assertEquals("absent", MeetingPresence.clickReason(absent));
        assertFalse(MeetingPresence.clicked(absent));

        assertEquals("absent", MeetingPresence.clickReason(null), "un résultat absent vaut 'absent'");
        assertFalse(MeetingPresence.clicked(null));
    }

    @Test
    @DisplayName("parseur de la sonde : inCall et detectedBy")
    void parsesInCallProbe() {
        ObjectNode inCall = mapper.createObjectNode();
        inCall.put("inCall", true).put("by", "controls");
        assertTrue(MeetingPresence.inCall(inCall));
        assertEquals("controls", MeetingPresence.detectedBy(inCall));

        ObjectNode none = mapper.createObjectNode();
        none.put("inCall", false).put("by", "none");
        assertFalse(MeetingPresence.inCall(none));
        assertEquals("none", MeetingPresence.detectedBy(none));

        assertFalse(MeetingPresence.inCall(null), "une sonde absente vaut pas-in-call");
        assertEquals("none", MeetingPresence.detectedBy(null));
    }
}
