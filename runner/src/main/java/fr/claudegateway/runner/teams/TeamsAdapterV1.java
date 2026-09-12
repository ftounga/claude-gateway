package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * <b>La couche unique</b> : la seule classe du produit qui sait quels champs Teams porte
 * (F-87 / SF-87-01).
 *
 * <p>Non publique. On l'obtient par {@link TeamsAdapters#current()}, sous la forme de l'interface
 * {@link TeamsAdapter} — de sorte qu'aucun appelant ne puisse dépendre de ce qu'elle sait.</p>
 *
 * <p><b>Deux règles gouvernent tout ce fichier.</b></p>
 * <ol>
 *   <li><b>Jamais à moitié faux.</b> Un objet n'est rendu que si tous ses champs obligatoires ont
 *       été lus. Sinon il n'est pas rendu, et un {@link TeamsGap} le dit. Une lecture incomplète qui
 *       se tait produirait un compte rendu plausible et faux.</li>
 *   <li><b>Rien n'est recopié en aveugle.</b> Chaque champ est lu par son nom. Il n'existe donc
 *       aucun chemin par lequel un cookie ou un jeton Microsoft pourrait franchir cette couche,
 *       même si Microsoft en ajoutait un demain dans un corps de réponse.</li>
 * </ol>
 *
 * <p><b>Hypothèse assumée</b> : la forme lue ici est écrite d'après la forme publiquement
 * documentée des réponses du service de conversation, <b>sans accès à un vrai compte Teams</b>.
 * Elle est vérifiée par des échantillons fabriqués (voir {@code src/test/resources/teams}), et
 * confrontée au réel par la sonde de santé (SF-87-03) au premier branchement — qui refusera
 * bruyamment plutôt que de rendre à moitié.</p>
 */
final class TeamsAdapterV1 implements TeamsAdapter {

    private static final String VERSION = "v1";

    /** Champs attendus d'une page de messages : la base du décompte de la sonde de santé. */
    private static final List<String> EXPECTED_MESSAGE_FIELDS =
            List.of("messages", "id", "from", "imdisplayname", "originalarrivaltime", "messagetype",
                    "content");

    private static final List<String> EXPECTED_CONVERSATION_FIELDS =
            List.of("conversations", "id", "threadProperties", "members");

    private static final List<String> EXPECTED_ACTIVITY_FIELDS =
            List.of("activities", "activityType", "activityTimestamp", "sourceUserImDisplayName",
                    "sourceThreadId");

    private static final List<String> EXPECTED_MEETING_FIELDS =
            List.of("value", "id", "subject", "startTime");

    private static final List<String> EXPECTED_TRANSCRIPT_FIELDS =
            List.of("entries", "text", "speakerDisplayName", "startDateTime");

    private final ObjectMapper mapper = new ObjectMapper();
    private final String selfId;

    TeamsAdapterV1() {
        this("");
    }

    private TeamsAdapterV1(String selfId) {
        this.selfId = selfId == null ? "" : selfId.strip();
    }

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public TeamsAdapter forUser(String mri) {
        return new TeamsAdapterV1(mri);
    }

    @Override
    public TeamsPayloadKind classify(String url) {
        return TeamsUrls.classify(url);
    }

    // ------------------------------------------------------------------ messages

    @Override
    public TeamsReading<TeamsMessage> messages(String url, JsonNode body, TeamsReadWindow window) {
        TeamsReadWindow effective = window == null ? TeamsReadWindow.standard(Instant.now()) : window;
        JsonNode array = arrayAt(body, "messages", "value");
        if (array == null) {
            return unreadable(url, body, effective, "page de messages");
        }
        return readMessages(url, body, array, effective);
    }

    @Override
    public TeamsReading<TeamsMessage> searchResults(String url, JsonNode body,
            TeamsReadWindow window) {
        TeamsReadWindow effective = window == null ? TeamsReadWindow.standard(Instant.now()) : window;
        JsonNode array = arrayAt(body, "messages", "value", "hits");
        if (array == null) {
            return unreadable(url, body, effective, "résultats de recherche");
        }
        // Un résultat de recherche enveloppe parfois le message : on déplie, sinon on lit tel quel.
        List<JsonNode> unwrapped = new ArrayList<>();
        array.forEach(entry -> {
            JsonNode message = entry.get("message");
            unwrapped.add(message != null && message.isObject() ? message : entry);
        });
        return readMessages(url, body, unwrapped, effective);
    }

    private TeamsReading<TeamsMessage> readMessages(String url, JsonNode body,
            Iterable<JsonNode> entries, TeamsReadWindow window) {
        List<TeamsMessage> messages = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        Instant oldest = null;
        Instant newest = null;
        boolean capReached = false;
        String where = conversationHint(body, entries);

        for (JsonNode entry : entries) {
            if (messages.size() >= window.cap()) {
                capReached = true;
                break;
            }
            TeamsMessageKind kind = messageKind(TeamsJson.text(entry, "messagetype", "messageType"));
            if (kind == null) {
                add(gaps, TeamsGap.of(TeamsGapKind.UNKNOWN_MESSAGE_KIND, where,
                        TeamsJson.text(entry, "messagetype", "messageType")));
                continue;
            }
            TeamsMessage message = readMessage(entry, kind, where, gaps);
            if (message == null) {
                continue;
            }
            if (outsideWindow(message.sentAt(), window)) {
                continue;
            }
            messages.add(message);
            oldest = oldest == null || message.sentAt().isBefore(oldest) ? message.sentAt() : oldest;
            newest = newest == null || message.sentAt().isAfter(newest) ? message.sentAt() : newest;
        }

        boolean reachedStart = nextPage(url, body).isEmpty() && !capReached;
        TeamsReadWindow covered = window.covering(oldest, newest, capReached, reachedStart);
        if (capReached) {
            add(gaps, TeamsGap.of(TeamsGapKind.CAP_REACHED, where,
                    "plafond de " + window.cap() + " messages"));
        }
        return new TeamsReading<>(messages, gaps, covered, healthOf(url, body));
    }

    /**
     * Un message, <b>ou rien</b>. Aucun message partiel n'est jamais rendu : l'identifiant, l'auteur
     * et l'horodatage sont obligatoires, et leur absence produit un manque nommé.
     */
    private TeamsMessage readMessage(JsonNode entry, TeamsMessageKind kind, String where,
            List<TeamsGap> gaps) {
        String id = TeamsJson.text(entry, "id", "messageId", "clientmessageid");
        if (id.isEmpty()) {
            add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, where, "id"));
            return null;
        }
        TeamsParticipant author = readAuthor(entry);
        if (!author.isReadable()) {
            add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, where, "from"));
            return null;
        }
        Instant sentAt = TeamsJson.instant(entry, "originalarrivaltime", "composetime",
                "createdDateTime");
        if (sentAt == null) {
            add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, where, "originalarrivaltime"));
            return null;
        }

        JsonNode properties = entry.get("properties");
        String html = TeamsJson.text(entry, "content", "body");
        String text = kind == TeamsMessageKind.TEXT ? html : TeamsJson.plainText(html);
        if (text.isEmpty() && kind != TeamsMessageKind.SYSTEM_EVENT
                && kind != TeamsMessageKind.MEETING_EVENT && kind != TeamsMessageKind.CALL) {
            // Un message vide n'est acceptable que s'il porte une pièce jointe : sinon on ne sait
            // pas ce qui a été dit, et le prétendre serait faux.
            if (readAttachments(properties).isEmpty()) {
                add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, where, "content"));
                return null;
            }
        }

        String parentId = TeamsJson.text(entry, "parentMessageId", "parentId");
        return new TeamsMessage(id,
                conversationId(entry),
                "0".equals(parentId) ? "" : parentId,
                author,
                sentAt,
                editedAt(properties),
                deleted(properties),
                kind,
                text,
                html,
                readMentions(properties),
                readAttachments(properties),
                readReactions(properties),
                TeamsJson.text(entry, "messagelink", "webUrl"));
    }

    private TeamsParticipant readAuthor(JsonNode entry) {
        String from = TeamsJson.text(entry, "from", "fromMri", "senderId");
        return new TeamsParticipant(mriOf(from), TeamsJson.text(entry, "imdisplayname",
                "displayName", "senderDisplayName"), null, isSelf(mriOf(from)));
    }

    private List<TeamsMention> readMentions(JsonNode properties) {
        JsonNode node = TeamsJson.embedded(mapper, properties, "mentions");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TeamsMention> mentions = new ArrayList<>();
        node.forEach(entry -> {
            String mri = mriOf(TeamsJson.text(entry, "mri", "itemid", "id"));
            String label = TeamsJson.text(entry, "mentionedText", "displayName", "text");
            mentions.add(new TeamsMention(mri, label,
                    mentionKind(TeamsJson.text(entry, "mentionType", "type")),
                    label.isEmpty() ? "" : "@" + label));
        });
        return mentions;
    }

    private List<TeamsAttachmentRef> readAttachments(JsonNode properties) {
        JsonNode node = TeamsJson.embedded(mapper, properties, "files", "attachments");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TeamsAttachmentRef> files = new ArrayList<>();
        node.forEach(entry -> files.add(new TeamsAttachmentRef(
                TeamsJson.text(entry, "itemid", "id"),
                TeamsJson.text(entry, "title", "fileName", "name"),
                TeamsJson.text(entry, "fileType", "contentType"),
                TeamsJson.number(entry, -1, "fileSize", "size"),
                TeamsJson.text(entry, "objectUrl", "contentUrl", "url"))));
        return files;
    }

    private List<TeamsReaction> readReactions(JsonNode properties) {
        JsonNode node = TeamsJson.embedded(mapper, properties, "emotions", "reactions");
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<TeamsReaction> reactions = new ArrayList<>();
        node.forEach(entry -> {
            String kind = TeamsJson.text(entry, "key", "type", "reactionType");
            JsonNode users = entry.get("users");
            int count = users != null && users.isArray() ? users.size()
                    : (int) TeamsJson.number(entry, 1, "count");
            if (!kind.isEmpty()) {
                reactions.add(new TeamsReaction(kind, count));
            }
        });
        return reactions;
    }

    // ------------------------------------------------------------------ conversations

    @Override
    public TeamsReading<TeamsConversation> conversations(String url, JsonNode body) {
        TeamsReadWindow window = TeamsReadWindow.standard(Instant.now());
        JsonNode array = arrayAt(body, "conversations", "value");
        if (array == null) {
            return unreadable(url, body, window, "liste des conversations");
        }
        List<TeamsConversation> conversations = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        for (JsonNode entry : array) {
            String id = TeamsJson.text(entry, "id", "conversationId", "threadId");
            if (id.isEmpty()) {
                add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, "liste des conversations", "id"));
                continue;
            }
            JsonNode threadProperties = entry.get("threadProperties");
            String topic = TeamsJson.text(threadProperties, "topic");
            if (topic.isEmpty()) {
                topic = TeamsJson.text(entry, "topic", "title");
            }
            conversations.add(new TeamsConversation(id,
                    conversationKind(TeamsJson.text(entry, "threadType", "chatType", "type")),
                    topic,
                    readMembers(entry),
                    TeamsJson.instant(entry.get("lastMessage"), "originalarrivaltime", "composetime"),
                    TeamsJson.text(entry, "webUrl", "link")));
        }
        return new TeamsReading<>(conversations, gaps, window, healthOf(url, body));
    }

    private List<TeamsParticipant> readMembers(JsonNode entry) {
        JsonNode members = entry.get("members");
        if (members == null || !members.isArray()) {
            return List.of();
        }
        List<TeamsParticipant> participants = new ArrayList<>();
        members.forEach(member -> {
            String mri = mriOf(TeamsJson.text(member, "mri", "id", "objectId"));
            participants.add(new TeamsParticipant(mri,
                    TeamsJson.text(member, "friendlyName", "displayName"),
                    TeamsJson.text(member, "email", "userPrincipalName"), isSelf(mri)));
        });
        return participants;
    }

    // ------------------------------------------------------------------ mentions

    @Override
    public TeamsReading<TeamsMentionEvent> mentions(String url, JsonNode body,
            TeamsReadWindow window) {
        TeamsReadWindow effective = window == null ? TeamsReadWindow.standard(Instant.now()) : window;
        JsonNode array = arrayAt(body, "activities", "value");
        if (array == null) {
            return unreadable(url, body, effective, "flux d'activité");
        }
        List<TeamsMentionEvent> events = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        Instant oldest = null;
        Instant newest = null;
        boolean capReached = false;

        for (JsonNode entry : array) {
            String activity = TeamsJson.text(entry, "activityType", "type")
                    .toLowerCase(Locale.ROOT);
            if (!activity.contains("mention")) {
                continue; // Le flux porte aussi les réactions et les réponses : ce n'est pas un défaut.
            }
            if (events.size() >= effective.cap()) {
                capReached = true;
                break;
            }
            Instant at = TeamsJson.instant(entry, "activityTimestamp", "originalarrivaltime");
            String messageId = TeamsJson.text(entry, "sourceMessageId", "messageId", "id");
            String authorMri = mriOf(TeamsJson.text(entry, "sourceUserId", "sourceUserImMri",
                    "from"));
            TeamsParticipant author = new TeamsParticipant(authorMri,
                    TeamsJson.text(entry, "sourceUserImDisplayName", "displayName"), null,
                    isSelf(authorMri));
            TeamsMention mention = new TeamsMention(selfId,
                    TeamsJson.text(entry, "targetDisplayName"),
                    mentionKind(TeamsJson.text(entry, "activitySubtype", "mentionType")), "");
            TeamsMentionEvent event = new TeamsMentionEvent(mention, messageId,
                    TeamsJson.text(entry, "sourceThreadId", "conversationId"),
                    TeamsJson.text(entry, "sourceThreadTopic", "topic"), author, at,
                    TeamsJson.plainText(TeamsJson.text(entry, "messagePreview", "preview")),
                    TeamsJson.text(entry, "messagelink", "webUrl"));
            if (!event.isReadable()) {
                add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, "flux d'activité",
                        at == null ? "activityTimestamp" : "sourceMessageId"));
                continue;
            }
            events.add(event);
            oldest = oldest == null || at.isBefore(oldest) ? at : oldest;
            newest = newest == null || at.isAfter(newest) ? at : newest;
        }
        if (capReached) {
            add(gaps, TeamsGap.of(TeamsGapKind.CAP_REACHED, "flux d'activité",
                    "plafond de " + effective.cap() + " mentions"));
        }
        boolean reachedStart = nextPage(url, body).isEmpty() && !capReached;
        return new TeamsReading<>(events, gaps,
                effective.covering(oldest, newest, capReached, reachedStart), healthOf(url, body));
    }

    // ------------------------------------------------------------------ réunions

    @Override
    public TeamsReading<TeamsMeeting> meetings(String url, JsonNode body) {
        TeamsReadWindow window = TeamsReadWindow.standard(Instant.now());
        JsonNode array = arrayAt(body, "value", "meetings");
        if (array == null && body != null && body.isObject() && body.has("id")) {
            array = mapper.createArrayNode().add(body); // détail d'une réunion unique
        }
        if (array == null) {
            return unreadable(url, body, window, "réunion");
        }
        List<TeamsMeeting> meetings = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        for (JsonNode entry : array) {
            TeamsMeeting meeting = new TeamsMeeting(
                    TeamsJson.text(entry, "id", "meetingId", "iCalUid"),
                    TeamsJson.text(entry, "subject", "title"),
                    TeamsJson.instant(entry, "startTime", "startDateTime"),
                    TeamsJson.instant(entry, "endTime", "endDateTime"),
                    mriOf(TeamsJson.text(entry, "organizerId", "organizer")),
                    readParticipants(entry),
                    TeamsJson.text(entry, "threadId", "conversationId"),
                    TeamsJson.flag(entry, "isRecorded", "recorded"),
                    TeamsJson.flag(entry, "isTranscriptAvailable", "transcriptAvailable"),
                    TeamsJson.text(entry, "joinWebUrl", "webUrl"));
            if (!meeting.isReadable()) {
                add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, "réunion",
                        meeting.id().isEmpty() ? "id" : "startTime"));
                continue;
            }
            meetings.add(meeting);
        }
        return new TeamsReading<>(meetings, gaps, window, healthOf(url, body));
    }

    private List<TeamsParticipant> readParticipants(JsonNode entry) {
        JsonNode array = entry.get("participants");
        if (array == null || !array.isArray()) {
            return List.of();
        }
        List<TeamsParticipant> participants = new ArrayList<>();
        array.forEach(node -> {
            String mri = mriOf(TeamsJson.text(node, "mri", "id"));
            participants.add(new TeamsParticipant(mri,
                    TeamsJson.text(node, "displayName", "friendlyName"),
                    TeamsJson.text(node, "email"), isSelf(mri)));
        });
        return participants;
    }

    // ------------------------------------------------------------------ transcription

    @Override
    public TeamsReading<TeamsTranscriptCue> transcript(String url, JsonNode body) {
        TeamsReadWindow window = TeamsReadWindow.standard(Instant.now());
        JsonNode array = arrayAt(body, "entries", "value", "cues");
        if (array == null) {
            return unreadable(url, body, window, "transcription");
        }
        List<TeamsTranscriptCue> cues = new ArrayList<>();
        List<TeamsGap> gaps = new ArrayList<>();
        Instant oldest = null;
        Instant newest = null;
        for (JsonNode entry : array) {
            Instant at = TeamsJson.instant(entry, "startDateTime", "startTime");
            String text = TeamsJson.text(entry, "text", "content");
            if (at == null || text.isEmpty()) {
                add(gaps, TeamsGap.of(TeamsGapKind.MISSING_FIELD, "transcription",
                        at == null ? "startDateTime" : "text"));
                continue;
            }
            long durationMs = TeamsJson.number(entry, -1, "durationMs");
            if (durationMs < 0) {
                durationMs = offsetMillis(TeamsJson.text(entry, "endOffset"))
                        - offsetMillis(TeamsJson.text(entry, "startOffset"));
                durationMs = durationMs > 0 ? durationMs : -1;
            }
            cues.add(new TeamsTranscriptCue(at, durationMs,
                    mriOf(TeamsJson.text(entry, "speakerId", "speakerMri")),
                    TeamsJson.text(entry, "speakerDisplayName", "speaker"), text));
            oldest = oldest == null || at.isBefore(oldest) ? at : oldest;
            newest = newest == null || at.isAfter(newest) ? at : newest;
        }
        return new TeamsReading<>(cues, gaps, window.covering(oldest, newest, false, true),
                healthOf(url, body));
    }

    /** « 00:01:05.120 » → millisecondes. {@code 0} si la forme n'est pas celle attendue. */
    private static long offsetMillis(String offset) {
        if (offset == null || offset.isBlank()) {
            return 0L;
        }
        String[] parts = offset.strip().split(":");
        if (parts.length != 3) {
            return 0L;
        }
        try {
            long hours = Long.parseLong(parts[0]);
            long minutes = Long.parseLong(parts[1]);
            double seconds = Double.parseDouble(parts[2].replace(',', '.'));
            return (long) (((hours * 60 + minutes) * 60 + seconds) * 1000);
        } catch (NumberFormatException e) {
            return 0L;
        }
    }

    // ------------------------------------------------------------------ pagination et santé

    @Override
    public Optional<String> nextPage(String url, JsonNode body) {
        if (body == null) {
            return Optional.empty();
        }
        JsonNode metadata = body.get("_metadata");
        String link = TeamsJson.text(metadata, "backwardLink", "syncState", "nextLink");
        if (link.isEmpty()) {
            link = TeamsJson.text(body, "nextLink", "@odata.nextLink", "backwardLink");
        }
        return link.isEmpty() ? Optional.empty() : Optional.of(link);
    }

    @Override
    public TeamsHealth inspect(String url, JsonNode body) {
        return healthOf(url, body);
    }

    /**
     * Le décompte qui fonde les trois issues de la sonde : combien des champs attendus pour ce genre
     * de réponse sont effectivement là. Tout reconnu → on travaille ; partiellement → on travaille
     * et on le dit ; rien → on refuse.
     */
    private TeamsHealth healthOf(String url, JsonNode body) {
        List<String> expected = expectedFieldsFor(classify(url));
        if (expected.isEmpty()) {
            return TeamsHealth.full(0);
        }
        Set<String> present = new LinkedHashSet<>();
        collectFieldNames(body, present, 0);
        List<String> missing = new ArrayList<>();
        int recognized = 0;
        for (String field : expected) {
            if (present.contains(field)) {
                recognized++;
            } else {
                missing.add(field);
            }
        }
        return TeamsHealth.of(recognized, expected.size(), missing, TeamsUrls.apiVersions(url),
                recognized >= expected.size() ? "" : "Lu par l'adaptateur " + VERSION + ".");
    }

    private static List<String> expectedFieldsFor(TeamsPayloadKind kind) {
        return switch (kind) {
            case CONVERSATION_MESSAGES, SEARCH_RESULTS -> EXPECTED_MESSAGE_FIELDS;
            case CONVERSATION_LIST -> EXPECTED_CONVERSATION_FIELDS;
            case ACTIVITY_FEED -> EXPECTED_ACTIVITY_FIELDS;
            case MEETING_DETAILS -> EXPECTED_MEETING_FIELDS;
            case MEETING_TRANSCRIPT -> EXPECTED_TRANSCRIPT_FIELDS;
            default -> List.of();
        };
    }

    /** Noms de champs présents, sur quelques niveaux : assez pour compter, jamais pour recopier. */
    private static void collectFieldNames(JsonNode node, Set<String> names, int depth) {
        if (node == null || depth > 4) {
            return;
        }
        if (node.isObject()) {
            node.fieldNames().forEachRemaining(name -> {
                names.add(name);
                collectFieldNames(node.get(name), names, depth + 1);
            });
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                collectFieldNames(child, names, depth + 1);
            }
        }
    }

    // ------------------------------------------------------------------ utilitaires

    private <T> TeamsReading<T> unreadable(String url, JsonNode body, TeamsReadWindow window,
            String what) {
        TeamsHealth health = healthOf(url, body);
        TeamsHealth refused = new TeamsHealth(TeamsHealthVerdict.NONE, health.recognizedFields(),
                health.expectedFields(), health.missingFields(), health.observedApiVersions(),
                "Lu par l'adaptateur " + VERSION + ".");
        return TeamsReading.unreadable(window,
                TeamsGap.of(TeamsGapKind.UNRECOGNIZED_PAYLOAD, what, "forme de réponse inattendue"),
                refused);
    }

    private static JsonNode arrayAt(JsonNode body, String... names) {
        if (body == null) {
            return null;
        }
        for (String name : names) {
            JsonNode node = body.get(name);
            if (node != null && node.isArray()) {
                return node;
            }
        }
        return null;
    }

    private static void add(List<TeamsGap> gaps, TeamsGap gap) {
        for (int index = 0; index < gaps.size(); index++) {
            TeamsGap existing = gaps.get(index);
            if (existing.kind() == gap.kind() && existing.where().equals(gap.where())
                    && existing.detail().equals(gap.detail())) {
                gaps.set(index, existing.plusOne());
                return;
            }
        }
        gaps.add(gap);
    }

    /** Dernière modification, ou {@code null} : un message jamais modifié n'en porte pas. */
    private static Instant editedAt(JsonNode properties) {
        return TeamsJson.instant(properties, "edittime", "editTime");
    }

    /**
     * Supprimé chez Microsoft. On le <b>dit</b> plutôt que de faire disparaître la ligne : un
     * compte rendu qui tait une suppression laisse croire que le message n'a jamais existé.
     */
    private static boolean deleted(JsonNode properties) {
        String raw = TeamsJson.text(properties, "deletetime", "deleteTime");
        return !raw.isEmpty() && !"0".equals(raw);
    }

    private boolean isSelf(String mri) {
        return !selfId.isEmpty() && selfId.equals(mri);
    }

    /** Un identifiant, qu'il vienne d'une adresse (« …/users/8:orgid:… ») ou déjà nu. */
    private static String mriOf(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        String value = raw.strip();
        int slash = value.lastIndexOf('/');
        return slash >= 0 && slash + 1 < value.length() ? value.substring(slash + 1) : value;
    }

    private static String conversationId(JsonNode entry) {
        String id = TeamsJson.text(entry, "conversationid", "conversationId", "threadId");
        return id.isEmpty() ? mriOf(stripSuffix(TeamsJson.text(entry, "conversationLink"))) : id;
    }

    private static String stripSuffix(String link) {
        if (link.endsWith("/messages")) {
            return link.substring(0, link.length() - "/messages".length());
        }
        return link;
    }

    private static String conversationHint(JsonNode body, Iterable<JsonNode> entries) {
        for (JsonNode entry : entries) {
            String id = conversationId(entry);
            if (!id.isEmpty()) {
                return "conversation " + id;
            }
        }
        return body != null && body.has("conversationid")
                ? "conversation " + TeamsJson.text(body, "conversationid") : "conversation";
    }

    private static boolean outsideWindow(Instant sentAt, TeamsReadWindow window) {
        if (window.requestedFrom() != null && sentAt.isBefore(window.requestedFrom())) {
            return true;
        }
        return window.requestedTo() != null && sentAt.isAfter(window.requestedTo());
    }

    private static TeamsMessageKind messageKind(String declared) {
        String kind = declared == null ? "" : declared.strip().toLowerCase(Locale.ROOT);
        if (kind.isEmpty()) {
            return null;
        }
        if (kind.startsWith("richtext/media") || kind.contains("card")) {
            return TeamsMessageKind.CARD;
        }
        if (kind.startsWith("richtext")) {
            return TeamsMessageKind.RICH_TEXT;
        }
        if (kind.equals("text") || kind.equals("message")) {
            return TeamsMessageKind.TEXT;
        }
        if (kind.contains("call")) {
            return TeamsMessageKind.CALL;
        }
        if (kind.contains("recording") || kind.contains("transcript") || kind.contains("meeting")) {
            return TeamsMessageKind.MEETING_EVENT;
        }
        if (kind.startsWith("threadactivity") || kind.startsWith("systemevent")) {
            return TeamsMessageKind.SYSTEM_EVENT;
        }
        return null; // Genre inconnu : on ne devine pas, on déclare un manque.
    }

    private static TeamsMentionKind mentionKind(String declared) {
        String kind = declared == null ? "" : declared.strip().toLowerCase(Locale.ROOT);
        if (kind.contains("everyone") || kind.contains("all")) {
            return TeamsMentionKind.EVERYONE;
        }
        if (kind.contains("channel") || kind.contains("thread")) {
            return TeamsMentionKind.CHANNEL;
        }
        if (kind.contains("tag") || kind.contains("group")) {
            return TeamsMentionKind.TAG;
        }
        return TeamsMentionKind.PERSON;
    }

    private static TeamsConversationKind conversationKind(String declared) {
        String kind = declared == null ? "" : declared.strip().toLowerCase(Locale.ROOT);
        if (kind.contains("oneonone") || kind.contains("one_on_one") || kind.contains("1:1")) {
            return TeamsConversationKind.ONE_ON_ONE;
        }
        if (kind.contains("meeting")) {
            return TeamsConversationKind.MEETING_CHAT;
        }
        if (kind.contains("channel") || kind.contains("space") || kind.contains("topic")) {
            return TeamsConversationKind.CHANNEL;
        }
        return TeamsConversationKind.GROUP;
    }
}
