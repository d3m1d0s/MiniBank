// src/Nav.tsx
import { NAV_ENTRIES, NAV_LANDMARK, NAV_TITLE } from '@shared/navigation';
import type { NavRole, NavView } from '@shared/navigation';

/**
 * The navigation column, drawn once for all three screens of this application.
 *
 * Three props and no entries prop. Passing the list in from the caller is what let three screens
 * show three different columns for one role, so the component reads the shared list itself and the
 * caller can only say who is looking and where they are.
 */
interface NavProps {
    role: NavRole;
    current: NavView;
    onNavigate: (view: NavView) => void;
}

export function Nav({ role, current, onNavigate }: NavProps) {
    const entries = NAV_ENTRIES[role];

    return (
        <nav className="nav" aria-label={NAV_LANDMARK}>
            <div className="nav-title">{NAV_TITLE}</div>
            <ul>
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
                                {entry.label}
                            </button>
                        ) : (
                            /*
                             * A span, not a disabled button. These entries were buttons with no
                             * handler, so four of the six tab stops did nothing when pressed, and
                             * the focus ring they took was the same blue ring that marks the
                             * current page. aria-disabled was the wrong word for them too: a
                             * disabled control is one that may become pressable, and these will
                             * not. What they are is said under the pointer, in the title, and by
                             * the muted colour the rest of the time.
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

export default Nav;
