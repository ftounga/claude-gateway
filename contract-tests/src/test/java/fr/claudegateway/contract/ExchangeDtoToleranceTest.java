package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Aucun DTO d'échange ne reste strict (F-81 / SF-81-02).
 *
 * <p><b>Ce que ce test empêche de revenir.</b> Le 2026-09-10, {@code StoredToken} était le seul DTO
 * du runner à ne pas porter {@link JsonIgnoreProperties} — et rien ne l'imposait. Un champ renommé
 * par la gateway est devenu, pour lui, un champ <b>inconnu</b> : la lecture a échoué, et aucun
 * appairage de machine neuve n'a fonctionné pendant deux jours.</p>
 *
 * <p><b>La raison n'est pas cosmétique.</b> Un runner installé chez un client vit <b>plus
 * longtemps</b> que la version de gateway qu'il a connue : il n'est mis à jour ni au même moment, ni
 * par les mêmes mains. Un champ ajouté demain ne doit pas paralyser les postes déjà déployés — il
 * doit être ignoré. La même asymétrie existe entre deux pods de gateway pendant une bascule
 * progressive.</p>
 *
 * <p><b>La lecture se fait sur les classes compilées</b>, comme {@code SpringConstructorAmbiguityTest}
 * livré la veille en SF-79-02 : c'est la leçon explicite de son premier essai, qui passait alors que
 * le défaut était là, parce qu'il interrogeait le scanner de Spring — donc ce qui tourne en test —
 * au lieu de ce qui part en production.</p>
 *
 * <p>La définition de « DTO d'échange » et la façon dont l'inventaire est reconstruit vivent dans
 * {@link ExchangeDtoInventory}.</p>
 */
class ExchangeDtoToleranceTest {

    /**
     * Un mapper <b>délibérément strict</b> : l'inverse de ce que Spring Boot configure.
     *
     * <p>C'est tout l'objet du second test. Côté gateway, la tolérance existe déjà — mais elle tient
     * à une <b>configuration</b>, pas aux classes. Une propriété
     * {@code spring.jackson.deserialization.fail-on-unknown-properties} ajoutée un jour, ou un
     * {@code new ObjectMapper()} construit à la main (le backend en compte déjà plusieurs), la fait
     * disparaître sans qu'aucun test ne le dise. Lire avec ce mapper-ci vérifie que la tolérance
     * voyage <b>avec la classe</b>.</p>
     */
    private static final ObjectMapper STRICT = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    @Test
    @DisplayName("l'inventaire des DTO d'échange est non vide et contient les deux bouts du défaut")
    void linventaireTientDebout() {
        List<Class<?>> dtos = ExchangeDtoInventory.tout();

        assertThat(dtos)
                .as("un inventaire vide signifierait que les sondes ne trouvent plus rien — un test "
                        + "qui passe à vide est pire qu'un test absent")
                .isNotEmpty();
        assertThat(dtos)
                .as("les deux extrémités du défaut du 2026-09-10 doivent y être : ce que le runner "
                        + "LIT de la gateway, et ce que la gateway LIT du runner")
                .contains(fr.claudegateway.runner.StoredToken.class,
                        fr.claudegateway.runner.dto.PairRequest.class);
    }

    @Test
    @DisplayName("chaque DTO d'échange porte @JsonIgnoreProperties(ignoreUnknown = true)")
    void chaqueDtoDEchangePorteLannotation() {
        List<String> manquants = new ArrayList<>();
        for (Class<?> dto : ExchangeDtoInventory.tout()) {
            JsonIgnoreProperties annotation = dto.getAnnotation(JsonIgnoreProperties.class);
            if (annotation == null) {
                manquants.add(dto.getName() + " (annotation absente)");
            } else if (!annotation.ignoreUnknown()) {
                manquants.add(dto.getName() + " (ignoreUnknown = false)");
            }
        }

        assertThat(manquants)
                .as("Ces classes lisent une charge utile écrite par un processus qu'on ne redéploie "
                        + "pas avec elles — le runner d'un client, ou un autre pod pendant une "
                        + "bascule. Sans @JsonIgnoreProperties(ignoreUnknown = true), le premier "
                        + "champ ajouté par l'autre côté fait échouer la lecture ENTIÈRE, et le "
                        + "poste déjà installé s'arrête. C'est exactement la panne d'appairage du "
                        + "2026-09-10.")
                .isEmpty();
    }

    @Test
    @DisplayName("chaque DTO d'échange se relit malgré un champ inconnu, même sous un mapper strict")
    void chaqueDtoDEchangeSurvitAUnChampInconnu() throws Exception {
        List<String> fragiles = new ArrayList<>();
        for (Class<?> dto : ExchangeDtoInventory.tout()) {
            try {
                // Une charge utile qui n'apporte QUE ce que la classe ne connaît pas : c'est le pire
                // cas, et c'est celui qu'un runner rencontrera face à une gateway plus récente.
                STRICT.readValue("{\"champAjouteParUneVersionFuture\":\"peu importe\"}", dto);
            } catch (Exception e) {
                fragiles.add(dto.getName() + " → " + e.getClass().getSimpleName());
            }
        }

        assertThat(fragiles)
                .as("La FORME de l'annotation ne suffit pas : ce test vérifie son EFFET, et le "
                        + "vérifie avec un mapper volontairement strict. Une classe qui échoue ici "
                        + "ne tolère les champs inconnus que tant que la configuration Jackson du "
                        + "moment le veut bien — pas par elle-même.")
                .isEmpty();
    }
}
