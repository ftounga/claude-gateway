package fr.claudegateway.governance.map;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernanceMapDigest;
import fr.claudegateway.governance.GovernancePackageFile;
import fr.claudegateway.governance.dto.GovernanceMapSectionView;

/**
 * <b>Le magasin de carte</b> : la copie de travail, côté gateway, de ce que la machine d'un client
 * sait d'elle-même (F-136 / SF-136-01).
 *
 * <p><b>Pourquoi une copie.</b> La carte vit sur la machine. La lire au <b>début</b> d'un tour
 * coûterait six allers-retours runner avant le premier mot du modèle — deux secondes ajoutées à
 * chaque demande, pour un savoir qui ne change qu'<b>en fin</b> de tour. On la relit donc une fois
 * la réponse partie : le tour en cours ne paie rien, le suivant trouve tout prêt.</p>
 *
 * <p><b>Rien n'est jamais détruit par un échec.</b> Machine muette, fichier illisible, droits
 * refusés : l'ancienne copie reste et continue de servir. Une carte à moitié effacée serait pire
 * qu'une carte un peu ancienne.</p>
 *
 * <p><b>Étranglé.</b> Une rafale de tours sur le même poste ne déclenche qu'une lecture : au-delà,
 * on sait déjà que rien n'a eu le temps de changer.</p>
 *
 * <p><b>Isolation.</b> Toute lecture et toute écriture portent {@code user_id} <b>et</b>
 * {@code host_id} ; c'est aussi la clé d'unicité en base.</p>
 */
@Service
public class HostMapStore {

    private static final Logger log = LoggerFactory.getLogger(HostMapStore.class);

    /**
     * Délai minimal entre deux lectures d'un même poste.
     *
     * <p>Une carte ne change qu'à la fin d'un tour, et un tour dure bien plus que cela. Ce délai ne
     * protège donc pas contre la perte d'information : il protège la machine du client contre une
     * rafale de lectures quand plusieurs terminaux travaillent en parallèle.</p>
     */
    static final Duration MIN_INTERVAL = Duration.ofSeconds(30);

    /** Borne du contenu gardé par fichier : au-delà, la carte n'est plus une carte. */
    static final int MAX_CONTENT_CHARS = 400_000;

    private final GovernanceMapDestinations destinations;
    private final GovernanceHostFiles hostFiles;
    private final HostMapFileRepository files;

    /** Dernière lecture par poste — en mémoire : perdre cet état ne coûte qu'une lecture de plus. */
    private final Map<UUID, Instant> lastRefresh = new ConcurrentHashMap<>();

    public HostMapStore(GovernanceMapDestinations destinations, GovernanceHostFiles hostFiles,
            HostMapFileRepository files) {
        this.destinations = destinations;
        this.hostFiles = hostFiles;
        this.files = files;
    }

    /**
     * Relit la carte de ce poste et range ce qu'elle dit.
     *
     * <p>Appelée <b>hors du chemin critique</b> d'un tour. Ne lève jamais : un magasin en panne doit
     * coûter un savoir un peu ancien, jamais un tour raté.</p>
     *
     * @return le nombre de fichiers rangés ({@code 0} si rien n'a été lu)
     */
    public int refresh(UUID userId, GovernanceHostRef host) {
        if (userId == null || host == null || host.hostId() == null || !hostFiles.supports(host)) {
            return 0; // Poste « Hébergé » ou appel mal formé : il n'y a pas de racine à lire.
        }
        if (!claimRefresh(host.hostId())) {
            return 0;
        }
        Map<String, GovernancePackageFile> expected;
        try {
            expected = destinations.filesOf(userId, host);
        } catch (RuntimeException ex) {
            log.debug("Carte non rafraîchie, destinations illisibles ({})",
                    ex.getClass().getSimpleName());
            return 0;
        }
        if (expected.isEmpty()) {
            return 0; // Poste sans mémoire : il n'y a pas de carte, et rien à lire sur la machine.
        }

        int stored = 0;
        for (Map.Entry<String, GovernancePackageFile> entry : expected.entrySet()) {
            String path = entry.getKey();
            HostFileRead read;
            try {
                read = hostFiles.read(userId, host, path);
            } catch (RuntimeException ex) {
                log.debug("Fichier de carte non lu ({})", ex.getClass().getSimpleName());
                continue;
            }
            if (read.presence() == Presence.UNREACHABLE) {
                // La machine s'est tue : on ne relance pas cinq délais pour l'apprendre cinq fois,
                // et surtout on ne touche à RIEN — la copie précédente reste et sert.
                break;
            }
            if (read.presence() != Presence.PRESENT) {
                continue; // Absent ou illisible : le suivant peut très bien répondre.
            }
            try {
                store(userId, host.hostId(), path, entry.getValue().getPath(),
                        read.contentOrEmpty());
                stored++;
            } catch (RuntimeException ex) {
                log.warn("Fichier de carte non rangé ({})", ex.getClass().getSimpleName());
            }
        }
        return stored;
    }

    /** La carte rangée de ce poste, fichier par fichier. */
    @Transactional(readOnly = true)
    public List<HostMapFile> filesOf(UUID userId, UUID hostId) {
        if (userId == null || hostId == null) {
            return List.of();
        }
        return files.findByUserIdAndHostIdOrderByPathAsc(userId, hostId);
    }

    // -------------------------------------------------------------- internes

    /**
     * Vrai si l'on prend la main pour rafraîchir ce poste maintenant.
     *
     * <p>Le jeton est posé <b>avant</b> la lecture, pas après : deux tours qui finissent en même
     * temps ne doivent pas partir tous les deux.</p>
     */
    private boolean claimRefresh(UUID hostId) {
        Instant now = Instant.now();
        Instant previous = lastRefresh.get(hostId);
        if (previous != null && Duration.between(previous, now).compareTo(MIN_INTERVAL) < 0) {
            return false;
        }
        lastRefresh.put(hostId, now);
        return true;
    }

    /**
     * Range un fichier.
     *
     * <p><b>Sans {@code @Transactional}</b>, et c'est volontaire : l'annotation serait inopérante
     * ici (appel interne, le proxy ne s'applique pas) et donnerait une garantie fausse. Chaque
     * {@code save} porte la sienne, et il n'y a rien à rendre atomique — un fichier rangé pendant
     * qu'un autre échoue est exactement ce qu'on veut.</p>
     */
    void store(UUID userId, UUID hostId, String path, String fallbackTitle,
            String rawContent) {
        String content = rawContent.length() > MAX_CONTENT_CHARS
                ? rawContent.substring(0, MAX_CONTENT_CHARS)
                : rawContent;
        String digest = digestOf(content);
        HostMapFile existing =
                files.findByUserIdAndHostIdAndPath(userId, hostId, path).orElse(null);
        if (existing != null && digest.equals(existing.getDigest())) {
            // Rien n'a changé : on ne réécrit pas une ligne pour y remettre les mêmes octets.
            existing.setObservedAt(OffsetDateTime.now());
            files.save(existing);
            return;
        }
        GovernanceMapDigest.Digest parsed = GovernanceMapDigest.of(content, fallbackTitle);
        HostMapFile file = existing != null ? existing : HostMapFile.builder()
                .userId(userId).hostId(hostId).path(path).build();
        file.setTitle(parsed.title());
        file.setSections(joinSections(parsed.sections()));
        file.setFacts(parsed.facts());
        file.setContent(content);
        file.setDigest(digest);
        file.setObservedAt(OffsetDateTime.now());
        files.save(file);
    }

    private static String joinSections(List<GovernanceMapSectionView> sections) {
        StringBuilder joined = new StringBuilder();
        for (GovernanceMapSectionView section : sections) {
            if (section.title() == null || section.title().isBlank()) {
                continue;
            }
            if (joined.length() > 0) {
                joined.append('\n');
            }
            joined.append(section.title().strip());
        }
        return joined.toString();
    }

    private static String digestOf(String content) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(sha.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            // SHA-256 est requis par la plateforme : ce chemin n'existe pas en pratique. On rend une
            // empreinte qui ne collera jamais, ce qui force la réécriture — jamais un silence.
            return String.valueOf(content.hashCode());
        }
    }
}
