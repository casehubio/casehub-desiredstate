---
layout: post
title: "When Widening a Map Type Quietly Rewrites Your Data"
date: 2026-09-28
entry_type: note
subtype: diary
projects: [casehubio/casehub-desiredstate]
tags: [yaml, jackson, variable-resolution, type-safety]
series: issue-149-yamlgraph-csv-foreach
---

# When Widening a Map Type Quietly Rewrites Your Data

The YAML surface needed typed variable resolution. A graph author writing `batch_size: 500` should get an integer in the spec, not a string that Jackson silently coerces via `TryConvert`. The fix looked straightforward: widen `YamlGraph.variables` from `Map<String, String>` to `Map<String, Object>`, register the variable source via `VariableResolver.withObjectScope()` instead of the string-only `VariableSource`, and let the resolver's `resolveTyped()` path return the raw value for sole references like `${var.batch_size}`.

The type widening worked exactly as designed — integers preserved, doubles preserved, string interpolation still fell through to `resolveString()` for embedded references like `s3://${var.bucket}/data`. Two new tests confirmed both paths.

Then the boolean tests broke.

`YamlGraph` was a Jackson-deserialized YAML record. With `Map<String, String>`, Jackson's YAML parser had no choice — everything became a string. `monitoring_enabled: yes` stayed `"yes"`. But with `Map<String, Object>`, the parser applied YAML 1.1 type resolution. `yes` became `Boolean.TRUE`. So did `no`, `on`, and `off`. An operator's `${var.monitoring_enabled}` that previously interpolated to `"yes"` now interpolated to `"true"`.

The trigger is the Java type, not the YAML content. Nothing in the YAML changed. The parser's inference path switched because the target type widened. Silent, upstream-invisible, and it changes resolved string values downstream.

The fix was `YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS`, available since Jackson 2.15. It keeps `yes`/`no`/`on`/`off` as strings while preserving typed resolution for integers and doubles. We wired it into both the Quarkus deployment processor and the Spring auto-config — every entry point that deserializes `YamlGraph` from YAML.

The same session also wired CSV data source dispatch into the `ForEachExpander` — YAML authors can now declare tabular data inline and iterate over typed CSV rows with `${each.region.name}` field access. And `VariablePrefixRewriter` normalization at deployment time means bare references like `${batch_size}` get rewritten to `${var.batch_size}` before they reach the resolver.

The boolean coercion was the interesting part. A type widening that looks purely additive — `String` is a subtype of `Object`, nothing should break — turns out to change the parser's inference path in ways that silently mutate data. Worth a garden entry.
