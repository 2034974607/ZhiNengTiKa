package app.zhinengtika.nativeapp.data

import org.junit.Assert.*
import org.junit.Test

class TemplateParserBoundariesTest {
    private fun record(id: String, child: String? = null): String =
        """{"ordered":1,"question":{"id":"$id","content":"synthetic","answer":"","answerExplanation":""}${child?.let { ",\"childQuestionList\":[$it]" }.orEmpty()}}"""

    private fun response(records: String): String =
        """{"code":200,"data":{"questions":[{"templateName":"Synthetic","questionsAnswers":[$records]}]}}"""

    @Test fun allQuestionGroupsRemainInServerOrder() {
        val body = """{"code":200,"data":{"questions":[{"templateName":"Synthetic","questionsAnswers":[${record("q1")}]},{"questionsAnswers":[${record("q2")}]}]}}"""
        assertEquals(listOf("q1", "q2"), TemplateParser.parse(body, "test").questions.map { it.id })
    }

    @Test fun duplicateIdsAreRejectedBeforeDraftsCanCollide() {
        val error = assertThrows(ApiException::class.java) {
            TemplateParser.parse(response("${record("q1")},${record("q1")}"), "test")
        }
        assertTrue(error.message!!.contains("重复"))
    }

    @Test fun limitsQuestionTreeDepthAndIgnoredMetadataNesting() {
        var tree = record("q13")
        for (n in 12 downTo 1) tree = record("q$n", tree)
        assertThrows(ApiException::class.java) { TemplateParser.parse(response(tree), "test") }
        val deepMetadata = "[".repeat(65) + "0" + "]".repeat(65)
        assertThrows(ApiException::class.java) {
            TemplateParser.parse("""{"code":200,"ignored":$deepMetadata} """, "test")
        }
    }

    @Test fun boundsTotalQuestionCountAcrossGroups() {
        val records = (1..2001).joinToString(",") { record("q$it") }
        val error = assertThrows(ApiException::class.java) { TemplateParser.parse(response(records), "test") }
        assertTrue(error.message!!.contains("2000"))
    }
}
