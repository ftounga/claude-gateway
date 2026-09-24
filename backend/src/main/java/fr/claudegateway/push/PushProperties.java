package fr.claudegateway.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration du <b>Web Push</b> (F-153 / SF-153-02), sur le modèle du fournisseur d'images
 * (F-142) : clés <b>par secret d'environnement</b>, jamais en dur, jamais journalisées.
 *
 * <p><b>Éteint par défaut (DRAPEAU FORT).</b> Tant que {@link #vapidPublicKey} <b>et</b>
 * {@link #vapidPrivateKey} ne sont pas fournies par l'environnement
 * ({@code APP_PUSH_VAPID_PUBLIC} / {@code APP_PUSH_VAPID_PRIVATE}), {@link #isConfigured()} est faux
 * et <b>aucun octet ne part</b> : l'émission push est inactive et le signal in-tab (SF-153-01) reste
 * le repli. La clé <b>privée</b> vient exclusivement de l'environnement et n'est jamais servie au
 * frontend ni journalisée ; seule la clé <b>publique</b> est exposée (endpoint dédié).</p>
 */
@ConfigurationProperties(prefix = "app.push")
public record PushProperties(
        String vapidPublicKey,
        String vapidPrivateKey,
        String subject) {

    public PushProperties {
        if (subject == null || subject.isBlank()) {
            // Sujet VAPID par défaut : une adresse de contact du service (jamais un secret).
            subject = "mailto:contact@ng-itconsulting.com";
        }
    }

    /**
     * Vrai si le Web Push est réellement émetteur : une clé publique <b>et</b> une clé privée sont
     * fournies. Faux par défaut ⇒ émission éteinte, aucune donnée ne sort.
     */
    public boolean isConfigured() {
        return vapidPublicKey != null && !vapidPublicKey.isBlank()
                && vapidPrivateKey != null && !vapidPrivateKey.isBlank();
    }
}
