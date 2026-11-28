package app.zhinengtika.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateTransferTest {
    @Test
    fun legacyDecryptedImportWhitelistsQuestionTreeAndNeverReadsHistoricalAnswers() {
        val child = legacyQuestion("synthetic-child").withFields(
            "answer" to buildJsonObject {
                put("answer", "SYNTHETIC_PRIVATE_PREVIOUS")
                put("rawScan", "https://private.invalid/SYNTHETIC_PRIVATE_UPLOAD.jpg")
                put("studentId", "SYNTHETIC_STUDENT")
            },
            "reviewAnswerHistory" to JsonArray(listOf(JsonPrimitive("SYNTHETIC_REVIEW"))),
        )
        val record = legacyQuestion("synthetic-parent", listOf(child)).let { original ->
            val source = (original.getValue("question") as JsonObject).withFields(
                "id" to JsonPrimitive(9_223_372_036_854_775_000L),
                "content" to JsonPrimitive("<p>合成材料</p><img src=\"https://questions.invalid/material.png\">"),
                "teacherId" to JsonPrimitive("SYNTHETIC_TEACHER"),
            )
            original.withFields("question" to source)
        }
        val root = legacyRoot(listOf(group(listOf(record), name = "首组名称")), code = null).let { original ->
            val data = (original.getValue("data") as JsonObject).withFields(
                "studentId" to JsonPrimitive("SYNTHETIC_STUDENT"),
                "schoolId" to JsonPrimitive("SYNTHETIC_SCHOOL"),
            )
            original.withFields("data" to data)
        }

        val imported = TemplateTransfer.importJson(root.toString(), "demo(2)-01")
        val parent = imported.card.questions.single()
        val encoded = Json.encodeToString(TemplateCard.serializer(), imported.card)

        assertEquals("demo(2)-01", imported.card.code)
        assertEquals("首组名称", imported.card.title)
        assertEquals("9223372036854775000", parent.id)
        assertEquals("<span>B</span>", parent.answer)
        assertTrue(parent.contentHtml.contains("https://questions.invalid/material.png"))
        assertEquals("", parent.children.single().previousAnswer)
        assertEquals("", parent.children.single().previousRawScan)
        listOf("SYNTHETIC_PRIVATE", "SYNTHETIC_STUDENT", "SYNTHETIC_SCHOOL", "SYNTHETIC_TEACHER", "SYNTHETIC_REVIEW").forEach {
            assertFalse(encoded.contains(it))
        }
        assertEquals(0L, imported.card.retrievedAtMillis)
        assertTrue(imported.warnings.any { it.contains("仅供离线阅读") })
    }

    @Test
    fun canonicalImportDropsPrivateFieldsAndUnknownMetadataRecursively() {
        val child = canonicalQuestion("synthetic-child").withFields(
            "previousAnswer" to JsonPrimitive("SYNTHETIC_PRIVATE_ANSWER"),
            "previousRawScan" to buildJsonObject { put("ignoredInvalidType", "https://private.invalid/SYNTHETIC_UPLOAD.jpg") },
            "schoolId" to JsonPrimitive("SYNTHETIC_SCHOOL"),
        )
        val parent = canonicalQuestion("synthetic-parent", listOf(child)).withFields(
            "previousAnswer" to JsonPrimitive("SYNTHETIC_PARENT_ANSWER"),
            "studentId" to JsonPrimitive("SYNTHETIC_STUDENT"),
            "review" to JsonPrimitive("SYNTHETIC_REVIEW"),
        )
        val source = canonicalRoot(listOf(parent)).withFields(
            "retrievedAtMillis" to JsonPrimitive(123456L),
            "teacherId" to JsonPrimitive("SYNTHETIC_TEACHER"),
        )
        val imported = TemplateTransfer.importJson(source.toString()).card
        val encoded = Json.encodeToString(TemplateCard.serializer(), imported)

        assertEquals("<p>合成题干</p>", imported.questions.single().contentHtml)
        assertEquals("<span>B</span>", imported.questions.single().children.single().answer)
        assertEquals(0L, imported.retrievedAtMillis)
        assertEquals("", imported.questions.single().previousAnswer)
        assertEquals("", imported.questions.single().children.single().previousRawScan)
        listOf("SYNTHETIC_PRIVATE", "SYNTHETIC_PARENT", "SYNTHETIC_UPLOAD", "SYNTHETIC_STUDENT", "SYNTHETIC_SCHOOL", "SYNTHETIC_TEACHER", "SYNTHETIC_REVIEW").forEach {
            assertFalse(encoded.contains(it))
        }
    }

    @Test
    fun rejectsLoginAccountAndNonTemplateDocuments() {
        listOf(
            """{"code":200,"data":{"accessToken":"synthetic","token":"synthetic","info":{"school":{}}}}""",
            """{"accounts":[{"username":"synthetic","password":"synthetic"}]}""",
            """{"code":200,"data":{"info":{"school":{}}}}""",
            """{"code":403,"data":{"questions":[]}}""",
            """{"code":"demo-1","questions":[]}""",
            "[]",
        ).forEach { source ->
            assertThrows(IllegalArgumentException::class.java) { TemplateTransfer.importJson(source, "demo-1") }
        }
    }

    @Test
    fun enforcesUtf8ByteLimitAndSafeCodeBounds() {
        val minimal = canonicalRoot(listOf(canonicalQuestion())).toString().toByteArray(Charsets.UTF_8)
        val exactlyAtLimit = minimal.copyOf(TemplateTransfer.MAX_IMPORT_BYTES)
        java.util.Arrays.fill(exactlyAtLimit, minimal.size, exactlyAtLimit.size, ' '.code.toByte())
        assertEquals("demo-1", TemplateTransfer.importJson(exactlyAtLimit).card.code)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(ByteArray(TemplateTransfer.MAX_IMPORT_BYTES + 1))
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(byteArrayOf(0xc3.toByte(), 0x28))
        }
        listOf("", "../outside", "bad/code", "bad\\code", "has space", "a..b", "x".repeat(129)).forEach { code ->
            assertThrows(IllegalArgumentException::class.java) {
                TemplateTransfer.importJson(canonicalRoot(listOf(canonicalQuestion()), code = code).toString())
            }
        }
        assertEquals("x".repeat(128), TemplateTransfer.importJson(
            canonicalRoot(listOf(canonicalQuestion()), code = "x".repeat(128)).toString(),
        ).card.code)
    }

    @Test
    fun enforcesDepthAndTotalQuestionLimitsForBothFormats() {
        assertEquals(1, TemplateTransfer.importJson(canonicalRoot(listOf(canonicalChain(12))).toString()).card.questions.size)
        assertEquals(1, TemplateTransfer.importJson(legacyRoot(listOf(group(listOf(legacyChain(12))))).toString()).card.questions.size)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(canonicalRoot(listOf(canonicalChain(13))).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(legacyRoot(listOf(group(listOf(legacyChain(13))))).toString())
        }
        assertEquals(2_000, TemplateTransfer.importJson(
            canonicalRoot(List(2_000) { canonicalQuestion("synthetic-$it") }).toString(),
        ).card.questions.size)
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(canonicalRoot(List(2_001) { canonicalQuestion("synthetic-$it") }).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson(legacyRoot(listOf(group(List(2_001) { legacyQuestion("synthetic-$it") }))).toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            TemplateTransfer.importJson("[".repeat(65) + "0" + "]".repeat(65))
        }
    }

    @Test
    fun usesGroupTitleBeforeEnvelopeFallbackAndPreservesAllGroups() {
        val first = group(listOf(legacyQuestion("synthetic-first")), name = "组名").withFields("templateName" to JsonPrimitive("组模板名"))
        val second = group(listOf(legacyQuestion("synthetic-second")), name = "次组")
        val root = legacyRoot(listOf(first, second), title = "信封标题")
        val imported = TemplateTransfer.importJson(root.toString())
        assertEquals("组模板名", imported.card.title)
        assertEquals(listOf("synthetic-first", "synthetic-second"), imported.card.questions.map { it.id })
        assertTrue(imported.warnings.any { it.contains("多个题组") })
        val noGroupName = buildJsonObject { put("questionsAnswers", JsonArray(listOf(legacyQuestion()))) }
        assertEquals("信封标题", TemplateTransfer.importJson(legacyRoot(listOf(noGroupName), title = "信封标题").toString()).card.title)
        assertEquals("demo-1", TemplateTransfer.importJson(legacyRoot(listOf(noGroupName), title = "").toString()).card.title)
    }

    @Test
    fun legacyListPreservesFinalNonNewlineCodeCrLfAndFirstDuplicateOrder() {
        val text = "\uFEFF第一卷\r\ndemo(2)-01\r\n重复卷名\r\ndemo(2)-01\r\n第二卷\r\nfinal_02"
        val list = parseLegacyList(text, " 数学 ")
        assertEquals(listOf("demo(2)-01", "final_02"), list.map { it.code })
        assertEquals(listOf("第一卷", "第二卷"), list.map { it.title })
        assertTrue(list.all { it.subject == "数学" })
        assertEquals(list, parseLegacyList("第一卷\ndemo(2)-01\n第二卷\nfinal_02\n", "数学"))
    }

    @Test
    fun malformedLegacyListRecordsAreSkippedWithBoundedNonSensitiveWarnings() {
        val parsed = TemplateTransfer.parseLegacyListWithWarnings(
            "正常记录\nok-1\n无效编号\ninvalid/path\n重复记录\nok-1\n缺少编号的末行",
            " ",
        )
        assertEquals(listOf(TemplateSummary("ok-1", "正常记录")), parsed.templates)
        assertEquals(3, parsed.warnings.size)
        assertTrue(parsed.warnings.any { it.contains("末尾") })
        assertFalse(parsed.warnings.joinToString().contains("invalid/path"))
        val manyBadRecords = (1..100).joinToString("\n") { "合成记录\ninvalid/path" }
        assertTrue(TemplateTransfer.parseLegacyListWithWarnings(manyBadRecords).warnings.size <= 33)
    }

    @Test
    fun htmlExportSanitizesEveryFragmentAndEscapesTitle() {
        val hostileHtml = """
            <p onclick="run()">安全正文</p><script>alert('unsafe')</script>
            <iframe src="https://unsafe.invalid/frame"></iframe>
            <a href="https://questions.invalid/help">安全链接</a>
            <a href="javascript:alert(1)">脚本链接</a><a href="file:///private">文件链接</a>
            <a href="data:text/html,unsafe">数据链接</a><a href="http://insecure.invalid">非HTTPS</a>
            <a href="/relative">相对链接</a><a href="jav&#97;script:alert(1)">编码脚本</a>
            <img src="https://questions.invalid/image.png" onerror="run()">
            <img src="data:image/png;base64,aaaa"><img src="file:///private"><img src="http://insecure.invalid/a.png">
        """.trimIndent()
        val question = QuestionItem(
            id = "synthetic-question", order = "<script>编号</script>",
            contentHtml = hostileHtml, options = listOf(QuestionOption("A", hostileHtml)),
            answer = hostileHtml, explanationHtml = hostileHtml,
        )
        val title = "<script>题卡 & 标题</script>"
        val html = TemplateTransfer.exportHtml(TemplateCard("demo-1", title, listOf(question)))
        val document = Jsoup.parse(html)
        assertEquals(title, document.title())
        assertTrue(document.select("meta[charset=UTF-8]").isNotEmpty())
        assertTrue(document.select("script,iframe,object,embed,style").isEmpty())
        document.allElements.forEach { element ->
            assertTrue(element.attributes().asList().none { it.key.startsWith("on", ignoreCase = true) || it.key == "style" })
        }
        document.select("a[href]").forEach { assertTrue(it.attr("href").startsWith("https://")) }
        document.select("img[src]").forEach { assertTrue(it.attr("src").startsWith("https://")) }
        assertEquals(4, document.select("img[src]").size)
        assertEquals(4, document.select("a[href]").size)
    }

    @Test
    fun htmlExportVisitsEveryParentAndLeafWithoutPrivatePreviousFields() {
        val leaf = QuestionItem("synthetic-leaf", "1(1)(1)", "合成叶子题干", listOf(QuestionOption("B", "合成选项")), "合成叶子答案", "合成叶子解析", "SYNTHETIC_PRIVATE_LEAF", "https://private.invalid/SYNTHETIC_PRIVATE.jpg")
        val child = QuestionItem("synthetic-child", "1(1)", "合成子材料", emptyList(), "", "合成子解析", children = listOf(leaf))
        val parent = QuestionItem("synthetic-parent", "1", "合成父材料", emptyList(), "合成父答案", "合成父解析", "SYNTHETIC_PRIVATE_PARENT", children = listOf(child))
        val html = TemplateTransfer.exportHtml(TemplateCard("demo-1", "合成题卡", listOf(parent)))
        val document = Jsoup.parse(html)
        assertEquals(3, document.select("article").size)
        listOf("合成父材料", "合成父答案", "合成父解析", "合成子材料", "合成子解析", "合成叶子题干", "合成选项", "合成叶子答案", "合成叶子解析").forEach {
            assertTrue(document.body().text().contains(it))
        }
        assertFalse(html.contains("SYNTHETIC_PRIVATE"))
        assertFalse(html.contains("private.invalid"))
    }

    private fun canonicalQuestion(id: String = "synthetic-question", children: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("id", id)
        put("order", "1")
        put("contentHtml", "<p>合成题干</p>")
        put("options", JsonArray(listOf(buildJsonObject { put("label", "B"); put("html", "合成选项") })))
        put("answer", "<span>B</span>")
        put("explanationHtml", "<p>合成解析</p>")
        put("children", JsonArray(children))
    }

    private fun canonicalRoot(questions: List<JsonObject>, code: String = "demo-1"): JsonObject = buildJsonObject {
        put("code", code)
        put("title", "合成题卡")
        put("questions", JsonArray(questions))
    }

    private fun legacyQuestion(id: String = "synthetic-question", children: List<JsonObject> = emptyList()): JsonObject = buildJsonObject {
        put("ordered", 1)
        put("question", buildJsonObject {
            put("id", id)
            put("content", "<p>合成题干</p>")
            put("optionB", "合成选项")
            put("answer", "<span>B</span>")
            put("answerExplanation", "<p>合成解析</p>")
        })
        put("childQuestionList", JsonArray(children))
    }

    private fun group(records: List<JsonObject>, name: String = ""): JsonObject = buildJsonObject {
        if (name.isNotEmpty()) put("name", name)
        put("questionsAnswers", JsonArray(records))
    }

    private fun legacyRoot(groups: List<JsonObject>, code: String? = "demo-1", title: String = "合成题卡"): JsonObject = buildJsonObject {
        put("code", 200)
        put("data", buildJsonObject {
            if (code != null) put("templateCode", code)
            put("templateName", title)
            put("questions", JsonArray(groups))
        })
    }

    private fun canonicalChain(depth: Int): JsonObject =
        canonicalQuestion("synthetic-depth-$depth", if (depth > 1) listOf(canonicalChain(depth - 1)) else emptyList())

    private fun legacyChain(depth: Int): JsonObject =
        legacyQuestion("synthetic-depth-$depth", if (depth > 1) listOf(legacyChain(depth - 1)) else emptyList())

    private fun JsonObject.withFields(vararg fields: Pair<String, JsonElement>): JsonObject =
        JsonObject(toMutableMap().apply { putAll(fields) })
}
