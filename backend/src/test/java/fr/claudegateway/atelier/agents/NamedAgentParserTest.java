package fr.claudegateway.atelier.agents;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-182 / SF-182-01 — lire et valider un agent nommé (D1, D3). */
class NamedAgentParserTest {

    private static NamedAgent parse(String content) {
        return NamedAgentParser.parse("relecteur", ".claude/agents/relecteur.md", NamedAgent.SUJET, content);
    }

    @Test
    void aFullFrenchHeaderIsRead() {
        NamedAgent agent = parse("""
                ---
                description: Relit un diff avant une MR.
                outils: lecture
                ---
                Tu es le relecteur. Classe tes constats.
                """);

        assertThat(agent.usable()).isTrue();
        assertThat(agent.description()).isEqualTo("Relit un diff avant une MR.");
        assertThat(agent.access()).isEqualTo(NamedAgent.LECTURE);
        assertThat(agent.tools()).containsExactly("read_file", "list_files", "search_files", "grep", "glob");
        assertThat(agent.model()).isNull();
        assertThat(agent.instructions()).isEqualTo("Tu es le relecteur. Classe tes constats.");
        assertThat(agent.warnings()).isEmpty();
    }

    @Test
    void withoutToolsTheAgentOnlyReads() {
        NamedAgent agent = parse("Consigne sans entête.");

        assertThat(agent.access()).isEqualTo(NamedAgent.LECTURE);
        assertThat(agent.tools()).doesNotContain("bash", "write_file", "edit_file");
        assertThat(agent.description()).isEqualTo("Consigne sans entête.");
    }

    @Test
    void writingGivesTheFullSet() {
        NamedAgent agent = parse("---\noutils: écriture\n---\nÉcris les tests.");

        assertThat(agent.access()).isEqualTo(NamedAgent.ECRITURE);
        assertThat(agent.tools()).contains("bash", "write_file", "edit_file", "multi_edit", "read_file");
    }

    @Test
    void claudeCodeKeysAndToolNamesAreTranslated() {
        NamedAgent agent = parse("""
                ---
                name: relecteur
                description: "Relit."
                tools: Read, Grep, Glob, Bash
                ---
                Relis.
                """);

        assertThat(agent.tools()).containsExactly("read_file", "grep", "glob", "bash");
        assertThat(agent.access()).isEqualTo(NamedAgent.ECRITURE);
        assertThat(agent.description()).isEqualTo("Relit.");
    }

    @Test
    void aYamlListAndABracketListAreAccepted() {
        NamedAgent yaml = parse("---\noutils:\n  - read_file\n  - Edit\n---\nFais.");
        NamedAgent bracket = parse("---\noutils: [grep, glob]\n---\nFais.");

        assertThat(yaml.tools()).containsExactly("read_file", "edit_file");
        assertThat(bracket.tools()).containsExactly("grep", "glob");
        assertThat(bracket.access()).isEqualTo(NamedAgent.LECTURE);
    }

    @Test
    void anUnknownToolIsIgnoredWithAWarning() {
        NamedAgent agent = parse("---\noutils: read_file, teleporter\n---\nFais.");

        assertThat(agent.usable()).isTrue();
        assertThat(agent.tools()).containsExactly("read_file");
        assertThat(agent.warnings()).anyMatch(w -> w.contains("teleporter"));
    }

    @Test
    void noRecognisedToolMakesTheAgentUnusable() {
        NamedAgent agent = parse("---\noutils: teleporter\n---\nFais.");

        assertThat(agent.usable()).isFalse();
        assertThat(agent.errors()).anyMatch(e -> e.contains("Aucun outil reconnu"));
    }

    @Test
    void anEmptyInstructionMakesTheAgentUnusable() {
        NamedAgent agent = parse("---\ndescription: rien\n---\n   \n");

        assertThat(agent.usable()).isFalse();
        assertThat(agent.errors()).anyMatch(e -> e.contains("Consigne vide"));
    }

    @Test
    void aJustifiedModelIsKept() {
        NamedAgent agent = parse("---\nmodele: claude-sonnet-5\njustification: lecture courte\n---\nFais.");

        assertThat(agent.model()).isEqualTo("claude-sonnet-5");
        assertThat(agent.justification()).isEqualTo("lecture courte");
        assertThat(agent.warnings()).isEmpty();
    }

    @Test
    void anUnjustifiedModelIsIgnoredAndOpusStays() {
        NamedAgent agent = parse("---\nmodel: claude-haiku-5\n---\nFais.");

        assertThat(agent.model()).isNull();
        assertThat(agent.usable()).isTrue();
        assertThat(agent.warnings()).anyMatch(w -> w.contains("justification"));
    }

    @Test
    void inheritMeansTheForgeModel() {
        NamedAgent agent = parse("---\nmodel: inherit\n---\nFais.");

        assertThat(agent.model()).isNull();
        assertThat(agent.warnings()).isEmpty();
    }

    @Test
    void aMalformedModelIsIgnored() {
        NamedAgent agent = parse("---\nmodele: \"pas un modèle !\"\njustification: x\n---\nFais.");

        assertThat(agent.model()).isNull();
        assertThat(agent.warnings()).anyMatch(w -> w.contains("mal formé"));
    }

    @Test
    void aTooLongInstructionIsTruncated() {
        NamedAgent agent = parse("x".repeat(NamedAgentParser.MAX_INSTRUCTIONS_CHARS + 50));

        assertThat(agent.instructions()).hasSize(NamedAgentParser.MAX_INSTRUCTIONS_CHARS);
        assertThat(agent.warnings()).anyMatch(w -> w.contains("tronquée"));
    }

    @Test
    void theEntryNeverCarriesTheInstructions() {
        NamedAgentEntry entry = parse("---\ndescription: d\n---\nSECRET_DE_CONSIGNE").entry();

        assertThat(entry.toString()).doesNotContain("SECRET_DE_CONSIGNE");
        assertThat(entry.usable()).isTrue();
    }
}
