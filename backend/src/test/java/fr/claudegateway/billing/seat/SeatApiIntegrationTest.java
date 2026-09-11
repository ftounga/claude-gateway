package fr.claudegateway.billing.seat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.runner.host.HostMissionStatus;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/**
 * Le supplément par poste, de bout en bout (F-65 / SF-65-01) : ce que {@code GET /billing/seats}
 * montre, ce que le quota oppose, et ce qu'une clôture suivie d'une réouverture <b>ne change
 * pas</b>.
 *
 * <p>Le supplément est configuré ici — et seulement ici — pour que le mécanisme soit observable.
 * En production, ces clés sont vides : aucun jeton n'est apporté tant que le PO n'a rien
 * renseigné.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.seat.included-seats=1",
        "app.seat.tokens-per-extra-seat=300000",
        // Proratisation neutralisée ICI seulement : les postes semés naissent aujourd'hui, et la
        // part attendue dépendrait donc du jour du mois où le test tourne. Le prorata temporis est
        // vérifié là où il se raisonne — SeatQuotaServiceTest, sur une horloge fixe.
        "app.seat.proration=NONE",
        "app.seat.price-id=price_extra_seat_test",
        "app.seat.display-price=70"})
class SeatApiIntegrationTest {

    /** Offre Gold : elle inclut la Forge, dont l'endpoint d'état de mission dépend (F-40). */
    private static final long GOLD_TOKENS = 12_000_000L;
    private static final long SEAT_TOKENS = 300_000L;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private HostSeatMonthRepository seatMonthRepository;
    @Autowired private JwtService jwtService;

    private String aliceToken;
    private String bobToken;
    private UUID aliceId;
    private RunnerHost aliceSecondHost;

    @BeforeEach
    void setUp() {
        seatMonthRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        User alice = seedUser("alice-seats@example.com");
        aliceId = alice.getId();
        aliceToken = jwtService.generateToken(alice);
        seedActiveGold(aliceId);
        seedHost(aliceId, "Poste CAGIP");
        aliceSecondHost = seedHost(aliceId, "Poste Banque");

        User bob = seedUser("bob-seats@example.com");
        bobToken = jwtService.generateToken(bob);
        seedActiveGold(bob.getId());
        seedHost(bob.getId(), "Poste de Bob");
    }

    @Test
    void theSubscriptionCoversOneSeatAndTheOtherGrantsItsShare() throws Exception {
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.includedSeats").value(1))
                .andExpect(jsonPath("$.countedSeats").value(2))
                .andExpect(jsonPath("$.extraSeats").value(1))
                .andExpect(jsonPath("$.grantedTokens").value(SEAT_TOKENS))
                .andExpect(jsonPath("$.billed").value(true))
                .andExpect(jsonPath("$.displayPrice").value("70"))
                .andExpect(jsonPath("$.seats[0].coveredByPlan").value(true))
                .andExpect(jsonPath("$.seats[1].extraSeatRank").value(1));
    }

    @Test
    void theGaugeOpposesThePlanPlusTheSeatShare() throws Exception {
        mockMvc.perform(get("/api/usage").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quotaTokens").value(GOLD_TOKENS + SEAT_TOKENS));
    }

    @Test
    void anotherAccountSeesOnlyItsOwnSeats() throws Exception {
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedSeats").value(1))
                .andExpect(jsonPath("$.extraSeats").value(0))
                .andExpect(jsonPath("$.grantedTokens").value(0))
                .andExpect(jsonPath("$.seats[0].name").value("Poste de Bob"));
    }

    @Test
    void closingASeatKeepsItCountedForTheMonthAndReopeningItChangesNothing() throws Exception {
        setMission(aliceSecondHost.getId(), "CLOSED");

        // Le mois est engagé : le poste reste compté, et sa part de jetons avec lui.
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedSeats").value(2))
                .andExpect(jsonPath("$.grantedTokens").value(SEAT_TOKENS))
                .andExpect(jsonPath("$.seats[1].closed").value(true));

        setMission(aliceSecondHost.getId(), "ACTIVE");

        // Rouvrir ne refacture rien et n'apporte rien de plus : une seule ligne de mois-poste.
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedSeats").value(2))
                .andExpect(jsonPath("$.grantedTokens").value(SEAT_TOKENS));

        assertThat(seatMonthRepository.findByUserIdAndPeriodStart(aliceId, periodStart()))
                .hasSize(1);
    }

    @Test
    void aSeatClosedBeforeThePeriodIsNeverCounted() throws Exception {
        // Clôture posée directement en base, sans passer par l'endpoint : c'est l'image d'un poste
        // clôturé un mois précédent — aucune ligne de mois-poste sur la période courante.
        aliceSecondHost.setMissionStatus(HostMissionStatus.CLOSED);
        hostRepository.save(aliceSecondHost);

        assertThat(seatMonthRepository.findByUserIdAndPeriodStart(aliceId, periodStart())).isEmpty();
        mockMvc.perform(get("/api/billing/seats").contextPath("/api").header("Authorization", "Bearer " + aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countedSeats").value(1))
                .andExpect(jsonPath("$.grantedTokens").value(0));
    }

    @Test
    void seatsAreRefusedWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/billing/seats").contextPath("/api")).andExpect(status().isUnauthorized());
    }

    private void setMission(UUID hostId, String status) throws Exception {
        mockMvc.perform(put("/api/runner-hosts/" + hostId + "/mission").contextPath("/api")
                        .header("Authorization", "Bearer " + aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"missionStatus\":\"" + status + "\"}"))
                .andExpect(status().isOk());
    }

    private static LocalDate periodStart() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1);
    }

    private User seedUser(String email) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(UserRole.USER).build());
    }

    private void seedActiveGold(UUID userId) {
        subscriptionRepository.save(Subscription.builder()
                .userId(userId)
                .planCode(PlanCode.GOLD)
                .status(SubscriptionStatus.ACTIVE)
                .build());
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder()
                .userId(userId)
                .name(name)
                .lastSeenAt(OffsetDateTime.now(ZoneOffset.UTC))
                .build());
    }
}
