package fr.claudegateway.runner.teams;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import fr.claudegateway.runner.ToolContext;
import fr.claudegateway.runner.ToolExecutor;
import fr.claudegateway.runner.ToolOutcome;

/**
 * <b>Les outils Teams</b> donnés à l'agent (F-87 / SF-87-03 pour {@code teams_status},
 * F-88 / SF-88-01 pour les outils de lecture).
 *
 * <p><b>Aucun n'est un bouton.</b> L'agent les compose : « qu'est-ce qu'on attend de moi ? »
 * appellera les mentions, puis la recherche sur les variantes du nom, puis la lecture des fils
 * récemment actifs — et il conclura. Une autre question appellera autre chose.</p>
 *
 * <p><b>Deux règles de forme, valables pour tous.</b></p>
 * <ol>
 *   <li>Une liaison impossible reste un <b>succès</b> d'outil porteur d'un état et de son remède.
 *       Une erreur d'outil ferait dire à l'agent « je n'ai pas réussi », là où il faut dire
 *       « lancez votre navigateur comme ceci ».</li>
 *   <li>Chaque résultat porte, à côté de ce qui a été lu, <b>ce qui n'a pas pu l'être</b> et la
 *       <b>fenêtre réellement lue</b>. Un trou se voit ; un trou silencieux ne se voit jamais.</li>
 * </ol>
 */
public final class TeamsTools implements ToolExecutor {

    public static final String STATUS = "teams_status";
    /** Retrouver une conversation par personne, groupe ou sujet (F-88 / SF-88-01). */
    public static final String FIND_CONVERSATIONS = "teams_find_conversations";
    /** Lire une conversation sur une fenêtre de temps (F-88 / SF-88-01). */
    public static final String READ_CONVERSATION = "teams_read_conversation";
    public static final String CAPABILITY = "teams";

    private final ObjectMapper mapper = new ObjectMapper();
    private final TeamsSession session;
    private final TeamsProbe probe;
    private final BrowserLink.Sleeper sleeper;
    private final TeamsScopeNotice scope = new TeamsScopeNotice();
    private final boolean enabled;
    private final String disabledReason;

    private volatile boolean firstUseSaid;
    private TeamsLedger ledger;

    public TeamsTools(TeamsSession session, BrowserLink.Sleeper sleeper) {
        this.session = session;
        this.probe = new TeamsProbe(session.adapter());
        this.sleeper = sleeper;
        this.enabled = true;
        this.disabledReason = "";
    }

    public static TeamsTools disabled(String reason) {
        return new TeamsTools(reason);
    }

    private TeamsTools(String reason) {
        this.session = null;
        this.probe = null;
        this.sleeper = null;
        this.enabled = false;
        this.disabledReason = reason == null || reason.isBlank()
                ? "Le volet Teams est désactivé sur cette machine." : reason.strip();
    }

    public boolean enabled() {
        return enabled;
    }

    @Override
    public ToolOutcome execute(String tool, JsonNode input, ToolContext context) {
        return switch (tool == null ? "" : tool) {
            case STATUS -> status();
            case FIND_CONVERSATIONS -> findConversations(input);
            case READ_CONVERSATION -> readConversation(input);
            default -> ToolOutcome.error("unsupported_tool", "Outil Teams inconnu : " + tool);
        };
    }

    // ------------------------------------------------------------------ teams_status

    private ToolOutcome status() {
        if (!enabled) {
            return ToolOutcome.ok(render(new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED,
                    TeamsHealth.full(0), 0, "", disabledReason), ""));
        }
        // D3 : l'annonce de premier usage voyage avec le PREMIER résultat, et une seule fois. Elle
        // est dite sur la console par la session ; ici, elle est écrite là où l'utilisateur regarde.
        String firstUse = firstUse();
        TeamsProbeResult result;
        try {
            result = probe.probe(link(), sleeper);
        } catch (BrowserLinkException e) {
            result = TeamsProbe.notLinked(e);
        } catch (RuntimeException e) {
            result = new TeamsProbeResult(TeamsLinkState.BROWSER_NOT_DETECTED, TeamsHealth.full(0),
                    0, "", "La liaison au navigateur n'a pas abouti sur cette machine.");
        }
        return ToolOutcome.ok(render(result, firstUse));
    }

    String render(TeamsProbeResult result, String firstUse) {
        ObjectNode node = mapper.createObjectNode();
        node.put("state", result.state().name());
        node.put("label", result.state().label());
        node.put("sentence", result.sentence());
        node.put("remedy", result.remedy());
        node.put("browser", result.browser());
        node.put("observedResponses", result.observed());
        node.put("conclusive", result.conclusive());
        node.put("adapter", enabled ? session.adapter().version() : "");
        ObjectNode health = node.putObject("health");
        health.put("verdict", result.health().verdict().name());
        health.put("recognizedFields", result.health().recognizedFields());
        health.put("expectedFields", result.health().expectedFields());
        ArrayNode missing = health.putArray("missingFields");
        result.health().missingFields().forEach(missing::add);
        ArrayNode versions = health.putArray("observedApiVersions");
        result.health().observedApiVersions().forEach(versions::add);
        StringBuilder text = new StringBuilder(result.sentence());
        if (!result.remedy().isEmpty()) {
            text.append(System.lineSeparator()).append(result.remedy());
        }
        if (firstUse != null && !firstUse.isBlank()) {
            text.append(System.lineSeparator()).append(firstUse);
        }
        node.put("text", text.toString());
        return node.toString();
    }

    // ------------------------------------------------------------------ teams_find_conversations

    /**
     * Les conversations que Teams a servies depuis le rattachement, <b>classées par dernière
     * activité</b>, filtrées par rapprochement sur le sujet, les participants et l'identifiant.
     *
     * <p>Le rapprochement porte <b>autant</b> sur le nom d'un participant que sur le sujet : un
     * tête-à-tête n'a pas de sujet, et « la conversation avec Paul » est la façon normale de la
     * nommer.</p>
     */
    private ToolOutcome findConversations(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(FIND_CONVERSATIONS);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.standard(null);
        new TeamsHarvester(link, book, new PageGestures(link, sleeper)).harvestInPlace(ask.window());

        String query = TeamsAsk.text(input, "query", "who", "topic");
        int limit = (int) Math.max(1, Math.min(200, TeamsAsk.number(input, 50, "limit")));
        List<TeamsConversation> matching = new ArrayList<>();
        for (TeamsConversation conversation : book.conversations()) {
            if (matches(conversation, query)) {
                matching.add(conversation);
            }
            if (matching.size() >= limit) {
                break;
            }
        }

        TeamsToolResult result = new TeamsToolResult(FIND_CONVERSATIONS,
                session.adapter().version(), TeamsLinkState.LINKED);
        ArrayNode items = result.array("conversations");
        matching.forEach(conversation -> TeamsViews.conversation(items, conversation));
        List<TeamsGap> gaps = new ArrayList<>(book.gaps());
        if (matching.isEmpty()) {
            gaps.add(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED,
                    query.isEmpty() ? "liste des conversations" : query,
                    "aucune conversation servie par Teams depuis le rattachement ne correspond"));
        }
        result.window(ask.window().covering(null, null, false, false))
                .gaps(gaps)
                .health(book.health())
                .text(sentence(matching.size(), "conversation", gaps, book, query));
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ teams_read_conversation

    /**
     * Le morceau difficile : lire un fil sur une fenêtre de temps. Le plafond (D4) est <b>annoncé</b>
     * dans la description de l'outil, <b>négociable</b> par {@code from} / {@code max_messages}, et
     * le résultat porte <b>toujours</b> la fenêtre réellement lue.
     */
    private ToolOutcome readConversation(JsonNode input) {
        Refusal refusal = refusalIfUnavailable(READ_CONVERSATION);
        if (refusal != null) {
            return refusal.outcome();
        }
        BrowserLink link = link();
        TeamsLedger book = ledger();
        TeamsAsk ask = TeamsAsk.of(input, null);
        String wanted = TeamsAsk.text(input, "conversation_id", "conversationId", "id");

        TeamsHarvester.Harvest harvest =
                new TeamsHarvester(link, book, new PageGestures(link, sleeper))
                        .readConversation(wanted, ask.window());

        TeamsConversation conversation = book.conversation(harvest.conversationId());
        String label = conversation == null ? harvest.conversationId() : conversation.label();

        TeamsToolResult result = new TeamsToolResult(READ_CONVERSATION,
                session.adapter().version(), TeamsLinkState.LINKED);
        TeamsViews.conversation(result.put("conversation"), conversation == null
                ? new TeamsConversation(harvest.conversationId(), TeamsConversationKind.GROUP, "",
                        List.of(), null, "")
                : conversation);
        ArrayNode messages = result.array("messages");
        harvest.messages().forEach(message -> TeamsViews.message(messages, message, book.self()));

        StringBuilder text = new StringBuilder(harvest.reading().summary("messages"));
        ask.notes().forEach(note -> text.append(' ').append(note));
        if (book.self() == null) {
            text.append(" L'utilisateur relié n'a pas encore été identifié : « m'a-t-on "
                    + "mentionné ? » ne peut pas être tranché ici — le flux d'activité y répond.");
        }
        result.window(harvest.window())
                .gaps(harvest.gaps())
                .health(harvest.health())
                .viewport(harvest.viewport())
                .notice(scope.announceOnce(harvest.conversationId(), "les messages de ce fil", label))
                .with("firstUse", firstUse())
                .text(text.toString());
        return ToolOutcome.ok(result.render());
    }

    // ------------------------------------------------------------------ plomberie

    /**
     * Une liaison qu'on n'a pas pu établir : <b>succès</b> d'outil portant l'état et le remède.
     * {@code null} quand tout va bien.
     */
    private Refusal refusalIfUnavailable(String tool) {
        if (!enabled) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED,
                    disabledReason, "Le volet Teams est désactivé sur cette machine."));
        }
        try {
            link();
        } catch (BrowserLinkException e) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED,
                    e.getMessage(), "Le navigateur du poste n'est pas relié : rien n'a été lu."));
        } catch (RuntimeException e) {
            return new Refusal(unavailable(tool, TeamsLinkState.BROWSER_NOT_DETECTED, "",
                    "La liaison au navigateur n'a pas abouti sur cette machine."));
        }
        return null;
    }

    private ToolOutcome unavailable(String tool, TeamsLinkState state, String remedy,
            String sentence) {
        TeamsToolResult result = new TeamsToolResult(tool,
                enabled ? session.adapter().version() : "", state);
        result.with("remedy", remedy)
                .window(null)
                .gaps(List.of(TeamsGap.of(TeamsGapKind.NOTHING_OBSERVED, "liaison Teams",
                        "la liaison au navigateur n'est pas établie")))
                .health(TeamsHealth.full(0))
                .with("firstUse", firstUse())
                .text(sentence + (remedy == null || remedy.isBlank()
                        ? "" : System.lineSeparator() + remedy));
        return ToolOutcome.ok(result.render());
    }

    private BrowserLink link() {
        return session.link();
    }

    /** Le registre vit avec la liaison — et repart avec elle : rien n'est mis en cache (D2). */
    private synchronized TeamsLedger ledger() {
        if (ledger == null) {
            ledger = new TeamsLedger(session.adapter());
        }
        return ledger;
    }

    private synchronized String firstUse() {
        if (firstUseSaid || !enabled) {
            return "";
        }
        firstUseSaid = true;
        return session.notice().text();
    }

    /** La phrase du cadrage : « n éléments lus, … ». */
    private static String sentence(int count, String noun, List<TeamsGap> gaps, TeamsLedger book,
            String query) {
        StringBuilder text = new StringBuilder();
        text.append(count).append(' ').append(noun).append(count > 1 ? "s" : "")
                .append(count > 1 ? " trouvées" : " trouvée");
        if (!query.isBlank()) {
            text.append(" pour « ").append(query).append(" »");
        }
        text.append('.');
        if (!gaps.isEmpty()) {
            List<String> described = new ArrayList<>();
            gaps.forEach(gap -> described.add(gap.describe()));
            text.append(" Ce qui n'a pas pu être lu : ").append(String.join(" ; ", described))
                    .append('.');
        }
        if (book.health().verdict() != TeamsHealthVerdict.FULL) {
            text.append(' ').append(book.health().describe());
        }
        return text.toString();
    }

    /** Rapprochement insensible à la casse et aux accents : « francky » trouve « Francky ». */
    private static boolean matches(TeamsConversation conversation, String query) {
        if (query == null || query.isBlank()) {
            return true;
        }
        String needle = fold(query);
        if (fold(conversation.topic()).contains(needle) || fold(conversation.id()).contains(needle)
                || fold(conversation.label()).contains(needle)) {
            return true;
        }
        return conversation.participants().stream()
                .anyMatch(participant -> fold(participant.label()).contains(needle)
                        || fold(participant.email() == null ? "" : participant.email())
                                .contains(needle));
    }

    static String fold(String raw) {
        if (raw == null) {
            return "";
        }
        return Normalizer.normalize(raw, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .toLowerCase(Locale.FRENCH)
                .strip();
    }

    /** Un refus rendu comme un succès porteur d'un état : c'est la règle de forme n° 1. */
    private record Refusal(ToolOutcome outcome) {
    }
}
