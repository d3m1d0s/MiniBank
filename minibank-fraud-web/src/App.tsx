import { useEffect, useState, useSyncExternalStore } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import { fetchMe, logoutSession, setSessionExpiredHandler, type Me } from './api';
/*
 * The one import in this window that does not go through the api barrel next door, and the line is
 * where it is because of what the barrel is: everything this application asks of the bank. Reading
 * back the record this tab wrote for itself asks nothing of anybody, and every request that follows
 * still goes through ./api exactly as before.
 */
import { restoreSession } from '@shared/http';
/* The alert code, spelled the way the queue card and the detail panel spell it. */
import {
    NO_SCREENS_TITLE,
    SIGNED_OUT_WITH_LOSS,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
    servedRole,
    type NavRole,
} from '@shared/navigation';
import {
    allowedForRole,
    homeRoute,
    parseHash,
    replaceRoute,
    subscribeToHash,
    type Route,
} from '@shared/route';

/**
 * What useSyncExternalStore reads, kept out of the component so that the same function is handed
 * to it on every render rather than a new one that means the same thing.
 */
const readHash = () => location.hash;

/**
 * What counts as somebody working, as far as the session is concerned.
 *
 * Three events and no more, chosen because each of them is a person and none of them is the
 * program: a key pressed, a pointer put down, a wheel turned. `mousemove` is not here, and that
 * is the whole difference between honouring the idle rule and repealing it, since a pointer left
 * resting on a trackpad reports movement for hours after its owner has gone home.
 *
 * Reading a long alert by scrolling is work, so `wheel` counts. It is listened to passively: this
 * side has an opinion about whether the session is alive, not about whether the page may scroll.
 */
const ACTIVITY_EVENTS = ['keydown', 'pointerdown', 'wheel'] as const;

/**
 * How much of the idle allowance may pass before an act of work is turned into a request.
 *
 * Five minutes against the server's fifteen, so that somebody working without a break is three
 * refreshes clear of the deadline and one dropped request cannot end a session under their hands.
 * Well short of the allowance, and far enough from it that a redundant call is cheaper than a
 * misjudged one.
 *
 * It bounds the requests, it does not schedule them. There is no timer anywhere in this file: the
 * clock is read inside a handler that a person has just fired, so a desk nobody is at asks the
 * bank nothing and is signed out exactly when the rule says. That is the difference between
 * extending a session somebody is using and polling to stay signed in forever.
 */
const ACTIVITY_REFRESH_MS = 5 * 60 * 1000;

interface AuthState {
    username: string;
    role: NavRole | null;
    customerId: number | null;
}

export default function App() {
    /*
     * The session this tab was already holding, read once on the way in.
     *
     * An initializer and not an effect. An effect would run after the first paint, so a reload of
     * a working session would show one frame of the sign-in card before the desk came back: the
     * analyst would watch the bank appear to throw them out and then take it back. Read here,
     * there is no such frame, and the desk is what the reload draws.
     *
     * Nothing asks the server whether the id is still good, and it does not need to: the fetchMe
     * below is the first request either way, and a session the server has forgotten answers 401
     * AUTH_REQUIRED, which is the path that already ends at the sign-in card with the notice.
     *
     * servedRole takes a plain string, which is exactly right for a value that came out of the
     * tab's storage: the stored role is not checked against the four names, so a record naming a
     * role this window has no screens for lands on the panel below rather than at the desk.
     */
    const [auth, setAuth] = useState<AuthState | null>(() => {
        const kept = restoreSession();
        return kept
            ? { username: kept.username, role: servedRole(kept.role), customerId: kept.customerId }
            : null;
    });
    const [signedOutReason, setSignedOutReason] = useState<string | null>(null);
    const [me, setMe] = useState<Me | null>(null);

    /**
     * Whose column the address is allowed to be corrected against, which in this window is one
     * role and nobody else.
     *
     * The correction below reads the navigation column of the role it is given, and that column
     * belongs to the product rather than to this application: handed CUSTOMER it would rewrite the
     * address bar of the workstation to `#/payments/new`, an address this origin does not serve,
     * over a panel that says so in words. Null corrects nothing and writes nothing, which is the
     * honest answer for somebody standing at a window that has no screen for them.
     */
    const routingRole: NavRole | null = auth?.role === 'FRAUD_ANALYST' ? auth.role : null;

    /**
     * The address bar, as something this window can render from.
     *
     * The hash is not this application's state, it is the browser's, and useSyncExternalStore is
     * the hook React has for exactly that: subscribe, read, and let React decide when the value it
     * read has changed. Held as the string rather than as a parsed Route, because a snapshot must
     * be comparable and a fresh object out of every read is a render loop.
     *
     * The obvious alternative, a piece of state seeded once and written from a hashchange
     * listener, is also what react-hooks/set-state-in-effect refuses at error in both
     * applications: a setState in the body of an effect is a second render of every screen for a
     * value that was available before the first one.
     */
    const hash = useSyncExternalStore(subscribeToHash, readHash);

    /**
     * Which alert the address bar is pointing at.
     *
     * This window has one screen, so the address carries only the selection, and that is worth
     * having on its own: `#/alerts/12` is an alert one analyst can send another to, and a
     * selection kept in component state is one a reload throws away. An address this application
     * does not serve leaves the desk with nothing selected, which is what the fallback says.
     */
    const route: Route = parseHash(hash) ?? { kind: 'fraud-desk', id: null };

    /**
     * The address corrected where it says something this role cannot have, which is the one thing
     * here that writes rather than reads.
     *
     * An effect and not a line of render: writing to the address bar while React is deciding what
     * to draw is a side effect in the middle of a pure function. It replaces the history entry
     * instead of pushing one, or the address nobody can use would sit one Back press away, and
     * Back is exactly where somebody goes when a screen surprises them.
     *
     * Nothing is corrected before there is a role to correct against: an analyst who followed
     * `#/alerts/12` and met the sign-in card must still land on alert 12 afterwards, and a
     * customer at this workstation is told in words that there is no screen here for them rather
     * than being sent to an address this origin does not serve.
     */
    useEffect(() => {
        if (!routingRole) return;
        const shown = parseHash(hash);
        if (shown && allowedForRole(routingRole, shown)) return;
        replaceRoute(homeRoute(routingRole));
    }, [routingRole, hash]);

    // This app already had the "return to sign-in" mechanism, on the sign-out controls below.
    // Nothing in any error path ever invoked it, so a session the server had forgotten
    // left the analyst on a queue where every request failed. Sessions now go idle, hit a
    // ceiling and end when the analyst's own row changes, so this fires on a schedule rather
    // than only after a restart - and the notice names no cause, because the server
    // deliberately answers all of them the same way.
    useEffect(() => {
        setSessionExpiredHandler(() => {
            setAuth(null);
            // A name left standing after the session has gone would put the ejected analyst's
            // name over whoever reaches this workstation next.
            setMe(null);
            // The address is read here rather than closed over: this handler is installed once,
            // and a route captured then would name whatever alert was open when the tab opened.
            setSignedOutReason(SIGNED_OUT_WITH_LOSS);
        });
        return () => setSessionExpiredHandler(null);
    }, []);

    // Asked once per session, and by the route the customer application asks. The name is dropped
    // where the session is dropped rather than here, both because an effect that sets state on the
    // way in is a render that could have been avoided, and because the two places that end a
    // session are the two that know it has ended. The hook sits above the early returns below
    // because the rules of hooks require it.
    useEffect(() => {
        if (!auth) return;

        let cancelled = false;

        void (async () => {
            try {
                const who = await fetchMe();
                if (!cancelled) setMe(who);
            } catch {
                // Nothing on any screen depends on the name; the title bar keeps the login.
            }
        })();

        return () => {
            cancelled = true;
        };
    }, [auth]);

    /**
     * What makes typing count.
     *
     * SessionStore.IDLE_TIMEOUT is refreshed inside resolve, that is by a request and by nothing
     * else, so until now an analyst could write for twenty minutes and be signed out at the press
     * of Decline: the work was real, the session had no way to hear about it, and the sentence in
     * the title bar had to admit as much. This turns the work into the one thing the server
     * listens to.
     *
     * GET /api/me and not a route invented for this. It is the cheapest read the application has,
     * it is what both shells already ask on the way in, and it needs no server change; nothing is
     * done with the answer, because the point of the call is that it was made at all.
     *
     * `last` starts at the sign-in, which was itself a request. The screens make plenty of others
     * that this shell never sees, so the gap it measures is the worst case rather than the truth,
     * and the cost of that is an occasional call the server did not need. The other way round,
     * counting a request that never happened, is a session that ends while somebody is mid
     * sentence, and that is the failure this exists to prevent.
     *
     * A refusal is not swallowed into silence: a 401 AUTH_REQUIRED goes through handle() exactly
     * as any other read does, so it reaches the expiry handler above and the person is told, with
     * the alert they were on named, rather than typing on into a session the bank has closed.
     * Nothing else about the failure is shown, because nobody asked for this call.
     */
    useEffect(() => {
        if (!auth) return;

        let last = Date.now();
        let asking = false;

        const worked = () => {
            const now = Date.now();
            if (asking || now - last < ACTIVITY_REFRESH_MS) return;
            last = now;
            asking = true;
            void fetchMe()
                .catch(() => {
                    // The 401 has already ejected whoever is here, by the path every other read
                    // takes. Anything else is a read nobody asked for, and it has nothing to say.
                })
                .finally(() => {
                    asking = false;
                });
        };

        for (const event of ACTIVITY_EVENTS) {
            window.addEventListener(event, worked, { passive: true });
        }
        return () => {
            for (const event of ACTIVITY_EVENTS) {
                window.removeEventListener(event, worked);
            }
        };
    }, [auth]);

    // Closes the session at both ends before dropping back to the dialog, and drops the name with
    // it. Written once here because both ways out of this window press the same thing.
    const handleSignOut = () => {
        logoutSession();
        setAuth(null);
        setMe(null);
    };

    if (!auth) {
        return (
            <Login
                notice={signedOutReason}
                onLoggedIn={(info) => {
                    setSignedOutReason(null);
                    setAuth({
                        username: info.username,
                        role: servedRole(info.role),
                        customerId: info.customerId,
                    });
                }}
            />
        );
    }

    if (auth.role !== 'FRAUD_ANALYST') {
        /*
         * Two different truths, and one sentence cannot carry both. A customer has screens in
         * MiniBank and simply not in this workstation yet; a role this client cannot name has
         * none anywhere. Neither line prints the role constant: for a customer the fact is
         * stated in prose, and for the other case the client has narrowed its type on purpose
         * so that it has no name to print, and inventing one would promise screens that do not
         * exist. The window is the sign-in window, because this person is at the door and not
         * inside: a full working window put two short lines in the corner of 700px of white.
         */
        const customer = auth.role === 'CUSTOMER';
        return (
            <div className="shell">
                <div className="window window--login">
                    {/*
                      * The same two parts the working window is built from, and named the same
                      * way: the band the bank is printed on, and the one region the window is
                      * for. This screen carried the name as bare text inside a plain div, so of
                      * the three windows this application draws it was the one where a reader
                      * who cannot see it was given a sentence with nothing above it. The class
                      * on the name is the one the door and the working window already use, so
                      * nothing about the type moves.
                      */}
                    <header className="titlebar">
                        <div className="title"><h1 className="brand-name">MiniBank</h1></div>
                    </header>
                    <div className="content">
                        <main className="panel">
                            {/*
                              * The second branch is both applications' sentence and is read from
                              * the shared module. The first is this window's alone: a signed in
                              * customer at the analyst's workstation has screens in MiniBank and
                              * simply not here, which is not what the other branch says.
                              */}
                            <h2 className="panel-title">
                                {customer
                                    ? 'This workstation is for fraud analysts'
                                    : NO_SCREENS_TITLE}
                            </h2>
                            <p>
                                {customer
                                    ? `You are signed in as ${auth.username}, a customer account. Customer screens are not part of this workstation.`
                                    : noScreensNote(auth.username)}
                            </p>
                            {/*
                              * The only control on the screen, so it may be the primary, and it
                              * is on the panel rather than in the chrome: a title bar exit here
                              * would be a second way out doing the same job. "Logout" was the
                              * old label and it is wrong for somebody who never signed in to
                              * this application; what pressing this buys is a different session.
                              */}
                            <div className="actions">
                                <button
                                    className="btn btn--primary"
                                    onClick={handleSignOut}
                                >
                                    {SIGN_IN_AS_SOMEONE_ELSE}
                                </button>
                            </div>
                        </main>
                    </div>
                </div>
            </div>
        );
    }

    /**
     * The name the title bar prints, which is the person's own where the server knows one.
     *
     * The login is the fallback rather than the default, and it is reached twice: before the
     * answer arrives, and for a login with no customer record behind it, which is every analyst
     * today. The answer is checked against the login it was asked for, so a name fetched for the
     * previous session cannot be printed over the current one.
     */
    const signedInAs =
        me && me.username === auth.username && me.name ? me.name : auth.username;

    return (
        <FraudDesk
            username={auth.username}
            signedInAs={signedInAs}
            /* Narrowed one line above, so this is FRAUD_ANALYST and the header can say so. */
            role={auth.role}
            /*
             * Handed down rather than kept at the desk, because the address bar is the shell's
             * to read. The desk publishes a selection back into it and hears the result here on
             * the next hashchange, so there is one value and not two agreeing copies of it.
             */
            selectedId={route.kind === 'fraud-desk' ? route.id : null}
            onLogout={handleSignOut}
        />
    );
}
