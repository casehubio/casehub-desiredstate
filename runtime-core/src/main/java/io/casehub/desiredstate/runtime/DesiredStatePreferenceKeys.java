package io.casehub.desiredstate.runtime;

import io.casehub.platform.api.preferences.DoublePreference;
import io.casehub.platform.api.preferences.DurationPreference;
import io.casehub.platform.api.preferences.IntPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

import java.time.Duration;

public final class DesiredStatePreferenceKeys {

    private DesiredStatePreferenceKeys() {}

    public static final PreferenceKey<DurationPreference> RESYNC_INTERVAL =
        new PreferenceKey<>("desiredstate", "resync",
            new DurationPreference(Duration.ofMinutes(5)),
            s -> new DurationPreference(Duration.parse(s)));

    public static final PreferenceKey<DoublePreference> CBR_MIN_RETRIEVAL_CONFIDENCE =
        new PreferenceKey<>("desiredstate", "cbr.min-retrieval-confidence",
            new DoublePreference(0.5), DoublePreference::parse);

    public static final PreferenceKey<DoublePreference> CBR_MIN_ADAPTATION_CONFIDENCE =
        new PreferenceKey<>("desiredstate", "cbr.min-adaptation-confidence",
            new DoublePreference(0.6), DoublePreference::parse);

    public static final PreferenceKey<IntPreference> CBR_MAX_CANDIDATES =
        new PreferenceKey<>("desiredstate", "cbr.max-candidates",
            new IntPreference(3), IntPreference::parse);
}
