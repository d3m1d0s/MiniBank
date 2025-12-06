import { useState } from 'react';
import { login, type LoginResponse } from './api';

interface Props {
    onLoggedIn: (info: {
        username: string;
        role: LoginResponse['role']; // вместо any
        customerId: number | null;
    }) => void;
}


export default function LoginDialog({ onLoggedIn }: Props) {
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
            <div className="card">
                <header className="card-header">
                    <h1>Sign in</h1>
                </header>
                <div className="card-body">
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
                            <div className="summary" style={{ borderColor: 'salmon', marginTop: 8 }}>
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
                    <p className="helper-text" style={{ marginTop: 8 }}>
                        For demo: alice / alice123 (customer) or fraud / fraud123 (analyst).
                    </p>
                </div>
            </div>
        </div>
    );
}
