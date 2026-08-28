package io.casehub.desiredstate.yaml.model;

import java.util.List;

public record YamlForEach(String as, List<String> in) {
    public YamlForEach {
        if (in == null) {in = List.of();}
    }
}
