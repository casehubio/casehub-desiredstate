import { describe, it, expect } from 'vitest';
import { defineGraph } from '../index.js';

describe('defineGraph', () => {
    it('transforms nodes map to array with id field', () => {
        const result = defineGraph({
            namespace: 'test',
            name: 'simple',
            nodes: {
                'node-a': { type: 'mock', spec: { value: 'hello' } },
                'node-b': { type: 'mock', spec: { value: 'world' } },
            },
        });

        expect(result.kind).toBe('single');
        expect(result.namespace).toBe('test');
        expect(result.name).toBe('simple');
        expect(result.nodes).toHaveLength(2);
        expect(result.nodes[0]).toEqual({
            id: 'node-a', type: 'mock', spec: { value: 'hello' },
        });
        expect(result.nodes[1]).toEqual({
            id: 'node-b', type: 'mock', spec: { value: 'world' },
        });
        expect(result.dependencies).toEqual([]);
    });

    it('extracts dependsOn into dependencies array', () => {
        const result = defineGraph({
            namespace: 'test',
            name: 'deps',
            nodes: {
                'source': { type: 'mock', spec: {} },
                'sink': {
                    type: 'mock',
                    spec: {},
                    dependsOn: ['source'],
                },
            },
        });

        expect(result.dependencies).toEqual([
            { from: 'sink', to: 'source' },
        ]);
        const sinkNode = result.nodes.find(n => n.id === 'sink');
        expect(sinkNode).not.toHaveProperty('dependsOn');
    });

    it('merges inline dependsOn with top-level dependencies', () => {
        const result = defineGraph({
            namespace: 'test',
            name: 'merged',
            nodes: {
                'a': { type: 'mock', spec: {} },
                'b': { type: 'mock', spec: {}, dependsOn: ['a'] },
            },
            dependencies: [{ from: 'a', to: 'external' }],
        });

        expect(result.dependencies).toEqual([
            { from: 'b', to: 'a' },
            { from: 'a', to: 'external' },
        ]);
    });

    it('preserves humanGating on nodes', () => {
        const result = defineGraph({
            namespace: 'test',
            name: 'gated',
            nodes: {
                'gated-node': {
                    type: 'mock',
                    spec: {},
                    humanGating: 'PROVISION_ONLY',
                },
            },
        });

        expect(result.nodes[0].humanGating).toBe('PROVISION_ONLY');
    });

    it('preserves hooks on nodes', () => {
        const result = defineGraph({
            namespace: 'test',
            name: 'hooked',
            nodes: {
                'hooked-node': {
                    type: 'mock',
                    spec: {},
                    hooks: {
                        provision: {
                            pre: [{ verify: { url: 'http://check', timeout: 10 } }],
                            post: [{ notify: { channel: 'ops', message: 'deployed' } }],
                        },
                    },
                },
            },
        });

        expect(result.nodes[0].hooks?.provision?.pre).toHaveLength(1);
        expect(result.nodes[0].hooks?.provision?.post).toHaveLength(1);
    });
});
