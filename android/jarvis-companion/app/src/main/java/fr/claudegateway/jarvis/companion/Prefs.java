package fr.claudegateway.jarvis.companion;

import android.content.Context;
import android.content.SharedPreferences;

import fr.claudegateway.jarvis.companion.core.JarvisLink;
import fr.claudegateway.jarvis.companion.core.WakeProfile;

/**
 * Ce que le compagnon garde : l'adresse de la gateway, les gabarits du mot d'éveil, l'interrupteur, et depuis
 * SF-232-03 le <b>jeton dédié</b> obtenu à l'appairage (il n'ouvre que la réception des annonces) et le curseur
 * des annonces. Préférences <b>privées</b> de l'application (sauvegarde Android désactivée dans le manifeste).
 */
final class Prefs {

    private static final String FILE = "jarvis";
    private static final String GATEWAY = "gateway";
    private static final String PROFILE = "wake_profile";
    private static final String LISTENING = "listening";
    private static final String TOKEN = "companion_token";
    private static final String CURSOR = "announcements_cursor";

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

    /** Le jeton dédié (SF-232-03), ou {@code null} : téléphone pas appairé. */
    String token() {
        return prefs.getString(TOKEN, null);
    }

    /** Enregistre le jeton d'un nouvel appairage ; le curseur repart de zéro (aucun rejeu). */
    void setToken(String token) {
        prefs.edit().putString(TOKEN, token).remove(CURSOR).apply();
    }

    /** Oublie l'appairage (révoqué, ou à la demande). */
    void clearPairing() {
        prefs.edit().remove(TOKEN).remove(CURSOR).apply();
    }

    String cursor() {
        return prefs.getString(CURSOR, null);
    }

    void setCursor(String cursor) {
        prefs.edit().putString(CURSOR, cursor).apply();
    }
}
