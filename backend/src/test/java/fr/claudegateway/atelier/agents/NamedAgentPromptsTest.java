package fr.claudegateway.atelier.agents;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** F-182 / SF-182-02 — ce que la gateway dit autour d'un agent nommé. */
class NamedAgentPromptsTest {

    private static NamedAgent agent(String name, String content) {
        return NamedAgentParser.parse(name, ".claude/agents/" + name + ".md", NamedAgent.SUJET, content);
    }

    @Test
    void theSystemFramesTheInstructionsWithTheRealConstraints() {
        NamedAgent relecteur = agent("relecteur", "Relis tout.");

        String read = NamedAgentPrompts.system(relecteur, NamedAgentPrompts.Mode.EXPLORE, List.of("read_file", "grep"));
        String write = NamedAgentPrompts.system(relecteur, NamedAgentPrompts.Mode.TASK, List.of("read_file", "bash"));

        assertThat(read).startsWith("Tu es l'agent nommé « relecteur »").contains("Relis tout.")
                .contains("Tu ne peux que LIRE").contains("Tes seuls outils : read_file, grep.");
        assertThat(write).contains("worktree git ISOLÉ").contains("read_file, bash").doesNotContain("Tu ne peux que LIRE");
    }

    @Test
    void theCatalogListsUsableAgentsAndIsCapped() {
        List<NamedAgent> agents = new ArrayList<>();
        agents.add(agent("cassé", "---\ndescription: x\n---\n"));
        for (int i = 0; i < NamedAgentPrompts.MAX_ANNOUNCED + 2; i++) {
            agents.add(agent("a" + i, "---\ndescription: d" + i + "\n---\nFais."));
        }

        String catalog = NamedAgentPrompts.catalog(agents);

        assertThat(catalog).startsWith(NamedAgentPrompts.CATALOG_HEADER).contains("- a0 (sujet, lecture → explore) : d0")
                .doesNotContain("cassé").contains("… et 2 autre(s) agent(s)");
        assertThat(NamedAgentPrompts.catalog(List.of())).isEmpty();
    }

    @Test
    void theCatalogSaysAJustifiedModel() {
        String catalog = NamedAgentPrompts.catalog(List.of(
                agent("t", "---\noutils: ecriture\nmodele: claude-sonnet-5\njustification: x\n---\nFais.")));
        assertThat(catalog).contains("- t (sujet, écriture → task, modèle claude-sonnet-5)");
    }

    @Test
    void invocationParsing() {
        assertThat(NamedAgentPrompts.invocationOf("/agent Relecteur relis la MR"))
                .contains(new NamedAgentPrompts.Invocation("relecteur", "relis la MR"));
        assertThat(NamedAgentPrompts.invocationOf("/agent")).contains(new NamedAgentPrompts.Invocation("", ""));
        assertThat(NamedAgentPrompts.invocationOf("/agents liste")).isEmpty();
        assertThat(NamedAgentPrompts.invocationOf("bonjour /agent x")).isEmpty();
    }

    @Test
    void invocationBlockWhenDelegationIsClosed() {
        String block = NamedAgentPrompts.invocationBlock(Optional.empty(),
                new NamedAgentPrompts.Invocation("x", ""), List.of(), false, false);
        assertThat(block).contains("délégation à un sous-agent est fermée").endsWith(NamedAgentPrompts.INVOKED_FOOTER);
    }

    @Test
    void aWriterWithoutPosteFallsBackToExploreAndSaysSo() {
        NamedAgent testeur = agent("testeur", "---\noutils: ecriture\n---\nTeste.");
        String block = NamedAgentPrompts.invocationBlock(Optional.of(testeur),
                new NamedAgentPrompts.Invocation("testeur", "teste"), List.of(testeur), true, false);
        assertThat(block).contains("appelle `explore` avec agent: \"testeur\"").contains("Pas de poste connecté");
    }
}
