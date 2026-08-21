/**
 * The customer's half of the API: the accounts they hold, one payment read back in full, what a
 * payment would cost before it is sent, and who is signed in.
 *
 * Shapes only, and that is deliberate. The fraud desk exists twice, so its calls are shared next
 * door in fraud.ts; the customer screens exist once so far, and each application's own api.ts owns
 * the fetching. What must not exist twice is the description of the wire: the workstation grows
 * these same screens later, and a second declaration of a record is how one platform learns about
 * a field the other has been reading for a month. The same argument as fields.ts, one level down.
 *
 * Every field the server always sends is written `| null` rather than optional, for the reason
 * fraud.ts gives: the key is always there, and null is a value with a meaning. Optional would say
 * the server sometimes omits it, which is a different wire and a different bug.
 */

import type { Money } from './money';
import type { LoginResponse } from './http';

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
 * One account of the signed-in customer, with the three numbers that decide what may leave it.
 *
 * The limits used to be invisible, and the two-tier behaviour they produce therefore looked
 * arbitrary from outside: the same amount settled at once in the morning and asked for a one time
 * code in the afternoon, with nothing on any screen saying that the difference was the day's
 * running total.
 */
export interface AccountSummary {
    id: number;
    iban: string;
    balance: Money;
    /** The hard ceiling on what may leave this account in one banking day, fees excluded. */
    dailyLimit: Money;
    /**
     * The day total above which this account asks for a one time code, or null when the account
     * has no tier of its own and the bank-wide one applies.
     *
     * Null is not resolved to that bank-wide number, here or on the server, and a screen must not
     * resolve it either: printed on a row it would state as a property of this account a figure
     * that belongs to the bank and moves when the bank moves it.
     */
    softDailyThreshold: Money | null;
    /**
     * What has actually SETTLED out of this account today, fees excluded, over the bank's own
     * day in Europe/Prague.
     *
     * It is what both limits above are measured against, so without it they are two thresholds and
     * no reading. A payment waiting for its code has moved nothing and counts nothing, which is
     * the same rule the refusal itself applies.
     */
    spentToday: Money;
}

/**
 * One payment read back in full: `GET /api/transfers/{id}`.
 *
 * This is the only route that carries the customer's own reference and the moment the money moved.
 * Neither is on a history row, so a list that wants to show them opens the payment.
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
