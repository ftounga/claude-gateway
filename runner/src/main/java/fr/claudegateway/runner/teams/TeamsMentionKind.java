package fr.claudegateway.runner.teams;

/**
 * Ce qu'une mention désigne (F-87 / SF-87-01). La distinction n'est pas décorative : « on m'a
 * nommé » et « on a nommé tout le canal » n'engagent pas la même chose, et un compte rendu qui les
 * confondrait ferait croire à un engagement personnel là où il n'y en a pas.
 */
public enum TeamsMentionKind {

    /** Une personne nommément désignée. */
    PERSON,

    /** Une étiquette d'équipe (un groupe nommé). */
    TAG,

    /** Le canal entier. */
    CHANNEL,

    /** Tout le monde. */
    EVERYONE
}
