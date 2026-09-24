package io.casehub.desiredstate.yaml.deployment;

import io.casehub.yaml.core.resolver.VariablePrefixRewriter;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class YamlPrefixNormalizationTest {

    private static final Set<String> KNOWN = Set.of("var", "each", "match", "fault", "module", "params");

    @Test
    void bareVariable_rewritesToVarPrefix() {
        String result = VariablePrefixRewriter.rewrite(
                "${batch_size}", "var", KNOWN, Set.of());
        assertThat(result).isEqualTo("${var.batch_size}");
    }

    @Test
    void alreadyPrefixed_passesThrough() {
        String result = VariablePrefixRewriter.rewrite(
                "${var.batch_size}", "var", KNOWN, Set.of());
        assertThat(result).isEqualTo("${var.batch_size}");
    }

    @Test
    void forEachVariable_rewritesToEachPrefix() {
        String result = VariablePrefixRewriter.rewrite(
                "${region}", "var", KNOWN, Set.of("region"));
        assertThat(result).isEqualTo("${each.region}");
    }

    @Test
    void mixed_rewritesCorrectly() {
        String result = VariablePrefixRewriter.rewrite(
                "s3://${bucket}/${region}/data", "var", KNOWN, Set.of("region"));
        assertThat(result).isEqualTo("s3://${var.bucket}/${each.region}/data");
    }

    @Test
    void deferredPrefixes_passThrough() {
        String result = VariablePrefixRewriter.rewrite(
                "${match.sink.id}", "var", KNOWN, Set.of());
        assertThat(result).isEqualTo("${match.sink.id}");
    }

    @Test
    void noVariables_passesThrough() {
        String result = VariablePrefixRewriter.rewrite(
                "plain-string", "var", KNOWN, Set.of());
        assertThat(result).isEqualTo("plain-string");
    }

    @Test
    void dotPath_rewritesCorrectly() {
        String result = VariablePrefixRewriter.rewrite(
                "${region.name}", "var", KNOWN, Set.of("region"));
        assertThat(result).isEqualTo("${each.region.name}");
    }
}
