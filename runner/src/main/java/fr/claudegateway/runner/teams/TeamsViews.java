package fr.claudegateway.runner.teams;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * La mise en JSON des objets du domaine (F-88 / SF-88-01) — <b>un seul endroit</b>, pour que F-89
 * n'ait qu'une forme à afficher quel que soit l'outil qui l'a produite.
 *
 * <p>Rien n'est recopié en aveugle : chaque champ est écrit par son nom, depuis un objet du domaine
 * qui a lui-même été rempli champ par champ par l'adaptateur. Il n'existe donc <b>aucun chemin</b>
 * par lequel un jeton ou un cookie glissé dans une réponse Microsoft pourrait ressortir ici.</p>
 */
final class TeamsViews {

    private TeamsViews() {
    }

    static void message(ArrayNode target, TeamsMessage message, TeamsParticipant self) {
        ObjectNode node = target.addObject();
        node.put("id", message.id());
        node.put("conversationId", message.conversationId());
        node.put("parentId", message.parentId());
        node.put("authorId", message.author().id());
        node.put("author", message.author().label());
        node.put("self", message.author().self());
        TeamsToolResult.instant(node, "sentAt", message.sentAt());
        TeamsToolResult.instant(node, "editedAt", message.editedAt());
        node.put("deleted", message.deleted());
        node.put("kind", message.kind().name());
        node.put("text", message.text());
        // null quand l'utilisateur relié n'a pas encore été identifié : répondre « non » faute de
        // savoir serait une affirmation fausse, et c'est exactement ce que le volet refuse.
        if (self == null) {
            node.putNull("mentionsMe");
        } else {
            node.put("mentionsMe", message.mentions(self));
        }
        node.put("webUrl", message.webUrl());
        ArrayNode mentions = node.putArray("mentions");
        message.mentions().forEach(mention -> {
            ObjectNode entry = mentions.addObject();
            entry.put("targetId", mention.targetId());
            entry.put("target", mention.targetDisplayName());
            entry.put("kind", mention.kind().name());
        });
        ArrayNode attachments = node.putArray("attachments");
        message.attachments().forEach(attachment -> {
            ObjectNode entry = attachments.addObject();
            entry.put("name", attachment.name());
            entry.put("type", attachment.contentType());
            entry.put("bytes", attachment.sizeBytes());
        });
        ArrayNode reactions = node.putArray("reactions");
        message.reactions().forEach(reaction -> {
            ObjectNode entry = reactions.addObject();
            entry.put("kind", reaction.kind());
            entry.put("count", reaction.count());
        });
    }

    static void conversation(ArrayNode target, TeamsConversation conversation) {
        conversation(target.addObject(), conversation);
    }

    static void conversation(ObjectNode node, TeamsConversation conversation) {
        if (conversation == null) {
            return;
        }
        node.put("id", conversation.id());
        node.put("label", conversation.label());
        node.put("kind", conversation.kind().name());
        node.put("topic", conversation.topic());
        TeamsToolResult.instant(node, "lastActivityAt", conversation.lastActivityAt());
        node.put("webUrl", conversation.webUrl());
        ArrayNode participants = node.putArray("participants");
        conversation.participants().forEach(participant -> {
            ObjectNode entry = participants.addObject();
            entry.put("id", participant.id());
            entry.put("name", participant.label());
            entry.put("self", participant.self());
        });
    }

    static void mention(ArrayNode target, TeamsMentionEvent event) {
        ObjectNode node = target.addObject();
        node.put("messageId", event.messageId());
        node.put("conversationId", event.conversationId());
        node.put("conversation", event.conversationLabel());
        node.put("authorId", event.author().id());
        node.put("author", event.author().label());
        TeamsToolResult.instant(node, "at", event.at());
        node.put("preview", event.preview());
        node.put("kind", event.mention().kind().name());
        node.put("webUrl", event.webUrl());
    }

    static void meeting(ArrayNode target, TeamsMeeting meeting) {
        ObjectNode node = target.addObject();
        node.put("id", meeting.id());
        node.put("subject", meeting.subject());
        TeamsToolResult.instant(node, "startedAt", meeting.startedAt());
        TeamsToolResult.instant(node, "endedAt", meeting.endedAt());
        node.put("organizerId", meeting.organizerId());
        node.put("conversationId", meeting.conversationId());
        node.put("recorded", meeting.recorded());
        node.put("transcriptAvailable", meeting.transcriptAvailable());
        node.put("webUrl", meeting.webUrl());
        ArrayNode participants = node.putArray("participants");
        meeting.participants().forEach(participant -> {
            ObjectNode entry = participants.addObject();
            entry.put("id", participant.id());
            entry.put("name", participant.label());
            entry.put("self", participant.self());
        });
    }

    static void cue(ArrayNode target, TeamsTranscriptCue cue) {
        ObjectNode node = target.addObject();
        TeamsToolResult.instant(node, "at", cue.at());
        node.put("durationMs", cue.durationMs());
        node.put("speakerId", cue.speakerId());
        node.put("speaker", cue.speakerDisplayName());
        node.put("text", cue.text());
    }
}
