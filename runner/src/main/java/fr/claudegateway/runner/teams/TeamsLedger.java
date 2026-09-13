package fr.claudegateway.runner.teams;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <b>Le registre d'observation</b> (F-88 / SF-88-01) : tout ce que la page Teams a reçu depuis le
 * rattachement, <b>déjà traduit</b> en objets à nous, fondu sans doublon.
 *
 * <p><b>Pourquoi un registre.</b> F-87 a tranché qu'on <b>observe</b> le trafic au lieu de
 * l'émettre. Une lecture « à la demande » supposerait de redemander à Teams ce qu'il a déjà servi —
 * exactement ce que le cadrage distingue d'un client API déguisé. Le registre est donc le pendant
 * naturel de la décision : Teams a déjà cherché, déjà trié, déjà paginé ; <b>on garde</b>.</p>
 *
 * <p><b>Deux règles.</b> (1) Un objet n'entre que s'il est <b>lisible</b> — l'adaptateur ne rend
 * jamais de moitié. (2) Ce qui n'a pas pu être lu entre <b>aussi</b>, comme manque : un registre qui
 * ne garderait que les succès produirait un compte rendu plausible et faux.</p>
 *
 * <p><b>Borné.</b> Il vit aussi longtemps que la liaison ; sans bornes, une journée de travail dans
 * Teams le ferait grossir sans fin. Les entrées les plus anciennes sortent en premier.</p>
 *
 * <p>Cette classe n'est <b>pas</b> sûre vis-à-vis des threads : elle est toujours utilisée depuis le
 * fil qui exécute l'outil, derrière la synchronisation de {@link TeamsSession}.</p>
 */
final class TeamsLedger {

    /** Au-delà, les messages les plus anciennement observés sortent. Une journée dense en tient. */
    static final int MAX_MESSAGES = 5_000;
    static final int MAX_CONVERSATIONS = 500;
    static final int MAX_MENTIONS = 1_000;
    static final int MAX_MEETINGS = 200;
    static final int MAX_CUES = 20_000;
    /** Les résultats de recherche sont volatils : ils décrivent une question, pas un état. */
    static final int MAX_SEARCH_HITS = 500;

    /**
     * Non final : dès que l'utilisateur relié est identifié, on continue avec le <b>même</b>
     * adaptateur « qui sait qui je suis » ({@link TeamsAdapter#forUser}). Sans cela, « on m'a
     * mentionné » ne pourrait pas se distinguer de « on a mentionné quelqu'un ».
     */
    private TeamsAdapter adapter;
    private TeamsParticipant self;

    private final Map<String, TeamsMessage> messages = new LinkedHashMap<>();
    private final Map<String, TeamsConversation> conversations = new LinkedHashMap<>();
    private final Map<String, TeamsMentionEvent> mentions = new LinkedHashMap<>();
    private final Map<String, TeamsMeeting> meetings = new LinkedHashMap<>();
    private final Map<String, TeamsMessage> searchHits = new LinkedHashMap<>();
    private final Map<String, TeamsTranscriptCue> cues = new LinkedHashMap<>();
    private final Map<String, String> cueMeetings = new LinkedHashMap<>();
    /**
     * Ordre d'observation des répliques (F-100 / SF-100-03). Une transcription servie par SharePoint ne
     * porte pas l'identifiant de sa réunion dans son adresse : la synchro attribue à la réunion affichée
     * les répliques observées <b>pendant</b> qu'elle l'était.
     */
    private final Map<String, Long> cueOrder = new LinkedHashMap<>();
    private long cueCounter;
    /** Par fil : la dernière page de messages annonçait-elle qu'il restait quelque chose avant ? */
    private final Map<String, Boolean> moreBefore = new LinkedHashMap<>();

    private final List<TeamsGap> gaps = new ArrayList<>();
    /** Les manques nés de la DERNIÈRE fournée : c'est ce qu'un outil doit rendre, pas tout l'historique. */
    private final List<TeamsGap> lastGaps = new ArrayList<>();
    private final Set<String> missingFields = new LinkedHashSet<>();
    private final Set<String> apiVersions = new LinkedHashSet<>();
    private int recognizedFields;
    private int expectedFields;
    private int bodiesRead;

    TeamsLedger(TeamsAdapter adapter) {
        this.adapter = adapter;
    }

    /**
     * Fond une fournée de réponses observées. Rend le nombre d'objets <b>nouveaux</b> — c'est cette
     * valeur, et non le nombre de réponses, qui dit si un geste de défilement a servi à quelque
     * chose : une page peut très bien rejouer trois fois les mêmes messages.
     */
    int absorb(List<ObservedResponse> observed, TeamsReadWindow window) {
        lastGaps.clear();
        int before = size();
        // Le plafond de la demande est celui de la RÉCOLTE, pas celui du décodage : l'adaptateur
        // coupe une page dans son ordre d'arrivée (le plus ancien d'abord), alors que le plafond
        // doit mordre du côté ancien de la fenêtre entière. On décode donc sans plafond utile, et
        // c'est le récolteur qui tranche — sinon le plafond couperait les messages RÉCENTS.
        // F-100 / SF-100-03 : le début de la fenêtre n'est pas appliqué au décodage non plus. Un message plus
        // ancien que la fenêtre est la PREUVE que la récolte a remonté jusqu'au début demandé ; le jeter ici
        // empêchait {@link #oldestObservedIn} de le voir, et une lecture bornée remontait jusqu'au trou au lieu
        // de s'arrêter. Les lectures, elles, restent bornées à la fenêtre ({@link #messagesOf}).
        TeamsReadWindow decoding = window == null ? null
                : new TeamsReadWindow(null, window.requestedTo(), null, null,
                        MAX_MESSAGES, false, false);
        for (ObservedResponse response : observed) {
            if (!response.hasBody()) {
                continue; // l'observation a déjà déclaré le manque ; le redire le compterait deux fois
            }
            bodiesRead++;
            absorbOne(response, decoding);
        }
        return size() - before;
    }

    private void absorbOne(ObservedResponse response, TeamsReadWindow window) {
        String url = response.url();
        switch (response.kind()) {
            case CONVERSATION_MESSAGES -> {
                TeamsReading<TeamsMessage> reading = adapter.messages(url, response.body(), window);
                reading.items().forEach(message -> put(messages, message.id(), message, MAX_MESSAGES));
                // Teams dit LUI-MÊME s'il reste quelque chose avant cette page. C'est la seule façon
                // de distinguer « on est au début du fil » de « la page refuse de remonter » — et
                // sans cette distinction, une lecture courte se dirait complète à tort.
                boolean more = adapter.nextPage(url, response.body()).isPresent();
                reading.items().stream().map(TeamsMessage::conversationId).distinct()
                        .forEach(conversationId -> moreBefore.put(conversationId, more));
                account(reading);
            }
            case SEARCH_RESULTS -> {
                TeamsReading<TeamsMessage> reading =
                        adapter.searchResults(url, response.body(), window);
                reading.items().forEach(hit -> {
                    put(searchHits, hit.id(), hit, MAX_SEARCH_HITS);
                    // Un résultat de recherche EST un message : il nourrit aussi la lecture de fil.
                    put(messages, hit.id(), hit, MAX_MESSAGES);
                });
                account(reading);
            }
            case CONVERSATION_LIST -> {
                TeamsReading<TeamsConversation> reading = adapter.conversations(url, response.body());
                reading.items().forEach(conversation ->
                        put(conversations, conversation.id(), conversation, MAX_CONVERSATIONS));
                account(reading);
            }
            case ACTIVITY_FEED -> {
                TeamsReading<TeamsMentionEvent> reading =
                        adapter.mentions(url, response.body(), window);
                reading.items().forEach(event ->
                        put(mentions, keyOf(event), event, MAX_MENTIONS));
                account(reading);
            }
            case MEETING_DETAILS -> {
                TeamsReading<TeamsMeeting> reading = adapter.meetings(url, response.body());
                reading.items().forEach(meeting -> put(meetings, meeting.id(), meeting, MAX_MEETINGS));
                account(reading);
            }
            case MEETING_TRANSCRIPT -> {
                TeamsReading<TeamsTranscriptCue> reading = adapter.transcript(url, response.body());
                String meetingId = meetingIdOf(url);
                reading.items().forEach(cue -> {
                    String key = keyOf(meetingId, cue);
                    if (!cues.containsKey(key)) {
                        cueOrder.put(key, ++cueCounter);
                    }
                    put(cues, key, cue, MAX_CUES);
                    cueMeetings.put(key, meetingId);
                });
                account(reading);
            }
            case PROFILE -> adapter.self(url, response.body()).ifPresent(this::learnSelf);
            default -> {
                // IGNORED, UNKNOWN : rien à fondre. L'observation ne les remonte déjà pas.
            }
        }
    }

    private <T> void put(Map<String, T> store, String key, T value, int max) {
        if (key == null || key.isBlank()) {
            return;
        }
        // Remove-then-put : la version la plus récemment OBSERVÉE gagne, et reprend sa place en
        // queue. C'est ce qui fait qu'une édition écrase l'originale au lieu de coexister avec elle.
        store.remove(key);
        store.put(key, value);
        while (store.size() > max) {
            String oldest = store.keySet().iterator().next();
            store.remove(oldest);
            cueMeetings.remove(oldest);
            cueOrder.remove(oldest);
        }
    }

    private void account(TeamsReading<?> reading) {
        reading.gaps().forEach(gap -> {
            addGap(gap);
            lastGaps.add(gap);
        });
        TeamsHealth health = reading.health();
        recognizedFields += health.recognizedFields();
        expectedFields += health.expectedFields();
        missingFields.addAll(health.missingFields());
        apiVersions.addAll(health.observedApiVersions());
    }

    /**
     * Retient qui est l'utilisateur relié, <b>une fois</b>, et poursuit avec l'adaptateur qui le
     * sait. Les objets déjà fondus gardent {@code self = false} ; le rattrapage se fait par
     * {@link #self()}, que les vues interrogent pour répondre à « m'a-t-on mentionné ? ».
     */
    private void learnSelf(TeamsParticipant participant) {
        if (self != null || participant == null || !participant.isReadable()) {
            return;
        }
        self = participant;
        adapter = adapter.forUser(participant.id());
    }

    /** L'utilisateur relié, ou {@code null} tant qu'aucune réponse observée ne l'a nommé. */
    TeamsParticipant self() {
        return self;
    }

    void addGap(TeamsGap gap) {
        if (gap == null) {
            return;
        }
        for (int index = 0; index < gaps.size(); index++) {
            TeamsGap existing = gaps.get(index);
            if (existing.kind() == gap.kind() && existing.where().equals(gap.where())
                    && existing.detail().equals(gap.detail())) {
                gaps.set(index, new TeamsGap(existing.kind(), existing.where(), existing.detail(),
                        existing.count() + gap.count()));
                return;
            }
        }
        gaps.add(gap);
    }

    // ------------------------------------------------------------------ interrogation

    /**
     * Les messages d'un fil, <b>triés du plus ancien au plus récent</b> et bornés à la fenêtre.
     * {@code conversationId} vide : tous les fils confondus — c'est ce dont a besoin la recherche
     * des engagements, où le fil n'est pas connu d'avance.
     */
    List<TeamsMessage> messagesOf(String conversationId, TeamsReadWindow window) {
        String wanted = conversationId == null ? "" : conversationId.strip();
        List<TeamsMessage> found = new ArrayList<>();
        for (TeamsMessage message : messages.values()) {
            if (!wanted.isEmpty() && !wanted.equals(message.conversationId())) {
                continue;
            }
            if (outside(message.sentAt(), window)) {
                continue;
            }
            found.add(message);
        }
        found.sort(Comparator.comparing(TeamsMessage::sentAt).thenComparing(TeamsMessage::id));
        return found;
    }

    /**
     * Le plus ancien message observé d'un fil, <b>toutes fenêtres confondues</b>. C'est lui qui dit
     * si la récolte a atteint le début de la période demandée : {@link #messagesOf} a déjà écarté ce
     * qui est hors fenêtre, et ne peut donc pas répondre à cette question.
     */
    Instant oldestObservedIn(String conversationId) {
        String wanted = conversationId == null ? "" : conversationId.strip();
        Instant oldest = null;
        for (TeamsMessage message : messages.values()) {
            if (!wanted.isEmpty() && !wanted.equals(message.conversationId())) {
                continue;
            }
            if (message.sentAt() != null && (oldest == null || message.sentAt().isBefore(oldest))) {
                oldest = message.sentAt();
            }
        }
        return oldest;
    }

    /**
     * Teams annonce-t-il encore quelque chose <b>avant</b> ce qu'on a lu ?
     *
     * <p>Vrai par défaut — tant qu'aucune page n'a dit le contraire, on suppose qu'il reste
     * quelque chose. Supposer l'inverse ferait dire « j'ai tout lu » à une lecture qui n'a rien lu,
     * et c'est la seule erreur que ce volet ne s'autorise pas.</p>
     */
    boolean announcesMoreBefore(String conversationId) {
        String wanted = conversationId == null ? "" : conversationId.strip();
        return moreBefore.getOrDefault(wanted, Boolean.TRUE);
    }

    List<TeamsConversation> conversations() {
        List<TeamsConversation> found = new ArrayList<>(conversations.values());
        // Par dernière activité, la plus récente d'abord : c'est l'ordre dans lequel un humain
        // regarde sa liste, et celui dans lequel l'agent doit ouvrir les fils.
        found.sort(Comparator.comparing(
                (TeamsConversation conversation) -> conversation.lastActivityAt() == null
                        ? Instant.EPOCH : conversation.lastActivityAt())
                .reversed());
        return found;
    }

    TeamsConversation conversation(String id) {
        return id == null ? null : conversations.get(id.strip());
    }

    List<TeamsMentionEvent> mentionsIn(TeamsReadWindow window) {
        List<TeamsMentionEvent> found = new ArrayList<>();
        for (TeamsMentionEvent event : mentions.values()) {
            if (!outside(event.at(), window)) {
                found.add(event);
            }
        }
        found.sort(Comparator.comparing(TeamsMentionEvent::at).reversed());
        return found;
    }

    List<TeamsMessage> searchHits(TeamsReadWindow window) {
        List<TeamsMessage> found = new ArrayList<>();
        for (TeamsMessage hit : searchHits.values()) {
            if (!outside(hit.sentAt(), window)) {
                found.add(hit);
            }
        }
        found.sort(Comparator.comparing(TeamsMessage::sentAt).reversed());
        return found;
    }

    List<TeamsMeeting> meetings() {
        List<TeamsMeeting> found = new ArrayList<>(meetings.values());
        found.sort(Comparator.comparing(
                (TeamsMeeting meeting) -> meeting.startedAt() == null
                        ? Instant.EPOCH : meeting.startedAt())
                .reversed());
        return found;
    }

    TeamsMeeting meeting(String id) {
        return id == null ? null : meetings.get(id.strip());
    }

    List<TeamsTranscriptCue> transcriptOf(String meetingId) {
        String wanted = meetingId == null ? "" : meetingId.strip();
        List<TeamsTranscriptCue> found = new ArrayList<>();
        for (Map.Entry<String, TeamsTranscriptCue> entry : cues.entrySet()) {
            String owner = cueMeetings.getOrDefault(entry.getKey(), "");
            if (wanted.isEmpty() || wanted.equals(owner)) {
                found.add(entry.getValue());
            }
        }
        found.sort(Comparator.comparing(TeamsTranscriptCue::at));
        return found;
    }

    /** La marque d'observation des répliques : ce qui arrive ensuite est « depuis la marque ». */
    long cueMark() {
        return cueCounter;
    }

    /** Les répliques observées pour la première fois après la marque, dans l'ordre du temps. */
    List<TeamsTranscriptCue> cuesSince(long mark) {
        List<TeamsTranscriptCue> found = new ArrayList<>();
        for (Map.Entry<String, TeamsTranscriptCue> entry : cues.entrySet()) {
            if (cueOrder.getOrDefault(entry.getKey(), 0L) > mark) {
                found.add(entry.getValue());
            }
        }
        found.sort(Comparator.comparing(TeamsTranscriptCue::at));
        return found;
    }

    List<TeamsGap> gaps() {
        return List.copyOf(gaps);
    }

    /** Les manques de la dernière fournée absorbée. */
    List<TeamsGap> lastGaps() {
        return List.copyOf(lastGaps);
    }

    /**
     * La santé cumulée de tout ce qui a été observé. {@code FULL} tant que rien n'a manqué,
     * {@code NONE} si aucun corps lisible n'a jamais été reconnu — c'est le cas « Teams a changé ».
     */
    TeamsHealth health() {
        if (bodiesRead == 0) {
            return TeamsHealth.full(0);
        }
        return TeamsHealth.of(recognizedFields, expectedFields, new ArrayList<>(missingFields),
                new ArrayList<>(apiVersions),
                "Lu par l'adaptateur " + adapter.version() + " sur " + bodiesRead
                        + (bodiesRead > 1 ? " réponses observées." : " réponse observée."));
    }

    int bodiesRead() {
        return bodiesRead;
    }

    int size() {
        return messages.size() + conversations.size() + mentions.size() + meetings.size()
                + cues.size();
    }

    // ------------------------------------------------------------------ utilitaires

    private static boolean outside(Instant at, TeamsReadWindow window) {
        if (window == null || at == null) {
            return at == null;
        }
        if (window.requestedFrom() != null && at.isBefore(window.requestedFrom())) {
            return true;
        }
        return window.requestedTo() != null && at.isAfter(window.requestedTo());
    }

    private static String keyOf(TeamsMentionEvent event) {
        return event.conversationId() + '#' + event.messageId();
    }

    private static String keyOf(String meetingId, TeamsTranscriptCue cue) {
        return meetingId + '#' + cue.at().toString() + '#' + cue.speakerId() + '#'
                + Integer.toHexString(cue.text().hashCode());
    }

    /**
     * L'identifiant de réunion porté par l'adresse d'une transcription. Il n'y a pas de champ pour
     * cela dans le corps : le lien entre une transcription et sa réunion est dans l'URL, et l'URL
     * est déjà privée de sa chaîne de requête (SF-87-02).
     */
    private static String meetingIdOf(String url) {
        String lower = url == null ? "" : url.toLowerCase(Locale.ROOT);
        int marker = lower.indexOf("/transcripts");
        if (marker <= 0) {
            return "";
        }
        String head = url.substring(0, marker);
        int slash = head.lastIndexOf('/');
        return slash >= 0 && slash + 1 < head.length() ? head.substring(slash + 1) : "";
    }
}
