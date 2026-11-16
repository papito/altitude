// Inputs that take no typed text (a slider keeps its arrows by other checks of where focus is)
const UNTYPED_INPUTS =
    ":not([type='range'], [type='checkbox'], [type='radio'], [type='button'], [type='submit'], [type='reset'], [type='color'], [type='file'], [type='image'])"

/**
 * Whether focus is in a field that takes typed keys, which the page's shortcuts leave alone. A slider, checkbox or
 * button-like input takes none, so `e`, `c` and Undo keep working on the Edit panel's sliders
 */
export function isTextEditingTarget(target) {
    return Boolean(
        target?.closest?.(
            `input${UNTYPED_INPUTS}, textarea, select, [contenteditable]`,
        ),
    )
}
