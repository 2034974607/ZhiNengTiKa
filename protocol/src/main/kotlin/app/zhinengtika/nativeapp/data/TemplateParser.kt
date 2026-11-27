package app.zhinengtika.nativeapp.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object TemplateParser {
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(response: String, requestedCode: String): TemplateCard {
        val root = parseObject(response)
        requireSuccess(root)
        val data = root.objectAt("data") ?: throw ApiException(null, "题卡响应缺少 data")
        val groups = data.arrayAt("questions") ?: throw ApiException(null, "题卡响应缺少 questions")
        if (groups.isEmpty()) throw ApiException(null, "题卡不包含题目")
        val firstGroup = groups.firstOrNull()?.jsonObjectOrNull()
            ?: throw ApiException(null, "题组格式无效")
        val budget = QuestionBudget()
        val questions = groups.flatMap { groupValue ->
            val group = groupValue.jsonObjectOrNull() ?: throw ApiException(null, "题组格式无效")
            val records = group.arrayAt("questionsAnswers")
                ?: throw ApiException(null, "题卡响应缺少 questionsAnswers")
            records.mapIndexed { index, element ->
                val record = element.jsonObjectOrNull() ?: throw ApiException(null, "题目格式无效")
                parseQuestion(record, index + 1, 1, budget)
            }
        }
        if (questions.isEmpty()) throw ApiException(null, "题卡不包含题目")
        val title = firstGroup.stringAt("templateName")
            ?: firstGroup.stringAt("name")
            ?: data.stringAt("templateName")
            ?: requestedCode
        return TemplateCard(requestedCode, title, questions, System.currentTimeMillis())
    }

    private fun parseQuestion(
        record: JsonObject,
        fallbackOrder: Int,
        depth: Int,
        budget: QuestionBudget,
    ): QuestionItem {
        val source = record.objectAt("question") ?: throw ApiException(null, "题目缺少 question")
        val id = source.stringAt("id")?.takeIf { it.isNotBlank() && it.length <= 256 && it.none(Char::isISOControl) }
            ?: throw ApiException(null, "题目缺少有效编号")
        budget.visit(depth, id)
        val options = ('A'..'G').mapNotNull { label ->
            source.stringAt("option$label")
                ?.takeIf(String::isNotBlank)
                ?.let { QuestionOption(label.toString(), it) }
        }
        val answer = decryptField(source.stringAt("answer"))
        val explanation = decryptField(source.stringAt("answerExplanation"))
        val previous = record.objectAt("answer")
        val previousAnswer = previous?.stringAt("answer").orEmpty()
        val previousRawScan = previous?.stringAt("rawScan").orEmpty()
        val children = record.arrayAt("childQuestionList")?.mapIndexed { index, child ->
            val childRecord = child.jsonObjectOrNull()
                ?: throw ApiException(null, "子题格式无效")
            parseQuestion(childRecord, index + 1, depth + 1, budget)
        }.orEmpty()
        val order = record.stringAt("ordered") ?: fallbackOrder.toString()
        return QuestionItem(
            id = id,
            order = order,
            contentHtml = source.stringAt("content").orEmpty(),
            options = options,
            answer = answer,
            explanationHtml = explanation,
            previousAnswer = previousAnswer,
            previousRawScan = previousRawScan,
            children = children,
        )
    }

    private fun decryptField(value: String?): String {
        if (value.isNullOrEmpty()) return ""
        return try {
            ProtocolCrypto.decryptFromBase64(value)
        } catch (_: Exception) {
            throw ApiException(null, "题卡答案解密失败，请检查协议版本")
        }
    }

    internal fun parseObject(value: String): JsonObject = try {
        require(value.length <= 8 * 1024 * 1024) { "响应过大" }
        var depth = 0
        var quoted = false
        var escaped = false
        value.forEach { char ->
            if (quoted) {
                when {
                    escaped -> escaped = false
                    char == '\\' -> escaped = true
                    char == '"' -> quoted = false
                }
            } else {
                when (char) {
                    '"' -> quoted = true
                    '{', '[' -> { depth++; require(depth <= 64) { "响应嵌套过深" } }
                    '}', ']' -> depth--
                }
            }
        }
        json.parseToJsonElement(value).jsonObject
    } catch (_: Exception) {
        throw ApiException(null, "服务器响应不是有效或受支持的 JSON")
    }

    private class QuestionBudget {
        private val ids = mutableSetOf<String>()
        fun visit(depth: Int, id: String) {
            if (depth > 12) throw ApiException(null, "题目子层级超过 12 层")
            if (!ids.add(id)) throw ApiException(null, "题卡包含重复题目编号")
            if (ids.size > 2_000) throw ApiException(null, "题卡超过 2000 题")
        }
    }

    internal fun requireSuccess(root: JsonObject) {
        val code = root.stringAt("code")?.toIntOrNull()
        if (code != 200) {
            val message = root.stringAt("msg")?.take(160)?.ifBlank { null } ?: "服务请求失败"
            throw ApiException(code, message)
        }
    }

    private fun JsonObject.objectAt(key: String): JsonObject? = this[key]?.jsonObjectOrNull()
    private fun JsonObject.arrayAt(key: String): JsonArray? = try {
        this[key]?.jsonArray
    } catch (_: Exception) {
        null
    }
    private fun JsonObject.stringAt(key: String): String? = try {
        this[key]?.jsonPrimitive?.contentOrNull
    } catch (_: Exception) {
        null
    }
    private fun JsonElement.jsonObjectOrNull(): JsonObject? = try {
        jsonObject
    } catch (_: Exception) {
        null
    }
}
