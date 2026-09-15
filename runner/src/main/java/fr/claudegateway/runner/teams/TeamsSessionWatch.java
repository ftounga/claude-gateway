package fr.claudegateway.runner.teams;

import java.util.function.Consumer;

/**
 * <b>La session Teams expire, et ça se voit</b> (F-122 / SF-122-03).
 *
 * <p>La Vigie tourne seule, en arrière-plan. Mais une session Teams d'entreprise expire (fréquence
 * variable selon le tenant), et sans elle le runner n'observe plus rien d'utile. Cette veille tient
 * l'état de la session à partir des observations réseau déjà produites par {@link NetworkObserver} —
 * <b>l'adresse et le statut, jamais un en-tête ni un cookie</b> — et bascule en
 * {@link TeamsSessionState#RELOGIN_REQUIRED} dès qu'une <b>identification</b> est demandée
 * (redirection vers une page de login Microsoft) ou qu'une réponse Microsoft revient <b>401/403</b>.</p>
 *
 * <p><b>La transition parle, pas chaque observation.</b> Une session expirée produit des dizaines de
 * réponses refusées ; répéter « reconnectez-vous » à chacune serait du bruit. La notification — et le
 * rappel de la fenêtre managée pour le login — n'arrive donc qu'à la <b>bascule</b>, une seule fois,
 * et de même le rétablissement quand une réponse normale revient après le relogin.</p>
 *
 * <p><b>Le runner ne se connecte jamais à la place de l'utilisateur</b> : il constate, notifie, et
 * rappelle la fenêtre (couture {@code onReloginRequired}). L'identification reste un geste humain.</p>
 */
public final class TeamsSessionWatch {

    private final Consumer<String> say;
    private final Runnable onReloginRequired;
    private final Runnable onReconnected;

    private TeamsSessionState state = TeamsSessionState.UNKNOWN;

    public TeamsSessionWatch(Consumer<String> say) {
        this(say, null, null);
    }

    /**
     * @param say               la notification (peut être {@code null})
     * @param onReloginRequired rappel de la fenêtre managée pour le login (peut être {@code null})
     * @param onReconnected     remise en arrière-plan après reconnexion (peut être {@code null})
     */
    public TeamsSessionWatch(Consumer<String> say, Runnable onReloginRequired,
            Runnable onReconnected) {
        this.say = say;
        this.onReloginRequired = onReloginRequired;
        this.onReconnected = onReconnected;
    }

    /** L'état courant. */
    public TeamsSessionState state() {
        return state;
    }

    /** Vrai si une reconnexion est demandée. */
    public boolean reloginRequired() {
        return state == TeamsSessionState.RELOGIN_REQUIRED;
    }

    /**
     * Ingère une observation réseau. Aucun en-tête, aucun cookie : seulement l'adresse (déjà sans sa
     * chaîne de requête) et le statut HTTP.
     *
     * @param url    adresse observée
     * @param status statut HTTP, ou 0 si inconnu
     */
    public void observe(String url, int status) {
        if (MicrosoftDomains.isSignIn(url)) {
            toRelogin();
            return;
        }
        if (!MicrosoftDomains.isMicrosoftFamily(url)) {
            return; // du bruit hors des domaines Microsoft : rien à en conclure
        }
        if (status == 401 || status == 403) {
            toRelogin();
        } else if (status >= 200 && status < 300) {
            toConnected();
        }
    }

    private void toRelogin() {
        if (state == TeamsSessionState.RELOGIN_REQUIRED) {
            return; // déjà signalé : la transition parle, pas chaque observation
        }
        state = TeamsSessionState.RELOGIN_REQUIRED;
        if (say != null) {
            say.accept("Session Teams expirée : reconnectez-vous à Teams dans la fenêtre qui "
                    + "s'ouvre. C'est à faire une seule fois, la Vigie reprend seule ensuite.");
        }
        if (onReloginRequired != null) {
            onReloginRequired.run();
        }
    }

    private void toConnected() {
        if (state == TeamsSessionState.CONNECTED) {
            return;
        }
        boolean wasRelogin = state == TeamsSessionState.RELOGIN_REQUIRED;
        state = TeamsSessionState.CONNECTED;
        if (wasRelogin) {
            if (say != null) {
                say.accept("Session Teams rétablie : la Vigie repasse en arrière-plan.");
            }
            if (onReconnected != null) {
                onReconnected.run();
            }
        }
    }
}
