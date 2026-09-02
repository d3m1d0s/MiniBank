import { useEffect, useState, useSyncExternalStore } from 'react';
import Login from './Login';
import FraudDesk from './FraudDesk';
import NavRail from './NavRail';
import NewPaymentScreen from './NewPaymentScreen';
import HistoryScreen from './HistoryScreen';
import WaitingAuthorizationsScreen from './WaitingAuthorizationsScreen';
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
    SESSION_IDLE_NOTE,
    SIGNED_OUT_WITH_LOSS,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
    servedRole,
    type NavRole,
    type NavView,
} from '@shared/navigation';
import { roleLabel } from '@shared/glossary';
import {
    allowedForRole,
    homeRoute,
    navigateToView,
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
     * Whose column the address is allowed to be corrected against.
     *
     * Both served roles now, where it used to be the analyst alone: this window drew one screen, so
     * a customer's `#/payments/new` was an address this origin did not serve and correcting towards
     * it would have sent somebody to nothing. It serves that address, so the column of the role
     * signed in is the right thing to correct against, whichever of the two it is.
     *
     * Null corrects nothing and writes nothing, which is the honest answer for somebody standing at
     * a window that has no screen for them.
     */
    const routingRole: NavRole | null = auth?.role ?? null;

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
     * Which screen the address bar is pointing at, and for the desk, which alert.
     *
     * `#/alerts/12` is an alert one analyst can send another, and a selection kept in component
     * state is one a reload throws away. The customer screens carry no selection in the address
     * today, exactly as they carry none in the customer application, so for them the address is the
     * screen and nothing else.
     *
     * The fallback is the first screen of whoever is signed in rather than a screen named here: a
     * customer with an empty address bar would otherwise be handed the desk for the one render
     * before the correction below runs.
     */
    const route: Route =
        parseHash(hash) ?? (routingRole ? homeRoute(routingRole) : { kind: 'fraud-desk', id: null });

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

    if (!auth.role) {
        /*
         * One truth left here, and it is the narrow one: a role this client cannot name has no
         * screens anywhere in the product. The other case this branch used to carry, a signed in
         * customer at the analyst's workstation, has stopped being true: the customer screens are
         * drawn below, so the sentence saying this window is for fraud analysts would now be a
         * false statement about a window that serves them.
         *
         * The line prints no role constant. The client narrowed its type on purpose so that it has
         * no name to print, and inventing one would promise screens that do not exist. The window
         * is the sign-in window, because this person is at the door and not inside: a full working
         * window put two short lines in the corner of 700px of white.
         */
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
                              * Both applications' sentence, read from the shared module: one
                              * product tells a role it has no screens for in one set of words,
                              * whichever window that person happened to open.
                              */}
                            <h2 className="panel-title">{NO_SCREENS_TITLE}</h2>
                            <p>{noScreensNote(auth.username)}</p>
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

    if (auth.role === 'CUSTOMER') {
        return (
            <CustomerWindow
                signedInAs={signedInAs}
                role={auth.role}
                route={route}
                onLogout={handleSignOut}
            />
        );
    }

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

/**
 * What each customer screen is called in the band across the top of the window.
 *
 * Three names and not four: the desk names itself, in the file that draws it. The words are the
 * customer application's own screen headings, because one screen is called one thing on both
 * platforms; the rail beside them reads the shared column, which calls the middle one History &
 * Statements, and the difference is the owner's, not a drift.
 */
const SCREEN_TITLE: Record<'new-payment' | 'history' | 'waiting-auth', string> = {
    'new-payment': 'New payment',
    history: 'Payment history',
    'waiting-auth': 'Waiting authorizations',
};

/**
 * THE WORKING WINDOW A CUSTOMER GETS, and it is the same window the analyst gets.
 *
 * The four parts of the desk, in the same order and out of the same classes: the band the bank is
 * printed on, the rail of screens, and the working region or regions beside it. Owner decision 5
 * says it stays a window, so nothing here reaches for the customer application's page shell.
 *
 * The content takes one of two shapes and the screen decides which. A payment form and a ledger are
 * read from top to bottom and take .content--page, a rail and one region. The waiting list is a
 * working cycle and takes the reversed split, where the list is the wide track and the chosen
 * payment the bounded one.
 *
 * THE PAYMENT FORM IS BUILT ONCE and hidden while the address points elsewhere, where the other two
 * are built and thrown away with each move. It is the one screen holding something a person would
 * be sorry to lose: the confirmation of a payment they have just made, which carries the transfer
 * number, the fee and the new balance and is the only place any of that is written down, and a half
 * typed payment beside it. Hidden with display and not with a flag on the component, because a
 * parked screen has to leave the accessibility tree and the tab order as well as the sight line.
 *
 * What that costs, said here rather than discovered later: the balances in the account picker are
 * the ones its own last request brought back, so a payment authorized on the waiting screen is not
 * in them until the form fetches again. The figure a customer reads after a payment is the one on
 * the confirmation, and the server sent that one with the payment.
 */
function CustomerWindow({
    signedInAs,
    role,
    route,
    onLogout,
}: {
    signedInAs: string;
    role: NavRole;
    route: Route;
    onLogout: () => void;
}) {
    /**
     * Whether the rail is folded to its handle. It outlives the screen for the reason the desk
     * gives: it is a posture the person takes towards the window, not a property of what is in it.
     */
    const [navFolded, setNavFolded] = useState(false);

    /*
     * The screen, as one of the three this window draws for a customer. The address is corrected
     * against the role's own column one component up, so a fourth kind reaches this only for the
     * single render before that correction lands, and the form is the honest thing to draw in it:
     * it is the screen the correction is on its way to.
     */
    const view: NavView = route.kind === 'fraud-desk' ? 'new-payment' : route.kind;
    const split = view === 'waiting-auth';

    /**
     * A move somebody made in the rail, written into the address.
     *
     * The guard is kept and it is not the only one: the shell corrects the same address when it is
     * typed by hand. The two answer different questions, this one refusing a press and that one
     * correcting an address already in the bar. Both windows call the same function for it now, so
     * a rule about which role may reach which screen is written once.
     */
    const handleNavigate = (next: NavView) => navigateToView(role, next);

    return (
        <div className="shell">
            {/* window--customer marks the half of this window the analyst never sees. Everything
                scoped to it is a customer decision: the reading size, the rail that stays put while
                a screen scrolls under it, and the shape of the split. The desk keeps its own. */}
            <div className="window window--customer">
                <header className="titlebar">
                    {/*
                      One product name, qualified by the screen and not by the role, and the mark in
                      front of it: one lockup on the door, on the desk and here, or on none of them.
                    */}
                    <div className="title">
                        <span className="brand">
                            <span className="brand-mark" aria-hidden="true" />
                            <h1 className="brand-name">MiniBank</h1>
                        </span>
                        <span className="title-screen">{SCREEN_TITLE[view]}</span>
                    </div>
                    <div className="titlebar-right" title={SESSION_IDLE_NOTE}>
                        {/* The word for the role comes from the table both applications read.
                            There is no second role map in this product, and a header that prints
                            CUSTOMER or invents its own wording is how there comes to be one. */}
                        <div className="user">{signedInAs} · {roleLabel(role)}</div>
                        {/* The action paired with "Sign in" is "Sign out". One product, one verb. */}
                        <button className="btn" onClick={onLogout}>Sign out</button>
                    </div>
                </header>

                <div
                    className={
                        split
                            ? 'content content--split content--split--list-led'
                            : 'content content--page'
                    }
                >
                    <NavRail
                        role={role}
                        current={view}
                        folded={navFolded}
                        onToggle={() => setNavFolded((folded) => !folded)}
                        onNavigate={handleNavigate}
                    />

                    {/* Kept mounted at every address, and drawing nothing at three of them. A
                        parked screen is display: none, so it claims no track of the grid beside
                        it and the screen that is on gets the room the rail leaves. */}
                    <NewPaymentScreen parked={view !== 'new-payment'} />

                    {view === 'history' && <HistoryScreen />}
                    {view === 'waiting-auth' && <WaitingAuthorizationsScreen />}
                </div>
            </div>
        </div>
    );
}
