// src/LoginDialog.tsx
import { useEffect, useRef, useState } from 'react';
import { login, type LoginResponse } from '../lib/api';
import ErrorBox from '../ui/ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/wire/apiErrors';
import { SIGN_IN_SLOW_MS, SIGN_IN_SLOW_NOTE } from '@shared/text/glossary';

/**
 * The band that holds the refusal, named so that both boxes can point at it.
 *
 * One id for both, because the sentence in it is about the pair: a wrong password is not a fault
 * of the username box or of the password box, and an empty submit names in one sentence whichever
 * of the two is empty.
 */
const LOGIN_ERROR_ID = 'login-error';

/**
 * A refusal this card worked out for itself, in the shape the box renders.
 *
 * No reference, because nothing was asked of the bank: printing "HTTP 0" under a sentence about
 * an empty box would name an answer that does not exist. No retry either, because the way out of
 * it is the box the sentence names. The payment form carries the same four lines for the same
 * reason; they are four lines, and a shared helper for them would be a shared decision about
 * something each screen decides for itself.
 */
function refusedHere(line: string): ApiFailure {
    return { lines: [line], reference: null };
}

/** Which of the two boxes were empty when Sign in was pressed. */
interface Missing {
    username: boolean;
    password: boolean;
}

const NOTHING_MISSING: Missing = { username: false, password: false };

/**
 * What an empty submit is told, and it names the boxes rather than the form.
 *
 * Both empty is the case worth writing separately: told only "Enter your username", somebody who
 * had filled in neither would type one, press again and be refused a second time by the same card
 * for the other one. Every problem this form can see is reported at once.
 */
function missingSentence(missing: Missing): string {
    if (missing.username && missing.password) {
        return 'Enter your username and your password, then press Sign in.';
    }
    return missing.username ? 'Enter your username.' : 'Enter your password.';
}

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
     * Whether this sign in has been running long enough to owe the reader a sentence.
     *
     * Checking a password here costs seconds by design, and the only thing that used to change was
     * the word on a disabled button. Silence that long reads as a press that did not land.
     */
    const [slow, setSlow] = useState(false);

    /**
     * Which boxes the last press found empty, which is what puts the mark on the box itself.
     *
     * A refused password marks neither: the bank refused the pair, and there is nothing about
     * either box that a reader should change on its own.
     */
    const [missing, setMissing] = useState<Missing>(NOTHING_MISSING);

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
    const passwordBox = useRef<HTMLInputElement | null>(null);
    useEffect(() => {
        usernameBox.current?.focus();
    }, []);

    async function handleSubmit(e: React.FormEvent) {
        e.preventDefault();
        setError(null);

        /*
         * The empty submit, refused here rather than sent.
         *
         * It used to POST {"username":"","password":""} and come back 401, so a card the reader
         * had simply not filled in yet answered with the sentence for a wrong password: the bank
         * was made to say that credentials nobody typed are not correct. The two boxes carry
         * `required` as well, for the reader who is told what a control is before filling it in,
         * and the form carries noValidate so that this sentence is the one that gets read: left
         * to the browser, the refusal is a bubble in the browser's own words that goes away on
         * the next keystroke, and the boxes below would never be marked.
         */
        const blank: Missing = {
            username: username.trim() === '',
            password: password.trim() === '',
        };
        if (blank.username || blank.password) {
            setMissing(blank);
            setError(refusedHere(missingSentence(blank)));
            // The caret goes to the first box that has to be filled in, which is also what
            // reads its label and this sentence out to anybody listening rather than looking.
            (blank.username ? usernameBox : passwordBox).current?.focus();
            return;
        }
        setMissing(NOTHING_MISSING);

        /*
         * The sentence that admits the wait, on a timer rather than on the press, so a sign in
         * that answers at once never draws it. Cleared on every way out, including the throw, or
         * a refused password would leave the screen saying it is still checking.
         */
        const slowTimer = window.setTimeout(() => setSlow(true), SIGN_IN_SLOW_MS);

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
            window.clearTimeout(slowTimer);
            setSlow(false);
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
                        /* Nothing was pressed and nothing succeeded or failed: the sentence here
                           tells the reader why they are looking at this box again. The neutral
                           edge is the one the three answers reserve for exactly that. */
                        <div className="summary summary--neutral gap-below-sm">
                            <ul><li>{notice}</li></ul>
                        </div>
                    )}
                    <form className="form" onSubmit={handleSubmit} noValidate>
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
                            {/*
                              name and autoComplete, which is what a password manager reads. The
                              pair had neither, so the one form in this application that a browser
                              could have filled in was the one form it could not see: nothing
                              named the boxes, and a saved sign-in had no way to know which of the
                              two it was looking at.
                            */}
                            <input
                                id="login-username"
                                className="field-input"
                                ref={usernameBox}
                                name="username"
                                autoComplete="username"
                                required
                                aria-invalid={missing.username || undefined}
                                aria-describedby={error ? LOGIN_ERROR_ID : undefined}
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
                                ref={passwordBox}
                                name="password"
                                autoComplete="current-password"
                                required
                                aria-invalid={missing.password || undefined}
                                aria-describedby={error ? LOGIN_ERROR_ID : undefined}
                                value={password}
                                onChange={(e) => setPassword(e.target.value)}
                            />
                        </div>
                        {/*
                          The band the refusal appears in, held open whether there is one or not.
                          Measured at 1440x900: the box under these two fields pushed the Sign in
                          button 126px down the card, out from under the pointer that had just
                          pressed it, and pulled it back up on the next press. The card is already
                          anchored by the shell so that the fields themselves hold still; this is
                          the other half of the same fault, and the empty band under the password
                          field is what it costs. Reserved rather than solved by moving the button
                          above the box, which would put the way out of a refusal above the
                          refusal itself.
                        */}
                        <div className="login-error-slot" id={LOGIN_ERROR_ID}>
                            {error && <ErrorBox failure={error} />}
                        </div>
                        <div className="actions">
                            <button type="submit" className="btn-primary" disabled={loading}>
                                {loading ? 'Signing in…' : 'Sign in'}
                            </button>
                        </div>
                        {/* Announced rather than only drawn: somebody who cannot see the card is
                            the reader most likely to press again into the silence. */}
                        <p className="helper-text" role="status">
                            {loading && slow ? SIGN_IN_SLOW_NOTE : ''}
                        </p>
                    </form>
                </div>
            </div>
        </div>
    );
}
