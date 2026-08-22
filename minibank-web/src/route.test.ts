import { describe, expect, it } from 'vitest';
import {
    allowedForRole,
    formatHash,
    homeRoute,
    parseHash,
    routeFor,
    type Route,
} from '@shared/route';

/**
 * Only the pure half of the module: everything from currentRoute down reads `location` and
 * `window`, and the runner has neither. What is asserted here is what a typed address does, which
 * is the half a person can get wrong from the keyboard.
 *
 * Here rather than beside the module, although the runner's include names ../frontend-shared: a
 * test file in that directory cannot import vitest, for the same reason nothing there can import
 * react. It has no node_modules and tsc resolves neither, so the build fails before the runner
 * ever sees the file. Every test of the shared layer is in this directory for that reason.
 */

describe('the six addresses this application has', () => {
    const cases: [string, Route][] = [
        ['#/payments/new', { kind: 'new-payment' }],
        ['#/history', { kind: 'history' }],
        ['#/authorizations', { kind: 'waiting-auth', id: null }],
        ['#/authorizations/8', { kind: 'waiting-auth', id: 8 }],
        ['#/alerts', { kind: 'fraud-desk', id: null }],
        ['#/alerts/12', { kind: 'fraud-desk', id: 12 }],
    ];

    it.each(cases)('reads %s', (hash, route) => {
        expect(parseHash(hash)).toEqual(route);
    });

    it.each(cases)('writes %s back unchanged, so a copied address survives a round trip', (hash, route) => {
        expect(formatHash(route)).toBe(hash);
    });
});

describe('an address nobody can have', () => {
    it.each(['', '#', '#/', '#/dashboard', '#/payments', '#/payments/new/8', '#/history/3'])(
        'has no route for %s',
        (hash) => {
            expect(parseHash(hash)).toBeNull();
        },
    );

    it('keeps the screen and drops an id that is not a number, rather than losing both', () => {
        // The screen was named correctly and only the selection was not. Sending somebody to a
        // different screen over one typed character helps nobody.
        expect(parseHash('#/authorizations/eight')).toEqual({ kind: 'waiting-auth', id: null });
        expect(parseHash('#/alerts/0')).toEqual({ kind: 'fraud-desk', id: null });
        expect(parseHash('#/alerts/-3')).toEqual({ kind: 'fraud-desk', id: null });
    });
});

describe('what each role may reach, which is what its own column offers', () => {
    it('gives the customer the three customer screens and not the desk', () => {
        expect(allowedForRole('CUSTOMER', { kind: 'new-payment' })).toBe(true);
        expect(allowedForRole('CUSTOMER', { kind: 'history' })).toBe(true);
        expect(allowedForRole('CUSTOMER', { kind: 'waiting-auth', id: 8 })).toBe(true);
        expect(allowedForRole('CUSTOMER', { kind: 'fraud-desk', id: 12 })).toBe(false);
    });

    it('gives the analyst the desk and nothing of the customer', () => {
        expect(allowedForRole('FRAUD_ANALYST', { kind: 'fraud-desk', id: null })).toBe(true);
        expect(allowedForRole('FRAUD_ANALYST', { kind: 'new-payment' })).toBe(false);
        expect(allowedForRole('FRAUD_ANALYST', { kind: 'history' })).toBe(false);
        expect(allowedForRole('FRAUD_ANALYST', { kind: 'waiting-auth', id: null })).toBe(false);
    });

    it('starts each role on the first screen its column actually offers', () => {
        // Both columns begin with planned entries, which go nowhere and must not be a home.
        expect(homeRoute('CUSTOMER')).toEqual({ kind: 'new-payment' });
        expect(homeRoute('FRAUD_ANALYST')).toEqual({ kind: 'fraud-desk', id: null });
        expect(allowedForRole('CUSTOMER', homeRoute('CUSTOMER'))).toBe(true);
        expect(allowedForRole('FRAUD_ANALYST', homeRoute('FRAUD_ANALYST'))).toBe(true);
    });
});

describe('the view the navigation column hands over', () => {
    it('carries no selection unless one is passed', () => {
        expect(routeFor('waiting-auth')).toEqual({ kind: 'waiting-auth', id: null });
        expect(routeFor('waiting-auth', 8)).toEqual({ kind: 'waiting-auth', id: 8 });
        expect(routeFor('new-payment')).toEqual({ kind: 'new-payment' });
    });
});
