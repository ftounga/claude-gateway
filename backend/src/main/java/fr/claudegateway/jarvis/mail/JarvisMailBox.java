package fr.claudegateway.jarvis.mail;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;

import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.UIDFolder;
import jakarta.mail.search.AndTerm;
import jakarta.mail.search.BodyTerm;
import jakarta.mail.search.ComparisonTerm;
import jakarta.mail.search.FromStringTerm;
import jakarta.mail.search.ReceivedDateTerm;
import jakarta.mail.search.SearchTerm;
import jakarta.mail.search.SubjectTerm;

/**
 * <b>Les gestes d'Opus sur une boîte</b> (F-242 / SF-242-05, SF-242-06) — résoudre une référence sous le {@code user_id}
 * du tour, lire un courrier, chercher. Tout passe par {@link JarvisMailServer} (relais IMAP) ; aucune lecture ne pose
 * le drapeau « lu » (dossier en lecture seule) ; jamais d'EXPUNGE.
 */
@Service
public class JarvisMailBox {

    /** Un résultat de recherche : sa référence et ses en-têtes (extrait borné). */
    public record Hit(String ref, String account, InboxReader.Fetched mail) {
    }

    /** Les critères d'une recherche (tous facultatifs, combinés par ET). */
    public record Criteria(String from, String subject, String text, LocalDate since, LocalDate until) {
        boolean empty() {
            return blank(from) && blank(subject) && blank(text) && since == null && until == null;
        }
    }

    public static final int MAX_HITS = 20;
    private static final ZoneId ZONE = ZoneId.of("Europe/Paris");

    private final JarvisMailAccountService accounts;
    private final JarvisMailAccountRepository accountRepository;
    private final JarvisMailItemService items;
    private final JarvisMailServer server;

    public JarvisMailBox(JarvisMailAccountService accounts, JarvisMailAccountRepository accountRepository,
            JarvisMailItemService items, JarvisMailServer server) {
        this.accounts = accounts;
        this.accountRepository = accountRepository;
        this.items = items;
        this.server = server;
    }

    /** Une référence du compte, boîte revérifiée ; 404 sinon. */
    public MailRef resolve(UUID userId, String raw) {
        UUID cached = MailRef.cachedId(raw);
        if (cached != null) {
            JarvisMailItem item = items.require(userId, cached);
            return new MailRef(item.getId(), item.getAccountId(), false, item.getUidvalidity(), item.getUid());
        }
        MailRef imap = MailRef.parseImap(raw);
        if (imap == null) {
            throw new JarvisMailExceptions.MailNotFoundException();
        }
        accounts.require(userId, imap.accountId());
        return imap;
    }

    /** La boîte désignée ({@code ""} : la première reliée ; sinon adresse ou libellé), sous le compte. */
    public JarvisMailAccount account(UUID userId, String designation) {
        List<JarvisMailAccount> all = accountRepository.findByUserIdOrderByCreatedAtAsc(userId);
        if (all.isEmpty()) {
            throw new JarvisMailExceptions.MailNotFoundException();
        }
        String d = designation == null ? "" : designation.strip().toLowerCase(Locale.ROOT);
        if (d.isEmpty()) {
            return all.get(0);
        }
        return all.stream().filter(a -> a.getAddress().equals(d) || a.getLabel().toLowerCase(Locale.ROOT).equals(d))
                .findFirst().orElseThrow(JarvisMailExceptions.MailNotFoundException::new);
    }

    /** Le libellé d'une boîte du compte. */
    public String label(UUID userId, UUID accountId) {
        JarvisMailAccount a = accounts.require(userId, accountId);
        return a.getLabel() + " (" + a.getAddress() + ")";
    }

    /** Lit un courrier : en-têtes et corps borné (texte seul). */
    public JarvisMailReader.Body read(UUID userId, MailRef ref) {
        JarvisMailAccount account = accounts.require(userId, ref.accountId());
        return server.withStore(account.getAddress(), accounts.secret(account), store -> {
            Folder folder = folder(store, ref.all());
            UIDFolder uids = (UIDFolder) folder;
            folder.open(Folder.READ_ONLY);
            try {
                Message m = uids.getUIDValidity() == ref.uidvalidity() ? uids.getMessageByUID(ref.uid()) : null;
                if (m == null) {
                    throw new JarvisMailExceptions.MailNotFoundException();
                }
                InboxReader.Fetched f = InboxReader.fetched(ref.uid(), m);
                String text = MailText.text(m, JarvisMailReader.BODY_MAX + 1);
                boolean truncated = text.length() > JarvisMailReader.BODY_MAX;
                JarvisMailItemService.ItemView view = new JarvisMailItemService.ItemView(ref.itemId(),
                        account.getId(), account.getLabel(), f.headers().from(), f.headers().fromName(),
                        f.headers().subject(), f.headers().snippet(), f.receivedAt(), null, null, f.hasCalendar(),
                        f.bulk(), f.flags());
                return new JarvisMailReader.Body(view, truncated ? text.substring(0, JarvisMailReader.BODY_MAX) : text,
                        truncated);
            } finally {
                folder.close(false);
            }
        });
    }

    /** Cherche dans « Tous les messages » (sinon la boîte de réception) ; 20 résultats au plus, les plus récents. */
    public List<Hit> search(UUID userId, String designation, Criteria criteria) {
        if (criteria == null || criteria.empty()) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Donne au moins un critère de recherche.");
        }
        JarvisMailAccount account = account(userId, designation);
        return server.withStore(account.getAddress(), accounts.secret(account), store -> {
            MailFolders folders = JarvisMailServer.folders(store);
            boolean all = folders.all() != null;
            Folder folder = all ? store.getFolder(folders.all()) : store.getFolder("INBOX");
            UIDFolder uids = (UIDFolder) folder;
            folder.open(Folder.READ_ONLY);
            try {
                Message[] found = folder.search(term(criteria));
                List<Message> sorted = new ArrayList<>(Arrays.asList(found));
                sorted.sort(Comparator.comparingLong((Message m) -> uid(uids, m)).reversed());
                List<Hit> hits = new ArrayList<>();
                long validity = uids.getUIDValidity();
                for (Message m : sorted.subList(0, Math.min(MAX_HITS, sorted.size()))) {
                    long uid = uids.getUID(m);
                    hits.add(new Hit(MailRef.imap(account.getId(), all, validity, uid), account.getLabel(),
                            InboxReader.fetched(uid, m)));
                }
                return hits;
            } finally {
                folder.close(false);
            }
        });
    }

    /** Le dossier d'une référence : boîte de réception, ou « Tous les messages » (SPECIAL-USE). */
    static Folder folder(Store store, boolean all) throws MessagingException {
        if (!all) {
            return store.getFolder("INBOX");
        }
        MailFolders folders = JarvisMailServer.folders(store);
        return store.getFolder(folders.all() != null ? folders.all() : "INBOX");
    }

    static SearchTerm term(Criteria c) {
        List<SearchTerm> terms = new ArrayList<>();
        if (!blank(c.from())) {
            terms.add(new FromStringTerm(c.from().strip()));
        }
        if (!blank(c.subject())) {
            terms.add(new SubjectTerm(c.subject().strip()));
        }
        if (!blank(c.text())) {
            terms.add(new BodyTerm(c.text().strip()));
        }
        if (c.since() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.GE, Date.from(c.since().atStartOfDay(ZONE).toInstant())));
        }
        if (c.until() != null) {
            terms.add(new ReceivedDateTerm(ComparisonTerm.LE, Date.from(c.until().atStartOfDay(ZONE).toInstant())));
        }
        return terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(SearchTerm[]::new));
    }

    private static long uid(UIDFolder uids, Message m) {
        try {
            return uids.getUID(m);
        } catch (MessagingException e) {
            return 0;
        }
    }

    static boolean blank(String s) {
        return s == null || s.isBlank();
    }
}
