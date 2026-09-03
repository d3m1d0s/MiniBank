// src/NavRail.tsx
import {
    NAV_ENTRIES,
    NAV_TITLE,
    type NavRole,
    type NavView,
} from '@shared/nav/navigation';

/**
 * The region the handle folds, named so the handle can point at it.
 */
const NAV_FOLD_ID = 'nav-fold';

/**
 * The rail's heading, named so the rail can be called by it.
 *
 * The landmark used to carry a word of its own, which meant one region under two names: the
 * reader saw Navigation printed over the entries and heard Primary announced when they arrived.
 * A region is named by its heading here, the way the two panels beside it are.
 */
const NAV_TITLE_ID = 'nav-title';

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
 * person. Every word on this surface is read from the shared module, the one the surface calls
 * itself by included, because a heading typed here is a heading that has to be found again the day
 * the other one is renamed.
 *
 * A renderer of its own rather than the customer application's component lifted into the shared
 * layer, and the reason is not the file it lives in. What the two platforms share is the list; what
 * they have never shared is the shape. It hangs in a window rather than beside a page, and it folds
 * to a strip where the customer column folds into a band.
 *
 * ONE RAIL, AND THE ROLE CHANGES ONLY WHAT IS ON IT. This used to draw two different things: the
 * customer's entries were buttons because a handler was passed, and the analyst's were words
 * because none was, on the argument that a button leading to the screen you are standing on is a
 * tab stop that does nothing. The argument proves too much. The customer's current entry is a
 * button on both platforms already, so the product had one column behaving two ways depending on
 * who was reading it, which is the thing the shared list exists to prevent. An entry that names a
 * screen is a control, on every screen and for every role; the stylesheet excludes the current and
 * the planned entry from hover and press by name, so neither answers like a live one.
 */
export function NavRail({
    role,
    current,
    folded,
    onToggle,
    onNavigate,
}: {
    role: NavRole;
    current: NavView;
    folded: boolean;
    onToggle: () => void;
    onNavigate: (view: NavView) => void;
}) {
    const entries = NAV_ENTRIES[role];

    return (
        <nav className={folded ? 'nav nav--folded' : 'nav'} aria-labelledby={NAV_TITLE_ID}>
            {/*
              THE HANDLE STANDS STILL.

              It is the first thing in the rail and it keeps its place whether the rail is open or
              shut, because a control that moves when it is pressed asks to be found again for the
              press that undoes it. Folded, the entries go and the heading stops being drawn, so
              this is all that is left, with the rail's own edge closing up behind it.
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
                {/*
                  A HEADING, AT THE RUNG THE TWO PANELS BESIDE IT ARE AT.

                  The rail is the third region on this screen and it was the one thing on it that
                  named itself with a word rather than with a heading: a reader moving by headings
                  went MiniBank, Alerts queue, Alert details and never passed through the
                  navigation at all. The outline now reads Navigation, Alerts queue, Alert details,
                  which is the screen as it is drawn, left to right.

                  Taken away by the stylesheet when the rail is folded rather than by `hidden`, and
                  the difference is the whole reason this is not a one word change. The name of the
                  region is this element, and a region named by an element that is hidden has no
                  name at all: folded, the rail would have been an unnamed landmark in the middle
                  of a screen with three of them. What folding takes away is the column of entries,
                  which is a posture of the window; what the rail IS does not change with it.
                */}
                <h2
                    className={folded ? 'nav-title visually-hidden' : 'nav-title'}
                    id={NAV_TITLE_ID}
                >
                    {NAV_TITLE}
                </h2>
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
                            <button
                                type="button"
                                className={
                                    entry.view === current
                                        ? 'nav-item nav-item--current'
                                        : 'nav-item'
                                }
                                aria-current={entry.view === current ? 'page' : undefined}
                                onClick={() => onNavigate(entry.view)}
                            >
                                {/* The word, and under it the same word at the weight the chosen
                                    entry is set in, drawn at no height and hidden from sight and
                                    from the accessibility tree alike. See .nav-label: the rail is
                                    as wide as its longest entry, and this is what stops that width
                                    depending on which entry is chosen. Nothing here is particular
                                    to a label or to a rail - every entry reserves its own word. */}
                                <span className="nav-label" data-label={entry.label}>
                                    {entry.label}
                                </span>
                            </button>
                        ) : (
                            /*
                             * The state carried by the muting and by the title, and not by a word
                             * standing in the rail. On a column this narrow the word doubled the
                             * width of every planned entry to repeat what the colour, the dead
                             * hover and the title were already saying three times over.
                             */
                            <span className="nav-item nav-item--planned" title={entry.title}>
                                {/* The same reserve. A planned entry is never the chosen one, but
                                    it is measured into the same column, and the column has to be
                                    one width whichever entry is standing in it. */}
                                <span className="nav-label" data-label={entry.label}>
                                    {entry.label}
                                </span>
                            </span>
                        )}
                    </li>
                ))}
            </ul>
        </nav>
    );
}

export default NavRail;
