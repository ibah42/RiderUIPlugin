# Changelog

One entry per version bump, newest first. See CLAUDE.md, "Keep a version log", for the rule.

## 1.12.0

- Swift and Objective-C are now properly supported, and Rust along with them.
- Fixed: in a Rust file, `impl` and `fn` blocks disappeared outright. `.rs` fell back to the
  generic dialect, where an apostrophe opens a character literal -- so in `fn longest<'a>(x: &'a
  str)` the lifetime swallowed the rest of the declaration and the braces with it. Rust is now a
  dialect of its own: a lifetime opens nothing, while `'x'` and `'\n'` still do, told apart by
  where the closing apostrophe falls rather than by what is between them.
- Swift is a dialect of its own too, instead of borrowing the JVM one: `#"raw"#` strings on top
  of the `"""` multi-line ones it already had, and no character literal at all, since an
  apostrophe is not a Swift token and a stray one must not swallow the line behind it.
- Swift and Rust declarations are recognised by their own rules. Both lead with the kind of the
  thing -- `func`, `struct`, `impl`, `mod` -- where the C family leads with the return type, so
  they get their own classifier rather than more special cases inside the one built for C#.
  Before this, `protocol`, `trait` and `mod` were all labelled `prop`, and `func`, `extension`
  and `deinit` were not found at all.
- The label stays the concept rather than the source word: Swift's `init` prints `ctor` and
  `deinit` prints `dtor`, exactly as a C# constructor and destructor do.
- Swift's `class` is handled where it is ambiguous: a modifier in `class func reset()`, the
  declaration itself in `final class Client`. Only the first has another declaring word behind
  it, which is what tells them apart. Attributes, including `@available(iOS 15, *)`, are stepped
  over with the modifiers.
- Objective-C keeps the C++ dialect, whose literals were already right for it, and gains the two
  shapes C++ has none of: the `- (void)doThing:(int)x` method header and the `^{ }` block
  literal. A block has no name of its own, so it borrows the argument it is passed as --
  `animations:^{` reads as `animations`, the same way a lambda borrows its call elsewhere.
  Neither shape is valid C or C++, so the files sharing that dialect are untouched.
- Fixed: a closure held in a property took its name from the last word of the header, which is
  the return type. `let handler: () -> Void = { ... }` read as `Void`; it now reads as
  `handler`, the way a lambda assigned to a C# field already took the field's name.
- `Flavor.CPP` is now `Flavor.C_FAMILY`. The dialect always covered C, C++, Objective-C,
  Objective-C++ and the shading languages, and the old name said otherwise. One dialect for all
  of them is deliberate rather than a shortcut: a `.h` is C, C++ or Objective-C with nothing in
  its name to say which, and a `.mm` is genuinely both at once, while their literals are
  identical -- so only the declaration shapes differ, and those are told apart by their own
  syntax instead of by the file's name.
- Five sample files joined the golden suite: Swift, Objective-C and Rust by hand, plus generated
  long-form Swift and Objective-C from `tools/gen_longform_ios.py`, built the same way the C#
  one is. Every block in those two is an exact size straddling one of the plugin's thresholds --
  48 and 50 lines against the 50-line label, 98 and 156 against the 100-line span, 29/31, 59/61,
  38/40 -- and the filler never opens a brace, so the sizes are exactly what the generator says.
  The generator records what it meant to produce and the golden records what the scanner saw:
  two independent accounts of the same file, which agreed on all 33 computed blocks.
- Tests grew to 319, and the new ones were checked by mutation rather than by passing: breaking
  the `class`-as-modifier rule and the block-literal caret test fails twelve of them, the golden
  files included.

## 1.11.0

- Fixed: a base-type list broken over more than one line made the class vanish -- no colour, no
  label, no place in the nesting or sibling bookkeeping. `public class Foo : IBar,` with `IBaz`
  under it, or the same list with the `:` leading, or a generic base wrapped inside its own angle
  brackets: only the first continuation line starts with the `:` that marked it as one, so every
  line after it read as a brand new statement and the header shrank to that last line alone. A
  line whose predecessor ended with `,` or `:` now continues the declaration too. Read only by
  the header bookkeeping, never by the phantom indent -- a trailing comma is also how every
  element of a `{ }` initializer list ends.
- Operator overloads and conversions are recognised. `public static Foo operator +(Foo a, Foo b)`
  used to get nothing at all, because what stands in front of the parameter list is `+` and the
  classifier was looking for an identifier; `public static implicit operator int(Foo a)` was
  worse, labelled `fun int` as if the conversion's target type were the method's name. Both are
  now `op` plus the operator exactly as the source writes it: `op +`, `op ==`, `op int`. They
  share the methods switch rather than owning one -- an operator is a method in every respect but
  its label, and `fun +` would have read as a method called `+`.
- The span marker no longer claims to point at the `{`. It printed `{: 920` while 920 was the
  line the block is **declared** on, which is a lie by one line in Allman style and by a whole
  signature when the signature wraps. It now reads `↑: 920  Δ: 143`, and the settings panel says
  the same thing.
- The rules behind the markers moved out of `BraceAccentStyle` into `scan/LabelPolicy.kt`, which
  has no IntelliJ in it. Which closing brace is named, which reports its span, which repeats its
  `[N]`, what the span text says -- all of it was previously welded to a live `Editor` and could
  only be checked by opening a file and looking at it. It now has 21 tests of its own, including
  the exact case from the report: a 112-line nested class in a file of fifteen types, with the
  plugin's default settings, reports `↑: 17  Δ: 112`.
- Fixed: a method with a parameter named `record` was classified as a **type**.
  `public void Save(Record record)` -- about as ordinary as C# gets -- came back as a type
  declaration called `Record`, and so did `if (record != null) {` and
  `foreach (var record in records) {`. `record` is contextual, so it is a legal identifier, and
  the type-keyword search read the whole header including the parameter list. It now stops at
  the parameter list: a type's own keyword always stands in front of one.
- Fixed: with types switched off, a positional record or a primary constructor
  (`public record Point(int X)`, `public class Node(int id)`) was relabelled `fun Point` instead
  of going quiet. Both end in a parameter list, so unlike a plain `class Foo` they reached the
  function fallback. Off means invisible, which is the rule the property fallback already had.
- New: whole real source files are now scanned by the test suite, from
  `src/test/resources/samples`. Two kinds of check on them -- invariants that run over every
  file in the folder with no expected output at all (balanced braces, in-bounds names, ascending
  offsets, phantom text that matches the document, a deterministic result), so a new file is
  covered the moment it is dropped in; and a golden file per sample, one readable line per
  block, which is what catches a class quietly disappearing from a generated source. The folder's
  own README says why regenerating a golden is a change to the plugin, not a fix to the test.
  Four UniTask sources to start with, 46 types and 329 blocks between them: the generated
  `WhenAny` file from the report (fifteen types, tuple return types, tuple base lists),
  `UniTask.cs` (conversion operators to fully qualified types, two same-named structs at
  namespace level), `UniTask.Factory.cs` (destructors, a lambda named from the call it is
  passed to) and `UniTask.Delay.cs` (eleven types, seven static constructors, an enum, a
  readonly struct nested in a readonly struct).
- Fixed: a constructor whose brace hangs off its base call was not recognised at all.
  `public C(int value)` with `: base() {` under it -- the K&R half of the colon-continuation
  family, whose Allman half was fixed in 1.6.1. The repair in `finishLine` runs once the line is
  over, which is soon enough for a brace on the next line and too late for one hanging off the
  end of this one, so the classifier got `: base()` as the whole header. Both the header's
  offset and its line number are now corrected in the middle of the line, through one shared
  test, so the two cannot disagree about where the declaration began.
- New sample, and the only hand-written one: `Zoo.EveryShape.cs`, 808 lines holding every shape
  the scanner knows and a good number it must ignore -- all four record spellings, five shapes
  of base-type list, three levels of type nesting with local functions inside them, ten
  operators, tuple return types, every control construct and initializer, all five kinds of C#
  string literal with braces inside them, `#if` and `#region`, and a second namespace written
  K&R. The vendored files could not cover that last part: three of the four are pure Allman and
  produced no phantom line at all, which left the move mechanic untested by real files. The
  constructor bug above is the one it found on its first run.
- Second hand-written sample, `Zoo.LongForm.cs`: 4277 lines, 166 blocks, generated by
  `tools/gen_longform.py` so its sizes are exact. `Zoo.EveryShape.cs` holds one of everything
  but almost nothing in it is longer than three lines, so every rule that asks "is this block
  long enough to mark" went untested by it -- the per-kind label lengths, the repeated `[N]`,
  the span marker. This one is sized around those thresholds: 46% of its blocks are named at
  their closing brace and 16% report a span (60% and 30% counted by line), with probes sitting
  one line either side of every boundary -- 29/30/31 against the function label, 59/60 against
  the function span, 49/50 and 99/100 against the type's two, 14/15 against the `[N]` repeat.
  The generator records what it meant to produce and that is cross-checked against what the
  scanner measures; the two agree on all 163 blocks.
- Tests grew to 280, from 220. The switch permutation test covers operators as well, and
  `record` now has a section of its own -- positional, `record class`, `readonly record struct`,
  default parameter values, a wrapped base list, nesting and numbering, its members. The last two
  fixes above are both things those tests found, not things anybody reported.

## 1.10.0

- Fixed: a method whose return type is a tuple lost its name. `public (int, T1 result1)
  GetResult(short token)` was read as a method called `public`, because the name was taken to be
  the identifier before the first `(` -- and the first `(` there belongs to the tuple, not to the
  parameter list. One generated UniTask file had fourteen of them. The parameter list is now
  found by what follows it rather than by its position: the first top-level group the declaration
  ends with, allowing for a `: base(...)` initializer or a `where` clause after it. Taking the
  last group instead would have named every constructor after its base call, which is how the
  first attempt at this was caught.
- Fixed: a block's length was measured from its opening brace rather than from its declaration.
  In a source already written in Allman style that is a line short, and a signature spread over
  several lines is short by all of them. Every threshold in the plugin reads this length, so the
  span marker now prints numbers that agree with the rule that decided to print them. Attributes
  on their own lines above a declaration are deliberately left out.
- The block-span marker is now four settings instead of one, because "long" is not the same
  number for each: types from 100 lines, functions from 60, properties and their accessors from
  40, namespaces from 150. Properties are split off from functions on purpose -- a forty-line
  property is remarkable and a forty-line method is not -- while methods, constructors,
  destructors and lambdas share one length, since theirs is the same question.
- New restriction, its own checkbox for types and another for namespaces, both on by default:
  report the span only in a file that holds more than one block of that kind, counted over the
  whole file with nesting included. The span answers "which of these, and how far back did it
  begin", and that is a question only where something could be confused with something else. One
  class in a file has no competition; fourteen of them, hundreds of lines each, is the case the
  marker was built for.
- Corrects 1.9.0: the span marker no longer pulls the end-of-block label in behind it. It is the
  coarsest marker in the plugin and says something complete on its own -- `{: 920  Δ: 143`
  reads, where a bare `[3]` does not -- so a block short of its kind's label length now reports
  its span without also being named. The three reasons to name a block are back to three.
- The `prop` keyword is now declared once, in the scan model, instead of being spelled out again
  wherever settings have to recognise a property.
- Tests grew to 220. The switch permutations added in the previous round paid for themselves
  immediately: they caught an early exit in the scanner that skipped work it should not have,
  and the constructor regression above.

## 1.9.0

- New marker, drawn last on a very long block's end-of-block label: `{: 920  Δ: 143` -- the
  line the block's `{` is on, and how many lines below it the `}` sits. Any kind of block
  qualifies, type, function or namespace alike. The two numbers are derived from one another,
  so they always add up to the line the reader is looking at and can be checked at a glance
  instead of trusted.
- It is the one marker with no copy on the declaration line, on purpose: standing on line 920
  you can already see the block starts there. The question is only worth answering at the far
  end, after the scroll that made it hard to guess.
- It is also the one marker not coloured from the editor's keyword colour: it takes the
  line-number colour instead, greyed by its own percentage. It reports a position in the file
  rather than naming a language construct, so it reads as an extension of the gutter opposite
  it. Nothing is sampled from the document for it either -- the gutter does not dim inside a
  disabled `#if` branch, so neither does this.
- New settings under "Block span": the switch, the length it starts at (default 100 lines, well
  above the label lengths -- "how much did I just scroll past" becomes a question much later
  than "what was this block called") and its own distance to grey. Reaching that length is the
  fourth reason to name the block, alongside the three 1.8.0 settled, so the span never
  stands alone with nothing to say what it spans.

## 1.8.0

- Fixed: a closing brace could end up carrying a bare `[3]` and nothing else -- an ordinal with
  nothing after it to say what it counted. It happened whenever a numbered block was shorter
  than its kind's label length: the markers were assembled before the "does this block get a
  label at all" question was asked, so the refusal dropped the name and left the number behind.
- The rule behind it is now stated once instead of being an accident of ordering. A closing
  brace names its block for one of three separate reasons, any one of which is enough: the
  block is long (the per-kind label length), the block is nested inside one of its own kind, or
  the block is numbered and the number is being repeated down there. The third reason is new,
  and it is what makes the bare `[3]` impossible by construction -- repeating the number is now
  itself a reason to repeat the name.
- New setting, "Repeat [N] after the closing brace from this block length" (default 15 lines),
  under "Sibling numbering". The two ends of a numbered block no longer share one rule: the
  `[N]` in front of the declaration is always drawn, since the declaration explains it, while
  the copy after the closing brace has to earn its place. Its default is well below the
  per-kind label lengths (50 for types, 30 for functions) on purpose -- "which sibling is this"
  becomes worth answering long before "what was this block called" does.
- The per-kind "From this block length" spinners now say in the panel that nesting and numbering
  can name a shorter block anyway, so the number in the spinner is not read as the whole story.

## 1.7.1

- Fixed: an indexer (`public int this[int i]`) was not recognised as a block at all -- its body
  got no colour and no label, and its `get`/`set` were left looking like accessors of nothing,
  unnested. An indexer is a property that takes arguments, so it is now labelled as one, with
  `this` for its name exactly as the source writes it. Two things had hidden it: the header ends
  with `]` instead of with its own name, and a default argument value inside the brackets read
  as an initializer's `=`.
- Fixed: the `nest` marker was being put on every property accessor. An accessor is inside its
  property by definition, so the word said nothing and simply repeated itself on every `get`,
  `set` and `init` in the file. Accessors are now excluded the same way lambdas already were,
  and the fact that a block is one is recorded by the scanner rather than guessed from its
  label, so it is covered by tests.
- Both fixes are covered by tests: the scanner now records that a block is an accessor instead
  of leaving that to be guessed from its label, which is what makes either one testable at all.
  The suite grew from 151 to 196 cases, including negative ones for the shapes an indexer is
  easy to confuse with -- an attribute on its own line, an array initializer, a collection
  initializer, and a property whose type is an array.

## 1.7.0

- Every kind of function block now has its own switch: methods, constructors (with static
  constructors and destructors), properties, accessors and lambdas. The switch works at the
  classifier, so turning one off does not merely drop its label -- the plugin stops seeing the
  block at all, with no colour, no shadow, and no place in the nesting or sibling bookkeeping.
- Braces and labels became two switches per kind instead of one. "Colour the braces" and "Label
  the end of the block" can now be set independently, so a file can carry names without coloured
  braces, or coloured braces without any names.
- A lambda's label split into its two halves: the symbol and the borrowed name each have their
  own switch. With both off a lambda simply gets no label.
- The `nest` word and "name a nested block whatever its length" are two switches now, not one.
  Previously, switching the marker off silently took the label with it, so a short nested class
  lost its name entirely rather than just losing the word in front of it.
- Markers are paired and behave that way: one switch drives `[N]` and one drives `nest`, each in
  both places the marker belongs -- before the declaration and on the closing brace. `nest` no
  longer waits on the end-of-block label being on, which is what made the two markers disagree.
- The `[N]` ordinal got its own distance to grey. It was being coloured by the `nest` marker's
  setting, which it has nothing to do with.
- Performance: the document was scanned twice for every edit, once by each of the two redraw
  timers -- about 90ms per scan at the file-size ceiling, so 180ms on the EDT for one keystroke.
  The scan is now shared between them, keyed on the document's modification stamp. Only the scan
  is cached, never the colours built from it: a colour is sampled from the editor and can change
  with no edit at all (the backend answering late, the scheme switching), so caching those would
  freeze them until the next keystroke.
- Performance: the move mechanic was building a style, and sampling a colour, for every accent in
  the file, then using almost none of them -- a phantom line only ever repaints the braces that
  actually moved down, and in a file already written in Allman style it repaints none at all. It
  now styles only what it draws.
- Performance: reading one character's colour no longer goes through the run-based sampler, which
  allocated two arrays and a list and sorted that list to answer it. This is the most-called thing
  in the plugin -- one or two calls per accent per redraw.

## 1.6.1

- Fixed: a function, constructor or property with a default parameter value in its parameter
  list (`string errorMessage = ""`, `ErrorType errorType = ErrorType.None`) was not coloured at
  all. Two compounding causes: the initializer-assignment check looked for `=` across the whole
  header, including inside the parameter list, so any default value made the declaration look
  like `var a = new Foo() {`; and separately, the header was being truncated at its first `"`
  (a rule meant only for the namespace/type keyword search, to stop a literal like `"class"` from
  being read as a real keyword), which chopped off a string-literal default value and everything
  after it, including the closing `)`.

## 1.6.0

- Constructors, destructors, static constructors and properties are now recognised and coloured
  the same way types and ordinary methods already were, each with its own phantom keyword: `ctor`,
  `static ctor`, `dtor`, `prop` for a property's own declaration, and `get`/`set`/`init` for its
  accessors. All of it lives under the existing "accent functions" setting.
- Fixed: a constructor with a `: base(...)` or `: this(...)` initializer on its own line lost its
  name and colour entirely and rendered as plain, unstyled code -- the initializer line was
  mistaken for the whole declaration, so the constructor's own name never made it into the header
  text being classified.
- Fixed: the phantom brace right after such an initializer line rendered one indentation level
  too deep, matching the initializer clause's own (conventionally deeper) indent instead of the
  declaration's.

## 1.5.2

- Phantom keyword text (`ns`, `class`, `struct`, `interface`, `record`, `fun`, `nest`, `[N]`) is
  now always coloured as the editor's real keyword colour, pushed toward grey the same way it
  already was for `ns`/`nest`/`[N]`. Previously, for a type or function's end-of-block label, the
  keyword word was tinted with the *name's* colour instead -- so `class Foo` showed `class` in
  the same colour as the class name `Foo`. Only the name itself keeps its own, distinct per-kind
  colour now.

## 1.5.1

- Performance: the "move brace" mechanic (keeps the file reading as Allman style while typing) and
  the "colour and label" mechanic now redraw on two independent timers instead of sharing one.
  Colour and labels redraw only after 600ms of no typing (was 200ms, shared with the move
  mechanic), so a long typing burst no longer tears down and rebuilds the more expensive colour
  decorations on every short pause.

## 1.5.0

- Added sibling numbering: when a file, namespace or type directly contains two or more types or
  namespaces, each of them now gets a `[1]`, `[2]`, ... phantom marker before its declaration and
  on its closing brace, so it is easy to tell which member is which without scrolling back up to
  its header. Functions and lambdas are never numbered.

## 1.4.1

- Markers now follow the editor's own dimming. In a switched-off `#if` branch, or in unreachable
  code, the IDE paints everything grey -- but `ns` and `nest` stayed brightly coloured, the only
  coloured thing left on an otherwise grey screen, because their colour was read from the colour
  scheme's keyword attribute, which knows nothing about `#if`. They are now sampled from the
  construct's own keyword in the document, falling back to the scheme only when nothing is
  painted there. A type or function label already behaved correctly, since it samples the
  declaration's name.
- Fixed: a generic method whose `where` constraint sat on its own line lost its declaration
  entirely and was never coloured or labelled. By the time the constraint is its own line, the
  parameter list's parentheses have already closed, so the line looked like the start of a fresh
  statement and the header was truncated down to the constraint clause -- taking the method's
  name and return type with it.

## 1.4.0

- Removed the fence mechanic: the `>>~~~ class Foo ~~~>>` phantom lines drawn above and below a
  nested block, and every setting belonging to them. Nested blocks are marked inline instead --
  the fences looked crooked and said in two full lines what a word can say.
- Nested blocks are now always labelled on their closing brace, whatever their length, and carry
  a `nest` marker before their declaration and at the start of that label. Lambdas are excluded:
  they are short and everywhere, so marking every one of them would be noise.
- Namespaces became a block kind of their own. Their braces get the same colour, weight, shadow
  and label as types and functions, and the closing brace always carries a bare `ns` -- no name,
  no minimum length. A namespace wraps the whole file, so its closing brace is by definition the
  one furthest from its declaration, which is exactly when a label earns its place.
- A lambda's label shows the lambda symbol instead of `fun`: it has no declaration of its own to
  be a `fun` of, and lambda calculus already owns the glyph.
- The settings panel was reorganised: bold subsection headings instead of one flat list, and the
  per-kind checkboxes now actually grey out everything beneath them -- previously "Types" could
  be off while all of its colour, shadow and label settings stayed live and clickable. Shadow and
  label sub-settings are likewise gated on their own checkbox.
- Performance: deciding whether a brace had already been dimmed by the move mechanic walked every
  phantom site for every brace -- O(braces x sites) on every keystroke, on the EDT. The dimmed
  braces are collected once per redraw instead.
- Fixed: a brace's shadow was always drawn in the bold font, so with "Bold" off the shadow was a
  wider silhouette than the glyph sitting on it and read as a smear rather than as depth.
- Fixed: an end-of-block label's width was measured by counting columns of a plain space while
  the label itself is painted in italic, so its tail could be clipped by a few pixels.
- Internals: the colour-mixing formula and its `100` now live in one place (`ColorBalance`)
  instead of three copies of the formula and two of the constant; and the scanner's single
  1688-line file was split into `ScanModel` (the data it speaks in), `HeaderReader` (pure text
  helpers over a declaration header), `BlockClassifier` (what a header declares) and
  `BraceScanner` (lexing, line tracking, emitting). The public API did not change and the tests
  were not touched -- they are what proved the split safe.

## 1.1.0 - 1.3.0

Reconstructed, not logged at the time. These versions were never committed separately: they
existed only in the working tree between `d77d171` (1.0.0) and `7b0393c` (1.4.0), so the exact
boundaries between them are not recoverable. What was built across that window:

- Brace accenting for types and functions: a brace takes its colour from its declaration's own
  name as the editor actually paints it -- sampled, not guessed from an attribute key, so it
  matches whatever ReSharper and the current scheme do -- pushed away from the background, with
  optional bold, a drop shadow, and an end-of-block label (`}  class Foo`) for blocks past a
  configurable length.
- The fence mechanic for nested blocks, removed again in 1.4.0.
- Fixed: a lambda passed to a call whose parentheses were still open had every brace in its body
  classified against that outer call's header, so unrelated `if` and `for` blocks came out
  coloured as types or functions. Bracket depth is now saved and reset per block.
- Fixed: the fence colour fell back to the line-number colour, too faint to read on a light
  scheme.

## Before 1.0.0

From commit subjects only: the initial plugin, settings and configuration, correct handling of
indented strings, brace colouring with end-of-block comments, and at 1.0.0 the translation of all
UI text, documentation and code comments to English.
