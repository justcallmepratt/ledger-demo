package dev.justcallmepratt.ledger.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * Rejects oversized request bodies before they are read: a declared Content-Length above
 * {@value #MAX_BODY_BYTES} bytes gets 413, and a body without Content-Length (chunked) gets
 * 411. The largest legitimate request (a batch of 100 transfers) is far below the limit.
 */
@Component
public class RequestSizeLimitFilter extends OncePerRequestFilter {

    public static final long MAX_BODY_BYTES = 262_144L;
    private static final Set<String> BODY_METHODS = Set.of("POST", "PUT", "PATCH");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if (BODY_METHODS.contains(request.getMethod())) {
            long length = request.getContentLengthLong();
            if (length > MAX_BODY_BYTES) {
                reject(response, 413, "payload_too_large", "Request body exceeds " + MAX_BODY_BYTES + " bytes");
                return;
            }
            if (length < 0 && request.getHeader("Transfer-Encoding") != null) {
                reject(response, 411, "length_required", "Requests with a body must declare Content-Length");
                return;
            }
        }
        chain.doFilter(request, response);
    }

    private static void reject(HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write("{\"type\":\"urn:ledger-core:problem:" + code + "\",\"title\":\""
                + (status == 413 ? "Content Too Large" : "Length Required") + "\",\"status\":" + status
                + ",\"detail\":\"" + detail + "\",\"code\":\"" + code + "\"}");
    }
}
