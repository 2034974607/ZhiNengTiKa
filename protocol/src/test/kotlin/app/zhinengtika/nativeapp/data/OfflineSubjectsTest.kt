package app.zhinengtika.nativeapp.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineSubjectsTest {
    @Test
    fun bundledLegacyMapCovers519CardsWithExpectedCounts() {
        val map = LegacyOfflineSubjectIndex.byCode
        assertEquals(519, map.size)
        assertEquals(
            mapOf("语文" to 67, "数学" to 101, "英语" to 87, "物理" to 111, "化学" to 84, "生物" to 69),
            map.values.groupingBy { it }.eachCount(),
        )
        assertEquals("语文", map["mxzy2022053000010121(01)"])
        assertEquals("数学", map["mxzy202208170041022135"])
        assertEquals("英语", map["mxzy202205250041045401"])
        assertEquals("物理", map["mxzy202204240017055308"])
        assertEquals("化学", map["mxzy202205170039062101"])
        assertEquals("生物", map["mxzy202207270001072113"])
        assertEquals("物理", map["mxzy202210180002055430"]) // Recovered card absent from the old lists.
    }

    @Test
    fun normalizesLegacyAliasesAndUnknownValues() {
        val cases = mapOf(
            "chinese" to "语文", "中文" to "语文", "math" to "数学", "Mathematics" to "数学",
            "english" to "英语", "physics" to "物理", "chemistry" to "化学",
            "biography" to "生物", "biology" to "生物", "other" to "未分类",
            " 未分类 " to "未分类", "unknown" to "未分类", "not-a-subject" to "未分类",
        )
        cases.forEach { (raw, expected) -> assertEquals(raw, expected, OfflineSubjects.normalize(raw)) }
        assertEquals("未分类", OfflineSubjects.normalize(null))
        assertEquals("未分类", OfflineSubjects.normalize("  "))
    }

    @Test
    fun exactCodeRepairsLegacyOtherAndOutranksTitleHeuristics() {
        assertEquals(
            "物理",
            OfflineSubjects.resolve(
                "mxzy202204240017055308", "英语阅读训练", explicitSubject = "other", knownSubject = "other",
            ),
        )
        assertEquals("物理", OfflineSubjects.resolve("mxzy202204240017055308", "英语阅读训练"))
        assertEquals("化学", OfflineSubjects.resolve("mxzy202205170039062101", "未分类标题"))
        assertEquals("生物", OfflineSubjects.resolve(null, "旧数据", explicitSubject = "biology"))
        assertEquals("数学", OfflineSubjects.resolve(null, "物理题名", knownSubject = "math"))
    }

    @Test
    fun recognizesSubjectDirectoriesAndLegacyListNames() {
        assertEquals("物理", OfflineSubjects.resolve(null, "没有学科的题名", "科目目录/物理.txt"))
        assertEquals("生物", OfflineSubjects.resolve(null, "没有学科的题名", "templateList_biography"))
        assertEquals("化学", OfflineSubjects.resolve(null, "没有学科的题名", "题卡JSON/化学/code.json"))
        assertEquals("数学", OfflineSubjects.resolve(null, "无科目", "\\\\export\\\\数学.txt"))
    }

    @Test
    fun infersOnlyAnUnambiguousKnownSubjectFromTitle() {
        assertEquals("语文", OfflineSubjects.resolve(null, "选择性必修上 语文 第1课 中国人民站起来了"))
        assertEquals("数学", OfflineSubjects.resolve(null, "选择性必修二 数学 第2章 建立空间直角坐标系"))
        assertEquals("英语", OfflineSubjects.resolve(null, "必修三 English Unit 9"))
        assertEquals("物理", OfflineSubjects.resolve(null, "必修三 物理 第十章 电势能和电势"))
        assertEquals("化学", OfflineSubjects.resolve(null, "选择性必修二 化学 第一章 共价键"))
        assertEquals("生物", OfflineSubjects.resolve(null, "选择性必修三 Biology 重组DNA技术"))
        assertEquals("未分类", OfflineSubjects.resolve(null, "综合练习"))
        assertEquals("未分类", OfflineSubjects.resolve(null, "物理与化学综合练习"))
    }

    @Test
    fun orderNormalizesDeDuplicatesAndUsesCanonicalSequence() {
        assertEquals(
            listOf("语文", "数学", "英语", "物理", "化学", "生物", "未分类"),
            OfflineSubjects.order(
                listOf("biology", "other", "physics", "chinese", "math", "english", "chemistry", "biography", "unknown"),
            ),
        )
        assertEquals(listOf("数学", "未分类"), OfflineSubjects.order(listOf("math", "math", "not-known")))
        assertTrue(OfflineSubjects.order(emptyList()).isEmpty())
    }
}
