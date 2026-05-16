# Scala 3 Code Improvement Guidelines

## Language Features

11. **Omit `new` for class instantiation** — Scala 3 does not require the `new` keyword when constructing class instances, including in `throw` expressions. Prefer `throw SomeException(...)` over `throw new SomeException(...)`.

12. **Migrate Scala 2 `implicit` conversions to `given Conversion`** — The `import scala.language.implicitConversions` flag signals Scala 2-style implicit conversions. In Scala 3, declare conversions using `given Conversion[A, B]` and audit whether the language import is still required.

13. **Prefer `++` over type-specific concatenation operators** — Use the general `++` operator for collection concatenation rather than type-specific alternatives like `:::` (List-only). `++` is idiomatic in Scala 3 and works uniformly across collection types.

