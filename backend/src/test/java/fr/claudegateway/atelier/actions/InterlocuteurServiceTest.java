package fr.claudegateway.atelier.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import fr.claudegateway.atelier.Workspace;
import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.radar.RadarPerson;
import fr.claudegateway.radar.RadarPersonRepository;

/**
 * Les interlocuteurs (F-180 / SF-180-03) : une personne par graphie normalisée, le lien au Radar, la
 * fusion proposée puis appliquée, « deux personnes », et rien hors du poste.
 */
class InterlocuteurServiceTest {

    private final InterlocuteurRepository repository = mock(InterlocuteurRepository.class);
    private final TerminalActionRepository actions = mock(TerminalActionRepository.class);
    private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
    private final RadarPersonRepository radar = mock(RadarPersonRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-16T10:00:00Z"), ZoneOffset.UTC);
    private final UUID userId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID workspaceId = UUID.randomUUID();
    private final List<Interlocuteur> stored = new ArrayList<>();
    private InterlocuteurService service;

    @BeforeEach
    void setUp() {
        service = new InterlocuteurService(repository, actions, workspaces, radar, clock);
        Workspace terminal = new Workspace();
        terminal.setId(workspaceId);
        terminal.setHostId(hostId);
        when(workspaces.findByIdAndUserId(workspaceId, userId)).thenReturn(Optional.of(terminal));
        when(repository.save(any())).thenAnswer(i -> {
            Interlocuteur it = i.getArgument(0);
            if (it.getId() == null) {
                it.setId(UUID.randomUUID());
                stored.add(it);
            }
            return it;
        });
        when(repository.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)).thenAnswer(i -> stored);
        when(repository.findByUserIdAndHostIdAndNormalizedName(eq(userId), eq(hostId), anyString()))
                .thenAnswer(i -> stored.stream().filter(s -> s.getNormalizedName().equals(i.getArgument(2))).findFirst());
        when(repository.findByIdAndUserIdAndHostId(any(), eq(userId), eq(hostId)))
                .thenAnswer(i -> stored.stream().filter(s -> s.getId().equals(i.getArgument(0))).findFirst());
    }

    @Test
    @DisplayName("normalisation : accents, casse, ponctuation")
    void normalize() {
        assertThat(InterlocuteurService.normalize("  Rémi  DUPONT ")).isEqualTo("remi dupont");
        assertThat(InterlocuteurService.normalize("R. Dupont")).isEqualTo("r dupont");
    }

    @Test
    @DisplayName("« Rémi », « remi », « RÉMI » : un seul interlocuteur")
    void sameNormalizedNameIsOnePerson() {
        var a = service.resolve(userId, hostId, "Rémi").orElseThrow();
        var b = service.resolve(userId, hostId, "remi").orElseThrow();
        var c = service.resolve(userId, hostId, "RÉMI").orElseThrow();
        assertThat(b.getId()).isEqualTo(a.getId());
        assertThat(c.getId()).isEqualTo(a.getId());
        assertThat(stored).hasSize(1);
    }

    @Test
    @DisplayName("sans poste ou sans nom : rien")
    void nothingWithoutHostOrName() {
        assertThat(service.resolve(userId, null, "Rémi")).isEmpty();
        assertThat(service.resolve(userId, hostId, "  ")).isEmpty();
    }

    @Test
    @DisplayName("le Radar connaît la personne : reliée, son intitulé devient le rôle")
    void linksRadar() {
        RadarPerson p = RadarPerson.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .displayName("Rémi Dupont").jobTitle("Responsable réseau").build();
        when(radar.findByUserIdAndHostIdOrderByDisplayNameAsc(userId, hostId)).thenReturn(List.of(p));
        var i = service.resolve(userId, hostId, "remi dupont").orElseThrow();
        assertThat(i.getRadarPersonId()).isEqualTo(p.getId());
        assertThat(i.getRole()).isEqualTo("Responsable réseau");
    }

    @Test
    @DisplayName("similarité : prolongement, initiales, une lettre près ; rien entre deux inconnus")
    void similarity() {
        assertThat(InterlocuteurService.similarity("remi", "remi dupont")).isEqualTo(InterlocuteurService.MergeReason.PREFIX);
        assertThat(InterlocuteurService.similarity("r dupont", "remi dupont")).isEqualTo(InterlocuteurService.MergeReason.INITIALS);
        assertThat(InterlocuteurService.similarity("remy", "remi")).isEqualTo(InterlocuteurService.MergeReason.TYPO);
        assertThat(InterlocuteurService.similarity("karim", "zahi")).isNull();
        assertThat(InterlocuteurService.similarity("remi dupont", "remi martin")).isNull();
    }

    @Test
    @DisplayName("fusion proposée puis appliquée : attentes reportées, graphie en variante")
    void suggestAndMerge() {
        var shortName = service.resolve(userId, hostId, "Rémi").orElseThrow();
        shortName.setCreatedAt(OffsetDateTime.now(clock).minusDays(3));
        var full = service.resolve(userId, hostId, "Rémi Dupont").orElseThrow();

        var suggestions = service.suggestions(userId, workspaceId);
        assertThat(suggestions).singleElement().satisfies(s -> {
            assertThat(s.keep().id()).isEqualTo(full.getId());
            assertThat(s.merge().id()).isEqualTo(shortName.getId());
        });

        service.merge(userId, workspaceId, shortName.getId(), full.getId());
        verify(actions).repointInterlocuteur(userId, shortName.getId(), full.getId());
        verify(repository).delete(shortName);
        assertThat(full.answersTo("remi")).isTrue();
    }

    @Test
    @DisplayName("« deux personnes » : plus proposée")
    void distinct() {
        var a = service.resolve(userId, hostId, "Rémi").orElseThrow();
        var b = service.resolve(userId, hostId, "Rémi Dupont").orElseThrow();
        service.distinct(userId, workspaceId, a.getId(), b.getId());
        assertThat(service.suggestions(userId, workspaceId)).isEmpty();
    }

    @Test
    @DisplayName("renommage vers un nom déjà pris : refusé ; bornes")
    void renameCollision() {
        var a = service.resolve(userId, hostId, "Rémi").orElseThrow();
        service.resolve(userId, hostId, "Karim").orElseThrow();
        assertThatThrownBy(() -> service.update(userId, workspaceId, a.getId(),
                new InterlocuteurService.UpdateRequest("karim", null, null, null)))
                .isInstanceOf(InvalidTerminalActionException.class).hasMessageContaining("fusion");
        assertThatThrownBy(() -> service.update(userId, workspaceId, a.getId(),
                new InterlocuteurService.UpdateRequest(" ", null, null, null)))
                .isInstanceOf(InvalidTerminalActionException.class);
        var view = service.update(userId, workspaceId, a.getId(),
                new InterlocuteurService.UpdateRequest("Rémi Dupont", "Réseau", "Infra", null));
        assertThat(view.role()).isEqualTo("Réseau");
        assertThat(a.answersTo("remi")).isTrue(); // l'ancienne graphie reste reconnue
    }

    @Test
    @DisplayName("hors poste : introuvable ; fusion avec soi-même : refusée")
    void isolation() {
        var a = service.resolve(userId, hostId, "Rémi").orElseThrow();
        assertThatThrownBy(() -> service.list(UUID.randomUUID(), workspaceId))
                .isInstanceOf(TerminalActionNotFoundException.class);
        assertThatThrownBy(() -> service.merge(userId, workspaceId, a.getId(), a.getId()))
                .isInstanceOf(InvalidTerminalActionException.class);
        assertThatThrownBy(() -> service.merge(userId, workspaceId, a.getId(), UUID.randomUUID()))
                .isInstanceOf(TerminalActionNotFoundException.class);
    }

    @Test
    @DisplayName("reprise au démarrage : les attentes nommées sont rattachées, une fois")
    void backfill() {
        TerminalAction a = TerminalAction.builder().id(UUID.randomUUID()).userId(userId).hostId(hostId)
                .person("Rémi").status(TerminalActionStatus.A_FAIRE).build();
        when(actions.findUnlinkedWithPerson(any(Pageable.class))).thenReturn(List.of(a));
        int linked = new InterlocuteurBackfill(actions, service, true).run();
        assertThat(linked).isEqualTo(1);
        assertThat(a.getInterlocuteurId()).isEqualTo(stored.get(0).getId());
    }
}
