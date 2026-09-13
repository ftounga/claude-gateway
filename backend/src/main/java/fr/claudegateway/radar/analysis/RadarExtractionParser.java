package fr.claudegateway.radar.analysis;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

import fr.claudegateway.radar.RadarEvidence;
import fr.claudegateway.radar.RadarRole;
import fr.claudegateway.radar.RadarSubject;
import fr.claudegateway.radar.RadarSubjectAlias;
import fr.claudegateway.radar.RadarSubjectFact;
import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.RadarText;
import fr.claudegateway.radar.analysis.RadarExtraction.RoleItem;
import fr.claudegateway.radar.analysis.RadarExtraction.SubjectItem;
import fr.claudegateway.radar.analysis.RadarExtraction.SummaryItem;
import fr.claudegateway.radar.analysis.RadarExtraction.Valued;
import fr.claudegateway.radar.analysis.RadarExtractionContext.FactSnapshot;
import fr.claudegateway.radar.analysis.RadarExtractionContext.MessageEntry;
import fr.claudegateway.radar.analysis.RadarExtractionContext.PersonEntry;
import fr.claudegateway.radar.analysis.RadarExtractionContext.SubjectEntry;

/**
 * La lecture <b>stricte</b> d'une sortie d'extraction (F-101 / SF-101-03).
 *
 * <p><b>Tout est vérifié avant la moindre écriture</b>, et une seule violation rend toute la sortie
 * illisible : un modèle qu'on n'a compris qu'à moitié n'a rien dit (cadrage §7 : « une sortie illisible
 * n'écrit rien »). Les champs inconnus sont ignorés — ce n'est pas de la forme qu'on corrige, c'est le
 * fond qu'on refuse de deviner.</p>
 */
public final class RadarExtractionParser {

    /** La ligne qui ouvre le bloc. Immuable : elle est écrite dans la consigne. */
    public static final String MARQUEUR = "===RADAR===";

    public static final int MAX_SUBJECTS = 30;
    public static final int MAX_EVIDENCE = 20;
    public static final int MAX_ALIASES = 5;
    public static final int MAX_ROLES = 20;

    /** Une violation de forme : la sortie est illisible. */
    static final class Unreadable extends RuntimeException {
        Unreadable(String reason) {
            super(reason, null, false, false);
        }
    }

    private RadarExtractionParser() {
    }

    /** La sortie vérifiée, ou vide si elle est illisible. */
    public static Optional<RadarExtraction> parse(String response, RadarExtractionContext context) {
        JsonNode block = RadarOutputBlock.read(response, MARQUEUR).orElse(null);
        if (block == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(read(block, context));
        } catch (Unreadable ex) {
            return Optional.empty();
        }
    }

    static RadarExtraction read(JsonNode block, RadarExtractionContext context) {
        JsonNode items = block.get("sujets");
        if (items == null || !items.isArray() || items.size() > MAX_SUBJECTS) {
            throw new Unreadable("sujets");
        }
        Map<String, String> quotes = new HashMap<>();
        List<SubjectItem> subjects = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (JsonNode item : items) {
            if (!item.isObject()) {
                throw new Unreadable("sujet");
            }
            SubjectItem subject = subject(item, context, quotes);
            String identity = subject.attached() ? subject.existing().label() : "nouveau:" + RadarText.key(subject.newName());
            if (!seen.add(identity)) {
                throw new Unreadable("sujet en double");
            }
            subjects.add(subject);
        }
        return new RadarExtraction(subjects, quotes);
    }

    private static SubjectItem subject(JsonNode item, RadarExtractionContext context, Map<String, String> quotes) {
        rejectScores(item);
        String ref = text(item, "sujet", 16, true);
        SubjectEntry existing;
        String newName = null;
        if ("nouveau".equals(ref)) {
            newName = text(item, "nom", RadarSubject.MAX_NAME_LENGTH, true);
            // Un « nouveau » qui porte exactement le nom ou un alias d'un sujet suivi est ce sujet.
            existing = context.byExactName(newName);
            if (existing != null) {
                newName = null;
            }
        } else {
            existing = context.subject(ref);
            if (existing == null) {
                throw new Unreadable("sujet inconnu");
            }
        }
        List<MessageEntry> evidence = evidence(item.get("preuves"), context);

        List<String> aliases = new ArrayList<>();
        JsonNode aliasNode = item.get("alias");
        if (aliasNode != null && !aliasNode.isNull()) {
            if (!aliasNode.isArray() || aliasNode.size() > MAX_ALIASES) {
                throw new Unreadable("alias");
            }
            for (JsonNode alias : aliasNode) {
                aliases.add(value(alias, RadarSubjectAlias.MAX_ALIAS_LENGTH));
            }
        }

        Valued<RadarSubjectState> state = null;
        JsonNode stateNode = item.get("etat");
        if (stateNode != null && !stateNode.isNull()) {
            requireObject(stateNode);
            state = new Valued<>(state(text(stateNode, "valeur", 16, true)), evidence(stateNode.get("preuves"), context));
        }

        Valued<String> nextStep = null;
        JsonNode stepNode = item.get("prochaine_etape");
        if (stepNode != null && !stepNode.isNull()) {
            requireObject(stepNode);
            nextStep = new Valued<>(text(stepNode, "texte", RadarSubject.MAX_NEXT_STEP_LENGTH, false),
                    evidence(stepNode.get("preuves"), context));
        }

        Valued<LocalDate> due = null;
        JsonNode dueNode = item.get("echeance");
        if (dueNode != null && !dueNode.isNull()) {
            requireObject(dueNode);
            due = new Valued<>(date(dueNode.get("date")), evidence(dueNode.get("preuves"), context));
        }

        List<SummaryItem> summary = null;
        JsonNode summaryNode = item.get("resume");
        if (summaryNode != null && !summaryNode.isNull()) {
            if (existing != null && !existing.summaryShown()) {
                throw new Unreadable("résumé non montré");
            }
            summary = summary(summaryNode, existing, context);
        }

        List<RoleItem> roles = new ArrayList<>();
        JsonNode rolesNode = item.get("roles");
        if (rolesNode != null && !rolesNode.isNull()) {
            if (!rolesNode.isArray() || rolesNode.size() > MAX_ROLES) {
                throw new Unreadable("roles");
            }
            for (JsonNode role : rolesNode) {
                requireObject(role);
                PersonEntry person = context.person(text(role, "personne", 8, true));
                if (person == null) {
                    throw new Unreadable("personne inconnue");
                }
                roles.add(new RoleItem(person, role(text(role, "role", 16, true)), evidence(role.get("preuves"), context)));
            }
        }

        JsonNode quoteNode = item.get("citations");
        if (quoteNode != null && !quoteNode.isNull()) {
            requireObject(quoteNode);
            var fields = quoteNode.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (context.message(field.getKey()) == null) {
                    throw new Unreadable("citation d'un message inconnu");
                }
                String quote = value(field.getValue(), RadarEvidence.MAX_QUOTE_LENGTH);
                quotes.putIfAbsent(field.getKey(), quote);
            }
        }
        return new SubjectItem(existing, newName, evidence, List.copyOf(aliases), state, nextStep, due, summary,
                List.copyOf(roles));
    }

    private static List<SummaryItem> summary(JsonNode node, SubjectEntry existing, RadarExtractionContext context) {
        if (!node.isArray() || node.size() > fr.claudegateway.radar.RadarRegistry.MAX_SUMMARY_SENTENCES) {
            throw new Unreadable("resume");
        }
        List<SummaryItem> items = new ArrayList<>();
        Set<String> reprised = new HashSet<>();
        for (JsonNode phrase : node) {
            requireObject(phrase);
            JsonNode reprise = phrase.get("reprise");
            if (reprise != null && !reprise.isNull()) {
                String label = value(reprise, 16);
                FactSnapshot fact = existing == null ? null : existing.phrases().get(label);
                if (fact == null || !reprised.add(label)) {
                    throw new Unreadable("reprise inconnue");
                }
                items.add(new SummaryItem(fact, null, List.of()));
            } else {
                items.add(new SummaryItem(null, text(phrase, "phrase", RadarSubjectFact.MAX_TEXT_LENGTH, true),
                        evidence(phrase.get("preuves"), context)));
            }
        }
        return List.copyOf(items);
    }

    // ------------------------------------------------------------------------------------ valeurs

    /** Des preuves : au moins une, toutes des messages montrés. */
    static List<MessageEntry> evidence(JsonNode node, RadarExtractionContext context) {
        if (node == null || !node.isArray() || node.isEmpty() || node.size() > MAX_EVIDENCE) {
            throw new Unreadable("preuves");
        }
        Set<MessageEntry> entries = new LinkedHashSet<>();
        for (JsonNode label : node) {
            MessageEntry entry = context.message(value(label, 8));
            if (entry == null) {
                throw new Unreadable("message inconnu");
            }
            entries.add(entry);
        }
        return List.copyOf(entries);
    }

    static RadarSubjectState state(String value) {
        return switch (value) {
            case "avance" -> RadarSubjectState.ADVANCING;
            case "en_attente" -> RadarSubjectState.WAITING;
            case "bloque" -> RadarSubjectState.BLOCKED;
            default -> throw new Unreadable("etat");
        };
    }

    static RadarRole role(String value) {
        return switch (value) {
            case "decide" -> RadarRole.DECIDES;
            case "pilote" -> RadarRole.DRIVES;
            case "expert" -> RadarRole.EXPERT;
            case "informe" -> RadarRole.INFORMED;
            default -> throw new Unreadable("role");
        };
    }

    /** Une date ISO, ou {@code null} explicite (effacer). */
    static LocalDate date(JsonNode node) {
        if (node == null) {
            throw new Unreadable("date absente");
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isTextual()) {
            throw new Unreadable("date");
        }
        try {
            return LocalDate.parse(node.asText().strip());
        } catch (DateTimeParseException ex) {
            throw new Unreadable("date");
        }
    }

    /**
     * Un champ texte d'un objet.
     *
     * @param required {@code false} : absent, {@code null} ou vide rendent {@code null}
     */
    static String text(JsonNode object, String field, int max, boolean required) {
        JsonNode node = object.get(field);
        if (node == null || node.isNull()) {
            if (required) {
                throw new Unreadable(field);
            }
            return null;
        }
        if (!node.isTextual()) {
            throw new Unreadable(field);
        }
        String value = node.asText().strip();
        if (value.isEmpty()) {
            if (required) {
                throw new Unreadable(field);
            }
            return null;
        }
        if (value.length() > max) {
            throw new Unreadable(field);
        }
        return value;
    }

    /** Une chaîne non vide et bornée (élément de tableau). */
    static String value(JsonNode node, int max) {
        if (node == null || !node.isTextual()) {
            throw new Unreadable("valeur");
        }
        String value = node.asText().strip();
        if (value.isEmpty() || value.length() > max) {
            throw new Unreadable("valeur");
        }
        return value;
    }

    /** La certitude se dit en toutes lettres (cadrage §4.3) : un score chiffré n'est pas une forme admise. */
    static void rejectScores(JsonNode object) {
        for (String field : List.of("score", "confiance", "certitude", "probabilite")) {
            JsonNode node = object.get(field);
            if (node != null && node.isNumber()) {
                throw new Unreadable("score");
            }
        }
    }

    static void requireObject(JsonNode node) {
        if (!node.isObject()) {
            throw new Unreadable("objet attendu");
        }
    }

    /** Pour les tests : l'ordre des sujets est celui de la sortie. */
    static Map<String, SubjectItem> byLabel(RadarExtraction extraction) {
        Map<String, SubjectItem> out = new LinkedHashMap<>();
        for (SubjectItem item : extraction.subjects()) {
            out.put(item.attached() ? item.existing().label() : item.newName(), item);
        }
        return out;
    }
}
