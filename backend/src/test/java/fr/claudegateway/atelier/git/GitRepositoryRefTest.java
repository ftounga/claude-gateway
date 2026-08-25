package fr.claudegateway.atelier.git;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import fr.claudegateway.git.InvalidGitBranchException;
import fr.claudegateway.git.InvalidGitRepositoryException;

/**
 * Vérifie le parsing d'URL de dépôt et la validation de branche (F-31 / SF-31-02). Ces contrôles sont
 * <b>hors-ligne</b> : une saisie invalide doit être refusée sans qu'aucun appel réseau ni aucune
 * écriture n'ait lieu.
 */
class GitRepositoryRefTest {

    @Test
    void parsesCanonicalRepositoryUrl() {
        GitRepositoryRef ref = GitRepositoryRef.parse("https://github.com/octocat/hello-world");

        assertThat(ref.owner()).isEqualTo("octocat");
        assertThat(ref.repo()).isEqualTo("hello-world");
        assertThat(ref.fullName()).isEqualTo("octocat/hello-world");
        assertThat(ref.cloneUrl()).isEqualTo("https://github.com/octocat/hello-world");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://github.com/octocat/hello-world.git",
            "https://github.com/octocat/hello-world/",
            "https://www.github.com/octocat/hello-world",
            "  https://github.com/octocat/hello-world  ",
    })
    void acceptsCommonVariants(String url) {
        assertThat(GitRepositoryRef.parse(url).fullName()).isEqualTo("octocat/hello-world");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://github.com/octocat/hello-world",
            "https://gitlab.com/octocat/hello-world",
            "https://github.com/octocat",
            "https://github.com/octocat/hello/tree/main",
            "git@github.com:octocat/hello-world.git",
            "https://github.com.evil.example/octocat/hello",
            "",
            "   ",
    })
    void rejectsAnythingElse(String url) {
        assertThatThrownBy(() -> GitRepositoryRef.parse(url))
                .isInstanceOf(InvalidGitRepositoryException.class);
    }

    @Test
    void rejectsUrlLongerThanColumn() {
        String url = "https://github.com/octocat/" + "a".repeat(600);

        assertThatThrownBy(() -> GitRepositoryRef.parse(url))
                .isInstanceOf(InvalidGitRepositoryException.class);
    }

    @Test
    void acceptsAndTrimsValidBranch() {
        assertThat(GitRepositoryRef.requireValidBranch("  feat/atelier  ")).isEqualTo("feat/atelier");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            "   ",
            "-force",
            "/absolute",
            "trailing/",
            "back..track",
            "espace interdit",
            "point;virgule",
    })
    void rejectsInvalidBranch(String branch) {
        assertThatThrownBy(() -> GitRepositoryRef.requireValidBranch(branch))
                .isInstanceOf(InvalidGitBranchException.class);
    }
}
