package fr.claudegateway.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import fr.claudegateway.atelier.WorkspaceRepository;
import fr.claudegateway.auth.JwtService;
import fr.claudegateway.billing.PlanCode;
import fr.claudegateway.billing.Subscription;
import fr.claudegateway.billing.SubscriptionRepository;
import fr.claudegateway.billing.SubscriptionStatus;
import fr.claudegateway.email.EmailService;
import fr.claudegateway.runner.host.RunnerHost;
import fr.claudegateway.runner.host.RunnerHostRepository;
import fr.claudegateway.runner.host.RunnerHostService;
import fr.claudegateway.user.AuthProvider;
import fr.claudegateway.user.User;
import fr.claudegateway.user.UserRepository;
import fr.claudegateway.user.UserRole;

/** L'adresse de réception d'un client, de bout en bout (F-110 / SF-110-01). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HostMailAddressApiIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private RunnerHostRepository hostRepository;
    @Autowired private RunnerHostService hostService;
    @Autowired private HostMailAddressRepository addressRepository;
    @Autowired private HostMailAddressService addressService;
    @Autowired private WorkspaceRepository workspaceRepository;
    @Autowired private SubscriptionRepository subscriptionRepository;
    @Autowired private JwtService jwtService;
    @MockitoBean private EmailService emailService;

    private User vera;
    private String veraToken;
    private RunnerHost veraHost;
    private String bobToken;
    private String adaToken;
    private RunnerHost adaHost;
    private String noraToken;

    @BeforeEach
    void setUp() {
        addressRepository.deleteAll();
        workspaceRepository.deleteAll();
        hostRepository.deleteAll();
        subscriptionRepository.deleteAll();
        userRepository.deleteAll();

        vera = seedUser("vera-mail@example.com", UserRole.USER);
        veraToken = jwtService.generateToken(vera);
        subscribe(vera, PlanCode.GOLD_VIGIE);
        veraHost = seedHost(vera.getId(), "CAGIP");

        User bob = seedUser("bob-mail@example.com", UserRole.USER);
        bobToken = jwtService.generateToken(bob);
        subscribe(bob, PlanCode.GOLD);

        User ada = seedUser("ada-mail@example.com", UserRole.ADMIN);
        adaToken = jwtService.generateToken(ada);
        adaHost = seedHost(ada.getId(), "Poste d'Ada");

        User nora = seedUser("nora-mail@example.com", UserRole.USER);
        noraToken = jwtService.generateToken(nora);
        subscribe(nora, PlanCode.SOLO);
    }

    private User seedUser(String email, UserRole role) {
        return userRepository.save(User.builder().email(email).emailVerified(true)
                .provider(AuthProvider.LOCAL).role(role).build());
    }

    private void subscribe(User user, PlanCode plan) {
        Subscription subscription = subscriptionRepository.findByUserId(user.getId())
                .orElseGet(() -> Subscription.builder().userId(user.getId()).build());
        subscription.setPlanCode(plan);
        subscription.setStatus(SubscriptionStatus.ACTIVE);
        subscription.setAtelierOptionStatus(null);
        subscription.setTeamsOptionStatus(null);
        subscriptionRepository.save(subscription);
    }

    private RunnerHost seedHost(UUID userId, String name) {
        return hostRepository.save(RunnerHost.builder().userId(userId).name(name).rootName("dev")
                .os("linux").shell("posix").elevated(false).build());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String token) {
        return request.contextPath("/api").header("Authorization", "Bearer " + token);
    }

    private String url(RunnerHost host, String suffix) {
        return "/api/runner-hosts/" + host.getId() + "/mail-address" + suffix;
    }

    private String declareAndCaptureCode(RunnerHost host, String token, String address) throws Exception {
        mockMvc.perform(as(put(url(host, "")), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"" + address + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(false))
                .andExpect(jsonPath("$.codePending").value(true))
                .andExpect(jsonPath("$.fallback").value(true));
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendReceptionAddressCode(eq(address.toLowerCase()), eq(host.getName()), code.capture());
        return code.getValue();
    }

    @Test
    void declareVerifyThenReadTheRecipient() throws Exception {
        mockMvc.perform(as(get(url(veraHost, "")), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.address").doesNotExist())
                .andExpect(jsonPath("$.recipient").value("vera-mail@example.com"))
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.clientName").value("CAGIP"));

        String code = declareAndCaptureCode(veraHost, veraToken, "Vera.Martin@CAGIP.fr");
        assertThat(addressRepository.findByUserIdAndHostId(vera.getId(), veraHost.getId()).orElseThrow()
                .getCodeHash()).isNotEqualTo(code).hasSize(64);

        mockMvc.perform(as(post(url(veraHost, "/verify")), veraToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.verified").value(true))
                .andExpect(jsonPath("$.recipient").value("vera.martin@cagip.fr"))
                .andExpect(jsonPath("$.fallback").value(false));

        mockMvc.perform(as(get(url(veraHost, "")), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value("vera.martin@cagip.fr"))
                .andExpect(jsonPath("$.codeHash").doesNotExist());
        assertThat(addressService.resolveRecipient(vera.getId(), veraHost.getId()))
                .isEqualTo(new ResolvedRecipient("vera.martin@cagip.fr", true, "CAGIP"));
    }

    @Test
    void errorsAreSaidWithTheirCodes() throws Exception {
        mockMvc.perform(as(put(url(veraHost, "")), veraToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"pas une adresse\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mail_address_invalid"));

        mockMvc.perform(as(post(url(veraHost, "/verify")), veraToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error").value("mail_code_none"));

        String code = declareAndCaptureCode(veraHost, veraToken, "vera@cagip.fr");
        String wrong = code.equals("000000") ? "111111" : "000000";
        mockMvc.perform(as(post(url(veraHost, "/verify")), veraToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + wrong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("mail_code_invalid"));

        mockMvc.perform(as(post(url(veraHost, "/code")), veraToken))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.error").value("mail_code_throttled"));
    }

    @Test
    void anotherUsersHostIsNotFoundAndNothingLeaks() throws Exception {
        mockMvc.perform(as(get(url(veraHost, "")), bobToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(as(put(url(veraHost, "")), bobToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"bob@ailleurs.fr\"}"))
                .andExpect(status().isNotFound());
        assertThat(addressRepository.findAll()).isEmpty();
    }

    @Test
    void theRightIsForgeOrVigieAndTheAdministratorHasIt() throws Exception {
        mockMvc.perform(as(get(url(veraHost, "")), noraToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(as(get(url(adaHost, "")), adaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipient").value("ada-mail@example.com"));
        mockMvc.perform(get(url(veraHost, "")).contextPath("/api"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void removingFallsBackAndDeletingTheHostErasesTheAddress() throws Exception {
        String code = declareAndCaptureCode(veraHost, veraToken, "vera@cagip.fr");
        mockMvc.perform(as(post(url(veraHost, "/verify")), veraToken).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"" + code + "\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(as(delete(url(veraHost, "")), veraToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fallback").value(true))
                .andExpect(jsonPath("$.recipient").value("vera-mail@example.com"));
        assertThat(addressRepository.findAll()).isEmpty();

        String again = declareAndCaptureCodeAgain(veraHost, veraToken, "vera2@cagip.fr");
        assertThat(again).matches("\\d{6}");
        assertThat(addressRepository.findAll()).hasSize(1);
        hostService.deleteWithCredentials(vera.getId(), veraHost.getId());
        assertThat(addressRepository.findAll()).isEmpty();
    }

    /** Second envoi dans le même test : le délai d'une minute est levé en vidant la ligne précédente. */
    private String declareAndCaptureCodeAgain(RunnerHost host, String token, String address) throws Exception {
        mockMvc.perform(as(put(url(host, "")), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"address\":\"" + address + "\"}"))
                .andExpect(status().isOk());
        ArgumentCaptor<String> code = ArgumentCaptor.forClass(String.class);
        verify(emailService).sendReceptionAddressCode(eq(address), anyString(), code.capture());
        return code.getValue();
    }
}
