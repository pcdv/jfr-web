// From web-lib (https://github.com/pcdv/web-lib), src/dialog.ts: changed there, then synced here.
/**
 * Asks for a line of text.
 *
 * @param value the text proposed
 * @returns the text entered; null if cancelled
 */
export declare function ask(question: string, value?: string): Promise<string | null>;
/** Shows a message until dismissed. */
export declare function tell(message: string): Promise<void>;
