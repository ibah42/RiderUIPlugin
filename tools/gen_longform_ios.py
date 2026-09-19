#!/usr/bin/env python3
"""
Generates the two long iOS samples -- Zoo.LongForm.swift and Zoo.LongForm.m -- the same way
gen_longform.py generates the C# one: every block grown to an exact size, chosen so that roughly
half of them cross each threshold the plugin cares about and half stay under it.

Thresholds it is aimed at (the plugin's own defaults):
    type label      50 lines      function label   30 lines
    type span      100 lines      function span    60 lines
    property span   40 lines      [N] repeated     15 lines

Sizes are exact by construction, and the filler never opens a brace, so a block declared at
line D closing at line D+N spans exactly N and contributes no block of its own. The manifest
records what the generator meant to produce; the golden file records what the scanner saw. Two
independent accounts of the same file is the whole point -- if they disagree, one is a bug.
"""

import io

SWIFT_OUT = "src/test/resources/samples/Zoo.LongForm.swift"
OBJC_OUT = "src/test/resources/samples/Zoo.LongForm.m"
MANIFEST = "/tmp/longform-ios-manifest.txt"


class File:
    def __init__(self):
        self.lines = []
        self.manifest = []

    def emit(self, text=""):
        self.lines.append(text)

    def here(self):
        """1-based line number of the next line to be emitted."""
        return len(self.lines) + 1

    def record(self, kind, name, decl_line):
        self.manifest.append([kind, name, decl_line, None])
        return len(self.manifest) - 1

    def close(self, index, close_line):
        self.manifest[index][3] = close_line - self.manifest[index][2]

    def write(self, path):
        io.open(path, "w", encoding="utf-8", newline="\n").write("\n".join(self.lines) + "\n")


# --------------------------------------------------------------------------- filler

SWIFT_FILLER = [
    "total += index",
    "log += \"step \\(index)\"",
    "values.append(index)",
    "index = index &+ 1",
    "print(log, total)",
    "flag = !flag",
    "name = name.uppercased()",
    "index = max(index, values.count)",
]

OBJC_FILLER = [
    "total += index;",
    "index += 1;",
    "[buffer addObject:@(index)];",
    "NSLog(@\"step %ld\", (long)index);",
    "flag = !flag;",
    "name = [name uppercaseString];",
    "index = MAX(index, (NSInteger)buffer.count);",
    "total = total * 2 - index;",
]


def fill(out, count, indent, filler):
    """`count` lines of body that never open a brace, so sizes stay exact."""
    for i in range(count):
        out.emit(indent + filler[i % len(filler)])


# --------------------------------------------------------------------------- Swift

def swift_locals(out, indent):
    out.emit(indent + "var total = 0")
    out.emit(indent + "var index = 0")
    out.emit(indent + "var flag = false")
    out.emit(indent + "var log = \"\"")
    out.emit(indent + "var values: [Int] = []")
    out.emit(indent + "var name = \"zoo\"")
    return 6


def swift_function(out, keyword, name, span, indent, kind="func"):
    """`func name() -> Int {` on one line, body, `}` -- span lines from the decl to the brace."""
    decl = out.here()
    index = out.record(kind, name or keyword, decl)
    if keyword == "init":
        out.emit(indent + "init(seed: Int) {")
    elif keyword == "deinit":
        out.emit(indent + "deinit {")
    elif keyword == "subscript":
        out.emit(indent + "subscript(position: Int) -> Int {")
    else:
        out.emit(indent + "%s %s() -> Int {" % (keyword, name))

    body = indent + "    "
    used = swift_locals(out, body)
    fill(out, span - 1 - used - 1, body, SWIFT_FILLER)
    out.emit(body + "return total")
    out.emit(indent + "}")
    out.close(index, out.here() - 1)


def swift_property(out, name, span, indent, accessor_span):
    """A computed property whose `get` is sized too, so both thresholds get exercised."""
    decl = out.here()
    index = out.record("prop", name, decl)
    out.emit(indent + "var %s: Int {" % name)

    inner = indent + "    "
    accessor_decl = out.here()
    accessor_index = out.record("get", name, accessor_decl)
    out.emit(inner + "get {")
    body = inner + "    "
    used = swift_locals(out, body)
    fill(out, accessor_span - 1 - used - 1, body, SWIFT_FILLER)
    out.emit(body + "return total")
    out.emit(inner + "}")
    out.close(accessor_index, out.here() - 1)

    fill(out, span - (out.here() - decl) - 1, inner, ["// padding that opens nothing"])
    out.emit(indent + "}")
    out.close(index, out.here() - 1)


def swift_type(out, keyword, name, span, indent, members):
    decl = out.here()
    index = out.record(keyword, name, decl)
    suffix = ": Fetching" if keyword == "extension" else ""
    out.emit(indent + "%s %s%s {" % (keyword, name, suffix))
    inner = indent + "    "

    for member in members:
        member(inner)
        out.emit("")

    pad = span - (out.here() - decl) - 1
    for i in range(pad):
        out.emit(inner + "private var pad%d = %d" % (i, i))
    out.emit(indent + "}")
    out.close(index, out.here() - 1)


def build_swift():
    out = File()
    out.emit("// Generated by tools/gen_longform_ios.py -- do not edit by hand.")
    out.emit("//")
    out.emit("// Every block here is an exact size, chosen to sit just under or just over one of")
    out.emit("// the plugin's thresholds. The filler never opens a brace, so the sizes are what")
    out.emit("// the generator says they are and nothing else is counted.")
    out.emit("")
    out.emit("import Foundation")
    out.emit("")
    out.emit("protocol Fetching {")
    out.emit("    func fetch() -> Int")
    out.emit("}")
    out.emit("")

    # A type just under the label threshold, and one just over it.
    swift_type(out, "struct", "UnderLabel", 49, "", [
        lambda i: swift_function(out, "func", "short", 5, i),
        lambda i: swift_function(out, "func", "justUnder", 29, i),
    ])
    out.emit("")
    swift_type(out, "struct", "OverLabel", 51, "", [
        lambda i: swift_function(out, "func", "justOver", 31, i),
        lambda i: swift_function(out, "func", "tiny", 3, i),
    ])
    out.emit("")

    # A type just under the span threshold, and one well over it.
    swift_type(out, "final class", "UnderSpan", 99, "", [
        lambda i: swift_function(out, "init", "", 8, i, kind="ctor"),
        lambda i: swift_function(out, "deinit", "", 4, i, kind="dtor"),
        lambda i: swift_function(out, "func", "justUnderSpan", 59, i),
    ])
    out.emit("")
    swift_type(out, "final class", "OverSpan", 140, "", [
        lambda i: swift_function(out, "func", "overSpan", 61, i),
        lambda i: swift_property(out, "underPropertySpan", 39, i, 30),
        lambda i: swift_property(out, "overPropertySpan", 41, i, 32),
        lambda i: swift_function(out, "subscript", "", 6, i, kind="prop"),
    ])
    out.emit("")

    swift_type(out, "actor", "Counter", 20, "", [
        lambda i: swift_function(out, "func", "bump", 16, i),
    ])
    out.emit("")
    swift_type(out, "extension", "String", 18, "", [
        lambda i: swift_function(out, "func", "fetch", 14, i),
    ])
    out.emit("")

    out.emit("enum Outcome {")
    out.emit("    case idle")
    out.emit("    case done")
    out.emit("}")
    out.emit("")

    # Trailing closures, in and out of a function long enough to be labelled.
    decl = out.here()
    index = out.record("func", "withClosures", decl)
    out.emit("func withClosures() {")
    out.emit("    var total = 0")
    out.emit("    let values = [1, 2, 3]")
    out.emit("")
    closure_decl = out.here()
    closure_index = out.record("lambda", "forEach", closure_decl)
    out.emit("    values.forEach { value in")
    fill(out, 18, "        ", ["total += value"])
    out.emit("    }")
    out.close(closure_index, out.here() - 1)
    out.emit("")
    short_decl = out.here()
    short_index = out.record("lambda", "async", short_decl)
    out.emit("    DispatchQueue.main.async {")
    out.emit("        total = 0")
    out.emit("    }")
    out.close(short_index, out.here() - 1)
    out.emit("")
    fill(out, 10, "    ", ["total = total &+ 1"])
    out.emit("}")
    out.close(index, out.here() - 1)
    out.emit("")

    out.write(SWIFT_OUT)
    return out


# --------------------------------------------------------------------------- Objective-C

def objc_locals(out, indent):
    out.emit(indent + "NSInteger total = 0;")
    out.emit(indent + "NSInteger index = 0;")
    out.emit(indent + "BOOL flag = NO;")
    out.emit(indent + "NSMutableArray *buffer = [NSMutableArray array];")
    out.emit(indent + "NSString *name = @\"zoo\";")
    return 5


def objc_method(out, sign, ret, name, selector, span, indent):
    """Allman, the way the existing sample is written: decl, `{` below it, body, `}`."""
    decl = out.here()
    index = out.record("method", name, decl)
    out.emit(indent + "%s (%s)%s%s" % (sign, ret, name, selector))
    out.emit(indent + "{")
    body = indent + "    "
    used = objc_locals(out, body)
    fill(out, span - 2 - used - 1, body, OBJC_FILLER)
    out.emit(body + "return total;" if ret != "void" else body + "(void)total;")
    out.emit(indent + "}")
    out.close(index, out.here() - 1)


def build_objc():
    out = File()
    out.emit("// Generated by tools/gen_longform_ios.py -- do not edit by hand.")
    out.emit("//")
    out.emit("// Objective-C rides on the C++ dialect and adds two shapes of its own; both are")
    out.emit("// here at several sizes, around the same thresholds the Swift file straddles.")
    out.emit("")
    out.emit("#import \"HTAZoo.h\"")
    out.emit("")
    out.emit("@implementation HTAZoo")
    out.emit("")

    objc_method(out, "-", "NSInteger", "tiny", "", 5, "")
    out.emit("")
    objc_method(out, "-", "NSInteger", "justUnderLabel", ":(NSInteger)index", 29, "")
    out.emit("")
    objc_method(out, "-", "NSInteger", "justOverLabel", ":(NSInteger)index andFlag:(BOOL)flag", 31, "")
    out.emit("")
    objc_method(out, "+", "NSInteger", "justUnderSpan", "", 59, "")
    out.emit("")
    objc_method(out, "+", "NSInteger", "justOverSpan", ":(NSString *)name", 61, "")
    out.emit("")

    # A method holding two block literals, one long enough to be labelled and one not.
    decl = out.here()
    index = out.record("method", "withBlocks", decl)
    out.emit("- (void)withBlocks")
    out.emit("{")
    out.emit("    NSInteger total = 0;")
    out.emit("")
    block_decl = out.here()
    block_index = out.record("lambda", "animations", block_decl)
    out.emit("    [UIView animateWithDuration:0.3 animations:^{")
    fill(out, 34, "        ", ["total += 1;"])
    out.emit("    } completion:^(BOOL finished) {")
    out.close(block_index, out.here() - 1)
    completion_decl = out.here() - 1
    completion_index = out.record("lambda", "completion", completion_decl)
    out.emit("        total = 0;")
    out.emit("    }];")
    out.close(completion_index, out.here() - 1)
    out.emit("")
    fill(out, 8, "    ", ["total += 1;"])
    out.emit("    (void)total;")
    out.emit("}")
    out.close(index, out.here() - 1)
    out.emit("")

    out.emit("@end")
    out.emit("")

    # A plain C function in the same file, to prove the C shapes still work beside the ObjC ones.
    decl = out.here()
    c_index = out.record("function", "HTAZooLogEverything", decl)
    out.emit("static NSInteger HTAZooLogEverything(NSArray *items)")
    out.emit("{")
    used = objc_locals(out, "    ")
    fill(out, 61 - 2 - used - 1, "    ", OBJC_FILLER)
    out.emit("    return total;")
    out.emit("}")
    out.close(c_index, out.here() - 1)
    out.emit("")

    out.write(OBJC_OUT)
    return out


swift = build_swift()
objc = build_objc()

with io.open(MANIFEST, "w", encoding="utf-8") as handle:
    for label, out in (("swift", swift), ("objc", objc)):
        for kind, name, decl, span in out.manifest:
            handle.write("%s\t%s\t%s\t%d\t%s\n" % (label, kind, name, decl, span))

print("wrote %s: %d lines, %d blocks" % (SWIFT_OUT, len(swift.lines), len(swift.manifest)))
print("wrote %s: %d lines, %d blocks" % (OBJC_OUT, len(objc.lines), len(objc.manifest)))
