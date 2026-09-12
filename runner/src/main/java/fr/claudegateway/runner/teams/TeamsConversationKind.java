package fr.claudegateway.runner.teams;

/**
 * Le genre d'une conversation (F-87 / SF-87-01). Il change ce qu'on peut en dire : « Paul m'a
 * écrit » dans un tête-à-tête, « quelqu'un a écrit dans le canal Migration » ailleurs.
 */
public enum TeamsConversationKind {

    /** Tête-à-tête. */
    ONE_ON_ONE,

    /** Groupe privé. */
    GROUP,

    /** Canal d'une équipe. */
    CHANNEL,

    /** Conversation attachée à une réunion. */
    MEETING_CHAT
}
