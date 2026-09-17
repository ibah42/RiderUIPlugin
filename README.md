# Allman View

A plugin for Rider (and any other IntelliJ IDE) that shows your code in Allman style
**visually**, without changing a single byte in the file.

```
// what is in the file            // what you see in the editor
if (x) {                         if (x)
    Foo();                       {
} else {                             Foo();
    Bar();                       }
}                                else
                                 {
                                     Bar();
                                 }
```

Single statements are moved down as well:

```
// what is in the file                    // what you see in the editor
if (body == null) return;                 if (body == null) return;
                                              return;

foreach (var x in items) Sum += x;        foreach (var x in items) Sum += x;
                                              Sum += x;

if (verbose) { Log(id); }                 if (verbose) { Log(id); }
                                          {
                                              Log(id);
                                          }
```

The grey text is the real one; the phantom is drawn with the real highlighting, the same one the
original has. `if`, `else`, `for`, `foreach`, `while`, `using`, `lock` and `fixed` are all split.
Each of those can be turned off separately in the settings.

Constructs where a single line is the right shape are deliberately left alone:
`using System.Text;` (a directive, not a block), `do { } while (x);`,
`public int X { get; set; }`, `void M() { }`, `while (reader.Read());`, and initializers such as
`new Point { X = 1 }`.

## Accented braces of types and functions

Braces that belong to a type declaration (`class`, `struct`, `interface`, `enum`, `record`) or to
a function declaration are painted more prominently than the rest: the base colour is taken
**from the name itself** in the document, pushed away from the background, and set in bold.

The colour is sampled from the name rather than read from scheme keys, so the accent matches
whatever ReSharper and the current colour scheme actually do. The `CLASS_NAME` and
`FUNCTION_DECLARATION` keys remain as a fallback for when the backend has not answered yet.

The shift is computed **relative to the background, not always towards black**: on a light scheme
the colour moves towards black, on a dark one towards white. The settings hold two separate
percentages. Darkening on Darcula would be pointless — the brace would sink into the background.

Prominence comes from a shadow: a copy of the glyph with an offset, drawn **before** the character
itself. `CustomHighlighterRenderer` paints over the background but before the text, so the copy
lands underneath the glyph and adds depth without dirtying the edges. The shadow colour runs from
the background towards grey — grey is darker than a light background and lighter than a dark one,
so one setting works in both themes. The X and Y offsets are separate: `X=1, Y=0` gives faux bold
instead of depth.

The shadow is drawn under the phantom brace too — otherwise it would be invisible in K&R code,
where what you see on screen is the phantom, not the real brace.

When a block is longer than the threshold, a label appears after the closing brace —
`class IosHttpClient`, `fun HandleNativeResult`. It is an inline inlay right after the `}`, in
italics, in the brace colour pushed towards grey. The area to the right of a closing brace is
usually empty, so nothing shifts.

Lambdas count as functions. They have no name of their own, so the colour and the label come from
the nearest meaningful identifier: first the unclosed call (`items.Select(y => {` → `Select`), and
only when there is no call, the assignment target (`Action handler = () => {` → `handler`).

Ownership is worked out without a parser: the scanner keeps a stack of open `{`, and a closing
brace recognizes its block by popping the stack. The kind of block is read from the header —
either a type keyword, or "ends with `)` and the first word is not a control keyword". The header
is looked for on the current line, and when the `{` sits on its own line (code that is already
Allman), on the previous one.

What deliberately passes by: `namespace`, auto-properties `{ get; set; }` and properties with a
body, initializers `new Foo() { ... }`, lambdas `() => { }`, and every control construct.
Constraints are cut off before classification, otherwise
`void Bind<T>(T v) where T : class {` would pass for a type.

## Two independent switches

The settings page has one master checkbox and two mechanics under it, each with its own switch:

- **Move braces down** — the phantom lines plus the dimming of the text they stand in for.
- **Accent braces** — the colour, the shadow and the end-of-block label.

Either can be turned off on its own. With the moves off, the file keeps its K&R shape on screen
and the braces of types and functions are still accented in place; with the accent off, the moves
work with the ordinary editor colours. The master checkbox at the top turns both off, and then the
editor shows the file exactly as it is on disk.

## How it works

The original text **stays where it is** — it is simply dimmed, and the phantom is drawn next to
it. Two platform primitives, both purely visual:

1. **RangeHighlighter** on `editor.markupModel`, layer `HighlighterLayer.LAST + 100`,
   `TextAttributes` carrying only a `foregroundColor`. It paints the real `{` (and
   `else`/`catch`/`finally` in full Allman) in the muted colour of parameter hints.
2. **Block inlay** — `InlayModel.addBlockElement(lineEnd, relatesToPrecedingText = true, showAbove = false, ...)`.
   It draws the phantom line under its owner line, at that line's indent. The caret does not walk
   into the inlay and skips to the real text, just like with parameter hints.

The phantom text is always a contiguous slice of the document, and `PhantomLine` keeps its
`sourceOffset`. That means the highlighting can be asked of the editor itself: phantom character
`i` lives at `sourceOffset + i` in the document. Colours are collected from two sources — the
lexer (`EditorEx.getHighlighter()`) and the document markup (`DocumentMarkupModel`) — because in
Rider the C# highlighting arrives from the ReSharper backend as markup, and the lexer alone is
not enough.

The phantom indent is expressed in **levels**, not spaces: only the editor knows how wide a level
is, and `Graphics.drawString` does not expand a tab inside a string — the indent would simply
disappear.

Folding is deliberately not used: it clashed with ReSharper's fold regions on method bodies
(`createFoldRegion` returned `null` and the move silently did not happen) and it pushed the caret
out of the collapsed region while typing.

The document is never touched. Copying, search, the compiler, git and ReSharper all see the real
K&R text, so diffs stay clean.

For a multi-line construct the indent is taken not from the line holding the brace, but from the
line the construct started on (the scanner tracks the depth of `(` and `[`):

```csharp
private static void HandleNativeResult(
    int requestId,
    bool isConnectionError) {   ← the brace physically sits here
{                               ← the phantom lands under `private`, not under `bool`
```

## Languages

The dialect affects **only** the parsing of string literals and comments — blocks themselves are
the same everywhere. So there are just a handful of special rules:

| Dialect | Extensions | What it handles |
|---|---|---|
| `CSHARP` | `cs csx` | `@"verbatim"`, `"""raw"""`, `$"{interp}"` with nested quotes |
| `CPP` | `c cpp h hpp m mm metal hlsl glsl shader compute cginc usf` | `R"delim(raw)delim"`, `1'000'000` |
| `JVM` | `java kt kts scala groovy gradle swift dart` | `"""` text blocks |
| `WEB` | `js jsx ts tsx go php` | `` `templates ${...}` `` |
| `GENERIC` | everything else (`rs json css scss sql proto zig`…) | `"..."`, `'...'`, `/* */`, `//` |

The `JVM` dialect is not decoration: Java, Kotlin, Scala and Swift all have `"""` blocks, and
without parsing them the scanner runs off inside a multi-line string. A test catches this
explicitly — the same Java file with a text block yields 1 hit under `JVM` and 2 under `GENERIC`.

The extension list is edited in Settings → Editor → Allman View. The same page has an "All text
files" checkbox, which ignores the list and runs the plugin everywhere.

## Layout

| File | What it does |
|---|---|
| `scan/BraceScanner.kt` | Lexer plus the search for places to move. **Zero IntelliJ dependencies**, covered by tests. |
| `AllmanController.kt` | One per editor: rebuilds the highlighting and the inlays on a timer. |
| `AllmanService.kt` | Subscribes to editor creation, `refreshAll()`. |
| `PhantomLineRenderer.kt` | Draws the phantom lines. |
| `EditorColorSampler.kt` | Pulls the editor's real highlighting for a slice of the document. |
| `BraceAccentStyle.kt` | Works out the brace, shadow and label colours from the name colour and the scheme background. |
| `BraceShadowRenderer.kt` | Draws the shadow under a real brace, before the glyph itself. |
| `BlockLabelRenderer.kt` | The end-of-block label after a `}` of a long block. |
| `AllmanSettings.kt` / `AllmanConfigurable.kt` | Settings plus the panel in Settings → Editor → Allman View. |

## Building

The wrapper is in the repository, so Gradle does not need to be installed separately.

```
gradlew.bat test        # scanner tests
gradlew.bat runIde      # launches a sandbox IDE with the plugin
gradlew.bat buildPlugin # build/distributions/allman-view-1.1.0.zip
```

The versions are pinned the way they are because:

- **Gradle 9.7.0** — IntelliJ Platform Gradle Plugin 2.x needs at least 9.0, and on JDK 25 Gradle
  can only start from 9.1. On 8.x the build fails with the cryptic
  `What went wrong: 25.0.1`.
- **Kotlin 2.4.20** — fully supports the Gradle 7.6.3–9.7.0 range.
- **jvmToolchain(21)** — the 2026.x platform runs on JBR 21, so the bytecode has to be 21
  regardless of which JDK runs Gradle itself. If JDK 21 is not installed,
  `foojay-resolver-convention` in `settings.gradle.kts` downloads it automatically.

From IDEA: File → Open → the project folder. The Gradle JVM can be any 17+.

The target IDE is set in `gradle.properties`:

```properties
platformType=RD          # RD = Rider, IC = IntelliJ IDEA Community (a much smaller download)
platformVersion=2026.2.2
```

Every API in use is a platform API, so you can build and check against `IC` and install the
finished zip into Rider.

## Known limitations

- The real `{` stays visible, just grey. This is deliberate: it shows where the text physically
  is, and it breaks neither the caret nor anyone else's folding. Turn it off with the "Dim the
  original text" checkbox (only the phantom then remains, on top of the ordinary brace).
- A phantom line gets no number in the gutter and takes no part in indent guides — the vertical
  indent lines will break.
- The caret cannot be placed in a phantom line: it is not text.
- The scanner recomputes the whole document on a timer (200 ms after an edit). On files of tens of
  thousands of lines this is worth measuring and, if needed, making incremental (caching the lexer
  state at the start of each line).
- The `} while (x);` of a `do` loop is deliberately not split.
