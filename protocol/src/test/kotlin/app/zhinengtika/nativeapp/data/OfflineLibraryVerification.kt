package app.zhinengtika.nativeapp.data

import java.io.File
import java.nio.file.Files
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipFile
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Explicit host acceptance against a user-supplied corpus, separate from ordinary unit tests. */
fun main(args: Array<String>) {
    require(args.size == 2) { "Usage: OfflineLibraryVerification <library.zip> <report.json>" }
    val archive = File(args[0]).canonicalFile
    require(archive.isFile)
    val output = File(args[1]).canonicalFile
    val root = Files.createTempDirectory("zhinengtika-519-library-").toFile()
    val random = SecureRandom()
    val key = SecretKeySpec(ByteArray(32).also(random::nextBytes), "AES")
    var decryptions = 0
    val encrypt: (String) -> String = { text ->
        val nonce = ByteArray(12).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(128, nonce))
        Base64.getEncoder().encodeToString(nonce + cipher.doFinal(text.toByteArray(Charsets.UTF_8)))
    }
    val decrypt: (String) -> String = { stored ->
        decryptions++
        val bytes = Base64.getDecoder().decode(stored)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        cipher.doFinal(bytes.copyOfRange(12, bytes.size)).toString(Charsets.UTF_8)
    }
    val oldCards = ZipFile(archive).use { zip ->
        zip.entries().asSequence().filter { "/题卡JSON/" in it.name && it.name.endsWith(".json") }
            .sortedBy { it.name }.take(100).mapIndexed { index, entry ->
                val imported = TemplateTransfer.importJson(zip.getInputStream(entry).use { it.readBytes() })
                OfflineTemplateEntry("legacy-$index", imported.card, "other", entry.name.substringAfterLast('/'), warnings = imported.warnings)
            }.toList()
    }
    check(oldCards.size == 100)
    var legacyClears = 0
    val legacy = object : LegacyOfflineLibrarySource {
        override fun read() = LegacyOfflineLibrarySnapshot(oldCards, oldCards.map {
            DirectoryEntry("directory-${it.id}", it.card.code, it.card.title, "other", it.sourceLabel)
        })
        override fun clear() { legacyClears++ }
    }
    val started = System.nanoTime()
    val store = FileOfflineTemplateStore(root, encrypt, decrypt, legacy)
    check(store.summaries().size == 100)
    check(store.summaries().none { it.subject == "other" || it.subject == "未分类" })
    check(legacyClears == 1)
    val oldIds = store.summaries().associate { it.code to it.id }
    fun importer(target: FileOfflineTemplateStore) = OfflineLibraryImporter(
        saveCard = { target.saveCard(it) }, saveDirectory = { target.saveDirectory(it) },
        existingCodes = target.summaries().map { it.code }.toSet(),
        knownSubjects = target.directory().associate { it.code to it.subject },
    )
    val firstBatch = importer(store)
    firstBatch.importZip(archive, onProgress = { if (it.completed % 100 == 0) println("Import ${it.completed}/${it.total}") })
    val firstReport = firstBatch.report()
    check(firstReport.failedFiles == 0) { firstReport.details() }
    check(firstReport.addedCards == 419 && firstReport.updatedCards == 100) { firstReport.summary() }
    val firstImportMillis = (System.nanoTime() - started) / 1_000_000
    check(firstReport.warnings.none { it in TemplateTransfer.importNotices })
    val summaries = store.summaries()
    check(summaries.size == 519 && summaries.map { it.code }.distinct().size == 519)
    check(summaries.filter { it.code in oldIds }.all { it.id == oldIds[it.code] })
    val expectedSubjects = linkedMapOf("语文" to 67, "数学" to 101, "英语" to 87, "物理" to 111, "化学" to 84, "生物" to 69)
    check(summaries.groupingBy { it.subject }.eachCount() == expectedSubjects)
    check(store.directory().size == 519)
    check(store.directory().groupingBy { it.subject }.eachCount() == expectedSubjects)
    val restarted = FileOfflineTemplateStore(root, encrypt, decrypt)
    decryptions = 0
    check(restarted.summaries().size == 519)
    check(decryptions == 1) { "Metadata listing decrypted card bodies" }
    val sample = summaries.first()
    decryptions = 0
    val one = requireNotNull(restarted.card(sample.id))
    check(one.card.code == sample.code && decryptions == 2) { "Opening one card decrypted the whole library" }
    var leaves = 0
    for (summary in restarted.summaries()) {
        val card = requireNotNull(restarted.card(summary.id))
        leaves += card.card.questions.sumOf { it.leaves().size }
        check(card.subject == summary.subject)
        check(card.card.questions.flatMap { it.leaves() }.all { it.previousAnswer.isEmpty() && it.previousRawScan.isEmpty() })
    }
    check(leaves == 7355)
    val repeatStarted = System.nanoTime()
    val repeatBatch = importer(restarted)
    repeatBatch.importZip(archive)
    val repeated = repeatBatch.report()
    check(repeated.addedCards == 0 && repeated.updatedCards == 519 && repeated.failedFiles == 0) { repeated.details() }
    check(restarted.summaries().size == 519 && restarted.directory().size == 519)
    check(restarted.summaries().associate { it.code to it.id } == summaries.associate { it.code to it.id })
    val encryptedIndex = File(root, "index.enc").readText()
    check(!encryptedIndex.contains(sample.title) && !encryptedIndex.contains(sample.code))
    val result = buildJsonObject {
        put("validatedAtUtc", Instant.now().toString())
        put("scope", "Host JVM: production importer and file engine with AES-GCM; Android SAF/UI/Keystore execution is separate")
        put("archive", archive.name)
        put("passed", true)
        put("legacyCardsMigrated", 100)
        put("legacyIdsPreserved", true)
        put("legacyOtherSubjectsRepaired", true)
        put("newCardsAdded", firstReport.addedCards)
        put("existingCardsUpdated", firstReport.updatedCards)
        put("cardsAfterImport", summaries.size)
        put("directoryEntries", 519)
        put("answerableQuestionsReadAfterRestart", leaves)
        put("commonNoticeCountInBatchReport", 0)
        put("failedFiles", firstReport.failedFiles)
        put("repeatImportAdded", repeated.addedCards)
        put("repeatImportUpdated", repeated.updatedCards)
        put("metadataOnlyListDecryptions", 1)
        put("singleCardOpenDecryptions", 2)
        put("firstImportAndMigrationMillis", firstImportMillis)
        put("repeatImportMillis", (System.nanoTime() - repeatStarted) / 1_000_000)
        putJsonObject("subjects") { expectedSubjects.forEach { (name, count) -> put(name, count) } }
    }
    output.parentFile.mkdirs()
    output.writeText(Json { prettyPrint = true }.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), result) + "\n")
    println(result.toString())
}
