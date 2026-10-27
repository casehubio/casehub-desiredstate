package io.casehub.desiredstate.bridge.terraform;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.msgpack.core.MessageBufferPacker;
import org.msgpack.core.MessagePack;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.Map;

public final class CtyEncoder {

    private static final ObjectMapper JSON = new ObjectMapper();

    private CtyEncoder() {}

    public static byte[] encodeMsgpack(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return new byte[0];
        try (MessageBufferPacker packer = MessagePack.newDefaultBufferPacker()) {
            packValue(packer, value);
            return packer.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode msgpack", e);
        }
    }

    public static byte[] encodeJson(Map<String, Object> value) {
        if (value == null || value.isEmpty()) return new byte[0];
        try {
            return JSON.writeValueAsBytes(value);
        } catch (IOException e) {
            throw new RuntimeException("Failed to encode JSON", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static void packValue(MessageBufferPacker packer, Object value) throws IOException {
        if (value == null) {
            packer.packNil();
        } else if (value instanceof String s) {
            packer.packString(s);
        } else if (value instanceof Boolean b) {
            packer.packBoolean(b);
        } else if (value instanceof BigDecimal bd) {
            if (bd.scale() <= 0 && bd.abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) <= 0) {
                packer.packLong(bd.longValue());
            } else {
                packer.packDouble(bd.doubleValue());
            }
        } else if (value instanceof Number n) {
            if (value instanceof Integer || value instanceof Long) {
                packer.packLong(n.longValue());
            } else {
                packer.packDouble(n.doubleValue());
            }
        } else if (value instanceof Map<?, ?> map) {
            packer.packMapHeader(map.size());
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                packer.packString(entry.getKey().toString());
                packValue(packer, entry.getValue());
            }
        } else if (value instanceof Collection<?> list) {
            packer.packArrayHeader(list.size());
            for (Object item : list) {
                packValue(packer, item);
            }
        } else {
            packer.packString(value.toString());
        }
    }
}
