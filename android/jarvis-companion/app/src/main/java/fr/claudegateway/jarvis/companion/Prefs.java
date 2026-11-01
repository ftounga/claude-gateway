package fr.claudegateway.jarvis.companion;

import android.content.Context;
import android.content.SharedPreferences;

import fr.claudegateway.jarvis.companion.core.JarvisLink;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * Ce que le compagnon garde : l'adresse de la gateway, les gabarits du mot d'éveil, l'interrupteur, et depuis
 * SF-232-03 le <b>jeton dédié aux annonces</b> et le curseur de relève. Préférences <b>privées</b> de l'application
 * (sauvegarde désactivée, {@code allowBackup="false"}) ; le jeton n'ouvre que la lecture des annonces Jarvis, il est
 * révocable depuis l'écran Jarvis et n'est jamais journalisé.
 */
final class Prefs {

    private static final String FILE = "jarvis";
    private static final String GATEWAY = "gateway";
    private static final String PROFILE = "wake_profile";
    private static final String LISTENING = "listening";
    private static final String TOKEN = "announce_token";
    private static final String CURSOR = "announce_cursor";

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

    /** Le jeton des annonces (SF-232-03), ou {@code null} si le compagnon n'est pas appairé. */
    String token() {
        return prefs.getString(TOKEN, null);
    }

    /** Pose le jeton reçu à l'appairage ; {@code null} l'oublie (révoqué, expiré) — et le curseur avec. */
    void setToken(String token) {
        SharedPreferences.Editor edit = prefs.edit().remove(CURSOR);
        if (token == null) {
            edit.remove(TOKEN);
        } else {
            edit.putString(TOKEN, token);
        }
        edit.apply();
    }

    String cursor() {
        return prefs.getString(CURSOR, null);
    }

    void setCursor(String cursor) {
        prefs.edit().putString(CURSOR, cursor).apply();
    }
}
