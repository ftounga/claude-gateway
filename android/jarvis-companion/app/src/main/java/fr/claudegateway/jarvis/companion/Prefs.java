package fr.claudegateway.jarvis.companion;

import android.content.Context;
import android.content.SharedPreferences;

import fr.claudegateway.jarvis.companion.core.JarvisLink;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * Ce que le compagnon garde : l'adresse de la gateway, les gabarits du mot d'éveil, l'interrupteur.
 * Préférences <b>privées</b> de l'application ; <b>aucun jeton, aucun secret</b> (SF-232-01).
 */
final class Prefs {

    private static final String FILE = "jarvis";
    private static final String GATEWAY = "gateway";
    private static final String PROFILE = "wake_profile";
    private static final String LISTENING = "listening";

    private final SharedPreferences prefs;

    Prefs(Context context) {
        this.prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    String gateway() {
        String saved = JarvisLink.normalizeGateway(prefs.getString(GATEWAY, null));
        return saved != null ? saved : JarvisLink.normalizeGateway(BuildConfig.DEFAULT_GATEWAY_URL);
    }

    /** Enregistre l'adresse si elle est acceptable ; rend {@code false} sinon (rien n'est changé). */
    boolean setGateway(String raw) {
        String normalized = JarvisLink.normalizeGateway(raw);
        if (normalized == null) {
            return false;
        }
        prefs.edit().putString(GATEWAY, normalized).apply();
        return true;
    }

    WakeProfile profile() {
        return WakeProfile.decode(prefs.getString(PROFILE, null));
    }

    void setProfile(WakeProfile profile) {
        prefs.edit().putString(PROFILE, profile.encode()).apply();
    }

    /** L'interrupteur voulu par le PO (D3) — distinct de la pause « batterie faible ». */
    boolean listening() {
        return prefs.getBoolean(LISTENING, false);
    }

    void setListening(boolean on) {
        prefs.edit().putBoolean(LISTENING, on).apply();
    }
}
