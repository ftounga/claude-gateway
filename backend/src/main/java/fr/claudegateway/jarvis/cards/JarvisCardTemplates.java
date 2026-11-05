package fr.claudegateway.jarvis.cards;

import java.text.Normalizer;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * <b>Les gabarits des pancartes, côté passerelle</b> (F-248 / SF-248-02, cadrage §4.1, D8) — des <b>données</b>, pas du
 * code par type : pour chaque type, la <b>liste blanche</b> des attributs (dans l'ordre, 3 à 5 retenus) et leur libellé,
 * et la table mot → ton. Un type inconnu prend {@code GENERIQUE}, jamais rien.
 *
 * <p><b>Jamais de secret</b> : un attribut hors liste ne quitte pas le serveur ; un nom d'attribut de secret (jeton, mot
 * de passe, clé…) et une valeur qui a la forme d'un secret sont écartés, même dans le gabarit générique. Pur.</p>
 */
public final class JarvisCardTemplates {

    public static final String TICKET = "TICKET";
    public static final String MR = "MR";
    public static final String PIPELINE = "PIPELINE";
    public static final String DEPLOIEMENT = "DEPLOIEMENT";
    public static final String RESULTAT = "RESULTAT";
    public static final String RENDEZ_VOUS = "RENDEZ_VOUS";
    public static final String COURRIEL = "COURRIEL";
    public static final String PERSONNE = "PERSONNE";
    public static final String ACCES = "ACCES";
    public static final String ATTENTE = "ATTENTE";
    public static final String GENERIQUE = "GENERIQUE";

    /** Tous les types, dans l'ordre (la garde de couverture de l'écran les parcourt). */
    public static final List<String> TYPES = List.of(TICKET, MR, PIPELINE, DEPLOIEMENT, RESULTAT, RENDEZ_VOUS,
            COURRIEL, PERSONNE, ACCES, ATTENTE, GENERIQUE);

    public static final int MAX_FIELDS = 5;
    public static final int MAX_VALUE = 80;
    public static final int MAX_TITLE = 90;

    /** La liste blanche de chaque type : attribut → libellé, dans l'ordre d'affichage. */
    static final Map<String, Map<String, String>> WHITELIST = Map.ofEntries(
            Map.entry(TICKET, ordered("status", "Statut", "assignee", "Assigné", "updated_at", "Mis à jour",
                    "blocked_by", "Bloqué par", "priority", "Priorité")),
            Map.entry(MR, ordered("state", "État", "status", "État", "pipeline", "Pipeline", "approvals",
                    "Approbations", "branch", "Branche", "source_branch", "Branche")),
            Map.entry(PIPELINE, ordered("status", "État", "result", "État", "failed_step", "Étape en échec", "ref",
                    "Branche", "branch", "Branche", "duration", "Durée")),
            Map.entry(DEPLOIEMENT, ordered("version", "Version", "image", "Image", "health", "Santé", "sync",
                    "Synchro", "replicas", "Réplicas", "status", "État")),
            Map.entry(RESULTAT, ordered("command", "Commande", "result", "Résultat", "line", "Ligne clé", "status",
                    "État")),
            Map.entry(RENDEZ_VOUS, ordered("hour", "Heure", "duration", "Durée", "location", "Lieu", "with",
                    "Avec")),
            Map.entry(COURRIEL, ordered("from", "De", "received", "Reçu", "subject", "Objet", "attachment",
                    "Pièce jointe")),
            Map.entry(PERSONNE, ordered("role", "Rôle", "client", "Client", "waiting", "Ce qui attend d'elle")),
            Map.entry(ACCES, ordered("account", "Compte", "kind", "Type", "expires_at", "Validité", "status",
                    "État", "scope", "Portée")),
            Map.entry(ATTENTE, ordered("since", "Depuis", "chantier", "Chantier", "who", "Qui", "status",
                    "État")));

    /** Les noms d'attribut d'un secret : jamais émis, même par le gabarit générique. */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(?i).*(token|secret|password|passwd|pwd|credential|cookie|private|apikey|api_key|bearer|session|"
                    + "authorization|signature|otp|mot_de_passe|jeton).*");
    /** Les valeurs qui ont la forme d'un secret (jetons connus, longues suites sans espace). */
    private static final Pattern SECRET_VALUE = Pattern.compile(
            "(?i)(glpat-|ghp_|gho_|ghs_|github_pat_|xox[abprs]-|sk-|akia[0-9a-z]{8}|asia[0-9a-z]{8}|eyj[a-z0-9_-]{10}|"
                    + "-----begin)|^[A-Za-z0-9+/=_-]{32,}$");

    private JarvisCardTemplates() {
    }

    private static Map<String, String> ordered(String... pairs) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            m.put(pairs[i], pairs[i + 1]);
        }
        return java.util.Collections.unmodifiableMap(m);
    }

    /** Le type admis (un type inconnu devient {@code GENERIQUE}). */
    public static String type(String raw) {
        return raw != null && TYPES.contains(raw) ? raw : GENERIQUE;
    }

    /** Vrai si un nom d'attribut ou sa valeur ressemble à un secret (jamais émis). */
    public static boolean secretLike(String name, String value) {
        return (name != null && SECRET_NAME.matcher(name).matches())
                || (value != null && SECRET_VALUE.matcher(value.strip()).find());
    }

    /**
     * Les champs d'un type, tirés des attributs selon sa liste blanche (3 à 5 retenus, un libellé une fois) ; le
     * générique prend les 3 premiers attributs non secrets. Aucun attribut hors liste.
     */
    public static List<JarvisCard.Field> fields(String type, Map<String, String> attributes) {
        List<JarvisCard.Field> out = new ArrayList<>();
        if (attributes == null || attributes.isEmpty()) {
            return out;
        }
        String t = type(type);
        Map<String, String> allowed = WHITELIST.get(t);
        Set<String> labels = new LinkedHashSet<>();
        if (allowed == null) {
            for (Map.Entry<String, String> e : attributes.entrySet()) {
                if (out.size() >= 3) {
                    break;
                }
                add(out, labels, humanize(e.getKey()), e.getKey(), e.getValue());
            }
            return out;
        }
        for (Map.Entry<String, String> e : allowed.entrySet()) {
            if (out.size() >= MAX_FIELDS) {
                break;
            }
            add(out, labels, e.getValue(), e.getKey(), attributes.get(e.getKey()));
        }
        return out;
    }

    private static void add(List<JarvisCard.Field> out, Set<String> labels, String label, String name, String raw) {
        String value = clean(raw, MAX_VALUE);
        if (value.isEmpty() || secretLike(name, raw) || secretLike(name, value) || !labels.add(label)) {
            return;
        }
        out.add(new JarvisCard.Field(label, value));
    }

    private static String humanize(String key) {
        String s = key == null ? "" : key.replace('_', ' ').strip();
        return s.isEmpty() ? "" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static final Pattern MASK_ASSIGN = Pattern.compile(
            "(?i)((?:password|passwd|pwd|token|secret|api[_-]?key|access[_-]?key|mot de passe)\\s*[=:]\\s*)[^\\s&\"']+");
    private static final Pattern MASK_BEARER = Pattern.compile("(?i)\\b(bearer|basic)\\s+[^\\s\"']+");
    private static final Pattern MASK_TOKEN = Pattern.compile(
            "\\b(?:glpat-|ghp_|gho_|ghs_|github_pat_|xox[abprs]-|sk-)[A-Za-z0-9_-]{6,}|\\bA[KS]IA[0-9A-Z]{12,}");

    /** Masque, dans un texte libre, ce qui a la forme d'un secret (« password=••• », jetons connus). */
    public static String mask(String text) {
        if (text == null) {
            return null;
        }
        String s = MASK_ASSIGN.matcher(text).replaceAll("$1•••");
        s = MASK_BEARER.matcher(s).replaceAll("$1 •••");
        return MASK_TOKEN.matcher(s).replaceAll("•••");
    }

    /** Une ligne, sans contrôle ni balise, bornée ; ce qui a la forme d'un secret est masqué. */
    public static String clean(String raw, int max) {
        String s = raw == null ? "" : mask(raw).replaceAll("[\\p{Cntrl}<>`]", " ").replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, Math.max(0, max - 1)).strip() + "…";
    }

    /** Les mots qui donnent le ton (normalisés, sans accents). Rouge réservé aux alertes. */
    private static final List<String> ALERT = List.of("echec", "echoue", "failed", "failure", "error", "erreur",
            "degrade", "degraded", "incident", "refuse", "denied", "expire", "expired", "broken", "auth_failed",
            "crashloop", "outofsync", "out of sync", "rouge", "ko", "panne");
    private static final List<String> WAIT = List.of("attend", "a repondre", "a envoyer", "a traiter", "a lire",
            "bloque", "blocked", "expire dans", "demande", "a faire", "pending", "draft", "brouillon");
    private static final List<String> OK = List.of("fusionne", "merged", "sain", "healthy", "reussi", "success",
            "succeeded", "synced", "termine", "fait", "done", "passed", "deploye", "deployed", "valide", "ok",
            "connecte");
    private static final List<String> OFF = List.of("deconnecte", "eteint", "offline", "hors ligne", "archive",
            "annule", "closed", "ferme", "pause");

    /** Le ton d'un mot de statut : {@code alerte}, {@code attend}, {@code ok}, {@code eteint} ou {@code normal}. */
    public static String tone(String word) {
        String n = normalize(word);
        if (n.isEmpty()) {
            return "normal";
        }
        if (n.contains("expire dans")) {
            return "attend";
        }
        if (n.contains("deconnecte") || n.contains("hors ligne")) {
            return "eteint";
        }
        if (matches(n, ALERT)) {
            return "alerte";
        }
        if (matches(n, WAIT)) {
            return "attend";
        }
        if (matches(n, OFF)) {
            return "eteint";
        }
        return matches(n, OK) ? "ok" : "normal";
    }

    private static boolean matches(String n, List<String> words) {
        for (String w : words) {
            // Une racine (« fusionne » ⇒ « fusionnée ») ; un mot court (« ok », « ko ») doit être entier.
            String end = w.length() <= 3 ? "([^a-z0-9]|$)" : "";
            if (Pattern.compile("(^|[^a-z0-9])" + Pattern.quote(w) + end).matcher(n).find()) {
                return true;
            }
        }
        return false;
    }

    /** Minuscules, sans accents. */
    public static String normalize(String s) {
        return s == null ? "" : Normalizer.normalize(s, Normalizer.Form.NFD).replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").strip();
    }

    /** Le ton d'un mot pour un type : un rendez-vous « en cours » ou « prochain » attend le PO. */
    public static String tone(String type, String word) {
        String n = normalize(word);
        if (RENDEZ_VOUS.equals(type) && (n.contains("en cours") || n.contains("prochain"))) {
            return "attend";
        }
        return tone(word);
    }

    /** Le statut d'un mot (le mot est toujours écrit), ou {@code null} sans mot. */
    public static JarvisCard.Status status(String type, String word) {
        String w = clean(word, 40);
        return w.isEmpty() ? null : new JarvisCard.Status(w, tone(type, w));
    }

    /** Les alias dicibles (≥ 3 caractères, sans doublon). */
    public static List<String> aliases(String... raw) {
        Set<String> out = new LinkedHashSet<>();
        for (String a : raw) {
            String c = clean(a, 60);
            if (c.length() >= 3) {
                out.add(c);
            }
        }
        return List.copyOf(out);
    }

    /**
     * F-248 / SF-248-06 — les champs du <b>plein écran</b> : libellé → valeur, dans l'ordre, chacun passé par le même
     * filtre (vide, secret ⇒ écarté) ; 12 au plus.
     */
    public static List<JarvisCard.Field> detailFields(Map<String, String> labeled) {
        List<JarvisCard.Field> out = new ArrayList<>();
        if (labeled == null) {
            return out;
        }
        Set<String> labels = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : labeled.entrySet()) {
            if (out.size() >= 12) {
                break;
            }
            String value = clean(e.getValue(), 400);
            if (!value.isEmpty() && !secretLike(e.getKey(), e.getValue()) && labels.add(e.getKey())) {
                out.add(new JarvisCard.Field(e.getKey(), value));
            }
        }
        return out;
    }

    /** Une fiche, champs passés par la liste blanche de son type. */
    public static JarvisCard card(String id, String type, String title, String statusWord,
            Map<String, String> attributes, String link, OffsetDateTime observedAt, String source, String group,
            List<String> aliases) {
        String t = type(type);
        return new JarvisCard(id, t, clean(title, MAX_TITLE), status(t, statusWord), fields(t, attributes), link,
                observedAt, source, group == null || group.isBlank() ? null : clean(group, 60), aliases, false);
    }
}
