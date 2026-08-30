package com.guildworkman.api.discovery.pagination;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.guildworkman.api.discovery.InvalidSearchCursorException;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Encodes / decodes the opaque {@code cursor} string: base64url (no padding) of
 * a tiny versioned JSON object {@code {"v":1,"s":<rankScore>,"id":<workerId>}}.
 *
 * <p>Opaque to clients on purpose — the shape can change under the {@code v} tag
 * without breaking callers, and a client can't hand-craft one to page from an
 * arbitrary score. Anything that doesn't decode cleanly, or carries a version
 * this build doesn't understand, is an {@link InvalidSearchCursorException}
 * ({@code 400}) — never silently treated as "no cursor".
 */
@Component
public class CursorCodec {

    private static final int VERSION = 1;
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final ObjectMapper objectMapper;

    public CursorCodec(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String encode(SearchCursor cursor) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("v", VERSION);
        node.put("s", cursor.rankScore());
        node.put("id", cursor.workerId());
        try {
            return ENCODER.encodeToString(objectMapper.writeValueAsBytes(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            // Serialising three primitives should never fail; treat as a bug, not a client error.
            throw new IllegalStateException("could not encode search cursor", ex);
        }
    }

    public SearchCursor decode(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidSearchCursorException("cursor is empty");
        }
        byte[] json;
        try {
            json = DECODER.decode(raw);
        } catch (IllegalArgumentException ex) {
            throw new InvalidSearchCursorException("cursor is not valid base64url", ex);
        }
        JsonNode node;
        try {
            node = objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new InvalidSearchCursorException("cursor payload is not valid JSON", ex);
        }
        if (node == null || !node.path("v").isInt() || node.path("v").asInt() != VERSION) {
            throw new InvalidSearchCursorException("unsupported cursor version");
        }
        if (!node.path("s").isNumber() || !node.path("id").isIntegralNumber()) {
            throw new InvalidSearchCursorException("cursor is missing its position fields");
        }
        return new SearchCursor(node.get("s").asDouble(), node.get("id").asLong());
    }
}
