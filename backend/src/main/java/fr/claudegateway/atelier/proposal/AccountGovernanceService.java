package fr.claudegateway.atelier.proposal;

import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.atelier.promptsource.PromptSourceStore;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceHostScope;

/**
 * <b>Les règles de tous mes postes</b> (F-266 / SF-266-01) : des règles de gouvernance rangées côté
 * serveur pour un compte, qui valent à chaque tour de cet utilisateur, quel que soit le poste.
 *
 * <p><b>Préfixe stable</b> : {@link #promptBlock} ne dépend que des règles (triées par clé de titre) et
 * du texte des règles du poste/sujet déjà injectées. Rien de volatil (ni date, ni identifiant) : la
 * consigne ne change que quand une règle change. Un compte sans règle obtient une chaîne vide.</p>
 *
 * <p><b>Isolation</b> : toute lecture et toute écriture portent {@code user_id} ; une règle d'autrui est
 * introuvable. Le contenu des règles n'est jamais journalisé.</p>
 */
@Service
public class AccountGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(AccountGovernanceService.class);

    public static final int MAX_TITLE_CHARS = GovernanceProposalService.MAX_NAME_CHARS;
    public static final int MAX_CONTENT_CHARS = GovernanceProposalService.MAX_RULE_CHARS;
    /** Borne du bloc injecté, comme un {@code GOUVERNANCE.md}. */
    public static final int MAX_BLOCK_CHARS = 8_000;

    public static final String HEADER = "--- Règles du client — tous mes postes (portée compte) : elles valent "
            + "sur chaque poste ; une règle du poste ou du sujet de même titre les remplace ---\n";
    public static final String FOOTER = "--- Fin des règles de tous mes postes ---\n\n";
    static final String TRUNCATION = "\n… (règles de tous mes postes tronquées)\n";

    private final AccountGovernanceRuleRepository rules;
    private final GovernanceHostScope hostScope;
    private final GovernanceHostFiles hostFiles;

    public AccountGovernanceService(AccountGovernanceRuleRepository rules, GovernanceHostScope hostScope,
            GovernanceHostFiles hostFiles) {
        this.rules = rules;
        this.hostScope = hostScope;
        this.hostFiles = hostFiles;
    }

    // ------------------------------------------------------------------ lire

    @Transactional(readOnly = true)
    public List<AccountGovernanceRuleView> list(UUID userId) {
        return rules.findByUserIdOrderByTitleKeyAsc(userId).stream().map(AccountGovernanceRuleView::of).toList();
    }

    /** La règle de ce titre (casse ignorée), s'il y en a une. */
    @Transactional(readOnly = true)
    public Optional<AccountGovernanceRule> find(UUID userId, String title) {
        String key = titleKey(title);
        return key.isEmpty() ? Optional.empty() : rules.findByUserIdAndTitleKey(userId, key);
    }

    /**
     * Le bloc à injecter dans la consigne, ou {@code ""}. Les règles dont le titre figure en {@code ## titre}
     * dans {@code specificRules} (le texte injecté des règles du poste et du sujet) sont omises : la règle la
     * plus précise l'emporte. <b>Repli passant</b> : une panne rend un bloc vide, jamais un tour raté.
     */
    @Transactional(readOnly = true)
    public String promptBlock(UUID userId, List<String> specificRules) {
        try {
            return render(rules.findByUserIdOrderByTitleKeyAsc(userId), specificRules);
        } catch (RuntimeException ex) {
            log.warn("Règles de tous mes postes ignorées pour ce tour ({})", ex.getClass().getSimpleName());
            return "";
        }
    }

    // ------------------------------------------------------------------ écrire

    /** Crée ou remplace la règle de ce titre. */
    @Transactional
    public AccountGovernanceRuleView upsert(UUID userId, String rawTitle, String rawContent, UUID sourceHostId,
            UUID sourceProposalId) {
        String title = cleanTitle(rawTitle);
        String content = cleanContent(rawContent);
        OffsetDateTime now = OffsetDateTime.now();
        AccountGovernanceRule rule = rules.findByUserIdAndTitleKey(userId, titleKey(title))
                .orElseGet(() -> AccountGovernanceRule.builder().userId(userId).titleKey(titleKey(title))
                        .createdAt(now).build());
        rule.setTitle(title);
        rule.setContent(content);
        rule.setSourceHostId(sourceHostId);
        rule.setSourceProposalId(sourceProposalId);
        rule.setUpdatedAt(now);
        return AccountGovernanceRuleView.of(rules.save(rule));
    }

    @Transactional
    public void delete(UUID userId, UUID ruleId) {
        AccountGovernanceRule rule = rules.findByIdAndUserId(ruleId, userId)
                .orElseThrow(() -> new ProposalNotFoundException("Règle introuvable."));
        rules.delete(rule);
    }

    /**
     * « Valable sur tous mes postes » depuis la Forge : lit le {@code GOUVERNANCE.md} du poste, en extrait la
     * section {@code ## title} et la range en portée compte.
     */
    @Transactional
    public AccountGovernanceRuleView promoteFromHost(UUID userId, String hostRef, String rawTitle) {
        String title = cleanTitle(rawTitle);
        GovernanceHostRef host = hostScope.require(userId, hostRef);
        if (host.hosted()) {
            throw new InvalidProposalException("Ce poste n'a pas de machine : aucune règle de poste à recopier.");
        }
        GovernanceHostFiles.HostFileRead read = hostFiles.read(userId, host, PromptSourceStore.GOVERNANCE_FILE);
        if (read.presence() == GovernanceHostFiles.Presence.ABSENT) {
            throw new ProposalNotFoundException("Ce poste n'a pas de GOUVERNANCE.md.");
        }
        if (read.presence() != GovernanceHostFiles.Presence.PRESENT) {
            throw new ProposalUnreachableException("Le poste n'a pas pu lire GOUVERNANCE.md : vérifie qu'il est "
                    + "en ligne, puis réessaie.");
        }
        String body = section(read.contentOrEmpty(), title)
                .orElseThrow(() -> new ProposalNotFoundException("Règle « " + title + " » introuvable dans "
                        + "le GOUVERNANCE.md du poste."));
        return upsert(userId, title, body, host.hostId(), null);
    }

    // ------------------------------------------------------------------ pur

    /** {@code « Style  des Preuves »} → {@code style des preuves}. */
    public static String titleKey(String title) {
        return title == null ? "" : title.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    static String cleanTitle(String raw) {
        String title = raw == null ? "" : raw.strip().replaceAll("\\s+", " ");
        if (title.startsWith("## ")) {
            title = title.substring(3).strip();
        }
        if (title.isEmpty() || title.length() > MAX_TITLE_CHARS) {
            throw new InvalidProposalException("titre obligatoire, " + MAX_TITLE_CHARS + " caractères au plus.");
        }
        return title;
    }

    static String cleanContent(String raw) {
        String content = raw == null ? "" : raw.strip();
        if (content.isEmpty()) {
            throw new InvalidProposalException("contenu obligatoire : le texte de la règle.");
        }
        if (content.length() > MAX_CONTENT_CHARS) {
            throw new InvalidProposalException("contenu trop long (" + MAX_CONTENT_CHARS
                    + " caractères au plus) : resserre-le.");
        }
        return content;
    }

    /** Les clés des titres {@code ## …} d'un texte de règles. */
    static Set<String> headingKeys(List<String> texts) {
        Set<String> keys = new HashSet<>();
        if (texts == null) {
            return keys;
        }
        for (String text : texts) {
            if (text == null) {
                continue;
            }
            text.lines().map(String::strip).filter(line -> line.startsWith("## "))
                    .forEach(line -> keys.add(titleKey(line.substring(3))));
        }
        return keys;
    }

    /** Le corps de la section {@code ## title} d'un {@code GOUVERNANCE.md}, sans son titre ; vide si absente. */
    public static Optional<String> section(String content, String title) {
        if (content == null) {
            return Optional.empty();
        }
        String key = titleKey(title);
        List<String> lines = content.lines().toList();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.startsWith("## ") && titleKey(line.substring(3)).equals(key)) {
                StringBuilder body = new StringBuilder();
                for (int j = i + 1; j < lines.size(); j++) {
                    String next = lines.get(j);
                    if (next.startsWith("## ") || next.startsWith("# ")) {
                        break;
                    }
                    body.append(next).append('\n');
                }
                String out = body.toString().strip();
                return out.isEmpty() ? Optional.empty() : Optional.of(out);
            }
        }
        return Optional.empty();
    }

    /** Le bloc injecté : sections triées, celles du poste/sujet de même titre omises, borné. */
    static String render(List<AccountGovernanceRule> all, List<String> specificRules) {
        if (all == null || all.isEmpty()) {
            return "";
        }
        Set<String> shadowed = headingKeys(specificRules);
        StringBuilder body = new StringBuilder();
        for (AccountGovernanceRule rule : all) {
            if (shadowed.contains(rule.getTitleKey())) {
                continue;
            }
            body.append("## ").append(rule.getTitle()).append("\n\n").append(rule.getContent().strip())
                    .append("\n\n");
        }
        if (body.length() == 0) {
            return "";
        }
        String text = body.toString().strip();
        if (text.length() > MAX_BLOCK_CHARS) {
            text = text.substring(0, MAX_BLOCK_CHARS) + TRUNCATION;
        }
        return HEADER + text + "\n\n" + FOOTER;
    }
}
