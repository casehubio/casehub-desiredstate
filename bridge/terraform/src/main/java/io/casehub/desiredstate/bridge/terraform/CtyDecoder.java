package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.core.MessagePack;
import org.msgpack.core.MessageUnpacker;
import org.msgpack.core.MessageFormat;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class CtyDecoder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CtyDecoder() {}

    public static Map<String, Object> decodeMsgpack(byte[] data) {
        if (data == null || data.length == 0) return Map.of();
        try (MessageUnpacker unpacker = MessagePack.newDefaultUnpacker(data)) {
            Object result = unpackValue(unpacker);
            if (result instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) result;
                return map;
            }
            return Map.of();
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode msgpack", e);
        }
    }

    public static Map<String, Object> decodeJson(byte[] data) {
        if (data == null || data.length == 0) return Map.of();
        try {
            return JSON.readValue(data, new TypeReference<>() {});
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode JSON", e);
        }
    }

    public static Object decodeJsonValue(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            return JSON.readValue(json, Object.class);
        } catch (IOException e) {
            throw new RuntimeException("Failed to decode JSON value", e);
        }
    }

    private static Object unpackValue(MessageUnpacker unpacker) throws IOException {
        if (!unpacker.hasNext()) return null;
        MessageFormat format = unpacker.getNextFormat();
        return switch (format.getValueType()) {
            case NIL -> { unpacker.unpackNil(); yield null; }
            case BOOLEAN -> unpacker.unpackBoolean();
            case INTEGER -> new BigDecimal(unpacker.unpackLong());
            case FLOAT -> new BigDecimal(unpacker.unpackDouble());
            case STRING -> unpacker.unpackString();
            case BINARY -> unpacker.readPayload(unpacker.unpackBinaryHeader());
            case ARRAY -> {
                int size = unpacker.unpackArrayHeader();
                List<Object> list = new ArrayList<>(size);
                for (int i = 0; i < size; i++) {
                    list.add(unpackValue(unpacker));
                }
                yield list;
            }
            case MAP -> {
                int size = unpacker.unpackMapHeader();
                Map<String, Object> map = new LinkedHashMap<>(size);
                for (int i = 0; i < size; i++) {
                    String key = unpacker.unpackString();
                    map.put(key, unpackValue(unpacker));
                }
                yield map;
            }
            case EXTENSION -> {
                var ext = unpacker.unpackExtensionTypeHeader();
                unpacker.readPayload(ext.getLength());
                yield null;
            }
        };
    }
}
