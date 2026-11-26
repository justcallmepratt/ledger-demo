package dev.justcallmepratt.ledger.app;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorsTest {

    private static String b64(String raw) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes());
    }

    @Test
    void longCursorsRoundTripAndAreOpaque() {
        String cursor = Cursors.encodeLong(12345L);

        assertThat(Cursors.decodeLong(cursor)).isEqualTo(12345L);
        assertThat(cursor).doesNotContain("12345").matches("[A-Za-z0-9_-]+");
    }

    @Test
    void badLongCursorsAreRejected() {
        assertThatThrownBy(() -> Cursors.decodeLong("***")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeLong(b64("x:5"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeLong(b64("n:abc"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeLong(b64("n:0"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeLong(b64("n:-4"))).isInstanceOf(ApiException.class)
                .satisfies(e -> assertThat(((ApiException) e).status().value()).isEqualTo(400));
    }

    @Test
    void accountCursorsKeepMicrosecondPrecision() {
        Instant createdAt = Instant.parse("2026-11-26T08:00:00.123456Z");
        UUID id = UUID.randomUUID();

        Object[] decoded = Cursors.decodeAccount(Cursors.encodeAccount(createdAt, id));

        assertThat(decoded[0]).isEqualTo(createdAt);
        assertThat(decoded[1]).isEqualTo(id);
    }

    @Test
    void badAccountCursorsAreRejected() {
        assertThatThrownBy(() -> Cursors.decodeAccount("***")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeAccount(b64("a:1"))).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeAccount(b64("x:1:" + UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeAccount(b64("a:abc:" + UUID.randomUUID())))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.decodeAccount(b64("a:1:not-a-uuid"))).isInstanceOf(ApiException.class);
    }

    @Test
    void limitsMustBeBetweenOneAndTwoHundred() {
        assertThat(Cursors.checkLimit(1)).isEqualTo(1);
        assertThat(Cursors.checkLimit(200)).isEqualTo(200);
        assertThatThrownBy(() -> Cursors.checkLimit(0)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> Cursors.checkLimit(201)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.code()).isEqualTo("invalid_request");
            assertThat(e.errors()).extracting(FieldIssue::field).containsExactly("limit");
        });
    }
}
