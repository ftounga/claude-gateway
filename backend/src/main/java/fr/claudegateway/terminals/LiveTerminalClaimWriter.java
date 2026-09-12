package fr.claudegateway.terminals;

import java.sql.DatabaseMetaData;
import java.util.Locale;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.JdbcUtils;
import org.springframework.jdbc.support.MetaDataAccessException;
import org.springframework.stereotype.Component;

/**
 * <b>L'écriture atomique</b> qui prend une place au registre des terminaux vivants (F-78 /
 * SF-78-01).
 *
 * <p><b>Le défaut qu'elle corrige.</b> Jusqu'ici, prendre une place c'était <i>lire</i> puis
 * <i>écrire</i> : chercher la fiche de l'onglet, et l'insérer si elle manquait. Entre les deux,
 * rien ne protégeait. Deux battements du <b>même onglet</b> arrivant ensemble ne trouvaient rien
 * tous les deux et inséraient tous les deux ; le second violait
 * {@code idx_live_terminals_user_session} et l'écran recevait <b>500</b> alors qu'il ne demandait
 * qu'à tenir sa place. F-70 rendait la collision rare (un battement toutes les 30 s) ; F-76 l'a
 * rendue courante en branchant trois sources d'envoi sur le même appel.</p>
 *
 * <p><b>Ce qu'elle fait à la place.</b> Une seule instruction, dont le conflit est absorbé
 * <b>par le moteur</b> : l'insertion réussit, ou elle ne fait rien parce que la place est déjà
 * prise. Aucun verrou applicatif — il ne vaudrait que pour le pod qui répond, et le registre est
 * justement en base parce qu'il y a plusieurs pods. Aucun rattrapage d'exception sur le moteur de
 * production — sur PostgreSQL, une violation de contrainte condamne la transaction entière
 * ({@code 25P02}) et déplacerait le problème au lieu de le résoudre.</p>
 *
 * <p><b>Pourquoi {@code DO NOTHING} et non {@code DO UPDATE}.</b> Parce que le plafond de quatre a
 * besoin de savoir <b>si une place a été créée</b> : c'est le seul cas où l'on peut dépasser.
 * {@code DO UPDATE} rend « une ligne touchée » dans les deux cas et efface précisément la
 * distinction dont dépend le refus ; il faudrait la reconstruire par {@code RETURNING (xmax = 0)},
 * un détail interne de PostgreSQL. {@code DO NOTHING} rend exactement l'information utile :
 * <b>1 = créée, 0 = déjà prise</b>. Le renouvellement, lui, est un {@code UPDATE} porté par le
 * dépôt — portable, et qui ne touche jamais {@code opened_at}.</p>
 *
 * <p><b>Les deux moteurs.</b> C'est le <b>seul</b> endroit du dépôt qui connaisse un dialecte.
 * PostgreSQL (production) absorbe le conflit dans l'instruction. H2 (tests) ne sait écrire aucun
 * upsert atomique — ni {@code ON CONFLICT}, ni un {@code MERGE} qui préserverait {@code opened_at}
 * et l'aperçu — mais il <b>attend</b> la fin de la transaction jumelle avant de signaler le
 * doublon, et, contrairement à PostgreSQL, la transaction y reste utilisable. Sur ces moteurs-là,
 * le doublon est donc lu comme « la place est déjà prise », ce qui est exactement ce qu'il dit.</p>
 */
@Component
public class LiveTerminalClaimWriter {

    private static final Logger log = LoggerFactory.getLogger(LiveTerminalClaimWriter.class);

    /**
     * L'insertion, <b>toutes</b> les colonnes d'une place d'un coup — y compris l'aperçu de F-76 :
     * prendre sa place et dire ce qu'on fait, c'est une seule écriture, pas deux.
     */
    static final String INSERT = "insert into live_terminals"
            + " (id, user_id, workspace_id, session_id, opened_at, last_seen_at,"
            + " activity, activity_detail, preview_lines, activity_at)"
            + " values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    /**
     * Ce que PostgreSQL ajoute : le conflit sur {@code (user_id, session_id)} ne lève rien, il rend
     * zéro ligne. La cible est nommée explicitement — un {@code ON CONFLICT} sans cible absorberait
     * aussi une collision de clef primaire, qui elle serait une vraie anomalie.
     */
    static final String ON_CONFLICT = " on conflict (user_id, session_id) do nothing";

    private final JdbcTemplate jdbcTemplate;
    private final String insertSql;

    public LiveTerminalClaimWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
        this.insertSql = insertSql(productName(jdbcTemplate.getDataSource()));
    }

    /**
     * Prend la place de cet onglet, en <b>une</b> écriture.
     *
     * @param place la fiche à écrire, identifiant compris (l'appelant le connaît : c'est lui qui
     *              devra reconnaître sa propre place au moment d'arbitrer le plafond)
     * @return {@code true} si la place a été <b>créée</b> — le seul cas où le plafond s'arbitre ;
     *         {@code false} si un jumeau du même onglet tenait déjà la place
     */
    public boolean insertIfAbsent(LiveTerminal place) {
        try {
            return jdbcTemplate.update(insertSql,
                    place.getId(),
                    place.getUserId(),
                    place.getWorkspaceId(),
                    place.getSessionId(),
                    place.getOpenedAt(),
                    place.getLastSeenAt(),
                    place.getActivity() == null ? null : place.getActivity().name(),
                    place.getActivityDetail(),
                    place.getPreviewLines(),
                    place.getActivityAt()) == 1;
        } catch (DuplicateKeyException alreadyTaken) {
            // JAMAIS ATTEINT SUR POSTGRESQL : la clause ON CONFLICT y absorbe le conflit avant
            // qu'il ne devienne une exception. Ce repli n'existe que pour les moteurs qui ne
            // savent pas l'écrire — H2, celui des tests. Il n'y est pas un rattrapage douteux :
            // H2 attend le commit du jumeau avant de signaler le doublon, et la transaction y
            // reste utilisable, donc le renouvellement qui suit trouve bien la place du jumeau.
            log.debug("Place déjà prise par un battement jumeau du même onglet — on renouvelle");
            return false;
        }
    }

    /** L'instruction réellement exécutée — lue par les tests, jamais recopiée par eux. */
    String insertSql() {
        return insertSql;
    }

    /**
     * L'instruction pour un moteur donné. Isolée et visible des tests : c'est elle qui empêche le
     * dialecte de production de partir à la dérive sans que rien ne rougisse.
     */
    static String insertSql(String databaseProductName) {
        return isPostgres(databaseProductName) ? INSERT + ON_CONFLICT : INSERT;
    }

    static boolean isPostgres(String databaseProductName) {
        return databaseProductName != null
                && databaseProductName.toLowerCase(Locale.ROOT).contains("postgres");
    }

    /**
     * Le moteur derrière la source de données. Lu <b>une fois</b>, au démarrage : c'est une
     * propriété de l'environnement, pas de l'appel.
     */
    private static String productName(DataSource dataSource) {
        if (dataSource == null) {
            return null;
        }
        try {
            String product = JdbcUtils.extractDatabaseMetaData(dataSource,
                    DatabaseMetaData::getDatabaseProductName);
            log.info("Registre des terminaux vivants : prise de place atomique sur « {} »", product);
            return product;
        } catch (MetaDataAccessException | RuntimeException e) {
            // Un moteur qu'on n'a pas su nommer reçoit l'insertion nue : c'est la forme qui marche
            // partout. On ne devine pas un dialecte.
            log.warn("Moteur de base indéterminé : insertion nue pour le registre des terminaux", e);
            return null;
        }
    }
}
