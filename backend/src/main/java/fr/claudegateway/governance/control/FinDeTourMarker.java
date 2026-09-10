package fr.claudegateway.governance.control;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Le <b>marqueur de fin de tour</b> (F-52 / SF-52-02) : ce que le modèle déclare en terminant, et la
 * seule chose que les contrôles de fin de tour lisent.
 *
 * <p><b>Pourquoi un marqueur.</b> La feature demande un juge <i>sémantique</i>, <i>best-effort</i>,
 * et <b>jamais une autorité</b>. Juger le sens d'un tour est un travail de modèle ; le refaire côté
 * serveur imposerait un second appel au fournisseur à chaque fin de tour — le double du coût, pour
 * une décision que le modèle peut rendre dans la réponse qu'il écrit déjà. Le marqueur déplace donc
 * le jugement là où il coûte zéro, et laisse au serveur ce qu'il fait bien : lire une <b>forme</b>,
 * mécaniquement, sans interpréter.</p>
 *
 * <p><b>C'est un commentaire HTML</b>, et ce n'est pas un détail : le rendu Markdown de la réponse ne
 * l'affiche pas. Le marqueur parle au produit, pas au lecteur.</p>
 *
 * <p><b>Le dernier fait foi.</b> Une réponse peut citer la forme attendue avant de la poser
 * réellement — en expliquant la règle, par exemple. Celui qui clôt la réponse est le vrai.</p>
 *
 * @param promotions ce que le tour a fait apparaître de durable et qui n'est pas dans la carte du
 *                   projet ; vide s'il n'y a rien à promouvoir
 * @param dette      nombre de cases {@code - [ ]} restées non cochées dans la carte du projet
 */
public record FinDeTourMarker(List<String> promotions, int dette) {

    /** La forme exacte attendue, telle qu'elle est rendue au modèle quand elle manque. */
    public static final String FORME = "<!-- fin-de-tour: promotion=aucune; dette=0 -->";

    /** Éléments cités dans une action corrective : au-delà, ce n'est plus une action, c'est une liste. */
    public static final int MAX_CITED = 10;

    /** Longueur de la citation des éléments à promouvoir dans le message correctif. */
    public static final int MAX_CITED_CHARS = 300;

    private static final Pattern MARKER =
            Pattern.compile("<!--\\s*fin-de-tour\\s*:(.*?)-->", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /** Ce qui s'écrit quand il n'y a rien à promouvoir. Le vide vaut la même chose. */
    private static final List<String> NOTHING = List.of("aucune", "aucun", "none", "-", "0");

    /** Rend la liste immuable et bornée : elle finit dans un message lu par un modèle. */
    public FinDeTourMarker {
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
    }

    /** Vrai si le tour n'a rien laissé à promouvoir. */
    public boolean nothingToPromote() {
        return promotions.isEmpty();
    }

    /**
     * Le <b>dernier</b> marqueur lisible de la réponse.
     *
     * @return le marqueur, ou {@link Optional#empty()} s'il est absent <b>ou illisible</b> — les deux
     *         appellent la même correction : reposer la forme attendue
     */
    public static Optional<FinDeTourMarker> parse(String reply) {
        if (reply == null || reply.isBlank()) {
            return Optional.empty();
        }
        Matcher matcher = MARKER.matcher(reply);
        String body = null;
        while (matcher.find()) {
            body = matcher.group(1);
        }
        return body == null ? Optional.empty() : read(body);
    }

    /** Lit le corps du marqueur ; {@code empty} dès qu'une valeur ne veut rien dire. */
    private static Optional<FinDeTourMarker> read(String body) {
        List<String> promotions = List.of();
        Integer dette = null;
        for (String pair : body.split(";")) {
            int equals = pair.indexOf('=');
            if (equals < 0) {
                continue; // Un fragment sans `=` n'apporte rien ; il ne condamne pas le marqueur.
            }
            String key = pair.substring(0, equals).trim().toLowerCase(Locale.ROOT);
            String value = pair.substring(equals + 1).trim();
            switch (key) {
                case "promotion", "promotions" -> promotions = readPromotions(value);
                case "dette", "debt" -> {
                    dette = readDette(value);
                    if (dette == null) {
                        // Une dette qu'on ne sait pas compter rend le marqueur illisible : mieux vaut
                        // le redemander que de conclure « zéro » sur une valeur qu'on n'a pas comprise.
                        return Optional.empty();
                    }
                }
                default -> {
                    // Clef inconnue : ignorée. Le marqueur peut s'enrichir sans casser les anciens.
                }
            }
        }
        return dette == null ? Optional.empty() : Optional.of(new FinDeTourMarker(promotions, dette));
    }

    /** {@code aucune}, {@code none} et le vide veulent tous dire « rien ». */
    private static List<String> readPromotions(String value) {
        if (value.isEmpty() || NOTHING.contains(value.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<String> items = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (!item.isEmpty() && !items.contains(item)) {
                items.add(item);
            }
        }
        return items;
    }

    /** Un entier positif ou nul, ou {@code null} : une dette négative ne veut rien dire. */
    private static Integer readDette(String value) {
        try {
            int parsed = Integer.parseInt(value.trim());
            return parsed < 0 ? null : parsed;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /** Les éléments à promouvoir, cités pour un message correctif : bornés en nombre et en longueur. */
    public String citedPromotions() {
        StringBuilder cited = new StringBuilder();
        int count = 0;
        for (String item : promotions) {
            if (count >= MAX_CITED || cited.length() >= MAX_CITED_CHARS) {
                cited.append('…');
                break;
            }
            if (count > 0) {
                cited.append(", ");
            }
            cited.append(item);
            count++;
        }
        return cited.toString();
    }
}
