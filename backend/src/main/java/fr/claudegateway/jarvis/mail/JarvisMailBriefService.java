package fr.claudegateway.jarvis.mail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * <b>Le bref du courrier et le bloc « Courrier » du cerveau rapide</b> (F-242 / SF-242-04, D8, D16).
 *
 * <p><b>Le bref</b> : à l'ouverture de {@code /jarvis}, s'il y a du neuf « à traiter » depuis le dernier bref, une phrase
 * de 30 mots au plus, le plus important d'abord, composée des <b>raisons du tri</b> (déjà rédigées par le cerveau
 * rapide, bornées à 8 mots) : <b>aucun appel</b> de plus, aucun appel à Opus. Les courriers dits sont marqués
 * {@code briefed_at}. En présentation : rien.</p>
 *
 * <p><b>Le bloc « Courrier »</b> : volatil, dans le <b>message</b> du cerveau rapide (jamais dans la consigne), il
 * liste les derniers courriers triés, <b>balisés non fiables</b>. En présentation, il dit seulement que le courrier est
 * indisponible.</p>
 */
@Service
public class JarvisMailBriefService {

    /** Le bref : la phrase et les courriers dont elle parle (le premier est « mis en avant »). */
    public record Brief(String text, List<BriefItem> items, String proposal) {
        static final Brief NONE = new Brief("", List.of(), "");

        public Brief(String text, List<BriefItem> items) {
            this(text, items, "");
        }
    }

    /** F-243 / SF-243-04 (D8) — le rendez-vous repéré à proposer, une phrase à la fois. Facultatif. */
    private JarvisMailCalendarHook calendar;

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setCalendar(JarvisMailCalendarHook calendar) {
        this.calendar = calendar;
    }

    public record BriefItem(UUID id, String from, String fromName, String subject, String reason) {
    }

    public static final int MAX_WORDS = 30;
    static final int MAX_NAMED = 3;
    static final int CONTEXT_ITEMS = 8;
    static final String PRESENTATION_BLOCK = "Indisponible en présentation : ne parle d'aucun courriel.";
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("dd/MM HH:mm", Locale.FRENCH);
    private static final String[] COUNT = {"Aucun", "Un", "Deux", "Trois", "Quatre", "Cinq", "Six", "Sept", "Huit",
            "Neuf", "Dix"};

    private final JarvisMailItemRepository items;
    private final JarvisMailItemService itemService;
    private final JarvisMailAccountRepository accounts;
    private final Clock clock;

    public JarvisMailBriefService(JarvisMailItemRepository items, JarvisMailItemService itemService,
            JarvisMailAccountRepository accounts, Clock clock) {
        this.items = items;
        this.itemService = itemService;
        this.accounts = accounts;
        this.clock = clock;
    }

    /** Le bref d'ouverture : vide s'il n'y a rien de neuf à traiter, ou en présentation. Marque ce qui est dit. */
    @Transactional
    public Brief brief(UUID userId, boolean presentation) {
        if (presentation || userId == null) {
            return Brief.NONE;
        }
        List<JarvisMailItem> fresh = items.findByUserIdAndTriageAndBriefedAtIsNullOrderByReceivedAtDesc(userId,
                JarvisMailItem.Triage.A_TRAITER, PageRequest.of(0, 20));
        String proposal = calendar == null ? "" : calendar.nextBriefSentence(userId);
        if (fresh.isEmpty()) {
            return proposal.isEmpty() ? Brief.NONE : new Brief("", List.of(), proposal);
        }
        List<BriefItem> said = new ArrayList<>();
        for (JarvisMailItem item : fresh) {
            MailHeaders h = itemService.headers(item);
            said.add(new BriefItem(item.getId(), h.from(), h.fromName(), h.subject(), item.getTriageReason()));
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        fresh.forEach(i -> i.setBriefedAt(now));
        items.saveAll(fresh);
        return new Brief(sentence(said), said.subList(0, Math.min(said.size(), MAX_NAMED)), proposal);
    }

    /** « Deux mails à traiter : Helios demande le devis signé, et la banque attend une signature. » (≤ 30 mots). */
    static String sentence(List<BriefItem> items) {
        int n = items.size();
        String head = (n < COUNT.length ? COUNT[n] : String.valueOf(n)) + (n == 1 ? " mail à traiter : " : " mails à traiter : ");
        for (int named = Math.min(n, MAX_NAMED); named >= 1; named--) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < named; i++) {
                parts.add(part(items.get(i)));
            }
            int rest = n - named;
            String body = join(parts, rest);
            String s = head + body + ".";
            if (words(s) <= MAX_WORDS) {
                return s;
            }
        }
        return clipWords(head + part(items.get(0)), MAX_WORDS) + ".";
    }

    private static String join(List<String> parts, int rest) {
        List<String> all = new ArrayList<>(parts);
        if (rest > 0) {
            all.add(rest == 1 ? "un autre" : rest + " autres");
        }
        if (all.size() == 1) {
            return all.get(0);
        }
        return String.join(", ", all.subList(0, all.size() - 1)) + ", et " + all.get(all.size() - 1);
    }

    /** La raison du tri si elle nomme l'expéditeur, sinon « Expéditeur : objet ». Texte seul, sans lien. */
    static String part(BriefItem item) {
        String name = clean(item.fromName().isBlank() ? localPart(item.from()) : item.fromName(), 40);
        String reason = clean(item.reason() == null ? "" : item.reason(), 80);
        if (!reason.isBlank() && !reason.equals(JarvisMailTriageParser.UNSURE)) {
            String first = name.split(" ")[0].toLowerCase(Locale.ROOT);
            return first.length() >= 3 && reason.toLowerCase(Locale.ROOT).contains(first) ? reason
                    : name + ", " + lowerFirst(reason);
        }
        return name + ", " + clean(item.subject(), 60);
    }

    /** Le bloc volatil du message du cerveau rapide ; vide sans boîte reliée. */
    @Transactional(readOnly = true)
    public String contextBlock(UUID userId, boolean presentation) {
        if (userId == null || accounts.countByUserId(userId) == 0) {
            return "";
        }
        if (presentation) {
            return "\n## Courrier\n" + PRESENTATION_BLOCK + "\n";
        }
        List<JarvisMailItem> recent = new ArrayList<>();
        recent.addAll(items.findByUserIdAndTriageOrderByReceivedAtDesc(userId, JarvisMailItem.Triage.A_TRAITER,
                PageRequest.of(0, CONTEXT_ITEMS)));
        if (recent.size() < CONTEXT_ITEMS) {
            recent.addAll(items.findByUserIdAndTriageOrderByReceivedAtDesc(userId, JarvisMailItem.Triage.A_LIRE,
                    PageRequest.of(0, CONTEXT_ITEMS - recent.size())));
        }
        StringBuilder lines = new StringBuilder();
        for (JarvisMailItem item : recent) {
            MailHeaders h = itemService.headers(item);
            lines.append("- courriel:").append(item.getId()).append(" — ")
                    .append(item.getTriage() == JarvisMailItem.Triage.A_TRAITER ? "à traiter" : "à lire")
                    .append(" — reçu ").append(item.getReceivedAt().atZoneSameInstant(ZONE).format(HOUR))
                    .append(" — de ").append(clean(h.fromName().isBlank() ? h.from() : h.fromName(), 40))
                    .append(" — objet : ").append(clean(h.subject(), 100))
                    .append(item.getTriageReason() == null ? "" : " — tri : " + clean(item.getTriageReason(), 60))
                    .append(rdvNote(userId, item))
                    .append('\n');
        }
        String body = lines.length() == 0 ? "Aucun courriel à traiter ni à lire." : lines.toString().strip();
        return "\n## Courrier (boîte de réception triée)\n" + UntrustedMail.NOTE + "\n" + UntrustedMail.tag(body) + "\n";
    }

    /** F-243 / SF-243-04 — ce que l'agenda a repéré dans ce courriel (« — invitation : … »), ou rien. */
    private String rdvNote(UUID userId, JarvisMailItem item) {
        if (calendar == null) {
            return "";
        }
        String note = calendar.noteFor(userId, item.getId());
        return note.isEmpty() ? "" : " — " + clean(note, 200);
    }

    private static String clean(String raw, int max) {
        String s = raw == null ? "" : raw.replaceAll("https?://\\S+", " ").replaceAll("[\\p{Cntrl}<>\"`]", " ")
                .replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, max).strip();
    }

    private static String localPart(String address) {
        return address == null ? "" : address.contains("@") ? address.substring(0, address.indexOf('@')) : address;
    }

    private static String lowerFirst(String s) {
        return s.isEmpty() ? s : Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    static int words(String s) {
        return s.isBlank() ? 0 : s.strip().split("\\s+").length;
    }

    private static String clipWords(String s, int max) {
        String[] w = s.strip().split("\\s+");
        return w.length <= max ? s.strip() : String.join(" ", java.util.Arrays.copyOf(w, max));
    }
}
