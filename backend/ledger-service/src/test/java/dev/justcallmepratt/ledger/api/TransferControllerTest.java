package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.app.Page;
import dev.justcallmepratt.ledger.app.TransferService;
import dev.justcallmepratt.ledger.app.TransferService.ItemResult;
import dev.justcallmepratt.ledger.config.SecurityConfig;
import dev.justcallmepratt.ledger.domain.PendingStatus;
import dev.justcallmepratt.ledger.domain.Transfer;
import dev.justcallmepratt.ledger.domain.TransferResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
import static org.mockito.ArgumentMatchers.anyInt;
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

@WebMvcTest(TransferController.class)
@Import(SecurityConfig.class)
class TransferControllerTest {

    @Autowired MockMvc mvc;
    @MockitoBean TransferService service;

    private final UUID debit = UUID.randomUUID();
    private final UUID credit = UUID.randomUUID();

    private String json(UUID id, String amount, String extra) {
        return "{\"id\":\"" + id + "\",\"debitAccountId\":\"" + debit + "\",\"creditAccountId\":\"" + credit
                + "\",\"amount\":" + (amount == null ? "null" : "\"" + amount + "\"")
                + ",\"ledger\":840,\"code\":1" + extra + "}";
    }

    @Test
    void createReturns201AndPassesTheParsedTransferToTheService() throws Exception {
        UUID id = UUID.randomUUID();
        Transfer stored = Samples.transfer(id, debit, credit, 1050);
        when(service.submit(any())).thenReturn(new ItemResult(0, id, TransferResult.CREATED, stored, null));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(id, "1050", ",\"reference\":\"INV-1\",\"timeoutSeconds\":0")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/transfers/" + id))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.amount").value("1050"))
                .andExpect(jsonPath("$.seq").value("7"))
                .andExpect(jsonPath("$.pendingId").value((Object) null))
                .andExpect(jsonPath("$.pendingStatus").value((Object) null));

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(service).submit(captor.capture());
        assertThat(captor.getValue().amount()).isEqualTo(1050L);
        assertThat(captor.getValue().debitAccountId()).isEqualTo(debit);
        assertThat(captor.getValue().reference()).isEqualTo("INV-1");
        assertThat(captor.getValue().flags().linked()).isFalse();
    }

    @Test
    void replayReturns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(any())).thenReturn(
                new ItemResult(0, id, TransferResult.EXISTS, Samples.transfer(id, debit, credit, 5), null));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json(id, "5", "")))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist("Location"));
    }

    @Test
    void pendingTransfersExposeStatusAndExpiry() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(any())).thenReturn(new ItemResult(0, id, TransferResult.CREATED,
                Samples.pendingTransfer(id, debit, credit, 5), null));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(id, "5", ",\"flags\":{\"pending\":true},\"timeoutSeconds\":60")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.pendingStatus").value("PENDING"))
                .andExpect(jsonPath("$.expiresAt").value("2026-11-26T08:01:00Z"))
                .andExpect(jsonPath("$.timeoutSeconds").value(60))
                .andExpect(jsonPath("$.flags.pending").value(true));
    }

    @ParameterizedTest
    @CsvSource({
            "AMOUNT_MUST_BE_POSITIVE,400",
            "LINKED_NOT_ALLOWED,400",
            "DEBIT_ACCOUNT_NOT_FOUND,404",
            "PENDING_TRANSFER_NOT_FOUND,404",
            "EXCEEDS_CREDITS,422",
            "EXCEEDS_DEBITS,422",
            "PENDING_TRANSFER_EXPIRED,422",
            "EXISTS_WITH_DIFFERENT_FIELDS,409"
    })
    void rejectionsBecomeProblemsWithTheResultCode(TransferResult result, int expectedStatus) throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(any())).thenReturn(new ItemResult(0, id, result, null, "because"));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON).content(json(id, "5", "")))
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value(result.code()))
                .andExpect(jsonPath("$.status").value(expectedStatus))
                .andExpect(jsonPath("$.detail").value("because"));
    }

    @Test
    void amountMustBeANonNegativeIntegerString() throws Exception {
        for (String bad : new String[]{"12.5", "-5", "abc", "", "1e3", " 5", "00", "99999999999999999999"}) {
            mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                            .content(json(UUID.randomUUID(), bad, "")))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("invalid_request"))
                    .andExpect(jsonPath("$.errors[0].field").value("amount"));
        }
    }

    @Test
    void amountsBeyondALongAreRejected() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(UUID.randomUUID(), "9223372036854775808", "")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("amount"))
                .andExpect(jsonPath("$.errors[0].message").value("must fit in a signed 64-bit integer"));
    }

    @Test
    void theLargestLongIsAccepted() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.submit(any())).thenReturn(new ItemResult(0, id, TransferResult.CREATED,
                Samples.transfer(id, debit, credit, Long.MAX_VALUE), null));

        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(id, "9223372036854775807", "")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value("9223372036854775807"));
    }

    @Test
    void ledgerAccountsAndCodeAreRequiredUnlessPostingOrVoiding() throws Exception {
        UUID id = UUID.randomUUID();
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"amount\":\"5\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("debitAccountId"));

        when(service.submit(any())).thenReturn(new ItemResult(0, id, TransferResult.CREATED,
                Samples.transfer(id, debit, credit, 5), null));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + id + "\",\"pendingId\":\"" + UUID.randomUUID()
                                + "\",\"flags\":{\"postPendingTransfer\":true}}"))
                .andExpect(status().isCreated());
    }

    @Test
    void fieldRangesAreValidated() throws Exception {
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(UUID.randomUUID(), "5", ",\"timeoutSeconds\":-1")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("timeoutSeconds"));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(json(UUID.randomUUID(), "5", ",\"reference\":\"" + "r".repeat(65) + "\"")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("reference"));
        mvc.perform(post("/api/v1/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"debitAccountId\":\"" + debit + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='id')]").exists());
    }

    @Test
    void batchReturnsOneResultPerTransfer() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(service.submitBatch(any())).thenReturn(List.of(
                new ItemResult(0, first, TransferResult.CREATED, Samples.transfer(first, debit, credit, 5), null),
                new ItemResult(1, second, TransferResult.EXISTS_WITH_DIFFERENT_FIELDS, null, "amount differs")));

        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transfers\":[" + json(first, "5", ",\"flags\":{\"linked\":true}") + ","
                                + json(second, "6", "") + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(2))
                .andExpect(jsonPath("$.results[0].index").value(0))
                .andExpect(jsonPath("$.results[0].id").value(first.toString()))
                .andExpect(jsonPath("$.results[0].code").value("created"))
                .andExpect(jsonPath("$.results[0].transfer.amount").value("5"))
                .andExpect(jsonPath("$.results[1].code").value("exists_with_different_fields"))
                .andExpect(jsonPath("$.results[1].transfer").value((Object) null))
                .andExpect(jsonPath("$.results[1].detail").value("amount differs"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transfer>> captor = ArgumentCaptor.forClass(List.class);
        verify(service).submitBatch(captor.capture());
        assertThat(captor.getValue()).hasSize(2);
        assertThat(captor.getValue().get(0).flags().linked()).isTrue();
        assertThat(captor.getValue().get(1).flags().linked()).isFalse();
    }

    @Test
    void batchSizeIsLimitedToBetweenOneAndOneHundred() throws Exception {
        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transfers\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transfers"));
        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        StringBuilder tooMany = new StringBuilder("{\"transfers\":[");
        for (int i = 0; i < 101; i++) {
            tooMany.append(i == 0 ? "" : ",").append(json(UUID.randomUUID(), "1", ""));
        }
        tooMany.append("]}");
        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON).content(tooMany.toString()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transfers"));
    }

    @Test
    void exactlyOneHundredTransfersAreAccepted() throws Exception {
        when(service.submitBatch(any())).thenReturn(List.of());
        StringBuilder hundred = new StringBuilder("{\"transfers\":[");
        for (int i = 0; i < 100; i++) {
            hundred.append(i == 0 ? "" : ",").append(json(UUID.randomUUID(), "1", ""));
        }
        hundred.append("]}");

        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON).content(hundred.toString()))
                .andExpect(status().isOk());
    }

    @Test
    void batchItemErrorsNameTheIndexedField() throws Exception {
        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transfers\":[" + json(UUID.randomUUID(), "1", "") + ","
                                + json(UUID.randomUUID(), "9223372036854775808", "") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transfers[1].amount"));
        mvc.perform(post("/api/v1/transfers/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"transfers\":[" + json(UUID.randomUUID(), "x", "") + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("transfers[0].amount"));
    }

    @Test
    void getReturnsTheTransferOr404() throws Exception {
        UUID id = UUID.randomUUID();
        UUID missing = UUID.randomUUID();
        when(service.get(id)).thenReturn(Samples.transfer(id, debit, credit, 5));
        when(service.get(missing)).thenThrow(ApiException.transferNotFound(missing));

        mvc.perform(get("/api/v1/transfers/" + id)).andExpect(status().isOk())
                .andExpect(jsonPath("$.debitAccountId").value(debit.toString()));
        mvc.perform(get("/api/v1/transfers/" + missing)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("transfer_not_found"));
    }

    @Test
    void listFiltersByPendingStatusAndPaginates() throws Exception {
        UUID id = UUID.randomUUID();
        when(service.list(eq(PendingStatus.PENDING), eq(10), eq("c1"))).thenReturn(
                new Page<>(List.of(Samples.pendingTransfer(id, debit, credit, 5)), "c2"));
        when(service.list(isNull(), anyInt(), isNull())).thenReturn(new Page<>(List.of(), null));

        mvc.perform(get("/api/v1/transfers").param("pendingStatus", "PENDING").param("limit", "10")
                        .param("cursor", "c1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].pendingStatus").value("PENDING"))
                .andExpect(jsonPath("$.nextCursor").value("c2"));
        mvc.perform(get("/api/v1/transfers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/api/v1/transfers").param("pendingStatus", "WAITING"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("pendingStatus"));
    }
}
