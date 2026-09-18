# Changelog

One entry per version bump, newest first. See CLAUDE.md, "Keep a version log", for the rule.

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

## Before 1.5.0

Not logged -- this changelog started at 1.5.0. Earlier changes exist in the plugin's history but
were not recorded version by version.
