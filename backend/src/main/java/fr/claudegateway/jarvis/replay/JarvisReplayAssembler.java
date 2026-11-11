package fr.claudegateway.jarvis.replay;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import fr.claudegateway.atelier.AtelierMessage;
import fr.claudegateway.jarvis.capture.JarvisAudioCapture;
import fr.claudegateway.jarvis.replay.JarvisReplay.Correlation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Delegation;
import fr.claudegateway.jarvis.replay.JarvisReplay.Heard;
import fr.claudegateway.jarvis.replay.JarvisReplay.Show;
import fr.claudegateway.jarvis.replay.JarvisReplay.Steps;
import fr.claudegateway.jarvis.replay.JarvisReplay.Subject;
import fr.claudegateway.jarvis.replay.JarvisReplay.Turn;
import fr.claudegateway.jarvis.timing.JarvisTurnTiming;
import fr.claudegateway.jarvis.trace.JarvisListenTraceEvent;
import fr.claudegateway.jarvis.trace.JarvisListenTraceService;

/**
 * <b>L'assemblage du rejeu</b> (F-255 / SF-255-02) — pur : des lignes déjà lues sous le compte, un rejeu tour par tour.
 *
 * <p><b>Le tour.</b> Le journal d'écoute est l'épine dorsale : un tour commence à chaque {@code listen_open} (ou à
 * chaque nouvel identifiant de tour, SF-255-01) et n'en est un que s'il porte une parole ({@code listen_end},
 * {@code transcript}, {@code speech_rejected}, {@code barge_in_turn}) ; une écoute restée muette n'est pas un tour.</p>
 *
 * <p><b>La corrélation.</b> Avec l'identifiant de tour, la mesure et la capture sont rattachées exactement, et chaque
 * événement porte son heure serveur. Sans lui (séances d'avant SF-255-01), l'horloge de la page est recalée sur les
 * mesures (une parole refusée est mesurée à l'instant du refus, une réponse à la fin de la voix) et le rattachement se
 * fait par fenêtre de temps : il est marqué {@link Correlation#ESTIMEE}. Les messages (ce qui a été dit et répondu, les
 * délégations) se rattachent toujours par fenêtre de temps, autour de la fin de parole.</p>
 */
public final class JarvisReplayAssembler {

    /** Les événements qui font d'une écoute un tour. */
    static final Set<String> SPEECH = Set.of("listen_end", "transcript", "speech_rejected", "barge_in_turn");
    /** Les événements datés au même instant que la mesure de l'écran (refus, fin de la voix). */
    static final Set<String> TIMING_ANCHORS = Set.of("speech_rejected", "voice_idle", "say_end");
    /** Les événements bavards ou sans intérêt pour un lecteur, écartés du résumé du tour. */
    static final Set<String> NOISE = Set.of("meter", "mic_keep", "state", "relisten_arm", "relisten_fire");

    static final long ANCHOR_TOLERANCE_MS = 400;
    static final long CALIBRATION_RANGE_MS = 15_000;
    /** Au-delà, le message suivant n'appartient plus au tour (délégation en file d'attente). */
    static final Duration DELEGATED_USER_WINDOW = Duration.ofSeconds(15);
    /** La réponse d'une délégation est cherchée dans ce délai. */
    static final Duration DELEGATION_REPLY_WINDOW = Duration.ofMinutes(20);
    static final int MAX_TEXT = 600;
    static final int MAX_EVENTS = 60;

    private static final Pattern CHARS = Pattern.compile("chars=(\\d+)");
    private static final Pattern MODE = Pattern.compile("mode=([A-Z_]+)");
    private static final Pattern SAID = Pattern.compile("said=(\\d+)");
    private static final Pattern SUBJECT = Pattern.compile("subject=([0-9a-fA-F-]{36})");
    private static final Pattern QUOTED = Pattern.compile("«\\s*(.+?)\\s*»");

    private JarvisReplayAssembler() {
    }

    /** Ce qu'il faut pour assembler : lignes du compte, fil Jarvis, noms des sujets. */
    public record Sources(List<JarvisListenTraceEvent> traces, List<JarvisTurnTiming> timings,
            List<JarvisAudioCapture> captures, List<AtelierMessage> messages, UUID threadId,
            Map<UUID, String> subjectNames) {
    }

    /** Un tour en cours d'assemblage. */
    static final class Draft {
        String turnId;
        String pageId;
        boolean exact;
        final List<JarvisListenTraceEvent> events = new ArrayList<>();
        final List<Instant> times = new ArrayList<>();
        Instant at;
        Instant next;
        JarvisTurnTiming timing;
        JarvisAudioCapture capture;
        UUID screenSubject;
    }

    /** Les tours des lignes données, dans l'ordre du temps. */
    public static List<Turn> assemble(Sources sources) {
        List<JarvisListenTraceEvent> server = new ArrayList<>();
        Map<String, List<JarvisListenTraceEvent>> pages = new LinkedHashMap<>();
        for (JarvisListenTraceEvent e : sources.traces()) {
            if (e.getEvent() != null && e.getEvent().startsWith("srv_")) {
                server.add(e);
            } else {
                pages.computeIfAbsent(e.getPageId(), k -> new ArrayList<>()).add(e);
            }
        }
        List<Draft> drafts = new ArrayList<>();
        for (Map.Entry<String, List<JarvisListenTraceEvent>> page : pages.entrySet()) {
            drafts.addAll(segment(page.getKey(), page.getValue(), sources.timings()));
        }
        drafts.sort(Comparator.comparing(d -> d.at));
        for (int i = 0; i < drafts.size(); i++) {
            Draft d = drafts.get(i);
            d.next = i + 1 < drafts.size() ? drafts.get(i + 1).at : d.at.plus(Duration.ofMinutes(10));
        }
        attachTimings(drafts, sources.timings());
        attachCaptures(drafts, sources.captures());
        for (JarvisListenTraceEvent e : server) {
            drafts.stream().filter(d -> d.turnId != null && d.turnId.equals(e.getTurnId())).findFirst()
                    .ifPresent(d -> {
                        Matcher m = SUBJECT.matcher(Objects.toString(e.getDetail(), ""));
                        if (m.find()) {
                            d.screenSubject = UUID.fromString(m.group(1));
                        }
                    });
        }
        Messages messages = new Messages(sources.messages(), sources.threadId());
        List<Turn> turns = new ArrayList<>();
        for (int i = 0; i < drafts.size(); i++) {
            turns.add(turn(i + 1, drafts.get(i), messages, sources.subjectNames()));
        }
        return turns;
    }

    // ---- Le journal : pages, horloge, tours ----

    static List<Draft> segment(String pageId, List<JarvisListenTraceEvent> events, List<JarvisTurnTiming> timings) {
        List<JarvisListenTraceEvent> sorted = new ArrayList<>(events);
        sorted.sort(Comparator.comparingInt(JarvisListenTraceEvent::getSeq));
        Long pageStart = sorted.stream().allMatch(e -> e.getOccurredAt() != null) ? null
                : pageStartMs(sorted, timings.stream().map(t -> t.getCreatedAt().toInstant().toEpochMilli()).toList());
        List<Draft> drafts = new ArrayList<>();
        Draft current = null;
        for (JarvisListenTraceEvent e : sorted) {
            boolean opens = e.getTurnId() != null ? current == null || !e.getTurnId().equals(current.turnId)
                    : "listen_open".equals(e.getEvent());
            if (opens) {
                current = new Draft();
                current.turnId = e.getTurnId();
                current.pageId = pageId;
                current.exact = e.getTurnId() != null && e.getOccurredAt() != null;
                drafts.add(current);
            }
            if (current == null) {
                continue;
            }
            current.events.add(e);
            current.times.add(e.getOccurredAt() != null ? e.getOccurredAt().toInstant()
                    : Instant.ofEpochMilli((pageStart == null ? e.getCreatedAt().toInstant().toEpochMilli()
                            - e.getAtMs() : pageStart) + e.getAtMs()));
        }
        drafts.removeIf(d -> d.events.stream().noneMatch(e -> SPEECH.contains(e.getEvent())));
        for (Draft d : drafts) {
            d.at = timeOf(d, "listen_end");
            if (d.at == null) {
                d.at = timeOf(d, "transcript");
            }
            if (d.at == null) {
                d.at = d.times.get(0);
            }
        }
        return drafts;
    }

    private static Instant timeOf(Draft d, String event) {
        for (int i = 0; i < d.events.size(); i++) {
            if (event.equals(d.events.get(i).getEvent())) {
                return d.times.get(i);
            }
        }
        return null;
    }

    /**
     * L'heure serveur de l'ouverture d'une page d'avant SF-255-01 (ms epoch). Borne haute : {@code min(envoi − t)} (un
     * lot part au plus tôt à l'événement). Recalage : la mesure d'un tour est envoyée à l'instant d'un refus ou de la fin
     * de la voix ; le décalage qui aligne le plus de ces instants sur des mesures (± 400 ms) l'emporte, s'il en aligne au
     * moins deux. Sinon, la borne haute.
     */
    static long pageStartMs(List<JarvisListenTraceEvent> events, List<Long> timingMs) {
        long bound = events.stream().mapToLong(e -> e.getCreatedAt().toInstant().toEpochMilli() - e.getAtMs()).min()
                .orElse(0);
        List<Long> anchors = events.stream().filter(e -> TIMING_ANCHORS.contains(e.getEvent()))
                .map(JarvisListenTraceEvent::getAtMs).toList();
        long best = bound;
        int bestScore = 1;
        for (long a : anchors) {
            for (long t : timingMs) {
                long candidate = t - a;
                if (candidate < bound - CALIBRATION_RANGE_MS || candidate > bound + ANCHOR_TOLERANCE_MS) {
                    continue;
                }
                int score = 0;
                for (long other : anchors) {
                    long expected = candidate + other;
                    if (timingMs.stream().anyMatch(x -> Math.abs(x - expected) <= ANCHOR_TOLERANCE_MS)) {
                        score++;
                    }
                }
                if (score > bestScore || (score == bestScore && score > 1 && candidate > best)) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    // ---- Mesures et captures ----

    static void attachTimings(List<Draft> drafts, List<JarvisTurnTiming> timings) {
        Set<JarvisTurnTiming> used = new HashSet<>();
        for (Draft d : drafts) {
            if (d.turnId == null) {
                continue;
            }
            timings.stream().filter(t -> d.turnId.equals(t.getTurnId()) && !used.contains(t)).findFirst()
                    .ifPresent(t -> {
                        d.timing = t;
                        used.add(t);
                    });
        }
        for (Draft d : drafts) {
            if (d.timing != null || d.exact) {
                continue;
            }
            boolean rejected = d.events.stream().anyMatch(e -> "speech_rejected".equals(e.getEvent()));
            Instant from = d.at.minusMillis(1_500);
            timings.stream()
                    .filter(t -> !used.contains(t) && t.getTurnId() == null)
                    .filter(t -> (t.getRejectedKind() != null) == rejected)
                    .filter(t -> within(t.getCreatedAt(), from, d.next.minusMillis(300)))
                    .min(Comparator.comparing(JarvisTurnTiming::getCreatedAt))
                    .ifPresent(t -> {
                        d.timing = t;
                        used.add(t);
                    });
        }
    }

    static void attachCaptures(List<Draft> drafts, List<JarvisAudioCapture> captures) {
        Set<JarvisAudioCapture> used = new HashSet<>();
        for (Draft d : drafts) {
            JarvisAudioCapture found = d.turnId == null ? null
                    : captures.stream().filter(c -> d.turnId.equals(c.getTurnId())).findFirst().orElse(null);
            if (found == null && !d.exact) {
                found = captures.stream()
                        .filter(c -> !used.contains(c) && c.getTurnId() == null)
                        .filter(c -> within(c.getCreatedAt(), d.at.minusMillis(1_000), d.next))
                        .min(Comparator.comparing(JarvisAudioCapture::getCreatedAt)).orElse(null);
            }
            if (found != null) {
                d.capture = found;
                used.add(found);
            }
        }
    }

    private static boolean within(OffsetDateTime at, Instant from, Instant to) {
        Instant i = at.toInstant();
        return !i.isBefore(from) && i.isBefore(to);
    }

    // ---- Messages : ce qui a été dit, répondu, délégué ----

    static final class Messages {
        final List<AtelierMessage> all;
        final UUID threadId;
        final Set<AtelierMessage> used = new HashSet<>();

        Messages(List<AtelierMessage> all, UUID threadId) {
            this.all = all.stream().sorted(Comparator.comparing(AtelierMessage::getCreatedAt)).toList();
            this.threadId = threadId;
        }

        boolean inThread(AtelierMessage m) {
            return threadId != null && threadId.equals(m.getWorkspaceId());
        }

        List<AtelierMessage> window(Instant from, Instant to) {
            return all.stream().filter(m -> !used.contains(m) && within(m.getCreatedAt(), from, to)).toList();
        }
    }

    static Turn turn(int number, Draft d, Messages messages, Map<UUID, String> names) {
        JarvisTurnTiming t = d.timing;
        Integer chars = null;
        String mode = null;
        String rejected = null;
        Integer said = null;
        boolean streamError = false;
        List<String> fallbacks = new ArrayList<>();
        List<Show> shows = new ArrayList<>();
        List<String> events = new ArrayList<>();
        for (int i = 0; i < d.events.size(); i++) {
            JarvisListenTraceEvent e = d.events.get(i);
            String ev = e.getEvent();
            String detail = Objects.toString(e.getDetail(), "");
            switch (ev) {
                case "transcript" -> {
                    chars = intOf(CHARS, detail);
                    mode = strOf(MODE, detail);
                }
                case "speech_rejected" -> rejected = detail.isBlank() ? "empty" : detail.split(" ")[0];
                case "brain_done" -> said = intOf(SAID, detail);
                case "stream_fallback" -> fallbacks.add(detail.isBlank() ? "autre" : detail);
                case "stream_error" -> streamError = true;
                case "show" -> shows.add(new Show(detail.split(" ")[0], false, null));
                case "show_refused" -> {
                    String[] parts = detail.split(" ", 2);
                    shows.add(new Show(parts[0], true, parts.length > 1 ? parts[1] : null));
                }
                default -> {
                }
            }
            if (!NOISE.contains(ev) && events.size() < MAX_EVENTS) {
                long ms = Duration.between(d.at, d.times.get(i)).toMillis();
                events.add(String.format(java.util.Locale.ROOT, "%+.1f s %s%s", ms / 1000.0, ev, detail.isBlank() ? "" : " " + detail));
            }
        }
        if (rejected == null && t != null && t.getRejectedKind() != null) {
            rejected = t.getRejectedKind();
        }
        if (t != null && t.getStreamFallback() != null && !fallbacks.contains(t.getStreamFallback())) {
            fallbacks.add(t.getStreamFallback());
        }

        Instant from = d.at.minusMillis(500);
        Instant to = d.next.minusMillis(500);
        List<AtelierMessage> window = messages.window(from, to);
        AtelierMessage user = window.stream()
                .filter(m -> "USER".equals(m.getRole()) && "JARVIS".equals(m.getVia()) && messages.inThread(m))
                .findFirst().orElse(null);
        Instant delegatedTo = d.at.plus(DELEGATED_USER_WINDOW).isBefore(to) ? d.at.plus(DELEGATED_USER_WINDOW) : to;
        List<AtelierMessage> links = window.stream().filter(m -> "LINK".equals(m.getRole())).toList();
        if (user == null) {
            // La copie déléguée de la parole (dans le sujet cible) ne vaut que si une délégation de ce tour y mène : une
            // délégation en file d'attente peut écrire sa copie bien plus tard, pendant un autre tour.
            Set<UUID> targets = new HashSet<>();
            links.forEach(l -> targets.add(l.getRefWorkspaceId()));
            user = window.stream().filter(m -> "USER".equals(m.getRole()) && "JARVIS".equals(m.getVia())
                    && !messages.inThread(m) && targets.contains(m.getWorkspaceId())
                    && m.getCreatedAt().toInstant().isBefore(delegatedTo))
                    .findFirst().orElse(null);
        }
        AtelierMessage reply = window.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && "JARVIS".equals(m.getVia()) && messages.inThread(m))
                .findFirst().orElse(null);
        if (user != null) {
            messages.used.add(user);
        }
        if (reply != null) {
            messages.used.add(reply);
        }
        messages.used.addAll(links);
        List<Delegation> delegations = new ArrayList<>();
        for (AtelierMessage link : links) {
            AtelierMessage answer = delegationReply(link, messages);
            Matcher q = QUOTED.matcher(link.getContent());
            delegations.add(new Delegation(link.getCreatedAt(), subject(link.getRefWorkspaceId(), names),
                    link.getRefChantierNumber(), link.getLinkStatus(), q.find() ? cut(q.group(1)) : null,
                    answer == null ? null : cut(answer.getContent())));
        }

        JarvisAudioCapture c = d.capture;
        Heard heard = new Heard(chars, rejected, c == null ? null : cut(c.getStreamText()),
                c == null ? null : cut(c.getFinalText()), user == null ? null : cut(user.getContent()),
                c == null ? null : c.getId(), c == null ? null : c.getSeconds());
        Steps steps = t == null ? new Steps(null, null, null, null, null, null, null)
                : new Steps(t.getSpeechMs(), t.getEndSilenceMs(), t.getTranscriptMs(), t.getSentMs(),
                        t.getFirstTokenMs(), t.getFirstSoundMs(), t.getDoneMs());
        UUID screen = d.screenSubject != null ? d.screenSubject : t == null ? null : t.getWorkspaceId();
        return new Turn(number, d.turnId, d.pageId,
                d.turnId != null && d.exact ? Correlation.IDENTIFIANT : Correlation.ESTIMEE,
                OffsetDateTime.ofInstant(d.at, ZoneOffset.UTC),
                t != null ? t.getListenMode() : mode, heard, steps,
                t == null ? null : t.getBrain(), t == null ? null : t.getSpeculation(),
                t == null ? null : t.getDelegationReason(), t == null ? null : t.getEmptyDiagnostic(), said,
                reply == null ? null : cut(reply.getContent()), subject(screen, names), delegations, shows,
                fallbacks, streamError, t != null, List.of(), events);
    }

    /** La réponse d'une délégation : le message lié, sinon la première réponse de Jarvis dans le sujet cible. */
    static AtelierMessage delegationReply(AtelierMessage link, Messages messages) {
        if (link.getRefMessageId() != null) {
            AtelierMessage linked = messages.all.stream().filter(m -> link.getRefMessageId().equals(m.getId()))
                    .findFirst().orElse(null);
            if (linked != null) {
                return linked;
            }
        }
        if (link.getRefWorkspaceId() == null) {
            return null;
        }
        Instant from = link.getCreatedAt().toInstant();
        Instant to = from.plus(DELEGATION_REPLY_WINDOW);
        return messages.all.stream()
                .filter(m -> "ASSISTANT".equals(m.getRole()) && link.getRefWorkspaceId().equals(m.getWorkspaceId()))
                .filter(m -> within(m.getCreatedAt(), from, to))
                .findFirst().orElse(null);
    }

    static Subject subject(UUID id, Map<UUID, String> names) {
        return id == null ? null : new Subject(id, names.get(id));
    }

    private static Integer intOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? Integer.valueOf(m.group(1)) : null;
    }

    private static String strOf(Pattern p, String s) {
        Matcher m = p.matcher(s);
        return m.find() ? m.group(1) : null;
    }

    static String cut(String s) {
        if (s == null) {
            return null;
        }
        String v = s.strip();
        return v.length() <= MAX_TEXT ? v : v.substring(0, MAX_TEXT) + "…";
    }

    /** L'événement serveur de réception d'un tour (SF-255-01), pour mémoire. */
    static final String SERVER_CONVERSE = JarvisListenTraceService.SERVER_CONVERSE;
}
