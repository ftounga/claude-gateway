package fr.claudegateway.contract;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import fr.claudegateway.runner.PairingClient;
import fr.claudegateway.runner.StoredToken;
import fr.claudegateway.runner.dto.PairRequest;
import fr.claudegateway.runner.dto.PairResponse;

/**
 * L'appairage, dans les deux sens, avec le code réel des deux côtés (F-81 / SF-81-01).
 *
 * <p><b>Ce que ce test empêche de revenir.</b> Le 2026-09-10, SF-48-01 a renommé {@code workspaceId}
 * en {@code hostId} dans {@link PairResponse}. Le changement était juste ; il était fait d'un seul
 * côté. Le {@code StoredToken} du runner attendait toujours l'ancien nom, son {@code ObjectMapper}
 * est strict par défaut, et la lecture échouait. <b>Aucun appairage de machine neuve n'a fonctionné
 * pendant deux jours</b>, et la gateway, elle, <b>réussissait</b> : elle créait le jeton et
 * enregistrait la machine pendant l'appel. L'écran montrait un poste connu dont le canal ne
 * s'ouvrirait jamais ; le terminal affichait « Réponse d'appairage illisible ». Deux affichages
 * contradictoires, aucun faux.</p>
 *
 * <p><b>Pourquoi un vrai serveur HTTP.</b> Le {@code PairingClient} garde son mapper pour lui —
 * c'est bien ainsi. Lui donner une chaîne à lire supposerait de la reconstruire, c'est-à-dire de
 * réécrire l'hypothèse à vérifier. On lui donne donc ce qu'il attend : une réponse HTTP, servie par
 * un serveur local, dont le corps est la sérialisation d'un vrai {@link PairResponse} par le vrai
 * mapper de la gateway. Et on lit le corps qu'il poste avec le vrai {@link PairRequest}. Aucun nom
 * de champ n'est écrit à la main dans ce fichier — sauf un, délibérément, pour le test de
 * tolérance.</p>
 */
class PairingContractTest {

    private HttpServer server;
    private String pairUrl;

    /** Ce que la gateway répondra ; posé par chaque test avant d'appeler le runner. */
    private final AtomicReference<byte[]> responseBody = new AtomicReference<>();

    /** Ce que le runner a réellement posté ; relu ensuite par le DTO du backend. */
    private final AtomicReference<String> capturedRequest = new AtomicReference<>();

    @BeforeEach
    void startGateway() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/runner/pair", exchange -> {
            capturedRequest.set(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = responseBody.get();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        pairUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/runner/pair";
    }

    @AfterEach
    void stopGateway() {
        server.stop(0);
    }

    @Test
    @DisplayName("la réponse d'appairage écrite par la gateway est lue entièrement par le runner")
    void laReponseDAppairageTraverse() throws Exception {
        // ÉMETTEUR : l'objet réel de la gateway, sérialisé par le mapper réel de la gateway.
        PairResponse emise = new PairResponse(
                "rnr_" + UUID.randomUUID().toString().replace("-", ""),
                UUID.randomUUID(),
                OffsetDateTime.now().plusDays(30).truncatedTo(ChronoUnit.MILLIS));
        responseBody.set(ContractMappers.gateway().writeValueAsBytes(emise));

        // RÉCEPTEUR : le client d'appairage réel du runner, avec son propre mapper.
        StoredToken recue = new PairingClient(HttpClient.newHttpClient())
                .pair(pairUrl, "AB12CD34", "poste-de-test");

        assertThat(recue.token())
                .as("le jeton doit traverser — sans lui, le runner ne s'authentifie jamais")
                .isEqualTo(emise.token());
        assertThat(recue.hostId())
                .as("LE CHAMP DE LA PANNE DU 2026-09-10 : renommé d'un seul côté, il ne traverse "
                        + "plus et aucun appairage de machine neuve ne fonctionne")
                .isEqualTo(emise.hostId());
        assertThat(recue.expiresAt())
                .as("l'expiration doit traverser, et au même instant : une date sérialisée en "
                        + "nombre de secondes ne se relit pas en OffsetDateTime")
                .isEqualTo(emise.expiresAt());
    }

    @Test
    @DisplayName("ce que le runner déclare à l'appairage est lu entièrement par la gateway")
    void laRequeteDAppairageTraverse() throws Exception {
        responseBody.set(ContractMappers.gateway().writeValueAsBytes(new PairResponse(
                "rnr_jeton", UUID.randomUUID(), OffsetDateTime.now().plusDays(1))));

        // ÉMETTEUR : le client réel du runner déclare tout ce qu'il sait déclarer.
        new PairingClient(HttpClient.newHttpClient())
                .pair(pairUrl, "ZZ99YY88", "poste-de-test", "dev", "linux", true);

        // RÉCEPTEUR : le DTO réel de la gateway, lu par le mapper réel de la gateway.
        PairRequest recue = ContractMappers.gateway()
                .readValue(capturedRequest.get(), PairRequest.class);

        assertThat(recue.code()).isEqualTo("ZZ99YY88");
        assertThat(recue.label()).isEqualTo("poste-de-test");
        assertThat(recue.rootName())
                .as("le NOM du dossier racine, jamais le chemin absolu (SF-38-15)")
                .isEqualTo("dev");
        assertThat(recue.os())
                .as("le système de la machine, rangé sur le POSTE depuis F-48 / SF-48-01")
                .isEqualTo("linux");
        assertThat(recue.elevated())
                .as("les droits sous lesquels le runner tourne (SF-38-18) : la gateway ne peut pas "
                        + "les deviner, et c'est au moment d'autoriser une commande qu'ils comptent")
                .isTrue();
    }

    @Test
    @DisplayName("un runner qui ne déclare rien reste appairable")
    void unRunnerQuiNeDeclareRienResteAppairable() throws Exception {
        responseBody.set(ContractMappers.gateway().writeValueAsBytes(new PairResponse(
                "rnr_jeton", UUID.randomUUID(), OffsetDateTime.now().plusDays(1))));

        new PairingClient(HttpClient.newHttpClient()).pair(pairUrl, "MM11NN22", null);

        PairRequest recue = ContractMappers.gateway()
                .readValue(capturedRequest.get(), PairRequest.class);

        assertThat(recue.code()).isEqualTo("MM11NN22");
        assertThat(recue.label()).isNull();
        assertThat(recue.rootName()).isNull();
        assertThat(recue.os()).isNull();
        assertThat(recue.elevated())
                .as("absent ≠ faux : le DTO le rend nul, et c'est le service qui décide")
                .isNull();
    }

    @Test
    @DisplayName("un champ AJOUTÉ par une gateway plus récente ne paralyse pas un runner déployé")
    void unChampAjouteNeCassePasLeRunner() throws Exception {
        PairResponse emise = new PairResponse("rnr_jeton", UUID.randomUUID(),
                OffsetDateTime.now().plusDays(1).truncatedTo(ChronoUnit.MILLIS));

        // Le seul endroit du module où un nom de champ est écrit à la main, et c'est assumé : on ne
        // peut pas simuler une gateway PLUS RÉCENTE sans écrire le champ qu'elle ajouterait. Il est
        // ajouté sur l'arbre du VRAI objet sérialisé, jamais sur une chaîne recopiée.
        ObjectNode futur = ContractMappers.gateway().valueToTree(emise);
        futur.put("seatsRemaining", 3);
        futur.putObject("plan").put("name", "pro");
        responseBody.set(ContractMappers.gateway().writeValueAsBytes(futur));

        StoredToken recue = new PairingClient(HttpClient.newHttpClient())
                .pair(pairUrl, "AB12CD34", "poste-de-test");

        assertThat(recue.hostId())
                .as("C'EST LA TOLÉRANCE QU'ON VEUT : un runner installé chez un client vit plus "
                        + "longtemps que la gateway qu'il a connue. Un champ ajouté demain doit "
                        + "être IGNORÉ, pas faire tomber l'appairage")
                .isEqualTo(emise.hostId());
        assertThat(recue.token()).isEqualTo(emise.token());
    }
}
