package app.zhinengtika.nativeapp.data

import java.io.IOException

class TemplateRepository(
    private val api: StudentApi,
    private val cache: TemplateCache,
    private val history: HistoryStore,
) {
    suspend fun load(code: String, account: StudentAccount): TemplateLoadResult {
        try {
            val card = api.getTemplate(code, account)
            cache.write(card, account)
            history.add(card, account)
            return TemplateLoadResult(card, fromCache = false)
        } catch (error: IOException) {
            if (error is ApiException) throw error
            val cached = cache.read(code, account) ?: throw error
            history.add(cached, account)
            return TemplateLoadResult(cached, fromCache = true)
        }
    }
}

data class TemplateLoadResult(
    val card: TemplateCard,
    val fromCache: Boolean,
)
