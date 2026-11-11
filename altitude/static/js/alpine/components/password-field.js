/** How long a generated password is: 20 characters of 56, about 116 bits */
const GENERATED_LENGTH = 20

// Letters and digits that no font confuses with one another (no 0/O, 1/l/I)
const ALPHABET = "abcdefghijkmnopqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789"

/**
 * A new password's field with its Generate and Show buttons (`includes/password_field`), on the
 * Users page's Add User form and Set password dialog: Generate writes a random password from the
 * browser's cryptographic source into the field and shows it, so it can be copied and passed on;
 * Show shows or hides what the field holds.
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
            // A rejection sampling of bytes, so every character of the alphabet is as likely
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
            this.shown = true
            this.$refs.input.focus()
        },
    }
}
