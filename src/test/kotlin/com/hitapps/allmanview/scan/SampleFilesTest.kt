package com.hitapps.allmanview.scan

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The scanner run over whole real source files, not over hand-written snippets.
 *
 * Two kinds of check, and the difference matters:
 *
 *  - **Invariants** ([everySampleHoldsItsInvariants] and friends) run over every file in
 *    `src/test/resources/samples` with no expected output of any kind. Drop a new file in that
 *    folder and it is checked from the next run onwards, without a line of new code. These are
 *    the properties that must hold for any input at all -- balanced braces, in-bounds names,
 *    ascending offsets, a deterministic result -- so they cannot be "fixed" by regenerating
 *    anything.
 *  - **The golden file** ([everySampleMatchesItsGoldenFile]) is one line per block, compared
 *    against a checked-in `.expected.txt`. It catches what nobody thought to assert: the bug
 *    that started this file was a class quietly disappearing from a generated UniTask source,
 *    which no snippet test was ever going to notice.
 *
 * The golden is one line per block rather than per brace, and it carries names: a failure is
 * meant to be read and judged, not regenerated on sight. **Regenerating it is a change to the
 * plugin's behaviour** -- if a diff appears that the changelog entry does not explain, the diff
 * is the bug.
 */
class SampleFilesTest {

    // ------------------------------------------------------------------ invariants

    @Test
    fun `there is at least one sample to run over`() {
        // Guards the guard: a sample folder that resolves to nothing would make every test
        // below pass by having nothing to check.
        assertTrue("no sample files found in $SAMPLES_RESOURCE", sampleFiles().isNotEmpty())
    }

    @Test
    fun `every sample pairs each opening brace with a closing one of the same block`() {
        forEachSample { name, _, result ->
            val open = ArrayDeque<BraceAccent>()
            var closed = 0
            for (accent in result.accents) {
                if (accent.isOpening) {
                    open.addLast(accent)
                    continue
                }
                assertTrue("$name: closing brace with nothing open", open.isNotEmpty())
                val opening = open.removeLast()
                assertEquals("$name: kind", opening.kind, accent.kind)
                assertEquals("$name: keyword", opening.keyword, accent.keyword)
                assertEquals("$name: name offset", opening.nameOffset, accent.nameOffset)
                assertEquals("$name: name length", opening.nameLength, accent.nameLength)
                assertEquals("$name: ordinal", opening.siblingOrdinal, accent.siblingOrdinal)
                assertEquals("$name: nested", opening.isNested, accent.isNested)
                closed++
            }
            assertTrue("$name: ${open.size} blocks never closed", open.isEmpty())
            assertEquals("$name: every block reported twice", result.accents.size, closed * 2)
        }
    }

    @Test
    fun `every sample reports accents in ascending document order`() {
        forEachSample { name, _, result ->
            var previous = -1
            for (accent in result.accents) {
                assertTrue("$name: offsets out of order at ${accent.offset}", accent.offset > previous)
                previous = accent.offset
            }
        }
    }

    @Test
    fun `every sample keeps its offsets and names inside the document`() {
        forEachSample { name, source, result ->
            for (accent in result.accents) {
                assertTrue("$name: brace offset ${accent.offset}", accent.offset in source.indices)
                assertTrue(
                    "$name: brace at ${accent.offset} is '${source[accent.offset]}'",
                    source[accent.offset] == '{' || source[accent.offset] == '}',
                )
                if (accent.nameLength > 0) {
                    val end = accent.nameOffset + accent.nameLength
                    assertTrue("$name: name offset ${accent.nameOffset}", accent.nameOffset >= 0)
                    assertTrue("$name: name end $end past ${source.length}", end <= source.length)
                    assertTrue(
                        "$name: blank name at ${accent.nameOffset}",
                        source.substring(accent.nameOffset, end).isNotBlank(),
                    )
                }
                if (accent.headerOffset >= 0) {
                    assertTrue(
                        "$name: header offset ${accent.headerOffset} past the brace",
                        accent.headerOffset <= accent.offset,
                    )
                }
            }
        }
    }

    @Test
    fun `every sample measures a span of zero on the way in and a real one on the way out`() {
        forEachSample { name, _, result ->
            for (accent in result.accents) {
                if (accent.isOpening) {
                    assertEquals("$name: opening span", 0, accent.spannedLines)
                } else {
                    assertTrue("$name: negative span ${accent.spannedLines}", accent.spannedLines >= 0)
                }
            }
        }
    }

    @Test
    fun `every sample's counts agree with the blocks it reported`() {
        forEachSample { name, _, result ->
            var types = 0
            var namespaces = 0
            for (accent in result.accents) {
                if (!accent.isOpening) {
                    continue
                }
                if (accent.kind == BlockKind.TYPE) {
                    types++
                }
                if (accent.kind == BlockKind.NAMESPACE) {
                    namespaces++
                }
            }
            assertEquals("$name: type count", types, result.counts.types)
            assertEquals("$name: namespace count", namespaces, result.counts.namespaces)
        }
    }

    @Test
    fun `every sample keeps its phantom sites inside the document`() {
        forEachSample { name, source, result ->
            for (site in result.sites) {
                assertTrue("$name: empty dim range", site.dimStart < site.dimEnd)
                assertTrue("$name: dim end ${site.dimEnd}", site.dimEnd <= source.length)
                assertTrue("$name: anchor ${site.anchorOffset}", site.anchorOffset <= source.length)
                assertTrue("$name: site with no phantom lines", site.phantomLines.isNotEmpty())
                for (line in site.phantomLines) {
                    val end = line.sourceOffset + line.text.length
                    assertTrue("$name: phantom source ${line.sourceOffset}", line.sourceOffset >= 0)
                    assertTrue("$name: phantom end $end past ${source.length}", end <= source.length)
                    assertEquals(
                        "$name: phantom text does not match the document at ${line.sourceOffset}",
                        source.substring(line.sourceOffset, end),
                        line.text,
                    )
                }
            }
        }
    }

    @Test
    fun `scanning a sample twice gives the same answer`() {
        // A scanner that carried state between runs, or hashed something iteration-ordered,
        // would show up here rather than as an intermittent failure somewhere else.
        forEachSample { name, source, result ->
            val again = BraceScanner(source, flavorOf(name), ScanOptions()).scan()
            assertEquals("$name: accents", result.accents, again.accents)
            assertEquals("$name: counts", result.counts, again.counts)
            assertEquals("$name: site count", result.sites.size, again.sites.size)
        }
    }

    // ------------------------------------------------------------------ the golden file

    @Test
    fun `every sample matches its golden file`() {
        for (sample in sampleFiles()) {
            val name = sample.name
            val source = sample.readText()
            val result = BraceScanner(source, flavorOf(name), ScanOptions()).scan()

            val goldenFile = File(sample.parentFile, name + GOLDEN_SUFFIX)
            assertTrue("$name: no golden file at ${goldenFile.name}", goldenFile.isFile)

            val expected = goldenFile.readText().trim().lines()
            val actual = renderBlocks(source, result).trim().lines()

            for (index in 0 until maxOf(expected.size, actual.size)) {
                // One line at a time, so a failure names the block that changed instead of
                // printing several hundred lines of context around it.
                assertEquals(
                    "$name golden line ${index + 1}: " + (expected.getOrNull(index) ?: "<end of file>"),
                    "$name golden line ${index + 1}: " + (actual.getOrNull(index) ?: "<end of file>"),
                )
            }
        }
    }

    // ------------------------------------------------------------------ the machinery

    /**
     * One line per block: where it is declared, where it closes, how long it is, and everything
     * the label would say about it. Written to be read -- a diff has to be judgeable by eye,
     * which is the whole difference between a golden file and a rubber stamp.
     */
    private fun renderBlocks(source: String, result: ScanResult): String {
        val lineStarts = lineStartsOf(source)
        val builder = StringBuilder()
        builder.append("counts: types=").append(result.counts.types)
            .append(" namespaces=").append(result.counts.namespaces).append('\n')
        builder.append("blocks: ").append(result.accents.size / 2).append('\n')
        builder.append('\n')

        val open = ArrayDeque<BraceAccent>()
        for (accent in result.accents) {
            if (accent.isOpening) {
                open.addLast(accent)
                continue
            }
            val opening = open.removeLast()
            val closeLine = lineOf(lineStarts, accent.offset)
            val declarationLine = closeLine - accent.spannedLines
            val openLine = lineOf(lineStarts, opening.offset)

            builder.append("decl=").append(declarationLine)
                .append(" open=").append(openLine)
                .append(" close=").append(closeLine)
                .append(" span=").append(accent.spannedLines)
                .append(' ').append(accent.kind)
                .append(" '").append(accent.keyword).append('\'')
                .append(" name='").append(nameOf(source, accent)).append('\'')
            if (accent.isNested) {
                builder.append(" nested")
            }
            if (accent.isLambda) {
                builder.append(" lambda")
            }
            if (accent.isAccessor) {
                builder.append(" accessor")
            }
            if (accent.siblingOrdinal > 0) {
                builder.append(" ordinal=").append(accent.siblingOrdinal)
            }
            builder.append('\n')
        }
        return builder.toString()
    }

    private fun nameOf(source: String, accent: BraceAccent): String {
        if (accent.nameOffset < 0 || accent.nameLength <= 0) {
            return ""
        }
        return source.substring(accent.nameOffset, accent.nameOffset + accent.nameLength)
    }

    /** Offsets every line starts at, so a line number is a binary search rather than a count. */
    private fun lineStartsOf(source: String): IntArray {
        val starts = ArrayList<Int>()
        starts.add(0)
        for (index in source.indices) {
            if (source[index] == '\n') {
                starts.add(index + 1)
            }
        }
        return starts.toIntArray()
    }

    /** 1-based, matching the gutter. */
    private fun lineOf(lineStarts: IntArray, offset: Int): Int {
        var low = 0
        var high = lineStarts.size - 1
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (lineStarts[middle] <= offset) {
                low = middle
            } else {
                high = middle - 1
            }
        }
        return low + 1
    }

    private fun flavorOf(fileName: String): Flavor {
        return Dialects.forExtension(fileName.substringAfterLast('.', ""))
    }

    private fun forEachSample(check: (String, String, ScanResult) -> Unit) {
        for (sample in sampleFiles()) {
            val source = sample.readText()
            check(sample.name, source, BraceScanner(source, flavorOf(sample.name), ScanOptions()).scan())
        }
    }

    /**
     * Every sample in the resource folder, golden files excluded.
     *
     * Read through the classloader rather than by a relative path: the working directory of a
     * test run is not promised to be the project root, and it differs between Gradle and an
     * IDE run configuration.
     */
    private fun sampleFiles(): List<File> {
        val url = javaClass.getResource(SAMPLES_RESOURCE)
        if (url == null) {
            return emptyList()
        }
        val directory = File(url.toURI())
        if (!directory.isDirectory) {
            return emptyList()
        }
        return directory.listFiles()
            .orEmpty()
            .filter { it.isFile && !it.name.endsWith(GOLDEN_SUFFIX) && !it.name.endsWith(".md") }
            .sortedBy { it.name }
    }

    private companion object {
        const val SAMPLES_RESOURCE = "/samples"
        const val GOLDEN_SUFFIX = ".expected.txt"
    }
}
