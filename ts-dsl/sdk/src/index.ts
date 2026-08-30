export type {
    GraphDef, NodeDef, DependencyRef, HumanGating,
    NodeHooks, HookBlock, HookStep, VerifyStep, NotifyStep, WaitStep,
    LifecycleDef, PhaseDef, CompletionCondition, DependencyDef,
    GraphEnvelope, LifecycleEnvelope, EnvelopeNode, EnvelopePhase,
    NodeTypeMap,
} from './types.js';

import type {
    GraphDef, LifecycleDef, NodeDef, DependencyDef,
    EnvelopeNode, GraphEnvelope, LifecycleEnvelope, NodeTypeMap,
} from './types.js';

function transformNodes(nodeMap: Record<string, NodeDef>): {
    nodes: EnvelopeNode[];
    dependencies: DependencyDef[];
} {
    const nodes: EnvelopeNode[] = [];
    const dependencies: DependencyDef[] = [];
    for (const [id, def] of Object.entries(nodeMap)) {
        const { dependsOn, ...rest } = def as NodeDef & { dependsOn?: string[] };
        nodes.push({ id, ...rest });
        if (dependsOn) {
            for (const dep of dependsOn) {
                dependencies.push({ from: id, to: dep });
            }
        }
    }
    return { nodes, dependencies };
}

export function defineGraph(def: GraphDef): GraphEnvelope {
    const { nodes, dependencies } = transformNodes(def.nodes);
    return {
        kind: 'single',
        namespace: def.namespace,
        name: def.name,
        nodes,
        dependencies: [...dependencies, ...(def.dependencies ?? [])],
    };
}

export function defineLifecycle(def: LifecycleDef): LifecycleEnvelope {
    return {
        kind: 'lifecycle',
        namespace: def.namespace,
        name: def.name,
        phases: def.phases.map(phase => {
            const { nodes, dependencies } = transformNodes(phase.nodes);
            return {
                id: phase.id,
                completionCondition: phase.completionCondition,
                nodes,
                dependencies: [...dependencies, ...(phase.dependencies ?? [])],
            };
        }),
    };
}

export function node<T extends keyof NodeTypeMap>(
    type: T,
    spec: NodeTypeMap[T],
    opts?: { dependsOn?: string[]; humanGating?: 'NONE' | 'PROVISION_ONLY' | 'DEPROVISION_ONLY' | 'ALL' },
): NodeDef {
    return { type, spec, ...opts } as NodeDef;
}
