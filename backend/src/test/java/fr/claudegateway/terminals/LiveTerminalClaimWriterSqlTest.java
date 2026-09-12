package fr.claudegateway.terminals;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * <b>La forme de l'écriture</b> qui prend une place (F-78 / SF-78-01).
 *
 * <p>Ce test ne touche aucune base — et c'est précisément son intérêt. La production tourne sur
 * PostgreSQL, les tests d'intégration sur H2 : le dialecte de production pourrait partir à la
 * dérive sans que rien ne rougisse jamais. Ce test épingle l'instruction que chaque moteur
 * recevra.</p>
 */
class LiveTerminalClaimWriterSqlTest {

    @Test
    void postgresAbsorbsTheConflictInTheStatement() {
        // C'EST LE CORRECTIF : le conflit sur (user_id, session_id) ne lève plus rien, il rend zéro
        // ligne. Sans cette clause, deux battements du même onglet font un 500 en production.
        assertThat(LiveTerminalClaimWriter.insertSql("PostgreSQL"))
                .endsWith(" on conflict (user_id, session_id) do nothing");
    }

    @Test
    void thePostgresProductNameIsRecognisedHoweverItIsWritten() {
        assertThat(LiveTerminalClaimWriter.isPostgres("PostgreSQL")).isTrue();
        assertThat(LiveTerminalClaimWriter.isPostgres("postgresql")).isTrue();
        // L'image de production est pgvector/pgvector:pg16 — elle se nomme toujours PostgreSQL.
        assertThat(LiveTerminalClaimWriter.isPostgres("PostgreSQL 16.4 (Debian)")).isTrue();
    }

    @Test
    void otherEnginesInsertPlainly() {
        // H2 ne sait écrire AUCUN upsert atomique : ni ON CONFLICT, ni un MERGE qui préserverait
        // opened_at et l'aperçu. Il reçoit donc l'insertion nue, et son doublon est lu comme
        // « la place est déjà prise » — ce qu'il dit exactement.
        assertThat(LiveTerminalClaimWriter.insertSql("H2")).doesNotContain("on conflict");
        assertThat(LiveTerminalClaimWriter.insertSql(null)).doesNotContain("on conflict");
    }

    @Test
    void theStatementWritesThePlaceAndItsPreviewAtOnce() {
        // L'aperçu de F-76 voyage avec la place : une seule écriture, pas deux. Dix colonnes,
        // dix paramètres — un décalage ici écrirait l'aperçu dans la mauvaise colonne.
        String sql = LiveTerminalClaimWriter.insertSql("H2");
        assertThat(sql).contains("id, user_id, workspace_id, session_id, opened_at, last_seen_at,"
                + " activity, activity_detail, preview_lines, activity_at");
        assertThat(sql).contains("values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)");
    }

    @Test
    void theConflictTargetIsNamedRatherThanLeftToTheEngine() {
        // Un ON CONFLICT sans cible absorberait AUSSI une collision de clef primaire — laquelle
        // serait une vraie anomalie, à voir et non à taire.
        assertThat(LiveTerminalClaimWriter.ON_CONFLICT).contains("(user_id, session_id)");
    }
}
