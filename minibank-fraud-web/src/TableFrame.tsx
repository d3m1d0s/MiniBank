import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';

/**
 * The box a table sits in, and the two things a box that clips owes its reader.
 *
 * `overflow-x: auto` on its own is silent. A table wider than its region is cut at the wrapper's
 * edge with no line, no shadow and no fade, so the cut reads as a rendering fault rather than as a
 * boundary. And the only way to reach what is past it is a pointer: a scroll container holding no
 * control of its own is not in the tab order, so a reader working from the keyboard cannot move it.
 *
 * So the frame reports which of its edges is hiding something, and it takes the tab stop for as
 * long as it does. Both parts are measured rather than assumed, because a table that fits must not
 * be dressed as one that does not, and a focus stop on a box that cannot move is a stop that
 * answers no key.
 *
 * The measurement is here rather than in the stylesheet because CSS cannot ask whether a box
 * scrolls. The bands and the ground live in fraud.css, keyed off the attribute this writes.
 *
 * Two observers and a listener, and each catches a case the others do not: the frame changes width
 * when the window or the split does, the table changes width when rows arrive or a column grows,
 * and neither of those fires when the reader simply scrolls.
 *
 * WHY THIS IS WRITTEN OUT AGAIN rather than imported. The customer application has the same
 * component, and it is the same measurement: the frame is not skin, and the two copies will drift
 * the way every pair in this product has drifted. It cannot move to frontend-shared as it stands,
 * because that directory resolves no import of react - see ErrorBoundary.d.ts, which is a .jsx with
 * hand written types for exactly this reason - and one application may not import another's src.
 * The handover names both halves of it.
 */
export default function TableFrame({
    label,
    className,
    children,
}: {
    /** What the region is called when a reader lands on it. The table's own heading. */
    label: string;
    /** Spacing utilities the call site already put on the frame. */
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
        // tables that do fit, and without the slack every table on the screen wears a band.
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
        // given it, so the first reading arrives from the callback below rather than from the body
        // of this effect.
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
