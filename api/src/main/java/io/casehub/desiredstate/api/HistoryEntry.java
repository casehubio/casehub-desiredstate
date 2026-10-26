package io.casehub.desiredstate.api;

import java.time.Instant;

public record HistoryEntry(long version, Instant recordedAt, String source, String initiatedBy) {}
