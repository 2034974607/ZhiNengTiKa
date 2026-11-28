package app.zhinengtika.nativeapp.data

import java.io.File
import java.nio.file.Files
import java.util.concurrent.CancellationException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class OfflineLibraryImporterTest {
    @Test
    fun batchReportsOnceAndCountsUpdatesWithoutRepeatingCommonNotices() {
        val cards = linkedMapOf<String, OfflineTemplateEntry>()
        val batch = OfflineLibraryImporter(
            saveCard = { cards[it.card.code] = it }, saveDirectory = {}, existingCodes = setOf("known-1"),
        )
        batch.importCard(card("known-1", "物理测试"), "known-1.json")
        repeat(120) { batch.importCard(card("new-$it", "数学测试"), "new-$it.json") }
        batch.importCard("not json".toByteArray(), "broken.json")
        val report = batch.report()
        assertEquals(120, report.addedCards)
        assertEquals(1, report.updatedCards)
        assertEquals(1, report.failedFiles)
        assertTrue(report.warnings.isEmpty())
        assertFalse(report.details().contains("账号身份"))
        assertTrue(cards.values.all { it.warnings.isEmpty() })
        assertEquals("物理", cards["known-1"]!!.subject)
    }

    @Test
    fun zipPrefersCanonicalCopiesAndImportsSubjectListsBeforeCards() {
        val file = createZip(linkedMapOf(
            "library/原始资源/templateData/demo-1" to "invalid legacy copy".toByteArray(),
            "library/题卡JSON/生物/demo-1.json" to card("demo-1", "合成资料"),
            "library/科目目录/生物.txt" to "合成资料\ndemo-1".toByteArray(),
            "library/题卡索引.json" to "ignored metadata".toByteArray(),
        ))
        val events = mutableListOf<String>()
        val progress = mutableListOf<OfflineImportProgress>()
        val batch = OfflineLibraryImporter(
            saveCard = { events += "card:${it.card.code}:${it.subject}" },
            saveDirectory = { entries -> events += "directory:${entries.single().subject}" },
        )
        batch.importZip(file, onProgress = { progress += it })
        assertEquals(listOf("directory:生物", "card:demo-1:生物"), events)
        assertEquals(1, batch.report().addedCards)
        assertEquals(1, batch.report().directoryRecords)
        assertEquals(0, batch.report().failedFiles)
        assertEquals(2, progress.last().completed)
        assertEquals(2, progress.last().total)
    }

    @Test
    fun explicitBatchSubjectOverridesFileAndDirectoryWhileJsonSubjectIsRetained() {
        val stored = mutableListOf<OfflineTemplateEntry>()
        val batch = OfflineLibraryImporter(saveCard = { stored += it }, saveDirectory = {})
        batch.importCard(card("one", "合成资料", "chemistry"), "one.json")
        batch.importCard(card("two", "数学测试", "chemistry"), "物理/two.json", subjectHint = "语文")
        assertEquals(listOf("化学", "语文"), stored.map { it.subject })
    }

    @Test
    fun badArchivePathsAndOversizedStreamsFailBeforeWritingAnyCards() {
        val file = createZip(mapOf("../demo.json" to card("demo", "测试")))
        var saves = 0
        val batch = OfflineLibraryImporter(saveCard = { saves++ }, saveDirectory = {})
        assertThrows(IllegalArgumentException::class.java) { batch.importZip(file) }
        assertEquals(0, saves)
        assertThrows(IllegalArgumentException::class.java) {
            OfflineLibraryImporter.readBounded(ByteArray(17).inputStream(), 16)
        }
    }

    @Test
    fun cancellationEscapesRatherThanBecomingAFileFailure() {
        val batch = OfflineLibraryImporter(saveCard = { throw CancellationException() }, saveDirectory = {})
        assertThrows(CancellationException::class.java) { batch.importCard(card("demo", "测试"), "demo.json") }
        assertEquals(0, batch.report().failedFiles)
    }

    private fun card(code: String, title: String, subject: String? = null): ByteArray = """
        {"code":"$code","title":"$title",${subject?.let { "\"subject\":\"$it\"," }.orEmpty()}
         "questions":[{"id":"q-$code","order":"1","contentHtml":"<p>题干</p>","options":[],"answer":"A","explanationHtml":"解析"}]}
    """.trimIndent().toByteArray()

    private fun createZip(files: Map<String, ByteArray>): File {
        val path = Files.createTempFile("offline-importer-test-", ".zip").toFile()
        path.deleteOnExit()
        ZipOutputStream(path.outputStream()).use { archive ->
            files.forEach { (name, content) ->
                archive.putNextEntry(ZipEntry(name))
                archive.write(content)
                archive.closeEntry()
            }
        }
        return path
    }
}
