package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import fr.claudegateway.atelier.storage.InMemoryWorkspaceStorage;
import fr.claudegateway.email.ClientMailMessage.Attachment;

/** Les pièces en attente dans le stockage objet : ordre, noms, effacement, isolation (F-110 / SF-110-03). */
class ClientMailAttachmentStoreTest {

    private final InMemoryWorkspaceStorage storage = new InMemoryWorkspaceStorage();
    private final ClientMailAttachmentStore store = new ClientMailAttachmentStore(storage);

    @Test
    void attachmentsComeBackInOrderWithTheirNamesAndBytes() {
        UUID userId = UUID.randomUUID();
        UUID emailId = UUID.randomUUID();
        List<Attachment> written = List.of(
                new Attachment("Compte rendu réunion 14/09 ?.pdf", "application/pdf", new byte[] {1, 2}),
                new Attachment("a.md", "text/markdown; charset=utf-8", "# A".getBytes()));

        store.put(userId, emailId, written);
        List<Attachment> read = store.load(userId, emailId);

        assertThat(read).extracting(Attachment::name).containsExactly("Compte rendu réunion 14/09 ?.pdf", "a.md");
        assertThat(read.get(0).content()).containsExactly(1, 2);
        assertThat(read.get(1).contentType()).startsWith("text/markdown");
        assertThat(storage.listKeys(ClientMailAttachmentStore.PREFIX + userId + "/" + emailId + "/"))
                .allSatisfy(key -> assertThat(key.substring(key.lastIndexOf('/') + 1)).doesNotContain(" ", "?"));
    }

    @Test
    void deleteErasesOnlyThisMailAndDeleteAccountOnlyThisAccount() {
        UUID vera = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        Attachment file = new Attachment("cr.pdf", "application/pdf", new byte[] {9});
        store.put(vera, first, List.of(file));
        store.put(vera, second, List.of(file));
        store.put(bob, first, List.of(file));

        store.delete(vera, first);
        assertThat(store.load(vera, first)).isEmpty();
        assertThat(store.load(vera, second)).hasSize(1);
        assertThat(store.load(bob, first)).as("même identifiant, autre compte : intact").hasSize(1);

        store.deleteAccount(vera);
        assertThat(store.load(vera, second)).isEmpty();
        assertThat(store.load(bob, first)).hasSize(1);
    }
}
