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
    }

    @Test
    fun `properties are not accented`() {
        assertTrue(accents("public int Count { get; set; }").isEmpty())
        assertTrue(accents("public int Count {\n    get {\n        return 1;\n    }\n}").isEmpty())
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
    fun `property with a body in Allman style is not accented`() {
        assertTrue(accents("public int Count\n{\n    get\n    {\n        return 1;\n    }\n}").isEmpty())
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
}
