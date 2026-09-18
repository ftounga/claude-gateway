package fr.claudegateway.runner.teams;

import java.util.function.Consumer;

/**
 * <b>La sonde réelle de la Vigie</b> (F-122 / SF-122-06) : le câblage qui fait parler
 * {@link TeamsSessionWatch} (SF-122-03) au runtime.
 *
 * <p>À chaque relevé, elle rejoue la sonde de santé (SF-87-03, {@link TeamsProbe}) contre le Chrome
 * managé — c'est exactement l'usage prévu, « rejouée à chaque relevé d'état » — en nourrit la veille
 * de session, et en tire l'état à remonter : <b>Teams connecté</b> (une réponse Teams a été lue),
 * <b>reconnexion requise</b> (page d'identification, ou onglet Teams non ouvert/non signé), et la
 * réussite du <b>test de lecture</b>.</p>
 *
 * <p><b>Elle ne réécrit aucune logique</b> : elle assemble {@link TeamsSession},
 * {@link TeamsProbe} et {@link TeamsSessionWatch}, tous déjà éprouvés. Le seul jugement propre —
 * traduire ce que la sonde a vu en observations pour la veille — est isolé dans {@link #judge} et
 * {@link #feedFromFailure}, éprouvables sans navigateur.</p>
 */
public final class VigieSonde implements VigieLoop.Sonde {

    private final TeamsSession session;
    private final TeamsProbe probe;
    private final TeamsSessionWatch watch;
    private final BrowserLink.Sleeper sleeper;

    VigieSonde(TeamsSession session, TeamsProbe probe, TeamsSessionWatch watch,
            BrowserLink.Sleeper sleeper) {
        this.session = session;
        this.probe = probe;
        this.watch = watch;
        this.sleeper = sleeper;
    }

    /**
     * La sonde réelle branchée sur le Chrome managé du poste.
     *
     * @param port              le port de débogage du Chrome managé (boucle locale)
     * @param say               la notification utilisateur
     * @param onReloginRequired à la bascule « reconnexion requise » : faire <b>surgir</b> la fenêtre
     *                          managée pour le login interactif (SF-122-06/03)
     * @param onReconnected     à la reconnexion : <b>remasquer</b> la fenêtre managée
     */
    public static VigieSonde real(int port, Consumer<String> say, Runnable onReloginRequired,
            Runnable onReconnected) {
        TeamsAdapter adapter = TeamsAdapters.current();
        return new VigieSonde(new TeamsSession(port, adapter, say), new TeamsProbe(adapter),
                new TeamsSessionWatch(say, onReloginRequired, onReconnected),
                BrowserLink.realSleeper());
    }

    @Override
    public VigieLoop.Reading sense() {
        try {
            BrowserLink link = session.link();
            TeamsProbeResult result = probe.probe(link, sleeper);
            return judge(result, link.teamsTabUrl(), watch);
        } catch (BrowserLinkException e) {
            // Une liaison qui échoue porte déjà sa cause : on en tire l'état sans deviner.
            feedFromFailure(e.code(), watch);
            return new VigieLoop.Reading(watch.state(), false);
        }
    }

    /**
     * Le jugement pur : traduit ce que la sonde a vu en observations pour la veille de session, et en
     * tire le relevé. Isolé de la liaison pour s'éprouver sans navigateur.
     */
    static VigieLoop.Reading judge(TeamsProbeResult result, String tabUrl, TeamsSessionWatch watch) {
        if (MicrosoftDomains.isSignIn(tabUrl)) {
            // L'onglet est resté sur une page d'identification Microsoft : reconnexion requise, et
            // aucun test de lecture ne peut être tenu pour réussi tant qu'on n'est pas connecté.
            watch.observe(tabUrl, 0);
            return new VigieLoop.Reading(watch.state(), false);
        }
        if (linked(result)) {
            // Teams a répondu et l'adaptateur a su lire : la session est ouverte.
            watch.observe(tabUrl, 200);
        }
        return new VigieLoop.Reading(watch.state(), linked(result));
    }

    /** Une liaison qui a échoué à l'attache : on en déduit l'état de session (SF-122-03). */
    static void feedFromFailure(String code, TeamsSessionWatch watch) {
        if (BrowserLinkException.NOT_SIGNED_IN.equals(code)) {
            // Une adresse d'identification suffit à faire basculer la veille en « reconnexion requise ».
            watch.observe("https://login.microsoftonline.com/", 0);
        }
    }

    private static boolean linked(TeamsProbeResult result) {
        return result.state() == TeamsLinkState.LINKED && result.conclusive();
    }
}
