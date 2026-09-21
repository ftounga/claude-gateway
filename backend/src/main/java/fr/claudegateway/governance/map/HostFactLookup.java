package fr.claudegateway.governance.map;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <b>Les faits qui répondent à la question</b> (F-137 / SF-137-01).
 *
 * <p>F-136 dit à l'agent <b>où</b> chercher ; il devait encore ouvrir le fichier, donc dépenser un
 * tour de boucle. Ici, les faits utiles arrivent <b>avec</b> la question.</p>
 *
 * <p><b>Aucun index persistant, et c'est délibéré.</b> Le contenu des fichiers de carte est déjà en
 * base (SF-136-01) : la recherche se fait sur quelques centaines de kilo-octets déjà rangés, en
 * quelques millisecondes. Un second stockage à tenir synchronisé avec le premier finit toujours par
 * en diverger — ne pas le créer supprime la classe entière de défauts. Point de bascule assumé :
 * au-delà de ~20 000 faits par poste, il faudra un index.</p>
 *
 * <p><b>Ce qui fait un terme distinctif.</b> Un nom propre d'infrastructure ne ressemble pas à un
 * mot de la langue : il porte un point, un tiret, un chiffre, une majuscule interne, ou il est tout
 * en capitales. « bastion » ou « serveur » ne qualifient pas — ils ramèneraient toute la carte et
 * noieraient la réponse.</p>
 */
public final class HostFactLookup {

    /** Longueur minimale d'un terme : en deçà, tout ressemble à tout. */
    static final int MIN_TERM_LENGTH = 3;

    /** Faits joints au plus : au-delà, ce n'est plus un rappel, c'est la carte. */
    static final int MAX_FACTS = 12;

    /** Borne du bloc entier. */
    static final int MAX_CHARS = 3_000;

    /** Longueur maximale d'un fait repris : une ligne de carte, pas un chapitre. */
    static final int MAX_FACT_CHARS = 300;

    /**
     * Un terme présent dans trop de faits ne distingue rien : il décrit la carte, pas la question.
     * Le seuil vaut par fichier — au-delà, on l'ignore plutôt que de tout remonter.
     */
    static final int MAX_MATCHES_PER_TERM = 6;

    /**
     * La date de constat d'un fait, telle que la règle du paquet l'impose : {@code constaté le
     * AAAA-MM-JJ}. Accentuée ou non — on lit ce qui est écrit, pas ce qu'on aurait voulu.
     */
    static final Pattern CONSTAT_DATE =
            Pattern.compile("constat[ée]e?\\s+le\\s+(\\d{4}-\\d{2}-\\d{2})",
                    Pattern.CASE_INSENSITIVE);

    /** Ce qui marque un fait qui a vieilli (F-139 / SF-139-01). */
    static final String STALE_MARK = "  ⟨à re-vérifier⟩";

    /** La consigne, ajoutée seulement s'il y a quelque chose à re-vérifier. */
    static final String STALE_NOTICE =
            "Ce qui est marqué « à re-vérifier » a dépassé l'âge où un fait d'infrastructure reste "
                    + "sûr : vérifie-le avant de l'affirmer.\n";

    static final String HEADER =
            "Ce que la carte de ce client dit déjà des éléments que tu viens de citer "
                    + "(vérifie si besoin, mais ne le redécouvre pas) :\n";

    private HostFactLookup() {
    }

    /**
     * Les termes distinctifs d'un texte, en minuscules, dans l'ordre d'apparition.
     *
     * <p>Public pour être testé seul : c'est la moitié du comportement, et une heuristique qu'on
     * veut pouvoir figer cas par cas.</p>
     */
    public static Set<String> distinctiveTerms(String text) {
        Set<String> terms = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return terms;
        }
        for (String raw : text.split("[\\s,;:!?()\\[\\]{}<>\"'«»]+")) {
            String token = raw.strip();
            // La ponctuation de fin de phrase ne fait pas partie du nom ; le point interne, si.
            while (!token.isEmpty() && ".…-–—`*_/\\".indexOf(token.charAt(token.length() - 1)) >= 0) {
                token = token.substring(0, token.length() - 1);
            }
            while (!token.isEmpty() && "`*_/\\".indexOf(token.charAt(0)) >= 0) {
                token = token.substring(1);
            }
            if (isDistinctive(token)) {
                terms.add(token.toLowerCase(Locale.ROOT));
            }
        }
        return terms;
    }

    /**
     * Le bloc de rappel pour cette question, ou {@code null}.
     *
     * @param files    la carte du poste <b>du tour</b>, déjà filtrée par utilisateur et par poste
     * @param question la demande de l'utilisateur
     */
    public static String factsFor(List<HostMapFile> files, String question) {
        // Sans âge de référence, aucun fait n'est marqué : le comportement d'avant F-139.
        return factsFor(files, question, null, 0);
    }

    /**
     * Le bloc de rappel, en marquant les faits qui ont vieilli (F-139 / SF-139-01).
     *
     * @param today      la date du jour, ou {@code null} pour ne rien marquer
     * @param maxAgeDays âge au-delà duquel un fait est dit « à re-vérifier » ({@code <= 0} : jamais)
     */
    public static String factsFor(List<HostMapFile> files, String question, LocalDate today,
            int maxAgeDays) {
        if (files == null || files.isEmpty()) {
            return null;
        }
        Set<String> terms = distinctiveTerms(question);
        if (terms.isEmpty()) {
            return null;
        }
        // Un fait retenu une fois ne se répète pas, même s'il porte deux des termes cités.
        Map<String, String> retained = new LinkedHashMap<>();
        for (String term : terms) {
            for (HostMapFile file : files) {
                List<String> matches = matchesIn(file, term);
                if (matches.size() > MAX_MATCHES_PER_TERM) {
                    // Terme trop présent dans ce fichier : il décrit la carte, pas la question.
                    continue;
                }
                for (String fact : matches) {
                    retained.putIfAbsent(fact, file.getPath());
                    if (retained.size() >= MAX_FACTS) {
                        break;
                    }
                }
                if (retained.size() >= MAX_FACTS) {
                    break;
                }
            }
            if (retained.size() >= MAX_FACTS) {
                break;
            }
        }
        if (retained.isEmpty()) {
            return null;
        }
        StringBuilder body = new StringBuilder();
        boolean anyStale = false;
        for (Map.Entry<String, String> entry : retained.entrySet()) {
            boolean stale = isStale(entry.getKey(), today, maxAgeDays);
            anyStale |= stale;
            body.append("- ").append(entry.getKey())
                    .append("  [").append(entry.getValue()).append(']')
                    .append(stale ? STALE_MARK : "")
                    .append('\n');
        }
        // La consigne ne s'écrit que s'il y a quelque chose à re-vérifier : une phrase qui ne
        // s'applique à rien apprend au modèle à ne plus la lire.
        String result = HEADER + (anyStale ? STALE_NOTICE : "") + body;
        return result.length() <= MAX_CHARS ? result : result.substring(0, MAX_CHARS) + "…\n";
    }

    // -------------------------------------------------------------- internes

    /**
     * Un terme est distinctif s'il ne ressemble pas à un mot de la langue.
     *
     * <p>Signaux retenus : un point interne (domaine), un tiret ou un souligné (identifiant), un
     * chiffre, une majuscule interne (CyberArk), ou tout en capitales (CAGIP). Un mot ordinaire n'en
     * porte aucun — et c'est exactement ce qu'on veut écarter.</p>
     */
    private static boolean isDistinctive(String token) {
        if (token.length() < MIN_TERM_LENGTH || token.length() > 120) {
            return false;
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        boolean hasInnerPunct = false;
        boolean hasInnerUpper = false;
        boolean allUpper = true;
        for (int i = 0; i < token.length(); i++) {
            char c = token.charAt(i);
            if (Character.isLetter(c)) {
                hasLetter = true;
                if (Character.isLowerCase(c)) {
                    allUpper = false;
                }
                if (i > 0 && Character.isUpperCase(c)) {
                    hasInnerUpper = true;
                }
            } else if (Character.isDigit(c)) {
                hasDigit = true;
                allUpper = false;
            } else if (c == '.' || c == '-' || c == '_' || c == '/' || c == ':') {
                if (i > 0 && i < token.length() - 1) {
                    hasInnerPunct = true;
                }
                allUpper = false;
            } else {
                return false; // Caractère inattendu : ce n'est pas un identifiant.
            }
        }
        if (!hasLetter) {
            return false; // Un nombre seul n'est pas un nom d'infrastructure.
        }
        return hasInnerPunct || hasDigit || hasInnerUpper || allUpper;
    }

    /**
     * Ce fait a-t-il dépassé l'âge où un fait d'infrastructure reste sûr ? (F-139 / SF-139-01)
     *
     * <p><b>Trois « non » délibérés.</b> Un fait <b>sans date</b> n'est pas marqué : on ne devine pas
     * un âge. Une date <b>illisible</b> non plus : elle ne prouve pas la vieillesse. Une date
     * <b>future</b> non plus : c'est une faute de saisie, pas un fait périmé. Marquer à tort userait
     * la marque — et une marque usée ne se lit plus.</p>
     */
    static boolean isStale(String fact, LocalDate today, int maxAgeDays) {
        if (today == null || maxAgeDays <= 0 || fact == null) {
            return false;
        }
        Matcher matcher = CONSTAT_DATE.matcher(fact);
        if (!matcher.find()) {
            return false;
        }
        try {
            LocalDate observed = LocalDate.parse(matcher.group(1));
            if (observed.isAfter(today)) {
                return false;
            }
            return observed.plusDays(maxAgeDays).isBefore(today);
        } catch (DateTimeParseException ex) {
            return false;
        }
    }

    /** Les lignes porteuses de ce fichier qui mentionnent le terme. */
    private static List<String> matchesIn(HostMapFile file, String term) {
        String content = file.getContent();
        if (content == null || content.isBlank()) {
            return List.of();
        }
        List<String> matches = new ArrayList<>();
        for (String line : content.split("\n")) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(">")) {
                continue; // Titres et consignes du gabarit ne sont pas des faits.
            }
            if (!trimmed.toLowerCase(Locale.ROOT).contains(term)) {
                continue;
            }
            matches.add(trimmed.length() > MAX_FACT_CHARS
                    ? trimmed.substring(0, MAX_FACT_CHARS) + "…"
                    : trimmed);
        }
        return matches;
    }
}
