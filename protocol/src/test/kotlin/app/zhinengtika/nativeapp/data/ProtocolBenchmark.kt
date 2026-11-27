package app.zhinengtika.nativeapp.data

import java.io.File
import java.lang.management.ManagementFactory
import kotlin.math.ceil
import kotlin.system.measureNanoTime
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private object BenchmarkSink { @Volatile var value: Any? = null }

/** Host JVM data-path benchmark. This is deliberately NOT an Android UI benchmark. */
fun main(args: Array<String>) {
    val card = TemplateCard(
        code = "SYNTHETIC-BENCH-200",
        title = "合成性能样例（非真实题卡）",
        questions = (1..200).map { n ->
            QuestionItem(
                id = "synthetic-q$n", order = n.toString(),
                contentHtml = "<p>合成题目 $n：计算 $n + 1。</p>",
                options = listOf(QuestionOption("A", "$n"), QuestionOption("B", "${n + 1}")),
                answer = "B", explanationHtml = "<p>合成解析：加法计算得到 ${n + 1}。</p>",
            )
        },
    )
    val input = Json.encodeToString(card).toByteArray()
    fun measure(name: String, iterations: Int, action: () -> Any?): String {
        repeat(20) { BenchmarkSink.value = action() }
        val samples = DoubleArray(iterations) {
            measureNanoTime { BenchmarkSink.value = action() } / 1_000_000.0
        }.sorted()
        val median = samples[samples.size / 2]
        val p95 = samples[(ceil(samples.size * 0.95).toInt() - 1).coerceIn(samples.indices)]
        return """{"name":"$name","iterations":$iterations,"medianMs":$median,"p95Ms":$p95,"minMs":${samples.first()},"maxMs":${samples.last()}}"""
    }
    val measurements = listOf(
        measure("import_200_synthetic_questions", 80) { TemplateTransfer.importJson(input) },
        measure("export_html_200_synthetic_questions", 80) { TemplateTransfer.exportHtml(card) },
        measure("aes_round_trip_4k_utf8", 200) { ProtocolCrypto.decryptFromBase64(ProtocolCrypto.encryptToBase64("合成内容".repeat(341))) },
        measure("request_digest_1000", 80) { repeat(1_000) { BenchmarkSink.value = ProtocolCrypto.requestDigest("1700000000000", "synthetic-session") } },
    )
    val output = """{
      "kind":"host-jvm-synthetic-data-path",
      "androidDeviceBenchmark":false,
      "timestampUtc":"${java.time.Instant.now()}",
      "javaVersion":"${System.getProperty("java.version")}",
      "vm":"${ManagementFactory.getRuntimeMXBean().vmName}",
      "os":"${System.getProperty("os.name")}",
      "architecture":"${System.getProperty("os.arch")}",
      "availableProcessors":${Runtime.getRuntime().availableProcessors()},
      "inputBytes":${input.size},
      "questionCount":200,
      "warmupIterationsPerMeasurement":20,
      "measurements":[${measurements.joinToString(",")}]
    }""".trimIndent() + "\n"
    val target = File(args.singleOrNull() ?: "build/reports/synthetic-benchmark.json")
    target.parentFile?.mkdirs()
    target.writeText(output)
    println(output)
    println("Synthetic host benchmark report: ${target.absolutePath}")
}
