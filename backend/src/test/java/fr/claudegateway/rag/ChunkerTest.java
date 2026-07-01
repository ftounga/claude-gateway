package fr.claudegateway.rag;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

/**
 * Tests unitaires du découpage (F-06 / SF-06-01) : taille/overlap, offsets, textes limites.
 */
class ChunkerTest {

    private Chunker chunkerWith(int maxTokens, int overlap) {
        return new Chunker(new RagProperties("noop", new RagProperties.Chunk(maxTokens, overlap)));
    }

    @Test
    void blankTextProducesNoChunks() {
        Chunker chunker = chunkerWith(400, 50);
        assertThat(chunker.chunk(null)).isEmpty();
        assertThat(chunker.chunk("   \n\t ")).isEmpty();
    }

    @Test
    void shortTextIsASingleChunk() {
        Chunker chunker = chunkerWith(400, 50);
        List<ChunkText> chunks = chunker.chunk("Bonjour le monde");
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).index()).isZero();
        assertThat(chunks.get(0).text()).isEqualTo("Bonjour le monde");
        assertThat(chunks.get(0).charStart()).isZero();
        assertThat(chunks.get(0).charEnd()).isEqualTo(16);
    }

    @Test
    void windowsSlideWithOverlapAndKeepOffsets() {
        // 10 mots "w0..w9", fenêtre de 4, overlap 2 => step 2 => fenêtres [0-3],[2-5],[4-7],[6-9]
        String text = IntStream.range(0, 10).mapToObj(i -> "w" + i).collect(Collectors.joining(" "));
        Chunker chunker = chunkerWith(4, 2);
        List<ChunkText> chunks = chunker.chunk(text);

        assertThat(chunks).hasSize(4);
        assertThat(chunks).extracting(ChunkText::index).containsExactly(0, 1, 2, 3);
        assertThat(chunks.get(0).text()).isEqualTo("w0 w1 w2 w3");
        assertThat(chunks.get(1).text()).isEqualTo("w2 w3 w4 w5");
        assertThat(chunks.get(3).text()).isEqualTo("w6 w7 w8 w9");
        // Offsets cohérents : substring du texte source redonne le chunk.
        for (ChunkText c : chunks) {
            assertThat(text.substring(c.charStart(), c.charEnd())).isEqualTo(c.text());
        }
    }

    @Test
    void lastWindowNotDuplicatedByOverlap() {
        // 5 mots, fenêtre 4, overlap 2 => step 2 => [0-3] puis [2-4] (fin), pas de fenêtre vide au-delà.
        String text = "a b c d e";
        Chunker chunker = chunkerWith(4, 2);
        List<ChunkText> chunks = chunker.chunk(text);
        assertThat(chunks).hasSize(2);
        assertThat(chunks.get(1).text()).isEqualTo("c d e");
    }
}
