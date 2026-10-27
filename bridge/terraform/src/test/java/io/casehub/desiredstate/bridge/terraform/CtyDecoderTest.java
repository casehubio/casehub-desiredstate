package io.casehub.desiredstate.bridge.terraform;

import org.junit.jupiter.api.Test;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class CtyDecoderTest {

    @Test
    void decodeJsonString() {
        var result = CtyDecoder.decodeJson("{\"name\": \"hello\"}".getBytes());
        assertThat(result).containsEntry("name", "hello");
    }

    @Test
    void decodeJsonNumber() {
        var result = CtyDecoder.decodeJson("{\"count\": 42}".getBytes());
        assertThat(result).containsEntry("count", 42);
    }

    @Test
    void decodeJsonBoolean() {
        var result = CtyDecoder.decodeJson("{\"enabled\": true}".getBytes());
        assertThat(result).containsEntry("enabled", true);
    }

    @Test
    void decodeJsonNull() {
        var result = CtyDecoder.decodeJson("{\"value\": null}".getBytes());
        assertThat(result).containsKey("value");
        assertThat(result.get("value")).isNull();
    }

    @Test
    void decodeJsonArray() {
        var result = CtyDecoder.decodeJson("{\"items\": [\"a\", \"b\"]}".getBytes());
        assertThat(result.get("items")).isInstanceOf(List.class);
        @SuppressWarnings("unchecked")
        List<String> items = (List<String>) result.get("items");
        assertThat(items).containsExactly("a", "b");
    }

    @Test
    void decodeJsonNestedObject() {
        var result = CtyDecoder.decodeJson("{\"tags\": {\"Name\": \"web\"}}".getBytes());
        assertThat(result.get("tags")).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> tags = (Map<String, Object>) result.get("tags");
        assertThat(tags).containsEntry("Name", "web");
    }

    @Test
    void decodeEmptyJsonReturnsEmptyMap() {
        assertThat(CtyDecoder.decodeJson(new byte[0])).isEmpty();
        assertThat(CtyDecoder.decodeJson(null)).isEmpty();
    }

    @Test
    void decodeMsgpackString() throws IOException {
        byte[] packed = packMap(Map.of("name", "hello"));
        var result = CtyDecoder.decodeMsgpack(packed);
        assertThat(result).containsEntry("name", "hello");
    }

    @Test
    void decodeMsgpackNumber() throws IOException {
        byte[] packed = packMap(Map.of("count", 42));
        var result = CtyDecoder.decodeMsgpack(packed);
        assertThat(result).containsEntry("count", new BigDecimal(42));
    }

    @Test
    void decodeMsgpackBoolean() throws IOException {
        byte[] packed = packMap(Map.of("enabled", true));
        var result = CtyDecoder.decodeMsgpack(packed);
        assertThat(result).containsEntry("enabled", true);
    }

    @Test
    void decodeMsgpackEmptyReturnsEmptyMap() {
        assertThat(CtyDecoder.decodeMsgpack(new byte[0])).isEmpty();
        assertThat(CtyDecoder.decodeMsgpack(null)).isEmpty();
    }

    @Test
    void encodeDecodeJsonRoundTrip() {
        var original = Map.<String, Object>of("name", "test", "count", 42, "enabled", true);
        byte[] encoded = CtyEncoder.encodeJson(original);
        var decoded = CtyDecoder.decodeJson(encoded);
        assertThat(decoded).containsEntry("name", "test");
        assertThat(decoded).containsEntry("count", 42);
        assertThat(decoded).containsEntry("enabled", true);
    }

    @Test
    void encodeDecodeMsgpackRoundTrip() {
        var original = Map.<String, Object>of("name", "test", "count", 42, "enabled", true);
        byte[] encoded = CtyEncoder.encodeMsgpack(original);
        var decoded = CtyDecoder.decodeMsgpack(encoded);
        assertThat(decoded).containsEntry("name", "test");
        assertThat(decoded).containsEntry("enabled", true);
    }

    @Test
    void decodeJsonValueString() {
        assertThat(CtyDecoder.decodeJsonValue("\"hello\"")).isEqualTo("hello");
    }

    @Test
    void decodeJsonValueNull() {
        assertThat(CtyDecoder.decodeJsonValue("null")).isNull();
        assertThat(CtyDecoder.decodeJsonValue(null)).isNull();
        assertThat(CtyDecoder.decodeJsonValue("")).isNull();
    }

    private byte[] packMap(Map<String, Object> map) throws IOException {
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packer.packMapHeader(map.size());
            for (var entry : map.entrySet()) {
                packer.packString(entry.getKey());
                Object v = entry.getValue();
                if (v instanceof String s) packer.packString(s);
                else if (v instanceof Integer i) packer.packLong(i);
                else if (v instanceof Boolean b) packer.packBoolean(b);
                else packer.packNil();
            }
            return packer.toByteArray();
        }
    }
}
