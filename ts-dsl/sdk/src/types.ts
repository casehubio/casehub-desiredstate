import type { NodeTypeMap } from './generated/node-type-map.js';

export type { NodeTypeMap };

export interface GraphDef {
    namespace: string;
    name: string;
    nodes: Record<string, NodeDef>;
    dependencies?: DependencyDef[];
}

export type NodeDef = {
    [T in keyof NodeTypeMap]: {
        type: T;
        spec: NodeTypeMap[T];
        dependsOn?: DependencyRef[];
        humanGating?: HumanGating;
        hooks?: NodeHooks;
    }
}[keyof NodeTypeMap];

export type DependencyRef = string;

export type HumanGating = 'NONE' | 'PROVISION_ONLY' | 'DEPROVISION_ONLY' | 'ALL';

export interface NodeHooks {
    provision?: HookBlock;
    deprovision?: HookBlock;
}

export interface HookBlock {
    pre?: HookStep[];
    post?: HookStep[];
}

export type HookStep =
    | { verify: VerifyStep }
    | { notify: NotifyStep }
    | { wait: WaitStep };

export interface VerifyStep {
    url: string;
    timeout?: number;
}

export interface NotifyStep {
    channel: string;
    message: string;
}

export interface WaitStep {
    seconds: number;
}

export interface LifecycleDef {
    namespace: string;
    name: string;
    phases: PhaseDef[];
}

export interface PhaseDef {
    id: string;
    completionCondition: CompletionCondition;
    nodes: Record<string, NodeDef>;
    dependencies?: DependencyDef[];
}

export type CompletionCondition = 'allPresent' | 'never' | { bean: string };

export interface DependencyDef {
    from: string;
    to: string;
}

export interface EnvelopeNode {
    id: string;
    type: string;
    spec: Record<string, unknown>;
    humanGating?: HumanGating;
    hooks?: NodeHooks;
}

export interface GraphEnvelope {
    kind: 'single';
    namespace: string;
    name: string;
    nodes: EnvelopeNode[];
    dependencies: DependencyDef[];
}

export interface EnvelopePhase {
    id: string;
    completionCondition: CompletionCondition;
    nodes: EnvelopeNode[];
    dependencies: DependencyDef[];
}

export interface LifecycleEnvelope {
    kind: 'lifecycle';
    namespace: string;
    name: string;
    phases: EnvelopePhase[];
}
