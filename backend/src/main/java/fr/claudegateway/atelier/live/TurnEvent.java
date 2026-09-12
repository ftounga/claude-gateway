package fr.claudegateway.atelier.live;

/**
 * Un événement de tour, <b>numéroté</b> (F-84 / SF-84-01).
 *
 * <p>La charge utile est déjà sérialisée : un événement est produit une fois et relu autant de fois
 * qu'il y a de spectateurs, présents ou à venir. La sérialiser une seule fois évite qu'un rejeu
 * coûte autant que le tour lui-même, et garantit surtout que <b>deux vues reçoivent les mêmes
 * octets</b> — c'est la promesse « même suite d'événements » de SF-84-02.</p>
 *
 * @param seq  numéro d'ordre, strictement croissant dans un tour et jamais réutilisé ; c'est le
 *             <b>curseur</b> que porte un spectateur pour dire ce qu'il a déjà vu
 * @param name nom de l'événement SSE ({@code text}, {@code action}, {@code done}…)
 * @param json charge utile JSON, telle qu'elle part sur le fil
 */
public record TurnEvent(long seq, String name, String json) {

    /** Poids de l'événement dans le tampon, en caractères — la borne de mémoire se compte ainsi. */
    int weight() {
        return json.length();
    }
}
