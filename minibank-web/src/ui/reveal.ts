// src/reveal.ts

/**
 * Carry the reader to what their choice produced, on the layouts where it is not already in front
 * of them.
 *
 * Choosing a row on either working screen is the first step of a cycle: choose, read, decide. On a
 * wide window the three steps stand side by side and the press moves something the reader is
 * looking at. On a narrow one they stand under each other, and measured on the fraud desk at
 * 1440x900 the page is 2544px tall with the decision box starting at 1939, so a press on a queue
 * row answered with nothing at all: the answer was two screenfuls down. Neither screen scrolled
 * anywhere, at any width, before this.
 *
 * Three things it deliberately does not do. It does not ask which layout is drawn, because the
 * question it needs answered is whether the reader can see the panel and that is what a rectangle
 * says; on the split layout the panel is always on screen and this returns without touching the
 * page. It does not centre, because the top of the panel is the top of what there is to read. And
 * it obeys a reader who has asked for less motion, which on a jump of two thousand pixels is not a
 * nicety: the smooth form of it is the one that can make somebody ill.
 *
 * The four fifths is a margin rather than a threshold. A panel whose heading is at the very bottom
 * of the window is technically visible and holds nothing a reader can act on.
 */
export function revealChoice(target: HTMLElement | null) {
    if (!target) {
        return;
    }
    /*
     * Next frame, and this is not a nicety. The panel is filled by a state update, and a state
     * update reaches the document before the next paint rather than before the next statement, so
     * measured here and now the page is still the short one it was. A scroll asked for past the
     * end of a short page is clipped to it and nothing scrolls it again afterwards: measured on
     * the fraud desk at 390, the page was 1464px when the row was pressed and 4897 once the alert
     * arrived, and the panel ended up at 664 of an 844px window instead of at its top.
     */
    requestAnimationFrame(() => {
        const top = target.getBoundingClientRect().top;
        if (top >= 0 && top < window.innerHeight * 0.8) {
            return;
        }
        const still = window.matchMedia('(prefers-reduced-motion: reduce)').matches;
        target.scrollIntoView({ block: 'start', behavior: still ? 'auto' : 'smooth' });
    });
}
