package fr.claudegateway.jarvis.calendar;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import fr.claudegateway.jarvis.mail.JarvisMailCalendarHook;
import fr.claudegateway.jarvis.mail.JarvisMailPurge;
import fr.claudegateway.jarvis.mail.MailRdv;
import fr.claudegateway.jarvis.portrait.PortraitSeal;

/**
 * <b>Repérer les rendez-vous dans les mails</b> (F-243 / SF-243-04, D6, D7, D8, D12) — le courrier signale, l'agenda
 * propose ; <b>rien n'est jamais posé ici</b>.
 *
 * <ul>
 * <li>Invitation {@code .ics} : analyse <b>déterministe</b> (ical4j) ; les invités ({@code ATTENDEE}) ne sont
 * <b>jamais</b> lus ; un {@code UID} déjà présent dans un agenda lu ⇒ on ne propose pas de le poser (on propose
 * « accepter » pour l'agenda pro, et l'on renvoie à Gmail pour l'agenda perso).</li>
 * <li>Proposition écrite : seule une certitude haute dont le créneau se résout ({@link JarvisRdvResolver}) devient une
 * proposition ; sinon Jarvis demande le créneau.</li>
 * <li>Une proposition à la fois au bref ; elle expire au début du rendez-vous.</li>
 * </ul>
 */
@Service
public class JarvisCalendarProposalService implements JarvisMailCalendarHook, JarvisMailPurge {

    private static final Logger log = LoggerFactory.getLogger(JarvisCalendarProposalService.class);

    /** Ce qui est scellé d'une proposition (données non fiables). */
    public record Text(String title, String location, String from) {
    }

    static final java.time.Duration ASK_TTL = java.time.Duration.ofDays(7);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.FRENCH);

    private final JarvisCalendarProposalRepository proposals;
    private final JarvisCalendarEventRepository events;
    private final PortraitSeal seal;
    private final JarvisCalendarProperties properties;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper();

    public JarvisCalendarProposalService(JarvisCalendarProposalRepository proposals,
            JarvisCalendarEventRepository events, PortraitSeal seal, JarvisCalendarProperties properties, Clock clock) {
        this.proposals = proposals;
        this.events = events;
        this.seal = seal;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void onInvitation(UUID userId, UUID accountId, String accountAddress, UUID mailItemId, String fromName,
            byte[] ics, OffsetDateTime receivedAt) {
        Optional<JarvisIcsParser.Invitation> parsed = JarvisIcsParser.invitation(ics, properties.zoneId());
        if (parsed.isEmpty() || userId == null || mailItemId == null) {
            return;
        }
        JarvisIcsParser.Invitation inv = parsed.get();
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!inv.end().isAfter(now.toInstant()) && !"CANCEL".equals(inv.method())) {
            return; // déjà passée
        }
        JarvisCalendarProposal p = new JarvisCalendarProposal();
        p.setUserId(userId);
        p.setAccountId(accountId);
        p.setMailItemId(mailItemId);
        p.setPersonalBox(personal(accountAddress));
        p.setKind("CANCEL".equals(inv.method()) ? JarvisCalendarProposal.Kind.CANCELLATION
                : JarvisCalendarProposal.Kind.INVITATION);
        p.setIcalUid(inv.uid());
        p.setStartsAt(inv.start().atOffset(ZoneOffset.UTC));
        p.setEndsAt(inv.end().atOffset(ZoneOffset.UTC));
        p.setAllDay(inv.allDay());
        p.setInCalendar(events.existsByUserIdAndIcalUid(userId, inv.uid()));
        p.setSealed(seal(new Text(inv.text().title(), inv.text().location(), MailRdv.clean(fromName))));
        p.setStatus(JarvisCalendarProposal.Status.OPEN);
        p.setCreatedAt(now);
        p.setExpiresAt(inv.start().isAfter(now.toInstant()) ? inv.start().atOffset(ZoneOffset.UTC) : now.plusDays(1));
        proposals.save(p);
        log.debug("Agenda : invitation repérée pour l'utilisateur {}", userId);
    }

    @Override
    @Transactional
    public void onTextProposal(UUID userId, UUID accountId, UUID mailItemId, String fromName, MailRdv rdv,
            OffsetDateTime receivedAt) {
        if (rdv == null || userId == null || mailItemId == null) {
            return;
        }
        // Une invitation .ics du même courrier prime : pas de doublon.
        if (!proposals.findByUserIdAndMailItemIdOrderByCreatedAtDesc(userId, mailItemId).isEmpty()) {
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        ZoneId zone = properties.zoneId();
        Optional<JarvisRdvResolver.Slot> slot = JarvisRdvResolver.resolve(rdv, receivedAt, zone);
        JarvisCalendarProposal p = new JarvisCalendarProposal();
        p.setUserId(userId);
        p.setAccountId(accountId);
        p.setMailItemId(mailItemId);
        p.setPersonalBox(false);
        p.setSealed(seal(new Text(rdv.title(), rdv.location(), MailRdv.clean(fromName))));
        p.setStatus(JarvisCalendarProposal.Status.OPEN);
        p.setCreatedAt(now);
        if (slot.isPresent()) {
            OffsetDateTime start = slot.get().start().atZone(zone).toOffsetDateTime();
            if (!start.isAfter(now)) {
                return;
            }
            p.setKind(JarvisCalendarProposal.Kind.PROPOSAL);
            p.setStartsAt(start);
            p.setEndsAt(slot.get().end().atZone(zone).toOffsetDateTime());
            p.setExpiresAt(start);
        } else {
            p.setKind(JarvisCalendarProposal.Kind.ASK);
            p.setExpiresAt(now.plus(ASK_TTL));
        }
        proposals.save(p);
    }

    @Override
    @Transactional
    public String nextBriefSentence(UUID userId) {
        if (userId == null) {
            return "";
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<JarvisCalendarProposal> next = proposals.toSay(userId, now, PageRequest.of(0, 1));
        if (next.isEmpty()) {
            return "";
        }
        JarvisCalendarProposal p = next.get(0);
        p.setBriefedAt(now);
        p.setStatus(JarvisCalendarProposal.Status.SAID);
        proposals.save(p);
        return sentence(p, open(p));
    }

    @Override
    @Transactional(readOnly = true)
    public String noteFor(UUID userId, UUID mailItemId) {
        if (userId == null || mailItemId == null) {
            return "";
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        return proposals.findByUserIdAndMailItemIdOrderByCreatedAtDesc(userId, mailItemId).stream()
                .filter(p -> p.getExpiresAt().isAfter(now)).findFirst().map(p -> note(p, open(p))).orElse("");
    }

    /** Les propositions en attente qui touchent [from, to[ (vue d'agenda, en pointillé). */
    @Transactional(readOnly = true)
    public List<JarvisCalendarReader.Item> pending(UUID userId, OffsetDateTime from, OffsetDateTime to) {
        return proposals.pendingBetween(userId, from, to, OffsetDateTime.now(clock)).stream().map(p -> {
            Text t = open(p);
            return new JarvisCalendarReader.Item(p.getId(), "proposé", p.getIcalUid() == null ? "" : p.getIcalUid(),
                    p.getStartsAt().withOffsetSameInstant(ZoneOffset.UTC),
                    p.getEndsAt().withOffsetSameInstant(ZoneOffset.UTC), p.isAllDay(), t.title(), t.location(),
                    "PROPOSED");
        }).toList();
    }

    /** L'invitation d'un courrier, pour la réponse « accepter / refuser » (SF-243-04, D6). */
    @Transactional(readOnly = true)
    public Optional<JarvisCalendarProposal> invitation(UUID userId, UUID mailItemId) {
        return proposals.findFirstByUserIdAndMailItemIdAndKind(userId, mailItemId,
                JarvisCalendarProposal.Kind.INVITATION);
    }

    @Transactional
    public void markDone(UUID userId, UUID mailItemId) {
        proposals.findByUserIdAndMailItemIdOrderByCreatedAtDesc(userId, mailItemId).forEach(p -> {
            p.setStatus(JarvisCalendarProposal.Status.DONE);
            proposals.save(p);
        });
    }

    public Text open(JarvisCalendarProposal p) {
        try {
            return json.readValue(seal.open(p.getSealed()), Text.class);
        } catch (JsonProcessingException | RuntimeException e) {
            return new Text("Rendez-vous", "", "");
        }
    }

    @Override
    public void purgeAccount(UUID userId, UUID accountId) {
        proposals.purgeAccount(userId, accountId);
    }

    @Override
    public void purgeUser(UUID userId) {
        proposals.purgeUser(userId);
    }

    // ------------------------------------------------------------------------------------------------------------

    /** La phrase du bref (≈ 25 mots), une proposition à la fois. */
    String sentence(JarvisCalendarProposal p, Text t) {
        String from = t.from().isBlank() ? "Un correspondant" : t.from();
        String title = t.title().isBlank() ? "rendez-vous" : t.title();
        return switch (p.getKind()) {
            case PROPOSAL -> from + " propose " + when(p) + " : « " + title + " ». Je le prépare pour votre agenda ?";
            case INVITATION -> p.isInCalendar()
                    ? (p.isPersonalBox()
                            ? "L'invitation de " + from + " pour " + day(p) + " est dans votre agenda perso ; la réponse "
                                    + "se fait depuis Gmail."
                            : "L'invitation de " + from + " pour " + day(p) + " est déjà dans votre agenda. Je "
                                    + "l'accepte ?")
                    : from + " vous invite " + when(p) + " : « " + title + " ». Je le prépare pour votre agenda ?";
            case CANCELLATION -> from + " annule « " + title + " » du " + day(p) + ".";
            case ASK -> from + " propose de se voir, sans créneau précis. Quel créneau lui proposer ?";
        };
    }

    /** La ligne d'un courriel (bloc Courrier, lecture d'Opus). */
    String note(JarvisCalendarProposal p, Text t) {
        String title = t.title().isBlank() ? "rendez-vous" : t.title();
        return switch (p.getKind()) {
            case PROPOSAL -> "rendez-vous proposé (date écrite) : " + iso(p) + " « " + title + " » — pas dans l'agenda";
            case INVITATION -> "invitation : " + iso(p) + " « " + title + " » — "
                    + (p.isInCalendar() ? (p.isPersonalBox() ? "déjà dans l'agenda perso (réponse depuis Gmail)"
                            : "déjà dans l'agenda pro (à accepter ou refuser)")
                            : "pas dans l'agenda");
            case CANCELLATION -> "invitation annulée : « " + title + " »";
            case ASK -> "rendez-vous évoqué sans créneau précis : demander le créneau, ne rien deviner";
        };
    }

    private String when(JarvisCalendarProposal p) {
        if (p.isAllDay()) {
            return day(p);
        }
        ZoneId zone = properties.zoneId();
        java.time.ZonedDateTime s = p.getStartsAt().atZoneSameInstant(zone);
        return day(p) + " à " + (s.getMinute() == 0 ? s.getHour() + " h" : s.getHour() + " h " + String.format("%02d",
                s.getMinute()));
    }

    private String day(JarvisCalendarProposal p) {
        return p.getStartsAt() == null ? "" : DAY.format(p.getStartsAt().atZoneSameInstant(properties.zoneId()));
    }

    private String iso(JarvisCalendarProposal p) {
        ZoneId zone = properties.zoneId();
        DateTimeFormatter f = DateTimeFormatter.ofPattern("EEEE d MMMM HH:mm", Locale.FRENCH);
        return f.format(p.getStartsAt().atZoneSameInstant(zone)) + "–"
                + DateTimeFormatter.ofPattern("HH:mm").format(p.getEndsAt().atZoneSameInstant(zone));
    }

    static boolean personal(String address) {
        String a = address == null ? "" : address.toLowerCase(Locale.ROOT);
        return a.endsWith("@gmail.com") || a.endsWith("@googlemail.com");
    }

    private String seal(Text t) {
        try {
            return seal.seal(json.writeValueAsString(t));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }
}
