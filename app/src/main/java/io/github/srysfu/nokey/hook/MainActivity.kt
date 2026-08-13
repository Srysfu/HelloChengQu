package io.github.srysfu.nokey.hook

import android.media.MediaPlayer
import android.media.RingtoneManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import io.github.srysfu.nokey.hook.ui.theme.MyApplicationTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private data class AdaptiveTextColors(
    val primary: Color,
    val secondary: Color,
    val muted: Color,
    val icon: Color,
    val isLightBackground: Boolean
)

private val LocalAdaptiveTextColors = compositionLocalOf {
    AdaptiveTextColors(
        primary = Color.White,
        secondary = Color.White.copy(alpha = 0.82f),
        muted = Color.White.copy(alpha = 0.68f),
        icon = Color.White,
        isLightBackground = false
    )
}

private fun bitmapLuminance(bitmap: android.graphics.Bitmap): Float {
    val sample = android.graphics.Bitmap.createScaledBitmap(bitmap, 1, 1, true)
    val pixel = sample.getPixel(0, 0)
    if (sample !== bitmap) sample.recycle()
    val r = android.graphics.Color.red(pixel) / 255f
    val g = android.graphics.Color.green(pixel) / 255f
    val b = android.graphics.Color.blue(pixel) / 255f
    return 0.2126f * r + 0.7152f * g + 0.0722f * b
}

private fun adaptiveColors(bitmap: android.graphics.Bitmap?): AdaptiveTextColors {
    val isLight = bitmap != null && bitmapLuminance(bitmap) >= 0.52f
    return if (isLight) {
        AdaptiveTextColors(
            primary = Color(0xFF111111),
            secondary = Color(0xFF222222).copy(alpha = 0.86f),
            muted = Color(0xFF333333).copy(alpha = 0.76f),
            icon = Color(0xFF111111),
            isLightBackground = true
        )
    } else {
        AdaptiveTextColors(
            primary = Color.White,
            secondary = Color.White.copy(alpha = 0.88f),
            muted = Color.White.copy(alpha = 0.74f),
            icon = Color.White,
            isLightBackground = false
        )
    }
}

/**
 * 模块配置界面：允许用户自定义每条车辆命令的唤醒词、成功提示音与屏幕文案。
 *
 * 布局（参照源车控 App 那排控车图标形态）：
 *  - 顶部：横向一排 6 个命令图标（图标在上、文字在下），点击切换，下方编辑该命令的唤醒词；
 *  - 底部：「反馈设置」一个方块，内含「提示音」「成功文案」两个页签切换。
 *
 * 编辑结果保存到共享配置文件 /data/local/tmp/nokey_cfg.json：
 *  - 当前进程（UI）先尝试直接写（调试/root shell 直跑时可行）；
 *  - 文件系统权限不足时回退到 su 提权写入，保证真机 root 场景可用。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                ConfigScreen()
            }
        }
    }
}

/** UI 中一条可编辑的命令：显示名（只读）、命令码（只读）、关键词文本（可编辑） */
private data class EditableCommand(
    val code: Int,
    val name: String,
    var keywordsText: String
)

/** 底部反馈方块的页签类型 */
private enum class FeedbackTab { TONE, TEXT }

/**
 * 单个命令图标规格：drawable 资源 + 可选着色。
 * tint == Color.Unspecified 表示保持原图颜色（彩色图标）；
 * tint == null 表示跟随主题前景色（线性图标）；
 * tint 为具体颜色时表示强制使用该颜色（用于区分车窗开/关）。
 */
private data class CommandIconSpec(val resId: Int, val tint: Color? = null)

/**
 * 命令图标映射：显示名 -> 图标规格。
 * 图标从乘趣资源库提取（AndResGuard 混淆路径）：
 * - 解锁 -> res/VR.png   (icon_keyvalue_unlock 彩色解锁挂锁)
 * - 锁车 -> res/6K.png   (icon_keyvalue_lock 彩色锁定挂锁)
 * - 引擎启动 -> res/lS1.webp (icon_small_engine_start 线性引擎+启动键，随主题色)
 * - 引擎关闭 -> res/j2.png (icon_keyvalue_engine 彩色发动机)
 * - 车窗开 -> res/Wt.webp (icon_small_window 车窗，蓝色)
 * - 车窗关 -> res/Wt.webp (icon_small_window 车窗，红色)
 */
private val COMMAND_ICONS: Map<String, CommandIconSpec> = mapOf(
    "解锁" to CommandIconSpec(R.drawable.ic_unlock, Color.Unspecified),
    "锁车" to CommandIconSpec(R.drawable.ic_lock, Color.Unspecified),
    "引擎启动" to CommandIconSpec(R.drawable.ic_engine_start, null),
    "引擎关闭" to CommandIconSpec(R.drawable.ic_engine_off, Color.Unspecified),
    "车窗开" to CommandIconSpec(R.drawable.ic_window, Color(0xFF1976D2)),
    "车窗关" to CommandIconSpec(R.drawable.ic_window, Color(0xFFC62828))
)

@Composable
fun ConfigScreen() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 初始词表
    // 初始词表：优先读共享配置，否则用默认词表
    val commands = remember {
        val base = NokeyConfig.loadCustom(force = true) ?: CommandMatcher.DEFAULT_COMMANDS
        mutableStateListOf(*base.map { EditableCommand(it.code, it.name, it.keywords.joinToString("，")) }.toTypedArray())
    }

    // 新功能状态：提示音 URI + 成功文案列表（多行编辑）
    var toneUri by remember { mutableStateOf(NokeyConfig.loadToneUri(force = true) ?: "") }
    var successTexts by remember {
        mutableStateOf(NokeyConfig.loadSuccessTexts(force = true).joinToString("\n"))
    }
    var selectedToneName by remember {
        val cur = NokeyConfig.loadToneUri(force = true)
        mutableStateOf(if (cur.isNullOrBlank()) null else SUGGESTED_TONES.firstOrNull { it.second == cur }?.first)
    }

    // 「隐藏后台卡片」开关：档位 1 隐藏乘趣最近任务卡片，防止用户在最近任务里手动划掉乘趣
    var hideRecents by remember { mutableStateOf(NokeyConfig.loadHideRecents(force = true)) }

    // 「进程保活」开关：开启后用 startForegroundService 启动乘趣 KeepAliveService 保持进程存活
    var keepAlive by remember { mutableStateOf(NokeyConfig.loadKeepAlive(force = true)) }

    // 「全静默执行」开关（档位 C）：开启后小爱静默执行指令，屏幕无任何文字、无提示音（连「已成功」气泡都不渲染）
    var silentMode by remember { mutableStateOf(NokeyConfig.loadSilentMode(force = true)) }

    // 顶部命令图标选中态：默认选中第 0 项
    var selectedCommandIndex by remember { mutableStateOf(0) }
    // 底部反馈方块页签：默认提示音
    var bottomTab by remember { mutableStateOf(FeedbackTab.TONE) }
    // 底部「反馈设置」是否展开：默认折叠，点击头部展开/收起
    var feedbackExpanded by remember { mutableStateOf(false) }

    var savingWords by remember { mutableStateOf(false) }
    var savingTone by remember { mutableStateOf(false) }
    var savingTexts by remember { mutableStateOf(false) }
    var savingHideRecents by remember { mutableStateOf(false) }
    var savingKeepAlive by remember { mutableStateOf(false) }
    var savingSilentMode by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf("") }
    var toneStatus by remember { mutableStateOf("") }
    var textStatus by remember { mutableStateOf("") }

    // ============ su 白名单（sulist）自动配置状态 ============
    // sulistBusy：正在检查/写入时置位，防止重复触发
    var sulistBusy by remember { mutableStateOf(false) }
    // sulistMsg：状态卡片上展示的提示文案
    var sulistMsg by remember { mutableStateOf<String?>(null) }
    // sulistOk：白名单是否已齐备（决定显示「已配置」还是「待处理/需重启」）
    var sulistOk by remember { mutableStateOf(false) }
    // sulistTrigger：检测触发器（首次=0 自动跑一次；点按钮 +1 手动重查/补写）
    var sulistTrigger by remember { mutableStateOf(0) }

    /**
     * sulist 检测补写协程：首次打开界面（trigger=0）自动执行一次，
     * 之后点「立即检测/补写」按钮（trigger+1）可手动重跑。
     * 缺失则补写小爱/乘趣（主进程 + 子进程通配共 4 条）；
     * 补写成功后提示用户重启设备使 magiskd 重新加载白名单生效。
     */
    LaunchedEffect(sulistTrigger) {
        if (sulistBusy) return@LaunchedEffect
        sulistBusy = true
        sulistMsg = "正在检测 su 白名单…"
        // hasRoot 单独捕获一次，避免重复执行 su 命令
        val hasRoot = withContext(Dispatchers.IO) { SulistHelper.isRootAvailable() }
        val (ok, missing, wroteSomething) = withContext(Dispatchers.IO) {
            if (!hasRoot) {
                // 无 root：无法检测/写入，仅提示
                Triple(false, emptyList(), false)
            } else {
                val missingBefore = SulistHelper.missingEntries()
                if (missingBefore.isEmpty()) {
                    // 已齐备，无需写入
                    Triple(true, emptyList(), false)
                } else {
                    val added = SulistHelper.ensureEntries()
                    val stillMissing = SulistHelper.missingEntries()
                    Triple(stillMissing.isEmpty(), stillMissing, added > 0)
                }
            }
        }
        sulistBusy = false
        sulistOk = ok
        sulistMsg = when {
            // 无 root：仅提示能力受限
            !hasRoot -> "未检测到 root 权限，无法自动配置 su 白名单。"
            ok && wroteSomething -> "已自动补写 su 白名单（小爱+乘趣，含子进程）。请【重启设备】后生效。"
            ok -> "su 白名单已齐备（小爱+乘趣，含子进程）。"
            else -> "su 白名单部分缺失：${missing.joinToString("、")}。请检查 root 权限或手动在 Magisk 中勾选。"
        }
    }

    // 原生下载背景图（手动处理 HTTPS→HTTP 跨协议 302 重定向）
    var bgBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(Unit) {
        bgBitmap = withContext(Dispatchers.IO) {
            try {
                var url = "https://api.suyanw.cn/api/ksxjj.php"
                var redirects = 0
                while (redirects < 5) {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 10000
                    conn.readTimeout = 10000
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    conn.connect()
                    val code = conn.responseCode
                    if (code in 301..303 || code == 307 || code == 308) {
                        url = conn.getHeaderField("Location") ?: break
                        conn.disconnect()
                        redirects++
                    } else {
                        val input = conn.inputStream
                        val bmp = android.graphics.BitmapFactory.decodeStream(input)
                        conn.disconnect()
                        return@withContext bmp
                    }
                }
                null
            } catch (t: Throwable) {
                null
            }
        }
    }

    // 玻璃取样：layerBackdrop 能取样到背景图片的真实像素
    val glassBackdrop = rememberLayerBackdrop()

    Box(modifier = Modifier
        .fillMaxSize()
        .layerBackdrop(glassBackdrop)
    ) {
        // ============ 渐变背景（最底层，图片加载失败时兜底） ============
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1a237e), // 深蓝
                            Color(0xFF4a148c)  // 深紫
                        )
                    )
                )
        )

        // ============ 网络背景图（手动 HTTP 下载，成功时盖住渐变） ============
        val bmp = bgBitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        // ============ 前景内容（玻璃卡片叠加在图片上） ============
        val adaptiveTextColors = remember(bmp) { adaptiveColors(bmp) }
        val adaptive = adaptiveTextColors
        CompositionLocalProvider(LocalAdaptiveTextColors provides adaptiveTextColors) {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = Color.Transparent
            ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
            Spacer(Modifier.height(4.dp))

            // ============ 标题下方：行为开关条（隐藏后台卡片 / 保活 / 全静默） ============
            BehaviorSwitchBar(
                backdrop = glassBackdrop,
                hideRecents = hideRecents,
                savingHideRecents = savingHideRecents,
                onHideRecents = { on ->
                    hideRecents = on
                    savingHideRecents = true
                    scope.launch {
                        val ok = writeConfig(hideRecents = on)
                        savingHideRecents = false
                        if (ok) {
                            Toast.makeText(context, if (on) "已开启：乘趣不出现在最近任务" else "已关闭：乘趣正常显示在最近任务", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "设置保存失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                            // 保存失败回滚开关态，与落盘配置保持一致
                            hideRecents = NokeyConfig.loadHideRecents(force = true)
                        }
                    }
                },
                keepAlive = keepAlive,
                savingKeepAlive = savingKeepAlive,
                onKeepAlive = { on ->
                    keepAlive = on
                    savingKeepAlive = true
                    scope.launch {
                        val ok = writeConfig(keepAlive = on)
                        savingKeepAlive = false
                        if (ok) {
                            Toast.makeText(context, if (on) "已开启：进程保活（前台服务已启用）" else "已关闭：进程保活", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "设置保存失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                            // 保存失败回滚开关态，与落盘配置保持一致
                            keepAlive = NokeyConfig.loadKeepAlive(force = true)
                        }
                    }
                },
                silentMode = silentMode,
                savingSilentMode = savingSilentMode,
                onSilentMode = { on ->
                    silentMode = on
                    savingSilentMode = true
                    scope.launch {
                        val ok = writeConfig(silentMode = on)
                        savingSilentMode = false
                        if (ok) {
                            Toast.makeText(context, if (on) "已开启：全静默（无文字、无提示音）" else "已关闭：全静默", Toast.LENGTH_SHORT).show()
                        } else {
                            Toast.makeText(context, "设置保存失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                            // 保存失败回滚开关态，与落盘配置保持一致
                            silentMode = NokeyConfig.loadSilentMode(force = true)
                        }
                    }
                }
            )

            Text(
                text = "点击命令图标编辑该命令的唤醒词；底部「反馈设置」可自定义提示音与「已成功」文案。各部分独立保存，小爱下次响应时自动生效（无需重启）。",
                style = MaterialTheme.typography.bodySmall,
                color = adaptive.secondary
            )

            // ============ 顶部一排 6 个命令图标 ============
            CommandIconRow(
                commands = commands,
                selectedIndex = selectedCommandIndex,
                onSelect = { selectedCommandIndex = it }
            )

            // ============ 选中命令的编辑区 ============
            val selected = commands[selectedCommandIndex]
            CommandCard(
                cmd = selected,
                onKeywordsChange = { newText ->
                    // 同步更新列表元素，保证「保存唤醒词/重置默认」读取到最新值
                    commands[selectedCommandIndex] = commands[selectedCommandIndex].copy(keywordsText = newText)
                }
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = {
                        savingWords = true
                        scope.launch {
                            val ok = writeConfig(commands = commands.toList())
                            savingWords = false
                            status = if (ok) "✓ 唤醒词已保存" else "✗ 唤醒词保存失败（无法写入）"
                            Toast.makeText(
                                context,
                                if (ok) "唤醒词已保存" else "保存失败，请检查 root 权限",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    },
                    enabled = !savingWords,
                    modifier = Modifier.weight(1f)
                ) {
                    Text(if (savingWords) "保存中…" else "保存唤醒词")
                }

                OutlinedButton(
                    onClick = {
                        val defaults = CommandMatcher.DEFAULT_COMMANDS
                        commands.clear()
                        commands.addAll(defaults.map { EditableCommand(it.code, it.name, it.keywords.joinToString("，")) })
                        scope.launch {
                            val ok = writeConfig(commands = commands.toList())
                            status = if (ok) "✓ 已恢复默认并保存" else "✗ 保存失败"
                        }
                    },
                    modifier = Modifier.weight(1f)
                ) {
                    Text("重置默认")
                }
            }

            if (status.isNotEmpty()) {
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // ============ 底部「反馈设置」方块（提示音 + 成功文案双页签，可折叠） ============
            BottomFeatureBlock(
                backdrop = glassBackdrop,
                expanded = feedbackExpanded,
                onToggle = { feedbackExpanded = !feedbackExpanded },
                tab = bottomTab,
                onTabChange = { bottomTab = it },
                selectedToneName = selectedToneName,
                toneUri = toneUri,
                onSelectSuggested = { name, uri ->
                    selectedToneName = name
                    toneUri = uri
                },
                onCustomUriChange = { uri ->
                    toneUri = uri
                    selectedToneName = SUGGESTED_TONES.firstOrNull { it.second == uri }?.first
                },
                onPreviewTone = { ctx ->
                    val uriText = if (toneUri.isBlank()) null else toneUri
                    previewTone(ctx, uriText)
                },
                onSaveTone = {
                    savingTone = true
                    scope.launch {
                        val src = if (toneUri.isBlank()) null else toneUri
                        val ok = writeConfig(toneUri = src)
                        savingTone = false
                        toneStatus = if (ok) "✓ 提示音已保存" else "✗ 提示音保存失败"
                        Toast.makeText(context, if (ok) "提示音已保存" else "保存失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                    }
                },
                savingTone = savingTone,
                toneStatus = toneStatus,
                text = successTexts,
                onTextChange = { successTexts = it },
                onPreviewText = {
                    val lines = successTexts.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                    val sample = if (lines.isEmpty()) "已成功" else lines[kotlin.random.Random.nextInt(lines.size)]
                    Toast.makeText(context, "随机示例：$sample", Toast.LENGTH_SHORT).show()
                },
                onSaveText = {
                    savingTexts = true
                    scope.launch {
                        val texts = successTexts.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
                        val ok = writeConfig(successTexts = texts)
                        savingTexts = false
                        textStatus = if (ok) "✓ 文案已保存（${texts.size} 条，将随机显示）" else "✗ 文案保存失败"
                        Toast.makeText(context, if (ok) "文案已保存" else "保存失败，请检查 root 权限", Toast.LENGTH_SHORT).show()
                    }
                },
                savingTexts = savingTexts,
                textStatus = textStatus,
                context = context
            )

            OutlinedButton(
                onClick = {
                    scope.launch {
                        val full = NokeyConfig.loadFull(force = true)
                        status = when {
                            full == null -> "当前使用全部默认配置（未自定义）"
                            else -> "当前：${full.commands.size} 条命令 · ${full.toneUri?.takeIf { it.isNotBlank() } ?: "默认提示音"} · ${full.successTexts.size} 条文案"
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("查看当前生效配置")
            }

            // ============ su 白名单（sulist）自动配置卡片 ============
            SulistCard(
                busy = sulistBusy,
                ok = sulistOk,
                msg = sulistMsg,
                onRecheck = { sulistTrigger++ }
            )

            // ============ 底部署名链接：@Srysfu + GitHub 图标 ============
            val context = LocalContext.current
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp)
                    .clickable {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Srysfu"))
                        )
                    },
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    painter = painterResource(id = R.drawable.ic_github),
                    contentDescription = "GitHub",
                    tint = adaptive.muted,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = "@Srysfu",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium,
                    color = adaptive.muted
                )
            }

            Spacer(Modifier.height(24.dp))
            }
            }
        }
    }
}

/**
 * 标题下方的行为开关条：隐藏后台卡片 / 保活 / 全静默 三个开关。
 * 设计为一张圆角浅底卡片，内部三个单元均分宽度（weight=1f），
 * 每个单元竖向排列「图标 + 标签 + 开关」，视觉统一、不重叠。
 */
@Composable
private fun BehaviorSwitchBar(
    backdrop: Backdrop,
    hideRecents: Boolean,
    savingHideRecents: Boolean,
    onHideRecents: (Boolean) -> Unit,
    keepAlive: Boolean,
    savingKeepAlive: Boolean,
    onKeepAlive: (Boolean) -> Unit,
    silentMode: Boolean,
    savingSilentMode: Boolean,
    onSilentMode: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SwitchCell(
                iconRes = R.drawable.ic_beh_recents,
                label = "隐藏后台卡片",
                checked = hideRecents,
                saving = savingHideRecents,
                onToggle = onHideRecents,
                modifier = Modifier.weight(1f)
            )
            SwitchCell(
                iconRes = R.drawable.ic_beh_keepalive,
                label = "保活",
                checked = keepAlive,
                saving = savingKeepAlive,
                onToggle = onKeepAlive,
                modifier = Modifier.weight(1f)
            )
            SwitchCell(
                iconRes = R.drawable.ic_beh_silent,
                label = "全静默",
                checked = silentMode,
                saving = savingSilentMode,
                onToggle = onSilentMode,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 行为开关条中的单个单元：图标在上、标签居中、开关在下。
 */
@Composable
private fun SwitchCell(
    iconRes: Int,
    label: String,
    checked: Boolean,
    saving: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    val adaptive = LocalAdaptiveTextColors.current
    Column(
        modifier = modifier.padding(horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = label,
            tint = adaptive.icon,
            modifier = Modifier.height(22.dp).width(22.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = adaptive.primary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            softWrap = false
        )
        Switch(
            checked = checked,
            onCheckedChange = onToggle,
            enabled = !saving
        )
    }
}

/**
 * 顶部一排 6 个命令图标（参照源图控车图标形态：图标在上、文字在下，横向一字排满）。
 * 每个子项均分一行（weight=1f），选中项高亮显示。
 */
@Composable
private fun CommandIconRow(
    commands: List<EditableCommand>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit
) {
    val adaptive = LocalAdaptiveTextColors.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        commands.forEachIndexed { index, cmd ->
            val selected = index == selectedIndex
            val spec = COMMAND_ICONS[cmd.name]
            val bg = Color.Transparent
            val fg = if (selected) adaptive.primary else adaptive.secondary
            val borderColor = Color.Transparent

            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = bg,
                        shape = RoundedCornerShape(14.dp)
                    )
                    .border(
                        border = BorderStroke(1.dp, borderColor),
                        shape = RoundedCornerShape(14.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(vertical = 10.dp, horizontal = 2.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (spec != null) {
                    Icon(
                        painter = painterResource(spec.resId),
                        contentDescription = cmd.name,
                        tint = spec.tint ?: fg,
                        modifier = Modifier
                            .height(24.dp)
                            .width(24.dp)
                    )
                } else {
                    Spacer(Modifier.height(22.dp))
                }
                Text(
                    text = cmd.name,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = fg,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    minLines = 2,
                    lineHeight = 12.sp
                )
            }
        }
    }
}

/**
 * 底部「反馈设置」方块：可折叠卡片。头部一行（可点击）显示标题与当前摘要，
 * 展开后展示「提示音」「成功文案」两个页签及内容；收起时仅保留头部，节约空间。
 */
@Composable
private fun BottomFeatureBlock(
    backdrop: Backdrop,
    expanded: Boolean,
    onToggle: () -> Unit,
    tab: FeedbackTab,
    onTabChange: (FeedbackTab) -> Unit,
    // 提示音参数
    selectedToneName: String?,
    toneUri: String,
    onSelectSuggested: (String, String) -> Unit,
    onCustomUriChange: (String) -> Unit,
    onPreviewTone: (android.content.Context) -> Unit,
    onSaveTone: () -> Unit,
    savingTone: Boolean,
    toneStatus: String,
    // 文案参数
    text: String,
    onTextChange: (String) -> Unit,
    onPreviewText: () -> Unit,
    onSaveText: () -> Unit,
    savingTexts: Boolean,
    textStatus: String,
    context: android.content.Context
) {
    val adaptive = LocalAdaptiveTextColors.current
    // 收起时的摘要：优先展示提示音名称，其次成功文案条数
    val summary = when {
        selectedToneName != null -> "提示音：$selectedToneName"
        toneUri.isNotBlank() -> "已自定义提示音"
        else -> "提示音：系统默认 · ${text.split('\n').count { it.isNotBlank() }} 条文案"
    }
    // 箭头旋转角度：展开时 180°，收起时 0°
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(durationMillis = 240),
        label = "feedbackArrow"
    )

    val glassSurface = Color.White.copy(alpha = 0.15f)
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(24.dp)
    ) {
        Column(Modifier.padding(vertical = 6.dp)) {
            // ===== 折叠头（可点击，点击展开/收起） =====
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                shape = RoundedCornerShape(14.dp),
                color = if (expanded) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f)
                        else Color.Transparent,
                onClick = onToggle
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 标题
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = "反馈设置",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = adaptive.primary
                        )
                        if (!expanded) {
                            Text(
                                text = summary,
                                style = MaterialTheme.typography.bodySmall,
                                color = adaptive.muted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    // 展开/收起提示 + 旋转箭头
                    Text(
                        text = if (expanded) "收起" else "展开",
                        style = MaterialTheme.typography.labelMedium,
                        color = adaptive.muted,
                        fontWeight = FontWeight.Medium
                    )
                    Spacer(Modifier.width(4.dp))
                    Icon(
                        imageVector = Icons.Default.KeyboardArrowDown,
                        contentDescription = if (expanded) "收起" else "展开",
                        tint = adaptive.muted,
                        modifier = Modifier
                            .height(22.dp)
                            .width(22.dp)
                            .rotate(arrowRotation)
                    )
                }
            }

            // ===== 展开区域（页签 + 内容）平滑展开/收起 =====
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp)) {
                    // ===== 页签切换条 =====
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        BottomTabItem(
                            enabled = tab == FeedbackTab.TONE,
                            iconResId = R.drawable.ic_tab_tone,
                            label = "提示音",
                            modifier = Modifier.weight(1f),
                            onClick = { onTabChange(FeedbackTab.TONE) }
                        )
                        BottomTabItem(
                            enabled = tab == FeedbackTab.TEXT,
                            iconResId = R.drawable.ic_tab_text,
                            label = "成功文案",
                            modifier = Modifier.weight(1f),
                            onClick = { onTabChange(FeedbackTab.TEXT) }
                        )
                    }

                    Spacer(Modifier.height(8.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                    Spacer(Modifier.height(8.dp))

                    // ===== 页签内容 =====
                    when (tab) {
                        FeedbackTab.TONE -> ToneSection(
                            selectedToneName = selectedToneName,
                            toneUri = toneUri,
                            onSelectSuggested = onSelectSuggested,
                            onCustomUriChange = onCustomUriChange,
                            onPreview = { onPreviewTone(context) },
                            onSave = onSaveTone,
                            saving = savingTone,
                            status = toneStatus
                        )
                        FeedbackTab.TEXT -> TextSection(
                            text = text,
                            onTextChange = onTextChange,
                            onPreview = onPreviewText,
                            onSave = onSaveText,
                            saving = savingTexts,
                            status = textStatus
                        )
                    }
                }
            }
        }
    }
}

/** 底部方块内的单个页签按钮 */
@Composable
private fun BottomTabItem(
    enabled: Boolean,
    iconResId: Int,
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val adaptive = LocalAdaptiveTextColors.current
    val bg = Color.Transparent
    val fg = if (enabled) adaptive.primary else adaptive.secondary
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = bg,
        border = null,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(iconResId),
                contentDescription = label,
                tint = fg,
                modifier = Modifier
                    .height(18.dp)
                    .width(18.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (enabled) FontWeight.Bold else FontWeight.Medium,
                color = fg
            )
        }
    }
}

/** 提示音页签内容区 */
@Composable
private fun ToneSection(
    selectedToneName: String?,
    toneUri: String,
    onSelectSuggested: (String, String) -> Unit,
    onCustomUriChange: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    status: String
) {
    val adaptive = LocalAdaptiveTextColors.current
    Text(
        text = "选择或输入提示音，命中车辆口令后作为「已成功」提示音播放；留空则用系统默认通知音。",
        style = MaterialTheme.typography.bodySmall,
        color = adaptive.secondary
    )

    Spacer(Modifier.height(8.dp))

    // 建议铃声列表（双排布局：每行两个按钮，均分宽度）
    SUGGESTED_TONES.chunked(2).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowItems.forEach { (name, uri) ->
                val selected = selectedToneName == name
                Button(
                    onClick = { onSelectSuggested(name, uri) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 2.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.Transparent,
                        contentColor = if (selected) adaptive.primary else adaptive.secondary
                    )
                ) {
                    Text(if (selected) "✓ $name" else name, maxLines = 1)
                }
            }
            if (rowItems.size == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    // 自定义 URI 输入框
    OutlinedTextField(
        value = toneUri,
        onValueChange = onCustomUriChange,
        modifier = Modifier.fillMaxWidth(),
        label = { Text("自定义 URI（留空用默认）", color = adaptive.muted) },
        minLines = 1,
        textStyle = TextStyle(fontSize = 14.sp, color = adaptive.primary),
        placeholder = { Text("file:/// 或 content:// 开头，如 file:///sdcard/Music/a.ogg", color = adaptive.muted) },
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = adaptive.primary,
            unfocusedTextColor = adaptive.primary,
            focusedLabelColor = adaptive.secondary,
            unfocusedLabelColor = adaptive.muted,
            focusedPlaceholderColor = adaptive.muted,
            unfocusedPlaceholderColor = adaptive.muted,
            focusedBorderColor = adaptive.secondary,
            unfocusedBorderColor = adaptive.muted,
            cursorColor = adaptive.primary
        )
    )

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onPreview,
            modifier = Modifier.weight(1f)
        ) {
            Text("试听")
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (saving) "保存中…" else "保存提示音")
        }
    }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

/** 成功文案页签内容区 */
@Composable
private fun TextSection(
    text: String,
    onTextChange: (String) -> Unit,
    onPreview: () -> Unit,
    onSave: () -> Unit,
    saving: Boolean,
    status: String
) {
    val adaptive = LocalAdaptiveTextColors.current
    Text(
        text = "每行一条，保存后命中口令时随机显示其中一条；点击下方预设可一键载入该方案。",
        style = MaterialTheme.typography.bodySmall,
        color = adaptive.secondary
    )

    Spacer(Modifier.height(8.dp))

    // 预制方案快捷载入（双排布局：每行两个按钮，均分宽度；5 项按 2+2+1 排列）
    PRESET_TEXT_SETS.entries.chunked(2).forEach { rowItems ->
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            rowItems.forEach { (name, lines) ->
                OutlinedButton(
                    onClick = { onTextChange(lines.joinToString("\n")) },
                    modifier = Modifier
                        .weight(1f)
                        .padding(vertical = 1.dp)
                ) {
                    Text("载入「$name」(${lines.size} 条)", maxLines = 1)
                }
            }
            if (rowItems.size == 1) {
                Spacer(Modifier.weight(1f))
            }
        }
    }

    Spacer(Modifier.height(8.dp))

    OutlinedTextField(
        value = text,
        onValueChange = onTextChange,
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp),
        label = { Text("文案内容（每行一条）", color = adaptive.muted) },
        minLines = 4,
        textStyle = TextStyle(fontSize = 14.sp, color = adaptive.primary),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = adaptive.primary,
            unfocusedTextColor = adaptive.primary,
            focusedLabelColor = adaptive.secondary,
            unfocusedLabelColor = adaptive.muted,
            focusedBorderColor = adaptive.secondary,
            unfocusedBorderColor = adaptive.muted,
            cursorColor = adaptive.primary
        )
    )

    Spacer(Modifier.height(8.dp))

    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(
            onClick = onPreview,
            modifier = Modifier.weight(1f)
        ) {
            Text("随机试看")
        }
        Button(
            onClick = onSave,
            enabled = !saving,
            modifier = Modifier.weight(1f)
        ) {
            Text(if (saving) "保存中…" else "保存文案")
        }
    }

    if (status.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(
            text = status,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary
        )
    }
}

@Composable
private fun CommandCard(
    cmd: EditableCommand,
    onKeywordsChange: (String) -> Unit
) {
    val adaptive = LocalAdaptiveTextColors.current
    // 用 remember(cmd) 派生可写输入 state：跟随选中命令切换而重置，
    // 每次键盘输入都写入并触发重组，避免 mutableStateListOf 不响应元素字段修改的坑。
    var input by remember(cmd.code) { mutableStateOf(cmd.keywordsText) }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = cmd.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = adaptive.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "命令码 ${cmd.code}",
                    style = MaterialTheme.typography.labelSmall,
                    color = adaptive.muted
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = input,
                onValueChange = {
                    input = it
                    onKeywordsChange(it)
                },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("唤醒词（分隔符隔开）", color = adaptive.muted) },
                minLines = 1,
                textStyle = TextStyle(fontSize = 15.sp, color = adaptive.primary),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = adaptive.primary,
                    unfocusedTextColor = adaptive.primary,
                    focusedLabelColor = adaptive.secondary,
                    unfocusedLabelColor = adaptive.muted,
                    focusedBorderColor = adaptive.secondary,
                    unfocusedBorderColor = adaptive.muted,
                    cursorColor = adaptive.primary
                )
            )
        }
    }
}

/**
 * su 白名单（sulist）自动配置状态卡片。
 *
 * 模块首次打开界面（触发 LaunchedEffect(sulistTrigger)）时自动检测白名单，
 * 缺失则补写小爱（com.miui.voiceassist）与乘趣（com.ingeek.nokey）的主进程 + 子进程通配条目。
 *
 * @param busy      正在检查/写入时置位（禁用按钮、显示进度）
 * @param ok        白名单是否已齐备（决定「已配置」绿标 vs 「待处理」橙标）
 * @param msg       状态提示文案（含缺失清单 / root 提示 / 重启生效提示）
 * @param onRecheck 点击「立即检测/补写」重跑检测（游标 sulistTrigger++）
 */
@Composable
private fun SulistCard(
    busy: Boolean,
    ok: Boolean,
    msg: String?,
    onRecheck: () -> Unit
) {
    val adaptive = LocalAdaptiveTextColors.current
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.15f)),
        shape = RoundedCornerShape(20.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "su 白名单（sulist）自动配置",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = adaptive.primary
                )
                Spacer(Modifier.weight(1f))
                if (busy) {
                    Text(
                        text = "检测中…",
                        style = MaterialTheme.typography.labelSmall,
                        color = adaptive.secondary
                    )
                } else if (msg != null && ok) {
                    Text(
                        text = "已配置",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                } else if (msg != null) {
                    Text(
                        text = "待处理",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9800)
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            Text(
                text = "确保小爱同学与乘趣（含子进程）已加入 su 白名单，使模块可静默执行 root 命令。缺失时首次打开界面会自动补齐。",
                style = MaterialTheme.typography.bodySmall,
                color = adaptive.secondary
            )

            if (msg != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = msg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (ok) Color(0xFF4CAF50) else Color(0xFFFF9800)
                )
            }

            Spacer(Modifier.height(10.dp))

            OutlinedButton(
                onClick = onRecheck,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = adaptive.primary
                ),
                border = BorderStroke(1.dp, adaptive.muted)
            ) {
                Text(if (busy) "正在检测/补写…" else "立即检测/补写")
            }

            if (ok && msg?.contains("重启设备") == true) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "提示：修改 sulist 需要【重启设备】后 magiskd 重新加载才生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = adaptive.muted
                )
            }
        }
    }
}

/**
 * 通用配置写入：支持唤醒词 / 提示音 URI / 成功文案三个维度的独立或合并保存。
 *
 * 只传入非空的维度做局部更新，其余维度从当前配置文件读出再合并写回，
 * 保证三个保存按钮互不干扰（分开保存语义）。
 * - 1) 先通过 NokeyConfig.writeTo 直接写（root shell 直跑 / 存储可写时可行）；
 * - 2) 失败则 su 提权写入（真机 root 场景保证可用）。
 */
private suspend fun writeConfig(
    commands: List<EditableCommand>? = null,
    toneUri: String? = null,
    successTexts: List<String>? = null,
    hideRecents: Boolean? = null,
    keepAlive: Boolean? = null,
    silentMode: Boolean? = null
): Boolean = withContext(Dispatchers.IO) {
    val cmdList = commands?.map {
        CommandMatcher.Command(
            it.code,
            it.name,
            it.keywordsText.split('，', ',', '、', ' ', '\n')
                .map { s -> s.trim() }
                .filter { s -> s.isNotEmpty() }
        )
    }

    // 1) 先尝试直接写（NokeyConfig.writeTo 内部会读现有配置做合并、刷新缓存）
    if (NokeyConfig.writeTo(commands = cmdList, toneUri = toneUri, successTexts = successTexts, hideRecents = hideRecents, keepAlive = keepAlive, silentMode = silentMode)) {
        return@withContext true
    }

    // 2) su 提权写入：需在 UI 侧自行合并现有配置并序列化
    try {
        val full = NokeyConfig.loadFull(force = true)
        val mergedCommands = cmdList ?: full?.commands.orEmpty()
        val mergedTone = toneUri ?: full?.toneUri
        val mergedTexts = successTexts ?: full?.successTexts.orEmpty()
        val mergedHideRecents = hideRecents ?: full?.hideRecents ?: false
        val mergedKeepAlive = keepAlive ?: full?.keepAlive ?: false
        val mergedSilentMode = silentMode ?: full?.silentMode ?: false
        val json = NokeyConfig.toJson(mergedCommands, mergedTone, mergedTexts, mergedHideRecents, mergedKeepAlive, mergedSilentMode)

        val escaped = json
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("$", "\\$")
            .replace("`", "\\`")
        val cmd = "su -c 'echo \"$escaped\" > ${NokeyConfig.CONFIG_FILE} && chmod 644 ${NokeyConfig.CONFIG_FILE}'"
        val exit = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd)).waitFor()
        if (exit == 0) {
            // 写成功后清掉缓存，让 hook 端/UI 端下次读到新值
            NokeyConfig.loadFull(force = true)
            return@withContext true
        }
        return@withContext false
    } catch (t: Throwable) {
        return@withContext false
    }
}

/** 建议铃声列表：第一项为显示名，第二项为可直接交给 MediaPlayer 的 URI */
private val SUGGESTED_TONES: List<Pair<String, String>> = listOf(
    "水滴（默认）" to "file:///system/media/audio/ui/WaterDrop_preview.ogg",
    "提示音（UI 标准通知）" to "content://settings/system/notification_sound",
    "锁屏解锁声" to "file:///system/media/audio/ui/unlock.ogg",
    "充电提示音" to "file:///system/media/audio/ui/ChargingStarted.ogg",
    "相机快门" to "file:///system/media/audio/ui/camera_click.ogg",
    "温馨提示音" to "file:///system/media/audio/ui/Effect_Tick.ogg"
)

/** 预制「已成功」反馈文案方案：每套为多条中文句子，保存后按行随机显示 */
private val PRESET_TEXT_SETS: Map<String, List<String>> = linkedMapOf(
    "简洁正式" to listOf(
        "已执行。",
        "已完成。",
        "指令已执行。"
    ),
    "日常口语" to listOf(
        "好嘞，搞定！",
        "收到，马上办妥！",
        "没问题，已经处理好啦。"
    ),
    "俏皮卖萌" to listOf(
        "这就给您安排上！",
        "妥妥的，办好啦～",
        "小助手马上帮您完成啦 ✨"
    ),
    "方言趣味" to listOf(
        "好嘞，整明白嘞！",
        "妥了妥了，搞定咯！",
        "收到收到，马上整好！"
    ),
    "极简一句" to listOf("成功")
)

/**
 * 在 UI 进程用 MediaPlayer 试听所选提示音。
 * - uri 为 null 时回退系统默认通知提示音；
 * - 播放失败（文件不存在 / 无法解析）时 Toast 提示并回退默认通知音再试一次。
 */
private fun previewTone(context: android.content.Context, uri: String?) {
    val player = MediaPlayer()
    try {
        val target = if (uri.isNullOrBlank()) {
            RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
        } else {
            Uri.parse(uri.trim())
        }
        if (target == null) {
            Toast.makeText(context, "无可用提示音", Toast.LENGTH_SHORT).show()
            return
        }
        player.setAudioAttributes(
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        player.setDataSource(context, target)
        player.prepare()
        player.start()
        // 一次性试听：播放完成或 5 秒后释放，避免复用旧实例造成状态残留
        player.setOnCompletionListener { it.release() }
        player.setOnErrorListener { mp, _, _ -> mp.release(); true }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            try {
                if (player.isPlaying) player.stop()
                player.release()
            } catch (_: Throwable) {
            }
        }, 5000L)
    } catch (t: Throwable) {
        try { player.release() } catch (_: Throwable) {}
        // 尝试回退到系统默认通知音
        try {
            val fallback = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            if (fallback != null) {
                val fb = MediaPlayer()
                fb.setAudioAttributes(
                    android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                fb.setDataSource(context, fallback)
                fb.prepare()
                fb.start()
                fb.setOnCompletionListener { it.release() }
            } else {
                Toast.makeText(context, "提示音无法播放：${t.message}", Toast.LENGTH_SHORT).show()
            }
        } catch (t2: Throwable) {
            Toast.makeText(context, "提示音无法播放：${t.message}", Toast.LENGTH_SHORT).show()
        }
    }
}