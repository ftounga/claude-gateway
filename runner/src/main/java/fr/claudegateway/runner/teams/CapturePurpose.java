package fr.claudegateway.runner.teams;

import java.util.Locale;

/**
 * <b>Les deux usages de la capture locale</b> (F-91 / SF-91-01), et lequel demande un second geste.
 *
 * <h2>Pourquoi deux, et pas un</h2>
 *
 * <p>Capturer <b>son propre écran</b> — une démo, un débogage — <b>ne concerne personne d'autre</b> :
 * aucune friction. Enregistrer <b>une réunion à plusieurs</b> demande une confirmation explicite
 * d'avoir prévenu.</p>
 *
 * <p>La raison est écrite dans le cadrage et elle est la seule qui compte :
 * <i>si les deux avaient la même friction, elle deviendrait un réflexe et ne protégerait plus
 * rien</i>. Une confirmation qu'on coche vingt fois par jour pour capturer son terminal n'est plus
 * une confirmation, c'est un clic.</p>
 *
 * <h2>L'usage n'est jamais deviné</h2>
 *
 * <p>{@link #of(String)} rend {@code null} pour tout ce qu'il ne reconnaît pas, et l'appelant
 * <b>refuse</b>. Se replier sur l'usage le moins exigeant reviendrait à supprimer la friction pour
 * qui oublie de nommer son usage — c'est-à-dire à la supprimer.</p>
 */
public enum CapturePurpose {

    /** Son propre écran : une démo, un débogage. <b>Aucune friction.</b> */
    SELF_SCREEN("mon propre écran", false),

    /** Une réunion à plusieurs. <b>Confirmation explicite d'avoir prévenu.</b> */
    MEETING_WITH_OTHERS("une réunion à plusieurs", true);

    private final String label;
    private final boolean confirmationRequired;

    CapturePurpose(String label, boolean confirmationRequired) {
        this.label = label;
        this.confirmationRequired = confirmationRequired;
    }

    /** Ce que l'utilisateur lit. Un seul endroit le dit. */
    public String label() {
        return label;
    }

    /** Vrai quand le démarrage demande une confirmation explicite d'avoir prévenu. */
    public boolean confirmationRequired() {
        return confirmationRequired;
    }

    /**
     * L'usage nommé dans la demande, ou {@code null} quand il n'est <b>pas</b> reconnu.
     *
     * <p>Les deux langues sont acceptées parce que l'agent écrit tantôt l'une tantôt l'autre ; ce
     * qui n'est pas accepté, c'est l'absence — voir la note de classe.</p>
     */
    public static CapturePurpose of(String raw) {
        String value = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "self", "self_screen", "screen", "ecran", "écran", "mon_ecran", "demo" ->
                    SELF_SCREEN;
            case "meeting", "meeting_with_others", "reunion", "réunion", "call" ->
                    MEETING_WITH_OTHERS;
            default -> null;
        };
    }
}
