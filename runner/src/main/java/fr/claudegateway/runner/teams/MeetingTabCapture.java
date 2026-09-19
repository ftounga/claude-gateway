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
                  micDenied: !mic, result: null, frames: [], sampler: null
                };
                // SF-128-03 : échantillonnage des images clés du partage d'écran (ne retient que
                // sur changement, borné). La vidéo pleine ne quitte jamais la machine.
                try {
                  const video = document.createElement('video');
                  video.muted = true; video.srcObject = display;
                  await video.play().catch(() => {});
                  let lastSig = '';
                  window.__cgMeetingCapture.sampler = setInterval(() => {
                    try {
                      const c2 = window.__cgMeetingCapture; if (!c2) return;
                      const vw = video.videoWidth, vh = video.videoHeight;
                      if (!vw || !vh || c2.frames.length >= 60) return;
                      const small = document.createElement('canvas'); small.width = 8; small.height = 8;
                      const sctx = small.getContext('2d');
                      sctx.drawImage(video, 0, 0, 8, 8);
                      const d = sctx.getImageData(0, 0, 8, 8).data;
                      let sig = ''; for (let i = 0; i < d.length; i += 4) { sig += (d[i] >> 5); }
                      if (sig === lastSig) return;
                      lastSig = sig;
                      const w = vw > 1280 ? 1280 : vw;
                      const full = document.createElement('canvas');
                      full.width = w; full.height = Math.round(w * vh / vw);
                      full.getContext('2d').drawImage(video, 0, 0, full.width, full.height);
                      c2.frames.push({ t: Date.now(), data: full.toDataURL('image/jpeg', 0.6) });
                    } catch (e) {}
                  }, 4000);
                } catch (e) {}
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
                try { if (c.sampler) clearInterval(c.sampler); } catch (e) {}
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

    /**
     * <b>Remet l'onglet dans un état ré-utilisable</b> après un arrêt (F-128 / SF-128-09) : coupe un
     * éventuel échantillonneur et des pistes restées ouvertes (défensif — {@link #STOP_SCRIPT} les a
     * normalement déjà arrêtées), puis <b>efface</b> le global {@code window.__cgMeetingCapture}. Sans ce
     * nettoyage, le résultat encodé et les images clés de la capture précédente resteraient en mémoire de
     * l'onglet ; avec lui, une 2ᵉ capture repart proprement. Synchrone, ne lève jamais.
     */
    static final String CLEANUP_SCRIPT = "(function(){try{var c=window.__cgMeetingCapture;if(c){"
            + "try{if(c.sampler)clearInterval(c.sampler);}catch(e){}"
            + "try{(c.display?c.display.getTracks():[]).forEach(function(t){t.stop();});}catch(e){}"
            + "try{(c.mic?c.mic.getTracks():[]).forEach(function(t){t.stop();});}catch(e){}}"
            + "window.__cgMeetingCapture=null;return {cleared:true};}catch(e){return {cleared:false};}})()";

    /**
     * <b>Sonde l'état de l'enregistrement</b> (F-128 / SF-128-12) : rend {@code {active:true}} tant
     * qu'un {@code MediaRecorder} tourne dans le contexte JS courant, {@code {active:false}} si le
     * global a été effacé par une navigation/rechargement de la page Teams (SPA). C'est cette perte du
     * global — {@code window.__cgMeetingCapture} vit dans le contexte de la page, détruit à chaque
     * rechargement de document — qui faisait échouer l'arrêt en {@code no_active_capture}. Synchrone,
     * ne lève jamais, sûre à ré-évaluer à chaque événement de chargement.
     */
    static final String ACTIVE_PROBE_SCRIPT = "(function(){try{var c=window.__cgMeetingCapture;"
            + "return {active: !!(c && c.recorder && c.recorder.state === 'recording')};}"
            + "catch(e){return {active:false};}})()";

    /**
     * <b>Le garde de ré-injection</b> (F-128 / SF-128-12), pur et éprouvable : on ne ré-injecte le
     * capteur que si le ré-armement est armé <b>et</b> qu'aucun enregistrement n'est actif. Un
     * enregistrement encore vivant (même contexte, pas de navigation) ne doit jamais être doublé —
     * c'est la protection contre un second {@code getDisplayMedia} inutile.
     *
     * @param armed          le ré-armement est en place (capture démarrée, pas encore arrêtée)
     * @param captureActive  un {@code MediaRecorder} tourne encore dans la page (voir {@link #ACTIVE_PROBE_SCRIPT})
     * @return vrai s'il faut ré-injecter {@link #START_SCRIPT}
     */
    static boolean shouldReinject(boolean armed, boolean captureActive) {
        return armed && !captureActive;
    }

    /** Nombre maximum d'images clés retenues par réunion. */
    static final int MAX_FRAMES = 60;

    /** Le script qui rend le nombre d'images clés retenues. */
    static String framesCountScript() {
        return "(function(){var c=window.__cgMeetingCapture;return {count:(c&&c.frames)?c.frames.length:0};})()";
    }

    /** Le script qui lit une tranche d'une image clé (par index), à partir d'un décalage. */
    static String framePullScript(int index, long offset, int length) {
        return "(function(i,o,l){var c=window.__cgMeetingCapture;var f=(c&&c.frames&&c.frames[i])||null;"
                + "if(!f)return{total:0,chunk:'',t:0};var d=f.data||'';"
                + "return {total:d.length, chunk:d.substr(o,l), t:f.t};})(" + index + "," + offset + "," + length + ")";
    }

    /** Retire le préfixe {@code data:...;base64,} d'une image encodée en data URL. */
    static String stripDataUrl(String dataUrl) {
        if (dataUrl == null) {
            return "";
        }
        int marker = dataUrl.indexOf("base64,");
        return marker < 0 ? dataUrl : dataUrl.substring(marker + "base64,".length());
    }

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
