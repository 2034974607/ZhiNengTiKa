package app.zhinengtika.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Test

class AnswerLettersTest {
    @Test
    fun extractsOnlyVisibleChoiceTokensFromHtml() {
        assertEquals(setOf("B"), AnswerLetters.parse("<span>B</span>"))
        assertEquals(setOf("C"), AnswerLetters.parse("<div>C</div>"))
        assertEquals(setOf("A", "C"), AnswerLetters.parse("<p>A、C</p>"))
        assertEquals(setOf("A", "B"), AnswerLetters.parse("AB"))
    }
}
