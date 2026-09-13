package fr.claudegateway.radar.analysis;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import fr.claudegateway.radar.RadarSubjectState;
import fr.claudegateway.radar.RadarText;

/**
 * <b>Ce que l'extraction montre au modèle</b> (F-101 / SF-101-03), et la table qui relit ses libellés.
 *
 * <p>Le modèle ne désigne jamais un identifiant de base : il cite des <b>libellés</b> — {@code S3} pour
 * un sujet, {@code S3.2} pour une phrase de résumé, {@code P1} pour une personne, {@code M4} pour un
 * message. Un libellé qui n'est pas dans cette table n'existe pas, et la sortie qui le cite est
 * illisible. C'est ce qui empêche une preuve inventée, et un sujet d'un autre poste d'entrer dans une
 * invite : la table est construite depuis un seul {@code RadarScope}.</p>
 */
public final class RadarExtractionContext {

    public static final int MAX_OPEN_SUBJECTS = 60;
    public static final int MAX_SUMMARIES_SHOWN = 40;
    public static final int MAX_CLOSED_SUBJECTS = 30;
    public static final int MESSAGE_CHARS = 4_000;

    /** Ce que le registre sait d'un sujet, lu avant l'appel. */
    public record SubjectSnapshot(UUID id, String name, RadarSubjectState state, OffsetDateTime lastActivityAt,
            List<String> aliases, List<String> rejectedAliases, List<FactSnapshot> summary) {
    }

    /** Une phrase de résumé existante. */
    public record FactSnapshot(UUID id, String text) {
    }

    /** Un sujet montré. */
    public record SubjectEntry(String label, SubjectSnapshot subject, boolean closed, boolean summaryShown,
            Map<String, FactSnapshot> phrases) {
    }

    /** Une personne du lot. */
    public record PersonEntry(String label, String authorKey, String name, String title) {
    }

    /** Un message retenu. */
    public record MessageEntry(String label, RadarExchangeBatch.Exchange exchange, RadarExchangeBatch.Message message,
            PersonEntry author) {
    }

    private final Map<String, SubjectEntry> subjects;
    private final Map<String, PersonEntry> people;
    private final Map<String, MessageEntry> messages;

    private RadarExtractionContext(Map<String, SubjectEntry> subjects, Map<String, PersonEntry> people,
            Map<String, MessageEntry> messages) {
        this.subjects = subjects;
        this.people = people;
        this.messages = messages;
    }

    /**
     * Construit la table.
     *
     * @param snapshots les sujets non fusionnés du poste
     * @param retained  les échanges retenus par le tri
     */
    public static RadarExtractionContext build(List<SubjectSnapshot> snapshots, List<RadarExchangeBatch.Exchange> retained) {
        Comparator<SubjectSnapshot> recent = Comparator.comparing(SubjectSnapshot::lastActivityAt,
                Comparator.nullsLast(Comparator.reverseOrder()));
        List<SubjectSnapshot> open = snapshots.stream().filter(s -> s.state() != RadarSubjectState.CLOSED)
                .sorted(recent).limit(MAX_OPEN_SUBJECTS).toList();
        List<SubjectSnapshot> closed = snapshots.stream().filter(s -> s.state() == RadarSubjectState.CLOSED)
                .sorted(recent).limit(MAX_CLOSED_SUBJECTS).toList();

        Map<String, SubjectEntry> subjects = new LinkedHashMap<>();
        int n = 0;
        for (SubjectSnapshot s : open) {
            n++;
            String label = "S" + n;
            boolean shown = n <= MAX_SUMMARIES_SHOWN;
            Map<String, FactSnapshot> phrases = new LinkedHashMap<>();
            if (shown) {
                int p = 0;
                for (FactSnapshot fact : s.summary()) {
                    phrases.put(label + "." + (++p), fact);
                }
            }
            subjects.put(label, new SubjectEntry(label, s, false, shown, phrases));
        }
        for (SubjectSnapshot s : closed) {
            n++;
            subjects.put("S" + n, new SubjectEntry("S" + n, s, true, false, Map.of()));
        }

        Map<String, PersonEntry> people = new LinkedHashMap<>();
        Map<String, PersonEntry> byKey = new LinkedHashMap<>();
        Map<String, MessageEntry> messages = new LinkedHashMap<>();
        int m = 0;
        for (RadarExchangeBatch.Exchange exchange : retained) {
            for (RadarExchangeBatch.Message message : exchange.messages()) {
                PersonEntry author = null;
                if (!message.fromMe() && message.authorKey() != null) {
                    String key = RadarText.key(message.authorKey());
                    author = byKey.get(key);
                    if (author == null) {
                        author = new PersonEntry("P" + (people.size() + 1), message.authorKey(),
                                message.authorName() != null ? message.authorName() : message.authorKey(),
                                message.authorTitle());
                        byKey.put(key, author);
                        people.put(author.label(), author);
                    }
                }
                String label = "M" + (++m);
                messages.put(label, new MessageEntry(label, exchange, message, author));
            }
        }
        return new RadarExtractionContext(Map.copyOf(subjects), Map.copyOf(people), Map.copyOf(messages));
    }

    public SubjectEntry subject(String label) {
        return subjects.get(label);
    }

    public PersonEntry person(String label) {
        return people.get(label);
    }

    public MessageEntry message(String label) {
        return messages.get(label);
    }

    /** Les sujets montrés, dans l'ordre des libellés. */
    public List<SubjectEntry> subjects() {
        return subjects.values().stream().sorted(Comparator.comparingInt(e -> number(e.label()))).toList();
    }

    public List<PersonEntry> people() {
        return people.values().stream().sorted(Comparator.comparingInt(e -> number(e.label()))).toList();
    }

    public List<MessageEntry> messages() {
        return messages.values().stream().sorted(Comparator.comparingInt(e -> number(e.label()))).toList();
    }

    /**
     * Le sujet montré dont le nom ou un alias <b>non refusé</b> est exactement ce nom (normalisé), s'il y
     * en a un seul. Filet contre le doublon, pas une compréhension.
     */
    public SubjectEntry byExactName(String name) {
        String key = RadarText.key(name);
        List<SubjectEntry> found = new ArrayList<>();
        for (SubjectEntry entry : subjects.values()) {
            SubjectSnapshot s = entry.subject();
            boolean rejected = s.rejectedAliases().stream().anyMatch(r -> RadarText.key(r).equals(key));
            if (rejected) {
                continue;
            }
            if (RadarText.key(s.name()).equals(key) || s.aliases().stream().anyMatch(a -> RadarText.key(a).equals(key))) {
                found.add(entry);
            }
        }
        return found.size() == 1 ? found.get(0) : null;
    }

    /** Le registre, tel que la consigne système le porte : stable tant que le registre ne change pas. */
    public String registryBlock() {
        StringBuilder out = new StringBuilder("=== LE REGISTRE DES SUJETS SUIVIS ===\n");
        if (subjects.isEmpty()) {
            return out.append("(aucun sujet suivi pour l'instant)\n").toString();
        }
        for (SubjectEntry entry : subjects()) {
            SubjectSnapshot s = entry.subject();
            out.append('\n').append(entry.label()).append(" — ").append(RadarMaterial.oneLine(s.name()))
                    .append(" [").append(entry.closed() ? "clos" : state(s.state())).append("]\n");
            if (!s.aliases().isEmpty()) {
                out.append("  aussi appelé : ").append(String.join(" ; ", s.aliases().stream()
                        .map(RadarMaterial::oneLine).toList())).append('\n');
            }
            if (!s.rejectedAliases().isEmpty()) {
                out.append("  N'EST PAS : ").append(String.join(" ; ", s.rejectedAliases().stream()
                        .map(RadarMaterial::oneLine).toList())).append('\n');
            }
            if (entry.summaryShown()) {
                if (entry.phrases().isEmpty()) {
                    out.append("  résumé : (vide)\n");
                }
                entry.phrases().entrySet().stream().sorted(Comparator.comparingInt(e -> phraseNumber(e.getKey())))
                        .forEach(e -> out.append("  ").append(e.getKey()).append(" : ")
                                .append(RadarMaterial.oneLine(e.getValue().text())).append('\n'));
            } else if (!entry.closed()) {
                out.append("  résumé : non montré — ne propose pas de \"resume\" pour ce sujet\n");
            }
        }
        return out.toString();
    }

    /** Les personnes et les messages retenus : la partie variable, soumise en message utilisateur. */
    public String material() {
        StringBuilder out = new StringBuilder("=== LES PERSONNES ===\n");
        out.append("MOI — l'utilisateur du tableau de bord\n");
        for (PersonEntry person : people()) {
            out.append(person.label()).append(" — ").append(RadarMaterial.oneLine(person.name()));
            if (person.title() != null) {
                out.append(" (").append(RadarMaterial.oneLine(person.title())).append(')');
            }
            out.append('\n');
        }
        out.append("\n=== LES MESSAGES ===\n");
        RadarExchangeBatch.Exchange current = null;
        int e = 0;
        for (MessageEntry entry : messages()) {
            if (entry.exchange() != current) {
                current = entry.exchange();
                out.append('\n').append(RadarMaterial.exchangeHeader("échange " + (++e), current)).append('\n');
            }
            out.append(RadarMaterial.messageLine(entry.label(), entry.message(),
                    entry.author() == null ? null : entry.author().label(), MESSAGE_CHARS)).append('\n');
        }
        return out.toString();
    }

    static String state(RadarSubjectState state) {
        return switch (state) {
            case NEW -> "nouveau";
            case ADVANCING -> "avance";
            case WAITING -> "en attente";
            case BLOCKED -> "bloqué";
            case DORMANT -> "en sommeil";
            case CLOSE_PROPOSED -> "clos ?";
            case CLOSED -> "clos";
        };
    }

    private static int number(String label) {
        return Integer.parseInt(label.substring(1));
    }

    private static int phraseNumber(String label) {
        return Integer.parseInt(label.substring(label.indexOf('.') + 1));
    }
}
