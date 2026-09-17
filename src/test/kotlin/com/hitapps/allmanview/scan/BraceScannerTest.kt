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

    /** Скобки, помеченные как принадлежащие типу или функции. */
    private fun accents(src: String, flavor: Flavor = Flavor.CSHARP): List<BraceAccent> {
        return BraceScanner(src, flavor, ScanOptions()).scan().accents
    }

    /** Пары «символ скобки — чей блок», по порядку в документе. */
    private fun accentKinds(src: String, flavor: Flavor = Flavor.CSHARP): List<Pair<Char, BlockKind>> {
        return accents(src, flavor)
            .sortedBy { it.offset }
            .map { src[it.offset] to it.kind }
    }

    /** Имя, с которого будет взят цвет скобки. */
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

    /** Текст, который будет погашен серым. */
    private fun dimmed(src: String, site: PhantomSite) = src.substring(site.dimStart, site.dimEnd)

    /** Как будет выглядеть редактор: «...» — погашенное, следом фантомные строки. */
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
    fun `простая висящая скобка`() {
        val src = "if (x) {\n    Foo();\n}"
        val site = scan(src).single()
        assertEquals("{", dimmed(src, site))
        assertEquals(listOf("{"), site.phantomTexts)
        assertEquals("if (x) «{»\n{\n    Foo();\n}", render(src))
    }

    @Test
    fun `гасим только скобку, не пробелы перед ней`() {
        val src = "if (x)     {\n}"
        assertEquals("{", dimmed(src, scan(src).single()))
    }

    @Test
    fun `отступ берётся у строки-владельца`() {
        val site = scan("\tclass A {\n\t}").single()
        assertEquals("\t", site.indent)
        assertEquals(listOf("{"), site.phantomTexts)
    }

    @Test
    fun `отступ пробелами`() {
        val site = scan("    if (x) {\n    }").single()
        assertEquals("    ", site.indent)
    }

    @Test
    fun `else разносится и гасится целиком`() {
        val src = "if (x) {\n} else if (y) {\n}"
        val sites = scan(src)
        assertEquals(2, sites.size)
        assertEquals(" else if (y) {", dimmed(src, sites[1]))
        assertEquals(listOf("else if (y)", "{"), sites[1].phantomTexts)
    }

    @Test
    fun `catch и finally тоже`() {
        val src = "try {\n} catch (E e) {\n} finally {\n}"
        val sites = scan(src)
        assertEquals(3, sites.size)
        assertEquals(listOf("catch (E e)", "{"), sites[1].phantomTexts)
        assertEquals(listOf("finally", "{"), sites[2].phantomTexts)
    }

    @Test
    fun `else без скобки`() {
        val src = "if (x) {\n} else\n    Foo();"
        val sites = scan(src)
        assertEquals(2, sites.size)
        assertEquals(" else", dimmed(src, sites[1]))
        assertEquals(listOf("else"), sites[1].phantomTexts)
    }

    @Test
    fun `выключенный fullAllman оставляет else на месте`() {
        val src = "if (x) {\n} else {\n}"
        val sites = scan(src, fullAllman = false)
        assertEquals(2, sites.size)
        assertEquals("{", dimmed(src, sites[1]))
        assertEquals(listOf("{"), sites[1].phantomTexts)
    }

    @Test
    fun `уже Allman не трогаем`() {
        assertTrue(scan("if (x)\n{\n}").isEmpty())
    }

    @Test
    fun `do-while не разносим`() {
        assertTrue(scan("do {\n} while (x);").none { it.phantomTexts.any { l -> l.startsWith("while") } })
    }

    @Test
    fun `закрывашка с точкой с запятой не трогается`() {
        assertEquals(1, scan("Run(() => {\n});").size)
    }

    @Test
    fun `хвостовой комментарий остаётся на исходной строке`() {
        assertEquals("if (x) «{» // note\n{\n}", render("if (x) { // note\n}"))
    }

    @Test
    fun `скобка внутри строкового литерала игнорируется`() {
        assertTrue(scan("""var s = "if (x) {";""").isEmpty())
    }

    @Test
    fun `скобка в комментарии игнорируется`() {
        assertTrue(scan("// if (x) {").isEmpty())
        assertTrue(scan("/* if (x) {\n   more { */").isEmpty())
    }

    @Test
    fun `verbatim строка на несколько строк`() {
        assertEquals(1, scan("var s = @\"line1 {\nline2 {\";\nif (x) {\n}").size)
    }

    @Test
    fun `raw string C sharp`() {
        assertEquals(1, scan("var s = \"\"\"\nif (x) {\n\"\"\";\nif (y) {\n}").size)
    }

    @Test
    fun `интерполяция с кавычкой внутри дырки`() {
        assertEquals(1, scan("var s = ${'$'}\"{dict[\"key\"]} tail\";\nif (x) {\n}").size)
    }

    @Test
    fun `экранированные скобки в интерполяции`() {
        assertTrue(scan("var s = ${'$'}\"{{ not a hole }}\";").isEmpty())
    }

    @Test
    fun `незакрытая кавычка не ломает следующие строки`() {
        assertEquals(1, scan("var s = \"oops;\nif (x) {\n}").size)
    }

    @Test
    fun `cpp raw string`() {
        assertEquals(1, scan("auto s = R\"(if (x) {\n)\";\nif (y) {\n}", Flavor.CPP).size)
    }

    @Test
    fun `cpp разделитель разрядов не char литерал`() {
        assertEquals(1, scan("int n = 1'000'000;\nif (x) {\n}", Flavor.CPP).size)
    }

    @Test
    fun `ts template literal`() {
        assertEquals(1, scan("const s = `a ${'$'}{obj[`x`]} {`;\nif (x) {\n}", Flavor.WEB).size)
    }

    // --- отступ у многострочных конструкций ---

    @Test
    fun `многострочная сигнатура — скобка под началом объявления`() {
        val src = "    void Foo(\n        int a,\n        int b) {\n    }"
        assertEquals("    ", scan(src).single().indent)
    }

    @Test
    fun `многострочное условие`() {
        assertEquals("", scan("if (a &&\n    b) {\n}").single().indent)
    }

    @Test
    fun `цепочка вызовов берёт отступ своей строки`() {
        assertEquals("    ", scan("var x = Foo()\n    .Bar(y => {\n    });").single().indent)
    }

    @Test
    fun `слишком длинное продолжение — фолбэк на свою строку`() {
        val src = "void A(\n" + "x,\n".repeat(60) + "y) {\n}"
        assertEquals("", scan(src).single().indent)
    }

    // --- диалекты ---

    @Test
    fun `расширения раскладываются по диалектам`() {
        assertEquals(Flavor.CSHARP, Dialects.forExtension("cs"))
        assertEquals(Flavor.CPP, Dialects.forExtension("HPP"))
        assertEquals(Flavor.JVM, Dialects.forExtension("kt"))
        assertEquals(Flavor.JVM, Dialects.forExtension("swift"))
        assertEquals(Flavor.WEB, Dialects.forExtension("go"))
        assertEquals(Flavor.GENERIC, Dialects.forExtension("rs"))
        assertEquals(Flavor.GENERIC, Dialects.forExtension(""))
    }

    @Test
    fun `java text block не даёт ложных срабатываний`() {
        val src = "String s = \"\"\"\n    if (x) {\n    \"\"\";\nvoid m() {\n}"
        assertEquals(1, scan(src, Flavor.JVM).size)
        // без поддержки текстовых блоков тот же файл ловит лишнее — ради этого диалект и нужен
        assertEquals(2, scan(src, Flavor.GENERIC).size)
    }

    @Test
    fun `kotlin raw string`() {
        assertEquals(1, scan("val s = \"\"\"\nif (x) {\n\"\"\"\nfun f() {\n}", Flavor.JVM).size)
    }

    @Test
    fun `go raw string в бэктиках`() {
        assertEquals(1, scan("var s = `if x {`\nfunc f() {\n}", Flavor.WEB).size)
    }

    @Test
    fun `тройная кавычка в C++ не включает raw-режим`() {
        assertEquals(1, scan("auto s = \"\"\"\";\nvoid f() {\n}", Flavor.CPP).size)
    }

    @Test
    fun `css`() {
        assertEquals(2, scan(".a {\n}\n.b {\n}", Flavor.GENERIC).size)
    }

    @Test
    fun `инициализатор объекта тоже переносится`() {
        assertEquals(1, scan("var a = new Foo {\n};").size)
    }

    @Test
    fun `однострочный блок не трогаем, когда разворачивание выключено`() {
        assertTrue(scan("if (x) { Foo(); }", expandInlineBlocks = false).isEmpty())
    }

    @Test
    fun `сигнатура на верхнем уровне`() {
        val src = "private void Foo(\n    int a,\n    int b) {\n}"
        assertEquals("", scan(src).first().indent)
    }

    @Test
    fun `swift — интерполяция обратным слешем`() {
        val src = "if a > 0 {\n    print(\"val \\(a) { x }\")\n}"
        assertEquals(1, scan(src, Flavor.JVM).size)
    }


    // --- виртуальный перенос одиночных инструкций ---

    @Test
    fun `if с одиночным return`() {
        val src = "if (pending == null) return;"
        val site = scan(src).single()
        assertEquals("return;", dimmed(src, site))
        assertEquals(listOf("return;"), site.phantomTexts)
    }

    @Test
    fun `if с throw`() {
        val src = "    if (x < 0) throw new ArgumentException(nameof(x));"
        val site = scan(src).single()
        assertEquals("throw new ArgumentException(nameof(x));", dimmed(src, site))
        assertEquals("    ", site.indent)
    }

    @Test
    fun `цикл с одиночной инструкцией`() {
        val src = "foreach (var item in items) total += item.Price;"
        val site = scan(src).single()
        assertEquals("total += item.Price;", dimmed(src, site))
    }

    @Test
    fun `lock с одиночной инструкцией`() {
        assertEquals(listOf("_count++;"), scan("lock (gate) _count++;").single().phantomTexts)
    }

    @Test
    fun `using-директива не разносится`() {
        assertTrue(scan("using System.Text;").isEmpty())
        assertTrue(scan("using static System.Math;").isEmpty())
    }

    @Test
    fun `using-выражение разносится`() {
        assertEquals(
            listOf("stream.Flush();"),
            scan("using (var stream = Open()) stream.Flush();").single().phantomTexts,
        )
    }

    @Test
    fun `пустая инструкция не разносится`() {
        assertTrue(scan("while (reader.Read());").isEmpty())
    }

    @Test
    fun `do-while в одну строку не трогаем`() {
        assertTrue(scan("do { } while (x);").isEmpty())
    }

    @Test
    fun `скобка внутри литерала не считается концом заголовка`() {
        val src = """if (Check(")")) Foo();"""
        val site = scan(src).single()
        assertEquals("Foo();", dimmed(src, site))
    }

    @Test
    fun `хвостовой комментарий остаётся при переносе инструкции`() {
        val src = "if (x) return; // early out"
        val site = scan(src).single()
        assertEquals("return;", dimmed(src, site))
    }

    @Test
    fun `закрывающая скобка, else и инструкция — три строки`() {
        val src = "if (x) {\n} else return;"
        val site = scan(src)[1]
        assertEquals(" else return;", dimmed(src, site))
        assertEquals(listOf("else", "return;"), site.phantomTexts)
    }

    @Test
    fun `else if с инструкцией`() {
        val src = "else if (x) return;"
        assertEquals(listOf("return;"), scan(src).single().phantomTexts)
    }

    @Test
    fun `отступ задаётся уровнями, а не пробелами`() {
        // Сканер не знает ширину отступа: таб внутри строки drawString не разворачивает,
        // поэтому в тексте фантома отступа нет вовсе — его рисует редактор.
        val line = scan("if (x) return;").single().phantomLines.single()
        assertEquals("return;", line.text)
        assertEquals(1, line.extraIndentLevels)
    }

    @Test
    fun `выключенный splitStatements ничего не разносит`() {
        assertTrue(scan("if (x) return;", splitStatements = false).isEmpty())
    }

    // --- однострочный блок в скобках ---

    @Test
    fun `однострочный блок разворачивается`() {
        val src = "if (x) { Foo(); }"
        val site = scan(src).single()
        assertEquals("{ Foo(); }", dimmed(src, site))
        assertEquals(listOf("{", "Foo();", "}"), site.phantomTexts)
    }

    @Test
    fun `пустой однострочный блок`() {
        assertEquals(listOf("{", "}"), scan("if (x) { }").single().phantomTexts)
    }

    @Test
    fun `вложенный однострочный блок разворачивается целиком`() {
        val site = scan("if (x) { if (y) { a(); } }").single()
        assertEquals(listOf("{", "if (y) { a(); }", "}"), site.phantomTexts)
    }

    @Test
    fun `закрывающая скобка с else и блоком`() {
        val src = "try {\n} catch (E e) { Log(e); }"
        val site = scan(src)[1]
        assertEquals(listOf("catch (E e)", "{", "Log(e);", "}"), site.phantomTexts)
    }

    @Test
    fun `автосвойство не трогаем`() {
        assertTrue(scan("public int Count { get; set; }").isEmpty())
    }

    @Test
    fun `пустое тело метода в одну строку не трогаем`() {
        assertTrue(scan("public void Dispose() { }").isEmpty())
    }

    @Test
    fun `инициализатор в одну строку не трогаем`() {
        assertTrue(scan("var point = new Point { X = 1, Y = 2 };").isEmpty())
    }


    @Test
    fun `инструкция уезжает на уровень глубже`() {
        val lines = scan("if (x) return;").single().phantomLines
        assertEquals(1, lines.single().extraIndentLevels)
    }

    @Test
    fun `заголовок и скобки остаются на своём уровне`() {
        val lines = scan("try {\n} catch (E e) { Log(e); }")[1].phantomLines
        assertEquals(listOf(0, 0, 1, 0), lines.map { it.extraIndentLevels })
    }

    @Test
    fun `текст фантома лежит в документе по своему offset`() {
        val src = "    if (ready) Launch();"
        for (line in scan(src).single().phantomLines) {
            val slice = src.substring(line.sourceOffset, line.sourceOffset + line.text.length)
            assertEquals(line.text, slice)
        }
    }

    @Test
    fun `offset совпадает с текстом и у скобок, и у заголовка`() {
        val src = "if (x) {\n} else if (y) {\n}"
        for (site in scan(src)) {
            for (line in site.phantomLines) {
                val slice = src.substring(line.sourceOffset, line.sourceOffset + line.text.length)
                assertEquals(line.text, slice)
            }
        }
    }


    // --- принадлежность скобок типам и функциям ---

    @Test
    fun `скобки класса помечаются как тип`() {
        val src = "public class Spawner {\n}"
        assertEquals(listOf('{' to BlockKind.TYPE, '}' to BlockKind.TYPE), accentKinds(src))
    }

    @Test
    fun `цвет берётся с имени класса`() {
        val src = "public class Spawner {\n}"
        for (accent in accents(src)) {
            assertEquals("Spawner", accentName(src, accent))
        }
    }

    @Test
    fun `struct interface enum record тоже типы`() {
        for (keyword in listOf("struct", "interface", "enum", "record")) {
            val src = "public $keyword Thing {\n}"
            assertEquals(2, accents(src).size)
            assertEquals(BlockKind.TYPE, accents(src).first().kind)
            assertEquals("Thing", accentName(src, accents(src).first()))
        }
    }

    @Test
    fun `record struct — имя берётся после обоих слов`() {
        val src = "public record struct Point(int X) {\n}"
        assertEquals("Point", accentName(src, accents(src).first()))
    }

    @Test
    fun `метод помечается как функция`() {
        val src = "private void Update() {\n}"
        assertEquals(listOf('{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION), accentKinds(src))
        assertEquals("Update", accentName(src, accents(src).first()))
    }

    @Test
    fun `конструктор — тоже функция`() {
        val src = "public Spawner(int count) {\n}"
        assertEquals("Spawner", accentName(src, accents(src).first()))
    }

    @Test
    fun `генерик-метод — имя до угловых скобок`() {
        val src = "public T Resolve<T>(string key) {\n}"
        assertEquals("Resolve", accentName(src, accents(src).first()))
    }

    @Test
    fun `where-констрейнты не мешают`() {
        val src = "public void Bind<T>(T value) where T : class {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Bind", accentName(src, accents(src).first()))
    }

    @Test
    fun `многострочная сигнатура распознаётся`() {
        val src = "private static void Handle(\n    int id,\n    bool flag) {\n}"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Handle", accentName(src, accents(src).first()))
    }

    @Test
    fun `вложенный класс и его методы`() {
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
    fun `две скобки на одной строке не путаются`() {
        // у второй скобки заголовок начинается после первой, иначе `class` утёк бы в метод
        val src = "class A { void M() {\n} }"
        assertEquals(
            listOf('{' to BlockKind.TYPE, '{' to BlockKind.FUNCTION),
            accentKinds(src).take(2),
        )
    }

    // --- что НЕ должно попадать в усиление ---

    @Test
    fun `управляющие конструкции не усиливаются`() {
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
    fun `инициализаторы не усиливаются`() {
        assertTrue(accents("var a = new Foo() {\n};").isEmpty())
        assertTrue(accents("return new Foo() {\n};").isEmpty())
        assertTrue(accents("var list = new List<int> {\n};").isEmpty())
    }

    // --- лямбды считаются функциями, имя берётся у ближайшего осмысленного ---

    @Test
    fun `лямбда берёт имя у метода, которому передана`() {
        val src = "Run(() => {\n});"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Run", accentName(src, accents(src).first()))
        assertEquals("fun", accents(src).first().keyword)
    }

    @Test
    fun `лямбда берёт имя у цели присваивания`() {
        val src = "Action handler = () => {\n};"
        assertEquals("handler", accentName(src, accents(src).first()))
    }

    @Test
    fun `лямбда в цепочке берёт последний незакрытый вызов`() {
        val src = "var r = items.Where(x => x > 0).Select(y => {\n});"
        assertEquals("Select", accentName(src, accents(src).first()))
    }

    @Test
    fun `лямбда после return всё равно функция`() {
        val src = "return items.Select(x => {\n});"
        assertEquals(BlockKind.FUNCTION, accents(src).first().kind)
        assertEquals("Select", accentName(src, accents(src).first()))
    }

    @Test
    fun `анонимный метод через delegate`() {
        assertEquals(BlockKind.FUNCTION, accents("Run(delegate {\n});").first().kind)
        assertEquals(BlockKind.FUNCTION, accents("Run(delegate(int x) {\n});").first().kind)
    }

    // --- подпись и протяжённость блока ---

    @Test
    fun `ключевое слово подписи`() {
        assertEquals("class", accents("class A {\n}").first().keyword)
        assertEquals("struct", accents("struct A {\n}").first().keyword)
        assertEquals("interface", accents("interface A {\n}").first().keyword)
        assertEquals("enum", accents("enum A {\n}").first().keyword)
        assertEquals("fun", accents("void M() {\n}").first().keyword)
    }

    @Test
    fun `длина имени позволяет его вырезать`() {
        val src = "public class IosHttpClient {\n}"
        val accent = accents(src).first()
        assertEquals(
            "IosHttpClient",
            src.substring(accent.nameOffset, accent.nameOffset + accent.nameLength),
        )
    }

    @Test
    fun `протяжённость блока считается в строках`() {
        val src = "class A {\n" + "    // line\n".repeat(9) + "}"
        val closing = accents(src).first { !it.isOpening }
        assertEquals(10, closing.spannedLines)
        assertEquals(0, accents(src).first { it.isOpening }.spannedLines)
    }

    @Test
    fun `типы и функции включаются по отдельности`() {
        val src = "class A {\n    void M() {\n    }\n}"
        val onlyTypes = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentFunctions = false)).scan()
        assertTrue(onlyTypes.accents.all { it.kind == BlockKind.TYPE })

        val onlyFunctions = BraceScanner(src, Flavor.CSHARP, ScanOptions(accentTypes = false)).scan()
        assertTrue(onlyFunctions.accents.all { it.kind == BlockKind.FUNCTION })
    }

    @Test
    fun `свойства не усиливаются`() {
        assertTrue(accents("public int Count { get; set; }").isEmpty())
        assertTrue(accents("public int Count {\n    get {\n        return 1;\n    }\n}").isEmpty())
    }

    @Test
    fun `namespace не усиливается`() {
        assertTrue(accents("namespace Com.Hitapps.Core {\n}").isEmpty())
    }

    @Test
    fun `слово class внутри литерала не делает блок типом`() {
        assertTrue(accents("Log(\"class A\");\nif (x) {\n}").isEmpty())
    }

    @Test
    fun `выключённое усиление не даёт меток`() {
        val options = ScanOptions(accentTypes = false, accentFunctions = false)
        val result = BraceScanner("class A {\n}", Flavor.CSHARP, options).scan()
        assertTrue(result.accents.isEmpty())
    }

    @Test
    fun `несбалансированные скобки не роняют стек`() {
        val result = BraceScanner("}\n}\nclass A {\n", Flavor.CSHARP, ScanOptions()).scan()
        assertEquals(1, result.accents.size)
    }

    // --- другие языки ---

    @Test
    fun `kotlin fun и class`() {
        val src = "class Foo {\n    fun bar() {\n    }\n}"
        assertEquals(
            listOf('{' to BlockKind.TYPE, '{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION, '}' to BlockKind.TYPE),
            accentKinds(src, Flavor.JVM),
        )
    }

    @Test
    fun `go struct — имя стоит перед ключевым словом`() {
        val src = "type Point struct {\n}"
        assertEquals(BlockKind.TYPE, accents(src, Flavor.WEB).first().kind)
        assertEquals("Point", accentName(src, accents(src, Flavor.WEB).first()))
    }


    // --- код, уже написанный в Allman: заголовок на строке выше ---

    @Test
    fun `класс в Allman-стиле`() {
        val src = "public class Spawner\n{\n}"
        assertEquals(listOf('{' to BlockKind.TYPE, '}' to BlockKind.TYPE), accentKinds(src))
        assertEquals("Spawner", accentName(src, accents(src).first()))
    }

    @Test
    fun `метод в Allman-стиле`() {
        val src = "private void Update()\n{\n}"
        assertEquals(listOf('{' to BlockKind.FUNCTION, '}' to BlockKind.FUNCTION), accentKinds(src))
        assertEquals("Update", accentName(src, accents(src).first()))
    }

    @Test
    fun `многострочная сигнатура в Allman-стиле`() {
        val src = "static void Handle(\n    int id,\n    bool flag)\n{\n}"
        assertEquals("Handle", accentName(src, accents(src).first()))
    }

    @Test
    fun `управляющие конструкции в Allman-стиле не усиливаются`() {
        assertTrue(accents("if (x)\n{\n}").isEmpty())
        assertTrue(accents("foreach (var a in b)\n{\n}").isEmpty())
        assertTrue(accents("try\n{\n}").isEmpty())
        assertTrue(accents("var a = new Foo()\n{\n};").isEmpty())
    }

    @Test
    fun `свойство с телом в Allman-стиле не усиливается`() {
        assertTrue(accents("public int Count\n{\n    get\n    {\n        return 1;\n    }\n}").isEmpty())
    }

    @Test
    fun `якорь указывает на конец строки`() {
        val src = "if (x) { // c\n}"
        assertEquals(src.indexOf('\n'), scan(src).single().anchorOffset)
    }
}
