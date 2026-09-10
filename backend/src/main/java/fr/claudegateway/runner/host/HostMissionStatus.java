package fr.claudegateway.runner.host;

/**
 * <b>État de mission</b> d'un poste (F-60 / SF-60-01) : où en est le travail chez ce client.
 *
 * <p>À ne pas confondre avec l'état <b>technique</b> que F-49 montre déjà — « connecté » ou non.
 * Les deux sont indépendants, et c'est tout l'objet de la feature : un poste dont le runner est
 * éteint peut être une mission active en pause, et un poste connecté peut être une mission close
 * que personne n'a rangée. L'état de mission est <b>déclaré</b> par le propriétaire du poste,
 * jamais déduit d'une activité ou d'une présence.</p>
 *
 * <p><b>Ce que cet état ne fait pas</b> : il ne coupe rien. Passer une mission en
 * {@link #CLOSED} n'invalide aucun jeton, ne ferme aucune liaison, ne détache aucun projet et
 * n'efface aucune ligne de journal. Couper une machine reste un geste distinct
 * ({@code POST /runner-hosts/{id}/kill}).</p>
 */
public enum HostMissionStatus {

    /** Mission en cours — le cas nominal, et la valeur de tout poste qui n'a rien déclaré. */
    ACTIVE,

    /** Mission en attente : elle existe, elle n'avance pas — un feu vert manque. */
    PENDING,

    /** Mission clôturée : elle se <b>range</b>, elle ne disparaît pas et rien n'est coupé. */
    CLOSED;

    /**
     * État de départ : {@link #ACTIVE}.
     *
     * <p>Le repli est « en cours » et non « en attente » : un poste qu'on vient de créer, ou qui
     * travaillait déjà avant F-60, <b>est</b> une mission en cours. Le mettre en attente
     * inventerait un feu rouge que personne n'a posé.</p>
     */
    public static HostMissionStatus defaultStatus() {
        return ACTIVE;
    }
}
