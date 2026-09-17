# Instructions for AI agents

Ivan's rules. Read them before writing code, not after the review.

The "Code style" section is not tied to this project — copy it into other repositories as is.

---

## Code style

### 1. Do not abbreviate names

Whole words. Always.

```kotlin
// wrong
private var i = 0
private var n = text.length
private var q = 0
val fm = graphics.fontMetrics
fun forExtension(ext: String)

// right
private var position = 0
private var textLength = text.length
private var quoteRun = 0
val fontMetrics = graphics.fontMetrics
fun forExtension(extension: String)
```

This is strictest for class members: fields, properties, constants. They are read far away from
their declaration, and `n` means nothing there.

A short name is acceptable in exactly one case — a genuine loop counter (`index`, `count`).
Even then a meaningful name is better: `lineIndex`, `columnCount`.

Function parameters are whole words too, including the parameters of an `override`. Renaming a
parameter inherited from the base class is allowed and encouraged: `g: Graphics` → `graphics`,
`e: AnActionEvent` → `event`.

### 2. No single-line bodies

The body of an `if`, `else`, `for`, `while` or `when` always goes on its own line and always in
braces. Even when it is a single `return`.

```kotlin
// wrong
if (editor.isDisposed) return
if (bracketDepth > 0) bracketDepth--
for (inlay in inlays) Disposer.dispose(inlay)

// right
if (editor.isDisposed) {
    return
}
if (bracketDepth > 0) {
    bracketDepth--
}
for (inlay in inlays) {
    Disposer.dispose(inlay)
}
```

The same applies to `if` used as an expression. A one-line ternary saves nothing but readability:

```kotlin
// wrong
position += if (hasNext) 2 else 1
val indentStart = if (isContinuation) statementLineStartOffset else lineStartOffset
presentation.text = if (isEnabled) "on" else "off"

// right
if (hasNext) {
    position += 2
} else {
    position++
}

val indentStart: Int
if (isContinuation) {
    indentStart = statementLineStartOffset
} else {
    indentStart = lineStartOffset
}
```

Do not save vertical space. Screen space is cheaper than reading time.

Exceptions where a single line is fine: `?:` for an early exit, `?.let { }`, expression-body
functions with no branching (`fun columnWidth(): Int = ...`), and single-line `when` branches
that call exactly one function with no conditions.

### 3. `when` takes a subject

If every branch compares the same value, that value goes into the header.

```kotlin
// wrong
when {
    current == '\\' -> advanceOverEscape()
    current == '"' -> closeStringLiteral()
    dollars > 0 && current == '{' -> handleInterpolationBrace()
    else -> position++
}

// right
when (current) {
    '\\' -> {
        advanceOverEscape()
    }
    '"' -> {
        closeStringLiteral()
    }
    '{' -> {
        stepInterpolationBraceOrSkip()   // the condition moved inside
    }
    else -> {
        position++
    }
}
```

A compound condition is not a reason to fall back to `when {}` — move it inside the branch or
into a separate function with a descriptive name. Do not use the guard syntax
(`'{' if dollars > 0 ->`): it only arrived in Kotlin 2.1, and pinning the language version for
the sake of one line is not worth it.

### 4. Braces are always written out

Even where the language allows omitting them. The physical position of the opening brace follows
the language convention (end of line in Kotlin): Ivan reads code through Allman View, which shows
Allman visually, so the file itself does not need to change.

### 5. Magic numbers become named constants

```kotlin
// wrong
HighlighterLayer.LAST + 100
ColorUtil.mix(foreground, background, 0.55)

// right
HighlighterLayer.LAST + DIM_LAYER_OFFSET
ColorUtil.mix(foreground, background, DIM_BALANCE)
```

### 6. Comments explain "why", not "what"

What the code does is visible in the code. A comment carries what is not visible: why this path
was chosen, what breaks with the obvious alternative, where the trap is.

```kotlin
// useless
// increase the position by two
position += 2

// useful
// Do not step over the line break, or the line markup is lost.
```

Comments and UI text are written in English.

---

## Working on a task

### Plan first, then code

For a non-trivial task, start with the analysis: which APIs, which risks, what to check first,
what the fallback is. The plan is stated before the first line is written.

List the risks honestly, marking which one is the main one and how to check it quickly.

### Verify, do not claim

Never write "done" about something that was never run. If something cannot be checked, say so
plainly and give the reason.

The order is:

1. Logic that can be detached from the framework gets detached and covered by tests. That is how
   `scan/BraceScanner.kt` is built in this project: zero IntelliJ dependencies, so it runs
   anywhere.
2. Run the tests against the code that is actually on disk, not against your own copy.
3. When a full build is unavailable, at least compile and sort the errors by kind, separating
   "missing jars" from the real ones.
4. Cover edge cases right away: string literals, comments, escaping, multi-line constructs,
   unbalanced input.

### Do not guess at APIs

Look up library signatures and behaviour in the documentation or the sources instead of recalling
them. Behaviour such as "is the region created already collapsed?" is exactly what gets recalled
wrong.

### Admit mistakes directly

If an earlier piece of advice turned out to be wrong, say so in the first sentence and explain
why. Do not blur it or bury it.

---

## About this project

The plugin shows code in Allman style without changing the file. How it works is in `README.md`.

Things that are easy to break:

- **Do not use folding.** It was tried and it clashes with ReSharper's fold regions on method
  bodies: `createFoldRegion` returns `null` and the move silently does not happen. It also pushes
  the caret out while typing. The original text now stays in place and is dimmed with a
  `RangeHighlighter`.
- **Measure indents in columns** with `EditorUtil.getSpaceWidth`, not by measuring a string with
  the font. Otherwise tabs and spaces align differently.
- **The scanner must not depend on IntelliJ.** Everything under `scan/` is a pure function of the
  text.
- **A dialect is only about string literals.** When adding a language, check whether it has
  `"""` blocks, raw strings or interpolation. Without that the scanner runs off inside a
  multi-line string.

### Versions

Change these together, they are linked:

| | version | why |
|---|---|---|
| Gradle | 9.7.0 | the IJ plugin 2.x needs 9.0+, and running on JDK 25 needs 9.1+ |
| Kotlin | 2.4.20 | full support for Gradle 7.6.3–9.7.0 |
| IntelliJ Platform Gradle Plugin | 2.19.0 | |
| toolchain | JDK 21 | the 2026.x platform runs on JBR 21 |

### What not to commit

`.intellijPlatform/` — the cache with the unpacked IDE and the sandbox, about a gigabyte. Also
`build/`, `.gradle/`, `.kotlin/` and built zips. The wrapper (`gradle-wrapper.jar`), on the other
hand, does belong in the repository.
