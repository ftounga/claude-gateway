package fr.claudegateway.runner.teams;

import java.util.Iterator;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * <b>Le squelette d'un corps JSON</b> (F-89 / SF-89-12) : les <b>NOMS</b> de champs et leur <b>TYPE</b>
 * JSON, <b>jamais</b> une valeur.
 *
 * <p>Le relevé de forme sert à recaler {@link TeamsAdapterV1} sur la forme <b>réelle</b> des réponses
 * Teams — les alias de champs et les clés d'enveloppe qu'{@code arrayAt(...)} attend. Pour cela il
 * suffit de connaître, à chaque niveau, <b>quels champs existent</b> et de <b>quel type</b> ils sont.
 * La valeur elle-même — un nom, un jeton, une adresse, un identifiant — n'a aucune utilité et
 * <b>n'entre jamais</b> dans la sortie. C'est le point de vie privée <b>non négociable</b> de cette
 * subfeature.</p>
 *
 * <p><b>Ce qui sort</b> : pour un objet, la liste de ses clés avec le type de chaque valeur
 * ({@code { id: string, subject: string, value: array<object> }}) ; pour un tableau, le type de ses
 * éléments et — <b>uniquement</b> — le squelette de son <b>premier</b> élément
 * ({@code array<object>[ { id: string } ]}), assez pour révéler la clé d'enveloppe et la forme des
 * items. Une feuille devient son type : {@code string}, {@code number}, {@code boolean}, {@code null}.</p>
 *
 * <p><b>Garde-fous</b> : la récursion est bornée en profondeur ({@link #DEFAULT_DEPTH}) ; le nombre de
 * clés est borné par niveau ({@link #MAX_KEYS_PER_LEVEL}) ; un nom de clé anormalement long — un signal
 * de fuite possible — est <b>tronqué</b> et marqué ({@link #MAX_KEY_CHARS}). On tronque toujours le
 * <b>NOM</b>, jamais une valeur : il n'y a, dans tout ce fichier, aucun chemin par lequel
 * {@code asText()} d'une feuille serait écrit.</p>
 */
final class PayloadShape {

    /** Profondeur de récursion par défaut : assez pour voir l'enveloppe et la forme des items. */
    static final int DEFAULT_DEPTH = 4;

    /** Nombre de clés décrites par niveau ; au-delà, on compte le reste. */
    static final int MAX_KEYS_PER_LEVEL = 200;

    /** Longueur maximale d'un nom de clé écrit ; au-delà, le NOM est tronqué et marqué. */
    static final int MAX_KEY_CHARS = 120;

    /** La marque d'un nom de clé tronqué : la coupe se voit. */
    static final String TRUNCATED = "…(tronqué)";

    private PayloadShape() {
    }

    /** Le squelette de {@code node}, à la profondeur par défaut. */
    static String of(JsonNode node) {
        return of(node, DEFAULT_DEPTH);
    }

    /** Le squelette de {@code node}, borné à {@code maxDepth} niveaux. */
    static String of(JsonNode node, int maxDepth) {
        StringBuilder out = new StringBuilder();
        describe(node, Math.max(0, maxDepth), out);
        return out.toString();
    }

    private static void describe(JsonNode node, int depth, StringBuilder out) {
        if (node == null || node.isNull()) {
            out.append("null");
            return;
        }
        if (node.isObject()) {
            describeObject(node, depth, out);
            return;
        }
        if (node.isArray()) {
            describeArray(node, depth, out);
            return;
        }
        // Feuille : SEULEMENT son type. La valeur (asText / asLong / …) n'est jamais lue ni écrite.
        out.append(typeOf(node));
    }

    private static void describeObject(JsonNode node, int depth, StringBuilder out) {
        if (node.isEmpty()) {
            out.append("object{}");
            return;
        }
        if (depth <= 0) {
            out.append("object"); // profondeur atteinte : le type, sans dépliage
            return;
        }
        out.append("{ ");
        int total = node.size();
        int written = 0;
        Iterator<String> names = node.fieldNames();
        while (names.hasNext() && written < MAX_KEYS_PER_LEVEL) {
            String name = names.next();
            if (written > 0) {
                out.append(", ");
            }
            out.append(safeKey(name)).append(": ");
            describe(node.get(name), depth - 1, out);
            written++;
        }
        if (total > written) {
            out.append(", …(+").append(total - written).append(" clés)");
        }
        out.append(" }");
    }

    private static void describeArray(JsonNode node, int depth, StringBuilder out) {
        if (node.isEmpty()) {
            out.append("array<empty>");
            return;
        }
        JsonNode first = node.get(0);
        out.append("array<").append(typeOf(first)).append('>');
        // Le PREMIER élément seulement — assez pour révéler la forme des items, sans parcourir le reste.
        if (depth > 0 && (first.isObject() || first.isArray())) {
            out.append("[ ");
            describe(first, depth - 1, out);
            out.append(" ]");
        }
    }

    /** Le type JSON d'un nœud, jamais sa valeur. */
    private static String typeOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "null";
        }
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNumber()) {
            return "number";
        }
        // Texte — et tout autre nœud non structurant (binaire, POJO) : le TYPE « string », pas le contenu.
        return "string";
    }

    /**
     * Un nom de clé, écrit tel quel — c'est un nom de champ, pas une valeur —, mais <b>tronqué</b> s'il
     * est anormalement long : une clé de cette taille est un signal de fuite, et on coupe le NOM
     * (jamais une valeur).
     */
    private static String safeKey(String name) {
        if (name == null || name.isEmpty()) {
            return "";
        }
        if (name.length() <= MAX_KEY_CHARS) {
            return name;
        }
        return name.substring(0, MAX_KEY_CHARS) + TRUNCATED;
    }
}
