/** 20 characters of a 57-character alphabet: about 117 bits */
const GENERATED_LENGTH = 20

// Letters and digits that no font confuses with one another (no 0/O, 1/l/I)
const ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/**
 * A new password's field with Generate and Show, plus an optional confirmation field
 * (`x-ref="repeat"`) that both buttons also act on. Generate shows the password, so it can be copied
 * and passed on.
 */
export function passwordField() {
    return {
        shown: false,

        inputType() {
            return this.shown ? "text" : "password"
        },

        showLabel() {
            return this.shown ? "Hide" : "Show"
        },

        toggleShown() {
            this.shown = !this.shown
        },

        generate() {
            // Rejection sampling, so every character is equally likely
            const limit = 256 - (256 % ALPHABET.length)
            let password = ""
            while (password.length < GENERATED_LENGTH) {
                for (const byte of crypto.getRandomValues(
                    new Uint8Array(GENERATED_LENGTH),
                )) {
                    if (byte < limit && password.length < GENERATED_LENGTH) {
                        password += ALPHABET[byte % ALPHABET.length]
                    }
                }
            }
            this.$refs.input.value = password
            if (this.$refs.repeat) this.$refs.repeat.value = password
            this.shown = true
            this.$refs.input.focus()
        },
    }
}
