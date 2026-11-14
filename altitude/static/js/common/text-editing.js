// The inputs that take no typed text: a key pressed on one is the page's (a slider's arrows are its own, which the
// page's shortcuts leave to it by where focus is)
const UNTYPED_INPUTS =
    ":not([type='range'], [type='checkbox'], [type='radio'], [type='button'], [type='submit'], [type='reset'], [type='color'], [type='file'], [type='image'])"

/**
 * Focus is in a field that takes the keys typed: a page's keyboard shortcuts leave them to it. A slider, a checkbox or
 * a button-like input takes none, so the shortcuts work on one: the Edit panel's sliders keep `e`, `c` and Undo
 */
export function isTextEditingTarget(target) {
    return Boolean(
        target?.closest?.(
            `input${UNTYPED_INPUTS}, textarea, select, [contenteditable]`,
        ),
    )
}
