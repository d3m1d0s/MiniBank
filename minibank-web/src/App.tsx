// src/App.tsx
import { useEffect, useState, type ReactNode } from 'react';
import './App.css';
import { setSessionExpiredHandler, logoutSession, fetchMe, type Me } from './api';
/*
 * The session this tab is holding, read from the module that keeps it. api.ts is this
 * application's list of calls, and restoring a session asks the server nothing: what it reads is
 * the record the tab wrote for itself at sign-in.
 */
import { restoreSession } from '@shared/http';
import type { NavRole, NavView } from '@shared/navigation';
import {
    ACTIVITY_EVENTS,
    ACTIVITY_REFRESH_MS,
    NO_SCREENS_TITLE,
    SESSION_IDLE_NOTE,
    SIGN_IN_AS_SOMEONE_ELSE,
    noScreensNote,
    signedOutFrom,
} from '@shared/navigation';
import {
    allowedForRole,
    currentRoute,
    goTo,
    routeFor,
    subscribeToHash,
    type Route,
} from '@shared/route';
import { roleLabel } from '@shared/glossary';
import NewPaymentPage from './NewPaymentPage';
import HistoryPage from './HistoryPage';
import { WaitingAuthorizationsPage } from './WaitingAuthorizationsPage';
import FraudDeskPage from './FraudDeskPage';
import LoginDialog from './LoginDialog.tsx';

/**
 * The roles this client has screens for. The server's UserRole has four values; two of them
 * have nothing to show here, so they are named nowhere in this file except as the absence
 * below. NavRole already declares exactly these two, so they are not declared a second time.
 */
const SERVED_ROLES: readonly NavRole[] = ['CUSTOMER', 'FRAUD_ANALYST'];

/**
 * What was open when the session ended, and what that costs whoever was in the middle of it.
 *
 * Read from the address at the moment the session ends rather than from a value captured when the
 * handler was registered: the handler is installed once, so anything it closed over would name the
 * screen that was open when the tab was opened. The hash is read directly for the same reason it
 * is read here at all, and because the reader that resolves a route needs a role this effect does
 * not have.
 *
 * The second clause is the one that matters. Told only that they have been signed out, somebody
 * who had just pressed Send has no way to know whether the money moved, and the honest answer is
 * that a request refused with 401 changed nothing.
 */
function signedOutSentence(hash: string): string {
    const head = hash.replace(/^#\/?/, '').split('/')[0];

    if (head === 'payments') {
        return signedOutFrom(
            'You were filling in a payment.',
            'Nothing you had typed was sent, so the amount, the beneficiary and your reference ' +
            'have not been kept, and no payment was made.',
        );
    }
    if (head === 'authorizations') {
        return signedOutFrom(
            'You were on Waiting authorizations, and signing in opens it again.',
            'Nothing you had typed was sent, so a code in the box has not been kept, and no ' +
            'payment was confirmed or cancelled.',
        );
    }
    if (head === 'alerts') {
        return signedOutFrom(
            'You were at the fraud desk, and signing in opens it again.',
            'Nothing you had typed was sent, so a decision comment or a note in progress has not ' +
            'been kept, and no decision was recorded.',
        );
    }
    return signedOutFrom(
        'You were reading your payment history.',
        'Nothing was sent and nothing has changed.',
    );
}

/**
 * The role when this client has screens for it, null when it does not.
 *
 * The argument is a plain string, and that is the point: the sign-in response is cast rather
 * than validated, so typing it as the two served roles would let a third walk through every
 * comparison that looks exhaustive. An unknown role is parsed here and answered with a screen
 * instead of reaching a customer form and failing there on a 403.
 */
function servedRole(role: string): NavRole | null {
    return (SERVED_ROLES as readonly string[]).includes(role) ? (role as NavRole) : null;
}

interface AuthState {
    username: string;
    role: NavRole | null;
    customerId: number | null;
}

function App() {
    /**
     * The session this tab was already holding, read once on the way in.
     *
     * An initializer and not an effect. An effect runs after the first paint, so a reload of a
     * working session would show one frame of the sign-in card before the screens came back: the
     * bank appearing to throw somebody out and then take it back. Read here, that frame does not
     * exist, and a reload draws the screen the person was standing on.
     *
     * Nothing asks the server whether the id is still good, and it does not need to. The first
     * request this session makes answers 401 AUTH_REQUIRED if it is not, and that path already
     * ends at the sign-in card with the notice; GET /api/me below is that request.
     *
     * servedRole takes a plain string, which is exactly right for a value that came out of the
     * tab's storage: the stored role is not checked against the four names, so a record naming a
     * role this application has no screens for lands on the panel below rather than on a payment
     * form that would fail at its first call.
     */
    const [auth, setAuth] = useState<AuthState | null>(() => {
        const kept = restoreSession();
        return kept
            ? { username: kept.username, role: servedRole(kept.role), customerId: kept.customerId }
            : null;
    });
    const [signedOutReason, setSignedOutReason] = useState<string | null>(null);

    /**
     * The person behind the login, or null until the answer arrives.
     *
     * The sign-in response carries the string that was typed into the box and nothing else, so
     * the header band greeted a customer whose record says Alice Novakova as `alice`. This is the
     * one call in the application whose failure changes nothing: the band falls back to the login
     * it already has, so there is no error state and no loading state for it.
     */
    const [me, setMe] = useState<Me | null>(null);

    // A 401 AUTH_REQUIRED means the server no longer accepts the session this app is
    // holding: it has gone idle, hit its ceiling, been closed, or the user behind it has
    // been removed or replaced - and the server deliberately does not say which. Until now
    // there was no path back to the sign-in screen at all: setAuth was called exactly once
    // and this app has no logout control, so recovery was an undiscoverable F5. The reason
    // is carried across so the user is not simply ejected mid-payment with no explanation,
    // and it names none of the four causes, because "expired" would be a false statement
    // about three of them. The hook sits above the early return below because the rules of
    // hooks require it.
    useEffect(() => {
        setSessionExpiredHandler(() => {
            setAuth(null);
            // A name left standing after the session has gone would greet whoever reaches this
            // browser next by the person who was ejected from it.
            setMe(null);
            setSignedOutReason(signedOutSentence(window.location.hash));
        });
        return () => setSessionExpiredHandler(null);
    }, []);

    // Asked once per session. The name is dropped where the session is dropped rather than here,
    // both because an effect that sets state on the way in is a render that could have been
    // avoided, and because the two places that end a session are the two that know it has ended.
    // The hook sits above the early return below because the rules of hooks require it.
    useEffect(() => {
        if (!auth) return;

        let cancelled = false;

        void (async () => {
            try {
                const who = await fetchMe();
                if (!cancelled) setMe(who);
            } catch {
                // Nothing on any screen depends on the name; the band keeps the login.
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
     * else, so until now a customer could spend twenty minutes over the reference line of a
     * payment and be signed out at the press of Send: the work was real and the session had no way
     * to hear about it. This turns the work into the one thing the server listens to.
     *
     * GET /api/me and not a route invented for this. It is the cheapest read the application has,
     * it is what this shell already asks on the way in, and it needs no server change; nothing is
     * done with the answer, because the point of the call is that it was made at all.
     *
     * A refusal is not swallowed: a 401 AUTH_REQUIRED goes through the same handler every other
     * read does, so whoever is here is told, with the screen they were on named, rather than
     * typing on into a session the bank has already closed.
     *
     * The same three events and the same floor as the workstation, from the shared module, because
     * one role is owed the same product on both platforms and a second copy of this is how the two
     * come apart.
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

    // While the user is not logged in, show only the login dialog
    if (!auth) {
        return (
            <LoginDialog
                notice={signedOutReason}
                onLoggedIn={(info) => {
                    setSignedOutReason(null);
                    const role = servedRole(info.role);
                    setAuth({ username: info.username, role, customerId: info.customerId });
                    // The first screen is not chosen here any more. The effect above re-reads the
                    // address as soon as the role arrives, and currentRoute answers with the first
                    // screen of that role's column for an address it cannot have. Choosing here as
                    // well would be two answers to one question, and the one written second would
                    // overwrite an address somebody had typed on purpose.
                }}
            />
        );
    }

    // Closes the session at both ends before dropping back to the dialog. Without the call the
    // server would keep a fully usable session for a user who believes they have left.
    const handleSignOut = () => {
        logoutSession();
        setSignedOutReason(null);
        setAuth(null);
        setMe(null);
    };

    // Ahead of the view switch, so a role with no screens never reaches the payment form and
    // never fires the 403 that form used to answer with.
    if (!auth.role) {
        return (
            <div className="app-shell">
                <div className="card">
                    <header className="card-header">
                        <h1>MiniBank</h1>
                    </header>
                    <div className="card-body">
                        <main className="form-panel">
                            <h2>{NO_SCREENS_TITLE}</h2>
                            <p className="helper-text">{noScreensNote(auth.username)}</p>
                            <div className="actions">
                                <button
                                    type="button"
                                    className="btn-primary"
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
     * The name the band greets, which is the person's own where the server knows one.
     *
     * The login is the fallback rather than the default, and it is reached twice: before the
     * answer arrives, and for a login with no customer record behind it, which is every analyst.
     * The answer is checked against the login it was asked for, so a name fetched for the
     * previous session cannot be printed over the current one.
     */
    const signedInAs =
        me && me.username === auth.username && me.name ? me.name : auth.username;

    /**
     * Who is signed in and the way out, built once here and handed to every screen as one
     * opaque piece of furniture, so no screen has to learn what a role or a session is.
     * The person is named because this application serves two roles from one sign-in and
     * gives each a different set of screens, and the role label is the only thing on screen
     * that explains why the screens differ.
     */
    const identity = (
        <div className="identity">
            <span className="identity-who">
                {signedInAs}
                <span className="identity-role">{roleLabel(auth.role)}</span>
            </span>
            {/*
              * The rule the session is judged by, beside the name it applies to and next to the
              * way out, which is the corner of the band that is about the session rather than
              * about the screen. A rule somebody is judged by is one they are owed in advance,
              * and the workstation says the same sentence in the same corner of its title bar.
              */}
            <span className="identity-idle">{SESSION_IDLE_NOTE}</span>
            <button type="button" className="identity-exit" onClick={handleSignOut}>
                Sign out
            </button>
        </div>
    );

    /**
     * The mark and the name of the application, built once here and handed to every screen the
     * same way identity is. The band across the top says which application this is; the screen
     * says its own name at the top of the working area, where the work begins.
     */
    const brand = (
        <div className="brand">
            <span className="brand-mark" aria-hidden="true" />
            <h1 className="brand-name">MiniBank</h1>
        </div>
    );

    return (
        <Screens
            role={auth.role}
            /* The login and not the name: the assignee column holds what was typed at sign-in,
               so the fraud queue's mine filter is a match against that string. */
            username={auth.username}
            brand={brand}
            identity={identity}
        />
    );
}

interface ScreensProps {
    role: NavRole;
    username: string;
    brand: ReactNode;
    identity: ReactNode;
}

/**
 * The screens of a signed in role, and the address that says which of them is on.
 *
 * A component of its own rather than the tail of App, and the reason is the first line of it: the
 * route is read when this mounts, which is the moment a role is known. Held in App it would have
 * to be born under the sign-in card with no role to check it against and then put right by an
 * effect once one arrived, which is a second answer to the question the address already answers.
 * Signing out unmounts this, so nothing a session had is left standing for whoever signs in next.
 *
 * The screen used to be a piece of component state, which is to say it had no address at all: a
 * reload landed on the first screen of the role, the third screen in could not be sent to anybody,
 * and Back from it left the bank. The parsing and the correcting are shared with the workstation,
 * which runs the same rules against its own column; what is written here is the subscription.
 */
function Screens({ role, username, brand, identity }: ScreensProps) {
    const [route, setRoute] = useState<Route>(() => currentRoute(role));
    useEffect(() => subscribeToHash(() => setRoute(currentRoute(role))), [role]);

    /**
     * A move somebody made in the navigation column, which is now written into the address.
     *
     * The restriction is kept and it is not the only one: currentRoute refuses the same address
     * when it is typed by hand. The two are deliberate rather than duplicated, because they
     * answer different things - this one refuses a press, and that one corrects an address that
     * is already in the bar.
     */
    const handleNavigate = (next: NavView) => {
        const target = routeFor(next);
        if (!allowedForRole(role, target)) return;
        goTo(target);
    };

    /**
     * The payment form is built once for a customer's session and hidden while the address points
     * somewhere else, where every other screen is built and thrown away with each move.
     *
     * It is the one screen that holds something a person would be sorry to lose. What it holds is
     * the confirmation of a payment they have just made, which carries the transfer number, the
     * fee and the new balance and is the only place any of that is written down, and a half typed
     * payment beside it. Both used to die on the way to the history and come back as an empty
     * form, so reading what a payment had cost cost the reader the receipt.
     *
     * The cost of keeping it, stated here rather than discovered later: the balances in the
     * account picker are the ones its own last request brought back, so a payment authorized on
     * the waiting screen is not in them until that screen fetches again, which today happens when
     * a payment is sent from it. The figure a customer reads after a payment is the one on the
     * confirmation, and the server sent that one with the payment.
     *
     * Hidden with display and not with a flag on the component: this shell does not reach into a
     * screen's props to tell it it is off stage. A parked screen is out of the accessibility tree
     * and out of the tab order for the same reason it is out of sight, so the tab key does not
     * walk through two navigation columns.
     */
    const showPaymentForm = route.kind === 'new-payment';
    const keepsPaymentForm = allowedForRole(role, routeFor('new-payment'));

    return (
        <>
            {keepsPaymentForm && (
                <div className={showPaymentForm ? undefined : 'screen-parked'}>
                    <NewPaymentPage
                        role={role}
                        brand={brand}
                        identity={identity}
                        onNavigate={handleNavigate}
                    />
                </div>
            )}
            {route.kind === 'history' && (
                <HistoryPage
                    role={role}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
            {route.kind === 'waiting-auth' && (
                <WaitingAuthorizationsPage
                    role={role}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
            {route.kind === 'fraud-desk' && (
                <FraudDeskPage
                    role={role}
                    username={username}
                    brand={brand}
                    identity={identity}
                    onNavigate={handleNavigate}
                />
            )}
        </>
    );
}

export default App;
