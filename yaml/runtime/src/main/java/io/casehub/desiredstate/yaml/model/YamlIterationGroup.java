package io.casehub.desiredstate.yaml.model;

import java.util.List;

public record YamlIterationGroup(String as, Object in) {

    @SuppressWarnings("unchecked")
    public List<Object> inAsList() {
        if (in instanceof List<?> list) {return (List<Object>) list;}
        if (in instanceof String s) {return List.of(s);}
        if (in == null) {return List.of();}
        throw new IllegalArgumentException("iterations.in must be a list or string, got: " + in.getClass());
    }
}
