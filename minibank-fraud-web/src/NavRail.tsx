// src/NavRail.tsx
import {
    NAV_ENTRIES,
    NAV_LANDMARK,
    NAV_TITLE,
    type NavRole,
    type NavView,
} from '@shared/navigation';

/**
 * The region the handle folds, named so the handle can point at it.
 */
const NAV_FOLD_ID = 'nav-fold';

/**
 * The two words on the handle, for a reader who is not looking at the icon.
 *
 * Declared here rather than in the shared module because the handle is this skin's: the customer
 * column stands beside a page and has nothing to fold into, and a name in the shared layer with
 * one caller is a name the next person has to go and check the callers of.
 */
const NAV_FOLD_LABEL = 'Hide the navigation';
const NAV_UNFOLD_LABEL = 'Show the navigation';

/**
 * WHAT THIS ROLE IS OFFERED, IN THE WINDOW SKIN.
 *
 * The list is the shared one and nothing here adds to it, drops from it or reorders it: the two
 * applications owe the same role the same destinations in the same order, and a column that keeps
 * entries of its own is how the customer screens came to show three different columns for one
 * person. Every word on this surface is read from the shared module, the two the surface calls
 * itself by included, because a heading typed here is a heading that has to be found again the day
 * the other one is renamed.
 *
 * A renderer of its own rather than the customer application's component lifted into the shared
 * layer, and the reason is not the file it lives in. What the two platforms share is the list; what
 * they have never shared is the shape, and this one differs in three ways at once. It hangs in a
 * window rather than beside a page, it folds to a strip where the customer column folds into a
 * band, and the planned state it leaves in the colour and the title rather than spending a second
 * column of the rail on the word. A single component that took all three as options would be a
 * component with one caller per option.
 *
 * Nothing in the list is a control, and that is a statement about this window rather than about
 * navigation. It serves one screen, so the one entry that is not planned is the screen the reader
 * is standing on: a button leading there is the tab stop that does nothing when pressed, which is
 * the fault the shared list was written to end. The day the customer screens arrive at this
 * workstation the entries that lead somewhere become buttons and this comment goes with them.
 */
export function NavRail({
    role,
    current,
    folded,
    onToggle,
}: {
    role: NavRole;
    current: NavView;
    folded: boolean;
    onToggle: () => void;
}) {
    const entries = NAV_ENTRIES[role];

    return (
        <nav className={folded ? 'nav nav--folded' : 'nav'} aria-label={NAV_LANDMARK}>
            {/*
              THE HANDLE STANDS STILL.

              It is the first thing in the rail and it keeps its place whether the rail is open or
              shut, because a control that moves when it is pressed asks to be found again for the
              press that undoes it. Folded, the heading and the entries go and this is all that is
              left, with the rail's own edge closing up behind it.
            */}
            <div className="nav-head">
                <button
                    type="button"
                    className="nav-toggle"
                    aria-expanded={!folded}
                    aria-controls={NAV_FOLD_ID}
                    title={folded ? NAV_UNFOLD_LABEL : NAV_FOLD_LABEL}
                    onClick={onToggle}
                >
                    {/*
                      The rail itself, drawn: a frame with its leading column ruled off. Inline
                      rather than a file, for the reason the mark in the title bar is inline, and
                      stroked in currentColor so the one colour on the button carries it.
                    */}
                    <svg
                        className="nav-toggle-icon"
                        viewBox="0 0 24 24"
                        fill="none"
                        stroke="currentColor"
                        strokeWidth="2"
                        strokeLinecap="round"
                        strokeLinejoin="round"
                        aria-hidden="true"
                        focusable="false"
                    >
                        <rect x="3" y="4" width="18" height="16" rx="2" />
                        <line x1="9.5" y1="4" x2="9.5" y2="20" />
                    </svg>
                    <span className="visually-hidden">
                        {folded ? NAV_UNFOLD_LABEL : NAV_FOLD_LABEL}
                    </span>
                </button>
                <div className="nav-title" hidden={folded}>{NAV_TITLE}</div>
            </div>

            {/*
              `hidden` rather than dropped from the tree, for the reason the decision block gives:
              what folds is a posture of the window, and a region that is unmounted and rebuilt is
              a region whose state the reader loses every time they change their mind about it.
            */}
            <ul id={NAV_FOLD_ID} hidden={folded}>
                {entries.map((entry) => (
                    <li key={entry.id}>
                        {entry.kind === 'screen' ? (
                            <span
                                className={
                                    entry.view === current
                                        ? 'nav-item nav-item--current'
                                        : 'nav-item'
                                }
                                aria-current={entry.view === current ? 'page' : undefined}
                            >
                                {entry.label}
                            </span>
                        ) : (
                            /*
                             * The state carried by the muting and by the title, and not by a word
                             * standing in the rail. On a column this narrow the word doubled the
                             * width of every planned entry to repeat what the colour, the dead
                             * hover and the title were already saying three times over.
                             */
                            <span className="nav-item nav-item--planned" title={entry.title}>
                                {entry.label}
                            </span>
                        )}
                    </li>
                ))}
            </ul>
        </nav>
    );
}

export default NavRail;
