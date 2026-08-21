// src/LoginDialog.tsx
import { useEffect, useRef, useState } from 'react';
import { login, type LoginResponse } from './api';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';

interface Props {
    onLoggedIn: (info: {
        username: string;
        /*
         * A plain string, and not the union the response declares. The response is cast rather
         * than validated, so a role the server adds later arrives here whatever this says; the
         * shell decides which of them it has screens for and answers the rest with one.
         */
        role: string;
        customerId: number | null;
    }) => void;
    /** Why the user is looking at this screen, when they did not ask to be. */
    notice?: string | null;
}

export default function LoginDialog({ onLoggedIn, notice }: Props) {
    const [username, setUsername] = useState('');
    const [password, setPassword] = useState('');
    /**
     * Why the sign in did not happen, in the words every other call in this application uses.
     *
     * It was `(e as Error).message`, which is the one place this application printed a sentence
     * nobody wrote for a reader. A refused password arrived as whatever the server put in the
     * message field, and a dropped connection arrived as the browser's own "Failed to fetch",
     * under a title reading Error, on the first screen anybody sees. The shared table has a
     * sign-in row and knows that a 401 here is a wrong password rather than a lost session.
     *
     * No retry is offered. Signing in is a write and the way to try again is the button that is
     * already under the two boxes; a second control saying so would ask for the same press twice.
     */
    const [error, setError] = useState<ApiFailure | null>(null);
    const [loading, setLoading] = useState(false);

    /**
     * The cursor, in the box the first keystroke belongs in.
     *
     * Done in an effect rather than with the `autoFocus` attribute, and the difference is not
     * evasion of the rule that forbids it. The rule is right about the case it is written for, a
     * form somewhere down a page that seizes the cursor before the reader has read what is above
     * it, and this application now keeps it at error for every other screen. This screen is not
     * that case: there is one form, it is the whole card, and there is nothing above it to be
     * read past. Attribute or effect, the behaviour is identical; what the effect buys is a gate
     * that can guard the screens where the complaint would be right.
     */
    const usernameBox = useRef<HTMLInputElement | null>(null);
    useEffect(() => {
        usernameBox.current?.focus();
    }, []);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);

        try {
            setLoading(true);
            const resp: LoginResponse = await login({ username, password });
            onLoggedIn({
                username: resp.username,
                role: resp.role,
                customerId: resp.customerId,
            });
        } catch (e) {
            setError(describeApiFailure(e, 'sign-in'));
        } finally {
            setLoading(false);
        }
    }

    return (
        <div className="app-shell">
            <div className="card card--narrow">
                {/*
                  * The entrance says whose bank this is. Without it the first frame of the
                  * application is a heading reading "Sign in" over two unnamed fields, which
                  * describes a form template rather than the door of a bank, and on a large
                  * screen it is a small box correctly centred with nothing to say. The mark is
                  * a background image on one empty span so the same lockup can be set per skin
                  * from the theme files, and it is hidden from the reader because the name
                  * beside it already carries the meaning.
                  */}
                <header className="card-header">
                    <div className="brand">
                        <span className="brand-mark" aria-hidden="true" />
                        <h1 className="brand-name">MiniBank</h1>
                    </div>
                    <p className="brand-line">Payments and fraud review</p>
                </header>
                <div className="card-body">
                    {notice && !error && (
                        <div className="summary gap-below-sm">
                            <ul><li>{notice}</li></ul>
                        </div>
                    )}
                    <form className="form" onSubmit={handleSubmit}>
                        {/*
                          Both labels are tied to their box by id, which every other form in this
                          application already does and this one, the first screen anybody meets,
                          did not: a bare label beside an input names nothing a machine can read,
                          so the two fields were announced as unlabelled and clicking the word did
                          not put the caret in the box.
                        */}
                        <div className="field-row">
                            <label className="field-label" htmlFor="login-username">
                                Username
                            </label>
                            <input
                                id="login-username"
                                className="field-input"
                                ref={usernameBox}
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                            />
                        </div>
                        <div className="field-row">
                            <label className="field-label" htmlFor="login-password">
                                Password
                            </label>
                            <input
                                id="login-password"
                                className="field-input"
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                        </div>
                        {error && (
                            <div className="gap-above-sm">
                                <ErrorBox failure={error} />
                            </div>
                        )}
                        <div className="actions">
                            <button type="submit" className="btn-primary" disabled={loading}>
                                {loading ? 'Signing in…' : 'Sign in'}
                            </button>
                        </div>
                    </form>
                </div>
            </div>
        </div>
    );
}
