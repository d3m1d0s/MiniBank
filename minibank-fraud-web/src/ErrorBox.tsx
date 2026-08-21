import type { ApiFailure } from '@shared/apiErrors';

/**
 * A refusal: what happened, the way out where the caller has one to offer, and the reference.
 *
 * The markup is this window's own, .error and a stack of lines, and stays so: what moved here from
 * the customer application is the semantics and the order, which are not skin.
 *
 * `role="alert"` because the box replaces something the reader was waiting for. The queue, the
 * detail pane and the decision block each draw one where their own content would have been, and an
 * analyst who has just pressed a button must not have to go looking for the answer.
 *
 * The title is the second half of the same point. A bordered red block with no name reads as a
 * fault of the screen; a named one reads as an answer to the thing that was asked, and a screen
 * with a better sentence than "Error" for its own failure passes one.
 *
 * Then the sentences, then the way out, then the reference, in that order and not the other one.
 * The shared table sends what happened and, where there is one, the thing to do about it, and the
 * control that does it belongs beside the sentence that asked for it. The reference is last
 * because nobody at this desk reads it: it is there so a photograph of the box is worth something
 * to whoever runs the bank, now that the body of a bad answer is dropped before it reaches a
 * screen.
 *
 * The button is drawn only where a caller passed one, and a caller passes one only for a read. A
 * decision, a take, a release and a sign in each have a press of their own already, and a second
 * control offering to send them again is how a refused verdict becomes two.
 */
export default function ErrorBox({
    failure,
    title = 'Error',
}: {
    failure: ApiFailure;
    title?: string;
}) {
    const { lines, reference, retry, retryLabel } = failure;
    return (
        <div className="error" role="alert">
            <div className="error-title">{title}</div>
            {lines.map(line => <div key={line}>{line}</div>)}
            {retry && (
                <div className="actions">
                    <button type="button" className="btn" onClick={retry}>{retryLabel}</button>
                </div>
            )}
            {reference && <div className="error-reference">{reference}</div>}
        </div>
    );
}
