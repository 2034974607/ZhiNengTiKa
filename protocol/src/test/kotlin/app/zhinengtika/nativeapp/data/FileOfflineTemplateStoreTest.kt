package app.zhinengtika.nativeapp.data

import java.io.File
import java.io.IOException
import java.util.Base64
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileOfflineTemplateStoreTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test
    fun supportsMoreThanTwoThousandCardsAndReopensByIdWithoutLoadingBodiesForLists() {
        val fixture = Fixture()
        val original = (1..2_001).map { entry("card-$it") }
        fixture.open().saveCards(original.asSequence())
        val restarted = fixture.open()
        fixture.crypto.decryptions = 0
        val rows = restarted.summaries()
        assertEquals(2_001, rows.size)
        assertEquals(1, fixture.crypto.decryptions) // One metadata decryption, zero card bodies.
        assertEquals(2_001, restarted.directory().size)
        fixture.crypto.decryptions = 0
        val chosen = restarted.card(original[1_937].id)
        assertEquals(original[1_937].card, chosen!!.card)
        assertEquals(2, fixture.crypto.decryptions) // Metadata plus only the selected card.
        assertNull(restarted.card("unknown-id"))
    }

    @Test
    fun importingExistingHundredThenWholeLibraryAndRepeatingDoesNotCreate619OrNewIds() {
        val fixture = Fixture()
        val store = fixture.open()
        val original = (1..100).map { entry("card-$it") }
        original.forEach(store::saveCard)
        (1..519).forEach { number ->
            store.saveCard(entry("card-$number", content = "updated-$number"))
        }
        assertEquals(519, store.summaries().size)
        for (old in original) {
            val current = store.card(old.id)!!
            assertEquals(old.id, current.id)
            assertEquals("updated-${old.card.code.removePrefix("card-")}", current.card.questions.single().contentHtml)
        }
        val ids = store.summaries().associate { it.code to it.id }
        store.saveCards((1..519).asSequence().map { entry("card-$it", content = "final-$it") })
        assertEquals(ids, fixture.open().summaries().associate { it.code to it.id })
        assertEquals(519, store.directory().size)
    }

    @Test
    fun savingAnotherCardDoesNotRewriteExistingEncryptedBodyAndDiskContainsNoPlainMetadata() {
        val fixture = Fixture()
        val store = fixture.open()
        val first = entry("UNIQUE_SENSITIVE_CODE_01", content = "PRIVATE_SYNTHETIC_CONTENT")
        store.saveCard(first)
        val body = fixture.cardFiles().single()
        val originalBytes = body.readBytes()
        store.saveCard(entry("other-card"))
        assertArrayEquals(originalBytes, body.readBytes())
        for (file in fixture.root.walkTopDown().filter(File::isFile)) {
            val text = file.readText()
            assertFalse(text.contains("UNIQUE_SENSITIVE_CODE_01"))
            assertFalse(text.contains("PRIVATE_SYNTHETIC_CONTENT"))
            assertFalse(text.contains(first.id))
        }
    }

    @Test
    fun directoryUpdatesAndAutomaticCardDirectoryUseCodeAndPreserveDirectoryId() {
        val fixture = Fixture()
        val store = fixture.open()
        val initial = DirectoryEntry(UUID.randomUUID().toString(), "subject-card", "旧标题", "other", "old.txt")
        store.saveDirectory(listOf(initial, initial.copy(id = UUID.randomUUID().toString())))
        store.saveDirectory(listOf(initial.copy(id = UUID.randomUUID().toString(), title = "物理 新标题", subject = "物理")))
        assertEquals(1, store.directory().size)
        assertEquals(initial.id, store.directory().single().id)
        assertEquals("物理", store.directory().single().subject)
        store.saveCard(entry("subject-card", subject = "other"))
        assertEquals(1, store.directory().size)
        assertEquals(initial.id, store.directory().single().id)
        assertEquals("物理", store.summaries().single().subject)
        store.saveDirectory(listOf(initial.copy(id = UUID.randomUUID().toString(), subject = "other")))
        assertEquals("物理", fixture.open().directory().single().subject)
        assertEquals("物理", store.card(store.summaries().single().id)!!.subject)
    }

    @Test
    fun quotaCheckAllowsReplacementAtCapacityAndRejectsOversizeWithoutReplacingOldCard() {
        val fixture = Fixture()
        val first = entry("quota-card", content = "x".repeat(400))
        val cardBytes = Json.encodeToString(TemplateCard.serializer(), first.card).toByteArray().size
        val limits = OfflineLibraryLimits(maxCards = 1, maxTotalBytes = cardBytes.toLong(), maxCardBytes = cardBytes)
        val store = fixture.open(limits = limits)
        store.saveCard(first)
        store.saveCard(entry("quota-card", content = "shorter"))
        assertEquals(first.id, store.summaries().single().id)
        assertEquals("shorter", store.card(first.id)!!.card.questions.single().contentHtml)
        assertThrows(OfflineStorageException::class.java) { store.saveCard(entry("new-card")) }
        assertThrows(OfflineStorageException::class.java) {
            store.saveCard(entry("quota-card", content = "x".repeat(1_000)))
        }
        assertEquals("shorter", fixture.open(limits = limits).card(first.id)!!.card.questions.single().contentHtml)
    }

    @Test
    fun totalQuotaUsesReplacementSizeAndDirectoryLimitIsAppliedAfterDeduplication() {
        val fixture = Fixture()
        val first = entry("card-a", content = "x".repeat(500))
        val second = entry("card-b", content = "y".repeat(500))
        val size = listOf(first, second).sumOf { Json.encodeToString(TemplateCard.serializer(), it.card).toByteArray().size.toLong() }
        val limits = OfflineLibraryLimits(maxTotalBytes = size, maxDirectoryEntries = 2)
        val store = fixture.open(limits = limits)
        store.saveCards(sequenceOf(first, second))
        store.saveCard(first.copy(card = first.card.copy(title = "new")))
        store.saveDirectory(store.directory().map { it.copy(id = UUID.randomUUID().toString(), title = "new directory") })
        assertEquals(2, store.directory().size)
        assertThrows(OfflineStorageException::class.java) {
            store.saveCard(first.copy(card = first.card.copy(questions = listOf(first.card.questions.single().copy(contentHtml = "x".repeat(1_000))))))
        }
        assertEquals(2, fixture.open(limits = limits).summaries().size)
    }

    @Test
    fun indexCommitFailureKeepsOriginalCardAndDirectoryThenRestartRecovers() {
        val fixture = Fixture()
        var rejectIndex = false
        val store = fixture.open(writer = { file, bytes ->
            if (rejectIndex && file.name == "index.enc") throw IOException("Synthetic index disk failure")
            OfflineLibraryFileIo.writeAtomically(file, bytes)
        })
        val original = entry("existing-card", content = "original")
        store.saveCard(original)
        val indexBefore = File(fixture.root, "index.enc").readBytes()
        rejectIndex = true
        val error = assertThrows(OfflineStorageException::class.java) {
            store.saveCard(entry("existing-card", content = "must not replace"))
        }
        assertTrue(error.message!!.contains("Synthetic index disk failure"))
        assertArrayEquals(indexBefore, File(fixture.root, "index.enc").readBytes())
        assertEquals(1, fixture.cardFiles().size)
        val restarted = fixture.open()
        assertEquals(original.card, restarted.card(original.id)!!.card)
        assertEquals(1, restarted.directory().size)
    }

    @Test
    fun encryptionFailureAndBatchParserFailureDoNotCommitPartOfTheBatch() {
        val fixture = Fixture()
        val store = fixture.open()
        val old = entry("old")
        store.saveCard(old)
        fixture.crypto.failEncryption = true
        assertThrows(OfflineStorageException::class.java) { store.saveCard(entry("never-written")) }
        fixture.crypto.failEncryption = false
        val batch = sequence {
            yield(entry("batch-first"))
            throw IOException("Synthetic parser failure after one card")
        }
        assertThrows(OfflineStorageException::class.java) { store.saveCards(batch) }
        assertEquals(listOf(old.id), fixture.open().summaries().map { it.id })
        assertEquals(1, fixture.cardFiles().size)
    }

    @Test
    fun interruptedWriteCandidatesAreCollectedWithoutTouchingCommittedBodies() {
        val fixture = Fixture()
        val old = entry("old")
        fixture.open().saveCard(old)
        val orphan = File(fixture.root, "cards/card-${UUID.randomUUID().toString().replace("-", "")}.enc")
        val temporaryIndex = File(fixture.root, ".index.enc.new-${UUID.randomUUID()}")
        val temporaryCard = File(fixture.root, "cards/.card-${UUID.randomUUID().toString().replace("-", "")}.enc.new-${UUID.randomUUID()}")
        listOf(orphan, temporaryIndex, temporaryCard).forEach { it.writeText("interrupted candidate") }
        val restarted = fixture.open()
        assertEquals(old.card, restarted.card(old.id)!!.card)
        assertTrue(listOf(orphan, temporaryIndex, temporaryCard).none(File::exists))
        assertEquals(1, fixture.cardFiles().size)
    }

    @Test
    fun migratingBothOldKeysPreservesUuidDeduplicatesCodeAndClearsOnlyAfterPersistentIndex() {
        val fixture = Fixture()
        val original = (1..100).map { entry("migrate-$it", subject = "other") }
        val old = LegacyOfflineLibrarySnapshot(
            original + original.first().copy(id = UUID.randomUUID().toString(), card = original.first().card.copy(title = "物理 更新题名")),
            listOf(DirectoryEntry(UUID.randomUUID().toString(), "migrate-1", "物理 目录名", "物理", "legacy.txt")),
        )
        val source = MemoryLegacy(old)
        source.beforeClear = {
            val persisted = fixture.open().summaries()
            assertEquals(100, persisted.size)
            assertEquals(original.map { it.id }.toSet(), persisted.map { it.id }.toSet())
        }
        val store = fixture.open(legacy = source)
        assertEquals(100, store.summaries().size)
        assertEquals(1, source.reads)
        assertEquals(1, source.clears)
        assertNull(source.snapshot)
        assertEquals("物理", store.card(original.first().id)!!.subject)
        assertEquals("物理 更新题名", fixture.open().card(original.first().id)!!.card.title)
    }

    @Test
    fun failedLegacyMigrationRetainsBothOldKeysAndCanRetryWithoutLosingIds() {
        val fixture = Fixture()
        val original = (1..4).map { entry("legacy-$it") }
        val snapshot = LegacyOfflineLibrarySnapshot(original, listOf(DirectoryEntry("directory-id", "extra-directory", "额外目录", "other", "legacy.txt")))
        val source = MemoryLegacy(snapshot)
        var bodyWrites = 0
        val broken = fixture.open(legacy = source, writer = { file, bytes ->
            if (file.name.startsWith("card-") && ++bodyWrites == 3) throw IOException("Synthetic interrupted migration")
            OfflineLibraryFileIo.writeAtomically(file, bytes)
        })
        assertThrows(OfflineStorageException::class.java) { broken.summaries() }
        assertSame(snapshot, source.snapshot)
        assertEquals(0, source.clears)
        assertFalse(File(fixture.root, "index.enc").exists())
        val recovered = fixture.open(legacy = source)
        assertEquals(original.map { it.id }, recovered.summaries().map { it.id })
        assertEquals(5, recovered.directory().size)
        assertEquals(1, source.clears)
        assertEquals(4, fixture.cardFiles().size)
    }

    @Test
    fun failedLegacyCleanupDoesNotRerunMigrationOrResurrectADeletedCard() {
        val fixture = Fixture()
        val old = entry("legacy-card")
        val source = MemoryLegacy(LegacyOfflineLibrarySnapshot(listOf(old), emptyList())).apply { failClear = true }
        assertThrows(OfflineStorageException::class.java) { fixture.open(legacy = source).summaries() }
        assertEquals(1, source.reads)
        assertNotNull(source.snapshot)
        // The new index was already committed, so a later user deletion must never be undone.
        fixture.open().deleteCard(old.id)
        source.failClear = false
        assertTrue(fixture.open(legacy = source).summaries().isEmpty())
        assertEquals(1, source.reads)
        assertNull(source.snapshot)
    }

    @Test
    fun clearAllCommitsEmptyMigrationMarkerEvenWhenLegacyCleanupFails() {
        val fixture = Fixture()
        val old = entry("legacy-card")
        val source = MemoryLegacy(LegacyOfflineLibrarySnapshot(listOf(old), emptyList())).apply { failClear = true }
        val store = fixture.open(legacy = source)
        assertThrows(OfflineStorageException::class.java) { store.clearAll() }
        assertEquals(0, source.reads)
        source.failClear = false
        val restarted = fixture.open(legacy = source)
        assertTrue(restarted.summaries().isEmpty())
        assertTrue(restarted.directory().isEmpty())
        assertEquals(0, source.reads)
        assertNull(source.snapshot)
    }

    @Test
    fun corruptLegacyReadAndCorruptIndexAreReportedInsteadOfResettingTheLibrary() {
        val fixture = Fixture()
        var clearCalls = 0
        val damagedLegacy = object : LegacyOfflineLibrarySource {
            override fun read(): LegacyOfflineLibrarySnapshot = throw IOException("Unreadable legacy directory key")
            override fun clear() { clearCalls++ }
        }
        assertThrows(OfflineStorageException::class.java) { fixture.open(legacy = damagedLegacy).summaries() }
        assertEquals(0, clearCalls)
        assertFalse(File(fixture.root, "index.enc").exists())
        fixture.open().saveCard(entry("recoverable"))
        File(fixture.root, "index.enc").writeText("CORRUPTED_CIPHERTEXT")
        assertThrows(OfflineStorageException::class.java) { fixture.open().summaries() }
        assertEquals("CORRUPTED_CIPHERTEXT", File(fixture.root, "index.enc").readText())
    }

    @Test
    fun damagedSingleBodyIsReportedWhenOpenedAndDoesNotRequireDecryptingAllOtherCards() {
        val fixture = Fixture()
        val original = entry("body-card")
        fixture.open().saveCard(original)
        fixture.cardFiles().single().writeText("CORRUPTED_BODY")
        val restarted = fixture.open()
        assertEquals(1, restarted.summaries().size)
        assertThrows(OfflineStorageException::class.java) { restarted.card(original.id) }
    }

    @Test
    fun deletingCardDirectoryAndClearingPersistAcrossRestarts() {
        val fixture = Fixture()
        val store = fixture.open()
        val a = entry("delete-a")
        val b = entry("delete-b")
        store.saveCards(sequenceOf(a, b))
        store.deleteCard(a.id)
        val directoryId = store.directory().first { it.code == a.card.code }.id
        store.deleteDirectory(directoryId)
        assertNull(fixture.open().card(a.id))
        assertEquals(listOf(b.card.code), fixture.open().directory().map { it.code })
        store.clearAll()
        assertTrue(fixture.open().summaries().isEmpty())
        assertTrue(fixture.open().directory().isEmpty())
        assertTrue(fixture.cardFiles().isEmpty())
    }

    @Test
    fun conflictingIdsNeverSilentlyReplaceDifferentCards() {
        val fixture = Fixture()
        val store = fixture.open()
        val original = entry("first")
        store.saveCard(original)
        assertThrows(OfflineStorageException::class.java) { store.saveCard(entry("second").copy(id = original.id)) }
        assertEquals(listOf(original.card.code), fixture.open().summaries().map { it.code })
    }

    private inner class Fixture {
        val root = temporary.newFolder()
        val crypto = TestCipher()
        fun open(
            legacy: LegacyOfflineLibrarySource? = null,
            limits: OfflineLibraryLimits = OfflineLibraryLimits(),
            writer: (File, ByteArray) -> Unit = OfflineLibraryFileIo::writeAtomically,
        ) = FileOfflineTemplateStore(root, crypto::encrypt, crypto::decrypt, legacy, limits, writer)
        fun cardFiles(): List<File> = File(root, "cards").listFiles()!!.filter { it.name.endsWith(".enc") && !it.name.startsWith('.') }
    }

    private class TestCipher {
        // Synthetic test-only key; no Android credentials, keystore, or signing data are read.
        private val key = SecretKeySpec(ByteArray(16) { (it + 3).toByte() }, "AES")
        var decryptions = 0
        var failEncryption = false
        fun encrypt(plain: String): String {
            if (failEncryption) throw IOException("Synthetic encryption failure")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            return Base64.getEncoder().encodeToString(iv + cipher.doFinal(plain.toByteArray(Charsets.UTF_8)))
        }
        fun decrypt(encoded: String): String {
            decryptions++
            val payload = Base64.getDecoder().decode(encoded)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, payload.copyOfRange(0, 12)))
            return cipher.doFinal(payload.copyOfRange(12, payload.size)).toString(Charsets.UTF_8)
        }
    }

    private class MemoryLegacy(var snapshot: LegacyOfflineLibrarySnapshot?) : LegacyOfflineLibrarySource {
        var reads = 0
        var clears = 0
        var failClear = false
        var beforeClear: (() -> Unit)? = null
        override fun read(): LegacyOfflineLibrarySnapshot? { reads++; return snapshot }
        override fun clear() {
            clears++
            beforeClear?.invoke()
            if (failClear) throw IOException("Synthetic old-key commit failure")
            snapshot = null
        }
    }

    private fun entry(code: String, content: String = "合成题干", subject: String = "物理") = OfflineTemplateEntry(
        id = UUID.randomUUID().toString(),
        card = TemplateCard(code, "合成题卡 $code", listOf(QuestionItem(
            id = "question-$code", order = "1", contentHtml = content,
            options = listOf(QuestionOption("A", "合成选项")), answer = "A", explanationHtml = "合成解析",
        ))),
        subject = subject, sourceLabel = "$code.json", importedAtMillis = 123456L,
        warnings = listOf("仅供离线阅读"),
    )
}
