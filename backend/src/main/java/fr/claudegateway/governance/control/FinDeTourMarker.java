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
 * <p><b>Il dit désormais OÙ</b> (F-93 / SF-93-01). Le champ {@code promu} porte ce que le tour a
 * réellement promu <b>et sa destination</b> : {@code promu=cluster atlas -> plateformes.md}. Sans
 * destination, une promotion n'est pas vérifiable — « je l'ai noté » se dit à chaque tour, et ne se
 * constate jamais. Le champ est <b>facultatif</b> : un marqueur qui ne le porte pas vaut « rien
 * promu », faute de quoi tout poste activé avant cette version cesserait de clore ses tours.</p>
 *
 * <p><b>C'est un commentaire HTML</b>, et ce n'est pas un détail : le rendu Markdown de la réponse ne
 * l'affiche pas. Le marqueur parle au produit, pas au lecteur.</p>
 *
 * <p><b>Le dernier fait foi.</b> Une réponse peut citer la forme attendue avant de la poser
 * réellement — en expliquant la règle, par exemple. Celui qui clôt la réponse est le vrai.</p>
 *
 * @param promotions ce que le tour a fait apparaître de durable et qui n'est encore dans aucune
 *                   carte ; vide s'il n'y a rien à promouvoir
 * @param promus     ce que le tour a promu, <b>avec sa destination</b> ; vide s'il n'a rien promu
 * @param dette      nombre de cases {@code - [ ]} restées non cochées dans les fichiers du projet
 */
public record FinDeTourMarker(List<String> promotions, List<Promotion> promus, int dette) {

    /** La forme exacte attendue, telle qu'elle est rendue au modèle quand elle manque. */
    public static final String FORME =
            "<!-- fin-de-tour: promotion=aucune; promu=aucune; dette=0 -->";

    /** Éléments cités dans une action corrective : au-delà, ce n'est plus une action, c'est une liste. */
    public static final int MAX_CITED = 10;

    /** Longueur de la citation des éléments à promouvoir dans le message correctif. */
    public static final int MAX_CITED_CHARS = 300;

    /**
     * Un élément promu, et <b>où</b> il l'a été (F-93 / SF-93-01).
     *
     * @param element     ce qui a été promu, tel que le modèle l'a écrit
     * @param destination le fichier de carte qui l'a reçu ; <b>vide</b> si le modèle ne l'a pas dit —
     *                    et c'est exactement ce qu'un contrôle refuse
     */
    public record Promotion(String element, String destination) {

        /** Rend les deux champs jamais nuls : ils finissent dans un message lu par un modèle. */
        public Promotion {
            element = element == null ? "" : element;
            destination = destination == null ? "" : destination;
        }

        /** Vrai si le modèle a dit où il avait promu. */
        public boolean hasDestination() {
            return !destination.isBlank();
        }

        /** Ce couple, tel qu'un message correctif le cite. */
        public String cited() {
            return hasDestination() ? element + " -> " + destination : element;
        }
    }

    private static final Pattern MARKER =
            Pattern.compile("<!--\\s*fin-de-tour\\s*:(.*?)-->", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);

    /**
     * La flèche de destination : {@code ->}, {@code →}, ou le mot {@code vers}.
     *
     * <p>On corrige le modèle sur le fond — a-t-il dit où ? — jamais sur la typographie. Un refus
     * qui porterait sur la forme d'une flèche ferait perdre un tour pour rien.</p>
     */
    private static final Pattern ARROW =
            Pattern.compile("\\s*(?:->|→|\\bvers\\b)\\s*", Pattern.CASE_INSENSITIVE);

    /** Ce qui s'écrit quand il n'y a rien à promouvoir. Le vide vaut la même chose. */
    private static final List<String> NOTHING = List.of("aucune", "aucun", "none", "-", "0");

    /** Rend les listes immuables et bornées : elles finissent dans un message lu par un modèle. */
    public FinDeTourMarker {
        promotions = promotions == null ? List.of() : List.copyOf(promotions);
        promus = promus == null ? List.of() : List.copyOf(promus);
    }

    /** Vrai si le tour n'a rien laissé à promouvoir. */
    public boolean nothingToPromote() {
        return promotions.isEmpty();
    }

    /** Les promotions déclarées <b>sans dire où</b> — celles qu'un contrôle refuse (F-93). */
    public List<Promotion> withoutDestination() {
        return promus.stream().filter(promotion -> !promotion.hasDestination()).toList();
    }

    /** Les destinations citées, dans l'ordre, sans doublon. */
    public List<String> destinations() {
        List<String> cited = new ArrayList<>();
        for (Promotion promotion : promus) {
            if (promotion.hasDestination() && !cited.contains(promotion.destination())) {
                cited.add(promotion.destination());
            }
        }
        return List.copyOf(cited);
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
        List<Promotion> promus = List.of();
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
                case "promu", "promus" -> promus = readPromus(value);
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
        return dette == null ? Optional.empty()
                : Optional.of(new FinDeTourMarker(promotions, promus, dette));
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

    /**
     * Lit {@code promu=élément -> fichier, élément -> fichier} (F-93 / SF-93-01), <b>tolérant à la
     * virgule dans le libellé</b> (F-125 / SF-125-02).
     *
     * <p>Un élément <b>sans</b> flèche est conservé avec une destination vide, et c'est délibéré :
     * c'est ce qui permet au contrôle de répondre « dis où », au lieu de perdre silencieusement une
     * déclaration mal formée — un silence qu'on prendrait pour « rien promu ».</p>
     *
     * <p><b>La virgule ne coupe plus un libellé.</b> Le cas réel : « le compte, avec sa virgule ->
     * plateformes.md » se coupait en deux — « le compte » sans destination, « avec sa virgule ->
     * plateformes.md » —, et le premier fragment, isolé devant la destination, faisait échouer le
     * suivi. Une virgule ne <b>ferme</b> désormais une promotion que si le segment qu'elle sépare
     * porte une flèche : un fragment sans flèche est <b>raccroché</b> au libellé en cours. Ce qui
     * reste sans flèche à la fin forme une promotion muette (une seule), que le contrôle refusera en
     * demandant où — le comportement attendu d'une déclaration incomplète.</p>
     */
    private static List<Promotion> readPromus(String value) {
        if (value.isEmpty() || NOTHING.contains(value.toLowerCase(Locale.ROOT))) {
            return List.of();
        }
        List<Promotion> items = new ArrayList<>();
        List<String> pending = new ArrayList<>();
        for (String part : value.split(",")) {
            String item = part.trim();
            if (item.isEmpty()) {
                continue;
            }
            pending.add(item);
            Matcher arrow = ARROW.matcher(item);
            if (!arrow.find()) {
                continue; // Fragment sans flèche : il appartient au libellé qui la porte, plus loin.
            }
            String joined = String.join(", ", pending);
            Matcher firstArrow = ARROW.matcher(joined);
            firstArrow.find();
            addPromotion(items, new Promotion(joined.substring(0, firstArrow.start()).strip(),
                    joined.substring(firstArrow.end()).strip()));
            pending.clear();
        }
        if (!pending.isEmpty()) {
            // Rien de tout ça n'avait de flèche : une déclaration muette, en un seul libellé.
            addPromotion(items, new Promotion(String.join(", ", pending), ""));
        }
        return items;
    }

    /** Ajoute une promotion non vide, sans doublon. */
    private static void addPromotion(List<Promotion> items, Promotion promotion) {
        if (!promotion.element().isEmpty() && !items.contains(promotion)) {
            items.add(promotion);
        }
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
        return cite(promotions);
    }

    /** Les promotions déclarées, citées avec leur destination quand elles en portent une. */
    public String citedPromus(List<Promotion> subset) {
        return cite(subset.stream().map(Promotion::cited).toList());
    }

    /** La citation bornée d'une liste : au-delà, ce n'est plus une action corrective. */
    private static String cite(List<String> items) {
        StringBuilder cited = new StringBuilder();
        int count = 0;
        for (String item : items) {
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
