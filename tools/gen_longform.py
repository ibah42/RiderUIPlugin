#!/usr/bin/env python3
"""
Generates Zoo.LongForm.cs -- the same zoo of shapes as Zoo.EveryShape.cs, but with every block
grown until roughly half of them cross the threshold that makes the plugin mark the closing
brace.

Thresholds it is aimed at (the plugin's own defaults):
    type label      50 lines      function label   30 lines
    type span      100 lines      function span    60 lines
    property span   40 lines      namespace span  150 lines
    [N] repeated    15 lines

Sizes are exact by construction: a block declared at line D with its `{` on D+1 and its `}` on
D+N spans N lines, so the generator emits bodies of a computed length and records what it meant
to produce. check_longform.py then asks the scanner and compares -- if the two disagree, one of
them is wrong and that is worth knowing.
"""

import io

OUT = "src/test/resources/samples/Zoo.LongForm.cs"
MANIFEST = "/tmp/longform-manifest.txt"

manifest = []
lines = []


def emit(text=""):
    lines.append(text)


def here():
    """1-based line number of the next line to be emitted."""
    return len(lines) + 1


def record(kind, name, decl_line):
    """Remember that the block named here started at decl_line; span filled in on close."""
    manifest.append([kind, name, decl_line, None])
    return len(manifest) - 1


def close(index, close_line):
    manifest[index][3] = close_line - manifest[index][2]


# --------------------------------------------------------------------------- body filler

# Every template is self-contained: it uses only what the preamble declares, so the file reads
# as code that could compile rather than as filler referring to names that were never bound.
STATEMENTS = [
    "total += source.Count;",
    "total -= source.Count / 4;",
    "log.Append(\"{p}\");",
    "log.Append(total);",
    "seen[\"{p}\"] = total;",
    "total = Math.Max(total, source.Count);",
    "total = Math.Min(total, source.Count * 2);",
    "buffer.Add(total);",
    "log.Append(seen.Count);",
    "total += buffer.Count;",
]

BLOCKS = [
    # each entry is a list of lines; braces here are control flow, never declarations
    [
        "if (total > source.Count)",
        "{",
        "    total -= source.Count;",
        "}",
        "else",
        "{",
        "    total += source.Count;",
        "}",
    ],
    [
        "foreach (var item in source)",
        "{",
        "    total += item;",
        "    buffer.Add(item);",
        "}",
    ],
    [
        "for (var index = 0; index < source.Count; index++)",
        "{",
        "    total += index;",
        "}",
    ],
    [
        "switch (total % 3)",
        "{",
        "    case 0:",
        "        total++;",
        "        break;",
        "    default:",
        "        total--;",
        "        break;",
        "}",
    ],
    [
        "try",
        "{",
        "    total += source.Count;",
        "}",
        "catch (InvalidOperationException)",
        "{",
        "    total = 0;",
        "}",
    ],
    [
        "while (total > 1000)",
        "{",
        "    total /= 2;",
        "}",
    ],
]


def body_lines(count, prefix):
    """Exactly `count` lines of plausible C#, control flow included, no declarations."""
    out = []
    block_index = 0
    statement_index = 0
    while len(out) < count:
        remaining = count - len(out)
        block = BLOCKS[block_index % len(BLOCKS)]
        if remaining >= len(block) + 1:
            out.extend(line.replace("{p}", prefix) for line in block)
            out.append("")
            block_index += 1
        else:
            out.append(STATEMENTS[statement_index % len(STATEMENTS)].replace("{p}", prefix))
            statement_index += 1
    return out[:count]


def write_body(count, prefix, indent):
    for line in body_lines(count, prefix):
        if line == "":
            emit("")
        else:
            emit(indent + line)


# --------------------------------------------------------------------------- emitters

def bulk_type(name, type_span, method_spans, prefix):
    """A type of an exact size holding methods of exact sizes."""
    d = here()
    i = record("class", name, d)
    emit("    public sealed class " + name)
    emit("    {")
    emit("        private readonly List<int> source = new List<int>();")
    emit("")
    for index, span in enumerate(method_spans):
        method("        ", "public int Step%d(List<int> source)" % index, span, prefix + "S%d" % index)
        emit("")
    # pad the type out to its exact size with fields
    used = here() - 1 - d
    pad = type_span - used - 1
    for index in range(pad):
        emit("        private int %s_pad%d;" % (prefix.lower(), index))
    emit("    }")
    close(i, here() - 1)
    emit("")




def method(indent, signature, span, prefix, kind="fun", name=None):
    """A method whose `}` lands exactly `span` lines below its declaration."""
    decl = here()
    index = record(kind, name or prefix, decl)
    emit(indent + signature)
    emit(indent + "{")
    emit(indent + "    var total = 0;")
    emit(indent + "    var log = new StringBuilder();")
    emit(indent + "    var seen = new Dictionary<string, int>();")
    emit(indent + "    var buffer = new List<int>();")
    emit("")
    # decl, {, 4 setup lines, blank, ... , return, }
    filler = span - 2 - 5 - 2
    write_body(filler, prefix, indent + "    ")
    emit("")
    emit(indent + "    return total;")
    emit(indent + "}")
    close(index, here() - 1)


def void_method(indent, signature, span, prefix, name=None):
    decl = here()
    index = record("fun", name or prefix, decl)
    emit(indent + signature)
    emit(indent + "{")
    emit(indent + "    var total = 0;")
    emit(indent + "    var log = new StringBuilder();")
    emit(indent + "    var seen = new Dictionary<string, int>();")
    emit(indent + "    var buffer = new List<int>();")
    emit("")
    write_body(span - 2 - 5, prefix, indent + "    ")
    emit(indent + "}")
    close(index, here() - 1)


def property_block(indent, signature, getter_span, setter_span, prefix, name):
    """A property with bodied accessors; its own span follows from the two."""
    decl = here()
    index = record("prop", name, decl)
    emit(indent + signature)
    emit(indent + "{")

    get_decl = here()
    get_index = record("get", name + ".get", get_decl)
    emit(indent + "    get")
    emit(indent + "    {")
    emit(indent + "        var total = field;")
    emit(indent + "        var log = new StringBuilder();")
    emit(indent + "        var seen = new Dictionary<string, int>();")
    emit(indent + "        var buffer = new List<int>();")
    emit("")
    write_body(getter_span - 2 - 5 - 2, prefix + "Get", indent + "        ")
    emit("")
    emit(indent + "        return total;")
    emit(indent + "    }")
    close(get_index, here() - 1)
    emit("")

    set_decl = here()
    set_index = record("set", name + ".set", set_decl)
    emit(indent + "    set")
    emit(indent + "    {")
    emit(indent + "        var total = value;")
    emit(indent + "        var log = new StringBuilder();")
    emit(indent + "        var seen = new Dictionary<string, int>();")
    emit(indent + "        var buffer = new List<int>();")
    emit("")
    write_body(setter_span - 2 - 5 - 1, prefix + "Set", indent + "        ")
    emit(indent + "        field = total;")
    emit(indent + "    }")
    close(set_index, here() - 1)

    emit(indent + "}")
    close(index, here() - 1)


def header(text):
    emit("    // " + "=" * 86)
    emit("    // " + text)
    emit("    // " + "=" * 86)
    emit("")



# --------------------------------------------------------------------------- the file

emit("// " + "=" * 96)
emit("// Zoo.LongForm.cs -- the same zoo of shapes as Zoo.EveryShape.cs, grown up.")
emit("//")
emit("// Zoo.EveryShape.cs is wide and short: it holds one of everything, and almost every block")
emit("// in it is three lines long. That makes it a good test of the CLASSIFIER and a useless one")
emit("// for every rule that asks \"is this block long enough to say something about\" -- the label")
emit("// thresholds, the [N] repeated after a closing brace, the block-span marker. None of those")
emit("// fire in it at all.")
emit("//")
emit("// This file is the other half. Same vocabulary, but sized on purpose: about half of its")
emit("// blocks cross the threshold that makes the closing brace speak, and the rest sit just")
emit("// under it. Several sit exactly ON it, one line either side, which is where an off-by-one")
emit("// would hide.")
emit("//")
emit("// Sizes are generated, not typed -- see repro/gen_longform.py in the plugin's scratch")
emit("// history. The bodies are filler with a shape: control flow, loops and switches, so the")
emit("// scanner has something to walk past rather than a wall of blank lines.")
emit("// " + "=" * 96)
emit("")
emit("#pragma warning disable CS0169, CS0649, CS1591, CS8321, CS0067")
emit("")
emit("using System;")
emit("using System.Collections.Generic;")
emit("using System.Linq;")
emit("using System.Text;")
emit("using System.Threading;")
emit("using System.Threading.Tasks;")
emit("")

ns_decl = here()
ns_index = record("ns", "LongForm", ns_decl)
emit("namespace AllmanView.Zoo.LongForm")
emit("{")

# ---------------------------------------------------------------- function thresholds
header("functions: 29 and 30 lines straddle the label, 59 and 60 the span")

t_decl = here()
t_index = record("class", "FunctionThresholds", t_decl)
emit("    public sealed class FunctionThresholds")
emit("    {")
emit("        private readonly List<int> source = new List<int>();")
emit("")

method("        ", "public int JustUnderTheLabel(List<int> source)", 29, "Under")
emit("")
method("        ", "public int ExactlyAtTheLabel(List<int> source)", 30, "Exact")
emit("")
method("        ", "public int JustOverTheLabel(List<int> source)", 31, "Over")
emit("")
method("        ", "public int JustUnderTheSpan(List<int> source)", 59, "SpanUnder")
emit("")
method("        ", "public int ExactlyAtTheSpan(List<int> source)", 60, "SpanExact")
emit("")
method("        ", "public int WellOverTheSpan(List<int> source)", 95, "SpanOver")
emit("")
method("        ", "public int Tiny(List<int> source)", 9, "Tiny")
emit("    }")
close(t_index, here() - 1)
emit("")

# ---------------------------------------------------------------- type thresholds
header("types: 49 and 50 lines straddle the label, 99 and 100 the span")

# bulk_type pads to an exact size, which is what a boundary probe needs: a type called
# JustUnderTheLabel that is actually one line OVER it would be worse than no probe at all.
bulk_type("TypeJustUnderTheLabel", 49, [20, 19], "TULabel")
bulk_type("TypeExactlyAtTheLabel", 50, [20, 19], "TALabel")
bulk_type("TypeJustUnderTheSpan", 99, [30, 30, 28], "TUSpan")
bulk_type("TypeExactlyAtTheSpan", 100, [30, 30, 28], "TASpan")

# ---------------------------------------------------------------- siblings and [N]
header("siblings: 14 and 15 lines straddle the repeated [N]")

sib_decl = here()
sib_index = record("class", "SiblingHost", sib_decl)
emit("    public sealed class SiblingHost")
emit("    {")
for name, span in [("Under", 14), ("Exact", 15), ("Over", 40)]:
    d = here()
    i = record("class", "Sibling" + name, d)
    emit("        public sealed class Sibling" + name)
    emit("        {")
    emit("            private readonly List<int> source = new List<int>();")
    emit("")
    method("            ", "public int Work(List<int> source)", span - 2 - 3, name + "W")
    emit("        }")
    close(i, here() - 1)
    emit("")
emit("        private readonly List<int> source = new List<int>();")
emit("    }")
close(sib_index, here() - 1)
emit("")

# ---------------------------------------------------------------- properties
header("properties: accessors either side of the 40-line span")

prop_decl = here()
prop_index = record("class", "PropertyZoo", prop_decl)
emit("    public sealed class PropertyZoo")
emit("    {")
emit("        private int field;")
emit("")
property_block("        ", "public int Small", 12, 12, "Small", "Small")
emit("")
property_block("        ", "public int Large", 26, 24, "Large", "Large")
emit("")
emit("        public int Auto { get; set; }")
emit("    }")
close(prop_index, here() - 1)
emit("")

# ---------------------------------------------------------------- three levels, all long
header("three levels of nesting, every level long enough to be named")

l1 = here()
l1_index = record("class", "Level1", l1)
emit("    public sealed class Level1")
emit("    {")
emit("        private readonly List<int> source = new List<int>();")
emit("")

l2 = here()
l2_index = record("class", "Level2", l2)
emit("        public sealed class Level2")
emit("        {")
emit("            private readonly List<int> source = new List<int>();")
emit("")

l3 = here()
l3_index = record("class", "Level3", l3)
emit("            public sealed class Level3")
emit("            {")
emit("                private readonly List<int> source = new List<int>();")
emit("")
method("                ", "public int Deepest(List<int> source)", 55, "Deep")
emit("            }")
close(l3_index, here() - 1)
emit("")
method("            ", "public int AtLevel2(List<int> source)", 35, "Mid")
emit("        }")
close(l2_index, here() - 1)
emit("")
method("        ", "public int AtLevel1(List<int> source)", 35, "Top")
emit("    }")
close(l1_index, here() - 1)
emit("")

# ---------------------------------------------------------------- members
header("constructors, destructor, operators -- all grown past the label")

mem = here()
mem_index = record("class", "MemberZoo", mem)
emit("    public sealed class MemberZoo")
emit("    {")
emit("        private readonly List<int> source = new List<int>();")
emit("        private int field;")
emit("")

d = here()
i = record("static ctor", "MemberZoo", d)
emit("        static MemberZoo()")
emit("        {")
emit("            var total = 0;")
emit("            var log = new StringBuilder();")
emit("            var seen = new Dictionary<string, int>();")
emit("            var buffer = new List<int>();")
emit("")
write_body(24, "Static", "            ")
emit("        }")
close(i, here() - 1)
emit("")

d = here()
i = record("ctor", "MemberZoo", d)
emit("        public MemberZoo(int seed)")
emit("            : this(seed, 0)")
emit("        {")
emit("            var total = seed;")
emit("            var log = new StringBuilder();")
emit("            var seen = new Dictionary<string, int>();")
emit("            var buffer = new List<int>();")
emit("")
write_body(26, "Ctor", "            ")
emit("        }")
close(i, here() - 1)
emit("")

d = here()
i = record("ctor", "MemberZoo", d)
emit("        public MemberZoo(int seed, int offset)")
emit("        {")
emit("            field = seed + offset;")
emit("        }")
close(i, here() - 1)
emit("")

d = here()
i = record("dtor", "MemberZoo", d)
emit("        ~MemberZoo()")
emit("        {")
emit("            var total = 0;")
emit("            var log = new StringBuilder();")
emit("            var seen = new Dictionary<string, int>();")
emit("            var buffer = new List<int>();")
emit("")
write_body(26, "Dtor", "            ")
emit("        }")
close(i, here() - 1)
emit("")

for op, signature, span in [
    ("+", "public static MemberZoo operator +(MemberZoo left, MemberZoo right)", 34),
    ("==", "public static bool operator ==(MemberZoo left, MemberZoo right)", 32),
    ("!=", "public static bool operator !=(MemberZoo left, MemberZoo right)", 8),
    ("int", "public static implicit operator int(MemberZoo value)", 62),
]:
    d = here()
    i = record("op", op, d)
    emit("        " + signature)
    emit("        {")
    emit("            var total = 0;")
    emit("            var log = new StringBuilder();")
    emit("            var seen = new Dictionary<string, int>();")
    emit("            var source = new List<int>();")
    emit("")
    write_body(span - 2 - 5 - 2, "Op", "            ")
    emit("")
    if op in ("==", "!="):
        emit("            return total > 0;")
    elif op == "int":
        emit("            return total;")
    else:
        emit("            return left;")
    emit("        }")
    close(i, here() - 1)
    emit("")

emit("        public override bool Equals(object other)")
emit("        {")
emit("            return false;")
emit("        }")
emit("")
emit("        public override int GetHashCode()")
emit("        {")
emit("            return field;")
emit("        }")
emit("    }")
close(mem_index, here() - 1)
emit("")

# ---------------------------------------------------------------- lambdas
header("lambdas long enough to be named at their closing brace")

lam = here()
lam_index = record("class", "LambdaZoo", lam)
emit("    public sealed class LambdaZoo")
emit("    {")
emit("        private readonly List<int> source = new List<int>();")
emit("")

d = here()
i = record("fun", "Passed", d)
emit("        public IEnumerable<int> Passed(List<int> source)")
emit("        {")
emit("            var total = 0;")
emit("")
d2 = here()
i2 = record("lambda", "Select", d2)
emit("            var mapped = source.Select(value =>")
emit("            {")
emit("                var log = new StringBuilder();")
emit("                var seen = new Dictionary<string, int>();")
emit("                var buffer = new List<int>();")
emit("")
write_body(26, "Lam", "                ")
emit("")
emit("                return value + total;")
emit("            });")
close(i2, here() - 1)
emit("")
emit("            return mapped;")
emit("        }")
close(i, here() - 1)
emit("    }")
close(lam_index, here() - 1)
emit("")

# ---------------------------------------------------------------- records and structs
header("records and structs, grown")

for keyword, name, span in [
    ("public record", "LongRecord", 52),
    ("public readonly record struct", "LongCoord", 44),
    ("public struct", "LongPoint", 58),
]:
    d = here()
    i = record(keyword.split()[-1], name, d)
    if "record" in keyword:
        emit("    " + keyword + " " + name + "(int X, int Y)")
    else:
        emit("    " + keyword + " " + name)
    emit("    {")
    emit("        private readonly List<int> source;")
    emit("")
    method("        ", "public int Measure(List<int> source)", span - 2 - 3, name + "M")
    emit("    }")
    close(i, here() - 1)
    emit("")

# ---------------------------------------------------------------- the bulk, balanced
header("the bulk: everything here is far bigger than Zoo.EveryShape, half of it still quiet")

# Under every threshold, but nothing like the three-line blocks of the other zoo:
# types in the forties, methods in the twenties.
for index in range(14):
    bulk_type(
        "QuietType%02d" % index,
        44 + (index % 5),
        [17 + (index % 6), 21 + (index % 4)],
        "Quiet%02d" % index,
    )

# Quiet but large: a type one line under its label and a method one line under its own.
# These carry weight without ever speaking, which is what keeps the line count honest --
# a fixture where every long block is also a marked block proves nothing about the rule.
for index in range(8):
    bulk_type(
        "HeavyQuietType%02d" % index,
        48,
        [29],
        "HeavyQuiet%02d" % index,
    )

# Over the label but under the span: named, not spanned.
for index in range(6):
    bulk_type(
        "NamedType%02d" % index,
        62 + (index % 7) * 3,
        [33 + (index % 5) * 2, 19 + (index % 3)],
        "Named%02d" % index,
    )

# Over both: these are what the span marker was built for.
for index in range(4):
    bulk_type(
        "SpanningType%02d" % index,
        150 + index * 30,
        [70 + index * 8, 64 + index * 6, 26 + index],
        "Span%02d" % index,
    )

emit("}")
close(ns_index, here() - 1)
emit("")


# ---------------------------------------------------------------- K&R namespace
emit("// " + "=" * 96)
emit("// The same again in K&R, so the move mechanic has long blocks to draw too.")
emit("// " + "=" * 96)
emit("")

ns2 = here()
ns2_index = record("ns", "Kandr", ns2)
emit("namespace AllmanView.Zoo.LongForm.Kandr")
emit("{")

kd = here()
k_index = record("class", "Hanging", kd)
emit("    public sealed class Hanging {")
emit("        private readonly List<int> source = new List<int>();")
emit("")

for name, span in [("Quiet", 19), ("Modest", 24), ("Named", 34), ("Longer", 41),
                   ("Spanned", 66), ("Wider", 78), ("Widest", 96), ("Calm", 22)]:
    d = here()
    i = record("fun", name, d)
    emit("        public int " + name + "(List<int> source) {")
    emit("            var total = 0;")
    emit("            var log = new StringBuilder();")
    emit("            var seen = new Dictionary<string, int>();")
    emit("            var buffer = new List<int>();")
    emit("")
    write_body(span - 1 - 5 - 2, "K" + name, "            ")
    emit("")
    emit("            return total;")
    emit("        }")
    close(i, here() - 1)
    emit("")

emit("    }")
close(k_index, here() - 1)
emit("}")
close(ns2_index, here() - 1)
emit("")

io.open(OUT, "w", encoding="utf-8").write("\n".join(lines) + "\n")

with io.open(MANIFEST, "w", encoding="utf-8") as handle:
    for kind, name, decl, span in manifest:
        handle.write("%s\t%s\t%d\t%s\n" % (kind, name, decl, span))

print("wrote %s: %d lines, %d blocks" % (OUT, len(lines), len(manifest)))
