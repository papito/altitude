# Scala 3 Code Improvement Guidelines

## Style

1. **Replace imperative mutable accumulators with functional pipelines** — Avoid building results by mutating a `mutable.Map` or `mutable.ListBuffer` inside a `foreach`. Use `map`, `flatMap`, and `toMap`/`toList` instead, and eliminate `mutable` imports.

2. **Eliminate redundant single-use intermediate `val`s in simple methods** — If a method body assigns to a `val` and immediately returns it, remove the `val` and use the expression directly as the method body.

3. **Use `.isDefined` instead of `.nonEmpty` on `Option`** — `.nonEmpty` is semantically for `Iterable`. For `Option`, always use `.isDefined` to express intent clearly.

4. **Remove no-op catch arms** — A `catch` arm of the form `case ex: SomeException => throw ex` adds no value and should be deleted.

5. **Match return types to actual behavior** — If a method always returns a value or throws (never returns `None`/`Left`/empty), don't wrap the return type in `Option`/`Either`. The wrapper type should reflect all real outcomes.

6. **Extract duplicated logic into private helpers** — When two or more methods share the same multi-line expression structure differing only in a terminal value, extract the shared prefix into a private helper method.

7. Use Scala 3 if-expression syntax — Replace if (condition) with if condition then. Parentheses around the condition are no longer needed in Scala 3, and then replaces the opening brace for multi-line branches. For single-line expressions this also eliminates the need for braces entirely.

---

## Formatting

8. **Use Scala 3 indentation syntax consistently** — All class, trait, object, and `enum` bodies should use the colon-based `class Foo:` / `trait Bar:` style throughout the codebase. Don't mix Scala 2 brace-based `{ }` bodies with Scala 3 indentation-based bodies across files.

9. **Omit empty parentheses on no-arg method calls** — Methods defined without parameter lists should be called without parentheses (e.g., `.toLowerCase` not `.toLowerCase()`). Scala 3 enforces this distinction.

10. **Group imports from the same package** — Multiple `import` statements from the same package should be consolidated into a single grouped `import pkg.{A, B}` statement.

---

## Language Features

11. **Omit `new` for class instantiation** — Scala 3 does not require the `new` keyword when constructing class instances, including in `throw` expressions. Prefer `throw SomeException(...)` over `throw new SomeException(...)`.

12. **Migrate Scala 2 `implicit` conversions to `given Conversion`** — The `import scala.language.implicitConversions` flag signals Scala 2-style implicit conversions. In Scala 3, declare conversions using `given Conversion[A, B]` and audit whether the language import is still required.

13. **Prefer `++` over type-specific concatenation operators** — Use the general `++` operator for collection concatenation rather than type-specific alternatives like `:::` (List-only). `++` is idiomatic in Scala 3 and works uniformly across collection types.

