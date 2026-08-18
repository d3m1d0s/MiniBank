import { useState } from 'react';
import { login } from './api';

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
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

    async function submit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);
        try {
            setLoading(true);
            const resp = await login({ username, password });
            props.onLoggedIn({ username: resp.username, role: resp.role, customerId: resp.customerId });
        } catch (e) {
            setError((e as Error).message || 'Login failed');
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
                            <label>Username</label>
                            <input value={username} onChange={(e) => setUsername(e.target.value)} />
                        </div>
                        <div className="row">
                            <label>Password</label>
                            <input type="password" value={password} onChange={(e) => setPassword(e.target.value)} />
                        </div>

                        {error && <div className="error">{error}</div>}

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
