package fr.claudegateway.jarvis.calendar;

import java.io.ByteArrayInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.time.temporal.Temporal;
import java.time.temporal.TemporalAmount;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import net.fortuna.ical4j.data.CalendarBuilder;
import net.fortuna.ical4j.model.Calendar;
import net.fortuna.ical4j.model.Component;
import net.fortuna.ical4j.model.Property;
import net.fortuna.ical4j.model.Recur;
import net.fortuna.ical4j.model.TimeZone;
import net.fortuna.ical4j.model.TimeZoneRegistry;
import net.fortuna.ical4j.model.TimeZoneRegistryFactory;
import net.fortuna.ical4j.model.component.VEvent;
import net.fortuna.ical4j.model.property.DateListProperty;
import net.fortuna.ical4j.model.property.DateProperty;
import net.fortuna.ical4j.model.property.RRule;

/**
 * <b>L'analyse d'un agenda iCal</b> (F-243 / SF-243-01, D2) — <b>déterministe, sans IA</b>, avec ical4j : événements
 * simples, journées entières, récurrences ({@code RRULE}), exceptions ({@code EXDATE}), occurrences modifiées
 * ({@code RECURRENCE-ID}), fuseaux ({@code TZID} et {@code VTIMEZONE}), annulations ({@code STATUS:CANCELLED}).
 *
 * <p>Seules les occurrences qui touchent la fenêtre demandée sortent (J-1 → J+60 pour la relève). Les heures
 * « flottantes » et les journées entières sont lues dans le fuseau de l'agenda ({@code X-WR-TIMEZONE}) ou, à défaut,
 * dans le fuseau par défaut (Europe/Paris). Le texte (titre, lieu, description) est une <b>donnée non fiable</b> (D12),
 * bornée par {@link EventText}.</p>
 */
public final class JarvisIcsParser {

    /** Au-delà, la fenêtre est tronquée (un agenda pathologique ne remplit pas la base). */
    static final int MAX_OCCURRENCES = 2000;

    /** Une occurrence dans la fenêtre. */
    public record Occurrence(String uid, String recurrenceId, Instant start, Instant end, boolean allDay,
            String status, EventText text) {
    }

    /** Le résultat d'une analyse. */
    public record Parsed(List<Occurrence> occurrences, boolean truncated, String method) {
    }

    /** Le corps n'est pas un agenda iCal lisible. */
    public static class InvalidCalendarException extends RuntimeException {
        public InvalidCalendarException() {
            super("agenda illisible");
        }
    }

    private JarvisIcsParser() {
    }

    /**
     * Analyse {@code body} et rend les occurrences qui touchent [{@code from}, {@code to}[, triées par début.
     *
     * @throws InvalidCalendarException si le corps n'est pas un {@code VCALENDAR}
     */
    public static Parsed parse(byte[] body, Instant from, Instant to, ZoneId fallbackZone) {
        Calendar calendar = build(body, fallbackZone);
        ZoneId zone = calendarZone(calendar).orElse(fallbackZone);
        String method = calendar.getProperty(Property.METHOD).map(Property::getValue)
                .map(v -> v.strip().toUpperCase(Locale.ROOT)).orElse(null);
        List<VEvent> events = calendar.getComponents(Component.VEVENT);

        Map<String, Map<Instant, VEvent>> overrides = new HashMap<>();
        List<VEvent> masters = new ArrayList<>();
        for (VEvent event : events) {
            Optional<Instant> recurrenceId = recurrenceId(event, zone);
            if (recurrenceId.isPresent()) {
                overrides.computeIfAbsent(uid(event), k -> new HashMap<>()).put(recurrenceId.get(), event);
            } else {
                masters.add(event);
            }
        }

        List<Occurrence> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        boolean truncated = false;
        for (VEvent master : masters) {
            try {
                truncated |= expand(master, overrides.getOrDefault(uid(master), Map.of()), from, to, zone, out, seen);
            } catch (RuntimeException e) {
                // Un événement mal formé ne prive pas l'agenda du reste.
            }
            if (out.size() >= MAX_OCCURRENCES) {
                truncated = true;
                break;
            }
        }
        // Les occurrences modifiées : à leur nouvelle place, si elles touchent la fenêtre.
        for (Map.Entry<String, Map<Instant, VEvent>> byUid : overrides.entrySet()) {
            for (Map.Entry<Instant, VEvent> o : byUid.getValue().entrySet()) {
                if (out.size() >= MAX_OCCURRENCES) {
                    truncated = true;
                    break;
                }
                try {
                    single(o.getValue(), byUid.getKey(), o.getKey().toString(), from, to, zone, out, seen);
                } catch (RuntimeException e) {
                    // idem
                }
            }
        }
        out.sort(Comparator.comparing(Occurrence::start).thenComparing(Occurrence::end)
                .thenComparing(Occurrence::uid));
        List<Occurrence> bounded = out.size() > MAX_OCCURRENCES ? List.copyOf(out.subList(0, MAX_OCCURRENCES)) : out;
        return new Parsed(List.copyOf(bounded), truncated, method);
    }

    /** SHA-256 hexadécimal (empreinte d'un corps, d'une adresse). */
    public static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------------------------------------------------

    private static Calendar build(byte[] body, ZoneId fallbackZone) {
        if (body == null || body.length == 0) {
            throw new InvalidCalendarException();
        }
        try {
            TimeZoneRegistry registry = new ZoneFallbackRegistry(
                    TimeZoneRegistryFactory.getInstance().createRegistry(), fallbackZone);
            return new CalendarBuilder(registry).build(new InputStreamReader(new ByteArrayInputStream(body),
                    StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new InvalidCalendarException();
        }
    }

    private static Optional<ZoneId> calendarZone(Calendar calendar) {
        return calendar.getProperty("X-WR-TIMEZONE").map(Property::getValue).flatMap(v -> {
            try {
                return Optional.of(ZoneId.of(v.strip()));
            } catch (DateTimeException e) {
                return Optional.empty();
            }
        });
    }

    /** Déploie un événement (simple ou récurrent) ; rend vrai si sa récurrence a été tronquée. */
    private static boolean expand(VEvent event, Map<Instant, VEvent> overrides, Instant from, Instant to, ZoneId zone,
            List<Occurrence> out, Set<String> seen) {
        if (cancelled(event)) {
            return false;
        }
        Optional<RRule<Temporal>> rrule = event.getProperty(Property.RRULE);
        if (rrule.isEmpty()) {
            single(event, uid(event), null, from, to, zone, out, seen);
            return false;
        }
        Temporal seed = start(event).orElseThrow();
        boolean allDay = seed instanceof LocalDate;
        Span span = span(event, seed, zone);
        Set<Instant> excluded = new HashSet<>();
        List<DateListProperty<Temporal>> exdates = event.getProperties(Property.EXDATE);
        for (DateListProperty<Temporal> ex : exdates) {
            for (Temporal t : dates(ex)) {
                excluded.add(instant(t, zone));
            }
        }
        Instant rangeStart = from.minus(span.exact()).minus(span.days(), ChronoUnit.DAYS).minusSeconds(1);
        Recur<Temporal> recur = rrule.get().getRecur();
        LinkedHashSet<Temporal> dates = new LinkedHashSet<>(
                recur.getDates(seed, like(seed, rangeStart, zone), like(seed, to, zone), MAX_OCCURRENCES + 1));
        Instant seedInstant = instant(seed, zone);
        if (!seedInstant.isBefore(rangeStart) && seedInstant.isBefore(to)) {
            dates.add(seed); // RFC 5545 : DTSTART est toujours la première occurrence.
        }
        boolean truncated = dates.size() > MAX_OCCURRENCES;
        String uid = uid(event);
        EventText text = text(event);
        String status = status(event);
        for (Temporal date : dates) {
            Instant original = instant(date, zone);
            if (excluded.contains(original) || overrides.containsKey(original)) {
                continue;
            }
            Instant start = original;
            Instant end = end(date, span, zone);
            add(out, seen, new Occurrence(uid, original.toString(), start, end, allDay, status, text), from, to);
        }
        return truncated;
    }

    private static void single(VEvent event, String uid, String recurrenceId, Instant from, Instant to, ZoneId zone,
            List<Occurrence> out, Set<String> seen) {
        if (cancelled(event)) {
            return;
        }
        Temporal seed = start(event).orElseThrow();
        Span span = span(event, seed, zone);
        add(out, seen, new Occurrence(uid, recurrenceId, instant(seed, zone), end(seed, span, zone),
                seed instanceof LocalDate, status(event), text(event)), from, to);
    }

    private static void add(List<Occurrence> out, Set<String> seen, Occurrence o, Instant from, Instant to) {
        boolean touches = o.start().isBefore(to)
                && (o.end().isAfter(from) || (o.end().equals(o.start()) && !o.start().isBefore(from)));
        if (touches && seen.add(o.uid() + "|" + o.recurrenceId() + "|" + o.start())) {
            out.add(o);
        }
    }

    /** La durée d'un événement : exacte pour une heure, en jours pour une journée entière. */
    private record Span(Duration exact, long days) {
    }

    private static Span span(VEvent event, Temporal seed, ZoneId zone) {
        Optional<DateProperty<Temporal>> dtend = event.getProperty(Property.DTEND);
        Temporal end = dtend.map(JarvisIcsParser::date).orElse(null);
        if (end != null) {
            if (seed instanceof LocalDate s && end instanceof LocalDate e) {
                return new Span(Duration.ZERO, Math.max(1, ChronoUnit.DAYS.between(s, e)));
            }
            Duration d = Duration.between(instant(seed, zone), instant(end, zone));
            return new Span(d.isNegative() ? Duration.ZERO : d, 0);
        }
        Optional<net.fortuna.ical4j.model.property.Duration> duration = event.getProperty(Property.DURATION);
        if (duration.isPresent() && duration.get().getDuration() != null) {
            TemporalAmount amount = duration.get().getDuration();
            if (seed instanceof LocalDate s) {
                long days = ChronoUnit.DAYS.between(s, s.plus(amount));
                return new Span(Duration.ZERO, Math.max(1, days));
            }
            Instant start = instant(seed, zone);
            Duration d = Duration.between(start, start.atZone(zone).plus(amount).toInstant());
            return new Span(d.isNegative() ? Duration.ZERO : d, 0);
        }
        return seed instanceof LocalDate ? new Span(Duration.ZERO, 1) : new Span(Duration.ZERO, 0);
    }

    private static Instant end(Temporal start, Span span, ZoneId zone) {
        if (start instanceof LocalDate d) {
            return d.plusDays(span.days()).atStartOfDay(zone).toInstant();
        }
        return instant(start, zone).plus(span.exact());
    }

    private static Optional<Temporal> start(VEvent event) {
        Optional<DateProperty<Temporal>> dtstart = event.getProperty(Property.DTSTART);
        return dtstart.map(JarvisIcsParser::date);
    }

    private static Optional<Instant> recurrenceId(VEvent event, ZoneId zone) {
        Optional<DateProperty<Temporal>> rid = event.getProperty(Property.RECURRENCE_ID);
        return rid.map(JarvisIcsParser::date).map(t -> instant(t, zone));
    }

    /** La date d'une propriété (les {@code TZID} sont résolus par {@link ZoneFallbackRegistry}). */
    static Temporal date(DateProperty<Temporal> property) {
        return property.getDate();
    }

    private static List<Temporal> dates(DateListProperty<Temporal> property) {
        return property.getDates();
    }

    private static boolean cancelled(VEvent event) {
        return "CANCELLED".equals(status(event));
    }

    private static String status(VEvent event) {
        String s = event.getProperty(Property.STATUS).map(Property::getValue).map(v -> v.strip().toUpperCase(Locale.ROOT))
                .orElse("CONFIRMED");
        return switch (s) {
            case "CANCELLED", "TENTATIVE" -> s;
            default -> "CONFIRMED";
        };
    }

    static String uid(VEvent event) {
        Optional<String> uid = event.getProperty(Property.UID).map(Property::getValue).map(String::strip)
                .filter(s -> !s.isEmpty());
        if (uid.isPresent()) {
            String u = uid.get();
            return u.length() <= 512 ? u : u.substring(0, 512);
        }
        String basis = start(event).map(Object::toString).orElse("") + "|" + value(event, Property.SUMMARY);
        return "sans-uid-" + sha256(basis.getBytes(StandardCharsets.UTF_8)).substring(0, 32);
    }

    private static EventText text(VEvent event) {
        return new EventText(value(event, Property.SUMMARY), value(event, Property.LOCATION),
                value(event, Property.DESCRIPTION));
    }

    private static String value(VEvent event, String name) {
        return event.getProperty(name).map(Property::getValue).orElse("");
    }

    /**
     * Le registre de fuseaux d'ical4j ne connaît que les {@code VTIMEZONE} du flux : un {@code TZID} nu
     * (« America/New_York », fréquent dans une invitation) y est inconnu. Repli sur {@code java.time}, puis, pour un nom
     * que {@code java.time} ignore (fuseau Windows d'Outlook, « Romance Standard Time »), sur le fuseau par défaut.
     */
    private record ZoneFallbackRegistry(TimeZoneRegistry delegate, ZoneId fallback) implements TimeZoneRegistry {
        @Override
        public void register(TimeZone timezone) {
            delegate.register(timezone);
        }

        @Override
        public void register(TimeZone timezone, boolean update) {
            delegate.register(timezone, update);
        }

        @Override
        public void clear() {
            delegate.clear();
        }

        @Override
        public TimeZone getTimeZone(String id) {
            return delegate.getTimeZone(id);
        }

        @Override
        public Map<String, java.time.zone.ZoneRules> getZoneRules() {
            return delegate.getZoneRules();
        }

        @Override
        public ZoneId getZoneId(String tzId) {
            try {
                return delegate.getZoneId(tzId);
            } catch (DateTimeException e) {
                try {
                    return ZoneId.of(tzId == null ? "" : tzId.strip());
                } catch (DateTimeException unknown) {
                    return fallback;
                }
            }
        }

        @Override
        public String getTzId(String zoneId) {
            return delegate.getTzId(zoneId);
        }
    }

    /** Un instant pour n'importe quel temporel d'ical4j. */
    static Instant instant(Temporal t, ZoneId zone) {
        if (t instanceof Instant i) {
            return i;
        }
        if (t instanceof ZonedDateTime z) {
            return z.toInstant();
        }
        if (t instanceof OffsetDateTime o) {
            return o.toInstant();
        }
        if (t instanceof LocalDateTime l) {
            return l.atZone(zone).toInstant();
        }
        if (t instanceof LocalDate d) {
            return d.atStartOfDay(zone).toInstant();
        }
        throw new DateTimeException("temporel inconnu : " + t.getClass().getSimpleName());
    }

    /** {@code i} exprimé dans le même type que {@code seed} (la récurrence se calcule dans le type de son départ). */
    private static Temporal like(Temporal seed, Instant i, ZoneId zone) {
        if (seed instanceof Instant) {
            return i;
        }
        if (seed instanceof ZonedDateTime z) {
            return i.atZone(z.getZone());
        }
        if (seed instanceof OffsetDateTime o) {
            return i.atOffset(o.getOffset());
        }
        if (seed instanceof LocalDateTime) {
            return LocalDateTime.ofInstant(i, zone);
        }
        if (seed instanceof LocalDate) {
            return LocalDate.ofInstant(i, zone);
        }
        throw new DateTimeException("temporel inconnu : " + seed.getClass().getSimpleName());
    }
}
