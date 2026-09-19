package com.hitapps.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BraceScannerTest {

    private fun scan(
        src: String,
        flavor: Flavor = Flavor.CSHARP,
        fullAllman: Boolean = true,
        splitStatements: Boolean = true,
        expandInlineBlocks: Boolean = true,
    ): List<PhantomSite> {
        val options = ScanOptions(
            fullAllman = fullAllman,
            splitStatements = splitStatements,
            expandInlineBlocks = expandInlineBlocks,
        )
        return BraceScanner(src, flavor, options).scan().sites
    }

    /** Braces marked as belonging to a type or a function. */
    private fun accents(src: String, flavor: Flavor = Flavor.CSHARP): List<BraceAccent> {
        return BraceScanner(src, flavor, ScanOptions()).scan().accents
    }

    /** Pairs of brace character and owning block kind, in document order. */
    private fun accentKinds(src: String, flavor: Flavor = Flavor.CSHARP): List<Pair<Char, BlockKind>> {
        return accents(src, flavor)
            .sortedBy { it.offset }
            .map { src[it.offset] to it.kind }
    }

    /** The name the brace colour will be taken from. */
    private fun accentName(src: String, accent: BraceAccent): String {
        if (accent.nameOffset < 0) {
            return ""
        }
        var end = accent.nameOffset
        while (end < src.length && (src[end].isLetterOrDigit() || src[end] == '_')) {
            end++
        }
        return src.substring(accent.nameOffset, end)
    }

    /** The text that will be dimmed. */
    private fun dimmed(src: String, site: PhantomSite) = src.substring(site.dimStart, site.dimEnd)

    /** How the editor will look: "..." marks dimmed text, then the phantom lines. */
    private fun render(src: String, flavor: Flavor = Flavor.CSHARP, fullAllman: Boolean = true): String {
        val sb = StringBuilder()
        var pos = 0
        for (s in scan(src, flavor, fullAllman).sortedBy { it.dimStart }) {
            sb.append(src, pos, s.dimStart)
            sb.append('«').append(src, s.dimStart, s.dimEnd).append('»')
            pos = s.dimEnd
            sb.append(src, pos, s.anchorOffset)
            pos = s.anchorOffset
            for (line in s.phantomLines) {
                sb.append('\n').append(s.indent)
                repeat(line.extraIndentLevels) { sb.append("    ") }
                sb.append(line.text)
            }
        }
        sb.append(src, pos, src.length)
        return sb.toString()
    }

    @Test
    fun `plain hanging brace`() {
        val src = "if (x) {\n    Foo();\n}"
        val site = scan(src).single()
        assertEquals("{", dimmed(src, site))
        assertEquals(listOf("{"), site.phantomTexts)
        assertEquals("if (x) «{»\n{\n    Foo();\n}", render(src))
    }

    @Test
    fun `dims only the brace, not the spaces before it`() {
        val src = "if (x)     {\n}"
        assertEquals("{", dimmed(src, scan(src).single()))
    }

    @Test
    fun `indent comes from the owner line`() {
        val site = scan("\tclass A {\n\t}").single()
        assertEquals("\t", site.indent)
        assertEquals(listOf("{"), site.phantomTexts)
    }

    @Test
    fun `indent made of spaces`() {
        val site = scan("    if (x) {\n    }").single()
        assertEquals("    ", site.indent)
    }

    @Test
    fun `else is split and dimmed as a whole`() {
        val src = "if (x) {\n} else if (y) {\n}"
        val sites = scan(src)
        assertEquals(2, sites.size)
        assertEquals(" else if (y) {", dimmed(src, sites[1]))
        assertEquals(listOf("else if (y)", "{"), sites[1].phantomTexts)
    }

    @Test
    fun `catch and finally too`() {
        val src = "try {\n} catch (E e) {\n} finally {\n}"
        val sites = scan(src)
        assertEquals(3, sites.size)
        assertEquals(listOf("catch (E e)", "{"), sites[1].phantomTexts)
        assertEquals(listOf("finally", "{"), sites[2].phantomTexts)
    }

    @Test
    fun `else without a brace`() {
        val src = "if (x) {\n} else\n    Foo();"
        val sites = scan(src)
        assertEquals(2, sites.size)
        assertEquals(" else", dimmed(src, sites[1]))
        assertEquals(listOf("else"), sites[1].phantomTexts)
    }

    @Test
    fun `fullAllman off leaves else in place`() {
        val src = "if (x) {\n} else {\n}"
        val sites = scan(src, fullAllman = false)
        assertEquals(2, sites.size)
        assertEquals("{", dimmed(src, sites[1]))
        assertEquals(listOf("{"), sites[1].phantomTexts)
    }

    @Test
    fun `already Allman is left alone`() {
        assertTrue(scan("if (x)\n{\n}").isEmpty())
    }

    @Test
    fun `do-while is not split`() {
        assertTrue(scan("do {\n} while (x);").none { it.phantomTexts.any { l -> l.startsWith("while") } })
    }

    @Test
    fun `closing brace with a semicolon is left alone`() {
        assertEquals(1, scan("Run(() => {\n});").size)
    }

    @Test
    fun `trailing comment stays on the original line`() {
        assertEquals("if (x) «{» // note\n{\n}", render("if (x) { // note\n}"))
    }

    @Test
    fun `brace inside a string literal is ignored`() {
        assertTrue(scan("""var s = "if (x) {";""").isEmpty())
    }

    @Test
    fun `brace inside a comment is ignored`() {
        assertTrue(scan("// if (x) {").isEmpty())
        assertTrue(scan("/* if (x) {\n   more { */").isEmpty())
    }

    @Test
    fun `multi-line verbatim string`() {
        assertEquals(1, scan("var s = @\"line1 {\nline2 {\";\nif (x) {\n}").size)
    }

    @Test
    fun `raw string C sharp`() {
        assertEquals(1, scan("var s = \"\"\"\nif (x) {\n\"\"\";\nif (y) {\n}").size)
    }

    @Test
    fun `interpolation with a quote inside the hole`() {
        assertEquals(1, scan("var s = ${'$'}\"{dict[\"key\"]} tail\";\nif (x) {\n}").size)
    }

    @Test
    fun `escaped braces in interpolation`() {
        assertTrue(scan("var s = ${'$'}\"{{ not a hole }}\";").isEmpty())
    }

    @Test
    fun `unterminated quote does not break the next lines`() {
        assertEquals(1, scan("var s = \"oops;\nif (x) {\n}").size)
    }

    @Test
    fun `cpp raw string`() {
        assertEquals(1, scan("auto s = R\"(if (x) {\n)\";\nif (y) {\n}", Flavor.CPP).size)
    }

    @Test
    fun `cpp digit separator is not a char literal`() {
        assertEquals(1, scan("int n = 1'000'000;\nif (x) {\n}", Flavor.CPP).size)
    }

    @Test
    fun `ts template literal`() {
        assertEquals(1, scan("const s = `a ${'$'}{obj[`x`]} {`;\nif (x) {\n}", Flavor.WEB).size)
    }

    // --- indent of multi-line constructs ---

    @Test
    fun `multi-line signature puts the brace under the declaration start`() {
        val src = "    void Foo(\n        int a,\n        int b) {\n    }"
        assertEquals("    ", scan(src).single().indent)
    }

    @Test
    fun `multi-line condition`() {
        assertEquals("", scan("if (a &&\n    b) {\n}").single().indent)
    }

    @Test
    fun `hanging brace after a constructor initializer is indented like the declaration, not the clause`() {
        // ": base(x)" is conventionally indented one level deeper than the constructor itself;
        // bracketDepth is already back to 0 by the time that line starts, since the
        // constructor's own parameter list already closed -- see BraceScanner.readIndent.
        val src = "    public Foo()\n        : base(x) {\n    }"
        val site = scan(src).single()
        assertEquals("    ", site.indent)
        assertEquals(listOf("{"), site.phantomTexts)
    }

    @Test
    fun `hanging brace after a this-call initializer is indented like the declaration too`() {
        val src = "    public Foo()\n        : this(1) {\n    }"
        assertEquals("    ", scan(src).single().indent)
    }

    @Test
    fun `a where clause still gets the same indent fix as a constructor initializer`() {
        val src = "    void Bind<T>(T value)\n        where T : class {\n    }"
        assertEquals("    ", scan(src).single().indent)
    }

    @Test
    fun `a constructor already in Allman style is left alone regardless of the initializer`() {
        val src = "public Foo()\n    : base(x)\n{\n}"
        assertTrue(scan(src).isEmpty())
    }

    @Test
    fun `call chain takes the indent of its own line`() {
        assertEquals("    ", scan("var x = Foo()\n    .Bar(y => {\n    });").single().indent)
    }

    @Test
    fun `overlong continuation falls back to its own line`() {
        val src = "void A(\n" + "x,\n".repeat(60) + "y) {\n}"
        assertEquals("", scan(src).single().indent)
    }

    // --- dialects ---

    @Test
    fun `extensions map to dialects`() {
        assertEquals(Flavor.CSHARP, Dialects.forExtension("cs"))
        assertEquals(Flavor.CPP, Dialects.forExtension("HPP"))
        assertEquals(Flavor.JVM, Dialects.forExtension("kt"))
        assertEquals(Flavor.JVM, Dialects.forExtension("swift"))
        assertEquals(Flavor.WEB, Dialects.forExtension("go"))
        assertEquals(Flavor.GENERIC, Dialects.forExtension("rs"))
        assertEquals(Flavor.GENERIC, Dialects.forExtension(""))
    }

    @Test
    fun `java text block causes no false positives`() {
        val src = "String s = \"\"\"\n    if (x) {\n    \"\"\";\nvoid m() {\n}"
        assertEquals(1, scan(src, Flavor.JVM).size)
        // without text block support the same file picks up extra hits, which is why the dialect exists
        assertEquals(2, scan(src, Flavor.GENERIC).size)
    }

    @Test
    fun `kotlin raw string`() {
        assertEquals(1, scan("val s = \"\"\"\nif (x) {\n\"\"\"\nfun f() {\n}", Flavor.JVM).size)
    }

    @Test
    fun `go raw string in backticks`() {
        assertEquals(1, scan("var s = `if x {`\nfunc f() {\n}", Flavor.WEB).size)
    }

    @Test
    fun `triple quote in C++ does not enable raw mode`() {
        assertEquals(1, scan("auto s = \"\"\"\";\nvoid f() {\n}", Flavor.CPP).size)
    }

    @Test
    fun `css`() {
        assertEquals(2, scan(".a {\n}\n.b {\n}", Flavor.GENERIC).size)
    }

    @Test
    fun `object initializer is moved too`() {
        assertEquals(1, scan("var a = new Foo {\n};").size)
    }

    @Test
    fun `single-line block is left alone when expansion is off`() {
        assertTrue(scan("if (x) { Foo(); }", expandInlineBlocks = false).isEmpty())
    }

    @Test
    fun `top level signature`() {
        val src = "private void Foo(\n    int a,\n    int b) {\n}"
        assertEquals("", scan(src).first().indent)
    }

    @Test
    fun `swift backslash interpolation`() {
        val src = "if a > 0 {\n    print(\"val \\(a) { x }\")\n}"
        assertEquals(1, scan(src, Flavor.JVM).size)
    }


    // --- virtual split of single statements ---

    @Test
    fun `if with a single return`() {
        val src = "if (pending == null) return;"
        val site = scan(src).single()
        assertEquals("return;", dimmed(src, site))
        assertEquals(listOf("return;"), site.phantomTexts)
    }

    @Test
    fun `if with a throw`() {
        val src = "    if (x < 0) throw new ArgumentException(nameof(x));"
        val site = scan(src).single()
        assertEquals("throw new ArgumentException(nameof(x));", dimmed(src, site))
        assertEquals("    ", site.indent)
    }

    @Test
    fun `loop with a single statement`() {
        val src = "foreach (var item in items) total += item.Price;"
        val site = scan(src).single()
        assertEquals("total += item.Price;", dimmed(src, site))
    }

    @Test
    fun `lock with a single statement`() {
        assertEquals(listOf("_count++;"), scan("lock (gate) _count++;").single().phantomTexts)
    }

    @Test
    fun `using directive is not split`() {
        assertTrue(scan("using System.Text;").isEmpty())
        assertTrue(scan("using static System.Math;").isEmpty())
    }

    @Test
    fun `using statement is split`() {
        assertEquals(
            listOf("stream.Flush();"),
            scan("using (var stream = Open()) stream.Flush();").single().phantomTexts,
        )
    }

    @Test
    fun `empty statement is not split`() {
        assertTrue(scan("while (reader.Read());").isEmpty())
    }

    @Test
    fun `single-line do-while is left alone`() {
        assertTrue(scan("do { } while (x);").isEmpty())
    }

    @Test
    fun `paren inside a literal does not end the header`() {
        val src = """if (Check(")")) Foo();"""
        val site = scan(src).single()
        assertEquals("Foo();", dimmed(src, site))
    }

    @Test
    fun `trailing comment survives a statement split`() {
        val src = "if (x) return; // early out"
        val site = scan(src).single()
        assertEquals("return;", dimmed(src, site))
    }

    @Test
    fun `closing brace, else and statement make three lines`() {
        val src = "if (x) {\n} else return;"
        val site = scan(src)[1]
        assertEquals(" else return;", dimmed(src, site))
        assertEquals(listOf("else", "return;"), site.phantomTexts)
    }

    @Test
    fun `else if with a statement`() {
        val src = "else if (x) return;"
        assertEquals(listOf("return;"), scan(src).single().phantomTexts)
    }

    @Test
    fun `indent is expressed in levels, not spaces`() {
        // The scanner does not know the indent width: drawString does not expand a tab inside
        // a string, so the phantom text carries no indent at all; the editor draws it.
        val line = scan("if (x) return;").single().phantomLines.single()
        assertEquals("return;", line.text)
        assertEquals(1, line.extraIndentLevels)
    }

    @Test
    fun `splitStatements off splits nothing`() {
        assertTrue(scan("if (x) return;", splitStatements = false).isEmpty())
    }

    // --- single-line braced block ---

    @Test
    fun `single-line block is expanded`() {
        val src = "if (x) { Foo(); }"
        val site = scan(src).single()
        assertEquals("{ Foo(); }", dimmed(src, site))
        assertEquals(listOf("{", "Foo();", "}"), site.phantomTexts)
    }

    @Test
    fun `empty single-line block`() {
        assertEquals(listOf("{", "}"), scan("if (x) { }").single().phantomTexts)
    }

    @Test
    fun `nested single-line block is expanded as a whole`() {
        val site = scan("if (x) { if (y) { a(); } }").single()
        assertEquals(listOf("{", "if (y) { a(); }", "}"), site.phantomTexts)
    }

    @Test
    fun `closing brace with else and a block`() {
        val src = "try {\n} catch (E e) { Log(e); }"
        val site = scan(src)[1]
        assertEquals(listOf("catch (E e)", "{", "Log(e);", "}"), site.phantomTexts)
    }

    @Test
    fun `auto-property is left alone`() {
        assertTrue(scan("public int Count { get; set; }").isEmpty())
    }

    @Test
    fun `empty single-line method body is left alone`() {
        assertTrue(scan("public void Dispose() { }").isEmpty())
    }

    @Test
    fun `single-line initializer is left alone`() {
        assertTrue(scan("var point = new Point { X = 1, Y = 2 };").isEmpty())
    }


    @Test
    fun `statement moves one level deeper`() {
        val lines = scan("if (x) return;").single().phantomLines
        assertEquals(1, lines.single().extraIndentLevels)
    }

    @Test
    fun `header and braces stay at their own level`() {
        val lines = scan("try {\n} catch (E e) { Log(e); }")[1].phantomLines
        assertEquals(listOf(0, 0, 1, 0), lines.map { it.extraIndentLevels })
    }

    @Test
    fun `phantom text sits in the document at its offset`() {
        val src = "    if (ready) Launch();"
        for (line in scan(src).single().phantomLines) {
            val slice = src.substring(line.sourceOffset, line.sourceOffset + line.text.length)
            assertEquals(line.text, slice)
        }
    }

    @Test
    fun `offset matches the text for braces and header alike`() {
        val src = "if (x) {\n} else if (y) {\n}"
        for (site in scan(src)) {
            for (line in site.phantomLines) {
                val slice = src.substring(line.sourceOffset, line.sourceOffset + line.text.length)
                assertEquals(line.text, slice)
            }
        }
    }


    // --- brace ownership by types and functions ---

    @Test
    fun `class braces are marked as a type`() {
        val src = "public class Spawner {\n}"
        assertEquals(listOf('{' to BlockKind.TYPE, '}' to BlockKind.TYPE), accentKinds(src))
    }

    @Test
    fun `colour is taken from the class name`() {
        val src = "public class Spawner {\n}"
        for (accent in accents(src)) {
            assertEquals("Spawner", accentName(src, accent))
        }
    }

    @Test
    fun `struct interface enum record are types too`() {
        for (keyword in listOf("struct", "interface", "enum", "record")) {
            val src = "public $keyword Thing {\n}"
            assertEquals(2, accents(src).size)
            assertEquals(BlockKind.TYPE, accents(src).first().kind)
            assertEquals("Thing", accentName(src, accents(src).first()))
        }
    }

    @Test
    fun `record struct takes the name after both words`() {
        val src = "public record struct Point(int X) {\n}"
        assertEquals("Point", accentName(src, accents(src).first()))
    }

    @Test
    fun `method is marked as a function`() {
        val src = "private void Update() {\n}"
        assertEquals(listOf('{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION), accentKinds(src))
        assertEquals("Update", accentName(src, accents(src).first()))
    }

    @Test
    fun `constructor is a function too`() {
        val src = "public Spawner(int count) {\n}"
        assertEquals("Spawner", accentName(src, accents(src).first()))
        assertEquals("ctor", accents(src).first().keyword)
    }

    @Test
    fun `constructor with a base call initializer on its own line keeps its name`() {
        val src = "public HttpService()\n    : base(Interface)\n{\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("HttpService", accentName(src, accents(src).first()))
        assertEquals("ctor", accents(src).first().keyword)
    }

    @Test
    fun `constructor with a this call initializer on its own line keeps its name`() {
        val src = "public Spawner()\n    : this(1)\n{\n}"
        assertEquals("Spawner", accentName(src, accents(src).first()))
        assertEquals("ctor", accents(src).first().keyword)
    }

    @Test
    fun `static constructor is its own keyword`() {
        val src = "static Spawner() {\n}"
        assertEquals("Spawner", accentName(src, accents(src).first()))
        assertEquals("static ctor", accents(src).first().keyword)
    }

    @Test
    fun `destructor is recognised and named after its type`() {
        val src = "~Spawner() {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Spawner", accentName(src, accents(src).first()))
        assertEquals("dtor", accents(src).first().keyword)
    }

    @Test
    fun `destructor in Allman style keeps its name`() {
        val src = "~Spawner()\n{\n}"
        assertEquals("Spawner", accentName(src, accents(src).first()))
        assertEquals("dtor", accents(src).first().keyword)
    }

    @Test
    fun `generic method takes the name before the angle brackets`() {
        val src = "public T Resolve<T>(string key) {\n}"
        assertEquals("Resolve", accentName(src, accents(src).first()))
    }

    @Test
    fun `where constraints do not interfere`() {
        val src = "public void Bind<T>(T value) where T : class {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Bind", accentName(src, accents(src).first()))
    }

    @Test
    fun `a default parameter value's equals sign is not mistaken for an initializer assignment`() {
        val src = "public void Handle(int id, string reason = \"\") {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Handle", accentName(src, accents(src).first()))
    }

    @Test
    fun `a constructor with several default parameter values is still recognised`() {
        val src = "public HttpResponse(\n" +
            "    TResponse response,\n" +
            "    long responseCode,\n" +
            "    ErrorType errorType = ErrorType.None,\n" +
            "    string errorMessage = \"\",\n" +
            "    string errorCode = \"\")\n" +
            "{\n" +
            "}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("ctor", accents(src).first().keyword)
        assertEquals("HttpResponse", accentName(src, accents(src).first()))
    }

    @Test
    fun `multi-line signature is recognised`() {
        val src = "private static void Handle(\n    int id,\n    bool flag) {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Handle", accentName(src, accents(src).first()))
    }

    @Test
    fun `nested class and its methods`() {
        val src = "class Outer {\n    class Inner {\n        void M() {\n        }\n    }\n}"
        assertEquals(
            listOf(
                '{' to BlockKind.TYPE,
                '{' to BlockKind.TYPE,
                '{' to BlockKind.FUNCTION,
                '}' to BlockKind.FUNCTION,
                '}' to BlockKind.TYPE,
                '}' to BlockKind.TYPE,
            ),
            accentKinds(src),
        )
    }

    @Test
    fun `two braces on one line do not get confused`() {
        // the second brace's header starts after the first, otherwise `class` would leak into the method
        val src = "class A { void M() {\n} }"
        assertEquals(
            listOf('{' to BlockKind.TYPE, '{' to BlockKind.FUNCTION),
            accentKinds(src).take(2),
        )
    }

    // --- what must NOT be accented ---

    @Test
    fun `control constructs are not accented`() {
        for (src in listOf(
            "if (x) {\n}",
            "for (int i = 0; i < n; i++) {\n}",
            "foreach (var a in b) {\n}",
            "while (x) {\n}",
            "switch (x) {\n}",
            "try {\n}",
            "lock (gate) {\n}",
            "using (var s = Open()) {\n}",
        )) {
            assertTrue(src, accents(src).isEmpty())
        }
    }

    @Test
    fun `initializers are not accented`() {
        assertTrue(accents("var a = new Foo() {\n};").isEmpty())
        assertTrue(accents("return new Foo() {\n};").isEmpty())
        assertTrue(accents("var list = new List<int> {\n};").isEmpty())
    }

    // --- lambdas count as functions and take the nearest meaningful name ---

    @Test
    fun `lambda takes the name of the method it is passed to`() {
        val src = "Run(() => {\n});"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Run", accentName(src, accents(src).first()))
        assertEquals("fun", accents(src).first().keyword)
    }

    @Test
    fun `lambda takes the name of the assignment target`() {
        val src = "Action handler = () => {\n};"
        assertEquals("handler", accentName(src, accents(src).first()))
    }

    @Test
    fun `lambda in a chain takes the last unclosed call`() {
        val src = "var r = items.Where(x => x > 0).Select(y => {\n});"
        assertEquals("Select", accentName(src, accents(src).first()))
    }

    @Test
    fun `lambda after return is still a function`() {
        val src = "return items.Select(x => {\n});"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Select", accentName(src, accents(src).first()))
    }

    @Test
    fun `anonymous method via delegate`() {
        assertEquals(BlockKind.FUNCTION, accents("Run(delegate {\n});").first().kind)
        assertEquals(BlockKind.FUNCTION, accents("Run(delegate(int x) {\n});").first().kind)
    }

    // --- label and block span ---

    @Test
    fun `label keyword`() {
        assertEquals("class", accents("class A {\n}").first().keyword)
        assertEquals("struct", accents("struct A {\n}").first().keyword)
        assertEquals("interface", accents("interface A {\n}").first().keyword)
        assertEquals("enum", accents("enum A {\n}").first().keyword)
        assertEquals("fun", accents("void M() {\n}").first().keyword)
    }

    @Test
    fun `name length allows slicing it out`() {
        val src = "public class IosHttpClient {\n}"
        val accent = accents(src).first()
        assertEquals(
            "IosHttpClient",
            src.substring(accent.nameOffset, accent.nameOffset + accent.nameLength),
        )
    }

    @Test
    fun `block span is counted in lines`() {
        val src = "class A {\n" + "    // line\n".repeat(9) + "}"
        val closing = accents(src).first { !it.isOpening }
        assertEquals(10, closing.spannedLines)
        assertEquals(0, accents(src).first { it.isOpening }.spannedLines)
    }

    @Test
    fun `types and functions toggle independently`() {
        val src = "class A {\n    void M() {\n    }\n}"
        val onlyTypes = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentFunctions = false)).scan()
        assertTrue(onlyTypes.accents.all { it.kind == BlockKind.TYPE })

        val onlyFunctions = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentTypes = false)).scan()
        assertTrue(onlyFunctions.accents.all { it.kind == BlockKind.FUNCTION })
        // `class A` itself must stay invisible, not fall into the new property fallback and
        // come out mislabeled as `prop A`.
        assertEquals(2, onlyFunctions.accents.size)
    }

    @Test
    fun `a class header does not fall into the property fallback when types are off`() {
        val options = ScanOptions(accentTypes = false, accentFunctions = true)
        val result = BraceScanner("class A {\n}", Flavor.CSHARP, options).scan()
        assertTrue(result.accents.isEmpty())
    }

    @Test
    fun `a namespace header does not fall into the property fallback when namespaces are off`() {
        val options = ScanOptions(accentNamespaces = false, accentFunctions = true, accentTypes = false)
        val result = BraceScanner("namespace Foo {\n}", Flavor.CSHARP, options).scan()
        assertTrue(result.accents.isEmpty())
    }

    @Test
    fun `an auto-property with no accessor body is still accented as prop, just once`() {
        // `get;` and `set;` never open a brace of their own, but the property's own `{ }` does.
        val src = "public int Count { get; set; }"
        assertEquals(listOf('{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION), accentKinds(src))
        assertEquals("prop", accents(src).first().keyword)
        assertEquals("Count", accentName(src, accents(src).first()))
    }

    @Test
    fun `a property with a block body is accented as prop, get and set`() {
        val src = "public int Count {\n    get {\n        return 1;\n    }\n    set {\n        _c = value;\n    }\n}"
        assertEquals(
            listOf(
                '{' to BlockKind.FUNCTION,
                '{' to BlockKind.FUNCTION,
                '}' to BlockKind.FUNCTION,
                '{' to BlockKind.FUNCTION,
                '}' to BlockKind.FUNCTION,
                '}' to BlockKind.FUNCTION,
            ),
            accentKinds(src),
        )
        val prop = accents(src).first { it.isOpening }
        assertEquals("prop", prop.keyword)
        assertEquals("Count", accentName(src, prop))

        val keywords = accents(src).filter { it.isOpening }.map { it.keyword }
        assertEquals(listOf("prop", "get", "set"), keywords)
    }

    @Test
    fun `a private setter keeps the accessor keyword, not the modifier`() {
        val src = "public int Count {\n    get {\n        return 1;\n    }\n    private set {\n        _c = value;\n    }\n}"
        val setter = accents(src).first { it.isOpening && it.keyword == "set" }
        assertEquals("", accentName(src, setter))
    }

    @Test
    fun `init accessor is recognised too`() {
        val src = "public int Count {\n    get {\n        return 1;\n    }\n    init {\n        _c = value;\n    }\n}"
        val keywords = accents(src).filter { it.isOpening }.map { it.keyword }
        assertEquals(listOf("prop", "get", "init"), keywords)
    }

    @Test
    fun `an accessor has no name of its own`() {
        val src = "public int Count {\n    get {\n        return 1;\n    }\n}"
        val getter = accents(src).first { it.isOpening && it.keyword == "get" }
        assertEquals(-1, getter.nameOffset)
        assertEquals(0, getter.nameLength)
    }

    @Test
    fun `an accessor is nested inside its own property, like any function in a function`() {
        val src = "public int Count {\n    get {\n        return 1;\n    }\n}"
        val getter = accents(src).first { it.isOpening && it.keyword == "get" }
        assertTrue(getter.isNested)
    }

    @Test
    fun `a property is never numbered as a sibling`() {
        val src = "public int A {\n    get {\n        return 1;\n    }\n}\n" +
            "public int B {\n    get {\n        return 2;\n    }\n}"
        assertTrue(accents(src).all { it.siblingOrdinal == 0 })
    }

    @Test
    fun `namespace is its own kind, neither TYPE nor FUNCTION`() {
        val namespaceAccents = accents("namespace Com.Hitapps.Core {\n}")
        assertTrue(namespaceAccents.isNotEmpty())
        assertTrue(namespaceAccents.all { it.kind == BlockKind.NAMESPACE })
    }

    @Test
    fun `the word class inside a literal does not make a type`() {
        assertTrue(accents("Log(\"class A\");\nif (x) {\n}").isEmpty())
    }

    @Test
    fun `disabled accenting produces no marks`() {
        val options = ScanOptions(accentTypes = false, accentFunctions = false)
        val result = BraceScanner("class A {\n}", Flavor.CSHARP, options).scan()
        assertTrue(result.accents.isEmpty())
    }

    @Test
    fun `unbalanced braces do not crash the stack`() {
        val result = BraceScanner("}\n}\nclass A {\n", Flavor.CSHARP, ScanOptions()).scan()
        assertEquals(1, result.accents.size)
    }

    // --- other languages ---

    @Test
    fun `kotlin fun and class`() {
        val src = "class Foo {\n    fun bar() {\n    }\n}"
        assertEquals(
            listOf('{' to BlockKind.TYPE, '{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION, '}' to BlockKind.TYPE),
            accentKinds(src, Flavor.JVM),
        )
    }

    @Test
    fun `go struct has the name before the keyword`() {
        val src = "type Point struct {\n}"
        assertEquals(BlockKind.TYPE, accents(src, Flavor.WEB).first().kind)
        assertEquals("Point", accentName(src, accents(src, Flavor.WEB).first()))
    }


    // --- code already written in Allman: the header is on the line above ---

    @Test
    fun `class in Allman style`() {
        val src = "public class Spawner\n{\n}"
        assertEquals(listOf('{' to BlockKind.TYPE, '}' to BlockKind.TYPE), accentKinds(src))
        assertEquals("Spawner", accentName(src, accents(src).first()))
    }

    @Test
    fun `method in Allman style`() {
        val src = "private void Update()\n{\n}"
        assertEquals(listOf('{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION), accentKinds(src))
        assertEquals("Update", accentName(src, accents(src).first()))
    }

    @Test
    fun `multi-line signature in Allman style`() {
        val src = "static void Handle(\n    int id,\n    bool flag)\n{\n}"
        assertEquals("Handle", accentName(src, accents(src).first()))
    }

    @Test
    fun `control constructs in Allman style are not accented`() {
        assertTrue(accents("if (x)\n{\n}").isEmpty())
        assertTrue(accents("foreach (var a in b)\n{\n}").isEmpty())
        assertTrue(accents("try\n{\n}").isEmpty())
        assertTrue(accents("var a = new Foo()\n{\n};").isEmpty())
    }

    @Test
    fun `property with a body in Allman style is accented too`() {
        val src = "public int Count\n{\n    get\n    {\n        return 1;\n    }\n}"
        val keywords = accents(src).filter { it.isOpening }.map { it.keyword }
        assertEquals(listOf("prop", "get"), keywords)
        assertEquals("Count", accentName(src, accents(src).first { it.isOpening }))
    }

    @Test
    fun `anchor points at the end of the line`() {
        val src = "if (x) { // c\n}"
        assertEquals(src.indexOf('\n'), scan(src).single().anchorOffset)
    }

    // ------------------------------------------------------- nesting, for the fences

    private fun closing(src: String): List<BraceAccent> {
        return accents(src).filter { !it.isOpening }
    }

    @Test
    fun `a top level type is not nested`() {
        val src = "class Outer\n{\n}"
        assertEquals(false, closing(src).single().isNested)
    }

    @Test
    fun `a type inside a type is nested`() {
        val src = "class Outer\n{\n    class Inner\n    {\n    }\n}"
        val nested = closing(src).filter { it.isNested }
        assertEquals(1, nested.size)
        assertEquals("Inner", accentName(src, nested.single()))
    }

    @Test
    fun `nesting counts at any depth`() {
        val src = "class A\n{\n    class B\n    {\n        class C\n        {\n        }\n    }\n}"
        assertEquals(2, closing(src).count { it.isNested })
    }

    @Test
    fun `a method inside a class is not a nested function`() {
        val src = "class A\n{\n    void M()\n    {\n    }\n}"
        val method = closing(src).single { it.kind == BlockKind.FUNCTION }
        assertEquals(false, method.isNested)
    }

    @Test
    fun `a local function inside a method is a nested function`() {
        val src = "class A\n{\n    void M()\n    {\n        void Local()\n        {\n        }\n    }\n}"
        val nested = closing(src).filter { it.isNested }
        assertEquals(1, nested.size)
        assertEquals(BlockKind.FUNCTION, nested.single().kind)
        assertEquals("Local", accentName(src, nested.single()))
    }

    @Test
    fun `a lambda is marked as a lambda and a declared function is not`() {
        val lambda = "void M()\n{\n    Run(() =>\n    {\n    });\n}"
        assertEquals(true, closing(lambda).single { it.isNested }.isLambda)

        val declared = "void M()\n{\n    void Local()\n    {\n    }\n}"
        assertEquals(false, closing(declared).single { it.isNested }.isLambda)
    }

    /** The fence goes above the line the signature starts on, not above the brace line. */
    @Test
    fun `header offset points at the declaration line, not at the brace line`() {
        val src = "class A\n{\n    void Handle(\n        int id)\n    {\n    }\n}"
        val method = accents(src).single { it.isOpening && it.kind == BlockKind.FUNCTION }

        val lineStart = src.lastIndexOf('\n', method.headerOffset - 1) + 1
        assertEquals(lineStart, method.headerOffset)
        assertTrue(
            "header offset must land on the signature line",
            src.startsWith("    void Handle(", method.headerOffset),
        )
    }

    @Test
    fun `a block left unclosed reports nothing about nesting`() {
        val src = "class A\n{\n    class B\n    {\n"
        assertTrue(closing(src).isEmpty())
    }

    // ----------------------------------------- bracket depth is scoped to its own block

    /**
     * A lambda passed to a still-unclosed outer call must not read that call's own header,
     * comments included, for every construct inside its body -- see BraceScanner.openBlock.
     */
    @Test
    fun `an if inside a lambda passed to an unclosed call is not accented`() {
        val src = "" +
            "Register(\n" +
            "    x,\n" +
            "    () =>\n" +
            "    {\n" +
            "        // a comment mentioning class and that word right before it\n" +
            "        if (ok)\n" +
            "        {\n" +
            "            return;\n" +
            "        }\n" +
            "    }\n" +
            ");"
        val inner = accents(src).filter { it.kind != BlockKind.FUNCTION || it.keyword != "fun" || !it.isLambda }
        assertTrue(inner.isEmpty())
    }

    /**
     * A block never remembers an accent for its own sake -- OTHER kind never gets one. This
     * only proves the multi-line `if` was not misread as a TYPE or FUNCTION: the sole accent
     * pair left is the enclosing lambda's own.
     */
    @Test
    fun `a multi-line if inside such a lambda still classifies as other`() {
        val src = "" +
            "Register(\n" +
            "    x,\n" +
            "    () =>\n" +
            "    {\n" +
            "        if (a\n" +
            "            && b)\n" +
            "        {\n" +
            "            return;\n" +
            "        }\n" +
            "    }\n" +
            ");"
        assertEquals(2, accents(src).size)
        assertTrue(accents(src).all { it.isLambda })
    }

    @Test
    fun `a nested function inside such a lambda is still found and named`() {
        val src = "" +
            "Register(\n" +
            "    x,\n" +
            "    () =>\n" +
            "    {\n" +
            "        void Local()\n" +
            "        {\n" +
            "        }\n" +
            "    }\n" +
            ");"
        val local = accents(src).single { it.isOpening && !it.isLambda }
        assertEquals("Local", accentName(src, local))
    }

    // ----------------------------------------- namespace is a block kind of its own

    @Test
    fun `a namespace produces one opening and one closing accent`() {
        val src = "" +
            "namespace Foo.Bar\n" +
            "{\n" +
            "    class A\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val namespaceAccents = accents(src).filter { it.kind == BlockKind.NAMESPACE }
        assertEquals(2, namespaceAccents.size)
        assertTrue(namespaceAccents.any { it.isOpening })
        assertTrue(namespaceAccents.any { !it.isOpening })
    }

    @Test
    fun `a namespace label is the bare ns, with no name recorded`() {
        val src = "namespace Foo\n{\n}\n"
        val closing = accents(src).single { it.kind == BlockKind.NAMESPACE && !it.isOpening }
        assertEquals("ns", closing.keyword)
        assertEquals(0, closing.nameLength)
        assertEquals("", accentName(src, closing))
    }

    @Test
    fun `accentNamespaces=false finds no namespace accents at all`() {
        val src = "namespace Foo\n{\n}\n"
        val options = ScanOptions(accentNamespaces = false)
        val result = BraceScanner(src, Flavor.CSHARP, options).scan()
        assertTrue(result.accents.none { it.kind == BlockKind.NAMESPACE })
    }

    @Test
    fun `namespace detection does not depend on accentTypes or accentFunctions`() {
        val src = "namespace Foo\n{\n}\n"
        val options = ScanOptions(accentTypes = false, accentFunctions = false, accentNamespaces = true)
        val result = BraceScanner(src, Flavor.CSHARP, options).scan()
        assertEquals(2, result.accents.count { it.kind == BlockKind.NAMESPACE })
    }

    @Test
    fun `a type directly inside a namespace is not nested -- the kinds differ`() {
        val src = "" +
            "namespace Foo\n" +
            "{\n" +
            "    class A\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val classClosing = accents(src).single { it.kind == BlockKind.TYPE && !it.isOpening }
        assertTrue(!classClosing.isNested)
    }

    @Test
    fun `a namespace inside a namespace is nested, like any other kind`() {
        val src = "" +
            "namespace Outer\n" +
            "{\n" +
            "    namespace Inner\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val closings = accents(src).filter { it.kind == BlockKind.NAMESPACE && !it.isOpening }
        assertEquals(2, closings.size)
        assertEquals(1, closings.count { it.isNested })
    }

    @Test
    fun `a namespace keeps the offset of its declaration line, not of its brace`() {
        val src = "namespace Foo\n{\n}\n"
        val opening = accents(src).single { it.kind == BlockKind.NAMESPACE && it.isOpening }
        assertTrue(src.startsWith("namespace Foo", opening.headerOffset))
    }

    @Test
    fun `a file-scoped namespace declaration has no braces, so no namespace accent`() {
        val src = "namespace Foo.Bar;\n\nclass A\n{\n}\n"
        assertTrue(accents(src).none { it.kind == BlockKind.NAMESPACE })
        assertTrue(accents(src).any { it.kind == BlockKind.TYPE })
    }

    // --- a `where` clause on its own line, right before the brace, must not eat the header ---

    @Test
    fun `generic method with a multi-line where clause and an Allman brace is still a function`() {
        // The bug: the closing `)` of the parameter list ends the previous statement, so by the
        // time the `where` line is its own line, bracketDepth is already back to 0 and it reads
        // as a brand new (empty) statement -- discarding the whole signature above it.
        val src = "public async UniTask<HttpResponse<TResponse>> Request<TResponse>(\n" +
            "    HttpRequest httpRequest,\n" +
            "    CancellationToken cancellationToken\n" +
            ")\n" +
            "    where TResponse : class\n" +
            "{\n" +
            "}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Request", accentName(src, accents(src).first()))
    }

    @Test
    fun `generic method with a multi-line where clause and an inline brace is still a function`() {
        val src = "public async UniTask<HttpResponse<TResponse>> Request<TResponse>(\n" +
            "    HttpRequest httpRequest,\n" +
            "    CancellationToken cancellationToken\n" +
            ") where TResponse : class {\n" +
            "}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Request", accentName(src, accents(src).first()))
    }

    @Test
    fun `two where clauses on separate lines both stay part of the same header`() {
        val src = "public void Bind<T1, T2>(\n" +
            "    T1 a,\n" +
            "    T2 b)\n" +
            "    where T1 : class\n" +
            "    where T2 : struct\n" +
            "{\n" +
            "}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Bind", accentName(src, accents(src).first()))
    }

    // --- numbering a container's type/namespace siblings: [1], [2], ... ---

    @Test
    fun `two namespaces at the top level are numbered one and two`() {
        val src = "namespace Name1\n{\n}\nnamespace Name2\n{\n}\n"
        val namespaces = accents(src).filter { it.kind == BlockKind.NAMESPACE && it.isOpening }
            .sortedBy { it.offset }
        assertEquals(listOf(1, 2), namespaces.map { it.siblingOrdinal })
    }

    @Test
    fun `the opening and closing brace of a numbered block share the same ordinal`() {
        val src = "namespace Name1\n{\n}\nnamespace Name2\n{\n}\n"
        val namespaceAccents = accents(src).filter { it.kind == BlockKind.NAMESPACE }.sortedBy { it.offset }
        assertEquals(4, namespaceAccents.size)
        val (openName1, closeName1, openName2, closeName2) = namespaceAccents
        assertEquals(1, openName1.siblingOrdinal)
        assertEquals(1, closeName1.siblingOrdinal)
        assertEquals(2, openName2.siblingOrdinal)
        assertEquals(2, closeName2.siblingOrdinal)
    }

    @Test
    fun `a container with only one type or namespace child is not numbered`() {
        val src = "namespace Only\n{\n    class Single\n    {\n    }\n}\n"
        assertTrue(accents(src).all { it.siblingOrdinal == 0 })
    }

    @Test
    fun `an interface, an enum and a class in the same namespace are numbered in order`() {
        val src = "" +
            "namespace Name2\n" +
            "{\n" +
            "    interface IThing\n" +
            "    {\n" +
            "    }\n" +
            "    enum EEEE\n" +
            "    {\n" +
            "    }\n" +
            "    class CLASS\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val children = accents(src)
            .filter { it.isOpening && it.kind != BlockKind.NAMESPACE }
            .sortedBy { it.offset }
        assertEquals(listOf(1, 2, 3), children.map { it.siblingOrdinal })
    }

    @Test
    fun `a single nested type among several functions is still not numbered`() {
        val src = "" +
            "class CLASS\n" +
            "{\n" +
            "    void F()\n" +
            "    {\n" +
            "    }\n" +
            "    class Nested\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        assertTrue(accents(src).none { it.kind == BlockKind.FUNCTION && it.siblingOrdinal != 0 })
        val nested = accents(src).single { it.kind == BlockKind.TYPE && it.isOpening && it.isNested }
        assertEquals(0, nested.siblingOrdinal)
    }

    @Test
    fun `functions interleaved with two nested types do not break the numbering`() {
        val src = "" +
            "class Outer\n" +
            "{\n" +
            "    void Foo()\n" +
            "    {\n" +
            "    }\n" +
            "    class Inner1\n" +
            "    {\n" +
            "    }\n" +
            "    void Bar()\n" +
            "    {\n" +
            "    }\n" +
            "    class Inner2\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val nested = accents(src).filter { it.kind == BlockKind.TYPE && it.isOpening && it.isNested }
            .sortedBy { it.offset }
        assertEquals(listOf("Inner1", "Inner2"), nested.map { accentName(src, it) })
        assertEquals(listOf(1, 2), nested.map { it.siblingOrdinal })
        assertTrue(accents(src).none { it.kind == BlockKind.FUNCTION && it.siblingOrdinal != 0 })
    }

    @Test
    fun `each container numbers its own children independently`() {
        val src = "" +
            "namespace App\n" +
            "{\n" +
            "    class Service\n" +
            "    {\n" +
            "        class Config\n" +
            "        {\n" +
            "        }\n" +
            "        class State\n" +
            "        {\n" +
            "        }\n" +
            "    }\n" +
            "    class Repository\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        val topLevelChildren = accents(src)
            .filter { it.isOpening && it.kind == BlockKind.TYPE && !it.isNested }
            .sortedBy { it.offset }
        assertEquals(listOf("Service", "Repository"), topLevelChildren.map { accentName(src, it) })
        assertEquals(listOf(1, 2), topLevelChildren.map { it.siblingOrdinal })

        val serviceChildren = accents(src)
            .filter { it.isOpening && it.kind == BlockKind.TYPE && it.isNested }
            .sortedBy { it.offset }
        assertEquals(listOf("Config", "State"), serviceChildren.map { accentName(src, it) })
        assertEquals(listOf(1, 2), serviceChildren.map { it.siblingOrdinal })
    }

    // ------------------------------------- each kind of function block has its own switch

    /** Every sub-kind at once, so one source serves all the switch tests below. */
    private fun everyFunctionKind(): String {
        return "" +
            "class Holder\n{\n" +
            "    public string Name\n    {\n" +
            "        get\n        {\n        }\n" +
            "        set\n        {\n        }\n" +
            "    }\n" +
            "    public Holder()\n    {\n    }\n" +
            "    static Holder()\n    {\n    }\n" +
            "    ~Holder()\n    {\n    }\n" +
            "    void Work()\n    {\n        Run(() =>\n        {\n        });\n    }\n" +
            "}\n"
    }

    private fun openingKeywords(src: String, options: ScanOptions): List<String> {
        return BraceScanner(src, Flavor.CSHARP, options).scan()
            .accents
            .filter { it.isOpening }
            .map { it.keyword }
    }

    @Test
    fun `by default every kind of function block is reported`() {
        val keywords = openingKeywords(everyFunctionKind(), ScanOptions())
        assertTrue("prop" in keywords)
        assertTrue("get" in keywords)
        assertTrue("set" in keywords)
        assertTrue("ctor" in keywords)
        assertTrue("static ctor" in keywords)
        assertTrue("dtor" in keywords)
        assertTrue("fun" in keywords)
    }

    @Test
    fun `accentAccessors=false drops get and set but keeps the property itself`() {
        val keywords = openingKeywords(everyFunctionKind(), ScanOptions(accentAccessors = false))
        assertTrue("get" !in keywords)
        assertTrue("set" !in keywords)
        assertTrue("prop" in keywords)
    }

    @Test
    fun `accentProperties=false drops the property but keeps its accessors`() {
        val keywords = openingKeywords(everyFunctionKind(), ScanOptions(accentProperties = false))
        assertTrue("prop" !in keywords)
        assertTrue("get" in keywords)
        assertTrue("set" in keywords)
    }

    @Test
    fun `accentConstructors=false drops ctor, static ctor and dtor but keeps methods`() {
        val keywords = openingKeywords(everyFunctionKind(), ScanOptions(accentConstructors = false))
        assertTrue("ctor" !in keywords)
        assertTrue("static ctor" !in keywords)
        assertTrue("dtor" !in keywords)
        assertTrue("fun" in keywords)
    }

    @Test
    fun `accentMethods=false drops methods but keeps constructors`() {
        val src = everyFunctionKind()
        val accents = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentMethods = false))
            .scan().accents.filter { it.isOpening }
        assertTrue(accents.none { it.keyword == "fun" && !it.isLambda })
        assertTrue(accents.any { it.keyword == "ctor" })
    }

    @Test
    fun `accentLambdas=false drops the lambda but keeps the method holding it`() {
        val src = everyFunctionKind()
        val accents = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentLambdas = false))
            .scan().accents.filter { it.isOpening }
        assertTrue(accents.none { it.isLambda })
        assertTrue(accents.any { it.keyword == "fun" })
    }

    @Test
    fun `a rejected sub-kind is not a block at all, so it cannot make a sibling nested`() {
        // The property is gone, so its accessors are no longer nested inside anything of their
        // own kind -- proof the switch removes the block rather than only its label.
        val src = everyFunctionKind()
        val accents = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentProperties = false))
            .scan().accents.filter { it.isOpening && it.keyword == "get" }
        assertEquals(1, accents.size)
        assertTrue(!accents[0].isNested)
    }

    // ============================================================ accessors and properties

    private fun openings(src: String, options: ScanOptions = ScanOptions()): List<BraceAccent> {
        return BraceScanner(src, Flavor.CSHARP, options).scan().accents.filter { it.isOpening }
    }

    private fun propertyWithBody(): String {
        return "" +
            "class C\n{\n" +
            "    public int A\n    {\n" +
            "        get\n        {\n        }\n" +
            "        set\n        {\n        }\n" +
            "    }\n}\n"
    }

    @Test
    fun `a property with a body reports itself and both of its accessors`() {
        val keywords = openings(propertyWithBody()).map { it.keyword }
        assertEquals(listOf("class", "prop", "get", "set"), keywords)
    }

    @Test
    fun `get and set are flagged as accessors`() {
        val accessors = openings(propertyWithBody()).filter { it.isAccessor }
        assertEquals(2, accessors.size)
        assertEquals(listOf("get", "set"), accessors.map { it.keyword })
    }

    @Test
    fun `an accessor sits inside its property, so it is nested`() {
        val accessors = openings(propertyWithBody()).filter { it.isAccessor }
        assertTrue(accessors.all { it.isNested })
    }

    @Test
    fun `the property itself is not an accessor`() {
        val property = openings(propertyWithBody()).single { it.keyword == "prop" }
        assertTrue(!property.isAccessor)
    }

    @Test
    fun `a method is never an accessor`() {
        val src = "class C\n{\n    void M()\n    {\n    }\n}\n"
        assertTrue(openings(src).none { it.isAccessor })
    }

    @Test
    fun `a lambda is never an accessor`() {
        val src = "class C\n{\n    void M()\n    {\n        Run(() =>\n        {\n        });\n    }\n}\n"
        val lambda = openings(src).single { it.isLambda }
        assertTrue(!lambda.isAccessor)
    }

    @Test
    fun `init is an accessor like get and set`() {
        val src = "class C\n{\n    public int A\n    {\n        init\n        {\n        }\n    }\n}\n"
        val accessor = openings(src).single { it.isAccessor }
        assertEquals("init", accessor.keyword)
    }

    @Test
    fun `an accessor keeps its flag when a modifier precedes it`() {
        val src = "class C\n{\n    public int A\n    {\n        private set\n        {\n        }\n    }\n}\n"
        val accessor = openings(src).single { it.isAccessor }
        assertEquals("set", accessor.keyword)
    }

    @Test
    fun `an auto-property has no accessor bodies, so only the property is reported`() {
        val src = "class C\n{\n    public int A { get; set; }\n}\n"
        val keywords = openings(src).map { it.keyword }
        assertEquals(listOf("class", "prop"), keywords)
    }

    @Test
    fun `an expression-bodied property has no braces and so no block`() {
        val src = "class C\n{\n    public int A => _a;\n}\n"
        assertEquals(listOf("class"), openings(src).map { it.keyword })
    }

    @Test
    fun `a property records its own name`() {
        val src = propertyWithBody()
        val property = openings(src).single { it.keyword == "prop" }
        assertEquals("A", accentName(src, property))
    }

    @Test
    fun `an accessor records no name -- it belongs to the property, it does not name a symbol`() {
        val accessor = openings(propertyWithBody()).first { it.isAccessor }
        assertEquals(-1, accessor.nameOffset)
        assertEquals(0, accessor.nameLength)
    }

    // ============================================================ constructors and destructors

    @Test
    fun `a constructor, a static constructor and a destructor each get their own keyword`() {
        val src = "" +
            "class C\n{\n" +
            "    public C()\n    {\n    }\n" +
            "    static C()\n    {\n    }\n" +
            "    ~C()\n    {\n    }\n}\n"
        val keywords = openings(src).map { it.keyword }
        assertEquals(listOf("class", "ctor", "static ctor", "dtor"), keywords)
    }

    @Test
    fun `a constructor with a base initializer on its own line keeps its name`() {
        val src = "class C\n{\n    public C(int a)\n        : base(a)\n    {\n    }\n}\n"
        val constructor = openings(src).single { it.keyword == "ctor" }
        assertEquals("C", accentName(src, constructor))
    }

    @Test
    fun `a destructor records the type name it belongs to`() {
        val src = "class C\n{\n    ~C()\n    {\n    }\n}\n"
        val destructor = openings(src).single { it.keyword == "dtor" }
        assertEquals("C", accentName(src, destructor))
    }

    // ============================================================ methods

    @Test
    fun `an expression-bodied method has no braces and so no block`() {
        val src = "class C\n{\n    public int A() => _a;\n}\n"
        assertEquals(listOf("class"), openings(src).map { it.keyword })
    }

    @Test
    fun `a default parameter value keeps the method recognisable`() {
        val src = "class C\n{\n    public void M(string a = \"x\", int b = 5)\n    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("M", accentName(src, method))
    }

    @Test
    fun `an attribute above a method does not hide it`() {
        val src = "class C\n{\n    [Test]\n    public void M()\n    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("M", accentName(src, method))
    }

    @Test
    fun `a local function inside a method is nested`() {
        val src = "class C\n{\n    void M()\n    {\n        void Local()\n        {\n        }\n    }\n}\n"
        val local = openings(src).single { accentName(src, it) == "Local" }
        assertTrue(local.isNested)
    }

    @Test
    fun `a method directly inside a class is not nested`() {
        val src = "class C\n{\n    void M()\n    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertTrue(!method.isNested)
    }

    // ============================================================ lambdas

    @Test
    fun `a lambda assigned to a field borrows the target's name and is not nested there`() {
        val src = "class C\n{\n    Action a = () =>\n    {\n    };\n}\n"
        val lambda = openings(src).single { it.isLambda }
        assertEquals("a", accentName(src, lambda))
        assertTrue(!lambda.isNested)
    }

    @Test
    fun `a lambda inside a method is nested and borrows the call's name`() {
        val src = "class C\n{\n    void M()\n    {\n        items.Select(x =>\n        {\n        });\n    }\n}\n"
        val lambda = openings(src).single { it.isLambda }
        assertEquals("Select", accentName(src, lambda))
        assertTrue(lambda.isNested)
    }

    // ============================================================ types and namespaces

    @Test
    fun `each type keyword is reported as written`() {
        val src = "" +
            "class A\n{\n}\n" +
            "struct B\n{\n}\n" +
            "interface C\n{\n}\n" +
            "enum D\n{\n}\n" +
            "record E\n{\n}\n"
        assertEquals(listOf("class", "struct", "interface", "enum", "record"),
            openings(src).map { it.keyword })
    }

    @Test
    fun `a record struct is still one type`() {
        val src = "record struct Point\n{\n}\n"
        val type = openings(src).single()
        assertEquals(BlockKind.TYPE, type.kind)
        assertEquals("Point", accentName(src, type))
    }

    @Test
    fun `a type inside a namespace is not nested -- a namespace is a different kind`() {
        val src = "namespace N\n{\n    class C\n    {\n    }\n}\n"
        val type = openings(src).single { it.kind == BlockKind.TYPE }
        assertTrue(!type.isNested)
    }

    @Test
    fun `a namespace inside a namespace is nested`() {
        val src = "namespace A\n{\n    namespace B\n    {\n    }\n}\n"
        val inner = openings(src).last()
        assertEquals(BlockKind.NAMESPACE, inner.kind)
        assertTrue(inner.isNested)
    }

    // ============================================================ literals and comments

    @Test
    fun `a type keyword inside a string literal declares nothing`() {
        val src = "class C\n{\n    void M()\n    {\n        Log(\"class Fake {\");\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `a type keyword inside a line comment declares nothing`() {
        val src = "class C\n{\n    // class Fake\n    void M()\n    {\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `a type keyword inside a block comment declares nothing`() {
        val src = "class C\n{\n    /* class Fake { */\n    void M()\n    {\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `a verbatim string holding a brace does not open a block`() {
        val src = "class C\n{\n    void M()\n    {\n        var s = @\"a { b\";\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    // ============================================================ sibling numbering

    @Test
    fun `a lone type child is not numbered`() {
        val src = "namespace N\n{\n    class Only\n    {\n    }\n}\n"
        assertTrue(openings(src).all { it.siblingOrdinal == 0 })
    }

    @Test
    fun `two type children of the same namespace are numbered in order`() {
        val src = "namespace N\n{\n    class A\n    {\n    }\n    class B\n    {\n    }\n}\n"
        val types = openings(src).filter { it.kind == BlockKind.TYPE }
        assertEquals(listOf(1, 2), types.map { it.siblingOrdinal })
    }

    @Test
    fun `a function is never numbered, however many siblings it has`() {
        val src = "class C\n{\n    void A()\n    {\n    }\n    void B()\n    {\n    }\n}\n"
        val functions = openings(src).filter { it.kind == BlockKind.FUNCTION }
        assertTrue(functions.all { it.siblingOrdinal == 0 })
    }

    @Test
    fun `accessors are never numbered`() {
        assertTrue(openings(propertyWithBody()).filter { it.isAccessor }.all { it.siblingOrdinal == 0 })
    }

    @Test
    fun `the closing accent carries the same ordinal as its opening one`() {
        val src = "namespace N\n{\n    class A\n    {\n    }\n    class B\n    {\n    }\n}\n"
        val all = BraceScanner(src, Flavor.CSHARP, ScanOptions()).scan().accents
        val closings = all.filter { !it.isOpening && it.kind == BlockKind.TYPE }
        assertEquals(setOf(1, 2), closings.map { it.siblingOrdinal }.toSet())
    }

    // ============================================================ indexers

    private fun indexerWithBothAccessors(): String {
        return "" +
            "class C\n{\n" +
            "    public int this[int i]\n    {\n" +
            "        get\n        {\n        }\n" +
            "        set\n        {\n        }\n" +
            "    }\n}\n"
    }

    @Test
    fun `an indexer is a property whose name is the this keyword`() {
        val src = indexerWithBothAccessors()
        val indexer = openings(src).single { it.keyword == "prop" }
        assertEquals("this", accentName(src, indexer))
    }

    @Test
    fun `an indexer's accessors sit inside it, so they are nested`() {
        val accessors = openings(indexerWithBothAccessors()).filter { it.isAccessor }
        assertEquals(2, accessors.size)
        assertTrue(accessors.all { it.isNested })
    }

    @Test
    fun `an explicitly implemented indexer is still an indexer`() {
        val src = "class C\n{\n    int IFoo.this[int i]\n    {\n        get\n        {\n        }\n    }\n}\n"
        val indexer = openings(src).single { it.keyword == "prop" }
        assertEquals("this", accentName(src, indexer))
    }

    @Test
    fun `a default argument value does not hide an indexer`() {
        // The `=` inside the brackets used to read as an initializer, which rejected the header.
        val src = "class C\n{\n    public int this[int i = 5]\n    {\n        get\n        {\n        }\n    }\n}\n"
        assertEquals(1, openings(src).count { it.keyword == "prop" })
    }

    @Test
    fun `an indexer taking several parameters is still one property`() {
        val src = "class C\n{\n    public int this[int x, int y]\n    {\n        get\n        {\n        }\n    }\n}\n"
        assertEquals(1, openings(src).count { it.keyword == "prop" })
    }

    @Test
    fun `an indexer is covered by the properties switch`() {
        val keywords = openings(indexerWithBothAccessors(), ScanOptions(accentProperties = false))
            .map { it.keyword }
        assertTrue("prop" !in keywords)
    }

    @Test
    fun `an attribute on its own line is not an indexer`() {
        val src = "class C\n{\n    [Test]\n    public void M()\n    {\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `an array initializer is not an indexer`() {
        val src = "class C\n{\n    void M()\n    {\n        var x = new[]\n        {\n            1\n        };\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `a collection initializer is not an indexer`() {
        val src = "class C\n{\n    void M()\n    {\n        var d = new Dictionary<int, int>\n        {\n        };\n    }\n}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
    }

    @Test
    fun `an array-typed property keeps its own name, not this`() {
        val src = "class C\n{\n    public int[] Values\n    {\n        get\n        {\n        }\n    }\n}\n"
        val property = openings(src).single { it.keyword == "prop" }
        assertEquals("Values", accentName(src, property))
    }

    // --- switch permutations -------------------------------------------------------------
    //
    // The tests above turn one switch off at a time. These turn every switch off in every
    // combination -- 256 of them -- because the bugs that actually shipped were never a single
    // switch misbehaving: they were one switch changing what another one did.

    /** One of every kind of block the scanner knows, so a single scan can be asked about all. */
    private fun oneOfEveryBlockKind(): String {
        return "namespace Space\n" +
            "{\n" +
            "    class Holder\n" +
            "    {\n" +
            "        struct Inner\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        static Holder()\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        public Holder()\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        ~Holder()\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        public int Value\n" +
            "        {\n" +
            "            get\n" +
            "            {\n" +
            "            }\n" +
            "        }\n" +
            "\n" +
            "        public static Holder operator +(Holder a, Holder b)\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        public void Method()\n" +
            "        {\n" +
            "            Run(() =>\n" +
            "            {\n" +
            "            });\n" +
            "        }\n" +
            "    }\n" +
            "}\n"
    }

    /**
     * What to call a block in these tests: its keyword, except a lambda, which shares `fun`
     * with an ordinary method and would otherwise be indistinguishable from one.
     */
    private fun blockIdentity(accent: BraceAccent): String {
        if (accent.isLambda) {
            return "lambda"
        }
        return accent.keyword
    }

    /**
     * Every block in [oneOfEveryBlockKind], in document order, next to the switch that decides
     * whether the scanner reports it at all.
     *
     * Written out by hand on purpose. The point of the permutation tests is to disagree with
     * the classifier when it is wrong, which it cannot do if it gets the answer from the same
     * code it is checking.
     */
    private fun blockSwitchOwners(): List<Pair<String, (ScanOptions) -> Boolean>> {
        return listOf(
            "ns" to { options: ScanOptions -> options.accentNamespaces },
            "class" to { options: ScanOptions -> options.accentTypes },
            "struct" to { options: ScanOptions -> options.accentTypes },
            "static ctor" to { options: ScanOptions ->
                options.accentFunctions && options.accentConstructors
            },
            "ctor" to { options: ScanOptions ->
                options.accentFunctions && options.accentConstructors
            },
            "dtor" to { options: ScanOptions ->
                options.accentFunctions && options.accentConstructors
            },
            "prop" to { options: ScanOptions ->
                options.accentFunctions && options.accentProperties
            },
            "get" to { options: ScanOptions ->
                options.accentFunctions && options.accentAccessors
            },
            // An operator shares the methods switch: its own label, but a method's switch.
            "op" to { options: ScanOptions -> options.accentFunctions && options.accentMethods },
            "fun" to { options: ScanOptions -> options.accentFunctions && options.accentMethods },
            "lambda" to { options: ScanOptions ->
                options.accentFunctions && options.accentLambdas
            },
        )
    }

    /** Bit `n` of [mask] is the nth switch, so counting to 256 walks every combination once. */
    private fun switchCombination(mask: Int): ScanOptions {
        return ScanOptions(
            accentTypes = mask shr 0 and 1 == 1,
            accentFunctions = mask shr 1 and 1 == 1,
            accentMethods = mask shr 2 and 1 == 1,
            accentConstructors = mask shr 3 and 1 == 1,
            accentProperties = mask shr 4 and 1 == 1,
            accentAccessors = mask shr 5 and 1 == 1,
            accentLambdas = mask shr 6 and 1 == 1,
            accentNamespaces = mask shr 7 and 1 == 1,
        )
    }

    /**
     * Which combination this is, in words. The stub-friendly `assertEquals(String, String)` has
     * nowhere to put a message, so the combination is folded into both sides of the comparison
     * instead: a failure then names the switches that produced it rather than leaving 256
     * indistinguishable runs to guess between.
     */
    private fun describeSwitches(options: ScanOptions): String {
        return "types=" + options.accentTypes +
            " functions=" + options.accentFunctions +
            " methods=" + options.accentMethods +
            " constructors=" + options.accentConstructors +
            " properties=" + options.accentProperties +
            " accessors=" + options.accentAccessors +
            " lambdas=" + options.accentLambdas +
            " namespaces=" + options.accentNamespaces
    }

    private fun openingIdentities(src: String, options: ScanOptions): List<String> {
        return BraceScanner(src, Flavor.CSHARP, options).scan().accents
            .filter { it.isOpening }
            .sortedBy { it.offset }
            .map { blockIdentity(it) }
    }

    @Test
    fun `every combination of the block switches reports exactly the blocks it turns on`() {
        val src = oneOfEveryBlockKind()
        val owners = blockSwitchOwners()

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            val expected = owners.filter { it.second(options) }.map { it.first }
            val actual = openingIdentities(src, options)
            assertEquals(
                describeSwitches(options) + " -> " + expected.joinToString(", "),
                describeSwitches(options) + " -> " + actual.joinToString(", "),
            )
        }
    }

    @Test
    fun `every combination reports each block's opening and closing brace together`() {
        val src = oneOfEveryBlockKind()

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            val accents = BraceScanner(src, Flavor.CSHARP, options).scan().accents
            val opened = accents.filter { it.isOpening }.map { blockIdentity(it) }.sorted()
            val closed = accents.filter { !it.isOpening }.map { blockIdentity(it) }.sorted()
            assertEquals(
                describeSwitches(options) + " -> " + opened.joinToString(", "),
                describeSwitches(options) + " -> " + closed.joinToString(", "),
            )
        }
    }

    @Test
    fun `a block leaves the nesting bookkeeping when its parent's switch is off`() {
        val src = oneOfEveryBlockKind()

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            val openings = BraceScanner(src, Flavor.CSHARP, options).scan().accents
                .filter { it.isOpening }

            // The lambda sits in a method. Drop methods and the lambda is still reported, but
            // there is no longer a function around it, so it is not nested in one either.
            val lambda = openings.firstOrNull { it.isLambda }
            if (lambda != null) {
                assertEquals(
                    describeSwitches(options) + " lambda nested=" + options.accentMethods,
                    describeSwitches(options) + " lambda nested=" + lambda.isNested,
                )
            }

            // Same shape one level down: an accessor sits in a property.
            val accessor = openings.firstOrNull { it.isAccessor }
            if (accessor != null) {
                assertEquals(
                    describeSwitches(options) + " accessor nested=" + options.accentProperties,
                    describeSwitches(options) + " accessor nested=" + accessor.isNested,
                )
            }
        }
    }

    @Test
    fun `sibling numbering ignores the switches of kinds that are never numbered`() {
        // Two classes in a namespace, two structs in the second class: every ordinal the file
        // has is a type's, and not one of them is a function's business.
        val src = "namespace Outer\n" +
            "{\n" +
            "    class First\n" +
            "    {\n" +
            "        void M()\n" +
            "        {\n" +
            "        }\n" +
            "    }\n" +
            "\n" +
            "    class Second\n" +
            "    {\n" +
            "        struct A\n" +
            "        {\n" +
            "        }\n" +
            "\n" +
            "        struct B\n" +
            "        {\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            if (!options.accentTypes) {
                continue
            }
            val ordinals = BraceScanner(src, Flavor.CSHARP, options).scan().accents
                .filter { it.isOpening && it.kind == BlockKind.TYPE }
                .sortedBy { it.offset }
                .map { it.siblingOrdinal }
            assertEquals(
                describeSwitches(options) + " -> 1, 2, 1, 2",
                describeSwitches(options) + " -> " + ordinals.joinToString(", "),
            )
        }
    }

    @Test
    fun `the block switches never change the phantom lines`() {
        // K&R on purpose: with the braces already on their own lines there would be nothing to
        // move, and the test would pass without having looked at anything.
        val src = "class Holder {\n" +
            "    public void Method() {\n" +
            "        if (ready) {\n" +
            "            Run(() => {\n" +
            "                Step();\n" +
            "            });\n" +
            "        } else {\n" +
            "            Stop();\n" +
            "        }\n" +
            "    }\n" +
            "}\n"

        val baseline = BraceScanner(src, Flavor.CSHARP, ScanOptions()).scan().sites
        assertTrue("the fixture must actually move something", baseline.isNotEmpty())
        val expected = baseline.sortedBy { it.dimStart }
            .joinToString(" | ") { it.dimStart.toString() + ":" + it.dimEnd }

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            val actual = BraceScanner(src, Flavor.CSHARP, options).scan().sites
                .sortedBy { it.dimStart }
                .joinToString(" | ") { it.dimStart.toString() + ":" + it.dimEnd }
            assertEquals(
                describeSwitches(options) + " -> " + expected,
                describeSwitches(options) + " -> " + actual,
            )
        }
    }


    // --- how many blocks of each kind the file holds ---------------------------------------
    //
    // What the "only in a file that holds more than one" block-span restriction reads. Counted
    // over the whole file, nesting included.

    private fun counts(src: String, options: ScanOptions = ScanOptions()): BlockCounts {
        return BraceScanner(src, Flavor.CSHARP, options).scan().counts
    }

    @Test
    fun `a file with one type and one namespace counts one of each`() {
        val src = "namespace A\n{\n    class C\n    {\n    }\n}\n"
        assertEquals(BlockCounts(types = 1, namespaces = 1), counts(src))
    }

    @Test
    fun `a nested type counts towards the file's total`() {
        // Nesting does not matter: the question is how many the file holds, not how many share
        // a container.
        val src = "namespace A\n{\n    class Outer\n    {\n        struct Inner\n        {\n" +
            "        }\n    }\n}\n"
        assertEquals(BlockCounts(types = 2, namespaces = 1), counts(src))
    }

    @Test
    fun `two namespaces side by side are both counted`() {
        val src = "namespace A\n{\n}\n\nnamespace B\n{\n}\n"
        assertEquals(BlockCounts(types = 0, namespaces = 2), counts(src))
    }

    @Test
    fun `a nested namespace counts too`() {
        assertEquals(2, counts("namespace A\n{\n    namespace B\n    {\n    }\n}\n").namespaces)
    }

    @Test
    fun `functions are not counted as types`() {
        val src = "class C\n{\n    void M()\n    {\n    }\n\n    void N()\n    {\n    }\n}\n"
        assertEquals(BlockCounts(types = 1, namespaces = 0), counts(src))
    }

    @Test
    fun `a keyword inside a string or a comment counts nothing`() {
        assertEquals(BlockCounts(0, 0), counts("// namespace B\n"))
        assertEquals(
            BlockCounts(types = 1, namespaces = 0),
            counts("class C\n{\n    string s = \"namespace B\";\n}\n"),
        )
    }

    @Test
    fun `a file-scoped namespace opens no block and is not counted`() {
        // `namespace Foo;` has no braces, so there is no block to put a span marker on.
        assertEquals(0, counts("namespace A;\n\nclass C\n{\n}\n").namespaces)
    }

    @Test
    fun `a kind that is switched off counts zero, and has no block left to ask about`() {
        val src = "namespace A\n{\n    class C\n    {\n    }\n}\n"
        assertEquals(0, counts(src, ScanOptions(accentTypes = false)).types)
        assertEquals(0, counts(src, ScanOptions(accentNamespaces = false)).namespaces)
    }

    @Test
    fun `the counts hold steady under every switch combination that reports the kind`() {
        val src = "namespace A\n{\n    class First\n    {\n        void M()\n        {\n        }\n" +
            "    }\n\n    class Second\n    {\n    }\n}\n\nnamespace B\n{\n}\n"

        for (mask in 0 until SWITCH_COMBINATIONS) {
            val options = switchCombination(mask)
            val expectedTypes = if (options.accentTypes) 2 else 0
            val expectedNamespaces = if (options.accentNamespaces) 2 else 0
            val actual = counts(src, options)
            assertEquals(
                describeSwitches(options) + " -> $expectedTypes/$expectedNamespaces",
                describeSwitches(options) + " -> " + actual.types + "/" + actual.namespaces,
            )
        }
    }

    // --- a block's length is measured from its declaration ---------------------------------

    private fun closingSpan(src: String, keyword: String): Int {
        return BraceScanner(src, Flavor.CSHARP, ScanOptions()).scan().accents
            .single { !it.isOpening && it.keyword == keyword }
            .spannedLines
    }

    @Test
    fun `a K and R block spans from the line that carries the brace`() {
        // `class C {` and `}`: the declaration and the brace are the same line, so there is
        // nothing to tell apart here -- this is the case that must not change.
        assertEquals(1, closingSpan("class C {\n}\n", "class"))
    }

    @Test
    fun `an Allman block spans from the declaration, not from the brace below it`() {
        // class C / { / } -- three lines, and the declaration is the first of them.
        assertEquals(2, closingSpan("class C\n{\n}\n", "class"))
    }

    @Test
    fun `a signature spread over several lines spans from its first line`() {
        val src = "class C\n" +
            "{\n" +
            "    void M(\n" +
            "        int a,\n" +
            "        int b)\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        // The method is declared on line 3 and closes on line 7; the class, line 1 to line 8.
        assertEquals(4, closingSpan(src, "fun"))
        assertEquals(7, closingSpan(src, "class"))
    }

    @Test
    fun `an attribute on its own line is not part of the block's length`() {
        val src = "class C\n{\n    [Test]\n    void M()\n    {\n    }\n}\n"
        // Declared on line 4, closes on line 6. The attribute above is deliberately left out.
        assertEquals(2, closingSpan(src, "fun"))
    }

    @Test
    fun `a where clause does not become the start of the block`() {
        val src = "class C\n" +
            "{\n" +
            "    void M<T>(T value)\n" +
            "        where T : class\n" +
            "    {\n" +
            "    }\n" +
            "}\n"
        // Line 3 declares it, line 6 closes it: the constraint on line 4 continues the
        // declaration rather than starting one.
        assertEquals(3, closingSpan(src, "fun"))
    }

    // --- a tuple return type must not be read as the parameter list ------------------------

    @Test
    fun `a method returning a tuple keeps its own name`() {
        // The first ( in the header belongs to the tuple, not to the parameter list. Taking it
        // made the modifier in front of it look like the method's name.
        val src = "class C\n{\n    public (int, string) GetResult(short token)\n    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("GetResult", accentName(src, method))
    }

    @Test
    fun `a method returning a tuple with named elements keeps its own name`() {
        val src = "class C\n{\n    public (int index, T1 result1) GetResult(short token)\n" +
            "    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("GetResult", accentName(src, method))
    }

    @Test
    fun `a tuple inside a generic return type does not take the name either`() {
        val src = "class C\n{\n    public static UniTask<(int index, T1 result1)> WhenAny<T1>(UniTask<T1> task)\n" +
            "    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("WhenAny", accentName(src, method))
    }

    @Test
    fun `a bracket inside a default parameter value does not move the parameter list`() {
        val src = "class C\n{\n    void Write(string suffix = \")\")\n    {\n    }\n}\n"
        val method = openings(src).single { it.keyword == "fun" }
        assertEquals("Write", accentName(src, method))
    }

    // ------------------------------------------------------------------------ records

    @Test
    fun `a positional record takes its name, not its first parameter`() {
        val src = "public record Point(int X, int Y)\n{\n}"
        val opening = accents(src).first()
        assertEquals(BlockKind.TYPE, opening.kind)
        assertEquals("record", opening.keyword)
        assertEquals("Point", accentName(src, opening))
    }

    @Test
    fun `a positional record with a base call keeps its own name`() {
        // The parameter list is followed by `: Shape`, exactly like a constructor initializer.
        val src = "public record Point(int X, int Y) : Shape(X)\n{\n}"
        assertEquals("Point", accentName(src, accents(src).first()))
    }

    @Test
    fun `a record class and a readonly record struct both take the name after the keywords`() {
        val recordClass = "public record class Handle(int Id)\n{\n}"
        assertEquals("Handle", accentName(recordClass, accents(recordClass).first()))

        val recordStruct = "public readonly record struct Point(int X)\n{\n}"
        assertEquals("Point", accentName(recordStruct, accents(recordStruct).first()))
    }

    @Test
    fun `a positional record with default parameter values is still a record`() {
        val src = "public record Options(int Retries = 3, string Tag = \"\")\n{\n}"
        val opening = accents(src).first()
        assertEquals("record", opening.keyword)
        assertEquals("Options", accentName(src, opening))
    }

    @Test
    fun `a record with a wrapped base list keeps its name`() {
        val src = "public record Point(int X)\n    : Shape,\n      IComparable<Point>\n{\n}"
        assertEquals("Point", accentName(src, accents(src).first()))
    }

    @Test
    fun `a record nested in a record is nested and numbered like any other type`() {
        val src = "public record Outer\n{\n" +
            "    public record First(int X)\n    {\n    }\n" +
            "    public record Second(int Y)\n    {\n    }\n" +
            "}\n"
        val nested = openings(src).filter { it.isNested }
        assertEquals(listOf("First", "Second"), nested.map { accentName(src, it) })
        assertEquals(listOf(1, 2), nested.map { it.siblingOrdinal })
    }

    @Test
    fun `a record's members are classified like any other type's`() {
        val src = "public record Point(int X)\n{\n" +
            "    public int Doubled\n    {\n        get\n        {\n        }\n    }\n" +
            "    public void Describe()\n    {\n    }\n" +
            "    public static Point operator +(Point a, Point b)\n    {\n    }\n" +
            "}\n"
        assertEquals(
            listOf("record", "prop", "get", "fun", "op"),
            openings(src).map { it.keyword },
        )
    }

    @Test
    fun `a record is measured from its declaration, like every other block`() {
        val src = "public record Point(\n    int X,\n    int Y)\n{\n}"
        val closing = accents(src).first { !it.isOpening }
        // The `}` is on line 5 (1-based), the declaration on line 1.
        assertEquals(4, closing.spannedLines)
    }

    @Test
    fun `accentTypes=false leaves a record unreported`() {
        // Not "reported as a function": a positional record ends in a parameter list, so unlike
        // a plain `class Foo` it reaches the function fallback, and used to come back `fun Point`.
        val src = "public record Point(int X)\n{\n}"
        assertTrue(openingKeywords(src, ScanOptions(accentTypes = false)).isEmpty())
    }

    @Test
    fun `accentTypes=false leaves a primary constructor unreported too`() {
        val src = "public class Node(int id)\n{\n}"
        assertTrue(openingKeywords(src, ScanOptions(accentTypes = false)).isEmpty())
    }

    @Test
    fun `a parameter named with a contextual keyword does not make a method a type`() {
        // `record` is contextual in C#, so `Record record` is an ordinary parameter with an
        // ordinary name. Searching the whole header for a type keyword turned this into a type
        // declaration called `Record`.
        val src = "public void Save(Record record)\n{\n}"
        val opening = accents(src).first()
        assertEquals(BlockKind.FUNCTION, opening.kind)
        assertEquals("fun", opening.keyword)
        assertEquals("Save", accentName(src, opening))
    }

    @Test
    fun `a condition mentioning a contextual keyword stays an ordinary block`() {
        val src = "if (record != null)\n{\n}"
        assertTrue(accents(src).isEmpty())
    }

    @Test
    fun `a local named with a contextual keyword does not make a loop a type`() {
        val src = "foreach (var record in records)\n{\n}"
        assertTrue(accents(src).isEmpty())
    }

    // ------------------------------------------------------ operators and conversions

    @Test
    fun `an operator overload is labelled op with the operator itself as its name`() {
        val src = "public static Foo operator +(Foo a, Foo b)\n{\n}"
        val opening = accents(src).first()
        assertEquals(BlockKind.FUNCTION, opening.kind)
        assertEquals("op", opening.keyword)
        assertEquals("+", src.substring(opening.nameOffset, opening.nameOffset + opening.nameLength))
    }

    @Test
    fun `a two-character operator keeps both characters`() {
        // The `=` in `==` must not read as an initializer's assignment either.
        val src = "public static bool operator ==(Foo a, Foo b)\n{\n}"
        val opening = accents(src).first()
        assertEquals("op", opening.keyword)
        assertEquals("==", src.substring(opening.nameOffset, opening.nameOffset + opening.nameLength))
    }

    @Test
    fun `a conversion operator is named after the type it converts to`() {
        val src = "public static implicit operator int(Foo a)\n{\n}"
        val opening = accents(src).first()
        assertEquals("op", opening.keyword)
        assertEquals("int", src.substring(opening.nameOffset, opening.nameOffset + opening.nameLength))
    }

    @Test
    fun `an explicit conversion operator is recognised the same way`() {
        val src = "public static explicit operator Foo(int value)\n{\n}"
        val opening = accents(src).first()
        assertEquals("op", opening.keyword)
        assertEquals("Foo", src.substring(opening.nameOffset, opening.nameOffset + opening.nameLength))
    }

    @Test
    fun `an operator in Allman style is recognised from the line above`() {
        val src = "class C\n{\n    public static C operator -(C a)\n    {\n    }\n}\n"
        assertEquals(listOf("class", "op"), openings(src).map { it.keyword })
    }

    @Test
    fun `an operator is turned off by the methods switch, like the method it is`() {
        val src = "class C\n{\n" +
            "    public static C operator +(C a, C b)\n    {\n    }\n" +
            "    public void Work()\n    {\n    }\n" +
            "}\n"
        assertTrue("op" in openingKeywords(src, ScanOptions()))

        val keywords = openingKeywords(src, ScanOptions(accentMethods = false))
        assertTrue("op" !in keywords)
        assertTrue("fun" !in keywords)
    }

    @Test
    fun `an operator answers to the function group's span length, not the property one`() {
        val operator = BraceAccent(
            offset = 0,
            kind = BlockKind.FUNCTION,
            nameOffset = 0,
            nameLength = 1,
            keyword = OPERATOR_KEYWORD,
            isOpening = false,
            spannedLines = 70,
        )
        assertEquals(BlockSpanGroup.FUNCTION, LabelPolicy.blockSpanGroupOf(operator))
    }

    @Test
    fun `a method whose name merely contains the word operator is still a method`() {
        val src = "public void RunOperatorChecks(int a)\n{\n}"
        val opening = accents(src).first()
        assertEquals("fun", opening.keyword)
        assertEquals("RunOperatorChecks", accentName(src, opening))
    }

    // ------------------------------------------ a base-type list running past one line

    @Test
    fun `a constructor whose brace hangs off its base call is still a constructor`() {
        // The K&R half of the colon-continuation family. The Allman shape -- the `{` on its own
        // line below `: base()` -- has worked since 1.6.1; this one reached the classifier with
        // `: base()` as its whole header and came back as nothing at all.
        val src = "class C : B\n{\n    public C(int value)\n        : base() {\n    }\n}\n"
        assertEquals(listOf("class", "ctor"), openings(src).map { it.keyword })
        assertEquals("C", accentName(src, openings(src).first { it.keyword == "ctor" }))
    }

    @Test
    fun `a constructor whose brace hangs off its base call is measured from the declaration`() {
        // The offset and the line number are corrected by the same test, so they cannot
        // disagree: the header points at line 3 and the span has to be counted from there too.
        val src = "class C : B\n{\n    public C(int value)\n        : base() {\n    }\n}\n"
        val closing = accents(src).first { !it.isOpening && it.keyword == "ctor" }
        // `public C(int value)` is line 3 (1-based) and the ctor's `}` is line 5.
        assertEquals(2, closing.spannedLines)
    }

    @Test
    fun `a where clause with the brace hanging off it keeps the method`() {
        val src = "public void Bind<T>(T value)\n    where T : class {\n}"
        val opening = accents(src).first()
        assertEquals("fun", opening.keyword)
        assertEquals("Bind", accentName(src, opening))
    }

    @Test
    fun `a base list wrapped onto a second line keeps the class`() {
        val src = "public class Foo\n    : IBar,\n      IBaz\n{\n}"
        val opening = accents(src).first()
        assertEquals(BlockKind.TYPE, opening.kind)
        assertEquals("class", opening.keyword)
        assertEquals("Foo", accentName(src, opening))
    }

    @Test
    fun `a base list broken after a trailing comma keeps the class`() {
        val src = "public class Foo : IBar,\n                   IBaz\n{\n}"
        assertEquals("Foo", accentName(src, accents(src).first()))
    }

    @Test
    fun `a base list broken after a trailing colon keeps the class`() {
        val src = "public class Foo :\n    IBar,\n    IBaz\n{\n}"
        assertEquals("Foo", accentName(src, accents(src).first()))
    }

    @Test
    fun `a generic base wrapped inside its angle brackets keeps the class`() {
        val src = "public class Foo : Base<int,\n    string>\n{\n}"
        assertEquals("Foo", accentName(src, accents(src).first()))
    }

    @Test
    fun `a wrapped base list is measured from the declaration, not from the last clause`() {
        val src = "public class Foo\n    : IBar,\n      IBaz\n{\n}"
        val closing = accents(src).first { !it.isOpening }
        // The `}` is on line 5 (1-based) and the declaration on line 1.
        assertEquals(4, closing.spannedLines)
    }

    @Test
    fun `a trailing comma inside an initializer does not swallow the next declaration`() {
        val src = "class C\n{\n" +
            "    int[] values = new[]\n    {\n        1,\n        2,\n    };\n" +
            "    public void Work()\n    {\n    }\n" +
            "}\n"
        assertEquals(listOf("class", "fun"), openings(src).map { it.keyword })
        assertEquals("Work", accentName(src, openings(src).first { it.keyword == "fun" }))
    }

    @Test
    fun `a constructor is still a constructor when nothing precedes its name`() {
        val src = "class C\n{\n    public C(int value)\n    {\n    }\n}\n"
        assertEquals(listOf("class", "ctor"), openings(src).map { it.keyword })
    }

    private companion object {
        /** Eight independent switches, so every combination of them is 2^8 runs. */
        const val SWITCH_COMBINATIONS = 1 shl 8
    }
}
