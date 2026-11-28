package app.zhinengtika.nativeapp.data

import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.safety.Safelist

/** Imported content is not an authenticated server response or a source of submission authority. */
data class ImportedTemplate(
    val card: TemplateCard,
    val warnings: List<String>,
)

@Serializable
data class TemplateSummary(
    val code: String,
    val title: String,
    val subject: String = "other",
)

data class ImportedTemplateList(
    val templates: List<TemplateSummary>,
    val warnings: List<String>,
)

/** SAF callers should bound their input stream before allocating the byte array, too. */
object TemplateTransfer {
    const val MAX_IMPORT_BYTES = 8 * 1024 * 1024
    const val MAX_QUESTION_DEPTH = 12
    const val MAX_QUESTIONS = 2_000
    private const val MAX_JSON_DEPTH = 64
    private const val READ_ONLY_WARNING = "导入题卡仅供离线阅读，不能作为在线提交或账号身份的依据。"
    private const val STRIPPED_WARNING = "只保留题目内容；历史答卷、账号资料和其他元数据不会导入。"
    private val codePattern = Regex("[A-Za-z0-9][A-Za-z0-9_.()\\-]{0,127}")
    private val json = Json { ignoreUnknownKeys = true }

    fun importJson(bytes: ByteArray, fallbackCode: String? = null): ImportedTemplate {
        require(bytes.size <= MAX_IMPORT_BYTES) { "题卡文件不能超过 8 MiB" }
        val text = try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
                .removePrefix("\uFEFF")
        } catch (_: java.nio.charset.CharacterCodingException) {
            throw IllegalArgumentException("题卡文件必须是有效的 UTF-8 文本")
        }
        require(text.isNotBlank()) { "题卡文件为空" }
        checkJsonNesting(text)
        val root = try {
            json.parseToJsonElement(text) as? JsonObject
                ?: throw IllegalArgumentException("题卡 JSON 的根节点必须是对象")
        } catch (_: SerializationException) {
            throw IllegalArgumentException("题卡文件不是有效的 JSON")
        }
        require(!containsCredentials(root) && !containsCredentials(root.objectAt("data"))) {
            "账号或登录数据不能作为题卡导入"
        }
        val warnings = mutableListOf(READ_ONLY_WARNING, STRIPPED_WARNING)
        val card = if (root["questions"] is JsonArray) {
            importCanonical(root)
        } else {
            importLegacy(root, fallbackCode, warnings)
        }
        return ImportedTemplate(card, warnings.toList())
    }

    fun importJson(text: String, fallbackCode: String? = null): ImportedTemplate =
        importJson(boundedUtf8(text), fallbackCode)

    fun parseLegacyList(text: String, subject: String = "other"): List<TemplateSummary> =
        parseLegacyListWithWarnings(text, subject).templates

    fun parseLegacyListWithWarnings(text: String, subject: String = "other"): ImportedTemplateList {
        boundedUtf8(text)
        val lines = text.removePrefix("\uFEFF").lineSequence().toMutableList()
        while (lines.lastOrNull()?.isBlank() == true) lines.removeAt(lines.lastIndex)
        val entries = mutableListOf<TemplateSummary>()
        val warnings = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        val normalizedSubject = subject.trim().ifBlank { "other" }
        var index = 0
        while (index + 1 < lines.size) {
            val title = lines[index].trim()
            val code = runCatching { safeCode(lines[index + 1]) }.getOrNull()
            val record = index / 2 + 1
            when {
                title.isBlank() || code == null -> addListWarning(warnings, "已跳过第 $record 条不完整或编号无效的记录。")
                !seen.add(code) -> addListWarning(warnings, "已忽略第 $record 条重复编号记录。")
                else -> entries += TemplateSummary(code, title, normalizedSubject)
            }
            index += 2
        }
        if (index < lines.size) addListWarning(warnings, "已跳过末尾缺少编号的记录。")
        return ImportedTemplateList(entries.toList(), warnings.toList())
    }

    /** Returns a complete UTF-8 HTML document; private previous answers are never included. */
    fun exportHtml(card: TemplateCard): String {
        val publicCard = whitelistCard(card)
        val document = Document.createShell("")
        document.outputSettings().charset(StandardCharsets.UTF_8).prettyPrint(false)
        document.head().appendElement("meta").attr("charset", "UTF-8")
        document.head().appendElement("meta").attr("name", "viewport").attr("content", "width=device-width, initial-scale=1")
        document.head().appendElement("meta").attr("name", "referrer").attr("content", "no-referrer")
        document.title(publicCard.title)
        val body = document.body()
        body.appendElement("h1").text(publicCard.title)
        body.appendElement("p").text("题卡编号：${publicCard.code}")
        publicCard.questions.forEach { appendQuestion(body, it) }
        return "<!doctype html>\n${document.outerHtml()}"
    }

    private fun importCanonical(root: JsonObject): TemplateCard {
        val nodes = root["questions"] as JsonArray
        require(nodes.isNotEmpty()) { "题卡不包含题目" }
        val publicRoot = JsonObject(root.filterKeys { it == "code" || it == "title" } +
            ("questions" to whitelistCanonicalTree(nodes, 1, QuestionBudget())))
        val decoded = try {
            json.decodeFromJsonElement(TemplateCard.serializer(), publicRoot)
        } catch (_: SerializationException) {
            throw IllegalArgumentException("原生题卡字段不完整或格式无效")
        }
        return whitelistCard(decoded)
    }

    private fun importLegacy(
        root: JsonObject,
        fallbackCode: String?,
        warnings: MutableList<String>,
    ): TemplateCard {
        require(root.stringAt("code") == "200") { "文件不是成功的离线题卡响应" }
        val data = root.objectAt("data") ?: throw IllegalArgumentException("离线题卡缺少 data")
        val groups = data["questions"] as? JsonArray
            ?: throw IllegalArgumentException("文件不包含离线题卡题组")
        require(groups.isNotEmpty()) { "题卡不包含题目" }
        val first = groups.first() as? JsonObject ?: throw IllegalArgumentException("题组格式无效")
        val code = safeCode(data.stringAt("templateCode")?.takeIf(String::isNotBlank) ?: fallbackCode.orEmpty())
        val title = first.stringAt("templateName")?.takeIf(String::isNotBlank)
            ?: first.stringAt("name")?.takeIf(String::isNotBlank)
            ?: data.stringAt("templateName")?.takeIf(String::isNotBlank)
            ?: code
        val budget = QuestionBudget()
        val questions = groups.flatMap { element ->
            val group = element as? JsonObject ?: throw IllegalArgumentException("题组格式无效")
            val records = group["questionsAnswers"] as? JsonArray
                ?: throw IllegalArgumentException("题组缺少 questionsAnswers")
            records.mapIndexed { index, record ->
                legacyQuestion(record as? JsonObject ?: throw IllegalArgumentException("题目格式无效"), index + 1, 1, budget)
            }
        }
        require(questions.isNotEmpty()) { "题卡不包含题目" }
        if (groups.size > 1) warnings += "多个题组已按原顺序合并，保留各题原始编号。"
        return TemplateCard(code, title, questions, retrievedAtMillis = 0L)
    }

    private fun legacyQuestion(record: JsonObject, fallbackOrder: Int, depth: Int, budget: QuestionBudget): QuestionItem {
        budget.visit(depth)
        val question = record.objectAt("question") ?: throw IllegalArgumentException("题目缺少 question")
        val id = questionId(question.stringAt("id"))
        val options = ('A'..'G').mapNotNull { label ->
            question.stringAt("option$label")?.takeIf(String::isNotBlank)?.let { QuestionOption(label.toString(), it) }
        }
        val childValue = record["childQuestionList"]
        val children = when {
            childValue == null || childValue == kotlinx.serialization.json.JsonNull -> emptyList()
            childValue is JsonArray -> childValue.mapIndexed { index, child ->
                legacyQuestion(child as? JsonObject ?: throw IllegalArgumentException("子题格式无效"), index + 1, depth + 1, budget)
            }
            else -> throw IllegalArgumentException("子题列表格式无效")
        }
        return QuestionItem(
            id = id,
            order = record.stringAt("ordered")?.takeIf(String::isNotBlank) ?: fallbackOrder.toString(),
            contentHtml = question.stringAt("content").orEmpty(),
            options = options,
            answer = question.stringAt("answer").orEmpty(),
            explanationHtml = question.stringAt("answerExplanation").orEmpty(),
            children = children,
        )
    }

    private fun whitelistCard(card: TemplateCard): TemplateCard {
        val code = safeCode(card.code)
        require(card.questions.isNotEmpty()) { "题卡不包含题目" }
        val budget = QuestionBudget()
        fun keep(item: QuestionItem, depth: Int): QuestionItem {
            budget.visit(depth)
            require(item.options.size <= 7 && item.options.map { it.label }.distinct().size == item.options.size) {
                "选择题选项格式无效"
            }
            require(item.options.all { it.label.length == 1 && it.label[0] in 'A'..'G' }) { "选择题选项格式无效" }
            return QuestionItem(
                id = questionId(item.id),
                order = item.order,
                contentHtml = item.contentHtml,
                options = item.options.map { QuestionOption(it.label, it.html) },
                answer = item.answer,
                explanationHtml = item.explanationHtml,
                children = item.children.map { keep(it, depth + 1) },
            )
        }
        return TemplateCard(code, card.title.ifBlank { code }, card.questions.map { keep(it, 1) }, retrievedAtMillis = 0L)
    }

    private fun whitelistCanonicalTree(nodes: JsonArray, depth: Int, budget: QuestionBudget): JsonArray =
        JsonArray(nodes.map { element ->
            budget.visit(depth)
            val node = element as? JsonObject ?: throw IllegalArgumentException("题目格式无效")
            val kept = node.filterKeys {
                it in setOf("id", "order", "contentHtml", "answer", "explanationHtml")
            }.toMutableMap()
            val options = node["options"] as? JsonArray ?: throw IllegalArgumentException("选择题选项格式无效")
            kept["options"] = JsonArray(options.map { option ->
                val value = option as? JsonObject ?: throw IllegalArgumentException("选择题选项格式无效")
                JsonObject(value.filterKeys { it == "label" || it == "html" })
            })
            kept["children"] = when (val children = node["children"]) {
                null -> JsonArray(emptyList())
                is JsonArray -> whitelistCanonicalTree(children, depth + 1, budget)
                else -> throw IllegalArgumentException("子题列表格式无效")
            }
            JsonObject(kept)
        })

    private fun appendQuestion(parent: Element, item: QuestionItem) {
        val article = parent.appendElement("article")
        article.appendElement("h2").text("题目 ${item.order}")
        appendFragment(article, "题干", item.contentHtml)
        if (item.options.isNotEmpty()) {
            val list = article.appendElement("ul")
            item.options.forEach { option ->
                val entry = list.appendElement("li")
                entry.appendElement("span").text("${option.label}. ")
                entry.appendElement("span").html(cleanFragment(option.html))
            }
        }
        appendFragment(article, "答案", item.answer)
        appendFragment(article, "解析", item.explanationHtml)
        item.children.forEach { appendQuestion(article, it) }
    }

    private fun appendFragment(parent: Element, label: String, fragment: String) {
        if (fragment.isBlank()) return
        parent.appendElement("h3").text(label)
        parent.appendElement("div").html(cleanFragment(fragment))
    }

    private fun cleanFragment(fragment: String): String {
        val safelist = Safelist.basicWithImages()
            .removeProtocols("a", "href", "ftp", "http", "https", "mailto")
            .addProtocols("a", "href", "https")
            .removeProtocols("img", "src", "http", "https")
            .addProtocols("img", "src", "https")
            .removeAttributes("blockquote", "cite")
            .removeAttributes("q", "cite")
            .addEnforcedAttribute("a", "rel", "nofollow noopener noreferrer")
            .preserveRelativeLinks(false)
        return Jsoup.clean(fragment, "", safelist, Document.OutputSettings().charset(StandardCharsets.UTF_8).prettyPrint(false))
    }

    private fun containsCredentials(value: JsonObject?): Boolean = value?.keys?.any {
        it in setOf("accounts", "accessToken", "authorization", "clientSession", "password", "token")
    } == true

    private fun safeCode(value: String): String {
        val code = value.trim()
        require(codePattern.matches(code) && !code.contains("..")) { "题卡编号须为 1–128 位字母、数字或安全标点（_ . - ( )）" }
        return code
    }

    private fun questionId(value: String?): String {
        require(!value.isNullOrBlank() && value.length <= 256 && value.none(Char::isISOControl)) { "题目编号无效" }
        return value
    }

    private fun boundedUtf8(value: String): ByteArray {
        require(value.length <= MAX_IMPORT_BYTES) { "题卡文件不能超过 8 MiB" }
        return value.toByteArray(StandardCharsets.UTF_8).also {
            require(it.size <= MAX_IMPORT_BYTES) { "题卡文件不能超过 8 MiB" }
        }
    }

    // Bound even ignored JSON metadata before the JSON library builds its tree.
    private fun checkJsonNesting(text: String) {
        var depth = 0
        var quoted = false
        var escaped = false
        text.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> {
                        depth++
                        require(depth <= MAX_JSON_DEPTH) { "JSON 嵌套层级过深" }
                    }
                    '}', ']' -> depth--
                }
            }
        }
    }

    private fun addListWarning(warnings: MutableList<String>, value: String) {
        if (warnings.size < 32) warnings += value
        else if (warnings.size == 32) warnings += "另有异常记录已跳过。"
    }

    private class QuestionBudget {
        private var count = 0
        fun visit(depth: Int) {
            require(depth <= MAX_QUESTION_DEPTH) { "题目子层级不能超过 12 层" }
            require(++count <= MAX_QUESTIONS) { "题目数量不能超过 2000" }
        }
    }

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key] as? JsonObject
    private fun JsonObject.stringAt(key: String): String? {
        val primitive = this[key] as? JsonPrimitive ?: return null
        if (!primitive.isString && primitive.contentOrNull in setOf("true", "false")) return null
        return primitive.contentOrNull
    }
}

fun parseLegacyList(text: String, subject: String = "other"): List<TemplateSummary> =
    TemplateTransfer.parseLegacyList(text, subject)
