package fr.claudegateway.atelier.live;

/**
 * Un <b>spectateur</b> d'un tour (F-84 / SF-84-01) : il consomme les événements, il ne les commande
 * pas.
 *
 * <p>Tout le renversement de F-84 tient dans le type de retour de {@link #deliver} : un envoi qui
 * échoue rend {@code false} et vaut « je m'en vais », jamais « arrête-toi ». Avant F-84, le même
 * échec remontait en {@code StreamAbortedException} jusqu'à la boucle du tour et l'interrompait.</p>
 */
public interface TurnSubscriber {

    /**
     * Remet un événement à ce spectateur.
     *
     * @return {@code true} si l'événement est parti ; {@code false} si le spectateur est parti — il
     *         sera alors <b>détaché</b>, et le tour continuera sans lui
     */
    boolean deliver(TurnEvent event);

    /**
     * Le tour est terminé : plus rien ne viendra. Appelé une seule fois, y compris sur un spectateur
     * qui vient d'être détaché — fermer proprement une connexion déjà morte n'est pas une erreur.
     */
    void finish();

    /**
     * <b>Battement de cœur</b> (F-170 / SF-170-01) : un signal « vide » qui garde la connexion vivante
     * à travers un proxy d'entreprise pendant les phases sans événement (réflexion du modèle, outil
     * long). Ce n'est <b>pas</b> un événement du tour : il ne consomme aucun numéro d'ordre, n'entre
     * pas au tampon, n'est pas persisté et reste invisible à l'utilisateur.
     *
     * <p>Par défaut un no-op qui rend {@code true} : un spectateur qui porte déjà son propre battement
     * (le relais ndjson entre pods, qui pingue dans {@code awaitFinish}) n'a rien à faire ici. Seuls
     * les spectateurs SSE l'implémentent.</p>
     *
     * @return {@code true} si le battement est parti ; {@code false} si le spectateur est parti — il
     *         sera alors <b>détaché</b>, exactement comme un {@link #deliver} en échec
     */
    default boolean heartbeat() {
        return true;
    }
}
