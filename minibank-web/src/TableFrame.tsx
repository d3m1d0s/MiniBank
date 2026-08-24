// src/TableFrame.tsx

import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * The box a table sits in, and the two things a box that clips owes its reader.
 *
 * `overflow-x: auto` on its own is silent. A table wider than the panel is cut at the wrapper's
 * right edge with no line, no shadow and no fade, so the cut reads as a rendering fault rather
 * than as a boundary: at 390 the queue's Status column showed one letter of its heading, and at
 * 200 percent text the fraud desk cut `Assignee` mid word and dropped `Created` entirely. The
 * reader is told nothing about where the rest went. Second, the only way to reach it was a
 * pointer: a scroll container that holds no control of its own is not in the tab order, so a
 * reader working from the keyboard cannot move it at all.
 *
 * So the frame reports which of its edges is hiding something, and it takes the tab stop for as
 * long as it does. Both parts are measured rather than assumed, because a table that fits must
 * not be dressed as one that does not: an edge drawn over a table with nothing past it is the
 * same lie in the other direction, and a focus stop on a box that cannot move is a stop that
 * answers no key.
 *
 * The measurement is here rather than in the stylesheet because CSS cannot ask whether a box
 * scrolls. The gradients and the ceiling live in App.css, keyed off the attribute this writes.
 *
 * Two observers and a listener, and each of them catches a case the others do not: the frame
 * changes width when the window or the split does, the table changes width when rows arrive or
 * a column grows, and neither of those fires when the reader simply scrolls.
 */
export default function TableFrame({
    label,
    className,
    children,
}: {
    /** What the region is called when a reader lands on it. The table's own heading. */
    label: string;
    /** Spacing utilities the call site already put on the wrapper. */
    className?: string;
    children: ReactNode;
}) {
    const scrollerRef = useRef<HTMLDivElement | null>(null);
    const [edges, setEdges] = useState('');

    const measure = useCallback(() => {
        const el = scrollerRef.current;
        if (!el) {
            return;
        }
        // A pixel of slack. Sub pixel layout leaves scrollWidth a fraction above clientWidth on
        // tables that do fit, and without the slack every table on the screen wears an edge.
        const room = el.scrollWidth - el.clientWidth;
        if (room <= 1) {
            setEdges('');
            return;
        }
        let start = el.scrollLeft > 1;
        let end = el.scrollLeft < room - 1;
        // A box with two pixels to give can be at neither end by this reckoning. It still scrolls,
        // and the string is also what says whether it does: an empty one here would take the tab
        // stop off a box the reader is standing in.
        if (!start && !end) {
            start = true;
            end = true;
        }
        setEdges(`${start ? 'start' : ''}${start && end ? ' ' : ''}${end ? 'end' : ''}`);
    }, []);

    useEffect(() => {
        const el = scrollerRef.current;
        if (!el) {
            return;
        }
        // No first measurement here. An observer reports the box it is given as soon as it is
        // given it, so the first reading arrives from the callback below and the state is written
        // from outside the effect rather than inside it.
        const observer = new ResizeObserver(() => measure());
        observer.observe(el);
        const table = el.firstElementChild;
        if (table) {
            observer.observe(table);
        }
        el.addEventListener('scroll', measure, { passive: true });
        return () => {
            observer.disconnect();
            el.removeEventListener('scroll', measure);
        };
    }, [measure]);

    const scrolls = edges !== '';

    return (
        <div
            className={className ? `table-frame ${className}` : 'table-frame'}
            data-edges={edges || undefined}
        >
            {/*
              The three attributes appear together and only while the box actually scrolls. A
              region needs a name to be worth landing on, and a name needs something to name.
            */}
            <div
                ref={scrollerRef}
                className="table-wrapper"
                tabIndex={scrolls ? 0 : undefined}
                role={scrolls ? 'region' : undefined}
                aria-label={scrolls ? label : undefined}
            >
                {children}
            </div>
        </div>
    );
}
