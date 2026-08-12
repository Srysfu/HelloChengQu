package io.github.srysfu.nokey.hook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * NokeyConfig 轻量 JSON 序列化 / 反序列化单元测试。
 */
class NokeyConfigTest {

    @Test
    fun `默认词表往返一致`() {
        val cmds = CommandMatcher.DEFAULT_COMMANDS
        val json = NokeyConfig.toJson(cmds)
        val back = NokeyConfig.fromJson(json)
        assertNotNull(back)
        assertEquals(cmds.size, back!!.size)
        assertEquals(cmds, back)
    }

    @Test
    fun `自定义词表往返一致`() {
        val custom = listOf(
            CommandMatcher.Command(0x0b, "解锁", listOf("开门", "开锁", "开一下门")),
            CommandMatcher.Command(0x33, "车窗开", listOf("摇下窗户", "打开窗"))
        )
        val back = NokeyConfig.fromJson(NokeyConfig.toJson(custom))
        assertNotNull(back)
        assertEquals(custom, back)
    }

    @Test
    fun `特殊字符转义无损`() {
        val cmds = listOf(
            CommandMatcher.Command(0x0c, "锁车", listOf("带\"引号\"", "后\\斜杠", "换\n行", "制表\t符"))
        )
        val json = NokeyConfig.toJson(cmds)
        val back = NokeyConfig.fromJson(json)
        assertNotNull(back)
        assertEquals(cmds, back)
    }

    @Test
    fun `非法JSON返回null`() {
        assertNull(NokeyConfig.fromJson("not json"))
        assertNull(NokeyConfig.fromJson("{}"))
        assertNull(NokeyConfig.fromJson("{\"commands\":}"))
        assertNull(NokeyConfig.fromJson(""))
    }

    @Test
    fun `空词表返回null`() {
        assertNull(NokeyConfig.fromJson("{\"commands\":[]}"))
    }
}