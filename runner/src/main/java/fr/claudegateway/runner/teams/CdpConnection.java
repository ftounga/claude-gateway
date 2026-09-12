package fr.claudegateway.runner.teams;

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

    /** Vrai tant que la socket vit. */
    boolean isOpen();

    @Override
    void close();
}
