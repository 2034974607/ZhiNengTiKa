package app.zhinengtika.nativeapp.data

import org.jsoup.Jsoup

object AnswerLetters {
    private val optionToken = Regex("(?<![A-Z])[A-G]{1,7}(?![A-Z])", RegexOption.IGNORE_CASE)

    fun parse(htmlOrText: String): Set<String> {
        val visibleText = Jsoup.parseBodyFragment(htmlOrText).text()
        return optionToken.findAll(visibleText)
            .flatMap { it.value.uppercase().asSequence().map(Char::toString) }
            .toSet()
    }
}
