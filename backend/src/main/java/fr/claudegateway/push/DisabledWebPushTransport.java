package fr.claudegateway.push;

/**
 * Transport <b>inactif</b> (F-153 / SF-153-02) : le repli propre quand le Web Push n'est pas
 * configuré (pas de clés VAPID). Aucun octet ne part ; l'émetteur n'a rien à purger. Le signal
 * in-tab (SF-153-01) reste alors la seule alerte — c'est voulu.
 */
public final class DisabledWebPushTransport implements WebPushTransport {

    @Override
    public boolean isEnabled() {
        return false;
    }

    @Override
    public Result send(PushSubscription subscription, String payloadJson) {
        return Result.FAILED;
    }
}
