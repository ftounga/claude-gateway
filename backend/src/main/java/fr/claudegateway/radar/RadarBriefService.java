package fr.claudegateway.radar;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import fr.claudegateway.radar.dto.RadarBoardViews.BriefCounts;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefKind;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefSentence;
import fr.claudegateway.radar.dto.RadarBoardViews.BriefView;
import fr.claudegateway.radar.dto.RadarBoardViews.CoverageLine;
import fr.claudegateway.radar.dto.RadarViews.SyncView;

/**
 * <b>Le résumé du matin</b> d'un client (F-102 / SF-102-01) : ce qui a bougé depuis hier, les compteurs, et
 * ce que la dernière synchro a lu.
 *
 * <p><b>Composé, jamais rédigé.</b> Chaque phrase est assemblée depuis le registre et désigne l'objet qui la
 * fonde — lui-même prouvé (cadrage §4.1). Aucun appel au modèle : ouvrir l'écran ne coûte rien.
 * <b>Il dit ce qu'il n'a pas lu</b> (§4.4) : une couverture incomplète, ancienne ou absente produit un
 * avertissement que l'écran place en tête.</p>
 *
 * <p>Toutes les lectures portent {@code user_id} et {@code host_id}.</p>
 */
@Service
@Transactional(readOnly = true)
public class RadarBriefService {

    /** Au plus trois phrases (cadrage §8). */
    static final int MAX_SENTENCES = 3;

    /** « Depuis hier » : 24 heures glissantes. */
    static final Duration SINCE = Duration.ofHours(24);

    /** Au-delà, la dernière synchro est dite ancienne : ce qui a bougé depuis n'a pas été lu. */
    static final Duration STALE = Duration.ofHours(36);

    /** Longueur d'une citation de description dans une phrase. */
    static final int QUOTE_MAX = 80;

    private static final DateTimeFormatter DAY_MONTH = DateTimeFormatter.ofPattern("MMMM", Locale.FRENCH);

    private final RadarSubjectRepository subjects;
    private final RadarCommitmentRepository commitments;
    private final RadarPersonRepository people;
    private final RadarReadService readService;
    private final Clock clock;

    public RadarBriefService(RadarSubjectRepository subjects, RadarCommitmentRepository commitments,
            RadarPersonRepository people, RadarReadService readService, Clock clock) {
        this.subjects = subjects;
        this.commitments = commitments;
        this.people = people;
        this.readService = readService;
        this.clock = clock;
    }

    public BriefView brief(RadarScope scope) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime since = now.minus(SINCE);
        LocalDate today = now.atZoneSameInstant(ZoneOffset.UTC).toLocalDate();

        List<RadarSubject> live = subjects.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(s -> s.getMergedIntoId() == null)
                .toList();
        Map<UUID, RadarSubject> subjectsById = live.stream()
                .collect(Collectors.toMap(RadarSubject::getId, Function.identity()));
        List<RadarCommitment> pending = commitments.findByUserIdAndHostId(scope.userId(), scope.hostId()).stream()
                .filter(c -> c.getStatus() != null && c.getStatus().isPending() && !c.isDisowned())
                .filter(c -> subjectsById.containsKey(c.getSubjectId()))
                .toList();
        Map<UUID, RadarPerson> directory = people.findByUserIdAndHostIdOrderByDisplayNameAsc(scope.userId(),
                scope.hostId()).stream().collect(Collectors.toMap(RadarPerson::getId, Function.identity()));

        List<SyncView> syncs = readService.syncs(scope);
        SyncView running = syncs.stream().filter(s -> s.status() == RadarSyncStatus.RUNNING).findFirst().orElse(null);
        SyncView lastSync = syncs.stream().filter(s -> s.status() != RadarSyncStatus.RUNNING).findFirst().orElse(null);

        boolean stale = lastSync != null && instantOf(lastSync).isBefore(now.minus(STALE));
        boolean complete = lastSync != null && !stale && lastSync.status() == RadarSyncStatus.SUCCEEDED
                && lastSync.summary() != null && lastSync.summary().headline() != null
                && lastSync.summary().headline().startsWith("Synchro complète");
        String warning = warning(lastSync, running, stale, complete);

        List<BriefSentence> sentences = sentences(live, pending, directory, since, today);
        if (sentences.isEmpty() && lastSync != null && !stale) {
            sentences = List.of(new BriefSentence(BriefKind.CALM,
                    complete ? "Rien n'a bougé depuis hier." : "Rien de nouveau dans ce qui a été lu.", null, null));
        }

        return new BriefView(now, since, sentences, counts(live, pending, today), running, lastSync, complete,
                warning, lastSync == null ? List.of() : coverageLines(lastSync.coverage()));
    }

    // -------------------------------------------------------------------------------- phrases

    private List<BriefSentence> sentences(List<RadarSubject> live, List<RadarCommitment> pending,
            Map<UUID, RadarPerson> directory, OffsetDateTime since, LocalDate today) {
        List<BriefSentence> out = new ArrayList<>();

        // 1. Un sujet clos qui se réveille : jamais rouvert en silence (SF-99-04).
        live.stream()
                .filter(s -> s.getState() == RadarSubjectState.CLOSED && s.getWokeAt() != null)
                .sorted(Comparator.comparing(RadarSubject::getWokeAt).reversed())
                .forEach(s -> out.add(new BriefSentence(BriefKind.WAKE,
                        "Le sujet " + quote(s.getName()) + " se réveille : rouvrir ou laisser clos ?", s.getId(), null)));

        // 2. Ce que je devais faire et qui est en retard.
        List<RadarCommitment> overdue = pending.stream()
                .filter(RadarBriefService::isMine)
                .filter(c -> !isQuestion(c))
                .filter(c -> c.getDueDate() != null && c.getDueDate().isBefore(today))
                .sorted(Comparator.comparing(RadarCommitment::getDueDate))
                .toList();
        if (overdue.size() == 1) {
            RadarCommitment c = overdue.get(0);
            long late = ChronoUnit.DAYS.between(c.getDueDate(), today);
            out.add(new BriefSentence(BriefKind.OVERDUE, "Vous deviez " + quote(c.getDescription()) + " pour le "
                    + day(c.getDueDate()) + " : en retard de " + plural(late, "jour", "jours") + ".",
                    c.getSubjectId(), c.getId()));
        } else if (overdue.size() > 1) {
            out.add(new BriefSentence(BriefKind.OVERDUE, overdue.size() + " engagements à votre charge sont en retard : "
                    + quotes(overdue.stream().map(RadarCommitment::getDescription).toList()) + ".",
                    overdue.get(0).getSubjectId(), overdue.get(0).getId()));
        }

        // 3. Les relances dues.
        List<RadarCommitment> followUps = pending.stream()
                .filter(c -> followUpDue(c, today))
                .sorted(Comparator.comparing(RadarCommitment::getFollowUpDueOn))
                .toList();
        if (followUps.size() == 1) {
            RadarCommitment c = followUps.get(0);
            String who = personOf(c, directory);
            out.add(new BriefSentence(BriefKind.FOLLOW_UP, "Relance due : " + (who == null ? "" : who + " — ")
                    + quote(c.getDescription()) + ".", c.getSubjectId(), c.getId()));
        } else if (followUps.size() > 1) {
            List<String> names = followUps.stream()
                    .map(c -> {
                        String who = personOf(c, directory);
                        return who == null ? quote(c.getDescription()) : who;
                    })
                    .distinct()
                    .toList();
            out.add(new BriefSentence(BriefKind.FOLLOW_UP, followUps.size() + " relances dues : "
                    + String.join(", ", names) + ".", followUps.get(0).getSubjectId(), followUps.get(0).getId()));
        }

        // 4. Une clôture proposée depuis hier.
        live.stream()
                .filter(s -> s.getState() == RadarSubjectState.CLOSE_PROPOSED && after(s.getCloseProposedAt(), since))
                .sorted(Comparator.comparing(RadarSubject::getCloseProposedAt).reversed())
                .forEach(s -> out.add(new BriefSentence(BriefKind.CLOSE_PROPOSED,
                        quote(s.getName()) + " peut être clos : à confirmer.", s.getId(), null)));

        // 5. Les nouveaux sujets.
        List<RadarSubject> fresh = live.stream()
                .filter(s -> s.getState() == RadarSubjectState.NEW && after(s.getCreatedAt(), since))
                .sorted(Comparator.comparing(RadarSubject::getCreatedAt).reversed())
                .toList();
        if (fresh.size() == 1) {
            out.add(new BriefSentence(BriefKind.NEW_SUBJECTS, "Nouveau sujet : " + quote(fresh.get(0).getName()) + ".",
                    fresh.get(0).getId(), null));
        } else if (fresh.size() > 1) {
            out.add(new BriefSentence(BriefKind.NEW_SUBJECTS, fresh.size() + " nouveaux sujets : "
                    + quotes(fresh.stream().map(RadarSubject::getName).toList()) + ".", fresh.get(0).getId(), null));
        }

        // 6. Un engagement probable apparu depuis hier, posé comme une question.
        pending.stream()
                .filter(RadarBriefService::isMine)
                .filter(RadarBriefService::isQuestion)
                .filter(c -> after(c.getCreatedAt(), since))
                .sorted(Comparator.comparing(RadarCommitment::getCreatedAt).reversed())
                .forEach(c -> out.add(new BriefSentence(BriefKind.QUESTION, "À confirmer : "
                        + quote(c.getDescription()) + " vous revient-il ?", c.getSubjectId(), c.getId())));

        // 7. Un sujet qui a bougé depuis hier (hors nouveaux).
        live.stream()
                .filter(s -> (s.getState() == RadarSubjectState.ADVANCING || s.getState() == RadarSubjectState.WAITING
                        || s.getState() == RadarSubjectState.BLOCKED)
                        && after(s.getLastActivityAt(), since) && !after(s.getCreatedAt(), since))
                .sorted(Comparator.comparing(RadarSubject::getLastActivityAt).reversed())
                .forEach(s -> out.add(new BriefSentence(BriefKind.MOVED, quote(s.getName()) + " " + stateWords(s.getState())
                        + (blank(s.getNextStep()) ? "." : " ; prochaine étape : " + clip(s.getNextStep()) + "."),
                        s.getId(), null)));

        // 8. Un sujet passé en sommeil depuis hier : jamais clos par le silence.
        live.stream()
                .filter(s -> s.getState() == RadarSubjectState.DORMANT && after(s.getDormantSince(), since))
                .forEach(s -> {
                    OffsetDateTime last = s.getLastActivityAt() == null ? s.getDormantSince() : s.getLastActivityAt();
                    long days = Math.max(1, ChronoUnit.DAYS.between(last, since.plus(SINCE)));
                    out.add(new BriefSentence(BriefKind.DORMANT, quote(s.getName()) + " n'a plus bougé depuis "
                            + plural(days, "jour", "jours") + " : qu'en est-il ?", s.getId(), null));
                });

        return out.size() <= MAX_SENTENCES ? List.copyOf(out) : List.copyOf(out.subList(0, MAX_SENTENCES));
    }

    // ------------------------------------------------------------------------------ compteurs

    private static BriefCounts counts(List<RadarSubject> live, List<RadarCommitment> pending, LocalDate today) {
        int toDo = (int) pending.stream().filter(c -> c.getDirection() == RadarCommitmentDirection.ME_TO_OTHER).count();
        int introductions = (int) pending.stream()
                .filter(c -> c.getDirection() == RadarCommitmentDirection.INTRODUCTION).count();
        int followUps = (int) pending.stream().filter(c -> followUpDue(c, today)).count();
        int followed = (int) live.stream().filter(s -> s.getState() != RadarSubjectState.CLOSED).count();
        int blocked = (int) live.stream().filter(s -> s.getState() == RadarSubjectState.BLOCKED).count();

        Set<UUID> handle = new HashSet<>();
        for (RadarCommitment c : pending) {
            boolean dueMine = isMine(c) && c.getDueDate() != null && !c.getDueDate().isAfter(today);
            if (dueMine || followUpDue(c, today) || isQuestion(c)) {
                handle.add(c.getId());
            }
        }
        long subjectsToHandle = live.stream()
                .filter(s -> s.getState() == RadarSubjectState.CLOSE_PROPOSED
                        || (s.getState() == RadarSubjectState.CLOSED && s.getWokeAt() != null))
                .count();
        return new BriefCounts(toDo, followUps, introductions, followed, blocked,
                handle.size() + (int) subjectsToHandle);
    }

    // ------------------------------------------------------------------------------ couverture

    private static String warning(SyncView lastSync, SyncView running, boolean stale, boolean complete) {
        if (lastSync == null) {
            return running == null ? "Aucune synchro encore : le Radar se remplira à la première synchro du soir."
                    : "Première synchro en cours : le résumé se remplira à sa fin.";
        }
        if (stale) {
            OffsetDateTime at = instantOf(lastSync);
            return "Dernière synchro le " + day(at.atZoneSameInstant(ZoneOffset.UTC).toLocalDate())
                    + " : ce qui a bougé depuis n'a pas été lu.";
        }
        if (complete) {
            return null;
        }
        String headline = lastSync.summary() == null ? null : lastSync.summary().headline();
        return blank(headline) ? "La dernière synchro n'a pas tout lu." : headline;
    }

    /** Ce qui a été lu, par source. */
    static List<CoverageLine> coverageLines(JsonNode coverage) {
        JsonNode cov = coverage == null ? MissingNode.getInstance() : coverage;
        List<CoverageLine> lines = new ArrayList<>();

        JsonNode conversations = cov.path("conversations");
        if (conversations.isObject()) {
            int read = conversations.path("read").asInt(0);
            int active = conversations.path("active").asInt(0);
            int messages = cov.path("messages").asInt(0);
            String text = "Teams · " + plural(read, "fil lu", "fils lus")
                    + (active > read ? " sur " + plural(active, "actif", "actifs") : "")
                    + (messages > 0 ? ", " + plural(messages, "message", "messages") : "");
            boolean ok = conversations.path("partial").asInt(0) + conversations.path("failed").asInt(0) == 0
                    && conversations.path("deferred").asInt(0) == 0
                    && cov.path("discovery").path("complete").asBoolean(true)
                    && cov.path("channels").path("unreadActive").asInt(0) == 0;
            lines.add(new CoverageLine("TEAMS", ok, text));
        }

        JsonNode meetings = cov.path("meetings");
        if (meetings.isObject()) {
            int transcribed = meetings.path("transcribed").asInt(0);
            int seen = meetings.path("seen").asInt(0);
            int gaps = meetings.path("failed").asInt(0) + meetings.path("denied").asInt(0)
                    + meetings.path("noTranscript").asInt(0);
            boolean refused = "REFUSED".equals(meetings.path("navigation").asText(""));
            if (refused) {
                lines.add(new CoverageLine("MEETINGS", false, "Calendrier inaccessible"));
            } else if (seen > 0 || transcribed > 0 || gaps > 0) {
                lines.add(new CoverageLine("MEETINGS", gaps == 0,
                        plural(transcribed, "réunion transcrite", "réunions transcrites")
                                + (seen > transcribed ? " sur " + plural(seen, "vue", "vues") : "")));
            }
        }

        JsonNode depot = cov.path("depot");
        if (depot.isObject()) {
            int found = depot.path("found").asInt(0);
            int transcribed = depot.path("transcribed").asInt(0);
            int gaps = depot.path("failed").asInt(0) + depot.path("unavailable").asInt(0);
            if (found > 0 || transcribed > 0 || gaps > 0) {
                lines.add(new CoverageLine("RECORDINGS", gaps == 0,
                        plural(transcribed, "enregistrement déposé transcrit", "enregistrements déposés transcrits")));
            }
        }
        return List.copyOf(lines);
    }

    // ---------------------------------------------------------------------------------- aides

    /** À ma charge : moi → autre, ou une mise en relation que je dois faire. */
    static boolean isMine(RadarCommitment c) {
        return c.getDirection() == RadarCommitmentDirection.ME_TO_OTHER
                || c.getDirection() == RadarCommitmentDirection.INTRODUCTION;
    }

    /** Un « probable » que l'utilisateur n'a pas tranché : posé comme une question (§4.3). */
    static boolean isQuestion(RadarCommitment c) {
        return c.getCertainty() == RadarCertainty.PROBABLE && !c.isSovereign();
    }

    static boolean followUpDue(RadarCommitment c, LocalDate today) {
        return c.getFollowUpDueOn() != null && !c.getFollowUpDueOn().isAfter(today);
    }

    private static String personOf(RadarCommitment c, Map<UUID, RadarPerson> directory) {
        UUID id = c.getFromPersonId() != null ? c.getFromPersonId() : c.getToPersonId();
        RadarPerson person = id == null ? null : directory.get(id);
        return person == null ? null : person.getDisplayName();
    }

    private static OffsetDateTime instantOf(SyncView sync) {
        return sync.finishedAt() != null ? sync.finishedAt() : sync.startedAt();
    }

    private static boolean after(OffsetDateTime at, OffsetDateTime since) {
        return at != null && !at.isBefore(since);
    }

    private static String stateWords(RadarSubjectState state) {
        return switch (state) {
            case WAITING -> "est en attente";
            case BLOCKED -> "est bloqué";
            default -> "avance";
        };
    }

    static String day(LocalDate date) {
        return (date.getDayOfMonth() == 1 ? "1er" : String.valueOf(date.getDayOfMonth())) + " " + DAY_MONTH.format(date);
    }

    private static String quote(String text) {
        return "« " + clip(text) + " »";
    }

    private static String quotes(List<String> texts) {
        return texts.stream().map(RadarBriefService::quote).collect(Collectors.joining(", "));
    }

    static String clip(String text) {
        String clean = text == null ? "" : text.strip().replaceAll("\\s+", " ");
        return clean.length() <= QUOTE_MAX ? clean : clean.substring(0, QUOTE_MAX - 1).strip() + "…";
    }

    private static boolean blank(String text) {
        return text == null || text.isBlank();
    }

    private static String plural(long count, String one, String many) {
        return count + " " + (count > 1 ? many : one);
    }
}
