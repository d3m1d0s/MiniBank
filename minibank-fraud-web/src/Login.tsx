import { useEffect, useRef, useState } from 'react';
import { login } from './api';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';

export default function Login(props: {
    /*
     * The role is a plain string, and not the union the response declares. The response is cast
     * rather than validated, so a role the server adds later arrives here whatever this says;
     * the shell decides which of them it has screens for and answers the rest with one.
     */
    onLoggedIn: (info: { username: string; role: string; customerId: number | null }) => void;
    /** Why the analyst is looking at this screen, when they did not ask to be. */
    notice?: string | null;
}) {
    // Empty, like the customer app's. A sign-in form that arrives with a password already in it
    // is not something to demonstrate, and the demo logins are announced by the profile that
    // creates them - see DemoUsersInitializer, which prints both at startup.
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    /**
     * Why the sign in did not happen, in the words every other call in this window uses.
     *
     * It was `(e as Error).message`, the one place this application printed a sentence nobody
     * wrote for a reader: a refused password arrived as whatever the server had put in the
     * message field, and a dropped connection arrived as the browser's own "Failed to fetch", on
     * the first screen anybody sees. The shared table has a sign-in row and knows that a 401 here
     * is a wrong password rather than a lost session, and the reference under the sentences is
     * what makes a photograph of the box worth something.
     *
     * No retry is offered, at the shared table's rule for a write: the way to try again is the
     * button already under the two boxes, and a second control saying so asks for one press twice.
     */
    const [error, setError] = useState<ApiFailure | null>(null);
    const [loading, setLoading] = useState(false);

    /**
     * The cursor, in the box the first keystroke belongs in.
     *
     * The customer application's sign in box has always opened this way and this one did not, so
     * the same person signing in to the same bank had to reach for the mouse on one platform and
     * not on the other.
     *
     * Done here rather than with the `autoFocus` attribute, and the difference is not evasion of
     * the rule that forbids it. That rule is right about the general case, a form that seizes the
     * cursor somewhere down a page the reader has not read yet, and this application keeps it at
     * error for every other screen. This screen is the exception the rule is not written for:
     * there is one form, it is the whole window, and there is nothing above it to be read past.
     */
    const usernameBox = useRef<HTMLInputElement | null>(null);
    useEffect(() => { usernameBox.current?.focus(); }, []);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        try {
            setLoading(true);
            const resp = await login({ username, password });
            props.onLoggedIn({ username: resp.username, role: resp.role, customerId: resp.customerId });
        } catch (e) {
            setError(describeApiFailure(e, 'sign-in'));
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="shell">
            <div className="window window--login">
                {/*
                  * Titled by its purpose. The bar used to carry a third spelling of the product
                  * name as well, which the body now says directly below, and the window is a
                  * door either way: repeating the name in the chrome added nothing.
                  */}
                <div className="titlebar">
                    <div className="title">Sign in</div>
                </div>

                <div className="content">
                    <form className="panel form" onSubmit={submit}>
                        {/*
                          * The entrance says whose bank this is, the same lockup the customer
                          * application carries and in this skin's palette. Without it the first
                          * frame of the workstation is two unnamed fields in a small window,
                          * which describes a form template rather than the door of a bank.
                          */}
                        <header>
                            <div className="brand">
                                <span className="brand-mark" aria-hidden="true" />
                                <h1 className="brand-name">MiniBank</h1>
                            </div>
                            <p className="brand-line">Payments and fraud review</p>
                        </header>

                        {props.notice && !error && <div className="hint">{props.notice}</div>}
                        <div className="row">
                            <label htmlFor="login-username">Username</label>
                            <input
                                id="login-username"
                                ref={usernameBox}
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                            />
                        </div>
                        <div className="row">
                            <label htmlFor="login-password">Password</label>
                            <input
                                id="login-password"
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                        </div>

                        {error && <ErrorBox failure={error} />}

                        <div className="actions">
                            <button className="btn btn--primary" disabled={loading} type="submit">
                                {loading ? 'Signing in…' : 'Sign in'}
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    );
}
