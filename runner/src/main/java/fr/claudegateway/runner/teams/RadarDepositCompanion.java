package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Le compagnon d'un enregistrement déposé</b> (F-100 / SF-100-05, complété par F-147 / SF-147-06) :
 * le petit {@code <nom>.json} posé à côté du fichier, qui porte son titre et sa date.
 *
 * <p>Depuis SF-147-06 il porte deux choses de plus, et ce sont elles qui rendent une reprise possible :
 * <b>{@code meeting_id}</b>, la réunion qui attend ce texte, et <b>{@code transcript_sent}</b>, la
 * marque disant qu'il est déjà remonté. Sans le premier, un runner qui redémarre ne sait plus à quoi
 * rattacher le fichier ; sans le second, il le remonterait une seconde fois.</p>
 *
 * <p><b>Tout est sur le disque, et c'est voulu</b> : ce qui doit survivre à un redémarrage ne peut pas
 * vivre en mémoire. Une écriture qui échoue est <b>silencieuse</b> : le dépôt lui-même reste bon, et
 * c'est ce qui compte.</p>
 */
final class RadarDepositCompanion {

    /** Les extensions d'enregistrement reconnues, pour retrouver le fichier d'un compagnon. */
    static final java.util.List<String> EXTENSIONS = RadarDepositCollector.EXTENSIONS;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private RadarDepositCompanion() {
    }

    /** Le chemin du compagnon d'un enregistrement, ou {@code null} si le fichier est inconnu. */
    static Path of(Path recording) {
        if (recording == null) {
            return null;
        }
        String name = recording.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        return recording.resolveSibling(base + ".json");
    }

    /** Inscrit la réunion qui attend ce texte. */
    static void rememberMeeting(Path recording, String meetingId) {
        write(recording, node -> node.put("meeting_id", meetingId));
    }

    /** Marque le compagnon : le texte est remonté, la reprise n'a plus rien à faire ici. */
    static void markSent(Path recording) {
        write(recording, node -> node.put("transcript_sent", true));
    }

    /** Ce que dit le compagnon, ou un objet vide s'il est absent ou illisible. */
    static JsonNode read(Path companion) {
        if (companion == null || !Files.isRegularFile(companion)) {
            return MAPPER.createObjectNode();
        }
        try {
            JsonNode node = MAPPER.readTree(Files.readString(companion));
            return node != null && node.isObject() ? node : MAPPER.createObjectNode();
        } catch (IOException | RuntimeException e) {
            return MAPPER.createObjectNode();
        }
    }

    /** L'enregistrement d'un compagnon : même nom, une extension connue — ou {@code null}. */
    static Path recordingOf(Path companion) {
        if (companion == null) {
            return null;
        }
        String name = companion.getFileName().toString();
        if (!name.toLowerCase(Locale.ROOT).endsWith(".json")) {
            return null;
        }
        String base = name.substring(0, name.length() - ".json".length());
        for (String extension : EXTENSIONS) {
            Path candidate = companion.resolveSibling(base + extension);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static void write(Path recording, java.util.function.Consumer<ObjectNode> change) {
        Path companion = of(recording);
        if (companion == null) {
            return;
        }
        try {
            JsonNode existing = read(companion);
            ObjectNode node = existing.isObject() ? ((ObjectNode) existing).deepCopy()
                    : MAPPER.createObjectNode();
            change.accept(node);
            Files.writeString(companion, node.toString());
        } catch (IOException | RuntimeException e) {
            // Le dépôt reste bon : on ne casse rien pour un compagnon qui n'a pas pu être écrit.
        }
    }
}
