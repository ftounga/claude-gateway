package fr.claudegateway.atelier.sends;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Le découpage serveur des blocs mail/message, aligné sur {@code message-segments.ts} (F-211 / SF-211-01). */
class MessageBlocksTest {

    @Test
    @DisplayName("seuls les blocs mail/message comptent, le code et les fences vides sont ignorés")
    void onlyMessageBlocksAreRanked() {
        String content = "Voici :\n```java\nint a;\n```\n\n```mail\nObjet : Accès\n\nBonjour\n```\n"
                + "puis\n```\n\n```\n```teams\nSalut\n```\nfin";

        List<MessageBlocks.Block> blocks = MessageBlocks.parse(content);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).index()).isZero();
        assertThat(blocks.get(0).kind()).isEqualTo(MessageBlockSend.Kind.MAIL);
        assertThat(blocks.get(0).subject()).isEqualTo("Accès");
        assertThat(blocks.get(0).body()).isEqualTo("Bonjour");
        assertThat(blocks.get(1).index()).isEqualTo(1);
        assertThat(blocks.get(1).kind()).isEqualTo(MessageBlockSend.Kind.MESSAGE);
        assertThat(blocks.get(1).subject()).isNull();
    }

    @Test
    @DisplayName("la balise porte la clé d'attente ; « À : » et « Objet : » se lisent dans les deux ordres")
    void attributesAndHeaders() {
        String content = "```mail attente=acces-forge\n**À :** Zahi\nObjet : Compte forge\n\nBonjour Zahi\n```\n"
                + "```Message attente=\"vpn\"\nObjet : VPN\nÀ : Léa\nCorps\n```";

        List<MessageBlocks.Block> blocks = MessageBlocks.parse(content);

        assertThat(blocks).hasSize(2);
        assertThat(blocks.get(0).attenteKey()).isEqualTo("acces-forge");
        assertThat(blocks.get(0).recipient()).isEqualTo("Zahi");
        assertThat(blocks.get(0).subject()).isEqualTo("Compte forge");
        assertThat(blocks.get(0).body()).isEqualTo("Bonjour Zahi");
        assertThat(blocks.get(1).kind()).isEqualTo(MessageBlockSend.Kind.MESSAGE);
        assertThat(blocks.get(1).attenteKey()).isEqualTo("vpn");
        assertThat(blocks.get(1).recipient()).isEqualTo("Léa");
        assertThat(blocks.get(1).subject()).isEqualTo("VPN");
    }

    @Test
    @DisplayName("un bloc non clos (en cours d'écriture) ou un texte vide ne donnent rien")
    void unclosedOrEmpty() {
        assertThat(MessageBlocks.parse("```mail\nObjet : x\nBonjour")).isEmpty();
        assertThat(MessageBlocks.parse(null)).isEmpty();
        assertThat(MessageBlocks.parse("")).isEmpty();
    }

    @Test
    @DisplayName("sans en-tête, le corps est le contenu inchangé")
    void noHeaders() {
        List<MessageBlocks.Block> blocks = MessageBlocks.parse("```email\nBonjour,\n\nmerci\n```");

        assertThat(blocks).singleElement().satisfies(block -> {
            assertThat(block.subject()).isNull();
            assertThat(block.recipient()).isNull();
            assertThat(block.attenteKey()).isNull();
            assertThat(block.body()).isEqualTo("Bonjour,\n\nmerci");
        });
    }
}
