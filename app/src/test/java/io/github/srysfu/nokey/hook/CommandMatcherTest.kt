package io.github.srysfu.nokey.hook

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * CommandMatcher 口令匹配逻辑单元测试。
 */
class CommandMatcherTest {

    @Test
    fun `解锁口令`() {
        assertEquals(0x0b, CommandMatcher.matchCommandCode("解锁"))
        assertEquals(0x0b, CommandMatcher.matchCommandCode("请帮我解锁车辆"))
        assertEquals(0x0b, CommandMatcher.matchCommandCode("开锁"))
    }

    @Test
    fun `锁车口令`() {
        assertEquals(0x0c, CommandMatcher.matchCommandCode("锁车"))
        assertEquals(0x0c, CommandMatcher.matchCommandCode("上锁"))
    }

    @Test
    fun `引擎口令`() {
        assertEquals(0x29, CommandMatcher.matchCommandCode("启动引擎"))
        assertEquals(0x29, CommandMatcher.matchCommandCode("点火"))
        assertEquals(0x2a, CommandMatcher.matchCommandCode("熄火"))
        assertEquals(0x2a, CommandMatcher.matchCommandCode("关闭引擎"))
    }

    @Test
    fun `车窗口令`() {
        assertEquals(0x33, CommandMatcher.matchCommandCode("打开车窗"))
        assertEquals(0x33, CommandMatcher.matchCommandCode("开窗"))
        assertEquals(0x34, CommandMatcher.matchCommandCode("关闭车窗"))
        assertEquals(0x34, CommandMatcher.matchCommandCode("关窗"))
        // 语义修正：玻璃降下 = 打开车窗(0x33)；玻璃升起 = 关闭车窗(0x34)
        assertEquals(0x33, CommandMatcher.matchCommandCode("车窗降下"))
        assertEquals(0x33, CommandMatcher.matchCommandCode("帮我降下车窗"))
        assertEquals(0x34, CommandMatcher.matchCommandCode("车窗升起"))
        assertEquals(0x34, CommandMatcher.matchCommandCode("把车窗升起来"))
    }

    @Test
    fun `非指令文本返回-1`() {
        assertEquals(-1, CommandMatcher.matchCommandCode("今天天气怎么样"))
        assertEquals(-1, CommandMatcher.matchCommandCode(""))
        assertEquals(-1, CommandMatcher.matchCommandCode(null))
        assertEquals(-1, CommandMatcher.matchCommandCode("   "))
    }

    @Test
    fun `超长文本防御`() {
        val longText = "这是一段很长的系统回复，" + "测试内容".repeat(30)
        assertEquals(-1, CommandMatcher.matchCommandCode(longText))
    }

    @Test
    fun `系统复述也命中`() {
        assertEquals(0x0b, CommandMatcher.matchCommandCode("好的，正在为您解锁车辆"))
    }

    @Test
    fun `自定义词表覆盖后按新词匹配`() {
        // 备份并覆盖为自定义词表
        val backup = CommandMatcher.currentCommands
        try {
            CommandMatcher.currentCommands = listOf(
                CommandMatcher.Command(0x0b, "解锁", listOf("芝麻开门", "开门大吉")),
                CommandMatcher.Command(0x34, "车窗关", listOf("把窗关严"))
            )
            // 新词命中
            assertEquals(0x0b, CommandMatcher.matchCommandCode("芝麻开门"))
            assertEquals(0x0b, CommandMatcher.matchCommandCode("请芝麻开门"))
            assertEquals(0x34, CommandMatcher.matchCommandCode("帮我把窗关严"))
            // 默认词不再命中（被覆盖后不生效）
            assertEquals(-1, CommandMatcher.matchCommandCode("解锁"))
            assertEquals(-1, CommandMatcher.matchCommandCode("开锁"))
            // 命令名取自生效词表
            assertEquals("解锁", CommandMatcher.nameOf(0x0b))
        } finally {
            CommandMatcher.currentCommands = backup
        }
    }
}