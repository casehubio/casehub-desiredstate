package io.casehub.desiredstate.yaml.model;

import com.fasterxml.jackson.annotation.JsonProperty;

public record YamlModuleParameter(
        String type,
        boolean required,
        @JsonProperty("default") String defaultValue) {

    public YamlModuleParameter {
        if (type == null) {type = "string";}
    }
}
