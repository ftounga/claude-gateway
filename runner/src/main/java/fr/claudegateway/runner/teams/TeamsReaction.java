package fr.claudegateway.runner.teams;

/**
 * Une réaction posée sur un message (F-87 / SF-87-01).
 *
 * <p>Elle compte pour un compte rendu : « personne n'a répondu, mais quatre pouces levés » n'est pas
 * la même chose que le silence. Le genre reste la chaîne déclarée (« like », « heart »…) : on ne
 * traduit pas un vocabulaire qui bouge chez Microsoft.</p>
 *
 * @param kind  genre déclaré, minuscules
 * @param count nombre de personnes, au moins 1
 */
public record TeamsReaction(String kind, int count) {

    public TeamsReaction {
        kind = kind == null ? "" : kind.strip().toLowerCase(java.util.Locale.ROOT);
        count = Math.max(1, count);
    }
}
