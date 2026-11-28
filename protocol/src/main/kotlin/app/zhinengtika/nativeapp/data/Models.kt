package app.zhinengtika.nativeapp.data

import kotlinx.serialization.Serializable

@Serializable
data class StudentAccount(
    val username: String,
    val accessToken: String,
    val authorization: String,
    val clientSession: String,
    val studentId: String,
    val schoolId: String,
    val displayName: String = "",
)

@Serializable
data class TemplateCard(
    val code: String,
    val title: String,
    val questions: List<QuestionItem>,
    val retrievedAtMillis: Long = 0L,
)

@Serializable
data class QuestionOption(
    val label: String,
    val html: String,
)

@Serializable
data class QuestionItem(
    val id: String,
    val order: String,
    val contentHtml: String,
    val options: List<QuestionOption>,
    val answer: String,
    val explanationHtml: String,
    val previousAnswer: String = "",
    val previousRawScan: String = "",
    val children: List<QuestionItem> = emptyList(),
) {
    fun leaves(): List<QuestionItem> =
        if (children.isEmpty()) listOf(this) else children.flatMap(QuestionItem::leaves)
}

@Serializable
data class AnswerSubmission(
    val questionId: String,
    val answer: String = "",
    val rawScan: String = "",
)

@Serializable
data class TemplateHistoryEntry(
    val code: String,
    val title: String,
    val viewedAtMillis: Long,
)

class ApiException(
    val businessCode: Int?,
    message: String,
) : java.io.IOException(message)

class HttpStatusException(
    val statusCode: Int,
) : java.io.IOException("网络请求失败（$statusCode）")
