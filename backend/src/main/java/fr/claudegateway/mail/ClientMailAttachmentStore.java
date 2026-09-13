package fr.claudegateway.mail;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import fr.claudegateway.atelier.storage.WorkspaceStorage;
import fr.claudegateway.email.ClientMailMessage.Attachment;

/**
 * <b>Les pièces jointes en attente d'envoi</b> (F-110 / SF-110-03), dans le stockage objet existant.
 *
 * <p>Même doctrine que les pages (F-109) : S3 en cluster, mémoire en dev et en test, sous un préfixe à lui. Les
 * pièces n'y vivent que <b>le temps de l'envoi</b> : la file les efface à l'état final, comme les corps.</p>
 *
 * <p><b>L'isolation est dans la clé</b> : {@code client-emails/{userId}/{emailId}/{nn}/{nom encodé}} est
 * toujours reconstruite depuis la ligne {@code client_emails} ; le nom, encodé, n'est qu'un dernier segment. Le
 * numéro {@code nn} garde l'ordre ; le type MIME est déduit du nom, comme à la mise en file.</p>
 */
@Component
public class ClientMailAttachmentStore {

    static final String PREFIX = "client-emails/";

    private final WorkspaceStorage storage;

    public ClientMailAttachmentStore(WorkspaceStorage storage) {
        this.storage = storage;
    }

    /** Range les pièces d'un courriel, dans l'ordre. */
    public void put(UUID userId, UUID emailId, List<Attachment> attachments) {
        for (int index = 0; index < attachments.size(); index++) {
            Attachment attachment = attachments.get(index);
            storage.putFile(prefix(userId, emailId) + String.format("%02d", index) + "/"
                    + URLEncoder.encode(attachment.name(), StandardCharsets.UTF_8), attachment.content(),
                    attachment.contentType());
        }
    }

    /** Les pièces d'un courriel, dans l'ordre de la mise en file ; celles qui manquent sont absentes. */
    public List<Attachment> load(UUID userId, UUID emailId) {
        String prefix = prefix(userId, emailId);
        List<Attachment> found = new ArrayList<>();
        for (String key : storage.listKeys(prefix).stream().sorted().toList()) {
            String rest = key.substring(prefix.length());
            int slash = rest.indexOf('/');
            if (slash < 0) {
                continue;
            }
            String name = URLDecoder.decode(rest.substring(slash + 1), StandardCharsets.UTF_8);
            storage.getFile(key).ifPresent(content ->
                    found.add(new Attachment(name, ClientMailAttachments.contentTypeOf(name), content)));
        }
        return List.copyOf(found);
    }

    /** Efface les pièces d'un courriel (état final, ou mise en file annulée). */
    public void delete(UUID userId, UUID emailId) {
        storage.deletePrefix(prefix(userId, emailId));
    }

    /** Efface les pièces en attente d'un compte supprimé. */
    public void deleteAccount(UUID userId) {
        storage.deletePrefix(PREFIX + userId + "/");
    }

    /** La barre finale compte : un identifiant n'en englobe jamais un autre. */
    static String prefix(UUID userId, UUID emailId) {
        return PREFIX + userId + "/" + emailId + "/";
    }
}
