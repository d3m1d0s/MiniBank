/**
 * The customer's half of the API: the accounts they hold and the one day those accounts share, one
 * payment read back in full, what a payment would cost before it is sent, and who is signed in.
 *
 * Shapes, and ONE call. The fraud desk exists twice, so its calls are shared next door in fraud.ts;
 * the customer screens exist once so far, and each application's own api.ts owns the fetching. What
 * must not exist twice is the description of the wire: the workstation grows these same screens
 * later, and a second declaration of a record is how one platform learns about a field the other
 * has been reading for a month. The same argument as fields.ts, one level down.
 *
 * The exception is {@link fetchMe}, and the test it passes is the one the rule is made of. A call
 * is shared when both platforms make it, and both make this one: `GET /api/me` is not guarded by
 * role, so it answers about whoever is at the keyboard. It was written out twice, identically, and
 * the two copies had already begun to explain themselves in different words.
 *
 * Every field the server always sends is written `| null` rather than optional, for the reason
 * fraud.ts gives: the key is always there, and null is a value with a meaning. Optional would say
 * the server sometimes omits it, which is a different wire and a different bug.
 */

import type { Money } from './money';
import type { LoginResponse } from './http';
import { API_BASE, apiFetch, handle } from './http';

/**
 * The four roles the server has, named once.
 *
 * Taken from the sign-in response rather than written out again, so the set has one declaration in
 * this directory. Two of the four have screens; see NavRole in navigation.ts, which is that subset
 * and says why it is smaller.
 *
 * The wire itself sends a plain string, and a role this list has not heard of still reaches a
 * screen intact: roleLabel prints an unknown value as itself rather than blanking it.
 */
export type UserRole = LoginResponse['role'];

/**
 * Where a settled payment stands with the bank on the other side.
 *
 * `null` is not a third value of this enum and must never be read as one. It is what a payment
 * inside this bank carries, what anything unsettled carries, and what every row written before the
 * column existed carries. The field that answers which side of the bank the money was going is
 * `toIbanInBank`, on this record and on a history row, and it is derived by the server for exactly
 * that reason. The words for these two values are in glossary.ts.
 */
export type DispatchState = 'PENDING' | 'DISPATCHED';

/**
 * One account of the signed-in customer: which one it is, and what is on it.
 *
 * Three fields, and what it no longer carries is the point. A ceiling and a day's running total
 * used to ride on every row, and they are facts about the person rather than about the account:
 * the customer holds ONE day across everything they hold, not one day per account that adds up.
 * Both moved to {@link DailyOutflow}, where there is exactly one of them. Printed per row they
 * offered a reader two allowances, neither of which was theirs, and read literally they said a
 * payment could be halved across two of the reader's own accounts to stay inside both.
 */
export interface AccountSummary {
    id: number;
    iban: string;
    balance: Money;
}

/**
 * The customer's day: how much has already left them, and how much they were allowed.
 *
 * One figure per person. `sentOut` is what has actually SETTLED out of every account this customer
 * holds today, fees excluded, over the bank's own day in Europe/Prague, LESS whatever only moved
 * between two accounts they hold themselves. That subtraction is what makes the number a day's
 * spending rather than a day's traffic: money landing on the payer's own second account has not
 * left them, and counting it would let a ceiling be spent without a crown going anywhere. A
 * payment still waiting for its code has moved nothing and counts nothing, which is the rule the
 * refusal itself applies.
 *
 * `limit` is the hard ceiling the next payment is refused against. Strictly above refuses: a day
 * landing exactly on the limit still settles.
 *
 * WHAT IS DELIBERATELY NOT HERE is the softer tier, the day total above which the bank stops
 * settling at once and asks for a one time code. It was on this record's ancestor and read as a
 * number the customer could steer by, which is the objection to publishing it: it is the height of
 * a fence, and the other two fences the bank keeps are not published either. The question a form
 * actually asks is about the payment in front of it, and that is answered exactly, per payment, by
 * {@link PaymentQuote.authorizationRequired} before it is sent.
 */
export interface DailyOutflow {
    sentOut: Money;
    limit: Money;
}

/**
 * What `GET /api/me/accounts` answers: the accounts, and the one day they share.
 *
 * The day travels with the accounts rather than on a route of its own because the two go stale
 * together. Every screen reloads balances because a payment has just moved money, and that same
 * payment is what moved the day's total; fetched separately they would be read against each other
 * while one of them was older than the other.
 *
 * The list is under a name now instead of being the body. An array had nowhere to put a fact about
 * the customer, which is how the day's ceiling came to be stamped onto every account in the first
 * place.
 */
export interface MyAccountsResponse {
    accounts: AccountSummary[];
    today: DailyOutflow;
}

/**
 * One payment read back in full: `GET /api/transfers/{id}`.
 *
 * It used to be the only route carrying the customer's own reference and the moment the money
 * moved. Both are on a history row now, so a list no longer has to open the payment for them; what
 * is still this route's alone is the onward leg, the tries left and the authorization deadline.
 */
export interface TransferDetails {
    id: number;
    fromIban: string;
    fromBalance: Money;
    toIban: string;
    /**
     * Whether this bank holds the account named above, which is what decides whether the money
     * stayed inside or was owed to the payment network. The same derived answer a history row
     * carries; see the field of this name in fraud.ts for why the client is not handed the halves.
     */
    toIbanInBank: boolean;
    amount: Money;
    /** What the payment was charged once it settled, and the current tariff's price until then. */
    feeAmount: Money;
    status: string;
    createdAt: string;
    /** When the money moved. Null on a payment that has not settled. */
    settledAt: string | null;
    /** See {@link DispatchState}: null is three different situations and not a state. */
    dispatchState: DispatchState | null;
    /** The customer's own reference. Null on a payment created without one. */
    message: string | null;
    /** Why the payment was stopped, or null while it still might go through. */
    declineReason: string | null;
    /**
     * How the payment was authorized, or null where nothing has been recorded.
     *
     * Null and never the empty string. This endpoint used to answer `""` where the fraud desk's
     * copy of the same fact answered null, so a client had no way to tell an absent method from an
     * empty one without knowing which route the record came from.
     */
    authMethod: string | null;
    /**
     * How many one time codes are left, and the deadline for using one. Both null unless the
     * payment is waiting for a code: they answer "how do I finish this", and a payment held for
     * review was reporting three attempts beside a control that will not take one.
     */
    triesLeft: number | null;
    authValidUntil: string | null;
}

/**
 * What a payment would cost, priced by the bank before anything is sent.
 *
 * The fee is quoted and never recomputed on this side. The tariff has a step in it, free below a
 * threshold and charged above, and a client that reimplements the step is a second copy of a rule
 * the bank changes without telling it.
 *
 * `authorizationRequired` is the real rule from the same risk service the payment itself runs, so
 * the answer depends on where the money is going as well as how much: the same amount to a saved
 * payee the bank trusts can need no code where a typed account number does. It reports nothing
 * about a fraud review, deliberately; see glossary.ts, where the sentence for it lives.
 *
 * It prices a payment and does not accept one, so there is no balance check behind it. The daily
 * ceiling is the exception and answers the same refusal the submit would, before the money moves
 * rather than after, which a form asking for a quote has to be ready for.
 */
export interface PaymentQuote {
    amount: Money;
    fee: Money;
    total: Money;
    authorizationRequired: boolean;
}

/** A postal address as the customer record holds one. */
export interface Address {
    street: string;
    city: string;
}

/**
 * Who is signed in: `GET /api/me`.
 *
 * Not guarded by role, which is what makes it worth having in this directory rather than in the
 * customer application. An analyst gets a username and a role and null for the rest, so the
 * workstation can name the person at the desk from the same call, and the customer application can
 * greet somebody by name instead of by the login they typed.
 */
export interface Me {
    username: string;
    role: UserRole;
    /** The customer behind this login, or null for a login that is not a customer's. */
    customerId: number | null;
    name: string | null;
    email: string | null;
    address: Address | null;
}

/**
 * Who is signed in, by name rather than by login.
 *
 * The header band had only the string that was typed into the sign-in box, so a customer whose
 * record says Alice Novakova was greeted as `alice`. The workstation asks the same route about its
 * own analyst: the four customer fields come back null for a login with no customer behind it, so
 * the title bar keeps the login for now, and a window that never asked would go on printing one
 * after the server has something better to give it.
 *
 * The one fetcher in this module, because it is the one call both applications make. See the note
 * at the top for why the rest of the customer's routes stay in each application's own api.ts.
 */
export async function fetchMe(): Promise<Me> {
    const res = await apiFetch(`${API_BASE}/me`);
    return handle<Me>(res);
}
