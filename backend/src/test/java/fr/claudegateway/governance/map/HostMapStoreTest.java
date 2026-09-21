package fr.claudegateway.governance.map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import fr.claudegateway.governance.GovernanceFileKind;
import fr.claudegateway.governance.GovernanceHostFiles;
import fr.claudegateway.governance.GovernanceHostFiles.HostFileRead;
import fr.claudegateway.governance.GovernanceHostFiles.Presence;
import fr.claudegateway.governance.GovernanceHostRef;
import fr.claudegateway.governance.GovernanceMapDestinations;
import fr.claudegateway.governance.GovernancePackageFile;

/**
 * Le magasin de carte (F-136 / SF-136-01).
 *
 * <p>Ce qu'il protège avant tout : <b>un échec de lecture ne détruit rien</b>. Une carte à moitié
 * effacée serait pire qu'une carte un peu ancienne — et c'est le savoir d'un client.</p>
 */
class HostMapStoreTest {

    private final GovernanceMapDestinations destinations = mock(GovernanceMapDestinations.class);
    private final GovernanceHostFiles hostFiles = mock(GovernanceHostFiles.class);
    private final HostMapFileRepository files = mock(HostMapFileRepository.class);

    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final GovernanceHostRef host = GovernanceHostRef.of(hostId);

    private HostMapStore store;

    @BeforeEach
    void setUp() {
        store = new HostMapStore(destinations, hostFiles, files);
        when(hostFiles.supports(any())).thenReturn(true);
        when(files.findByUserIdAndHostIdAndPath(any(), any(), any())).thenReturn(Optional.empty());
        when(files.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void expectMap(String... paths) {
        Map<String, GovernancePackageFile> expected = new LinkedHashMap<>();
        for (String path : paths) {
            expected.put(path, GovernancePackageFile.builder().path(path)
                    .kind(GovernanceFileKind.MAP).build());
        }
        when(destinations.filesOf(userId, host)).thenReturn(expected);
    }

    private void machineSays(String path, String content) {
        when(hostFiles.read(userId, host, path))
                .thenReturn(new HostFileRead(Presence.PRESENT, content, false));
    }

    @Test
    @DisplayName("range la carte, fichier par fichier, avec ses sections et ses faits")
    void storesTheMapFileByFile() {
        expectMap("acces.md");
        machineSays("acces.md", """
                # Accès

                ## Bastions
                - lzi, constaté le 2026-09-14, source : équipe socle

                ## Coffres
                - CyberArk, constaté le 2026-09-15, source : RSSI
                """);

        assertThat(store.refresh(userId, host)).isEqualTo(1);

        ArgumentCaptor<HostMapFile> saved = ArgumentCaptor.forClass(HostMapFile.class);
        verify(files).save(saved.capture());
        HostMapFile file = saved.getValue();
        assertThat(file.getUserId()).isEqualTo(userId);
        assertThat(file.getHostId()).isEqualTo(hostId);
        assertThat(file.getTitle()).isEqualTo("Accès");
        assertThat(file.getSections()).isEqualTo("Bastions\nCoffres");
        assertThat(file.getFacts()).isEqualTo(2);
        assertThat(file.getContent()).contains("CyberArk");
        assertThat(file.getDigest()).isNotBlank();
    }

    @Test
    @DisplayName("une machine muette ne détruit RIEN : la copie précédente reste")
    void asilentMachineDestroysNothing() {
        expectMap("acces.md", "reseau.md");
        when(hostFiles.read(userId, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.UNREACHABLE, null, false));

        assertThat(store.refresh(userId, host)).isZero();

        verify(files, never()).save(any());
        // Et on ne relance pas cinq délais pour apprendre cinq fois la même chose.
        verify(hostFiles, never()).read(userId, host, "reseau.md");
    }

    @Test
    @DisplayName("un fichier illisible n'empêche pas de ranger les autres")
    void anUnreadableFileDoesNotStopTheOthers() {
        expectMap("acces.md", "reseau.md");
        when(hostFiles.read(userId, host, "acces.md"))
                .thenReturn(new HostFileRead(Presence.UNKNOWN, null, false));
        machineSays("reseau.md", "# Réseau\n\n## Proxy\n- Zscaler, constaté le 2026-09-16\n");

        assertThat(store.refresh(userId, host)).isEqualTo(1);
    }

    @Test
    @DisplayName("deux rafraîchissements rapprochés ne lisent la machine qu'une fois")
    void twoQuickRefreshesReadTheMachineOnce() {
        expectMap("acces.md");
        machineSays("acces.md", "# Accès\n\n## Bastions\n- lzi\n");

        store.refresh(userId, host);
        store.refresh(userId, host);

        verify(hostFiles, times(1)).read(userId, host, "acces.md");
    }

    @Test
    @DisplayName("un contenu inchangé n'est pas réécrit, seule la date d'observation bouge")
    void unchangedContentIsNotRewritten() {
        expectMap("acces.md");
        String content = "# Accès\n\n## Bastions\n- lzi\n";
        machineSays("acces.md", content);
        HostMapFile existing = HostMapFile.builder()
                .userId(userId).hostId(hostId).path("acces.md").title("Ancien titre")
                .sections("Ancienne section").facts(99)
                .digest(digestOf(content))
                .build();
        when(files.findByUserIdAndHostIdAndPath(userId, hostId, "acces.md"))
                .thenReturn(Optional.of(existing));

        store.refresh(userId, host);

        // Le titre n'a pas été recalculé : l'empreinte a suffi à conclure.
        assertThat(existing.getTitle()).isEqualTo("Ancien titre");
        assertThat(existing.getObservedAt()).isNotNull();
    }

    @Test
    @DisplayName("un poste sans mémoire ne déclenche aucun appel machine")
    void ahostWithoutMemoryTouchesNothing() {
        when(destinations.filesOf(userId, host)).thenReturn(Map.of());

        assertThat(store.refresh(userId, host)).isZero();

        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("le poste « Hébergé » n'a pas de racine : rien n'est lu")
    void thehostedWorkspaceHasNoRoot() {
        when(hostFiles.supports(any())).thenReturn(false);

        assertThat(store.refresh(userId, host)).isZero();

        verify(destinations, never()).filesOf(any(), any());
        verify(hostFiles, never()).read(any(), any(), any());
    }

    @Test
    @DisplayName("la lecture porte toujours l'utilisateur ET le poste")
    void readsAlwaysCarryBothUserAndHost() {
        // L'isolation de cette table tient à ce couple : aucune lecture ne peut l'omettre.
        store.filesOf(userId, hostId);

        verify(files).findByUserIdAndHostIdOrderByPathAsc(eq(userId), eq(hostId));
        assertThat(store.filesOf(null, hostId)).isEmpty();
        assertThat(store.filesOf(userId, null)).isEmpty();
    }

    private static String digestOf(String content) {
        try {
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            return java.util.HexFormat.of()
                    .formatHex(sha.digest(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
