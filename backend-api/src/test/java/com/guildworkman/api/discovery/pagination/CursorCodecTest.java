package com.guildworkman.api.discovery.pagination;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.guildworkman.api.discovery.InvalidSearchCursorException;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {

    private final CursorCodec codec = new CursorCodec(new ObjectMapper());

    @Test
    void roundTripsAPosition() {
        SearchCursor original = new SearchCursor(0.7345216, 4271L);
        SearchCursor decoded = codec.decode(codec.encode(original));
        assertThat(decoded.workerId()).isEqualTo(4271L);
        assertThat(decoded.rankScore()).isEqualTo(0.7345216);
    }

    @Test
    void encodedCursorIsUrlSafeAndUnpadded() {
        String encoded = codec.encode(new SearchCursor(0.5, 1L));
        assertThat(encoded).doesNotContain("+", "/", "=");
    }

    @Test
    void rejectsGarbage() {
        assertThatThrownBy(() -> codec.decode("not-base64!!"))
                .isInstanceOf(InvalidSearchCursorException.class);
    }

    @Test
    void rejectsNullAndBlank() {
        assertThatThrownBy(() -> codec.decode(null)).isInstanceOf(InvalidSearchCursorException.class);
        assertThatThrownBy(() -> codec.decode("   ")).isInstanceOf(InvalidSearchCursorException.class);
    }

    @Test
    void rejectsValidBase64ThatIsNotOurJson() {
        String notOurs = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("hello world".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(notOurs))
                .isInstanceOf(InvalidSearchCursorException.class);
    }

    @Test
    void rejectsAWrongVersion() {
        String v99 = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":99,\"s\":0.5,\"id\":1}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(v99))
                .isInstanceOf(InvalidSearchCursorException.class)
                .hasMessageContaining("version");
    }

    @Test
    void rejectsMissingPositionFields() {
        String noId = Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"v\":1,\"s\":0.5}".getBytes(StandardCharsets.UTF_8));
        assertThatThrownBy(() -> codec.decode(noId))
                .isInstanceOf(InvalidSearchCursorException.class);
    }
}
