package com.guildworkman.api.signing.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.escrow.rpc.SorobanRpcClient;
import com.guildworkman.api.handler.ProblemDetails;
import com.guildworkman.api.signing.StellarTestFixtures;
import com.guildworkman.api.signing.repository.ChannelAccountRepository;
import com.guildworkman.api.signing.repository.TransactionSubmissionRepository;
import com.guildworkman.api.signing.service.ChannelAccountService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.stellar.sdk.KeyPair;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The HTTP surface: status codes, the idempotent-replay header, the RFC 7807
 * error contract shared with the rest of the API, and the authorisation rules —
 * signing requires authentication, pool management requires ADMIN.
 */
@SpringBootTest(properties = {
        "stellar.signing.prepare-poll-delay-ms=3600000",
        "stellar.signing.broadcast-poll-delay-ms=3600000",
        "stellar.signing.confirm-poll-delay-ms=3600000",
        "stellar.signing.lease-sweep-delay-ms=3600000",
        "chain.events.poll-delay-ms=3600000",
        "escrow.orchestration.submit-poll-delay-ms=3600000",
        "escrow.orchestration.confirm-poll-delay-ms=3600000",
        "escrow.reconciliation.poll-delay-ms=3600000",
        "booking.expiry-sweep-delay-ms=3600000",
        "spring.task.scheduling.enabled=false"
})
@AutoConfigureMockMvc
class SigningApiTest {

    private static final String KEY_REF = "apitest1";
    private static KeyPair keyPair;

    @DynamicPropertySource
    static void localSigningKeys(DynamicPropertyRegistry registry) {
        keyPair = KeyPair.random();
        registry.add("stellar.signing.local.keys." + KEY_REF, () -> String.valueOf(keyPair.getSecretSeed()));
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransactionSubmissionRepository submissions;

    @Autowired
    private ChannelAccountRepository channelAccounts;

    @Autowired
    private ChannelAccountService channelAccountService;

    @MockBean
    private SorobanRpcClient rpc;

    @BeforeEach
    void cleanSlate() {
        submissions.deleteAll();
        channelAccounts.deleteAll();
        when(rpc.getAccountSequence(anyString())).thenReturn(4_000L);
    }

    private String submitBody() throws Exception {
        return objectMapper.writeValueAsString(new SubmitTransactionRequest(
                "idem-" + UUID.randomUUID(), "ref-api", StellarTestFixtures.unsignedEnvelope(), null));
    }

    // --- submission ---------------------------------------------------------

    @Test
    @WithMockUser
    void submittingAcceptsTheRequestAndReportsItAsNew() throws Exception {
        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody()))
                .andExpect(status().isAccepted())
                .andExpect(header().string(TransactionSigningController.IDEMPOTENT_REPLAY_HEADER, "false"))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.reference").value("ref-api"))
                // Never in a response body, whatever else is.
                .andExpect(jsonPath("$.signedEnvelopeXdr").doesNotExist())
                .andExpect(jsonPath("$.unsignedTransactionXdr").doesNotExist());
    }

    @Test
    @WithMockUser
    void resubmittingTheSameKeyIsFlaggedAsAReplay() throws Exception {
        String body = submitBody();

        mockMvc.perform(post("/api/v1/stellar/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(body));
        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string(TransactionSigningController.IDEMPOTENT_REPLAY_HEADER, "true"));

        assertThat(submissions.count()).isEqualTo(1);
    }

    @Test
    @WithMockUser
    void aMalformedEnvelopeIsAProblemJsonBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(new SubmitTransactionRequest(
                "idem-" + UUID.randomUUID(), null, "AAAAnotanenvelope", null));

        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Transaction could not be assembled"));
    }

    @Test
    @WithMockUser
    void anUnknownKeyReferenceIsAProblemJsonBadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(new SubmitTransactionRequest(
                "idem-" + UUID.randomUUID(), null, StellarTestFixtures.unsignedEnvelope(), List.of("nosuchkey")));

        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Unknown key reference"));
    }

    @Test
    @WithMockUser
    void validationFailuresUseTheSharedFieldErrorShape() throws Exception {
        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.errors.idempotencyKey").exists())
                .andExpect(jsonPath("$.errors.unsignedTransactionXdr").exists());
    }

    @Test
    @WithMockUser
    void anUnknownSubmissionIsAProblemJsonNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/stellar/transactions/999999"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Transaction submission not found"));
    }

    @Test
    @WithMockUser
    void submissionsAreLookedUpByCallerReference() throws Exception {
        mockMvc.perform(post("/api/v1/stellar/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(submitBody()));

        mockMvc.perform(get("/api/v1/stellar/transactions").param("reference", "ref-api"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].reference").value("ref-api"));
    }

    /** Signing on someone's behalf is never anonymous. */
    @Test
    void submittingRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/v1/stellar/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(submitBody()))
                .andExpect(status().isUnauthorized());
    }

    // --- channel-account pool -----------------------------------------------

    @Test
    @WithMockUser(roles = "ADMIN")
    void anAdminCanRegisterAndListPoolMembers() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterChannelAccountRequest(KEY_REF));

        mockMvc.perform(post("/api/v1/stellar/channel-accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountId").value(keyPair.getAccountId()))
                .andExpect(jsonPath("$.keyRef").value(KEY_REF))
                .andExpect(jsonPath("$.status").value("NEEDS_RESYNC"));

        mockMvc.perform(get("/api/v1/stellar/channel-accounts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].accountId").value(keyPair.getAccountId()));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void registeringTheSameKeyTwiceIsAConflict() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterChannelAccountRequest(KEY_REF));
        mockMvc.perform(post("/api/v1/stellar/channel-accounts")
                .contentType(MediaType.APPLICATION_JSON).content(body));

        mockMvc.perform(post("/api/v1/stellar/channel-accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith(ProblemDetails.CONTENT_TYPE))
                .andExpect(jsonPath("$.title").value("Channel account already registered"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void registeringAKeyCustodyDoesNotHoldIsABadRequest() throws Exception {
        String body = objectMapper.writeValueAsString(new RegisterChannelAccountRequest("nosuchkey"));

        mockMvc.perform(post("/api/v1/stellar/channel-accounts")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Unknown key reference"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void disableEnableAndResyncDriveThePoolMemberThroughItsStates() throws Exception {
        Long id = channelAccountService.register(KEY_REF).getId();

        mockMvc.perform(post("/api/v1/stellar/channel-accounts/" + id + "/resync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AVAILABLE"))
                .andExpect(jsonPath("$.nextSequence").value(4001));

        mockMvc.perform(post("/api/v1/stellar/channel-accounts/" + id + "/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DISABLED"));

        mockMvc.perform(post("/api/v1/stellar/channel-accounts/" + id + "/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("NEEDS_RESYNC"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void anUnknownChannelAccountIsAProblemJsonNotFound() throws Exception {
        mockMvc.perform(get("/api/v1/stellar/channel-accounts/999999"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Channel account not found"));
    }

    /** Pool composition and lease state are operational detail, not public API. */
    @Test
    @WithMockUser
    void anOrdinaryUserCannotSeeOrChangeThePool() throws Exception {
        mockMvc.perform(get("/api/v1/stellar/channel-accounts"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/stellar/channel-accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterChannelAccountRequest(KEY_REF))))
                .andExpect(status().isForbidden());
    }
}
