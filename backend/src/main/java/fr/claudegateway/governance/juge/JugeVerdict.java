package fr.claudegateway.governance.juge;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Le <b>bloc de verdict</b> du juge indépendant (F-94 / SF-94-01).
 *
 * <p>Le juge peut raisonner librement — c'est même souhaitable : comparer une carte à des notes
 * demande de peser. Mais il <b>doit</b> terminer par une ligne contenant exactement
 * {@value #MARQUEUR}, suivie soit de {@code AUCUN}, soit d'une ligne par élément :</p>
 *
 * <pre>
 * ===VERDICT===
 * - bastion « bst-01 » — cité dans migration-dns/STATE.md
 * </pre>
 *
 * <p><b>Seul ce qui suit le marqueur est lu</b>, et c'est le point de cette classe. Le prompt
 * d'origine note que l'inverse a coûté un bug : quand on testait la sortie entière, un juge bavard
 * qui concluait « aucun élément manquant » au fil de son raisonnement était pris pour une
 * <i>alerte</i>. Ici, ce qui précède le marqueur n'existe pas.</p>
 *
 * <p><b>Le repli alerte.</b> Marqueur absent, bloc vide, bloc incompréhensible : le verdict est
 * {@link #illisible()}. L'appelant en tire une alerte, jamais un silence — c'est la règle du filet
 * qui doit <b>échouer bruyamment</b>, et elle est plus importante ici qu'ailleurs : un juge qu'on
 * n'a pas compris n'a pas dit qu'il n'y avait rien.</p>
 *
 * <p><b>Tolérance de forme, jamais de fond.</b> Trois séparateurs (le tiret cadratin, {@code --},
 * {@code -}), deux puces ({@code -}, {@code *}), {@code AUCUN} sans accent ni casse : on corrige un
 * modèle sur ce qu'il dit, pas sur sa typographie. Même arbitrage que {@code FinDeTourMarker}.</p>
 *
 * @param lisible  vrai si le bloc de verdict a été trouvé <b>et</b> compris
 * @param elements ce qui est cité dans les notes et absent de la carte ; vide quand le juge conclut
 *                 {@code AUCUN}, et vide aussi quand le verdict est illisible
 */
public record JugeVerdict(boolean lisible, List<Element> elements) {

    /** La ligne qui ouvre le bloc. Immuable : elle est écrite dans la consigne du juge. */
    public static final String MARQUEUR = "===VERDICT===";

    /** Ce que le juge écrit quand il ne trouve rien. */
    public static final String AUCUN = "AUCUN";

    /** Éléments retenus. Au-delà, ce n'est plus une liste à vérifier, c'est un inventaire. */
    public static final int MAX_ELEMENTS = 20;

    /** Longueur d'un élément cité. */
    public static final int MAX_ELEMENT_CHARS = 200;

    /** Longueur d'une source citée — un nom de fichier, pas une phrase. */
    public static final int MAX_SOURCE_CHARS = 120;

    private static final JugeVerdict ILLISIBLE = new JugeVerdict(false, List.of());
    private static final JugeVerdict RIEN = new JugeVerdict(true, List.of());

    /**
     * Un élément que le juge dit cité dans les notes et absent de la carte.
     *
     * @param element ce dont il s'agit, tel que le juge l'a écrit
     * @param source  le fichier où il l'a vu ; <b>vide</b> si le juge ne l'a pas dit — on ne rejette
     *                pas pour autant : un élément sans source reste une piste à vérifier
     */
    public record Element(String element, String source) {

        /** Borne et nettoie : ces deux champs finissent dans un message lu par un modèle. */
        public Element {
            element = clamp(element, MAX_ELEMENT_CHARS);
            source = clamp(source, MAX_SOURCE_CHARS);
        }

        /** Cet élément, tel qu'un message le cite. */
        public String cited() {
            return source.isEmpty() ? element : element + " — cité dans " + source;
        }

        private static String clamp(String value, int max) {
            if (value == null) {
                return "";
            }
            String trimmed = value.strip();
            return trimmed.length() <= max ? trimmed : trimmed.substring(0, max).strip();
        }
    }

    /** Rend la liste immuable et bornée. */
    public JugeVerdict {
        elements = elements == null ? List.of() : List.copyOf(elements);
    }

    /** Le verdict qu'on rend quand on n'a pas compris — et qui fait <b>alerter</b> l'appelant. */
    public static JugeVerdict illisible() {
        return ILLISIBLE;
    }

    /** Le verdict « rien à signaler », et lui seul autorise à se taire. */
    public static JugeVerdict rien() {
        return RIEN;
    }

    /** Vrai si le juge a été compris et n'a rien trouvé. */
    public boolean rienASignaler() {
        return lisible && elements.isEmpty();
    }

    /**
     * Lit le bloc de verdict d'une réponse de juge.
     *
     * <p><b>Le dernier marqueur fait foi</b> : une réponse peut citer la forme attendue en
     * expliquant la consigne avant de la poser réellement. Celui qui clôt la réponse est le vrai —
     * même arbitrage que {@code FinDeTourMarker}, pour la même raison.</p>
     *
     * @return le verdict, ou {@link #illisible()} si le marqueur manque ou si ce qui le suit ne veut
     *         rien dire
     */
    public static JugeVerdict parse(String reponse) {
        if (reponse == null || reponse.isBlank()) {
            return illisible();
        }
        int marker = reponse.lastIndexOf(MARQUEUR);
        if (marker < 0) {
            // Un juge bavard qui conclut « aucun élément manquant » SANS poser le marqueur n'a pas
            // rendu de verdict. Le prendre pour un silence est exactement le bug du prompt.
            return illisible();
        }
        String bloc = reponse.substring(marker + MARQUEUR.length());
        return read(bloc);
    }

    /** Lit ce qui suit le marqueur ; {@code illisible} dès que le bloc ne dit rien d'exploitable. */
    private static JugeVerdict read(String bloc) {
        List<Element> elements = new ArrayList<>();
        boolean aucun = false;
        for (String raw : bloc.split("\\R")) {
            String ligne = raw.strip();
            if (ligne.isEmpty() || isFence(ligne)) {
                continue;
            }
            if (isAucun(ligne)) {
                aucun = true;
                continue;
            }
            if (!isBullet(ligne)) {
                // Une phrase libre dans le bloc n'est PAS un élément. La forme demandée est une
                // liste à puces ; tout le reste est du raisonnement qui a débordé, et le prendre
                // pour un élément fabriquerait une alerte que le juge n'a pas écrite. Si le bloc
                // n'a finalement rien d'exploitable, on alerte — bruyamment, comme il se doit.
                continue;
            }
            String item = stripBullet(ligne);
            if (item.isEmpty()) {
                continue;
            }
            Element element = element(item);
            if (!element.element().isEmpty() && !contains(elements, element)) {
                elements.add(element);
            }
            if (elements.size() >= MAX_ELEMENTS) {
                break;
            }
        }
        if (!elements.isEmpty()) {
            // Un juge qui liste ET écrit « aucun » s'est contredit : la liste l'emporte, parce que
            // le filet est là pour signaler. Se taire sur un doute serait le contraire de son rôle.
            return new JugeVerdict(true, elements);
        }
        // Un bloc VIDE n'est pas un silence : c'est une réponse qu'on n'a pas comprise. Le filet
        // doit échouer bruyamment — conclure « rien à signaler » d'un blanc est le bug, à l'envers.
        return aucun ? rien() : illisible();
    }

    /** Vrai pour une clôture de bloc de code : un juge peut encadrer son verdict, ça ne dit rien. */
    private static boolean isFence(String ligne) {
        return ligne.startsWith("```") || ligne.startsWith("~~~") || ligne.equals(MARQUEUR);
    }

    /** {@code AUCUN}, {@code aucun.}, {@code Aucun élément} : le même « rien ». */
    private static boolean isAucun(String ligne) {
        String plain = deaccent(stripBullet(ligne)).toUpperCase(Locale.ROOT);
        return plain.equals(AUCUN) || plain.startsWith(AUCUN + " ") || plain.equals(AUCUN + ".")
                || plain.equals("NONE") || plain.equals("RIEN");
    }

    /** Vrai si la ligne est une puce de liste — la seule forme qui porte un élément. */
    private static boolean isBullet(String ligne) {
        return ligne.startsWith("- ") || ligne.startsWith("* ") || ligne.startsWith("• ");
    }

    /** Retire la puce d'une ligne de liste, quelle qu'elle soit. */
    private static String stripBullet(String ligne) {
        String item = ligne;
        if (item.startsWith("- ") || item.startsWith("* ") || item.startsWith("• ")) {
            item = item.substring(2);
        } else if (item.equals("-") || item.equals("*")) {
            item = "";
        }
        return item.strip();
    }

    /**
     * Découpe {@code <élément> — cité dans <fichier>}.
     *
     * <p>Sans séparateur, tout l'item est l'élément et la source reste vide : une piste sans source
     * reste une piste. Refuser la ligne perdrait une alerte pour un tiret.</p>
     */
    private static Element element(String item) {
        int[] cut = separator(item);
        if (cut == null) {
            return new Element(item, "");
        }
        String element = item.substring(0, cut[0]).strip();
        String source = stripCitePrefix(item.substring(cut[1]).strip());
        return element.isEmpty() ? new Element(item, "") : new Element(element, source);
    }

    /**
     * Les bornes du séparateur : tiret cadratin, demi-cadratin, {@code --}, ou {@code -} entouré
     * d'espaces.
     *
     * @return {@code [début, fin]} du séparateur, ou {@code null} s'il n'y en a pas
     */
    private static int[] separator(String item) {
        int em = item.indexOf('—');
        if (em >= 0) {
            return new int[] {em, em + 1};
        }
        int en = item.indexOf('–');
        if (en >= 0) {
            return new int[] {en, en + 1};
        }
        int dashdash = item.indexOf("--");
        if (dashdash >= 0) {
            return new int[] {dashdash, dashdash + 2};
        }
        int dash = item.indexOf(" - ");
        return dash < 0 ? null : new int[] {dash, dash + 3};
    }

    /** Retire le « cité dans » qui précède le nom de fichier, avec ou sans accent. */
    private static String stripCitePrefix(String source) {
        String plain = deaccent(source).toLowerCase(Locale.ROOT);
        for (String prefix : List.of("cite dans ", "cite par ", "cite en ", "dans ", "source : ",
                "source: ")) {
            if (plain.startsWith(prefix)) {
                return source.substring(prefix.length()).strip();
            }
        }
        return source;
    }

    /** Insensible aux accents : on ne refuse pas un verdict parce qu'il manque un accent. */
    private static String deaccent(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }

    /** Doublons écartés sans tenir compte de la casse : deux fois le même serveur reste un serveur. */
    private static boolean contains(List<Element> elements, Element candidate) {
        return elements.stream()
                .anyMatch(existing -> existing.element().equalsIgnoreCase(candidate.element()));
    }

    /** Les éléments, tels qu'un message correctif les cite — un par ligne, préfixés d'un tiret. */
    public String cited() {
        StringBuilder cited = new StringBuilder();
        for (Element element : elements) {
            if (cited.length() > 0) {
                cited.append(" ; ");
            }
            cited.append(element.cited());
        }
        return cited.toString();
    }
}
