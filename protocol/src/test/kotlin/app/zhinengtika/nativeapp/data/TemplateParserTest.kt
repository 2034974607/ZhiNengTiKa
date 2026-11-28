package app.zhinengtika.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TemplateParserTest {
    @Test
    fun decryptsAnswersAndPreservesNestedQuestionOrdering() {
        val parentAnswer = ProtocolCrypto.encryptToBase64("综合说明")
        val childAnswer = ProtocolCrypto.encryptToBase64("B")
        val explanation = ProtocolCrypto.encryptToBase64("因为条件成立")
        val response = """
            {
              "code":"200",
              "data":{"questions":[{"templateName":"样例题卡","questionsAnswers":[
                {"ordered":1,"question":{"id":"q-parent","content":"<p>父题</p>","answer":"$parentAnswer","answerExplanation":"","optionA":""},
                 "childQuestionList":[
                   {"ordered":3,"question":{"id":"q-child","content":"<p>子题</p>","answer":"$childAnswer","answerExplanation":"$explanation","optionA":"选项甲","optionB":"选项乙"},"answer":{"answer":"A","rawScan":"https://cdn.test/path/previous.jpg"}}
                 ]}
              ]}]}
            }
        """.trimIndent()

        val card = TemplateParser.parse(response, "template-test")
        val parent = card.questions.single()
        val child = parent.children.single()

        assertEquals("样例题卡", card.title)
        assertEquals("1", parent.order)
        assertEquals("综合说明", parent.answer)
        assertEquals("q-child", child.id)
        assertEquals("3", child.order)
        assertEquals("B", child.answer)
        assertEquals("因为条件成立", child.explanationHtml)
        assertEquals("A", child.previousAnswer)
        assertEquals("https://cdn.test/path/previous.jpg", child.previousRawScan)
        assertEquals(listOf("A", "B"), child.options.map { it.label })
        assertEquals(listOf(child), parent.leaves())
    }

    @Test
    fun rejectsMalformedBusinessResponseAndBrokenCiphertext() {
        val businessFailure = """{"code":403,"msg":"denied"}"""
        assertEquals(403, assertThrows(ApiException::class.java) {
            TemplateParser.parse(businessFailure, "template-test")
        }.businessCode)

        val brokenCipher = """{"code":200,"data":{"questions":[{"questionsAnswers":[{"question":{"id":"q1","answer":"not-base64!"}}]}]}}"""
        val error = assertThrows(ApiException::class.java) {
            TemplateParser.parse(brokenCipher, "template-test")
        }
        assertTrue(error.message!!.contains("解密失败"))
    }
}
