package fr.claudegateway.runner.teams;

import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>L'adaptateur unique</b> (F-87 / SF-87-01) : la seule couche qui sait ce qu'est Teams.
 *
 * <p>Trois savoirs, et rien d'autre : <b>quelles URL portent quoi</b> ({@link #classify}),
 * <b>quels champs lire</b> (les méthodes de lecture), <b>comment paginer</b>
 * ({@link #nextPage}). Tout ce qui appelle cette interface ne manipule que des objets à nous.</p>
 *
 * <p>C'est le principe {@code AIProvider} de l'architecture, appliqué à Teams : le jour où Microsoft
 * change quelque chose, une seule implémentation est à corriger, et le reste du produit ne bouge
 * pas. Le jour où l'on voudra lire Slack ou Google Chat, c'est cette interface qu'on réalisera.</p>
 *
 * <p><b>Aucune méthode ne lève.</b> Un corps illisible rend une lecture vide porteuse d'un manque :
 * c'est la règle « échouer bruyamment, jamais à moitié faux » — et une exception qui traverse un
 * outil ne dit rien d'utile à l'utilisateur.</p>
 */
public interface TeamsAdapter {

    /**
     * Version de l'adaptateur, écrite dans un refus : « lu par l'adaptateur v1 ». Sans elle, un
     * refus ne dit pas <b>quelle</b> hypothèse s'est révélée fausse.
     */
    String version();

    /**
     * Le même adaptateur, qui sait désormais <b>qui est l'utilisateur relié</b> : les objets rendus
     * porteront {@code self}. Sans cela, « on m'a mentionné » ne peut pas se distinguer de « on a
     * mentionné quelqu'un ». L'identifiant est celui observé dans la session du navigateur ; il
     * n'est ni deviné, ni demandé à l'utilisateur.
     */
    TeamsAdapter forUser(String selfId);

    /** Ce que porte une URL observée. Ne lit jamais le corps. */
    TeamsPayloadKind classify(String url);

    /** Une page de messages. */
    TeamsReading<TeamsMessage> messages(String url, JsonNode body, TeamsReadWindow window);

    /** La liste des conversations. */
    TeamsReading<TeamsConversation> conversations(String url, JsonNode body);

    /** Le flux d'activité, d'où viennent les mentions. */
    TeamsReading<TeamsMentionEvent> mentions(String url, JsonNode body, TeamsReadWindow window);

    /** Des résultats de recherche : ce sont des messages. */
    TeamsReading<TeamsMessage> searchResults(String url, JsonNode body, TeamsReadWindow window);

    /** Une ou plusieurs réunions. */
    TeamsReading<TeamsMeeting> meetings(String url, JsonNode body);

    /** Une transcription de réunion enregistrée. */
    TeamsReading<TeamsTranscriptCue> transcript(String url, JsonNode body);

    /**
     * <b>Qui est l'utilisateur relié</b>, quand une réponse observée le dit (F-88 / SF-88-01).
     *
     * <p>Ajout <b>additif</b> à l'interface de SF-87-01, annoncé à F-89 : sans identité, « on m'a
     * mentionné » ne se distingue pas de « on a mentionné quelqu'un », et répondre « non » faute de
     * savoir serait <b>faux</b> — précisément ce que le volet refuse. L'identifiant est celui
     * observé dans la session du navigateur ; il n'est ni deviné, ni demandé à l'utilisateur.</p>
     */
    Optional<TeamsParticipant> self(String url, JsonNode body);

    /**
     * L'adresse de la page suivante, quand le corps en annonce une. Vide quand il n'y en a plus —
     * ou quand la forme n'est plus reconnue, auquel cas la lecture porte déjà un manque
     * {@link TeamsGapKind#PAGINATION_STOPPED}.
     */
    Optional<String> nextPage(String url, JsonNode body);

    /**
     * Confronte une réponse observée à ce que l'adaptateur <b>attendait</b> : combien de champs
     * attendus sont là, lesquels manquent, quelle version d'interface a été vue. C'est la matière
     * première de la sonde de santé (SF-87-03) — et c'est ici, dans l'adaptateur, que vit cette
     * connaissance, parce que c'est ici qu'elle change quand Microsoft change.
     */
    TeamsHealth inspect(String url, JsonNode body);
}
