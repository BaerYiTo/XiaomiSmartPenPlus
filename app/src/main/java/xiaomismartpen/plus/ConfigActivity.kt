package xiaomismartpen.plus

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.roundToLong

/** Compose and Miuix are loaded only in the configuration app, never in the hook host. */
class ConfigActivity : ComponentActivity() {
    private var config by mutableStateOf<PenConfig?>(null)
    private var saveStatus by mutableStateOf("正在载入配置")
    private var message by mutableStateOf<String?>(null)
    private var saveGeneration = 0L
    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    ) { dark },
                    navigationBarStyle = SystemBarStyle.auto(
                        android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT,
                    ) { dark },
                )
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                ConfigScreen()
            }
        }
    }

    private fun updateConfig(packageName: String?, edit: (PenConfig) -> Unit) {
        val next = config?.copy() ?: return
        val target = if (packageName == null) next else next.appConfig(packageName) ?: return
        edit(target)
        persist(next)
    }

    private fun persist(next: PenConfig) {
        config = next
        val generation = ++saveGeneration
        saveStatus = "正在保存"
        val appContext = applicationContext
        // Saves and reads share one queue: a recreated Activity cannot load before a pending save.
        persistence.execute {
            val failure = runCatching { ConfigStore.save(appContext, next) }.exceptionOrNull()
            mainHandler.post {
                if (!isDestroyed && generation == saveGeneration) {
                    saveStatus = if (failure == null) "已保存" else "保存未完成"
                    if (failure != null) message = "无法保存配置，请返回后重试。"
                }
            }
        }
    }

    @Composable
    private fun ConfigScreen() {
        var page by rememberSaveable { mutableIntStateOf(0) }
        var selectedPackage by rememberSaveable { mutableStateOf<String?>(null) }
        var appSearch by rememberSaveable { mutableStateOf("") }
        var chooser by rememberSaveable { mutableStateOf("") }
        var chooserSearch by rememberSaveable { mutableStateOf("") }
        var chooserProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var chooserButton by rememberSaveable { mutableStateOf(PenConfig.B1) }
        var chooserSlot by rememberSaveable { mutableStateOf(PenConfig.SHORT) }
        var deletePackage by rememberSaveable { mutableStateOf<String?>(null) }
        var resetRequested by rememberSaveable { mutableStateOf(false) }
        var resetProfile by rememberSaveable { mutableStateOf<String?>(null) }
        var apps by remember { mutableStateOf<List<PenApp>>(emptyList()) }
        var appsLoaded by remember { mutableStateOf(false) }
        var appLoadError by remember { mutableStateOf(false) }
        var appLoadGeneration by remember { mutableIntStateOf(0) }

        LaunchedEffect(Unit) {
            val result = withContext(Dispatchers.IO) {
                runCatching { persistence.submit<PenConfig> { ConfigStore.load(applicationContext) }.get() }
            }
            result.onSuccess {
                config = it
                saveStatus = "配置已载入"
            }.onFailure {
                saveStatus = "配置载入失败"
                message = "无法载入已保存的配置。请关闭页面后重试，现有配置没有被覆盖。"
            }
        }
        LaunchedEffect(appLoadGeneration) {
            appsLoaded = false
            val result = withContext(Dispatchers.IO) { runCatching { loadLaunchableApps() } }
            apps = result.getOrDefault(emptyList())
            appLoadError = result.isFailure
            appsLoaded = true
        }

        val current = config
        // Preserve entries for uninstalled/non-launcher apps and obtain their labels off the UI thread.
        val packages = current?.appPackages().orEmpty()
        var extraApps by remember { mutableStateOf<Map<String, PenApp>>(emptyMap()) }
        LaunchedEffect(packages, apps) {
            val known = apps.map { it.pkg }.toSet()
            extraApps = withContext(Dispatchers.IO) {
                packages.filter { it !in known }.associateWith { loadApp(it, launchable = false) }
            }
        }
        val catalog = remember(apps, extraApps) { apps.associateBy { it.pkg } + extraApps }
        LaunchedEffect(current != null, packages, selectedPackage) {
            if (current != null && selectedPackage != null && selectedPackage !in packages) selectedPackage = null
        }

        fun openAppChooser(mode: String, profile: String? = null, button: String = PenConfig.B1,
                           slot: String = PenConfig.SHORT) {
            chooserProfile = profile
            chooserButton = button
            chooserSlot = slot
            chooserSearch = ""
            chooser = mode
        }

        BackHandler(
            enabled = chooser.isEmpty() && deletePackage == null && !resetRequested && message == null &&
                page != 0,
        ) {
            if (selectedPackage != null && page == 1) selectedPackage = null else page = 0
        }

        val lists = List(4) { rememberLazyListState() }
        val appBars = List(4) { rememberTopAppBarState() }
        val screen = when { page == 0 -> 0; page == 1 && selectedPackage == null -> 1; page == 1 -> 2; else -> 3 }
        val listState = lists[screen]
        val scrollBehavior = MiuixScrollBehavior(state = appBars[screen],
            canScroll = { listState.canScrollBackward || listState.canScrollForward })
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            topBar = {
                TopAppBar(
                    title = "焦点笔 Plus",
                    subtitle = if (page == 1 && selectedPackage != null) "应用专属配置" else "Plus · 笔键配置",
                    scrollBehavior = scrollBehavior,
                )
            },
        ) { insets ->
            Box(
                Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets),
                contentAlignment = Alignment.TopCenter,
            ) {
                Column(Modifier.widthIn(max = 1040.dp).fillMaxWidth()) {
                    TabRow(
                        tabs = listOf("默认配置", "应用配置", "关于"),
                        selectedTabIndex = page,
                        onTabSelected = { page = it },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                        height = 48.dp,
                    )
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (current == null) {
                            item { InfoCard(saveStatus, "正在读取你的笔键设置。") }
                        } else when (page) {
                            0 -> {
                                item {
                                    InfoCard("默认配置", "没有专属配置的应用使用这一组设置。更改会自动保存。", saveStatus)
                                }
                                item {
                                    ProfileEditor(
                                        current, null, catalog,
                                        onEdit = { edit -> updateConfig(null, edit) },
                                        onChooseLaunch = { button, slot -> openAppChooser("action", null, button, slot) },
                                    )
                                }
                                item {
                                    TextButton("恢复默认配置的初始值", onClick = {
                                        resetProfile = null
                                        resetRequested = true
                                    },
                                        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                }
                                item { GestureGuide() }
                            }
                            1 -> {
                                val selected = selectedPackage
                                val appConfig = selected?.let { current.appConfig(it) }
                                if (selected != null && appConfig != null) {
                                    item {
                                        TextButton("返回应用列表", onClick = { selectedPackage = null },
                                            modifier = Modifier.heightIn(min = 48.dp))
                                    }
                                    item {
                                        InfoCard(catalog[selected]?.label ?: selected,
                                            "$selected\n仅在此应用位于前台时使用。所有笔键和时间阈值独立保存。", saveStatus)
                                    }
                                    item {
                                        ProfileEditor(appConfig, selected, catalog,
                                            onEdit = { edit -> updateConfig(selected, edit) },
                                            onChooseLaunch = { button, slot -> openAppChooser("action", selected, button, slot) })
                                    }
                                    item {
                                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                            TextButton("复制当前默认配置", onClick = {
                                                resetProfile = selected
                                                resetRequested = true
                                            },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                            TextButton("删除此应用配置", onClick = { deletePackage = selected },
                                                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
                                        }
                                    }
                                    item { GestureGuide() }
                                } else {
                                    item {
                                        InfoCard("按应用配置", "为不同应用设置不同的笔键动作。添加时复制当前默认配置，以后各自独立。",
                                            "${packages.size} 个应用已有专属配置")
                                    }
                                    item {
                                        TextButton("添加应用配置", onClick = { openAppChooser("profile") },
                                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                                            colors = ButtonDefaults.textButtonColorsPrimary())
                                    }
                                    item {
                                        TextField(value = appSearch, onValueChange = { appSearch = it },
                                            label = "搜索已配置的应用或包名", singleLine = true,
                                            modifier = Modifier.fillMaxWidth())
                                    }
                                    val filtered = packages.filter {
                                        it.contains(appSearch.trim(), ignoreCase = true) ||
                                            catalog[it]?.label?.contains(appSearch.trim(), ignoreCase = true) == true
                                    }.sortedWith(compareBy { catalog[it]?.label ?: it })
                                    if (filtered.isEmpty()) item {
                                        InfoCard(if (packages.isEmpty()) "还没有应用配置" else "没有匹配的应用",
                                            if (packages.isEmpty()) "添加一个应用，为它设置专属笔键。其余应用继续使用默认配置。" else "试试应用名称或包名。")
                                    }
                                    items(filtered, key = { it }) { pkg ->
                                        val profile = current.appConfig(pkg)!!
                                        Card(Modifier.fillMaxWidth()) {
                                            ArrowPreference(
                                                title = catalog[pkg]?.label ?: pkg,
                                                summary = "$pkg\n笔键 1：${ownershipLabel(profile, PenConfig.B1)} · 笔键 2：${ownershipLabel(profile, PenConfig.B2)}",
                                                startAction = { AppIcon(catalog[pkg]) },
                                                onClick = { selectedPackage = pkg },
                                            )
                                        }
                                    }
                                }
                            }
                            else -> item { About() }
                        }
                        item { Spacer(Modifier.height(10.dp)) }
                    }
                }
            }

            OverlayDialog(
                show = chooser.isNotEmpty(),
                title = if (chooser == "profile") "选择应用" else "选择要启动的应用",
                summary = if (chooser == "profile") "新配置从当前默认配置复制；已有配置会直接打开。" else "选择具有桌面入口的应用。",
                onDismissRequest = { chooser = "" },
            ) {
                TextField(value = chooserSearch, onValueChange = { chooserSearch = it },
                    label = "搜索名称或包名", singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                val found = apps.filter {
                    val query = chooserSearch.trim()
                    it.label.contains(query, ignoreCase = true) || it.pkg.contains(query, ignoreCase = true)
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (!appsLoaded) item { DialogHint("正在读取应用列表…") }
                    else if (appLoadError) item { DialogHint("无法读取应用列表，请重试。") }
                    else if (found.isEmpty()) item {
                        DialogHint(if (apps.isEmpty()) "没有找到可启动的应用。" else "没有匹配的应用。")
                    }
                    items(found, key = { it.pkg }) { app ->
                        ArrowPreference(
                            title = app.label,
                            summary = app.pkg + if (chooser == "profile" && app.pkg in packages) " · 已配置" else "",
                            startAction = { AppIcon(app) },
                            insideMargin = PaddingValues(horizontal = 4.dp, vertical = 12.dp),
                            enabled = current != null,
                            onClick = {
                                if (chooser == "profile") {
                                    val next = config?.copy()
                                    if (next != null) {
                                        if (next.appConfig(app.pkg) == null) {
                                            next.ensureAppConfig(app.pkg)
                                            persist(next)
                                        }
                                        selectedPackage = app.pkg
                                        page = 1
                                    }
                                } else updateConfig(chooserProfile) {
                                    it.setAction(chooserButton, chooserSlot, PenConfig.ACTION_LAUNCH_PREFIX + app.pkg)
                                }
                                chooser = ""
                            },
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    TextButton("取消", onClick = { chooser = "" }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                    if (appLoadError) TextButton("重试", onClick = { appLoadGeneration++ },
                        modifier = Modifier.weight(1f).heightIn(min = 48.dp))
                }
            }

            OverlayDialog(
                show = deletePackage != null,
                title = "删除应用配置？",
                summary = "${deletePackage?.let { catalog[it]?.label } ?: deletePackage.orEmpty()} 将重新使用默认配置。其他应用的配置会保留。",
                onDismissRequest = { deletePackage = null },
            ) {
                ConfirmButtons("删除", enabled = current != null, onCancel = { deletePackage = null }) {
                    deletePackage?.let { pkg ->
                        config?.copy()?.let { next ->
                            next.removeAppConfig(pkg)
                            persist(next)
                            if (selectedPackage == pkg) selectedPackage = null
                        }
                    }
                    deletePackage = null
                }
            }

            OverlayDialog(
                show = resetRequested,
                title = if (resetProfile == null) "恢复初始配置？" else "复制默认配置？",
                summary = if (resetProfile == null) "默认配置的笔键动作与时间阈值将恢复初始值。已添加的应用配置会保留。"
                    else "此应用的笔键动作与时间阈值将替换为当前默认配置。其他应用的配置会保留。",
                onDismissRequest = { resetRequested = false },
            ) {
                ConfirmButtons(if (resetProfile == null) "恢复" else "复制", enabled = current != null,
                    onCancel = { resetRequested = false }) {
                    val source = if (resetProfile == null) PenConfig.defaults() else config
                    if (source != null) updateConfig(resetProfile) { replaceSettings(it, source) }
                    resetRequested = false
                }
            }
            OverlayDialog(show = message != null, title = "焦点笔 Plus", summary = message,
                onDismissRequest = { message = null }) {
                TextButton("知道了", onClick = { message = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp))
            }
        }
    }

    @Composable
    private fun ProfileEditor(
        profile: PenConfig,
        packageName: String?,
        catalog: Map<String, PenApp>,
        onEdit: ((PenConfig) -> Unit) -> Unit,
        onChooseLaunch: (String, String) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                if (maxWidth >= 800.dp) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                        ButtonCard(profile, PenConfig.B1, catalog, onEdit, onChooseLaunch, Modifier.weight(1f))
                        ButtonCard(profile, PenConfig.B2, catalog, onEdit, onChooseLaunch, Modifier.weight(1f))
                    }
                } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    ButtonCard(profile, PenConfig.B1, catalog, onEdit, onChooseLaunch)
                    ButtonCard(profile, PenConfig.B2, catalog, onEdit, onChooseLaunch)
                }
            }
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("识别时间", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                SecondaryText(if (packageName == null) "默认配置中的长按与双击阈值。" else "这组阈值仅用于当前应用。")
                Spacer(Modifier.height(20.dp))
                TimingSlider("长按阈值", profile.longPressMs, 200f..1500f, 129,
                    "按住多久后识别为长按。") { value -> onEdit { it.longPressMs = value } }
                Spacer(Modifier.height(20.dp))
                TimingSlider("双击间隔", profile.doubleWindowMs, 150f..800f, 64,
                    "两次点击之间允许的最长间隔；越大，单击响应等待越久。") { value -> onEdit { it.doubleWindowMs = value } }
            }
        }
    }

    @Composable
    private fun ButtonCard(
        profile: PenConfig,
        button: String,
        catalog: Map<String, PenApp>,
        onEdit: ((PenConfig) -> Unit) -> Unit,
        onChooseLaunch: (String, String) -> Unit,
        modifier: Modifier = Modifier,
    ) {
        Card(modifier.fillMaxWidth()) {
            SwitchPreference(
                checked = profile.takeover(button),
                onCheckedChange = { checked -> onEdit { it.setTakeover(button, checked) } },
                title = if (button == PenConfig.B1) "笔键 1" else "笔键 2",
                summary = if (profile.takeover(button)) "使用下方动作配置"
                    else "保持系统原生；仍可预先配置动作",
            )
            PenConfig.SLOTS.forEach { slot ->
                val current = profile.action(button, slot)
                val launchesApp = current.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)
                val launchPackage = if (launchesApp) current.removePrefix(PenConfig.ACTION_LAUNCH_PREFIX) else ""
                val actionLabels = PenConfig.ACTION_LABELS.toList() + if (launchesApp)
                    "启动应用：${catalog[launchPackage]?.label ?: launchPackage}" else "启动应用…"
                val selected = if (launchesApp) PenConfig.ACTIONS.size
                    else PenConfig.ACTIONS.indexOf(current).coerceAtLeast(0)
                OverlayDropdownPreference(
                    items = actionLabels,
                    selectedIndex = selected,
                    title = when (slot) { PenConfig.SHORT -> "单击"; PenConfig.LONG -> "长按"; else -> "双击" },
                    summary = when (current) {
                        PenConfig.ACTION_PAGE_UP, PenConfig.ACTION_PAGE_DOWN -> "发送翻页按键，适合浏览器和文档"
                        PenConfig.ACTION_SWIPE_UP, PenConfig.ACTION_SWIPE_DOWN -> "模拟触摸滑动，适合短视频等应用"
                        PenConfig.ACTION_PASSTHROUGH -> "将这次笔键操作交给当前应用"
                        PenConfig.ACTION_NONE -> "此手势不执行额外动作"
                        else -> null
                    },
                    maxHeight = 420.dp,
                    onSelectedIndexChange = { index ->
                        if (index == PenConfig.ACTIONS.size) onChooseLaunch(button, slot)
                        else onEdit { it.setAction(button, slot, PenConfig.ACTIONS[index]) }
                    },
                )
                if (launchesApp) ArrowPreference(
                    title = "更换启动应用",
                    summary = launchPackage,
                    onClick = { onChooseLaunch(button, slot) },
                )
            }
        }
    }

    @Composable
    private fun TimingSlider(title: String, value: Long, range: ClosedFloatingPointRange<Float>, steps: Int,
                             hint: String, onChange: (Long) -> Unit) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
            Text("$value ms", color = MiuixTheme.colorScheme.primary, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
        Spacer(Modifier.height(6.dp))
        SecondaryText(hint)
        Slider(
            value = value.toFloat(),
            onValueChange = { raw ->
                val next = ((raw / 10f).roundToLong() * 10L).coerceIn(range.start.toLong(), range.endInclusive.toLong())
                if (next != value) onChange(next)
            },
            valueRange = range,
            steps = steps,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title },
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            SecondaryText("${range.start.toInt()} ms")
            SecondaryText("${range.endInclusive.toInt()} ms")
        }
    }

    @Composable
    private fun InfoCard(title: String, detail: String, status: String? = null) {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(20.dp),
            colors = CardDefaults.defaultColors(color = MiuixTheme.colorScheme.primary.copy(alpha = 0.09f))) {
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SecondaryText(detail)
            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(status, fontSize = 13.sp, color = MiuixTheme.colorScheme.primary,
                    modifier = Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                        RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
    }

    @Composable
    private fun GestureGuide() {
        Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
            Text("动作说明", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))
            SecondaryText("「上滑 / 下滑」模拟触摸手势，适合抖音、小红书等应用。「上翻页 / 下翻页」发送键盘翻页按键，适合浏览器和文档。")
            Spacer(Modifier.height(8.dp))
            SecondaryText("接管关闭时，笔键保持系统原生。接管开启但三个手势都设为「不执行」时，此笔键也保持原生。")
        }
    }

    @Composable
    private fun About() {
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            InfoCard("焦点笔 Plus", "笔键重映射 · 默认配置与应用专属配置", "版本 ${BuildConfig.VERSION_NAME}")
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("使用说明", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SecondaryText("在 LSPosed 中启用模块，并按框架提示重启。模块加载后，已保存的设置会自动同步。")
                Spacer(Modifier.height(8.dp))
                SecondaryText("应用配置优先于默认配置。删除应用配置后，该应用重新使用默认配置。旧版应用名单会迁移为对应的应用配置。")
                Spacer(Modifier.height(8.dp))
                SecondaryText("需要恢复系统原生行为时，可关闭对应笔键的接管；也可以在 LSPosed 中停用模块并重启。")
                Spacer(Modifier.height(8.dp))
                SecondaryText("从旧版迁移时，先打开本应用确认配置，再停用旧模块、启用 Plus 并重启，避免同时接管笔键。")
            }
            Card(Modifier.fillMaxWidth(), insideMargin = PaddingValues(18.dp)) {
                Text("项目声明", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(8.dp))
                SecondaryText("本项目由社区独立维护，与小米及其关联公司无隶属、授权或背书关系。小米及相关产品名称仅用于说明设备兼容性。")
                Spacer(Modifier.height(8.dp))
                SecondaryText("软件按现状提供。Root、LSPosed 与系统 Hook 的兼容性取决于设备和 ROM；使用前请备份，遇到异常可停用模块并重启。第三方组件版权和许可证保留在源码与 APK 的 third_party 目录中。")
            }
            Card(Modifier.fillMaxWidth()) {
                ArrowPreference(title = "焦点笔 Plus 源码", summary = "BaerYiTo/XiaomiSmartPenPlus · Apache 2.0",
                    onClick = { openUrl("https://github.com/BaerYiTo/XiaomiSmartPenPlus") })
                ArrowPreference(title = "Miuix 开源项目", summary = "compose-miuix-ui/miuix · Apache 2.0",
                    onClick = { openUrl("https://github.com/compose-miuix-ui/miuix") })
            }
        }
    }

    @Composable
    private fun ConfirmButtons(confirm: String, enabled: Boolean = true, onCancel: () -> Unit, onConfirm: () -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton("取消", onClick = onCancel, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(confirm, onClick = onConfirm, enabled = enabled, modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }

    @Composable
    private fun SecondaryText(value: String) {
        Text(value, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }

    @Composable
    private fun DialogHint(value: String) {
        Text(value, modifier = Modifier.padding(vertical = 20.dp), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
    }

    @Composable
    private fun AppIcon(app: PenApp?) {
        Box(Modifier.padding(end = 14.dp).size(38.dp), contentAlignment = Alignment.Center) {
            if (app?.icon != null) Image(app.icon, contentDescription = null, modifier = Modifier.fillMaxSize())
            else Text(app?.label?.take(1) ?: "应", fontSize = 19.sp, fontWeight = FontWeight.Medium,
                color = MiuixTheme.colorScheme.primary,
                modifier = Modifier.background(MiuixTheme.colorScheme.primary.copy(alpha = 0.10f),
                    RoundedCornerShape(10.dp)).padding(8.dp))
        }
    }

    private fun loadLaunchableApps(): List<PenApp> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val resolved = packageManager.queryIntentActivities(intent, 0)
        val collator = Collator.getInstance(Locale.CHINA)
        return resolved.map { it.activityInfo.packageName }.distinct().map { loadApp(it, launchable = true) }
            .sortedWith { first, second -> collator.compare(first.label, second.label) }
    }

    private fun loadApp(pkg: String, launchable: Boolean): PenApp {
        return try {
            val info = packageManager.getApplicationInfo(pkg, 0)
            val label = packageManager.getApplicationLabel(info).toString()
            val icon = runCatching {
                val drawable = packageManager.getApplicationIcon(info)
                val bitmap = Bitmap.createBitmap(72, 72, Bitmap.Config.ARGB_8888)
                drawable.setBounds(0, 0, bitmap.width, bitmap.height)
                drawable.draw(Canvas(bitmap))
                bitmap.asImageBitmap()
            }.getOrNull()
            PenApp(pkg, label, icon, launchable)
        } catch (_: PackageManager.NameNotFoundException) {
            PenApp(pkg, pkg, null, false)
        } catch (_: RuntimeException) {
            PenApp(pkg, pkg, null, false)
        }
    }

    private fun openUrl(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { message = "没有可用的浏览器来打开项目页面。" }
    }

    private fun ownershipLabel(profile: PenConfig, button: String) = if (profile.owns(button)) "接管" else "原生"

    private fun replaceSettings(target: PenConfig, source: PenConfig) {
        for (button in listOf(PenConfig.B1, PenConfig.B2)) {
            target.setTakeover(button, source.takeover(button))
            for (slot in PenConfig.SLOTS) target.setAction(button, slot, source.action(button, slot))
        }
        target.longPressMs = source.longPressMs
        target.doubleWindowMs = source.doubleWindowMs
    }

    private data class PenApp(val pkg: String, val label: String, val icon: ImageBitmap?, val launchable: Boolean)

    companion object {
        private val persistence = Executors.newSingleThreadExecutor()
    }
}
