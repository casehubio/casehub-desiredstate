package io.casehub.desiredstate.runtime;

import io.casehub.platform.api.preferences.DurationPreference;
import io.casehub.platform.api.preferences.PreferenceKey;

import java.time.Duration;

public final class DesiredStatePreferenceKeys {

    private DesiredStatePreferenceKeys() {}

    public static final PreferenceKey<DurationPreference> RESYNC_INTERVAL =
        new PreferenceKey<>("desiredstate", "resync",
            new DurationPreference(Duration.ofMinutes(5)),
            s -> new DurationPreference(Duration.parse(s)));
}
