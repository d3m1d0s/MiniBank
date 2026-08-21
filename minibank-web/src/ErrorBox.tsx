// src/ErrorBox.tsx

import type { ApiFailure } from '@shared/apiErrors';

/**
 * One failed call, drawn the way this application draws a failure everywhere.
 *
 * There were six copies of this markup across four screens, each of them a title, a list and
 * nothing else, and that is how the reference line and the retry control would have been added to
 * some of them and not the others. The shape is decided once here; what each screen still decides
 * is whether the call it made can be asked again, which is the one thing this component must not
 * guess. See ApiFailure: a retry belongs to a read and never to a payment, an authorization, a
 * cancellation or a decision, where a second press is a second attempt at the thing itself.
 *
 * Three parts, and the second and third appear only when there is something to put in them:
 * - the sentences, which is what the reader acts on;
 * - the way out, when the caller passed one;
 * - the reference, in small muted type, so that a screenshot still names which answer this was.
 *
 * `role="alert"` because the box replaces something the reader was waiting for, and because a
 * customer whose payment was refused should not have to go looking for the reason.
 *
 * The title defaults to the one word the boxes it replaces all carried. A screen that has a better
 * sentence than "Error" for its own failure passes one.
 */
export default function ErrorBox({
    failure,
    title = 'Error',
}: {
    failure: ApiFailure;
    title?: string;
}) {
    return (
        <div className="summary summary--danger" role="alert">
            <div className="summary-title">{title}</div>
            <ul>
                {failure.lines.map((line) => (
                    <li key={line}>{line}</li>
                ))}
            </ul>
            {failure.retry && (
                <div className="summary-actions">
                    <button type="button" className="btn-quiet" onClick={failure.retry}>
                        {failure.retryLabel}
                    </button>
                </div>
            )}
            {failure.reference && <p className="summary-reference">{failure.reference}</p>}
        </div>
    );
}
