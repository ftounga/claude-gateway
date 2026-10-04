package fr.claudegateway.governance.map.index;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import fr.claudegateway.governance.map.HostMapFile;
import fr.claudegateway.governance.map.HostMapFileRepository;

/**
 * L'index de la carte, en base (F-174 / SF-174-02) : couche déterministe, lecture du modèle (simulée),
 * incrémentalité par section (D3), isolation et purge (D10).
 */
@SpringBootTest
@ActiveProfiles("test")
class HostMapIndexIntegrationTest {

    @Autowired private HostMapIndexService service;
    @Autowired private HostMapFileRepository files;
    @Autowired private HostMapSectionRepository sections;
    @Autowired private HostMapFactRepository facts;
    @Autowired private HostMapEntityRepository entities;
    @Autowired private HostMapRelationRepository relations;
    @Autowired private HostMapIndexPurge purge;
    @MockitoBean private HostMapSectionExtractor extractor;

    private final UUID alice = UUID.randomUUID();
    private final UUID aliceHost = UUID.randomUUID();
    private final UUID bob = UUID.randomUUID();
    private final UUID bobHost = UUID.randomUUID();

    private static final String MAP = """
            # Plateformes

            ## Comptes AWS
            - compte 123456789012 prod, cluster eks-prod-1, constaté le 2026-09-14
            - piège : le proxy Netskope coupe les websockets vers eks-prod-1

            ## Jetons
            - jeton GitLab périme le 2026-10-09
            """;

    @BeforeEach
    void setUp() {
        facts.deleteAll();
        entities.deleteAll();
        relations.deleteAll();
        sections.deleteAll();
        files.deleteAll();
        reset(extractor);
        when(extractor.extract(any(), any(), any(), any())).thenReturn(okExtraction());
    }

    private static HostMapSectionExtractor.Result okExtraction() {
        return new HostMapSectionExtractor.Result(new HostMapSectionExtractor.Extraction(
                List.of(new HostMapSectionExtractor.ModelEntity("cluster", "eks-prod-1",
                        List.of("eks-prod-1"), "socle", "prod", "actif")),
                List.of(new HostMapSectionExtractor.ModelRelation("eks-prod-1", "123456789012", "dans")),
                Set.of(), List.of()), 500, 60, "claude-sonnet-5-5", false);
    }

    private HostMapFile save(UUID userId, UUID hostId, String content, String digest) {
        return files.save(HostMapFile.builder().userId(userId).hostId(hostId).path("plateformes.md")
                .title("Plateformes").content(content).digest(digest).facts(3)
                .observedAt(OffsetDateTime.now()).build());
    }

    @Test
    @DisplayName("indexe la carte : sections, faits typés, identifiants exacts, entités et relations")
    void indexesTheMap() {
        save(alice, aliceHost, MAP, "d1");

        HostMapIndexService.RunOutcome outcome = service.runOnce();

        assertThat(outcome.filesIndexed()).isEqualTo(1);
        assertThat(outcome.sectionsExtracted()).isEqualTo(2); // Le préambule n'a pas de fait.
        List<HostMapSection> indexed = sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(alice, aliceHost);
        assertThat(indexed).extracting(HostMapSection::getStatus)
                .containsExactly("SKIPPED", "DONE", "DONE");
        assertThat(indexed.get(1).getInputTokens()).isEqualTo(500);

        List<HostMapFact> all = facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(alice, aliceHost);
        assertThat(all).extracting(HostMapFact::getKind).containsExactly("FAIT", "PIEGE", "ECHEANCE");
        assertThat(all.get(0).getObservedOn()).isEqualTo(LocalDate.parse("2026-09-14"));
        assertThat(all.get(2).getDueOn()).isEqualTo(LocalDate.parse("2026-10-09"));
        assertThat(facts.findByIdentifierPattern(alice, aliceHost,
                HostMapLikes.exactIdentifier("123456789012"))).hasSize(1);

        List<HostMapEntity> found = entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(alice, aliceHost);
        assertThat(found).extracting(HostMapEntity::getOrigin).contains("MOTIF", "MODELE");
        assertThat(found).filteredOn(e -> e.getKind().equals("compte_aws"))
                .extracting(HostMapEntity::getLabel).containsExactly("123456789012");
        assertThat(relations.findByUserIdAndHostId(alice, aliceHost)).isNotEmpty();
        assertThat(files.findById(files.findByUserIdAndHostIdOrderByPathAsc(alice, aliceHost).get(0).getId())
                .orElseThrow().getIndexedDigest()).isEqualTo("d1");
    }

    @Test
    @DisplayName("incrémental : seule la section modifiée est ré-extraite (D3)")
    void onlyChangedSectionsAreReExtracted() {
        HostMapFile file = save(alice, aliceHost, MAP, "d1");
        service.runOnce();
        verify(extractor, times(2)).extract(any(), any(), any(), any());
        UUID keptId = sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(alice, aliceHost).get(1).getId();

        file = files.findById(file.getId()).orElseThrow();
        file.setContent(MAP.replace("périme le 2026-10-09", "périme le 2026-11-30"));
        file.setDigest("d2");
        files.save(file);
        service.runOnce();

        verify(extractor, times(3)).extract(any(), any(), any(), any());
        // « Jetons » lue deux fois (avant / après), « Comptes AWS » une seule : son texte n'a pas changé.
        verify(extractor, times(2)).extract(eq(alice), eq("plateformes.md"), eq("Jetons"), any());
        verify(extractor, times(1)).extract(eq(alice), eq("plateformes.md"), eq("Comptes AWS"), any());
        List<HostMapSection> after = sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(alice, aliceHost);
        assertThat(after).hasSize(3);
        assertThat(after.get(1).getId()).isEqualTo(keptId);
        assertThat(facts.findByUserIdAndHostIdOrderByPathAscLineNoAsc(alice, aliceHost))
                .filteredOn(f -> f.getKind().equals("ECHEANCE"))
                .extracting(HostMapFact::getDueOn).containsExactly(LocalDate.parse("2026-11-30"));

        // Une passe de plus sans changement ne coûte rien.
        service.runOnce();
        verify(extractor, times(3)).extract(any(), any(), any(), any());
    }

    @Test
    @DisplayName("sans clé, les sections restent en attente sans user leurs essais")
    void providerUnavailableKeepsPending() {
        when(extractor.extract(any(), any(), any(), any()))
                .thenReturn(new HostMapSectionExtractor.Result(null, 0, 0, "claude-sonnet-5-5", true));
        save(alice, aliceHost, MAP, "d1");
        service.runOnce();
        assertThat(sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(alice, aliceHost))
                .filteredOn(s -> !s.getStatus().equals("SKIPPED"))
                .allMatch(s -> s.getStatus().equals("PENDING") && s.getAttempts() == 0);
        // La couche déterministe, elle, est là.
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isEqualTo(3);
    }

    @Test
    @DisplayName("isolation : la carte d'un compte ne se lit jamais sous un autre ; purge bornée au poste")
    void isolationAndPurge() {
        save(alice, aliceHost, MAP, "d1");
        save(bob, bobHost, MAP, "d1");
        service.runOnce();

        assertThat(facts.findByIdentifierPattern(alice, bobHost,
                HostMapLikes.exactIdentifier("123456789012"))).isEmpty();
        assertThat(facts.findByIdentifierPattern(bob, bobHost,
                HostMapLikes.exactIdentifier("123456789012"))).hasSize(1);

        purge.purgeHost(alice, aliceHost);
        assertThat(facts.countByUserIdAndHostId(alice, aliceHost)).isZero();
        assertThat(sections.findByUserIdAndHostIdOrderByPathAscOrdinalAsc(alice, aliceHost)).isEmpty();
        assertThat(facts.countByUserIdAndHostId(bob, bobHost)).isEqualTo(3);
        purge.purgeUser(bob);
        assertThat(entities.findByUserIdAndHostIdOrderByKindAscLabelNormAsc(bob, bobHost)).isEmpty();
    }

    @Test
    @DisplayName("coupe-circuit : éteint, rien n'est indexé")
    void killSwitch() {
        HostMapIndexService off = new HostMapIndexService(
                new HostMapIndexProperties(false, null, null, null, null, null, null, null, null),
                files, sections, facts, null, extractor);
        save(alice, aliceHost, MAP, "d1");
        assertThat(off.runOnce().filesIndexed()).isZero();
        assertThat(sections.count()).isZero();
    }
}
