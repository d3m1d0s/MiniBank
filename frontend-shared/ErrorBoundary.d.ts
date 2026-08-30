/**
 * The types for ErrorBoundary.jsx, written by hand because that file cannot be a .tsx today.
 * The reason, and the one line in each application's tsconfig that ends this arrangement, are
 * written at the top of ErrorBoundary.jsx.
 *
 * Nothing here may name a type from react, for the same reason the implementation cannot import
 * react: this directory has no node_modules and tsc resolves neither. That is what decides the
 * two shapes below.
 *
 * - `children` is `unknown` rather than ReactNode. It is only ever passed, never read by a
 *   caller, so the shells lose nothing and every tree they can build is accepted.
 * - the export is declared as a function although the implementation is a class. A class type
 *   would have to say `extends React.Component`, which this file cannot name, and a class that
 *   does not say it is rejected at the call site: React's JSX asks a class component for
 *   props, context, setState and forceUpdate. A function component is asked for none of them,
 *   and its props ARE still checked, which is the part that matters here. Nothing constructs
 *   this by hand; both shells only ever write it as a tag.
 */

export interface ErrorBoundaryProps {
    /**
     * What to call the application on the panel, in the words its own screens use: "MiniBank",
     * "Fraud workstation". A reader who has both open needs to know which one stopped, and the
     * component cannot know: it is the same component in both.
     */
    appName: string;
    children?: unknown;
}

/* The return type is `any` because it has to be assignable to React's own function component
   type, and that type is not nameable from here. */
// eslint-disable-next-line @typescript-eslint/no-explicit-any
export declare function ErrorBoundary(props: ErrorBoundaryProps): any;

export default ErrorBoundary;
