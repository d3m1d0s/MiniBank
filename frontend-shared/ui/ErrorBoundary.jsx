/**
 * The last stop between one throw during render and a blank window.
 *
 * There was no boundary anywhere in either application, so an error thrown while React was
 * rendering unmounted the whole tree: the page went white, with no sentence on it and no control
 * on it, and the only way on was the browser's reload button. This is what both shells wrap
 * themselves in so that the same throw costs one screen instead of the application.
 *
 * It is deliberately the smallest thing that works, and it holds no state beyond "this failed":
 * a boundary that tries to be clever is a boundary that can throw, and there is nothing
 * underneath it to catch that.
 *
 * Two things it does NOT do, both on purpose:
 * - it does not keep the session. Reloading signs the reader out today, because the session id
 *   lives in a module variable in http.ts. Moving it into storage is a change to the session and
 *   is a batch of its own.
 * - it does not retry the render. Rendering the same tree from the same state throws again, and
 *   a boundary that loops is worse than a boundary that stops.
 *
 * Styled from the theme tokens and from nothing else. Both applications load a theme that
 * declares the same name list, one dark and one light, so the panel arrives in the skin of
 * whichever shell it is standing in and neither stylesheet has to be touched.
 *
 * WHY THIS FILE IS .jsx AND NOT .tsx, WHICH IS WHERE IT BELONGS
 *
 * The shared layer sits beside the two packages and has no node_modules of its own, and there is
 * none above it either. Vite and vitest both resolve `react` from here, which is why this file
 * runs; `tsc -b` does not, because its module resolution walks up from the importing file, so a
 * .tsx here fails with "Cannot find module 'react'" and, for every tag, "This JSX tag requires
 * the module path 'react/jsx-runtime' to exist". Both front ends run tsc in their build, so a
 * .tsx here turns both builds red.
 *
 * The one line that fixes it properly, in the compilerOptions.paths of BOTH
 * minibank-web/tsconfig.app.json and minibank-fraud-web/tsconfig.app.json, beside the @shared
 * entry that is already there:
 *
 *     "react": ["./node_modules/react"], "react/*": ["./node_modules/react/*"],
 *
 * With that in place this file becomes ErrorBoundary.tsx, the annotations move back off the
 * declaration file beside it, and ErrorBoundary.d.ts is deleted. Nothing else about it changes.
 * This run did not own those two files.
 */

import { Component } from 'react';

const panelStyle = {
    minHeight: '100vh',
    display: 'flex',
    alignItems: 'center',
    justifyContent: 'center',
    padding: 'var(--space-9)',
    background: 'var(--color-surface-canvas)',
    color: 'var(--color-text-primary)',
    fontFamily: 'var(--font-family-ui)',
};

const cardStyle = {
    maxWidth: 'var(--size-prose-max)',
    display: 'flex',
    flexDirection: 'column',
    gap: 'var(--space-7)',
    padding: 'var(--space-10)',
    background: 'var(--color-surface-shell)',
    border: '1px solid var(--color-border-container)',
    borderRadius: 'var(--radius-surface)',
};

const headingStyle = {
    margin: 0,
    fontSize: 'var(--text-6)',
    fontWeight: 'var(--weight-semibold)',
    lineHeight: 'var(--leading-tight)',
};

const proseStyle = {
    margin: 0,
    fontSize: 'var(--text-4)',
    lineHeight: 'var(--leading-normal)',
    color: 'var(--color-text-secondary)',
};

const buttonStyle = {
    alignSelf: 'flex-start',
    minHeight: 'var(--control-min-height)',
    padding: '0 var(--space-8)',
    background: 'var(--color-accent)',
    color: 'var(--color-text-on-accent)',
    border: '1px solid var(--color-border-accent)',
    borderRadius: 'var(--radius-control)',
    fontFamily: 'inherit',
    fontSize: 'var(--text-4)',
    fontWeight: 'var(--weight-medium)',
    cursor: 'pointer',
};

const referenceStyle = {
    margin: 0,
    fontSize: 'var(--text-1)',
    lineHeight: 'var(--leading-dense)',
    color: 'var(--color-text-muted)',
    overflowWrap: 'break-word',
};

/**
 * The same job the reference line does under an error box: a screenshot of this panel has to be
 * worth something to whoever is asked to fix it, and the name of the throw is the whole of what
 * is known here. Truncated because a message from a render can be a paragraph and this is a
 * panel, not a console.
 */
function reference(error) {
    const message = String(error && error.message ? error.message : '').trim();
    const name = (error && error.name) || 'Error';
    if (!message) return name;
    const short = message.length > 160 ? `${message.slice(0, 160)}…` : message;
    return `${name}: ${short}`;
}

export class ErrorBoundary extends Component {
    state = { error: null };

    static getDerivedStateFromError(error) {
        return { error };
    }

    componentDidCatch(error, info) {
        // React reports a caught error itself, but without saying which application it belonged
        // to, and in a demo both are open at once. The component stack is the part that says
        // where the throw was, and it is not on the error.
        console.error(
            `${this.props.appName}: a screen stopped while rendering`,
            error,
            info && info.componentStack,
        );
    }

    render() {
        const { error } = this.state;
        if (!error) {
            return this.props.children;
        }

        return (
            <div style={panelStyle} role="alert">
                <div style={cardStyle}>
                    <h1 style={headingStyle}>{this.props.appName} could not show this screen</h1>
                    {/* Says nothing about what did or did not reach the bank. A throw during
                        render can happen after a request has been answered, so the one thing
                        this panel must not do is promise that nothing was sent. */}
                    <p style={proseStyle}>
                        Something went wrong inside the page, so it stopped rather than show you
                        something that might be wrong.
                    </p>
                    <p style={proseStyle}>
                        Reloading starts the application again. Anything typed on this screen and
                        not yet sent is lost.
                    </p>
                    <button
                        type="button"
                        style={buttonStyle}
                        onClick={() => window.location.reload()}
                    >
                        Reload the page
                    </button>
                    <p style={referenceStyle}>{reference(error)}</p>
                </div>
            </div>
        );
    }
}

export default ErrorBoundary;
