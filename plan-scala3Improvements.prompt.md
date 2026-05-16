# Scala 3 Code Improvement Guidelines

## Formatting

8. **Use Scala 3 indentation syntax consistently** — All class, trait, object, and `enum` bodies should use the colon-based `class Foo:` / `trait Bar:` style throughout the codebase. Don't mix Scala 2 brace-based `{ }` bodies with Scala 3 indentation-based bodies across files.

9. **Omit empty parentheses on no-arg method calls** — Methods defined without parameter lists should be called without parentheses (e.g., `.toLowerCase` not `.toLowerCase()`). Scala 3 enforces this distinction.

10. **Group imports from the same package** — Multiple `import` statements from the same package should be consolidated into a single grouped `import pkg.{A, B}` statement.

---

## Language Features

11. **Omit `new` for class instantiation** — Scala 3 does not require the `new` keyword when constructing class instances, including in `throw` expressions. Prefer `throw SomeException(...)` over `throw new SomeException(...)`.

12. **Migrate Scala 2 `implicit` conversions to `given Conversion`** — The `import scala.language.implicitConversions` flag signals Scala 2-style implicit conversions. In Scala 3, declare conversions using `given Conversion[A, B]` and audit whether the language import is still required.

13. **Prefer `++` over type-specific concatenation operators** — Use the general `++` operator for collection concatenation rather than type-specific alternatives like `:::` (List-only). `++` is idiomatic in Scala 3 and works uniformly across collection types.

