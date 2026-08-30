/**
 * Which screen the address bar is pointing at.
 *
 * Written by hand and kept small on purpose. Both applications choose their screen with a piece
 * of component state, so no screen has an address at all: a reload lands on the first screen of
 * the role, a link to one payment cannot be sent to anybody, and the browser's Back button leaves
 * the bank altogether from the third screen in. A router package would answer all of that and
 * bring a dependency, a provider around each shell and a second vocabulary for the same four
 * screens; this is the sixty lines of it that this product uses.
 *
 * The hash and not the path, because both applications are served by a dev server that knows one
 * document: a path form needs the server to answer every address with index.html, and neither
 * vite config does.
 *
 * No react here, and it is not a preference. This directory has no node_modules and tsc resolves
 * no import of react from it, which is written up in ErrorBoundary.d.ts and is why that component
 * is a .jsx with hand written types. So the parsing, the addressing and the subscription live
 * here, and the four line hook that turns them into state is written in each shell, where react
 * can be named.
 *
 * Everything above {@link currentRoute} is pure and can be read in a test; from there down the
 * functions touch `location` and `window`, which do not exist in the test runner's environment.
 */

import type { NavEntry, NavRole, NavView } from './navigation';
import { NAV_ENTRIES } from './navigation';

/**
 * A screen, and for the two that have one, the thing on it that is open.
 *
 * The id sits in the route rather than beside it because that is the whole point of the address:
 * `#/authorizations/8` is a payment somebody can come back to, and a selection kept only in
 * component state is one a reload throws away.
 */
export type Route =
    | { kind: 'new-payment' }
    | { kind: 'history' }
    | { kind: 'waiting-auth'; id: number | null }
    | { kind: 'fraud-desk'; id: number | null };

/**
 * The route for a screen the navigation column offers.
 *
 * The switch is exhaustive over NavView, which is what keeps this module from becoming a second
 * list of the screens: a view added to navigation.ts stops compiling here until it has an
 * address.
 */
export function routeFor(view: NavView, id: number | null = null): Route {
    switch (view) {
        case 'new-payment':
            return { kind: 'new-payment' };
        case 'history':
            return { kind: 'history' };
        case 'waiting-auth':
            return { kind: 'waiting-auth', id };
        case 'fraud-desk':
            return { kind: 'fraud-desk', id };
    }
}

/** The address, which is also the only place these six forms are written down. */
export function formatHash(route: Route): string {
    switch (route.kind) {
        case 'new-payment':
            return '#/payments/new';
        case 'history':
            return '#/history';
        case 'waiting-auth':
            return route.id === null ? '#/authorizations' : `#/authorizations/${route.id}`;
        case 'fraud-desk':
            return route.id === null ? '#/alerts' : `#/alerts/${route.id}`;
    }
}

/** Null for an address this application does not have, including the empty one. */
export function parseHash(hash: string): Route | null {
    const parts = hash.replace(/^#/, '').split('/').filter(Boolean);
    if (parts.length === 0) return null;

    const [head, tail] = parts;
    if (parts.length === 2 && head === 'payments' && tail === 'new') return { kind: 'new-payment' };
    if (parts.length === 1 && head === 'history') return { kind: 'history' };
    if (parts.length <= 2 && head === 'authorizations') {
        return { kind: 'waiting-auth', id: readId(tail) };
    }
    if (parts.length <= 2 && head === 'alerts') return { kind: 'fraud-desk', id: readId(tail) };
    return null;
}

/**
 * A segment that is not a positive whole number opens the screen with nothing selected, rather
 * than making the whole address unreadable. The screen was named correctly and only the selection
 * was not, and sending somebody to a different screen for a typed digit helps nobody.
 */
function readId(segment: string | undefined): number | null {
    if (segment === undefined) return null;
    return /^[1-9][0-9]*$/.test(segment) ? Number(segment) : null;
}

/** Whether this role is offered this screen, read off the navigation column and not a copy of it. */
export function allowedForRole(role: NavRole, route: Route): boolean {
    return NAV_ENTRIES[role].some(
        (entry: NavEntry) => entry.kind === 'screen' && entry.view === route.kind,
    );
}

/** Where a role starts: the first screen its column offers, planned entries skipped. */
export function homeRoute(role: NavRole): Route {
    const first = NAV_ENTRIES[role].find((entry: NavEntry) => entry.kind === 'screen');
    // Every role in NAV_ENTRIES has at least one screen. The fallback is here because the type
    // cannot say so, and it is the customer's first screen rather than an invented one.
    return first && first.kind === 'screen' ? routeFor(first.view) : { kind: 'new-payment' };
}

/**
 * What the address bar says right now, corrected where it says something this role cannot have.
 *
 * Reads and, in the one case where it had to correct something, writes: an address that is not
 * ours or not this role's is REPLACED rather than pushed. Pushing would leave the bad address one
 * Back press away, and Back is exactly where somebody goes when a screen surprises them.
 *
 * With no role, nobody is signed in and nothing is corrected. The address is what the person came
 * for, the sign-in card is only in the way, and the shell reads this again once a role has
 * arrived: the value returned here is not on screen until then.
 */
export function currentRoute(role: NavRole | null): Route {
    const parsed = parseHash(location.hash);

    if (!role) return parsed ?? { kind: 'new-payment' };
    if (parsed && allowedForRole(role, parsed)) return parsed;

    const home = homeRoute(role);
    replaceRoute(home);
    return home;
}

/**
 * A move somebody made: the navigation column, or opening a row.
 *
 * Fires hashchange through the browser, so every subscriber hears it. The address it is already
 * at is not written again, or pressing the screen you are standing on would fill Back with
 * copies of it.
 */
export function goTo(route: Route) {
    const hash = formatHash(route);
    if (hash !== location.hash) location.hash = hash;
}

/**
 * A correction the program made: a selection dropped because the payment left the list, an
 * address nobody can have.
 *
 * replaceState does not fire hashchange, and that is why this is the right call for a correction
 * and the wrong one for a move: whoever asked for it already holds the route it wrote, and a
 * screen that told the address bar what it is showing does not need to be told back.
 */
export function replaceRoute(route: Route) {
    const hash = formatHash(route);
    if (hash !== location.hash) history.replaceState(history.state, '', hash);
}

/** Returns the unsubscribe, so an effect can hand it straight back. */
export function subscribeToHash(listener: () => void): () => void {
    window.addEventListener('hashchange', listener);
    return () => window.removeEventListener('hashchange', listener);
}
