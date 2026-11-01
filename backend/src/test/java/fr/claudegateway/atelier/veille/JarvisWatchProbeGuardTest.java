package fr.claudegateway.atelier.veille;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** F-230 / SF-230-01 (D5) — une sonde est en lecture seule et ne porte aucun secret en clair. */
class JarvisWatchProbeGuardTest {

    private static String problem(String text) {
        return JarvisWatchProbeGuard.problemIn(text, "la commande").orElse(null);
    }

    @Test
    void aReadOnlyCommandReadingItsTokenFromTheHostPasses() {
        assertThat(problem("curl -s -H \"Authorization: Bearer $JIRA_TOKEN\" "
                + "'https://x.atlassian.net/rest/api/3/search/jql?jql=project=AGENOR' | jq -c '[.issues[]]'"))
                .isNull();
        assertThat(problem("TOKEN=$(cat ~/.config/jira-token) python3 .atelier/veilles/agenor.py")).isNull();
        assertThat(JarvisWatchProbeGuard.problemIn("token = os.environ[\"JIRA_TOKEN\"]\n"
                + "r = requests.get(url, headers={'Authorization': 'Bearer ' + token})", "le script")).isEmpty();
    }

    @Test
    void aLiteralTokenIsRefusedByNatureNeverByValue() {
        String bearer = problem("curl -H 'Authorization: Bearer abcdefghijklmnop0123456789' https://x");
        assertThat(bearer).contains("jeton d'authentification").doesNotContain("abcdefghijklmnop");
        assertThat(problem("curl -u me@x.fr:ATATT3xFfGF0abcdefghijklmnopqrstuvwxyz https://x"))
                .contains("en clair");
        assertThat(problem("export GH=ghp_abcdefghijklmnopqrstuvwxyz0123456789")).contains("jeton GitHub");
        assertThat(JarvisWatchProbeGuard.problemIn("API_KEY = \"a1b2c3d4e5f6g7h8i9j0\"", "le script"))
                .hasValueSatisfying(p -> assertThat(p).startsWith("Le script contient un secret"));
    }

    @Test
    void writingMethodsAreRefused() {
        assertThat(problem("curl -X DELETE https://x/rest/api/3/issue/1")).contains("DELETE");
        assertThat(problem("curl --request PUT https://x/issue/1")).contains("PUT");
        assertThat(problem("curl -X POST https://x/rest/api/3/issue -d '{}'")).contains("POST");
        assertThat(problem("curl https://x/rest/api/3/issue -d '{\"a\":1}'")).contains("curl -d");
        assertThat(JarvisWatchProbeGuard.problemIn("requests.post(url, json=body)", "le script")).isPresent();
        assertThat(JarvisWatchProbeGuard.problemIn("Invoke-RestMethod -Method Patch -Uri $u", "le script")).isPresent();
    }

    @Test
    void graphqlPostWithoutMutationIsARead() {
        assertThat(problem("curl -X POST https://api.monday.com/v2 -H \"Authorization: $MONDAY\" "
                + "-d '{\"query\":\"{ boards(ids: 1) { items { id name } } }\"}'")).isNull();
        assertThat(problem("curl -X POST https://api.monday.com/v2 -d '{\"query\":\"mutation { archive_item(id: 1) "
                + "{ id } }\"}'")).contains("mutation");
    }

    @Test
    void scriptReferencesAreDetected() {
        assertThat(JarvisWatchProbeGuard.referencesScript("bash .atelier/veilles/agenor.sh")).isTrue();
        assertThat(JarvisWatchProbeGuard.referencesScript("python3 probe.py --board 3")).isTrue();
        assertThat(JarvisWatchProbeGuard.referencesScript("curl -s https://x/search | jq -c .")).isFalse();
    }
}
