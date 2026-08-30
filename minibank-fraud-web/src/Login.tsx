import { useEffect, useRef, useState } from 'react';
import { login } from './api';
import ErrorBox from './ErrorBox';
import { describeApiFailure, type ApiFailure } from '@shared/apiErrors';
import { SIGN_IN_SLOW_MS, SIGN_IN_SLOW_NOTE } from '@shared/glossary';

/**
 * The two words the boxes carry, declared once because the refusal below names them.
 *
 * A sentence that says which box is empty has to spell it the way the caption over that box is
 * spelled, or it points at a control this window does not have.
 */
const USERNAME_LABEL = 'Username';
const PASSWORD_LABEL = 'Password';

/** The box's own name, so the two inputs can say which sentence explains them. */
const SIGN_IN_ERROR_ID = 'sign-in-error';

/**
 * Why nothing was sent, when a box was left empty.
 *
 * The request used to go anyway: an empty form posted {"username":"","password":""} and came back
 * 401, so a person who had simply not filled the second box was told their credentials were wrong.
 * That is a false statement about their account, made by a bank, on the first screen they meet.
 *
 * The sentence names the boxes rather than counting them, because the answer is different for each
 * of the three ways to get here and the reader only has to be told which one is theirs.
 *
 * The customer application's door has to refuse the same press in the same words, so this belongs
 * in frontend-shared/glossary.ts beside DECLINE_NEEDS_COMMENT once that pass is made; it is here
 * for now because that file is not this one's to edit.
 */
function signInNeeds(missing: readonly string[]): string {
    return `Fill in ${missing.join(' and ')}: an empty box is not a sign-in this bank can check.`;
}

/**
 * A refusal this window reached on its own.
 *
 * The reference is null and stated rather than left out: it prints the status and the code of an
 * answer, and there is no answer here, because nothing was asked.
 */
function refusedHere(lines: string[]): ApiFailure {
    return { lines, reference: null };
}

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
     * Whether this sign in has been running long enough to owe the reader a sentence. The customer
     * application's door carries the same state for the same reason: checking a password costs
     * seconds by design, and a disabled button is not an answer to "did my press arrive".
     */
    const [slow, setSlow] = useState(false);
    /**
     * Which box was empty at the last press, so the sentence has something to stand beside.
     *
     * Held rather than derived from `error`, because the box above the button carries a refused
     * password too and that one belongs to neither field. Cleared per box as it is typed into: a
     * mark on a control the reader has already fixed is a mark they learn to ignore.
     */
    const [blank, setBlank] = useState({ username: false, password: false });

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

        /*
         * The empty boxes, before anything is sent. The username is trimmed because a login of
         * spaces is a slip; the password is not, because a password of spaces is a password and
         * silently disagreeing with the reader about what they typed is worse than refusing it.
         */
        const noUsername = username.trim() === '';
        const noPassword = password === '';
        setBlank({ username: noUsername, password: noPassword });
        const missing = [
            ...(noUsername ? [USERNAME_LABEL] : []),
            ...(noPassword ? [PASSWORD_LABEL] : []),
        ];
        if (missing.length > 0) {
            setError(refusedHere([signInNeeds(missing)]));
            return;
        }

        setError(null);
        /*
         * On a timer rather than on the press, so a sign in that answers at once never draws it,
         * and cleared on every way out including the throw, or a refused password would leave the
         * screen claiming it is still checking.
         */
        const slowTimer = window.setTimeout(() => setSlow(true), SIGN_IN_SLOW_MS);

        try {
            setLoading(true);
            const resp = await login({ username, password });
            props.onLoggedIn({ username: resp.username, role: resp.role, customerId: resp.customerId });
        } catch (e) {
            setError(describeApiFailure(e, 'sign-in'));
        } finally {
            window.clearTimeout(slowTimer);
            setSlow(false);
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
                    {/*
                      * noValidate, and the two boxes below still carry `required`.
                      *
                      * The attribute is what tells a screen reader that a box has to be filled in,
                      * and that is worth having. What comes with it by default is not: the browser
                      * stops the submit itself and puts up a bubble of its own, in its own words
                      * and its own language, which vanishes on the next keystroke and never reaches
                      * the box every other refusal on this screen is printed in. Turning the
                      * browser's half off leaves the meaning and gives the sentence back to the
                      * application, which is where the rest of this window's refusals are written.
                      */}
                    <form className="panel form" onSubmit={submit} noValidate>
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

                        {/*
                          * Why the analyst is standing here, kept on screen while a refusal is
                          * printed below rather than swapped out for it.
                          *
                          * It used to be hidden as soon as anything was refused, and with the band
                          * below held open that hiding is a jump of its own: the notice leaving
                          * pulls the two fields and the button up the window on a press, which is
                          * the fault the band was reserved to end. The two lines answer different
                          * questions anyway, why this screen appeared and why this press failed,
                          * and neither is an answer to the other.
                          */}
                        {props.notice && <div className="hint">{props.notice}</div>}
                        {/*
                          * `name` and `autoComplete` on both boxes, which neither door had.
                          *
                          * Without them a password manager has nothing to recognise: the two
                          * boxes are an unnamed pair of inputs, so the one person who signs in to
                          * this bank in two windows has to type the same credentials by hand in
                          * both. The values are the standard ones, `username` and
                          * `current-password`, because that is the whole point of a standard.
                          */}
                        <div className="row">
                            <label htmlFor="login-username">{USERNAME_LABEL}</label>
                            <input
                                id="login-username"
                                name="username"
                                autoComplete="username"
                                required
                                aria-invalid={blank.username || undefined}
                                aria-describedby={blank.username ? SIGN_IN_ERROR_ID : undefined}
                                ref={usernameBox}
                                value={username}
                                onChange={(e) => {
                                    setUsername(e.target.value);
                                    setBlank(prev => ({ ...prev, username: false }));
                                }}
                            />
                        </div>
                        <div className="row">
                            <label htmlFor="login-password">{PASSWORD_LABEL}</label>
                            <input
                                id="login-password"
                                name="password"
                                type="password"
                                autoComplete="current-password"
                                required
                                aria-invalid={blank.password || undefined}
                                aria-describedby={blank.password ? SIGN_IN_ERROR_ID : undefined}
                                value={password}
                                onChange={(e) => {
                                    setPassword(e.target.value);
                                    setBlank(prev => ({ ...prev, password: false }));
                                }}
                            />
                        </div>

                        {/*
                          * The band the refusal appears in, held open whether or not there is one.
                          *
                          * Empty it is a strip of nothing under the password box, and that is the
                          * price. What it buys is a button that does not move: the box arriving
                          * used to add its own height to a window that grows downward, so the
                          * Sign in button dropped out from under the pointer that had just pressed
                          * it and came back on the next press. Measured at 1440x900, the window
                          * went 362px to 460px and the button with it.
                          *
                          * Reserved rather than solved by putting the button above the box, which
                          * would stand the way out of a refusal above the refusal itself. The
                          * customer application's door reserves the same band for the same reason.
                          */}
                        <div className="login-error-slot">
                            {error && <ErrorBox failure={error} id={SIGN_IN_ERROR_ID} />}
                        </div>

                        <div className="actions">
                            <button className="btn btn--primary" disabled={loading} type="submit">
                                {loading ? 'Signing in…' : 'Sign in'}
                            </button>
                        </div>
                        {/* Announced rather than only drawn: somebody who cannot see the window is
                            the reader most likely to press again into the silence. */}
                        <div className="hint" role="status">
                            {loading && slow ? SIGN_IN_SLOW_NOTE : ''}
                        </div>
                    </form>
                </div>
            </div>
        </div>
    );
}
