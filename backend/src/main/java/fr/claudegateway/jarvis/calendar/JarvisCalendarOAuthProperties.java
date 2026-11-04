package fr.claudegateway.jarvis.calendar;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * <b>L'écriture dans l'agenda Workspace</b> (F-243 / SF-243-03, D3) — le client OAuth <b>Internal</b> du projet Google
 * Cloud de l'organisation ng-itconsulting.com. <b>Dormant</b> tant que {@code clientId} est vide : aucun outil
 * {@code agenda_poser}, « Relier » répond « non configuré ». Le secret vient de l'environnement
 * ({@code APP_JARVIS_CALENDAR_OAUTH_CLIENT_SECRET}), jamais du code. Les adresses Google ne sont surchargées que par les
 * tests (serveur local).
 *
 * <p>Un seul constructeur (record compact) : un second casserait le démarrage du contexte Spring.</p>
 *
 * @param clientId     identifiant du client OAuth ({@code APP_JARVIS_CALENDAR_OAUTH_CLIENT_ID})
 * @param clientSecret secret du client ({@code APP_JARVIS_CALENDAR_OAUTH_CLIENT_SECRET})
 * @param redirectUri  adresse de retour déclarée chez Google ({@code …/api/jarvis/calendar/oauth/callback})
 * @param authUri      page de consentement Google
 * @param tokenUri     échange du code et rafraîchissement
 * @param revokeUri    révocation d'un jeton
 * @param apiBase      racine de l'API Google Calendar v3
 * @param timeout      délai des appels
 * @param hostedDomain domaine Workspace attendu (indice {@code hd} ; le client Internal le garantit déjà)
 */
@ConfigurationProperties(prefix = "app.jarvis.calendar.oauth")
public record JarvisCalendarOAuthProperties(
        String clientId,
        String clientSecret,
        String redirectUri,
        String authUri,
        String tokenUri,
        String revokeUri,
        String apiBase,
        Duration timeout,
        String hostedDomain) {

    /** La portée d'écriture (D3) et l'identité du compte relié. */
    public static final String SCOPE = "openid email https://www.googleapis.com/auth/calendar.events";

    public JarvisCalendarOAuthProperties {
        clientId = blank(clientId) ? "" : clientId.strip();
        clientSecret = blank(clientSecret) ? "" : clientSecret.strip();
        redirectUri = blank(redirectUri) ? "https://portal.ng-itconsulting.com/api/jarvis/calendar/oauth/callback"
                : redirectUri.strip();
        authUri = blank(authUri) ? "https://accounts.google.com/o/oauth2/v2/auth" : authUri.strip();
        tokenUri = blank(tokenUri) ? "https://oauth2.googleapis.com/token" : tokenUri.strip();
        revokeUri = blank(revokeUri) ? "https://oauth2.googleapis.com/revoke" : revokeUri.strip();
        apiBase = blank(apiBase) ? "https://www.googleapis.com/calendar/v3" : apiBase.strip();
        timeout = timeout == null || timeout.isNegative() || timeout.isZero() ? Duration.ofSeconds(15) : timeout;
        hostedDomain = blank(hostedDomain) ? "" : hostedDomain.strip();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** Vrai quand le client est configuré (identifiant et secret). */
    public boolean configured() {
        return !clientId.isEmpty() && !clientSecret.isEmpty();
    }
}
