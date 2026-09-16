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

    /** Как выглядел бы текст после применения фолдинга + фантомных строк. */
    private fun render(src: String, flavor: Flavor = Flavor.CSHARP, fullAllman: Boolean = true): String {
        val sites = scan(src, flavor, fullAllman)
        val sb = StringBuilder()
        var pos = 0
        for (s in sites.sortedBy { it.hideStart }) {
            sb.append(src, pos, s.hideStart)
            pos = s.hideEnd
            // хвост строки (например, комментарий) остаётся видимым
            sb.append(src, pos, s.anchorOffset)
            pos = s.anchorOffset
            for (line in s.phantomLines) {
                sb.append('\n').append(s.indent).append(line)
            }
        }
        sb.append(src, pos, src.length)
        return sb.toString()
    }

    @Test
    fun `простая висящая скобка`() {
        assertEquals(
            """
            if (x)
            {
                Foo();
            }
            """.trimIndent(),
            render(
                """
                if (x) {
                    Foo();
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `отступ берётся у строки-владельца`() {
        val src = "\tclass A {\n\t}"
        val site = scan(src).single()
        assertEquals("\t", site.indent)
        assertEquals(listOf("{"), site.phantomLines)
    }

    @Test
    fun `else разносится на три строки`() {
        assertEquals(
            """
            if (x)
            {
            }
            else if (y)
            {
            }
            """.trimIndent(),
            render(
                """
                if (x) {
                } else if (y) {
                }
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun `catch и finally тоже`() {
        val r = render("try {\n} catch (E e) {\n} finally {\n}")
        assertEquals("try\n{\n}\ncatch (E e)\n{\n}\nfinally\n{\n}", r)
    }

    @Test
    fun `else без скобки`() {
        assertEquals("if (x)\n{\n}\nelse\n    Foo();", render("if (x) {\n} else\n    Foo();"))
    }

    @Test
    fun `выключенный fullAllman оставляет else на месте`() {
        assertEquals("if (x)\n{\n} else\n{\n}", render("if (x) {\n} else {\n}", fullAllman = false))
    }

    @Test
    fun `уже Allman не трогаем`() {
        assertTrue(scan("if (x)\n{\n}").isEmpty())
    }

    @Test
    fun `do-while не разносим`() {
        assertTrue(scan("do {\n} while (x);").none { it.phantomLines.contains("while (x);") })
    }

    @Test
    fun `закрывашка с точкой с запятой не трогается`() {
        assertTrue(scan("Run(() => {\n});").size == 1) // только сама лямбда
        assertEquals("Run(() =>\n{\n});", render("Run(() => {\n});"))
    }

    @Test
    fun `хвостовой комментарий остаётся на исходной строке`() {
        assertEquals("if (x) // note\n{\n}", render("if (x) { // note\n}"))
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
        val src = "var s = @\"line1 {\nline2 {\";\nif (x) {\n}"
        assertEquals(1, scan(src).size)
    }

    @Test
    fun `raw string C sharp`() {
        val src = "var s = \"\"\"\nif (x) {\n\"\"\";\nif (y) {\n}"
        assertEquals(1, scan(src).size)
    }

    @Test
    fun `интерполяция с кавычкой внутри дырки`() {
        val src = "var s = ${'$'}\"{dict[\"key\"]} tail\";\nif (x) {\n}"
        assertEquals(1, scan(src).size)
    }

    @Test
    fun `экранированные скобки в интерполяции`() {
        assertTrue(scan("var s = ${'$'}\"{{ not a hole }}\";").isEmpty())
    }

    @Test
    fun `незакрытая кавычка не ломает следующие строки`() {
        val src = "var s = \"oops;\nif (x) {\n}"
        assertEquals(1, scan(src).size)
    }

    @Test
    fun `cpp raw string`() {
        val src = "auto s = R\"(if (x) {\n)\";\nif (y) {\n}"
        assertEquals(1, scan(src, flavor = Flavor.CPP).size)
    }

    @Test
    fun `cpp разделитель разрядов не char литерал`() {
        val src = "int n = 1'000'000;\nif (x) {\n}"
        assertEquals(1, scan(src, flavor = Flavor.CPP).size)
    }

    @Test
    fun `ts template literal`() {
        val src = "const s = `a ${'$'}{obj[`x`]} {`;\nif (x) {\n}"
        assertEquals(1, scan(src, flavor = Flavor.GENERIC).size)
    }

    @Test
    fun `инициализатор объекта тоже переносится`() {
        assertEquals("var a = new Foo\n{\n};", render("var a = new Foo {\n};"))
    }

    @Test
    fun `однострочный блок не трогаем`() {
        assertTrue(scan("if (x) { Foo(); }").isEmpty())
    }

    @Test
    fun `якорь указывает на конец строки`() {
        val src = "if (x) { // c\n}"
        val site = scan(src).single()
        assertEquals(src.indexOf('\n'), site.anchorOffset)
    }
}
