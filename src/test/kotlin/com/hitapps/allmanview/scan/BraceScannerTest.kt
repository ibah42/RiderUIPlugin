package com.hitapps.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BraceScannerTest {

    private fun scan(
        src: String,
        flavor: Flavor = Flavor.CSHARP,
        fullAllman: Boolean = true,
    ): List<PhantomSite> = BraceScanner(src, flavor, fullAllman).scan()

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
            for (line in s.phantomLines) sb.append('\n').append(s.indent).append(line)
        }
        sb.append(src, pos, src.length)
        return sb.toString()
    }

    @Test
    fun `простая висящая скобка`() {
        val src = "if (x) {\n    Foo();\n}"
        val site = scan(src).single()
        assertEquals("{", dimmed(src, site))
        assertEquals(listOf("{"), site.phantomLines)
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
        assertEquals(listOf("{"), site.phantomLines)
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
        assertEquals(listOf("else if (y)", "{"), sites[1].phantomLines)
    }

    @Test
    fun `catch и finally тоже`() {
        val src = "try {\n} catch (E e) {\n} finally {\n}"
        val sites = scan(src)
        assertEquals(3, sites.size)
        assertEquals(listOf("catch (E e)", "{"), sites[1].phantomLines)
        assertEquals(listOf("finally", "{"), sites[2].phantomLines)
    }

    @Test
    fun `else без скобки`() {
        val src = "if (x) {\n} else\n    Foo();"
        val sites = scan(src)
        assertEquals(2, sites.size)
        assertEquals(" else", dimmed(src, sites[1]))
        assertEquals(listOf("else"), sites[1].phantomLines)
    }

    @Test
    fun `выключенный fullAllman оставляет else на месте`() {
        val src = "if (x) {\n} else {\n}"
        val sites = scan(src, fullAllman = false)
        assertEquals(2, sites.size)
        assertEquals("{", dimmed(src, sites[1]))
        assertEquals(listOf("{"), sites[1].phantomLines)
    }

    @Test
    fun `уже Allman не трогаем`() {
        assertTrue(scan("if (x)\n{\n}").isEmpty())
    }

    @Test
    fun `do-while не разносим`() {
        assertTrue(scan("do {\n} while (x);").none { it.phantomLines.any { l -> l.startsWith("while") } })
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
    fun `однострочный блок не трогаем`() {
        assertTrue(scan("if (x) { Foo(); }").isEmpty())
    }

    @Test
    fun `многострочная сигнатура — отступ у начала конструкции`() {
        val src = "private void Foo(\n    int a,\n    int b) {\n}"
        assertEquals("", scan(src).first().indent)
    }

    @Test
    fun `вложенная многострочная сигнатура`() {
        assertEquals("    ", scan("    void Foo(\n        int a) {\n    }").first().indent)
    }

    @Test
    fun `многострочное условие`() {
        assertEquals("", scan("if (a &&\n    b) {\n}").first().indent)
    }

    @Test
    fun `цепочка вызовов — отступ у своей строки`() {
        assertEquals("    ", scan("var x = Foo()\n    .Bar(y => {\n    });").first().indent)
    }

    @Test
    fun `несбалансированные скобки — фолбэк на свою строку`() {
        val src = "void A(\n" + "x,\n".repeat(60) + "y) {\n}"
        assertEquals("", scan(src).first().indent)
    }

    @Test
    fun `swift — интерполяция и многострочный литерал`() {
        val src = "if a > 0 {\n    print(\"val \\(a) { x }\")\n}"
        assertEquals(1, scan(src, Flavor.GENERIC).size)
    }

    @Test
    fun `go — backtick raw string`() {
        val src = "s := `raw { string`\nif x {\n}"
        assertEquals(1, scan(src, Flavor.GENERIC).size)
    }

    @Test
    fun `java — text block`() {
        val src = "String t = \"\"\"\n  block { here\n  \"\"\";\ntry {\n}"
        assertEquals(1, scan(src, Flavor.GENERIC).size)
    }

    @Test
    fun `якорь указывает на конец строки`() {
        val src = "if (x) { // c\n}"
        assertEquals(src.indexOf('\n'), scan(src).single().anchorOffset)
    }
}
