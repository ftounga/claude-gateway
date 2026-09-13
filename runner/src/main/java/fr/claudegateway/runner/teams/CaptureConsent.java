package fr.claudegateway.runner.teams;

/**
 * <b>Le second geste</b> (F-91 / SF-91-01, garde-fou n° 2 du cadrage).
 *
 * <h2>Ce que cette classe fait</h2>
 *
 * <p>Elle décide si une capture a le droit de <b>démarrer</b>, et elle le décide <b>avant</b> qu'on
 * ait cherché {@code ffmpeg}, ouvert un dossier ou lancé quoi que ce soit. Un refus ne doit rien
 * laisser derrière lui.</p>
 *
 * <h2>La case n'est jamais pré-cochée</h2>
 *
 * <p>{@code participantsInformed} n'a <b>pas de valeur par défaut</b> : absent vaut non, et le
 * démarrage est refusé. C'est la transposition littérale de la règle du cadrage. Et la confirmation
 * n'est valable que pour <b>cette</b> capture : une confirmation valable pour la session
 * deviendrait un réflexe, ce que le garde-fou refuse explicitement.</p>
 *
 * <h2>Ce que le produit ne peut pas garantir, et qui est écrit</h2>
 *
 * <p><b>Que les participants soient informés.</b> Cela ne peut venir que de l'utilisateur, de vive
 * voix. On ne peut rien afficher dans la réunion des autres — seul Teams le peut, et seulement quand
 * c'est lui qui enregistre. La confirmation sert à le lui <b>rappeler</b>, pas à le
 * <b>protéger</b> : cette phrase est dans le refus <b>et</b> dans l'acceptation, parce qu'elle est
 * vraie dans les deux cas.</p>
 *
 * @param purpose              l'usage, jamais deviné
 * @param participantsInformed la confirmation d'avoir prévenu, pour cette capture seulement
 */
public record CaptureConsent(CapturePurpose purpose, boolean participantsInformed) {

    /**
     * Ce que le produit <b>ne peut pas</b> garantir. Écrit une fois, cité partout : dans le refus,
     * dans le résultat de démarrage, et dans le filigrane de la capture.
     */
    public static final String NOT_GUARANTEED =
            "Le produit ne peut pas garantir que les participants soient informés : on ne peut rien "
                    + "afficher dans la réunion des autres, seul Teams le peut quand c'est lui qui "
                    + "enregistre. Cela ne peut venir que de vous, de vive voix. Cette confirmation "
                    + "sert à vous le rappeler, pas à vous protéger.";

    /**
     * Le consentement d'une demande, ou un <b>refus</b>.
     *
     * @param rawPurpose l'usage tel qu'il a été nommé ; {@code null} ou inconnu ⇒ refus
     * @param informed   la confirmation ; {@code null} vaut <b>non</b>
     * @throws CaptureRefusedException quand le geste manque, avec son remède
     */
    public static CaptureConsent require(String rawPurpose, Boolean informed) {
        CapturePurpose purpose = CapturePurpose.of(rawPurpose);
        if (purpose == null) {
            throw new CaptureRefusedException(CaptureRefusedException.NO_PURPOSE,
                    "Je ne démarre pas une capture sans savoir ce qu'elle enregistre.",
                    "Dites-le explicitement : « purpose »: « self » pour votre propre écran (une "
                            + "démo, un débogage — cela ne concerne personne d'autre), ou "
                            + "« meeting » pour une réunion à plusieurs. Je ne le devine pas : "
                            + "deviner reviendrait à choisir l'usage le moins exigeant.");
        }
        boolean confirmed = Boolean.TRUE.equals(informed);
        if (purpose.confirmationRequired() && !confirmed) {
            throw new CaptureRefusedException(CaptureRefusedException.NOT_CONFIRMED,
                    "Enregistrer une réunion à plusieurs demande que vous confirmiez avoir prévenu "
                            + "les participants. Je ne démarre pas sans cela.",
                    "Prévenez-les de vive voix, puis redemandez avec "
                            + "« participants_informed »: true. " + NOT_GUARANTEED);
        }
        return new CaptureConsent(purpose, confirmed);
    }

    /** Vrai quand cette capture concerne d'autres personnes que l'utilisateur du poste. */
    public boolean concernsOthers() {
        return purpose == CapturePurpose.MEETING_WITH_OTHERS;
    }

    /**
     * Ce qu'on dit à l'utilisateur au démarrage. Pour une capture d'écran propre, c'est court — il
     * n'y a rien à rappeler. Pour une réunion, c'est la phrase que la confirmation sert à rappeler.
     */
    public String describe() {
        if (!concernsOthers()) {
            return "Capture de " + purpose.label() + " : elle ne concerne personne d'autre, je "
                    + "démarre sans autre formalité.";
        }
        return "Capture de " + purpose.label() + ", démarrée sur votre confirmation d'avoir prévenu "
                + "les participants. " + NOT_GUARANTEED;
    }
}
