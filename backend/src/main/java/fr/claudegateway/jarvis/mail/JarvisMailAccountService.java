package fr.claudegateway.jarvis.mail;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.byok.ByokKeyCipher;
import fr.claudegateway.byok.EncryptedKey;

/**
 * <b>Relier, tester, révoquer une boîte</b> (F-242 / SF-242-01, D1, D2, D3, D18).
 *
 * <p><b>Ordre volontaire</b> (patron {@code GitTokenService}) : rien n'est écrit tant que Google n'a pas accepté le mot
 * de passe en IMAP <b>et</b> en SMTP. Le mot de passe en clair n'est jamais journalisé, jamais renvoyé : seuls
 * l'adresse, l'état et {@code …last4} sortent du service.</p>
 *
 * <p><b>Isolation multi-tenant</b> : chaque geste porte sur le {@code userId} du principal ; une boîte d'autrui est
 * introuvable (404).</p>
 */
@Service
public class JarvisMailAccountService {

    private static final Logger log = LoggerFactory.getLogger(JarvisMailAccountService.class);

    private static final Pattern ADDRESS = Pattern.compile("^[a-z0-9._%+\\-]+@[a-z0-9.\\-]+\\.[a-z]{2,}$");
    private static final int MAX_ADDRESS = 254;
    private static final int MAX_LABEL = 40;
    private static final int MAX_SECRET = 64;

    /** La vue d'une boîte : jamais le secret. */
    public record View(UUID id, String address, String label, String provider, String status, String secretLast4,
            OffsetDateTime lastOkAt, String lastError, OffsetDateTime createdAt, MailFolders folders) {
    }

    private final JarvisMailAccountRepository repository;
    private final ByokKeyCipher cipher;
    private final JarvisMailServer server;
    private final JarvisMailProperties properties;
    private final Clock clock;
    private final ObjectProvider<JarvisMailPurge> purges;

    public JarvisMailAccountService(JarvisMailAccountRepository repository, ByokKeyCipher cipher,
            JarvisMailServer server, JarvisMailProperties properties, Clock clock, ObjectProvider<JarvisMailPurge> purges) {
        this.repository = repository;
        this.cipher = cipher;
        this.server = server;
        this.properties = properties;
        this.clock = clock;
        this.purges = purges;
    }

    @Transactional(readOnly = true)
    public List<View> list(UUID userId) {
        return repository.findByUserIdOrderByCreatedAtAsc(userId).stream().map(a -> view(a, null)).toList();
    }

    /**
     * Relie une boîte : validation, test IMAP + SMTP, <b>puis</b> chiffrement et écriture. Une adresse déjà reliée
     * voit son secret remplacé (réparation d'un {@code AUTH_FAILED}).
     */
    @Transactional
    public View link(UUID userId, String rawAddress, String rawLabel, String rawPassword) {
        String address = normalizeAddress(rawAddress);
        String label = normalizeLabel(rawLabel, address);
        String password = normalizeSecret(rawPassword);
        JarvisMailAccount existing = repository.findByUserIdAndAddress(userId, address).orElse(null);
        if (existing == null && repository.countByUserId(userId) >= properties.maxAccounts()) {
            throw new JarvisMailExceptions.MailAccountsFullException(properties.maxAccounts());
        }

        // Vérification AVANT toute écriture : un mot de passe refusé laisse l'état précédent intact.
        MailFolders folders = server.verify(address, password);

        EncryptedKey encrypted = cipher.encrypt(password);
        OffsetDateTime now = OffsetDateTime.now(clock);
        JarvisMailAccount account = existing != null ? existing : new JarvisMailAccount();
        if (existing == null) {
            account.setUserId(userId);
            account.setAddress(address);
            account.setCreatedAt(now);
            account.setNextPollAt(now);
        }
        account.setLabel(label);
        account.setProvider(JarvisMailAccount.Provider.GOOGLE);
        account.setEncryptedDataKey(encrypted.encryptedDataKey());
        account.setCipherIv(encrypted.iv());
        account.setCiphertext(encrypted.ciphertext());
        account.setSecretLast4(last4(password));
        markOk(account, now);
        JarvisMailAccount saved = repository.save(account);
        log.info("Courrier : boîte reliée pour l'utilisateur {}", userId);
        return view(saved, folders);
    }

    /** Refait le test IMAP + SMTP d'une boîte et en inscrit le résultat. */
    @Transactional
    public View test(UUID userId, UUID accountId) {
        JarvisMailAccount account = require(userId, accountId);
        OffsetDateTime now = OffsetDateTime.now(clock);
        MailFolders folders = null;
        try {
            folders = server.verify(account.getAddress(), secret(account));
            markOk(account, now);
        } catch (JarvisMailExceptions.MailAuthException e) {
            account.setStatus(JarvisMailAccount.Status.AUTH_FAILED);
            account.setLastError("Mot de passe d'application refusé par Google.");
        } catch (JarvisMailExceptions.MailUnavailableException e) {
            account.setLastError("La boîte ne répond pas.");
        }
        return view(repository.save(account), folders);
    }

    /** Révoque : la boîte et tout ce qui en dépend (courriers en cache, actions) sont effacés. */
    @Transactional
    public void revoke(UUID userId, UUID accountId) {
        JarvisMailAccount account = require(userId, accountId);
        purges.orderedStream().forEach(purge -> purge.purgeAccount(userId, account.getId()));
        repository.delete(account);
        log.info("Courrier : boîte révoquée pour l'utilisateur {}", userId);
    }

    /** Purge du compte (suppression de l'utilisateur). */
    @Transactional
    public void purgeUser(UUID userId) {
        purges.orderedStream().forEach(purge -> purge.purgeUser(userId));
        repository.purgeUser(userId);
    }

    /** La boîte du compte, ou 404. */
    @Transactional(readOnly = true)
    public JarvisMailAccount require(UUID userId, UUID accountId) {
        if (userId == null || accountId == null) {
            throw new JarvisMailExceptions.MailNotFoundException();
        }
        return repository.findByIdAndUserId(accountId, userId)
                .orElseThrow(JarvisMailExceptions.MailNotFoundException::new);
    }

    /** Le mot de passe en clair, pour un usage interne immédiat (jamais persisté, jamais journalisé). */
    public String secret(JarvisMailAccount account) {
        return cipher.decrypt(new EncryptedKey(account.getEncryptedDataKey(), account.getCipherIv(),
                account.getCiphertext()));
    }

    private static void markOk(JarvisMailAccount account, OffsetDateTime now) {
        account.setStatus(JarvisMailAccount.Status.OK);
        account.setLastOkAt(now);
        account.setLastError(null);
        account.setAuthFailures(0);
    }

    static String normalizeAddress(String raw) {
        String address = raw == null ? "" : raw.strip().toLowerCase(Locale.ROOT);
        if (address.isEmpty() || address.length() > MAX_ADDRESS || !ADDRESS.matcher(address).matches()) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Adresse e-mail invalide.");
        }
        return address;
    }

    static String normalizeLabel(String raw, String address) {
        String label = raw == null ? "" : raw.strip();
        if (label.length() > MAX_LABEL) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Libellé trop long (40 caractères au plus).");
        }
        if (label.isEmpty()) {
            return address.endsWith("@gmail.com") || address.endsWith("@googlemail.com") ? "Perso" : "Pro";
        }
        return label;
    }

    /** Google affiche le mot de passe d'application par groupes de 4 : les espaces sont retirés. */
    static String normalizeSecret(String raw) {
        String secret = raw == null ? "" : raw.replaceAll("\\s+", "");
        if (secret.isEmpty() || secret.length() > MAX_SECRET) {
            throw new JarvisMailExceptions.InvalidMailAccountException("Mot de passe d'application invalide.");
        }
        return secret;
    }

    private static String last4(String secret) {
        return secret.length() <= 4 ? secret : secret.substring(secret.length() - 4);
    }

    private static View view(JarvisMailAccount a, MailFolders folders) {
        return new View(a.getId(), a.getAddress(), a.getLabel(), a.getProvider().name(), a.getStatus().name(),
                a.getSecretLast4(), a.getLastOkAt(), a.getLastError(), a.getCreatedAt(), folders);
    }
}
