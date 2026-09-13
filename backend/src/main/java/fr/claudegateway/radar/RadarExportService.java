package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.PersonView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;
import fr.claudegateway.radar.dto.RadarViews.SubjectSummary;
import fr.claudegateway.runner.host.RunnerHostService;

/**
 * <b>L'export Markdown</b> du Radar d'un poste (F-99 / SF-99-05), proposé avant la purge.
 *
 * <p>Il rend ce que le Radar sait, avec ses renvois : chaque phrase, chaque engagement cite les
 * preuves {@code [P1]} de la chronologie du sujet. Les sujets absorbés par une fusion n'y figurent
 * pas en propre — leur contenu est dans la cible.</p>
 */
@Service
@Transactional(readOnly = true)
public class RadarExportService {

    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RadarReadService read;
    private final RunnerHostService hostService;

    public RadarExportService(RadarReadService read, RunnerHostService hostService) {
        this.read = read;
        this.hostService = hostService;
    }

    /** Le document exporté et son nom de fichier. */
    public record Export(String fileName, String markdown) {
    }

    public Export export(RadarScope scope, LocalDate today) {
        String hostName = hostService.requireOwned(scope.userId(), scope.hostId()).getName();
        List<SubjectSummary> subjects = read.subjects(scope, null, true);
        StringBuilder md = new StringBuilder();
        md.append("# Radar — ").append(RadarMarkdown.text(hostName)).append("\n\n");
        md.append("Exporté le ").append(today).append(". Des extraits, pas des archives : citations courtes ")
                .append("et liens vers les sources.\n\n");
        md.append("## Sujets (").append(subjects.size()).append(")\n");
        for (SubjectSummary summary : subjects) {
            appendSubject(md, read.subject(scope, summary.id()));
        }
        List<PersonView> people = read.people(scope);
        md.append("\n## Annuaire (").append(people.size()).append(")\n\n");
        for (PersonView person : people) {
            md.append("- ").append(RadarMarkdown.text(person.displayName()));
            if (person.jobTitle() != null) {
                md.append(" — ").append(RadarMarkdown.text(person.jobTitle()));
            }
            if (!person.subjects().isEmpty()) {
                md.append(" — ").append(person.subjects().stream()
                        .map(s -> RadarMarkdown.text(s.subjectName()) + " (" + role(s.role()) + ")")
                        .collect(Collectors.joining(", ")));
            }
            md.append('\n');
        }
        return new Export(RadarMarkdown.fileName(hostName, today), md.toString());
    }

    private void appendSubject(StringBuilder md, SubjectDetail subject) {
        Map<UUID, String> refs = new LinkedHashMap<>();
        List<EvidenceView> chronology = new ArrayList<>(subject.chronology());
        chronology.sort((a, b) -> a.occurredAt().compareTo(b.occurredAt()));
        chronology.forEach(e -> refs.put(e.id(), "P" + (refs.size() + 1)));

        md.append("\n### ").append(RadarMarkdown.text(subject.name())).append("\n\n");
        md.append("- État : ").append(state(subject.state())).append('\n');
        if (!subject.aliases().isEmpty()) {
            List<String> known = subject.aliases().stream().filter(a -> !a.rejected())
                    .map(a -> RadarMarkdown.text(a.alias())).toList();
            List<String> refused = subject.aliases().stream().filter(a -> a.rejected())
                    .map(a -> RadarMarkdown.text(a.alias())).toList();
            if (!known.isEmpty()) {
                md.append("- Aussi appelé : ").append(String.join(", ", known)).append('\n');
            }
            if (!refused.isEmpty()) {
                md.append("- N'est pas : ").append(String.join(", ", refused)).append('\n');
            }
        }
        if (subject.nextStep() != null) {
            md.append("- Prochaine étape : ").append(RadarMarkdown.text(subject.nextStep()))
                    .append(cite(refs, subject.nextStepEvidenceIds())).append('\n');
        }
        if (subject.dueDate() != null) {
            md.append("- Échéance : ").append(subject.dueDate()).append(cite(refs, subject.dueDateEvidenceIds()))
                    .append('\n');
        }
        if (subject.closedAt() != null) {
            md.append("- Clos le : ").append(subject.closedAt().format(WHEN)).append('\n');
        }
        if (!subject.summary().isEmpty()) {
            md.append("\n**Résumé**\n\n");
            subject.summary().forEach(s -> md.append("- ").append(RadarMarkdown.text(s.text()))
                    .append(cite(refs, s.evidenceIds())).append('\n'));
        }
        if (!subject.people().isEmpty()) {
            md.append("\n**Personnes**\n\n");
            subject.people().forEach(r -> md.append("- ").append(RadarMarkdown.text(r.displayName()))
                    .append(" — ").append(role(r.role())).append(cite(refs, r.evidenceIds())).append('\n'));
        }
        if (!subject.commitments().isEmpty()) {
            md.append("\n**Engagements**\n\n");
            subject.commitments().forEach(c -> md.append("- ").append(commitment(c))
                    .append(cite(refs, c.evidenceIds())).append('\n'));
        }
        if (!chronology.isEmpty()) {
            md.append("\n**Chronologie**\n\n");
            for (EvidenceView e : chronology) {
                md.append("- ").append(refs.get(e.id())).append(" · ").append(e.occurredAt().format(WHEN))
                        .append(" · ").append(source(e.source())).append(" — « ")
                        .append(RadarMarkdown.text(e.quote())).append(" »");
                String link = RadarMarkdown.link(e.deepLink());
                if (!link.isEmpty()) {
                    md.append(" — ").append(link);
                }
                md.append('\n');
            }
        }
    }

    private static String commitment(CommitmentView c) {
        StringBuilder line = new StringBuilder();
        line.append('[').append(status(c.status())).append(" · ")
                .append(c.certainty() == RadarCertainty.CERTAIN ? "certain" : "probable");
        if (c.disowned()) {
            line.append(" · pas moi");
        }
        line.append("] ");
        switch (c.direction()) {
            case ME_TO_OTHER -> line.append("À faire par moi");
            case OTHER_TO_ME -> line.append("J'attends de ").append(name(c.fromPerson()));
            case INTRODUCTION -> line.append("Présenter ").append(name(c.toPerson())).append(" et ")
                    .append(name(c.otherPerson()));
            default -> line.append("Engagement");
        }
        line.append(" : ").append(RadarMarkdown.text(c.description()));
        if (c.dueDate() != null) {
            line.append(" — échéance ").append(c.dueDate()).append(c.dueDeduced() ? " (déduite)" : "");
        }
        return line.toString();
    }

    private static String name(PersonRef person) {
        return person == null ? "?" : RadarMarkdown.text(person.displayName());
    }

    private static String cite(Map<UUID, String> refs, List<UUID> ids) {
        if (ids == null || ids.isEmpty()) {
            return "";
        }
        List<String> labels = new ArrayList<>(refs.values());
        return " " + ids.stream().map(id -> refs.getOrDefault(id, "?")).distinct()
                .sorted(Comparator.comparingInt(label -> {
                    int index = labels.indexOf(label);
                    return index < 0 ? Integer.MAX_VALUE : index;
                }))
                .map(label -> "[" + label + "]")
                .collect(Collectors.joining(""));
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

    static String role(RadarRole role) {
        return switch (role) {
            case DECIDES -> "décide";
            case DRIVES -> "pilote";
            case EXPERT -> "expert";
            case INFORMED -> "informé";
        };
    }

    static String status(RadarCommitmentStatus status) {
        return switch (status) {
            case OPEN -> "ouvert";
            case KEPT -> "tenu";
            case POSTPONED -> "reporté";
            case ABANDONED -> "abandonné";
        };
    }

    static String source(RadarEvidenceSource source) {
        return switch (source) {
            case TEAMS_MESSAGE -> "message Teams";
            case TEAMS_MEETING -> "réunion Teams";
            case LOCAL_RECORDING -> "enregistrement";
            case USER_NOTE -> "nouvelle donnée";
            case PASTED_MAIL -> "courriel collé";
        };
    }
}
