import { describe, it, expect } from 'vitest';
import {
    ACTIVITY_EVENTS,
    ACTIVITY_REFRESH_MS,
    NAV_ENTRIES,
    NAV_LANDMARK,
    NAV_TITLE,
    NO_SCREENS_TITLE,
    PLANNED_TITLE,
    SERVED_ROLES,
    SESSION_IDLE_NOTE,
    SIGNED_OUT_NOTICE,
    SIGNED_OUT_WITH_LOSS,
    noScreensNote,
    servedRole,
} from './navigation';
import type { NavEntry, NavRole, NavView } from './navigation';

/**
 * The column existed three times in hand written markup and the three copies disagreed, which is
 * what moved it here. Moving it settled the disagreement and left the list itself unasserted: the
 * rule that no application may add a destination, drop one or reorder them is stated in a comment
 * and nothing has been holding anybody to it.
 */

/** Every screen a person can stand on, and the compiler will not let this list be short. */
const ALL_VIEWS: Record<NavView, true> = {
    'new-payment': true,
    history: true,
    'waiting-auth': true,
    'fraud-desk': true,
};

/**
 * The destinations in a column, the planned entries dropped.
 *
 * flatMap rather than filter and map, because a filter narrows nothing: the planned half of the
 * union survives it as far as the compiler is concerned and has no view to read.
 */
function viewsOf(entries: readonly NavEntry[]): NavView[] {
    return entries.flatMap((entry) => (entry.kind === 'screen' ? [entry.view] : []));
}

describe('which signed in roles these clients have screens for', () => {
    it.each(['CUSTOMER', 'FRAUD_ANALYST'])('serves %s', (role) => {
        expect(servedRole(role)).toBe(role);
    });

    it.each(['OPERATIONS', 'MANAGEMENT'])('has nothing to draw for %s, which the bank has', (r) => {
        // The wire keeps all four names on purpose. An endpoint that can answer OPERATIONS is not
        // made honest by a type saying it cannot, so the narrowing happens here and answers null.
        expect(servedRole(r)).toBeNull();
    });

    it.each(['', 'customer', 'ADMIN', 'FRAUD_ANALYST ', 'undefined'])(
        'answers null for %o rather than letting it through a comparison that looks exhaustive',
        (junk) => {
            // The argument is a plain string because both the sign-in response and the record a
            // tab kept for itself are cast rather than validated.
            expect(servedRole(junk)).toBeNull();
        },
    );

    it('admits exactly the roles that have a column, neither more nor fewer', () => {
        expect([...SERVED_ROLES].sort()).toEqual(Object.keys(NAV_ENTRIES).sort());
        for (const role of SERVED_ROLES) {
            expect(servedRole(role)).toBe(role);
        }
    });
});

describe('the column each role is offered', () => {
    const roles = Object.keys(NAV_ENTRIES) as NavRole[];

    it.each(roles)('gives %s somewhere to actually stand', (role) => {
        // A role with a column of planned entries only would sign in to a shell that goes nowhere,
        // which is the state the no-screens panel exists for and not a column.
        expect(NAV_ENTRIES[role].some((entry) => entry.kind === 'screen')).toBe(true);
    });

    it.each(roles)('keys the list of %s by ids no two entries share', (role) => {
        const ids = NAV_ENTRIES[role].map((entry) => entry.id);
        expect(new Set(ids).size).toBe(ids.length);
    });

    it.each(roles)('offers no screen twice in the column of %s', (role) => {
        const views = viewsOf(NAV_ENTRIES[role]);
        expect(new Set(views).size).toBe(views.length);
    });

    it('offers every screen that exists, to exactly one role', () => {
        const offered = viewsOf(Object.values(NAV_ENTRIES).flat());
        expect(offered.sort()).toEqual(Object.keys(ALL_VIEWS).sort());
    });

    it('hands a planned entry nothing to navigate to, so no renderer can wire one up', () => {
        // The union is what enforces this at compile time. Asserted again here because an entry
        // built elsewhere and cast reaches the renderers through the same list.
        for (const entry of Object.values(NAV_ENTRIES).flat()) {
            if (entry.kind === 'planned') {
                expect('view' in entry).toBe(false);
            }
        }
    });

    it('gives every planned entry the same sentence, because the reason does not differ', () => {
        const titles = Object.values(NAV_ENTRIES)
            .flat()
            .flatMap((entry) => (entry.kind === 'planned' ? [entry.title] : []));
        expect(titles.length).toBeGreaterThan(0);
        for (const title of titles) {
            expect(title).toBe(PLANNED_TITLE);
        }
    });

    it('is in product order, not in the order things happened to be built', () => {
        // Both columns open on a planned entry and the customer column closes on one, with the
        // three built screens between them. A column sorted by what is implemented would tell the
        // reader about the state of the project instead of about the bank.
        for (const role of roles) {
            expect(NAV_ENTRIES[role][0].kind).toBe('planned');
        }
        const customer = NAV_ENTRIES.CUSTOMER;
        expect(customer[customer.length - 1].kind).toBe('planned');
    });

    it('labels itself for a reader and for a screen reader without saying the same word twice', () => {
        expect(NAV_TITLE).not.toBe(NAV_LANDMARK);
        // The element already announces its own kind, so the landmark must not repeat it.
        expect(NAV_LANDMARK).not.toMatch(/navigation/i);
    });
});

describe('what a role with no screens is told', () => {
    it('names the person, which is what makes it a fact about the account', () => {
        expect(noScreensNote('opsuser')).toContain('opsuser');
    });

    it('prints no role, because the client narrowed its type rather than keep a name to print', () => {
        const note = `${NO_SCREENS_TITLE} ${noScreensNote('opsuser')}`;
        expect(note).not.toMatch(/OPERATIONS|MANAGEMENT/);
    });

    it('reads as a fact about the account and not as a fault in the application', () => {
        expect(NO_SCREENS_TITLE).not.toMatch(/error|sorry|wrong|failed/i);
    });
});

describe('the session, and the notice for the person who was in the middle of something', () => {
    it('carries the plain notice whole, plus the one clause that notice cannot answer', () => {
        expect(SIGNED_OUT_WITH_LOSS.startsWith(SIGNED_OUT_NOTICE)).toBe(true);
        // Being told you were signed out does not say whether what you typed went through, and
        // silence reads as yes.
        expect(SIGNED_OUT_WITH_LOSS.slice(SIGNED_OUT_NOTICE.length)).toContain('Nothing');
    });

    it('says how long a session survives idle, so nobody is judged by a rule they were not told', () => {
        expect(SESSION_IDLE_NOTE).toContain('15');
    });

    it('reads for a keep alive well inside that window', () => {
        expect(ACTIVITY_REFRESH_MS).toBeLessThan(15 * 60 * 1000);
    });

    it('does not count a cursor crossing the window as somebody working', () => {
        // A pointer on its way somewhere else would keep an empty desk signed in, which is the
        // thing the timeout exists to end.
        expect(ACTIVITY_EVENTS).not.toContain('mousemove');
        expect(ACTIVITY_EVENTS.length).toBeGreaterThan(0);
    });
});
