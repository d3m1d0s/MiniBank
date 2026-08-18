// src/LoginDialog.tsx
import { useState } from 'react';
import { login, type LoginResponse } from './api';

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
    const [error, setError] = useState<string | null>(null);
    const [loading, setLoading] = useState(false);

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
            setError((e as Error).message || 'Login failed');
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
                        <div className="field-row">
                            <label className="field-label">Username</label>
                            <input
                                className="field-input"
                                value={username}
                                onChange={(e) => setUsername(e.target.value)}
                                autoFocus
                            />
                        </div>
                        <div className="field-row">
                            <label className="field-label">Password</label>
                            <input
                                className="field-input"
                                type="password"
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                        </div>
                        {error && (
                            <div className="summary summary--danger gap-above-sm">
                                <div className="summary-title">Error</div>
                                <ul><li>{error}</li></ul>
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
