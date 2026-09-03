/**
 * What each role can reach, as data, and which roles count as roles at all.
 *
 * It is shared for the same reason the fraud DTOs are: it was written more than once and the
 * copies disagreed. The customer navigation existed three times in hand written markup, once per
 * screen, and one entry ended up with three appearances inside one application: Dashboard was a
 * dashed plate on the payment form, an ordinary bright line on the authorization screen, and on
 * neither of them anything that said it goes nowhere. A reader could not tell a live destination
 * from a dead one, and the only way to keep three copies agreeing was to remember all three.
 *
 * The list therefore lives here and the renderers hold no entries of their own. Both applications
 * must offer the same role the same destinations in the same order, so the desktop reads this file
 * unchanged when its customer screens arrive.
 *
 * The rule binds what a role is offered and in what order: no application may add a destination,
 * drop one, or reorder them. The planned entries are not deleted for a role that can reach only
 * one screen today, and they are not hidden either: they are what the product intends to offer,
 * and the column is where a reader learns that the intention exists.
 *
 * No JSX, no class names and nothing else about how any of this looks: the two skins draw the same
 * list in their own palette, which is the one thing they are allowed to disagree about.
 */

/** The roles these clients have screens for. The server's UserRole has four values; two get a UI. */
export type NavRole = 'CUSTOMER' | 'FRAUD_ANALYST';

/**
 * The same two as values, because a type cannot be asked at run time what it admits.
 *
 * The wire is NOT narrowed to match. SessionRole in http.ts keeps all four names the server can
 * sign somebody in as, since an endpoint that can answer OPERATIONS is not made honest by a type
 * saying it cannot. The narrowing is a fact about these clients rather than about the bank, so it
 * happens here, once, on the way from what arrived to what a shell knows how to draw.
 */
export const SERVED_ROLES: readonly NavRole[] = ['CUSTOMER', 'FRAUD_ANALYST'];

/**
 * The role when the clients have screens for it, null when they do not.
 *
 * The argument is a plain string, and that is the point: both the sign-in response and the record
 * a tab kept for itself are cast rather than validated, so typing this as the two served roles
 * would let a third walk through every comparison that looks exhaustive. An unknown role is
 * answered here with the null that both shells turn into a panel saying so, instead of reaching a
 * payment form and failing there on a 403.
 *
 * Both shells carried this function and the list above word for word, one copy each, which is two
 * answers to a question that has one. Neither copy was wrong; that is what made it worth moving,
 * since a pair that agrees today is a pair that will be edited on one side tomorrow.
 */
export function servedRole(role: string): NavRole | null {
    return (SERVED_ROLES as readonly string[]).includes(role) ? (role as NavRole) : null;
}

/** The screens that exist. A view here is a screen a person can actually be standing on. */
export type NavView = 'new-payment' | 'history' | 'waiting-auth' | 'fraud-desk';

/**
 * One row of the column.
 *
 * A discriminated union rather than a built flag, because a planned entry must not carry a view.
 * With a flag, every renderer is free to wire a handler to an entry that has nowhere to go, which
 * is exactly how the three copies drifted: nothing in the type stopped a dead entry from being
 * built as a control. Here there is no view to hand to onNavigate, so it cannot be done by
 * accident.
 *
 * `id` is stable and is not the label: it survives the wording pass that will rename
 * "History & Statements", and it is what a renderer keys its list by.
 */
export type NavEntry =
    | { kind: 'screen'; id: string; label: string; view: NavView }
    | { kind: 'planned'; id: string; label: string; title: string };

/**
 * What a planned entry says under the pointer, and the reason it is one constant rather than a
 * sentence per entry: varying the wording would suggest the reasons differ, and they do not.
 */
export const PLANNED_TITLE = 'Planned - not part of this showcase';

/**
 * What the column calls itself, and what it is called in the list of landmarks.
 *
 * Two words for two readers and neither is decoration. The heading is what a person reads above
 * the entries; `Primary` is what a screen reader announces, where `Navigation` would be said
 * twice over because the element already names its own kind. Both were typed into the web markup,
 * which was safe while one application had a column and stopped being safe the moment a second
 * one draws the same three entries.
 */
export const NAV_TITLE = 'Navigation';
export const NAV_LANDMARK = 'Primary';

/**
 * Both lists are in product order, the built and the unbuilt interleaved.
 *
 * A column that sorts by what happens to be implemented tells the reader about the state of the
 * project rather than about the bank. Where an entry sits is decided by what it is for, and it does
 * not move when the screen behind it is finished.
 */
export const NAV_ENTRIES: Record<NavRole, readonly NavEntry[]> = {
    CUSTOMER: [
        { kind: 'planned', id: 'dashboard', label: 'Dashboard', title: PLANNED_TITLE },
        { kind: 'planned', id: 'accounts', label: 'Accounts', title: PLANNED_TITLE },
        { kind: 'screen', id: 'new-payment', label: 'New payment', view: 'new-payment' },
        { kind: 'screen', id: 'history', label: 'History & Statements', view: 'history' },
        {
            kind: 'screen',
            id: 'waiting-auth',
            label: 'Waiting authorizations',
            view: 'waiting-auth',
        },
        { kind: 'planned', id: 'settings', label: 'Settings', title: PLANNED_TITLE },
    ],
    FRAUD_ANALYST: [
        { kind: 'planned', id: 'dashboard', label: 'Dashboard', title: PLANNED_TITLE },
        // The desk before the settings, which is the order the customer's column already reads in:
        // the place the work happens, then the place the account is configured. Settings sat above
        // it and put the one screen this role has third of three.
        { kind: 'screen', id: 'fraud-desk', label: 'Fraud desk', view: 'fraud-desk' },
        { kind: 'planned', id: 'settings', label: 'Settings', title: PLANNED_TITLE },
    ],
};

/**
 * What the shell says when the server stops accepting the session it is holding.
 *
 * It names no cause, and that is the server's decision carried through rather than vagueness. A
 * session that has gone idle, hit its ceiling, been closed, or belonged to a user who has since
 * been removed all answer the same 401, deliberately, so a notice reading "expired" would be a
 * false statement about three of the four.
 *
 * Typed into both shells, identically, which is the state a word is in just before it drifts.
 */
export const SIGNED_OUT_NOTICE = 'You have been signed out. Please sign in again.';

/**
 * The way out of a screen that has nothing on it for whoever is standing there.
 *
 * `Logout` was the old word and it is wrong for somebody who never got in: what pressing this buys
 * is a different session, not the end of this one.
 */
export const SIGN_IN_AS_SOMEONE_ELSE = 'Sign in as someone else';

/**
 * What both applications say to a signed in role neither of them has screens for.
 *
 * The heading names the situation and the sentence names the person, because a bare heading over
 * an empty panel reads as a fault in the application rather than as a fact about the account.
 * Neither prints the role: the client narrowed its type on purpose so that it has no name to
 * print here, and inventing one would promise screens that do not exist.
 *
 * A signed in customer at the analyst's workstation is a different case and is NOT this one. That
 * person has screens in MiniBank and simply not in that window, so the workstation says so in its
 * own words; one sentence cannot carry both truths, and the wrong half of it is a lie.
 */
export const NO_SCREENS_TITLE = 'There are no screens for this account';

export function noScreensNote(username: string): string {
    return (
        `You are signed in as ${username}. MiniBank has screens for customers and for fraud ` +
        'analysts, and this account is neither.'
    );
}

/**
 * How long a session survives with nothing asked of the bank.
 *
 * SessionStore.IDLE_TIMEOUT is fifteen minutes and it is refreshed by a request and by nothing
 * else, so the number belongs on screen: somebody who spends twenty minutes writing has done real
 * work that the server has no way to hear about. Naming it is half the answer and the keep alive
 * below is the other half; a rule a person is judged by is one they are owed in advance.
 */
export const SESSION_IDLE_NOTE = 'Signed out after 15 minutes idle';

/**
 * What counts as somebody working, as far as the session is concerned.
 *
 * Three events and deliberately not mousemove: a cursor crossing the window on its way somewhere
 * else is not work, and counting it would keep a session alive for an empty desk, which is the
 * thing the timeout exists to end.
 */
export const ACTIVITY_EVENTS = ['keydown', 'pointerdown', 'wheel'] as const;

/**
 * The floor between two keep alive reads, well under the server's fifteen minutes.
 *
 * The gap this measures is the worst case rather than the truth, because the screens make requests
 * of their own that the shell never sees. Erring this way costs an occasional call the server did
 * not need; erring the other way ends a session while somebody is mid sentence.
 */
export const ACTIVITY_REFRESH_MS = 5 * 60 * 1000;

/**
 * What the shell says when the session ends under somebody who was working.
 *
 * One clause more than {@link SIGNED_OUT_NOTICE}, and it is the only one the reader cannot work
 * out for themselves: whether the thing they were in the middle of went through. Being told they
 * were signed out does not answer that, and silence reads as yes.
 *
 * What it deliberately does NOT say is which screen they were on and that signing in returns to
 * it. Both are about to happen in front of them, and a card that narrates them is a card nobody
 * finishes reading.
 */
export const SIGNED_OUT_WITH_LOSS =
    `${SIGNED_OUT_NOTICE} Nothing you had typed was sent.`;
