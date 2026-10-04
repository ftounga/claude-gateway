package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.governance.map.index.HostMapFact;
import fr.claudegateway.governance.map.index.HostMapSearch;

/** Le bloc de faits du tour à partir de l'index (F-174 / SF-174-03, D6). */
class HostMapFactsBlockTest {

    private static HostMapSearch.Hit hit(String text) {
        return new HostMapSearch.Hit(HostMapFact.builder().id(UUID.randomUUID()).path("acces.md")
                .heading("Bastions").lineNo(3).text(text).kind("FAIT").build(), HostMapSearch.Reason.LEXICAL);
    }

    @Test
    @DisplayName("même en-tête que F-137, et la section en plus du fichier")
    void citesFileAndSection() {
        String block = HostMapFactsBlock.render(List.of(hit("- lzi ouvert")), null, 0, 6_000);
        assertThat(block).startsWith(HostFactLookup.HEADER);
        assertThat(block).contains("- lzi ouvert  [acces.md § Bastions]\n");
    }

    @Test
    @DisplayName("un fait trop vieux est marqué, et la consigne de péremption suit")
    void staleFactsAreMarked() {
        String block = HostMapFactsBlock.render(List.of(hit("- lzi ouvert, constaté le 2026-01-01")),
                LocalDate.parse("2026-10-04"), 120, 6_000);
        assertThat(block).contains(HostFactLookup.STALE_NOTICE).contains(HostFactLookup.STALE_MARK);
    }

    @Test
    @DisplayName("la borne de caractères ne coupe jamais un fait, et le dit")
    void neverCutsAFact() {
        List<HostMapSearch.Hit> hits = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            hits.add(hit("- fait numéro " + i + " " + "x".repeat(200)));
        }
        String block = HostMapFactsBlock.render(hits, null, 0, 2_000);
        assertThat(block.length()).isLessThanOrEqualTo(2_000);
        assertThat(block).endsWith(HostMapFactsBlock.MORE_NOTICE);
        assertThat(block.split("\n")).filteredOn(l -> l.startsWith("- "))
                .allMatch(l -> l.endsWith("[acces.md § Bastions]"));
        assertThat(HostMapFactsBlock.render(List.of(), null, 0, 6_000)).isNull();
    }
}
