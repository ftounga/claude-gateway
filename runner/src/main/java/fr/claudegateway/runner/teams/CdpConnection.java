package fr.claudegateway.runner.teams;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Une socket de débogage ouverte sur un onglet (F-87 / SF-87-02).
 *
 * <p>Interface, et non classe : c'est elle qui permet d'éprouver l'observation du réseau sans
 * navigateur — nous n'avons pas de compte Teams de test, il aurait été absurde d'exiger en plus un
 * Chrome vivant pour vérifier qu'un corps indisponible produit bien un manque.</p>
 */
public interface CdpConnection extends AutoCloseable {

    /**
     * Envoie une commande et attend son résultat.
     *
     * <p>Toute implémentation passe d'abord par {@link CdpCommands#assertAllowed(String)} : la
     * garde est dans le contrat, pas dans la politesse de l'appelant.</p>
     */
    JsonNode send(String method, ObjectNode params);

    /** S'abonne à un événement du protocole (par exemple {@code Network.responseReceived}). */
    void onEvent(String method, Consumer<JsonNode> listener);

    /**
     * Envoie une commande <b>sur la session d'une cible attachée</b> (cadre, worker) — F-100 /
     * SF-100-00. Ajout additif : sans session, c'est {@link #send(String, ObjectNode)}.
     *
     * <p>La garde ne bouge pas : toute implémentation passe par
     * {@link CdpCommands#assertAllowed(String)} avant émission, session ou pas. Une implémentation qui
     * ne sait pas parler à une session <b>refuse</b> plutôt que d'envoyer la commande à l'onglet.</p>
     */
    default JsonNode send(String sessionId, String method, ObjectNode params) {
        if (sessionId == null || sessionId.isBlank()) {
            return send(method, params);
        }
        CdpCommands.assertAllowed(method);
        throw new BrowserLinkException(BrowserLinkException.COMMAND_REFUSED,
                "Cette liaison ne sait pas s'adresser à une cible attachée.");
    }

    /**
     * S'abonne à un événement <b>en recevant la session qui l'a émis</b> ({@code ""} pour l'onglet
     * lui-même) — F-100 / SF-100-00. C'est ce qui permet de dire d'où une réponse a été vue : l'onglet,
     * un cadre intégré ou un worker.
     */
    default void onSessionEvent(String method, BiConsumer<String, JsonNode> listener) {
        onEvent(method, params -> listener.accept("", params));
    }

    /** Vrai tant que la socket vit. */
    boolean isOpen();

    @Override
    void close();
}
