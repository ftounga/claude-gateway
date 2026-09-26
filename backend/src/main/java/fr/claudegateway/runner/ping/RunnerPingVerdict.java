package fr.claudegateway.runner.ping;

/**
 * Ce que le ping conditionnel a constaté (F-161 / SF-161-04).
 *
 * <p>Deux issues seulement, et c'est délibéré : <b>muet</b> — le poste n'a pas traité l'appel, la
 * porte peut fermer sans dépenser un jeton — ou <b>passe</b>, qui couvre aussi bien la preuve
 * d'exécution que <b>le doute</b>. Une ignorance ne ferme rien : c'est la doctrine posée par
 * SF-161-01, et un ping qui refuserait sur une réponse illisible serait pire que pas de ping.</p>
 *
 * @param mute   vrai si le poste n'a manifestement pas traité l'appel
 * @param reason pourquoi, en une phrase qui dit quoi faire ; {@code null} si le tour passe
 */
public record RunnerPingVerdict(boolean mute, String reason) {

    private static final RunnerPingVerdict PASSES = new RunnerPingVerdict(false, null);

    /** Le tour passe : exécution prouvée, ping inutile, ping débranché — ou doute. */
    public static RunnerPingVerdict passes() {
        return PASSES;
    }

    /** Le poste n'a pas traité l'appel : la porte peut fermer, gratuitement. */
    public static RunnerPingVerdict mute(String reason) {
        return new RunnerPingVerdict(true, reason);
    }
}
