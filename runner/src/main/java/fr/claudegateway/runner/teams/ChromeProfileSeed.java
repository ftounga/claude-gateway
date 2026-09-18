package fr.claudegateway.runner.teams;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * <b>Amorce ciblée des préférences du Chrome managé</b> (F-122 / SF-122-05).
 *
 * <p>La capture de réunion (F-128 / SF-128-02) déclenche dans l'onglet Teams une invite <b>micro</b>
 * ({@code getUserMedia}). Le Chrome managé étant lancé <b>hors champ</b> (SF-122-01), cette invite ne
 * peut pas être cliquée et <b>bloque</b> la capture. Plutôt qu'un auto-accept média <b>large</b>
 * ({@code --use-fake-ui-for-media-stream}, écarté par le PO car il ouvrirait micro <b>et</b> caméra à
 * <b>tout</b> site), on <b>pré-autorise le micro pour la seule origine Teams</b> en amorçant le fichier
 * {@code Preferences} du profil dédié — le content setting {@code media_stream_mic = allow}
 * ({@link #ALLOW}).</p>
 *
 * <p><b>Portée bornée, sans effet de bord.</b> L'amorçage vit dans le <b>seul</b> profil managé
 * ({@code --user-data-dir} de F-122, jamais le navigateur perso), pour la <b>seule</b> origine
 * {@code teams.microsoft.com} (et ses sous-domaines). La fusion est <b>idempotente</b> et ne touche
 * <b>aucune autre clé</b> du {@code Preferences} : la session Teams déjà connectée et les réglages
 * existants sont préservés. Un fichier corrompu n'est <b>jamais</b> écrasé et une erreur d'écriture ne
 * casse <b>jamais</b> le cycle de vie F-122 — au pire, l'invite micro réapparaît, la Vigie tient.</p>
 *
 * <p><b>Éprouvable sans Chrome.</b> La fusion ({@link #withTeamsMicAllowed(ObjectNode)}) est <b>pure</b>
 * et testée seule ; {@link #seed(Path, Consumer)} est testé sur un dossier temporaire.</p>
 */
final class ChromeProfileSeed {

    private ChromeProfileSeed() {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Valeur Chrome d'un content setting « autorisé ». */
    static final int ALLOW = 1;

    /**
     * Emplacement du fichier de préférences du profil par défaut dans un {@code --user-data-dir} :
     * Chrome écrit le profil « Default » sous le dossier de données utilisateur.
     */
    static final String PREFERENCES_PATH = "Default/Preferences";

    /** Motif Chrome (primaire,secondaire) pour l'origine Teams exacte. {@code :443} = https, {@code ,*} = tout secondaire. */
    static final String TEAMS_ORIGIN = "https://teams.microsoft.com:443,*";

    /** Motif Chrome pour les sous-domaines Teams ({@code [*.]} = tout sous-domaine). */
    static final String TEAMS_WILDCARD = "https://[*.]teams.microsoft.com:443,*";

    /**
     * Fusion <b>pure</b> : pose {@code media_stream_mic = allow} pour les origines Teams dans {@code root},
     * en créant l'arborescence manquante et sans jamais toucher les autres clés. Idempotente : une entrée
     * déjà « allow » n'est pas réécrite.
     *
     * @return {@code root}, muté sur place puis rendu pour l'usage fluide/les tests
     */
    static ObjectNode withTeamsMicAllowed(ObjectNode root) {
        ObjectNode profile = child(root, "profile");
        ObjectNode contentSettings = child(profile, "content_settings");
        ObjectNode exceptions = child(contentSettings, "exceptions");
        ObjectNode mic = child(exceptions, "media_stream_mic");
        allow(mic, TEAMS_ORIGIN);
        allow(mic, TEAMS_WILDCARD);
        return root;
    }

    /**
     * Amorce le {@code Preferences} du profil managé de façon idempotente : lit l'existant, fusionne
     * l'autorisation micro Teams, réécrit atomiquement. Ne lève jamais : toute erreur est signalée via
     * {@code say} et le lancement se poursuit.
     */
    static void seed(Path profileDir, Consumer<String> say) {
        Path prefs = profileDir.resolve(PREFERENCES_PATH);
        try {
            ObjectNode root;
            if (Files.exists(prefs)) {
                JsonNode parsed;
                try {
                    parsed = MAPPER.readTree(Files.readString(prefs));
                } catch (IOException | RuntimeException e) {
                    // JSON illisible ou corrompu : ne pas écraser des données peut-être valides.
                    tell(say, "Préférences du Chrome managé illisibles — micro Teams laissé à l'invite, "
                            + "lancement poursuivi.");
                    return;
                }
                if (parsed == null || !parsed.isObject()) {
                    tell(say, "Préférences du Chrome managé au format inattendu — micro Teams laissé à "
                            + "l'invite, lancement poursuivi.");
                    return;
                }
                root = (ObjectNode) parsed;
            } else {
                root = MAPPER.createObjectNode();
            }
            withTeamsMicAllowed(root);
            writeAtomic(prefs, MAPPER.writeValueAsString(root));
        } catch (IOException e) {
            tell(say, "Impossible d'amorcer le profil du Chrome managé (" + e.getMessage() + ") — micro "
                    + "Teams laissé à l'invite, lancement poursuivi.");
        }
    }

    private static void allow(ObjectNode mic, String pattern) {
        JsonNode existing = mic.get(pattern);
        if (existing != null && existing.isObject() && existing.path("setting").asInt() == ALLOW) {
            return; // déjà posé : idempotent, on ne réécrit pas.
        }
        ObjectNode entry = (existing != null && existing.isObject())
                ? (ObjectNode) existing
                : MAPPER.createObjectNode();
        entry.put("setting", ALLOW);
        mic.set(pattern, entry);
    }

    private static ObjectNode child(ObjectNode parent, String name) {
        JsonNode node = parent.get(name);
        if (node != null && node.isObject()) {
            return (ObjectNode) node;
        }
        ObjectNode created = MAPPER.createObjectNode();
        parent.set(name, created);
        return created;
    }

    private static void writeAtomic(Path target, String content) throws IOException {
        Files.createDirectories(target.getParent());
        Path tmp = target.resolveSibling(target.getFileName() + ".cg-tmp");
        Files.writeString(tmp, content);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicUnsupported) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void tell(Consumer<String> say, String message) {
        if (say != null) {
            say.accept(message);
        }
    }
}
