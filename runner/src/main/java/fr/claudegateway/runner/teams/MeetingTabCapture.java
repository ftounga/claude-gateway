package fr.claudegateway.runner.teams;

import java.util.Base64;
import java.util.List;

/**
 * <b>La capture d'onglet d'une réunion</b> (F-128 / SF-128-02) — les scripts injectés dans l'onglet
 * Teams du Chrome managé, et les fonctions pures qui les entourent.
 *
 * <h2>Pourquoi dans la page</h2>
 * <p>Seule la page a accès à l'<b>audio de l'onglet</b> ({@code getDisplayMedia}, avec
 * {@code preferCurrentTab}), au <b>micro</b> ({@code getUserMedia}), au <b>mixage</b> (WebAudio) et à
 * l'<b>enregistrement</b> ({@code MediaRecorder}). Le runner ne fait que <b>piloter</b> par CDP :
 * il injecte le démarrage (avec un geste utilisateur simulé — {@code userGesture:true} — sans lequel
 * {@code getDisplayMedia} refuse), puis, à l'arrêt, récupère les octets par tranches.</p>
 *
 * <h2>DRAPEAU « À VALIDER SUR CALL RÉEL »</h2>
 * <p>Le comportement navigateur (invite de partage d'onglet, invite micro, mixage, enregistrement) ne
 * peut être vérifié qu'en réunion réelle. Ici, seules les fonctions <b>pures</b> (assemblage des
 * tranches, décodage base64, fabrique du script de lecture) sont testées.</p>
 */
final class MeetingTabCapture {

    private MeetingTabCapture() {
    }

    /** Taille d'une tranche de lecture (caractères base64) : borne les retours de {@code Runtime.evaluate}. */
    static final int PULL_CHUNK_CHARS = 2 * 1024 * 1024;

    /** Type de média enregistré (Opus dans un conteneur WebM) : léger, natif {@code MediaRecorder}. */
    static final String MEDIA_TYPE = "audio/webm";

    /**
     * Démarre la capture : audio de l'onglet + micro, mixés, enregistrés. Auto-invoqué, rend
     * {@code {started:true}} ou {@code {started:false, error:<nom>}}. À évaluer avec
     * {@code userGesture:true} et {@code awaitPromise:true}.
     */
    static final String START_SCRIPT = """
            (async () => {
              try {
                if (window.__cgMeetingCapture && window.__cgMeetingCapture.recorder
                    && window.__cgMeetingCapture.recorder.state === 'recording') {
                  return { started: true, already: true };
                }
                const display = await navigator.mediaDevices.getDisplayMedia({
                  video: true, audio: true, preferCurrentTab: true
                });
                let mic = null;
                try { mic = await navigator.mediaDevices.getUserMedia({ audio: true }); }
                catch (e) { /* le micro peut être refusé : on garde au moins l'audio de l'onglet */ }
                const ctx = new (window.AudioContext || window.webkitAudioContext)();
                const dest = ctx.createMediaStreamDestination();
                const tabAudio = display.getAudioTracks();
                if (tabAudio.length) { ctx.createMediaStreamSource(new MediaStream(tabAudio)).connect(dest); }
                if (mic) { ctx.createMediaStreamSource(mic).connect(dest); }
                const chunks = [];
                const recorder = new MediaRecorder(dest.stream, { mimeType: 'audio/webm' });
                recorder.ondataavailable = (ev) => { if (ev.data && ev.data.size) chunks.push(ev.data); };
                recorder.start(1000);
                window.__cgMeetingCapture = {
                  recorder, chunks, ctx, display, mic,
                  micDenied: !mic, result: null
                };
                return { started: true, micDenied: !mic };
              } catch (e) {
                return { started: false, error: (e && e.name) ? e.name : String(e) };
              }
            })()
            """;

    /**
     * Arrête la capture, assemble le média et l'encode en base64 dans {@code window.__cgMeetingCapture.result}.
     * Rend {@code {stopped:true, size:<octets>}} ou {@code {stopped:false, error:<nom>}}. À évaluer avec
     * {@code awaitPromise:true}.
     */
    static final String STOP_SCRIPT = """
            (async () => {
              const c = window.__cgMeetingCapture;
              if (!c || !c.recorder) { return { stopped: false, error: 'no_active_capture' }; }
              try {
                const blob = await new Promise((resolve) => {
                  c.recorder.onstop = () => resolve(new Blob(c.chunks, { type: 'audio/webm' }));
                  if (c.recorder.state !== 'inactive') { c.recorder.stop(); } else { resolve(new Blob(c.chunks)); }
                });
                try { (c.display ? c.display.getTracks() : []).forEach(t => t.stop()); } catch (e) {}
                try { (c.mic ? c.mic.getTracks() : []).forEach(t => t.stop()); } catch (e) {}
                try { if (c.ctx) c.ctx.close(); } catch (e) {}
                const buffer = await blob.arrayBuffer();
                let binary = '';
                const bytes = new Uint8Array(buffer);
                for (let i = 0; i < bytes.length; i++) { binary += String.fromCharCode(bytes[i]); }
                c.result = btoa(binary);
                return { stopped: true, size: bytes.length };
              } catch (e) {
                return { stopped: false, error: (e && e.name) ? e.name : String(e) };
              }
            })()
            """;

    /** Le script qui lit une tranche du média encodé, à partir d'un décalage. */
    static String pullScript(long offset, int length) {
        return "(function(o,l){var r=(window.__cgMeetingCapture&&window.__cgMeetingCapture.result)||'';"
                + "return {total:r.length, chunk:r.substr(o,l)};})(" + offset + "," + length + ")";
    }

    /** Réassemble les tranches base64 dans l'ordre reçu. */
    static String reassemble(List<String> chunks) {
        StringBuilder sb = new StringBuilder();
        for (String chunk : chunks) {
            if (chunk != null) {
                sb.append(chunk);
            }
        }
        return sb.toString();
    }

    /** Décode le base64 en octets (rend un tableau vide si la chaîne est vide/illisible). */
    static byte[] decode(String base64) {
        if (base64 == null || base64.isEmpty()) {
            return new byte[0];
        }
        try {
            return Base64.getDecoder().decode(base64);
        } catch (IllegalArgumentException e) {
            return new byte[0];
        }
    }
}
