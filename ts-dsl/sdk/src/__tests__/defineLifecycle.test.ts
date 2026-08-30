import { describe, it, expect } from 'vitest';
import { defineLifecycle } from '../index.js';

describe('defineLifecycle', () => {
    it('transforms phases with nodes and dependencies', () => {
        const result = defineLifecycle({
            namespace: 'test',
            name: 'lifecycle',
            phases: [
                {
                    id: 'infra',
                    completionCondition: 'allPresent',
                    nodes: {
                        'db': { type: 'mock', spec: { engine: 'postgres' } },
                    },
                },
                {
                    id: 'app',
                    completionCondition: 'never',
                    nodes: {
                        'api': {
                            type: 'mock',
                            spec: { image: 'api:latest' },
                            dependsOn: ['db'],
                        },
                    },
                },
            ],
        });

        expect(result.kind).toBe('lifecycle');
        expect(result.namespace).toBe('test');
        expect(result.phases).toHaveLength(2);

        expect(result.phases[0].id).toBe('infra');
        expect(result.phases[0].completionCondition).toBe('allPresent');
        expect(result.phases[0].nodes).toHaveLength(1);
        expect(result.phases[0].nodes[0].id).toBe('db');

        expect(result.phases[1].id).toBe('app');
        expect(result.phases[1].completionCondition).toBe('never');
        expect(result.phases[1].dependencies).toEqual([
            { from: 'api', to: 'db' },
        ]);
    });

    it('supports bean completion condition', () => {
        const result = defineLifecycle({
            namespace: 'test',
            name: 'custom',
            phases: [
                {
                    id: 'phase1',
                    completionCondition: { bean: 'myCondition' },
                    nodes: {
                        'a': { type: 'mock', spec: {} },
                    },
                },
            ],
        });

        expect(result.phases[0].completionCondition).toEqual({ bean: 'myCondition' });
    });
});
