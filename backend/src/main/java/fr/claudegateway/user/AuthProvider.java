package fr.claudegateway.user;

/**
 * Mode d'authentification à l'origine d'un compte utilisateur.
 * <ul>
 *   <li>{@code LOCAL} : compte email / mot de passe (SF-01-02+).</li>
 *   <li>{@code GOOGLE} : compte fédéré via OAuth2/OIDC Google (SF-01-05).</li>
 * </ul>
 * Présent dès le socle pour accueillir les deux modes sans migration ultérieure.
 */
public enum AuthProvider {
    LOCAL,
    GOOGLE
}
