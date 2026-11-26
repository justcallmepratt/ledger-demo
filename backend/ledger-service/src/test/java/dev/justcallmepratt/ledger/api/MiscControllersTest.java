package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.app.ActivityService;
import dev.justcallmepratt.ledger.app.ApiException;
import dev.justcallmepratt.ledger.app.ReconciliationReport;
import dev.justcallmepratt.ledger.app.ReconciliationService;
import dev.justcallmepratt.ledger.config.SecurityConfig;
import dev.justcallmepratt.ledger.domain.Ledger;
import dev.justcallmepratt.ledger.events.ActivityBroadcaster;
import dev.justcallmepratt.ledger.persistence.ActivityEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest({LedgerController.class, ActivityController.class, ReconciliationController.class})
@Import(SecurityConfig.class)
class MiscControllersTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService accountService;
    @MockitoBean ActivityService activityService;
    @MockitoBean ActivityBroadcaster broadcaster;
    @MockitoBean ReconciliationService reconciliation;

    @Test
    void ledgersListIdCurrencyAndScale() throws Exception {
        when(accountService.ledgers()).thenReturn(List.of(new Ledger(840, "USD", 2)));

        mvc.perform(get("/api/v1/ledgers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(840))
                .andExpect(jsonPath("$[0].currency").value("USD"))
                .andExpect(jsonPath("$[0].assetScale").value(2));
    }

    @Test
    void activityIsABareArrayNewestFirst() throws Exception {
        UUID event = UUID.randomUUID();
        UUID transfer = UUID.randomUUID();
        when(activityService.latest(50)).thenReturn(List.of(new ActivityEntry(event, "transfer.created", 12L, transfer,
                null, 1050L, 840, Samples.T, "Transfer created: 1050 (ledger 840)")));

        mvc.perform(get("/api/v1/activity"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].eventId").value(event.toString()))
                .andExpect(jsonPath("$[0].type").value("transfer.created"))
                .andExpect(jsonPath("$[0].seq").value("12"))
                .andExpect(jsonPath("$[0].transferId").value(transfer.toString()))
                .andExpect(jsonPath("$[0].accountId").value((Object) null))
                .andExpect(jsonPath("$[0].amount").value("1050"))
                .andExpect(jsonPath("$[0].ledger").value(840))
                .andExpect(jsonPath("$[0].occurredAt").value("2026-11-26T08:00:00Z"));
    }

    @Test
    void activityLimitIsValidated() throws Exception {
        when(activityService.latest(500)).thenThrow(ApiException.invalid("limit", "must be between 1 and 200"));

        mvc.perform(get("/api/v1/activity").param("limit", "500"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void streamIsServerSentEventsAndPassesLastEventId() throws Exception {
        SseEmitter emitter = new SseEmitter();
        emitter.send(SseEmitter.event().name("activity").id("abc").data("{\"x\":1}", MediaType.APPLICATION_JSON));
        emitter.complete();
        when(broadcaster.subscribe("abc")).thenReturn(emitter);

        MvcResult started = mvc.perform(get("/api/v1/activity/stream").accept(MediaType.TEXT_EVENT_STREAM)
                        .header("Last-Event-ID", "abc"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(started))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:activity")))
                .andExpect(content().string(containsString("id:abc")));
        verify(broadcaster).subscribe("abc");
    }

    @Test
    void reconciliationReportIsServedAsIs() throws Exception {
        UUID account = UUID.randomUUID();
        when(reconciliation.run()).thenReturn(new ReconciliationReport(Samples.T, false,
                List.of(new ReconciliationReport.LedgerLine(840, "105", "100", "0", "0", false)),
                List.of(new ReconciliationReport.AccountMismatch(account, "debitsPosted", "105", "100"))));

        mvc.perform(get("/api/v1/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.checkedAt").value("2026-11-26T08:00:00Z"))
                .andExpect(jsonPath("$.balanced").value(false))
                .andExpect(jsonPath("$.ledgers[0].ledger").value(840))
                .andExpect(jsonPath("$.ledgers[0].debitsPosted").value("105"))
                .andExpect(jsonPath("$.ledgers[0].balanced").value(false))
                .andExpect(jsonPath("$.accountMismatches[0].accountId").value(account.toString()))
                .andExpect(jsonPath("$.accountMismatches[0].recomputed").value("100"));
    }

    @Test
    void reportProblemCountCountsMismatchesAndUnbalancedLedgers() {
        ReconciliationReport report = new ReconciliationReport(Samples.T, false,
                List.of(new ReconciliationReport.LedgerLine(840, "1", "2", "0", "0", false),
                        new ReconciliationReport.LedgerLine(978, "0", "0", "0", "0", true)),
                List.of(new ReconciliationReport.AccountMismatch(UUID.randomUUID(), "creditsPosted", "1", "2")));

        org.assertj.core.api.Assertions.assertThat(report.problemCount()).isEqualTo(2);
    }
}
