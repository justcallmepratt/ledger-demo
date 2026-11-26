package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.app.AccountService.CreateResult;
import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.app.Page;
import dev.justcallmepratt.ledger.config.SecurityConfig;
import dev.justcallmepratt.ledger.domain.Account;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.persistence.BalanceHistoryEntry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
class AccountControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService service;

    private static String body(UUID id, String extra) {
        return "{\"id\":\"" + id + "\",\"ledger\":840,\"code\":1,\"name\":\"Alice wallet\"" + extra + "}";
    }

    @Test
    void createReturns201WithLocationAndStringCounters() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(new CreateResult(Samples.account(id), true));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(body(id, ",\"flags\":{\"debitsMustNotExceedCredits\":true,\"history\":true}")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/accounts/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.ledger").value(840))
                .andExpect(jsonPath("$.name").value("Alice wallet"))
                .andExpect(jsonPath("$.flags.debitsMustNotExceedCredits").value(true))
                .andExpect(jsonPath("$.flags.closed").value(false))
                .andExpect(jsonPath("$.debitsPending").value("1"))
                .andExpect(jsonPath("$.debitsPosted").value("2"))
                .andExpect(jsonPath("$.creditsPending").value("3"))
                .andExpect(jsonPath("$.creditsPosted").value("4"))
                .andExpect(jsonPath("$.createdAt").value("2026-11-26T08:00:00Z"));

        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        verify(service).create(captor.capture());
        assertThat(captor.getValue().flags().debitsMustNotExceedCredits()).isTrue();
        assertThat(captor.getValue().flags().creditsMustNotExceedDebits()).isFalse();
        assertThat(captor.getValue().flags().history()).isTrue();
    }

    @Test
    void identicalReplayReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.create(any())).thenReturn(new CreateResult(Samples.account(id), false));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content(body(id, "")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void conflictingReplayIsAProblemWithCode() throws Exception {
        when(service.create(any())).thenThrow(ApiException.accountDiffers("name"));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "")))
                .andExpect(status().isConflict())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.code").value("exists_with_different_fields"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("name")));
    }

    @Test
    void validationErrorsListTheFields() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ledger\":0,\"code\":70000,\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.errors[?(@.field=='id')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='ledger')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='code')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='name')]").exists());
    }

    @Test
    void nameLongerThan100CharactersIsRejected() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), "").replace("Alice wallet", "x".repeat(101))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void closedIsNotAllowedAtCreation() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), ",\"flags\":{\"closed\":true}")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("flags.closed"));
    }

    @Test
    void malformedJsonAndBadUuidsAreBadRequests() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"nope\",\"ledger\":840,\"code\":1,\"name\":\"x\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getReturnsTheAccountOr404() throws Exception {
        UUID id = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(service.get(id)).thenReturn(Samples.account(id));
        when(service.get(missing)).thenThrow(ApiException.accountNotFound(missing));

        mvc.perform(get("/api/v1/accounts/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
        mvc.perform(get("/api/v1/accounts/" + missing)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("account_not_found"));
    }

    @Test
    void listIsPaginated() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        when(service.list(2, null)).thenReturn(new Page<>(List.of(Samples.account(a), Samples.account(b)), "abc"));
        when(service.list(50, "abc")).thenReturn(new Page<>(List.of(Samples.account(a)), null));

        mvc.perform(get("/api/v1/accounts").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.nextCursor").value("abc"));
        mvc.perform(get("/api/v1/accounts").param("cursor", "abc"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.nextCursor").value((Object) null));
    }

    @Test
    void invalidLimitIsABadRequest() throws Exception {
        when(service.list(eq(0), isNull())).thenThrow(ApiException.invalid("limit", "must be between 1 and 200"));

        mvc.perform(get("/api/v1/accounts").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
        mvc.perform(get("/api/v1/accounts").param("limit", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("limit"));
    }

    @Test
    void accountTransfersAreServedAsATransferPage() throws Exception {
        UUID id = UUID.randomUUID();
        Transfer t = Samples.transfer(UUID.randomUUID(), id, UUID.randomUUID(), 1050);
        when(service.transfers(id, 50, null)).thenReturn(new Page<>(List.of(t), "next"));

        mvc.perform(get("/api/v1/accounts/" + id + "/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].amount").value("1050"))
                .andExpect(jsonPath("$.items[0].seq").value("7"))
                .andExpect(jsonPath("$.items[0].timestamp").value("2026-11-26T08:00:00Z"))
                .andExpect(jsonPath("$.items[0].flags.linked").value(false))
                .andExpect(jsonPath("$.items[0].reference").value("INV-1"))
                .andExpect(jsonPath("$.nextCursor").value("next"));
    }

    @Test
    void balancesAreServedOrRefused() throws Exception {
        UUID id = UUID.randomUUID();
        UUID plain = UUID.randomUUID();
        UUID transferId = UUID.randomUUID();
        when(service.balances(id, 50, null)).thenReturn(new Page<>(
                List.of(new BalanceHistoryEntry(9L, transferId, 7L, Samples.T, 1, 2, 3, 4)), null));
        when(service.balances(plain, 50, null)).thenThrow(ApiException.historyNotEnabled(plain));

        mvc.perform(get("/api/v1/accounts/" + id + "/balances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].transferId").value(transferId.toString()))
                .andExpect(jsonPath("$.items[0].seq").value("7"))
                .andExpect(jsonPath("$.items[0].debitsPending").value("1"))
                .andExpect(jsonPath("$.items[0].creditsPosted").value("4"));
        mvc.perform(get("/api/v1/accounts/" + plain + "/balances"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("history_not_enabled"));
    }
}
