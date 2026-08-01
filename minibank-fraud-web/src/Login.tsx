import { useState } from 'react';
import { login, type LoginResponse } from './api';

export default function Login(props: {
    onLoggedIn: (info: { username: string; role: LoginResponse['role']; customerId: number | null }) => void;
    /** Why the analyst is looking at this screen, when they did not ask to be. */
    notice?: string | null;
}) {
    const [username, setUsername] = useState('fraud');
    const [password, setPassword] = useState('fraud123');
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
                <div className="titlebar">
                    <div className="title">MiniBank Fraud — Sign in</div>
                </div>

                <div className="content">
                    <form className="panel form" onSubmit={submit}>
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

                        <div className="hint">Demo: fraud / fraud123</div>
                    </form>
                </div>
            </div>
        </div>
    );
}
