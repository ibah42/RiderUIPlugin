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
   "missing jars" from the real ones. See "Verifying without a full build" below for the exact
   recipe used in a sandboxed session with no Gradle/Maven access.
4. Cover edge cases right away: string literals, comments, escaping, multi-line constructs,
   unbalanced input.

### Do not guess at APIs

Look up library signatures and behaviour in the documentation or the sources instead of recalling
them. Behaviour such as "is the region created already collapsed?" is exactly what gets recalled
wrong.

### Admit mistakes directly

If an earlier piece of advice turned out to be wrong, say so in the first sentence and explain
why. Do not blur it or bury it.

### Keep a version log

Every time `version` in `build.gradle.kts` is bumped, add an entry to `CHANGELOG.md` in the same
turn -- newest version at the top, written for whoever reads it later (Ivan, or another agent
picking this project back up), not just "bumped version". Say what changed and, where it matters,
why -- a fix names the symptom it removes, a feature names what it now does. A version bump
without a changelog entry, or a changelog entry without a version bump, is an incomplete turn:
they are the same action and always happen together.

### Verifying without a full build

Gradle needs network access it may not have in a sandboxed session (Maven Central, the Gradle
Plugin Portal and `services.gradle.org` have all been seen blocked by an agent proxy). Do not
silently skip verification when that happens -- say so, then fall back to this:

1. Copy `scan/*.kt` (the whole package -- it is a closed, IntelliJ-free unit) and the real
   `scan/BraceScannerTest.kt` into a scratch folder outside the repo.
2. Get a standalone `kotlinc` if one is not already on the machine. `github.com` and
   `objects.githubusercontent.com` are reachable even when the usual dependency hosts are not --
   download `kotlin-compiler-<version>.zip` straight from JetBrains' GitHub releases.
3. JUnit4 itself is a Maven dependency and may be unreachable too. `org.junit.Test` is just an
   annotation and `org.junit.Assert.assertEquals`/`assertTrue` are two static methods -- write
   minimal stand-ins for both, compile the real, unmodified test file against them, and run its
   `@Test` methods with a short reflection-based runner (find the annotated methods, invoke each
   on a fresh instance, count passes and failures). This exercises the actual file on disk, not a
   rewritten copy of it.
4. For anything the tests do not already cover (a new bug report, a specific reported file), also
   write a small throwaway `main()` that runs `BraceScanner` on the exact reported text and prints
   every `BraceAccent`, then compare that by hand against the screenshot or the report.

This verifies `scan/` for real. Everything outside it -- `AllmanController.kt`,
`AllmanSettings.kt`, `AllmanConfigurable.kt`, `BraceAccentStyle.kt`, the renderers -- depends on
the IntelliJ Platform and cannot be compiled this way. Review those by hand instead, and say
plainly that they were not compiler-checked rather than implying they were.

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
- **A container's type/namespace children can be numbered `[1]`, `[2]`, ...**
  (`AllmanSettings.Config.siblingNumberingEnabled`). The scanner computes this itself, in
  `BraceScanner`: `OpenBlock.parent` / `OpenBlock.siblingOrdinal` and `finalizeSiblingOrdinals()`,
  deferred to the very end of `scan()` because a container's final child count -- needed to know
  whether it even has "two or more" -- is not known until the container itself, or the file, has
  been fully scanned. Reading `siblingOrdinal` off a `BraceAccent` produced mid-scan, rather than
  from the finished `ScanResult`, would see 0 for every block. Only `BlockKind.TYPE`/`NAMESPACE`
  ever count towards the threshold or get numbered; a function or a lambda never does.
- **A line starting with a bare `:` continues the declaration above it**, not a new statement --
  a constructor's `: base(...)`/`: this(...)`, or a wrapped base-type list. `BraceScanner`
  tracks two separate things that both go stale on such a line unless it is recognised
  (`isColonContinuationLine`, alongside the older `isWhereConstraintLine` for a generic method's
  `where` clause): `classifyBlock`'s header text (`previousCodeStart`/`previousCodeEnd`, fixed by
  extending the end instead of resetting the start), and the phantom brace's indent
  (`statementLineStartOffset`/`statementLineNumber`, fixed by restoring them from
  `statementStartBeforeContinuation`/`statementNumberBeforeContinuation` before `readIndent` can
  see the wrong, deeper value). Missing either one showed up as a constructor with a base-call
  initializer losing its own colour and name, or its hanging brace landing one indent level too
  deep, under the initializer clause instead of the declaration.
- **A clause can run past its own first line, and only the first line starts with the `:`.**
  `public class Foo\n    : IBar,\n      IBaz\n{` has a second continuation line beginning with
  an ordinary identifier, indistinguishable from a new statement except that the line above it
  ended mid-thought. `followsUnfinishedDeclaration` is that test -- the previous code line ended
  with `,` or a non-`::` `:` -- and it is deliberately read **only** by the header bookkeeping,
  never by `readIndent`. A trailing comma is also how every element of a `{ }` initializer list
  ends, and those must keep taking their indent from their own line. Before this, a class with
  two interfaces spread over three lines was not recognised as a class at all: no colour, no
  label, no place in the nesting or sibling bookkeeping.
- **`BlockKind.FUNCTION` covers more than `fun Name` now.** `BlockClassifier` also recognises a
  constructor (`ctor`), a static constructor (`static ctor`), a destructor (`dtor`), a property's
  own declaration (`prop`) and its accessors (`get`/`set`/`init`) -- all under the same kind and
  the same `accentFunctions` toggle, distinguished only by `OpenBlock.keyword`. An accessor has
  no name of its own (`nameOffset == -1`, like a namespace), so `BraceAccentStyle.baseColor`
  samples its own keyword instead of a name for both. A constructor is told apart from an
  ordinary method by `scanModifiers`: nothing but modifiers between the header's start and the
  name means no return type precedes it, which is what a constructor looks like and an ordinary
  method never does. An operator or conversion (`op`) is there too, and it is the one whose
  "name" is not an identifier -- `+`, `==`, `int` -- which is why `spannedBlock` exists beside
  `namedBlock`. It shares the **methods** switch rather than owning one: an operator is a method
  in every respect but its label, and `fun +` would have read as a method called `+`.
- **The parameter list is found by what follows it, not by its position.**
  `HeaderReader.parameterListStart` takes the first top-level `(...)` group the declaration ends
  with, allowing a `: base(...)` initializer or a `where` clause after it. The first `(` is wrong
  for a tuple return type (`public (int, T1 result1) GetResult(short token)` would be named
  `public`), and the last one is wrong for a constructor (it would be named after its base call).
- **A keyword search must stop at the parameter list.** `record` is contextual in C#, so
  `void Save(Record record)` is an ordinary method with an ordinarily named parameter --
  searching the whole header for a type keyword made it a type declaration called `Record`, and
  did the same to `if (record != null) {`. `classifyTypeHeader` and the guard in
  `classifyFunctionHeader` both search only `header.substring(0, parameterListStart)`: a type's
  own keyword always stands in front of any parameter list it has (a positional record's, a
  primary constructor's), and one found inside a list is somebody's variable.
- **A kind's switch being off must mean invisible, never relabelled.** Two fallbacks can swallow
  a block whose own kind is switched off: the property fallback (guarded since 1.7) and the
  function one, which a plain `class Foo` never reaches but a positional record or a primary
  constructor does, because both end in a parameter list. Adding a new fallback means adding the
  same guard, and a test per switch -- the permutation test in `BraceScannerTest` walks all 256
  combinations for exactly this reason.
- **Real files are tested too, from `src/test/resources/samples`.** `SampleFilesTest` runs two
  different things over them. The **invariants** need no expected output and pick up any file
  dropped into the folder, so they cannot be satisfied by regenerating anything. The **golden
  file** (`<name>.expected.txt`, one line per block) catches what nobody thought to assert.
  Regenerating a golden is a change to the plugin's behaviour and belongs in `CHANGELOG.md`; a
  diff the changelog does not explain is the bug the golden was put there to catch. Read a
  golden before committing it -- an unread golden is a rubber stamp. See that folder's README.
- **The marker decisions are pure, and live in `scan/LabelPolicy.kt`.** Which closing brace gets
  a name, which reports its span, which repeats its `[N]`, what the span text says -- none of it
  needs an `Editor`, so none of it lives in `BraceAccentStyle` any more. That class now looks up
  the settings and owns the colours, which genuinely do need the editor, and asks `LabelPolicy`
  for the rest. Put a new rule there, with a test, rather than back in the style class: the whole
  point is that "why did this block not print a span" is answerable without opening the IDE.

### Performance: two refresh timers, not one

`AllmanController` redraws on two independent `Alarm`s, not one, because the two mechanics cost
very different amounts to rebuild:

- **Move** (`scheduleMove` / `refreshMove`, `MOVE_REFRESH_DELAY_MS`): the phantom lines and the
  dimming of the real braces they stand in for. Short delay -- this is what keeps the file
  reading as valid Allman style at all, so it has to stay responsive while typing.
- **Accent** (`scheduleAccent` / `refreshAccent`, `ACCENT_REFRESH_DELAY_MS`): brace colour,
  shadow, the end-of-block label, the `nest` marker and the sibling-ordinal `[N]` marker. Longer
  delay on purpose -- it is pure decoration on top of what Move already drew, it touches more
  highlighters and inlays per accent than Move does, and it is the one nobody notices lagging a
  few hundred milliseconds behind, unlike Move.

Each half keeps its own highlighter/inlay lists (`moveHighlighters`/`moveInlays` vs.
`accentHighlighters`/`accentInlays`) and its own `clear*()`. That split is deliberate, not
incidental: if Accent shared its list with Move, every short Move-timer tick would tear down and
rebuild Accent's decorations too, defeating the point of giving it a longer timer. When adding a
new kind of decoration, decide which half it belongs to -- does it move or dim a brace, or does
it just decorate one that is already placed? -- and route it through that half's list. Do not
introduce a third shared list "for convenience".

`schedule(delayMs)`, the public entry point `AllmanService` calls on a settings change or a newly
opened editor, still refreshes both halves at the same delay, so nothing looks stale right after
an explicit trigger; only the document-changed listener staggers them.

If a file is ever large enough that even the debounced full-file rebuild is felt, the next step
is viewport-based painting -- a `VisibleAreaListener`, only materialising highlighters/inlays for
the visible line range plus a margin, extending it as the user scrolls -- rather than lowering
`MAX_FILE_CHARS`. That is a bigger change than the two-timer split and should not be built
speculatively; nobody has needed it yet.

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
