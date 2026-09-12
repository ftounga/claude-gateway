package fr.claudegateway.runner.teams;

/**
 * Une personne, telle que le produit la connaît (F-87 / SF-87-01).
 *
 * @param id          identifiant opaque du côté Microsoft ; jamais interprété, seulement comparé
 * @param displayName nom affiché ; jamais {@code null} — {@code ""} si Teams ne l'a pas donné
 * @param email       adresse, ou {@code null} : elle n'est pas toujours présente, et on n'invente pas
 * @param self        vrai s'il s'agit de l'utilisateur du navigateur relié
 */
public record TeamsParticipant(String id, String displayName, String email, boolean self) {

    public TeamsParticipant {
        id = id == null ? "" : id.strip();
        displayName = displayName == null ? "" : displayName.strip();
        email = email == null || email.isBlank() ? null : email.strip();
    }

    /** Vrai si le participant est exploitable : sans identifiant, on ne rend pas de message. */
    public boolean isReadable() {
        return !id.isEmpty();
    }

    /** Ce qu'on écrit dans un compte rendu : le nom s'il existe, l'identifiant sinon. */
    public String label() {
        return displayName.isEmpty() ? id : displayName;
    }
}
