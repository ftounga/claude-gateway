package fr.claudegateway.radar;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import fr.claudegateway.radar.dto.RadarSubjectPageViews.AskView;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownKind;
import fr.claudegateway.radar.dto.RadarSubjectPageViews.UnknownView;
import fr.claudegateway.radar.dto.RadarViews.CommitmentView;
import fr.claudegateway.radar.dto.RadarViews.EvidenceView;
import fr.claudegateway.radar.dto.RadarViews.PersonRef;
import fr.claudegateway.radar.dto.RadarViews.RoleView;
import fr.claudegateway.radar.dto.RadarViews.SubjectDetail;

/**
 * <b>Ce que le Radar ne sait pas</b>, et à qui le demander (F-103 / SF-103-02) — en fonction pure.
 *
 * <p>Un manque dit <b>ce que le registre ne contient pas</b>, rien de plus : pas de prochaine étape, pas
 * d'échéance, pas de décideur, un engagement sans porteur certain, une échéance déduite, un silence, une
 * synchro qui n'a pas tout lu (cadrage §4.3, §4.4). Aucun appel au modèle, aucune supposition : la règle
 * « pas de fait sans preuve » vaut aussi pour ce qu'on déclare ignorer.</p>
 *
 * <p><b>À qui demander</b> : la personne que le registre désigne — le pilote, puis le décideur, puis
 * l'auteur de la preuve la plus récente ; pour un engagement, sa partie, puis l'auteur de sa preuve.
 * Aucune personne d'un autre poste ne peut apparaître : tout vient de la vue du sujet et de l'annuaire
 * du même périmètre.</p>
 */
public final class RadarUnknowns {

    /** Plafond de manques rendus : au-delà, la page deviendrait une liste de reproches. */
    public static final int MAX_UNKNOWNS = 8;

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMMM", Locale.FRENCH);
    private static final DateTimeFormatter DAY_YEAR = DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.FRENCH);

    private RadarUnknowns() {
    }

    /**
     * Les manques d'un sujet, dans l'ordre où la page les dit.
     *
     * @param subject    la page du sujet, lue dans le périmètre
     * @param directory  l'annuaire du même périmètre, pour nommer un auteur
     * @param lastSync   l'issue de la dernière synchro du poste, ou {@code null} s'il n'y en a jamais eu
     * @param zone       le fuseau du poste, pour dire les dates
     * @param today      le jour courant dans ce fuseau (l'année d'une date n'est écrite que si elle diffère)
     */
    public static List<UnknownView> of(SubjectDetail subject, Map<UUID, RadarPerson> directory,
            RadarSyncStatus lastSync, ZoneId zone, LocalDate today) {
        if (subject.mergedIntoId() != null || subject.state() == RadarSubjectState.CLOSED) {
            return List.of();
        }
        Context ctx = new Context(subject, directory, zone, today);
        List<UnknownView> unknowns = new ArrayList<>();

        if (lastSync == RadarSyncStatus.PARTIAL || lastSync == RadarSyncStatus.FAILED) {
            unknowns.add(new UnknownView(UnknownKind.COVERAGE,
                    "La dernière synchro n'a pas tout lu : ce qui précède peut être incomplet.", null, List.of(), null));
        }
        boolean active = subject.state().isOpenWork() || subject.state() == RadarSubjectState.DORMANT;
        if (isBlank(subject.nextStep())) {
            unknowns.add(subjectUnknown(UnknownKind.NEXT_STEP, "La prochaine étape n'est pas connue.",
                    ctx.subjectAsk(true)));
        }
        if (subject.dueDate() == null && active) {
            unknowns.add(subjectUnknown(UnknownKind.DUE_DATE, "Aucune échéance n'est connue.", ctx.subjectAsk(true)));
        }
        if (subject.people().stream().noneMatch(r -> r.role() == RadarRole.DECIDES)) {
            unknowns.add(subjectUnknown(UnknownKind.DECIDER, "On ne sait pas qui décide.", ctx.subjectAsk(false)));
        }
        if (subject.state() == RadarSubjectState.DORMANT) {
            String since = ctx.day(Optional.ofNullable(subject.lastActivityAt()).orElse(subject.dormantSince()));
            String question = since == null ? "Rien n'a bougé depuis longtemps : où en est le sujet ?"
                    : "Rien n'a bougé depuis le " + since + " : où en est le sujet ?";
            unknowns.add(subjectUnknown(UnknownKind.SILENCE, question, ctx.subjectAsk(true)));
        }
        for (CommitmentView c : subject.commitments()) {
            if (unknowns.size() >= MAX_UNKNOWNS) {
                break;
            }
            if (!c.status().isPending() || c.disowned()) {
                continue;
            }
            if (c.certainty() == RadarCertainty.PROBABLE) {
                unknowns.add(new UnknownView(UnknownKind.OWNER,
                        "« " + c.description() + " » : évoqué, sans porteur certain.", ctx.commitmentAsk(c),
                        c.evidenceIds(), c.id()));
            } else if (c.dueDeduced() && c.dueDate() != null) {
                unknowns.add(new UnknownView(UnknownKind.DEDUCED_DUE,
                        "« " + c.description() + " » : l'échéance du " + ctx.day(c.dueDate())
                                + " est déduite, pas écrite.",
                        ctx.commitmentAsk(c), c.evidenceIds(), c.id()));
            }
        }
        return unknowns.size() > MAX_UNKNOWNS ? List.copyOf(unknowns.subList(0, MAX_UNKNOWNS)) : List.copyOf(unknowns);
    }

    private static UnknownView subjectUnknown(UnknownKind kind, String question, AskView ask) {
        return new UnknownView(kind, question, ask, List.of(), null);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /** Ce dont les règles de destinataire ont besoin. */
    private static final class Context {

        private final SubjectDetail subject;
        private final Map<UUID, RadarPerson> directory;
        private final ZoneId zone;
        private final LocalDate today;
        private final Map<UUID, RadarRole> roleByPerson = new HashMap<>();
        private final Map<UUID, EvidenceView> evidenceById = new HashMap<>();

        Context(SubjectDetail subject, Map<UUID, RadarPerson> directory, ZoneId zone, LocalDate today) {
            this.subject = subject;
            this.directory = directory;
            this.zone = zone;
            this.today = today;
            for (RoleView role : subject.people()) {
                roleByPerson.put(role.personId(), role.role());
            }
            for (EvidenceView evidence : subject.chronology()) {
                evidenceById.put(evidence.id(), evidence);
            }
        }

        /** Pilote, puis (si permis) décideur, puis l'auteur de la preuve la plus récente. */
        AskView subjectAsk(boolean deciderAllowed) {
            Optional<AskView> byRole = byRole(RadarRole.DRIVES, "pilote le sujet");
            if (byRole.isEmpty() && deciderAllowed) {
                byRole = byRole(RadarRole.DECIDES, "décide sur ce sujet");
            }
            return byRole.orElseGet(() -> latestAuthor(subject.chronology(), true));
        }

        /** La partie de l'engagement, puis l'auteur de sa preuve la plus récente, puis le pilote. */
        AskView commitmentAsk(CommitmentView c) {
            PersonRef party = switch (c.direction()) {
                case OTHER_TO_ME -> c.fromPerson();
                case ME_TO_OTHER -> c.toPerson();
                case INTRODUCTION -> null;
            };
            if (party != null && directory.containsKey(party.id())) {
                String reason = c.direction() == RadarCommitmentDirection.OTHER_TO_ME
                        ? "est attendu sur cet engagement" : "attend cet engagement";
                return ask(directory.get(party.id()), reason);
            }
            List<EvidenceView> proofs = c.evidenceIds().stream().map(evidenceById::get).filter(Objects::nonNull).toList();
            AskView author = latestAuthor(proofs, false);
            return author != null ? author : byRole(RadarRole.DRIVES, "pilote le sujet").orElse(null);
        }

        private Optional<AskView> byRole(RadarRole role, String reason) {
            return subject.people().stream()
                    .filter(r -> r.role() == role && directory.containsKey(r.personId()))
                    .min(Comparator.comparing(RoleView::displayName, String.CASE_INSENSITIVE_ORDER))
                    .map(r -> ask(directory.get(r.personId()), reason));
        }

        private AskView latestAuthor(List<EvidenceView> proofs, boolean onSubject) {
            return proofs.stream()
                    .filter(e -> e.authorPersonId() != null && directory.containsKey(e.authorPersonId()))
                    .max(Comparator.comparing(EvidenceView::occurredAt))
                    .map(e -> {
                        String when = day(e.occurredAt());
                        String reason = onSubject
                                ? "a écrit en dernier sur le sujet" + (when == null ? "" : ", le " + when)
                                : "a écrit la preuve de cet engagement" + (when == null ? "" : ", le " + when);
                        return ask(directory.get(e.authorPersonId()), reason);
                    })
                    .orElse(null);
        }

        private AskView ask(RadarPerson person, String reason) {
            return new AskView(person.getId(), person.getDisplayName(), person.getJobTitle(),
                    roleByPerson.get(person.getId()), reason);
        }

        String day(OffsetDateTime instant) {
            return instant == null ? null : day(instant.atZoneSameInstant(zone).toLocalDate());
        }

        String day(LocalDate date) {
            if (date == null) {
                return null;
            }
            return (date.getYear() == today.getYear() ? DAY : DAY_YEAR).format(date);
        }
    }
}
