package fr.claudegateway.teams.block;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;

import fr.claudegateway.atelier.storage.WorkspaceStorage;

/**
 * <b>Les images des moments</b> (F-89 / SF-89-02) : leur dépôt, leur lecture, et leur effacement.
 *
 * <h2>Ce qui remonte, et ce qui reste</h2>
 *
 * <p>La vidéo d'une réunion ne remonte <b>jamais</b> — c'est l'artefact le plus indiscret de tous, et
 * la gateway orchestre, elle ne devient pas un entrepôt de vidéos de réunions (§7 du cadrage). Ce qui
 * remonte, ce sont les <b>20 à 60 images retenues</b> : celles qui portent une décision.</p>
 *
 * <h2>L'isolation est dans la clé</h2>
 *
 * <p>Une image vit sous {@code teams-moments/{userId}/{workspaceId}/{imageId}.{ext}}, et la clé est
 * <b>reconstruite</b> à partir de l'utilisateur authentifié : un identifiant venu du client n'est
 * jamais qu'un <b>dernier segment</b>, jamais un chemin. C'est ce qui rend impossible, par
 * construction, d'atteindre l'image d'un autre compte — et l'identifiant est vérifié caractère par
 * caractère pour qu'un {@code ../} n'en fasse pas un chemin.</p>
 *
 * <h2>D2 — elles vivent avec le compte rendu et s'effacent avec lui</h2>
 *
 * <p>Supprimer le terminal Teams efface ses images, dans le même flux. Il n'y a <b>aucun cache</b>
 * de conversations de clients : ce serait un entrepôt de données sensibles que personne n'a demandé
 * et qu'il faudrait un jour expliquer.</p>
 *
 * <h2>Aucun producteur, et c'est assumé</h2>
 *
 * <p>F-89 livre le dépôt, la lecture et l'effacement — donc tout ce qu'il faut pour qu'un moment
 * s'affiche et s'agrandisse. <b>L'extraction</b> des images aux changements de plan, et leur
 * alignement sur la transcription, sont le cœur de <b>F-90</b>. Ouvrir dès maintenant un endpoint de
 * dépôt public sans rien pour l'alimenter offrirait une surface pour rien : le dépôt est donc une
 * méthode de service, que F-90 appellera.</p>
 */
@Service
public class TeamsMomentImageService {

    /** Racine du stockage des moments, hors du préfixe des fichiers de workspace. */
    static final String PREFIX = "teams-moments/";

    /**
     * Les types d'image acceptés, et l'extension qui les porte. Liste <b>close</b> : l'extension est
     * ce qui permet de redonner le bon type à la lecture sans stocker de métadonnée à côté, et un
     * type inconnu n'a rien à faire dans un compte rendu.
     */
    private static final Map<String, String> EXTENSIONS = Map.of(
            "image/png", "png",
            "image/jpeg", "jpg",
            "image/webp", "webp");

    private final WorkspaceStorage storage;

    public TeamsMomentImageService(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /**
     * Dépose une image de moment et rend son identifiant.
     *
     * @param userId      propriétaire (contexte de sécurité, jamais un paramètre client)
     * @param workspaceId terminal Teams auquel l'image appartient
     * @param contentType type d'image, parmi la liste close
     * @param content     octets de l'image
     * @return l'identifiant à poser dans un moment
     */
    public String store(UUID userId, UUID workspaceId, String contentType, byte[] content) {
        String extension = EXTENSIONS.get(contentType == null ? "" : contentType.toLowerCase(Locale.ROOT));
        if (extension == null) {
            throw new IllegalArgumentException("Type d'image non accepté : " + contentType);
        }
        String imageId = UUID.randomUUID().toString().replace("-", "");
        storage.putFile(prefixOf(userId, workspaceId) + imageId + "." + extension, content, contentType);
        return imageId;
    }

    /** Vrai si cette image existe <b>pour ce compte et ce terminal</b>. */
    public boolean exists(UUID userId, UUID workspaceId, String imageId) {
        return find(userId, workspaceId, imageId).isPresent();
    }

    /**
     * L'image, si elle existe pour ce compte et ce terminal.
     *
     * @return le type et les octets, ou vide — <b>vide</b> couvre « inconnue » et « à quelqu'un
     *         d'autre », qui doivent rester indiscernables
     */
    public Optional<StoredImage> find(UUID userId, UUID workspaceId, String imageId) {
        if (!isSafeId(imageId)) {
            return Optional.empty();
        }
        for (Map.Entry<String, String> type : EXTENSIONS.entrySet()) {
            String key = prefixOf(userId, workspaceId) + imageId + "." + type.getValue();
            Optional<byte[]> content = storage.getFile(key);
            if (content.isPresent()) {
                return Optional.of(new StoredImage(type.getKey(), content.get()));
            }
        }
        return Optional.empty();
    }

    /** Les identifiants d'images de ce terminal — sert à l'effacement et aux tests. */
    public List<String> list(UUID userId, UUID workspaceId) {
        String prefix = prefixOf(userId, workspaceId);
        return storage.listKeys(prefix).stream()
                .map(key -> key.substring(prefix.length()))
                .map(name -> name.contains(".") ? name.substring(0, name.lastIndexOf('.')) : name)
                .toList();
    }

    /**
     * Efface <b>toutes</b> les images d'un terminal (D2 : elles s'en vont avec le compte rendu).
     *
     * <p>Appelée à la suppression du workspace. Un échec partiel n'est pas silencieux : l'abstraction
     * de stockage lève quand l'effacement est incomplet (F-79 / SF-79-01).</p>
     */
    public void deleteAll(UUID userId, UUID workspaceId) {
        storage.deletePrefix(prefixOf(userId, workspaceId));
    }

    /**
     * La clé racine des images d'un terminal. <b>Publique et statique</b> parce que la suppression
     * d'un workspace doit l'effacer sans avoir à connaître ce service : c'est la seule
     * connaissance de la forme de la clé, et elle reste ici.
     */
    public static String prefixOf(UUID userId, UUID workspaceId) {
        return PREFIX + userId + "/" + workspaceId + "/";
    }

    /**
     * Un identifiant d'image est un <b>dernier segment</b>, jamais un chemin : lettres, chiffres et
     * tirets seulement. Ce qui exclut {@code ..}, {@code /} et tout ce qui en ferait une traversée.
     */
    private static boolean isSafeId(String imageId) {
        if (imageId == null || imageId.isBlank()
                || imageId.length() > TeamsBlockCards.MAX_IMAGE_ID_CHARS) {
            return false;
        }
        return imageId.chars().allMatch(c -> Character.isLetterOrDigit(c) || c == '-' || c == '_');
    }

    /** Une image relue : son type, et ses octets. */
    public record StoredImage(String contentType, byte[] content) {
    }
}
