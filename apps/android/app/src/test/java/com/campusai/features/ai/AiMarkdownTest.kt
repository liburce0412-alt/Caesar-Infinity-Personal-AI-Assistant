package com.campusai.features.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.LinkAnnotation
import org.junit.Test

class AiMarkdownTest {
    @Test
    fun `web links require http or https with a valid host and no credentials`() {
        for (url in listOf("https://developer.android.com/guide?x=1#top", "http://example.com:8080/a", "https://[::1]/")) {
            assertEquals(url, aiMarkdownWebUrl(url))
        }
        assertEquals("https://example.com/", aiMarkdownWebUrl("HTTPS://example.com/"))
        for (url in listOf("", "javascript:alert(1)", "data:text/html,hello", "intent://example.com/",
            "file:///tmp/example", "content://example.com/", "mailto:a@example.com", "//example.com/",
            "https:", "https:///path", "https://", "https://?q=1", "https://#fragment",
            "https://user:password@example.com/", "https://example.com:65536/", "https://example.com:abc/",
            " https://example.com", "https://example.com\n", "https://exa mple.com", "https://example.com\\@other.test/")) {
            assertNull(url, aiMarkdownWebUrl(url))
        }
    }

    @Test
    fun `inline links carry the actual URL while unsafe code and incomplete targets have no link`() {
        val safe = inlineAiMarkdown("查看 [Android Developers](https://developer.android.com/) 文档", Color.Black, Color.Gray)
        val link = safe.getLinkAnnotations(0, safe.length).single()
        assertEquals("https://developer.android.com/", (link.item as LinkAnnotation.Url).url)
        assertEquals("Android Developers", safe.substring(link.start, link.end))
        for (text in listOf("[危险](javascript:alert)", "[空主机](https:///path)",
            "[尚未完成](https://developer.android.com", "`[代码](https://example.com/)`")) {
            val annotated = inlineAiMarkdown(text, Color.Black, Color.Gray)
            assertTrue(text, annotated.getLinkAnnotations(0, annotated.length).isEmpty())
        }
    }

    @Test
    fun `parser preserves headings and ordered and unordered list items`() {
        val blocks = parseAiMarkdown(
            """
            # 连接结果

            - 模型已加载
            2. 对话已验证
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                AiMarkdownBlock.Heading(level = 1, text = "连接结果"),
                AiMarkdownBlock.ListItem(marker = "•", text = "模型已加载"),
                AiMarkdownBlock.ListItem(marker = "2.", text = "对话已验证"),
            ),
            blocks,
        )
    }

    @Test
    fun `parser keeps fenced code language and content separate`() {
        val blocks = parseAiMarkdown(
            """
            示例：

            ```kotlin
            val answer = "OK"
            println(answer)
            ```
            """.trimIndent(),
        )

        assertEquals(AiMarkdownBlock.Paragraph("示例："), blocks.first())
        assertEquals(
            AiMarkdownBlock.Code(
                language = "kotlin",
                code = "val answer = \"OK\"\nprintln(answer)",
            ),
            blocks.last(),
        )
    }

    @Test
    fun `unfinished streaming fence remains a code block through end of input`() {
        val blocks = parseAiMarkdown(
            """
            正在生成
            ```json
            {"status":"partial"}
            """.trimIndent(),
        )

        assertEquals(2, blocks.size)
        assertEquals(AiMarkdownBlock.Paragraph("正在生成"), blocks[0])
        val code = blocks[1]
        assertTrue(code is AiMarkdownBlock.Code)
        assertEquals("json", (code as AiMarkdownBlock.Code).language)
        assertEquals("{\"status\":\"partial\"}", code.code)
    }
}
