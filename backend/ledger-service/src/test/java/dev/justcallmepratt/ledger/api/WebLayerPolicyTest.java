package dev.justcallmepratt.ledger.api;

import dev.justcallmepratt.ledger.app.AccountService;
import dev.justcallmepratt.ledger.config.SecurityConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Security rules, CORS, headers, size limits and the generic problem mapping. */
@WebMvcTest(AccountController.class)
@Import(SecurityConfig.class)
@TestPropertySource(properties = "ledger.web-origin=http://localhost:4200")
class WebLayerPolicyTest {

    @Autowired MockMvc mvc;
    @MockitoBean AccountService service;

    @BeforeEach
    void stubAccount() {
        when(service.get(any())).thenReturn(Samples.account(UUID.randomUUID()));
    }

    @Test
    void everythingOutsideTheApiAndAllowedEndpointsIsDenied() throws Exception {
        mvc.perform(get("/actuator/env")).andExpect(status().isForbidden());
        mvc.perform(get("/")).andExpect(status().isForbidden());
        mvc.perform(get("/admin")).andExpect(status().isForbidden());
    }

    @Test
    void anonymousApiCallsAreAllowedInPhaseOne() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID())).andExpect(status().isOk());
    }

    @Test
    void securityHeadersAreSet() throws Exception {
        mvc.perform(get("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string("Referrer-Policy", "no-referrer"))
                .andExpect(header().string("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'"))
                .andExpect(header().exists("X-Frame-Options"))
                .andExpect(header().exists("Cache-Control"));
    }

    @Test
    void corsAllowsOnlyTheConfiguredWebOrigin() throws Exception {
        mvc.perform(options("/api/v1/accounts").header("Origin", "http://localhost:4200")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:4200"));
        mvc.perform(options("/api/v1/accounts").header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden());
    }

    @Test
    void oversizedBodiesAreRejectedBeforeTheyAreRead() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + "a".repeat(300_000) + "\"}"))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("payload_too_large"));
    }

    @Test
    void chunkedBodiesWithoutLengthAreRejected() throws Exception {
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .header("Transfer-Encoding", "chunked"))
                .andExpect(status().is(411))
                .andExpect(jsonPath("$.code").value("length_required"));
    }

    @Test
    void unknownPathsMethodsAndMediaTypesKeepTheirStatusAsProblems() throws Exception {
        mvc.perform(get("/api/v1/nothing-here"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(delete("/api/v1/accounts/" + UUID.randomUUID()))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
        mvc.perform(post("/api/v1/accounts").contentType(MediaType.TEXT_PLAIN).content("hello"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("unsupported_media_type"));
    }

    @Test
    void aBadPathVariableIsABadRequest() throws Exception {
        mvc.perform(get("/api/v1/accounts/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"))
                .andExpect(jsonPath("$.errors[0].field").value("id"));
    }

    @Test
    void unexpectedFailuresAreGenericAndDoNotLeakDetails() throws Exception {
        when(service.create(any())).thenThrow(new IllegalStateException("secret internal detail"));

        mvc.perform(post("/api/v1/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + UUID.randomUUID() + "\",\"ledger\":840,\"code\":1,\"name\":\"x\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("internal_error"))
                .andExpect(jsonPath("$.detail").value("Internal server error"));
    }
}
