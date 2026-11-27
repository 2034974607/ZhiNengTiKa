package app.zhinengtika.nativeapp.data

/**
 * A fully synthetic, hand-written offline sample. It contains no real student record, no real
 * template, and no copied question bank. It is clearly labelled as a demo everywhere it appears.
 */
object DemoContent {
    const val FILENAME = "演示题卡-合成示例.json"
    const val SUBJECT = "演示"

    val json: String = """
{
  "code": "DEMO-SYNTHETIC-001",
  "title": "合成演示题卡（非真实题目）",
  "questions": [
    {
      "id": "demo-q1",
      "order": "1",
      "contentHtml": "这是一道<strong>合成演示题</strong>，仅用于展示离线导入效果，并非真实考试题。下面的说法正确吗？",
      "options": [
        {"label": "A", "html": "正确"},
        {"label": "B", "html": "错误"}
      ],
      "answer": "A",
      "explanationHtml": "这是演示解析：本题答案为 A，纯属示例内容。",
      "children": []
    },
    {
      "id": "demo-q2",
      "order": "2",
      "contentHtml": "这是第二道合成演示题。请选出所有属于演示内容的选项。",
      "options": [
        {"label": "A", "html": "演示内容"},
        {"label": "B", "html": "也是演示内容"},
        {"label": "C", "html": "真实考题（不是演示）"}
      ],
      "answer": "AB",
      "explanationHtml": "演示解析：A 和 B 均为示例选项。",
      "children": [
        {
          "id": "demo-q2-1",
          "order": "(1)",
          "contentHtml": "这是合成演示题的子问题，用于展示父子层级显示。",
          "options": [
            {"label": "A", "html": "子选项一"},
            {"label": "B", "html": "子选项二"}
          ],
          "answer": "B",
          "explanationHtml": "演示解析：子题答案为 B。",
          "children": []
        }
      ]
    }
  ]
}
""".trimIndent()
}
