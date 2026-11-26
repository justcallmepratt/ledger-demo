package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The documented HTTP contract end to end: JSON in, real PostgreSQL, JSON out. */
class ApiFlowIntegrationTest extends IntegrationTestBase {

    private static String accountJson(UUID id, String name, String flags) {
        return "{\"id\":\"" + id + "\",\"ledger\":840,\"code\":1,\"name\":\"" + name + "\",\"flags\":" + flags + "}";
    }

    private static String transferJson(UUID id, UUID debit, UUID credit, String amount, String extra) {
        return "{\"id\":\"" + id + "\",\"debitAccountId\":\"" + debit + "\",\"creditAccountId\":\"" + credit
                + "\",\"amount\":\"" + amount + "\",\"ledger\":840,\"code\":1" + extra + "}";
    }

    @Test
    void accountsTransfersPendingAndPagingOverHttp() throws Exception {
        UUID alice = UUID.randomUUID();
        UUID bob = UUID.randomUUID();
        UUID treasury = UUID.randomUUID();

        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(alice, "Alice wallet",
                                "{\"debitsMustNotExceedCredits\":true,\"history\":true}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + alice))
                .andExpect(jsonPath("$.flags.debitsMustNotExceedCredits").value(true))
                .andExpect(jsonPath("$.flags.history").value(true))
                .andExpect(jsonPath("$.flags.closed").value(false))
                .andExpect(jsonPath("$.debitsPosted").value("0"));
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(alice, "Alice wallet",
                                "{\"debitsMustNotExceedCredits\":true,\"history\":true}")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(alice, "Someone else", "{}")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("exists_with_different_fields"));
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(accountJson(bob, "Bob", "{}"))).andExpect(status().isCreated());
        mockMvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                .content(accountJson(treasury, "Treasury", "null"))).andExpect(status().isCreated());

        // fund alice, then spend more than she has
        UUID fundingId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(fundingId, treasury, alice, "1050", ",\"reference\":\"INV-1\"")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("1050"))
                .andExpect(jsonPath("$.seq").isString())
                .andExpect(jsonPath("$.reference").value("INV-1"))
                .andExpect(jsonPath("$.pendingStatus").doesNotExist());
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(fundingId, treasury, alice, "1050", ",\"reference\":\"INV-1\"")))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), alice, bob, "1051", "")))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("exceeds_credits"));
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), alice, UUID.randomUUID(), "5", "")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("credit_account_not_found"));
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), alice, bob, "0", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("amount_must_be_positive"));

        // two-phase: authorise, capture partially
        UUID pendingId = UUID.randomUUID();
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(pendingId, alice, bob, "500",
                                ",\"flags\":{\"pending\":true},\"timeoutSeconds\":3600")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingStatus").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").isString());
        mockMvc.perform(get("/api/v1/transfers").param("pendingStatus", "PENDING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(pendingId.toString()))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + UUID.randomUUID() + "\",\"pendingId\":\"" + pendingId
                                + "\",\"amount\":\"200\",\"flags\":{\"postPendingTransfer\":true}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.debitAccountId").value(alice.toString()))
                .andExpect(jsonPath("$.amount").value("200"))
                .andExpect(jsonPath("$.pendingId").value(pendingId.toString()));
        mockMvc.perform(get("/api/v1/transfers/" + pendingId))
                .andExpect(jsonPath("$.pendingStatus").value("POSTED"));
        mockMvc.perform(get("/api/v1/accounts/" + alice))
                .andExpect(jsonPath("$.debitsPosted").value("200"))
                .andExpect(jsonPath("$.debitsPending").value("0"))
                .andExpect(jsonPath("$.creditsPosted").value("1050"));

        // listings and history
        MvcResult page = mockMvc.perform(get("/api/v1/accounts/" + alice + "/transfers").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn();
        String cursor = com.jayway.jsonpath.JsonPath.read(page.getResponse().getContentAsString(), "$.nextCursor");
        mockMvc.perform(get("/api/v1/accounts/" + alice + "/transfers").param("limit", "2").param("cursor", cursor))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
        mockMvc.perform(get("/api/v1/accounts/" + alice + "/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].debitsPosted").value("200"));
        mockMvc.perform(get("/api/v1/accounts/" + bob + "/balances"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("history_not_enabled"));
        mockMvc.perform(get("/api/v1/accounts").param("limit", "2"))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").isString());
        mockMvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("account_not_found"));
        mockMvc.perform(get("/api/v1/ledgers"))
                .andExpect(jsonPath("$[?(@.id==840)].currency").value("USD"))
                .andExpect(jsonPath("$[?(@.id==840)].assetScale").value(2));
        mockMvc.perform(get("/api/v1/reconciliation"))
                .andExpect(jsonPath("$.balanced").value(true));
    }

    @Test
    void batchEndpointReportsPerItemResults() throws Exception {
        UUID a = newAccount().id();
        UUID b = newAccount().id();
        UUID ok = UUID.randomUUID();
        UUID chain1 = UUID.randomUUID();
        UUID chain2 = UUID.randomUUID();
        String body = "{\"transfers\":["
                + transferJson(ok, a, b, "10", "") + ","
                + transferJson(chain1, a, b, "20", ",\"flags\":{\"linked\":true}") + ","
                + transferJson(chain2, a, b, "0", "") + "]}";

        mockMvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(3))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].code").value("created"))
                .andExpect(jsonPath("$.results[0].transfer.amount").value("10"))
                .andExpect(jsonPath("$.results[1].code").value("linked_event_failed"))
                .andExpect(jsonPath("$.results[1].transfer").value((Object) null))
                .andExpect(jsonPath("$.results[2].code").value("amount_must_be_positive"));
        assertThat(count("transfers")).isEqualTo(1);

        mockMvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.results[0].code").value("exists"));
    }

    @Test
    void validationFailuresAreProblemDetailsWithFieldErrors() throws Exception {
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "12.5", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                                "9223372036854775808", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"));
        mockMvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "1",
                                ",\"flags\":{\"linked\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("linked_not_allowed"));
    }
}
