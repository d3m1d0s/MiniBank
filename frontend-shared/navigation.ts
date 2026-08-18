/**
 * What each role can reach, as data.
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
 * No JSX, no class names and nothing else about how any of this looks: the two skins draw the same
 * list in their own palette, which is the one thing they are allowed to disagree about.
 */

/** The roles these clients have screens for. The server's UserRole has four values; two get a UI. */
export type NavRole = 'CUSTOMER' | 'FRAUD_ANALYST';

/** The screens that exist. A view here is a screen a person can actually be standing on. */
export type NavView = 'new-payment' | 'waiting-auth' | 'fraud-desk';

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
        { kind: 'planned', id: 'history', label: 'History & Statements', title: PLANNED_TITLE },
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
        { kind: 'planned', id: 'settings', label: 'Settings', title: PLANNED_TITLE },
        { kind: 'screen', id: 'fraud-desk', label: 'Fraud desk', view: 'fraud-desk' },
    ],
};
