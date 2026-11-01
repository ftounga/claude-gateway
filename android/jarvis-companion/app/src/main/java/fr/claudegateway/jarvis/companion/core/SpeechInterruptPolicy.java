package fr.claudegateway.jarvis.companion.core;

/**
 * <b>Couper le compagnon en parlant</b> (F-233 / SF-233-10, D8, D12), sans Android : pendant qu'il dit une annonce, le
 * micro reste ouvert (source {@code VOICE_COMMUNICATION} + annulation d'écho) et un mot entendu le fait taire, puis
 * ouvre Jarvis qui écoute. Pur.
 *
 * <ul>
 *   <li>Un mot ne compte que s'il dure au moins {@link WakeEngine#MIN_WORD_FRAMES} trames (un clic, une toux brève ne
 *   coupent pas) — c'est la détection de voix de l'appareil, déjà portée par le mot d'éveil (rien ne sort).</li>
 *   <li>Les {@value #ATTACK_GRACE_MS} ms qui suivent le début d'une phrase sont ignorées : l'annulation d'écho met un
 *   instant à converger sur la voix du haut-parleur.</li>
 *   <li>Une seule coupure par prise de parole.</li>
 * </ul>
 */
public final class SpeechInterruptPolicy {

    /** Ignoré juste après le début d'une phrase dite (convergence de l'annulation d'écho). */
    public static final long ATTACK_GRACE_MS = 300L;

    private long utteranceStartedAt = -1L;
    private boolean speaking;
    private boolean interrupted;

    /** Une phrase commence à être dite. */
    public synchronized void onUtteranceStart(long nowMs) {
        speaking = true;
        utteranceStartedAt = nowMs;
    }

    /** Le compagnon a fini de parler (ou a été coupé). */
    public synchronized void onSpeakingEnd() {
        speaking = false;
        interrupted = false;
        utteranceStartedAt = -1L;
    }

    /** Un mot de {@code frames} trames est entendu à {@code nowMs} : faut-il couper ? (vrai une seule fois) */
    public synchronized boolean shouldInterrupt(int frames, long nowMs) {
        if (!speaking || interrupted || frames < WakeEngine.MIN_WORD_FRAMES) {
            return false;
        }
        if (utteranceStartedAt >= 0 && nowMs - utteranceStartedAt < ATTACK_GRACE_MS) {
            return false;
        }
        interrupted = true;
        return true;
    }
}
