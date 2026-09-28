package io.casehub.desiredstate.runtime;

import io.casehub.desiredstate.api.ActualState;
import io.casehub.desiredstate.api.CompilationResult;
import io.casehub.desiredstate.api.DesiredStateGraph;
import io.casehub.desiredstate.api.DesiredStateGraphFactory;
import io.casehub.desiredstate.api.SituationRecompiler;
import io.casehub.ras.api.ActiveSituation;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

@ApplicationScoped
public class SituationRecompilerEngine {

    private final List<SituationRecompiler> recompilers;

    public SituationRecompilerEngine(List<SituationRecompiler> recompilers) {
        this.recompilers = recompilers.stream()
            .sorted(Comparator.comparingInt(SituationRecompiler::priority))
            .toList();
    }

    public Optional<CompilationResult> recompile(
            String tenancyId,
            DesiredStateGraph current, ActualState actual,
            ActiveSituation situation, DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.recompile(
                    tenancyId, current, actual, situation, factory);
            if (result.isPresent()) {return result;}
        }
        return Optional.empty();
    }

    public Optional<CompilationResult> situationResolved(
            String tenancyId, String situationId,
            DesiredStateGraph current, ActualState actual,
            DesiredStateGraphFactory factory) {
        for (SituationRecompiler recompiler : recompilers) {
            Optional<CompilationResult> result = recompiler.situationResolved(
                    tenancyId, situationId, current, actual, factory);
            if (result.isPresent()) {return result;}
        }
        return Optional.empty();
    }

}
