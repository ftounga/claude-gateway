package fr.claudegateway.jarvis.calendar;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.agent.AgentTool;
import fr.claudegateway.atelier.Workspace;

/**
 * <b>L'outil d'agenda d'Opus</b> (F-243 / SF-243-03, D5) — {@value #POSER}, offert <b>seulement dans le fil Jarvis</b>
 * (F-245) et seulement quand le client OAuth est configuré ; jamais dans le terminal d'un sujet client. Définition
 * <b>figée</b> (préfixe stable).
 *
 * <p>L'outil ne pose rien : il valide les champs, calcule la reformulation et crée l'action en attente ; la pose a lieu
 * au « oui » vérifié par la passerelle ({@link JarvisCalendarActionService}). Aucun invité n'est accepté (le schéma n'en
 * a pas).</p>
 */
@Component
public class JarvisCalendarToolCatalog {

    public static final String POSER = "agenda_poser";

    static final int MAX_TITLE = 200;
    static final int MAX_LOCATION = 200;
    static final int MAX_DESCRIPTION = 1000;
    static final Duration MAX_SPAN = Duration.ofHours(24);
    static final int DEFAULT_MINUTES = 60;
    private static final Pattern MAIL_REF = Pattern.compile("^courriel:([0-9a-fA-F-]{36})$");

    /** Le résultat d'un appel d'outil. */
    public record Result(String content, boolean error) {
    }

    private final JarvisCalendarGrantService grants;
    private final JarvisCalendarActionService actions;
    private final Clock clock;

    public JarvisCalendarToolCatalog(JarvisCalendarGrantService grants, JarvisCalendarActionService actions,
            Clock clock) {
        this.grants = grants;
        this.actions = actions;
        this.clock = clock;
    }

    public static boolean isCalendarTool(String name) {
        return POSER.equals(name);
    }

    /** Les outils du tour : dans le fil Jarvis, client configuré ; sinon aucun. */
    public List<AgentTool> toolsFor(UUID userId, Workspace workspace) {
        if (userId == null || workspace == null || !workspace.isJarvisThread() || !grants.configured()) {
            return List.of();
        }
        return definitions();
    }

    /** La définition figée. */
    public static List<AgentTool> definitions() {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("titre", Map.of("type", "string", "description", "Titre du rendez-vous (200 caractères au plus)."));
        p.put("debut", Map.of("type", "string",
                "description", "Début, heure de Paris : AAAA-MM-JJTHH:MM (jamais une heure devinée)."));
        p.put("fin", Map.of("type", "string", "description", "Fin, heure de Paris : AAAA-MM-JJTHH:MM ; vide sinon."));
        p.put("duree_minutes", Map.of("type", List.of("integer", "null"),
                "description", "Durée en minutes si la fin n'est pas connue (60 par défaut)."));
        p.put("lieu", Map.of("type", "string", "description", "Lieu ; vide sinon."));
        p.put("description", Map.of("type", "string", "description", "Note courte ; vide sinon."));
        p.put("courriel", Map.of("type", "string",
                "description", "Le courriel d'origine (courriel:…) ; vide sinon."));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", p);
        schema.put("required", List.copyOf(p.keySet()));
        schema.put("additionalProperties", false);
        return List.of(new AgentTool(POSER, "Prépare la pose d'un rendez-vous dans l'agenda pro de l'utilisateur (Google "
                + "Workspace). Rien n'est posé tout de suite : la passerelle rend la reformulation à dire mot pour mot, "
                + "terminée par « Vous confirmez ? », et pose le rendez-vous seulement si le message suivant de "
                + "l'utilisateur est un « oui ». N'invite personne et n'envoie aucun mail. Une heure absente ou "
                + "ambiguë se demande à l'utilisateur, jamais devinée.", schema));
    }

    /** Exécute {@value #POSER} dans le fil Jarvis, sous le {@code user_id} du tour. */
    public Result execute(UUID userId, Workspace workspace, String name, JsonNode input) {
        if (!POSER.equals(name) || workspace == null || !workspace.isJarvisThread()) {
            return new Result("Outil réservé au fil Jarvis.", true);
        }
        if (!grants.configured()) {
            return new Result("L'écriture dans l'agenda pro n'est pas configurée.", true);
        }
        JarvisCalendarGrant grant = grants.active(userId).orElse(null);
        if (grant == null) {
            return new Result("Agenda pro non relié : rien n'est posé. Propose à l'utilisateur de le relier dans "
                    + "« Mes agendas » (Jarvis, réglages).", true);
        }
        String title = text(input, "titre", MAX_TITLE);
        if (title.isEmpty()) {
            return new Result("Titre manquant : demande-le à l'utilisateur.", true);
        }
        LocalDateTime start;
        LocalDateTime end;
        try {
            start = LocalDateTime.parse(text(input, "debut", 40)).truncatedTo(ChronoUnit.MINUTES);
            String rawEnd = text(input, "fin", 40);
            if (!rawEnd.isEmpty()) {
                end = LocalDateTime.parse(rawEnd).truncatedTo(ChronoUnit.MINUTES);
            } else {
                JsonNode d = input == null ? null : input.get("duree_minutes");
                int minutes = d != null && d.canConvertToInt() && d.asInt() > 0 ? d.asInt() : DEFAULT_MINUTES;
                end = start.plusMinutes(minutes);
            }
        } catch (DateTimeParseException e) {
            return new Result("Heure illisible (attendu AAAA-MM-JJTHH:MM, heure de Paris) : demande le créneau à "
                    + "l'utilisateur.", true);
        }
        LocalDateTime now = LocalDateTime.now(clock.withZone(JarvisCalendarActionService.ZONE));
        if (!end.isAfter(start) || Duration.between(start, end).compareTo(MAX_SPAN) > 0) {
            return new Result("Créneau invalide (la fin suit le début, 24 h au plus).", true);
        }
        if (start.isBefore(now.minusMinutes(5)) || start.isAfter(now.plusDays(366))) {
            return new Result("Ce créneau est passé ou trop lointain : vérifie la date avec l'utilisateur.", true);
        }
        String mailRef = text(input, "courriel", 60);
        UUID mailItemId = null;
        java.util.regex.Matcher m = MAIL_REF.matcher(mailRef);
        if (m.matches()) {
            mailItemId = UUID.fromString(m.group(1));
        }
        JarvisCalendarActionService.Payload payload = new JarvisCalendarActionService.Payload(title, start.toString(),
                end.toString(), text(input, "lieu", MAX_LOCATION), text(input, "description", MAX_DESCRIPTION),
                mailItemId == null ? null : mailItemId.toString());
        JarvisCalendarAction action = actions.propose(userId, workspace.getId(), grant,
                JarvisCalendarAction.Kind.CREATE, payload, mailItemId);
        return new Result("En attente du « oui » de l'utilisateur : rien n'est posé. Dis mot pour mot, puis rien "
                + "d'autre : « " + action.getSummary() + " Vous confirmez ? »", false);
    }

    private static String text(JsonNode input, String field, int max) {
        String s = input == null ? "" : input.path(field).asText("");
        return EventText.clip(s, max);
    }
}
