package fr.claudegateway.jarvis.portrait;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentCallShape;
import fr.claudegateway.agent.AgentContextPolicy;
import fr.claudegateway.agent.AgentMessage;
import fr.claudegateway.agent.AgentReasoning;
import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.agent.AgentToolCall;
import fr.claudegateway.agent.AgentTurn;
import fr.claudegateway.agent.AgentTurnMode;
import fr.claudegateway.agent.AgentTurnRequest;
import fr.claudegateway.agent.AiAgentProvider;
import fr.claudegateway.jarvis.fastbrain.JarvisFastBrainService;
import fr.claudegateway.jarvis.portrait.JarvisPortraitExceptions.InvalidPortraitException;

/**
 * <b>L'entretien du portrait</b> (F-241 / SF-241-02, D13-D16).
 *
 * <p>Le parcours (questions dites, réponses captées, « je passe ») vit <b>sur l'appareil</b>. La passerelle ne fait
 * que deux choses : la <b>cadence</b> de la proposition (une fois, puis tous les 7 jours, 3 fois au plus) et le
 * <b>rangement</b> des réponses en sections — <b>un seul</b> appel au cerveau rapide via {@link AiAgentProvider}, avec
 * une consigne figée. Le brouillon est rendu à l'écran et <b>n'est jamais écrit</b> : rien n'est enregistré avant la
 * validation du PO (qui passe par {@code PUT /api/jarvis/portrait}). Aucune réponse n'est journalisée.</p>
 */
@Service
public class JarvisInterviewService {

    private static final Logger log = LoggerFactory.getLogger(JarvisInterviewService.class);

    static final Duration OFFER_EVERY = Duration.ofDays(7);
    static final int MAX_OFFERS = 3;
    static final int MAX_ANSWERS = 20;
    static final int MAX_ANSWER_CHARS = 2_000;
    static final String TOOL = "ranger_portrait";

    /** Une question posée et sa réponse (vide = « je passe »). */
    public record Answer(String question, String answer) {
    }

    /** Un souvenir proposé par le rangement (origine {@code ENTRETIEN}). */
    public record DraftSouvenir(String text, boolean sensitive, String source) {
    }

    /** Le brouillon rendu à l'écran : jamais stocké. */
    public record Draft(Map<String, String> sections, List<DraftSouvenir> souvenirs) {
    }

    /** L'état de la proposition d'entretien pour l'écran. */
    public record Offer(boolean offer, int offers) {
    }

    private final AiAgentProvider provider;
    private final JarvisFastBrainService fastBrain;
    private final JarvisPortraitService portraits;
    private final JarvisPortraitRepository repository;
    private final Clock clock;

    public JarvisInterviewService(AiAgentProvider provider, JarvisFastBrainService fastBrain,
            JarvisPortraitService portraits, JarvisPortraitRepository repository, Clock clock) {
        this.provider = provider;
        this.fastBrain = fastBrain;
        this.portraits = portraits;
        this.repository = repository;
        this.clock = clock;
    }

    // ---- Cadence (D13) ----

    /**
     * Vrai si l'écran peut proposer l'entretien : aucun entretien validé, aucun contenu au portrait, moins de 3
     * propositions, la dernière il y
     * a 7 jours au moins. (Présentation et tâche en cours sont vérifiées par l'écran, qui seul les connaît.)
     */
    @Transactional(readOnly = true)
    public Offer offer(UUID userId) {
        Optional<JarvisPortrait> row = repository.findById(userId);
        if (row.isEmpty()) {
            return new Offer(true, 0);
        }
        JarvisPortrait p = row.get();
        // F-241 / SF-241-06 (D4) — un entretien validé clôt la proposition, même si le portrait est vidé ensuite ;
        // la mise à jour reste à la voix (D16).
        boolean empty = p.getLastInterviewAt() == null
                && portraits.snapshot(userId).map(s -> !s.hasContent()).orElse(true);
        return new Offer(eligible(empty, p.getInterviewOffers(), p.getInterviewOfferedAt(),
                OffsetDateTime.now(clock)), p.getInterviewOffers());
    }

    /** La règle pure de la cadence D13. */
    static boolean eligible(boolean empty, int offers, OffsetDateTime lastOffered, OffsetDateTime now) {
        if (!empty || offers >= MAX_OFFERS) {
            return false;
        }
        return lastOffered == null || !lastOffered.plus(OFFER_EVERY).isAfter(now);
    }

    /** L'écran a proposé l'entretien (« Plus tard » ou refus) : la proposition suivante attend 7 jours. */
    @Transactional
    public Offer offered(UUID userId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisPortrait row = repository.findById(userId)
                .orElseGet(() -> JarvisPortrait.builder().userId(userId).version(1L).updatedAt(now).build());
        row.setInterviewOffers(row.getInterviewOffers() + 1);
        row.setInterviewOfferedAt(now);
        repository.save(row);
        portraits.invalidate(userId);
        return offer(userId);
    }

    // ---- Rangement (D13) ----

    /**
     * Range les réponses dans les sections par <b>un</b> appel au cerveau rapide. N'écrit <b>rien</b> en base. Une
     * panne rend un brouillon brut (chaque réponse dans la section de sa question) : l'entretien n'est jamais perdu.
     */
    public Draft draft(UUID userId, List<Answer> answers) {
        List<Answer> given = validate(answers);
        if (given.isEmpty()) {
            return new Draft(Map.of(), List.of());
        }
        String current = portraits.snapshot(userId).map(JarvisInterviewService::currentText).orElse("");
        StringBuilder message = new StringBuilder();
        if (!current.isBlank()) {
            message.append("[Portrait actuel]\n").append(current).append("\n\n");
        }
        message.append("[Entretien]\n");
        for (Answer a : given) {
            message.append("Q : ").append(a.question()).append("\nR : ").append(a.answer()).append("\n\n");
        }
        try {
            AgentTurn turn = provider.nextTurn(new AgentTurnRequest(fastBrain.model(), SYSTEM,
                    List.of(AgentMessage.userText(message.toString().strip())), List.of(tool()),
                    fastBrain.apiKeyFor(userId), AgentReasoning.none(), AgentContextPolicy.none(), AgentTurnMode.ACT,
                    new AgentCallShape(2_000, false, true, false)));
            fastBrain.accountAnnex(userId, turn);
            Optional<AgentToolCall> call = turn == null ? Optional.empty() : turn.toolCalls().stream()
                    .filter(tc -> TOOL.equals(tc.name())).findFirst();
            if (call.isPresent()) {
                return parse(call.get().input());
            }
        } catch (RuntimeException e) {
            log.warn("Entretien du portrait : rangement indisponible ({})", e.getClass().getSimpleName());
        }
        return raw(given);
    }

    static List<Answer> validate(List<Answer> answers) {
        if (answers == null || answers.size() > MAX_ANSWERS) {
            throw new InvalidPortraitException("Entretien illisible (" + MAX_ANSWERS + " réponses au plus).");
        }
        List<Answer> given = new ArrayList<>();
        for (Answer a : answers) {
            if (a == null || a.question() == null) {
                throw new InvalidPortraitException("Entretien illisible.");
            }
            String answer = a.answer() == null ? "" : a.answer().strip();
            if (answer.length() > MAX_ANSWER_CHARS || a.question().length() > 400) {
                throw new InvalidPortraitException("Une réponse tient en " + MAX_ANSWER_CHARS + " caractères.");
            }
            if (!answer.isEmpty()) {
                given.add(new Answer(a.question().strip(), answer));
            }
        }
        return given;
    }

    /** Le brouillon du modèle, borné : sections connues seulement, souvenirs ≤ 200 caractères, 10 au plus. */
    static Draft parse(JsonNode input) {
        Map<String, String> sections = new LinkedHashMap<>();
        JsonNode s = input == null ? null : input.get("sections");
        if (s != null && s.isObject()) {
            for (PortraitSection section : PortraitSection.values()) {
                JsonNode v = s.get(section.name());
                if (v != null && v.isTextual() && !v.asText().isBlank()) {
                    sections.put(section.name(), v.asText().strip());
                }
            }
        }
        List<DraftSouvenir> souvenirs = new ArrayList<>();
        JsonNode list = input == null ? null : input.get("souvenirs");
        if (list != null && list.isArray()) {
            for (JsonNode m : list) {
                String text = JarvisPortraitRules.normalizeSouvenir(m.path("texte").asText(""));
                if (!text.isEmpty() && text.length() <= JarvisPortraitRules.MAX_SOUVENIR_CHARS && souvenirs.size() < 10) {
                    souvenirs.add(new DraftSouvenir(text, m.path("sensible").asBoolean(false), "ENTRETIEN"));
                }
            }
        }
        return new Draft(sections, souvenirs);
    }

    /** Repli sans modèle : chaque réponse va dans la section de sa question (liste D14). */
    static Draft raw(List<Answer> given) {
        Map<String, String> sections = new LinkedHashMap<>();
        for (Answer a : given) {
            String key = sectionOf(a.question()).name();
            sections.merge(key, a.answer(), (x, y) -> x + "\n" + y);
        }
        return new Draft(sections, List.of());
    }

    static PortraitSection sectionOf(String question) {
        String q = question.toLowerCase(java.util.Locale.ROOT);
        if (q.contains("jamais aborder")) {
            return PortraitSection.INTERDITS;
        }
        if (q.contains("dates")) {
            return PortraitSection.DATES;
        }
        if (q.contains("humour")) {
            return PortraitSection.HUMOUR;
        }
        if (q.contains("goûts") || q.contains("gouts")) {
            return PortraitSection.GOUTS;
        }
        if (q.contains("partage votre vie") || q.contains("vie personnelle")) {
            return PortraitSection.ENTOURAGE;
        }
        if (q.contains("occupe") || q.contains("événement") || q.contains("evenement") || q.contains("neuf")) {
            return PortraitSection.MOMENT;
        }
        return PortraitSection.QUI;
    }

    private static String currentText(JarvisPortraitService.Snapshot s) {
        StringBuilder b = new StringBuilder();
        for (PortraitSection section : PortraitSection.values()) {
            String t = s.text(section);
            if (!t.isBlank()) {
                b.append(section.name()).append(" : ").append(t).append('\n');
            }
        }
        return b.toString().strip();
    }

    static final String SYSTEM = """
            Tu ranges les réponses d'un entretien dans le portrait d'une personne, pour son assistant vocal. Tu \
            n'inventes rien : tu reprends ce qui est dit, en phrases courtes, à la deuxième personne évitée (style \
            notes : « Chef de projet chez … ; deux enfants : … »). Sections : QUI (métier, rôle, parcours, comment \
            l'appeler), ENTOURAGE (famille, proches), MOMENT (ce qui occupe en ce moment, événements récents ou à \
            venir), GOUTS (loisirs, sport, musique, lectures), HUMOUR (l'humour qui plaît), INTERDITS (sujets à ne \
            jamais aborder, mot pour mot), DATES (une ligne par date : JJ/MM[/AAAA] — libellé). Si un portrait \
            actuel est fourni, rends chaque section modifiée EN ENTIER (ancien texte mis à jour, rien de perdu sans \
            raison) ; omets une section inchangée. Une réponse vide ou « je passe » ne crée rien. Un fait ponctuel \
            et durable qui ne tient dans aucune section peut devenir un souvenir (une phrase, 200 caractères au \
            plus ; sensible = santé, famille, argent, conflit). Appelle l'outil ranger_portrait une seule fois.""";

    static AgentTool tool() {
        Map<String, Object> sectionProps = new LinkedHashMap<>();
        for (PortraitSection section : PortraitSection.values()) {
            sectionProps.put(section.name(), Map.of("type", "string"));
        }
        Map<String, Object> souvenir = new LinkedHashMap<>();
        souvenir.put("type", "object");
        souvenir.put("properties", Map.of("texte", Map.of("type", "string"), "sensible", Map.of("type", "boolean")));
        souvenir.put("required", List.of("texte", "sensible"));
        souvenir.put("additionalProperties", false);
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("sections", Map.of("type", "object", "properties", sectionProps, "additionalProperties", false));
        props.put("souvenirs", Map.of("type", "array", "items", souvenir));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("sections", "souvenirs"));
        schema.put("additionalProperties", false);
        return new AgentTool(TOOL, "Rend le brouillon du portrait rangé en sections.", schema);
    }
}
