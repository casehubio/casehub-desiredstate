import { describe, it, expect } from 'vitest';
import { node } from '../index.js';

describe('node', () => {
    it('returns NodeDef with correct type and spec', () => {
        const n = node('mock', { value: 'hello' });
        expect(n.type).toBe('mock');
        expect(n.spec).toEqual({ value: 'hello' });
    });

    it('passes through dependsOn', () => {
        const n = node('mock', { value: 'x' }, {
            dependsOn: ['other'],
        });
        expect((n as any).dependsOn).toEqual(['other']);
    });

    it('passes through humanGating', () => {
        const n = node('mock', { value: 'x' }, {
            humanGating: 'PROVISION_ONLY',
        });
        expect(n.humanGating).toBe('PROVISION_ONLY');
    });

    it('works without opts', () => {
        const n = node('mock', {});
        expect(n.type).toBe('mock');
        expect(n.spec).toEqual({});
        expect(n.humanGating).toBeUndefined();
    });
});
