package xiaomismartpen.plus

import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.inkdye.xiaomipen.BuildConfig
import com.inkdye.xiaomipen.R
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.icon.extended.GridView
import top.yukonga.miuix.kmp.icon.extended.Home
import top.yukonga.miuix.kmp.icon.extended.Info
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.theme.darkColorScheme
import top.yukonga.miuix.kmp.theme.lightColorScheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.roundToInt

class ConfigActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val dark = isSystemInDarkTheme()
            SideEffect {
                enableEdgeToEdge(
                    statusBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                    navigationBarStyle = SystemBarStyle.auto(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT) { dark },
                )
            }
            MiuixTheme(colors = if (dark) darkColorScheme() else lightColorScheme()) {
                Scaffold { PenApp() }
            }
        }
    }
}

private data class AppEntry(val pkg: String, val label: String, val icon: Bitmap?)
private data class Confirmation(val title: String, val body: String, val apply: () -> Unit)
// 读写使用同一跨 Activity 队列，旋转和主题切换不取消已经提交的保存。
private val persistence = Executors.newSingleThreadExecutor()
private val persistenceCallbacks = Handler(Looper.getMainLooper())

@Composable
private fun PenApp() {
    val context = LocalContext.current
    val resources = LocalResources.current
    val rom = remember { RomProfile.detect() }
    val scope = rememberCoroutineScope()
    val attached = remember { AtomicBoolean(true) }
    DisposableEffect(Unit) { onDispose { attached.set(false) } }
    var config by remember { mutableStateOf<PenConfig?>(null) }
    var apps by remember { mutableStateOf<List<AppEntry>>(emptyList()) }
    var extraApps by remember { mutableStateOf<Map<String, AppEntry>>(emptyMap()) }
    var appsLoaded by remember { mutableStateOf(false) }
    var appLoadError by remember { mutableStateOf(false) }
    var appLoadGeneration by remember { mutableIntStateOf(0) }
    var state by remember { mutableStateOf(HardwareState.read(context)) }
    var moduleActive by remember { mutableStateOf(false) }
    var ipeActive by remember { mutableStateOf(false) }
    var saveStatus by remember { mutableStateOf(resources.getString(R.string.loading)) }
    var saveGeneration by remember { mutableLongStateOf(0) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var profilePkg by rememberSaveable { mutableStateOf<String?>(null) }
    var devicePage by rememberSaveable { mutableStateOf(false) }
    var picker by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerProfile by rememberSaveable { mutableStateOf<String?>(null) }
    var pickerButton by rememberSaveable { mutableStateOf(PenConfig.B1) }
    var pickerSlot by rememberSaveable { mutableStateOf(PenConfig.SHORT) }
    var pickerQuery by rememberSaveable { mutableStateOf("") }
    var appQuery by rememberSaveable { mutableStateOf("") }
    var confirmation by remember { mutableStateOf<Confirmation?>(null) }
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val catalog = remember(apps, extraApps) { extraApps + apps.associateBy { it.pkg } }
    fun s(id: Int) = resources.getString(id)

    LaunchedEffect(Unit) {
        try {
            val appContext = context.applicationContext
            config = withContext(Dispatchers.IO) { persistence.submit<PenConfig> { ConfigStore.load(appContext) }.get() }
            saveStatus = s(R.string.saved)
        } catch (failure: CancellationException) { throw failure }
        catch (failure: Exception) {
            saveStatus = s(R.string.failed)
            result = saveStatus to (failure.message ?: failure.toString())
        }
    }
    LaunchedEffect(appLoadGeneration) {
        appsLoaded = false
        appLoadError = false
        try {
            apps = withContext(Dispatchers.IO) {
                val pm = context.packageManager
                val collator = Collator.getInstance(Locale.getDefault())
                pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                    .map { AppEntry(it.activityInfo.packageName, it.loadLabel(pm).toString(),
                        runCatching { it.loadIcon(pm).toBitmap(96, 96) }.getOrNull()) }
                    .distinctBy { it.pkg }.sortedWith { a, b -> collator.compare(a.label, b.label) }
            }
        } catch (failure: CancellationException) { throw failure }
        catch (_: Exception) { appLoadError = true }
        finally { appsLoaded = true }
    }
    val referencedPackages = config?.let { cfg ->
        buildSet {
            addAll(cfg.appPackages())
            for (profile in listOf(cfg) + cfg.appPackages().mapNotNull { cfg.appConfig(it) }) {
                for (button in listOf(PenConfig.B1, PenConfig.B2)) for (slot in PenConfig.SLOTS) {
                    val action = profile.action(button, slot)
                    if (action.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)) add(action.removePrefix(PenConfig.ACTION_LAUNCH_PREFIX))
                }
            }
        }
    } ?: emptySet()
    LaunchedEffect(config?.appPackages(), profilePkg) {
        if (config != null && profilePkg != null && config?.appConfig(profilePkg) == null) profilePkg = null
    }
    LaunchedEffect(referencedPackages, apps) {
        extraApps = withContext(Dispatchers.IO) {
            referencedPackages.filter { pkg -> apps.none { it.pkg == pkg } }.associateWith { pkg ->
                runCatching {
                    val pm = context.packageManager
                    val info = pm.getApplicationInfo(pkg, 0)
                    AppEntry(pkg, pm.getApplicationLabel(info).toString(),
                        runCatching { pm.getApplicationIcon(info).toBitmap(96, 96) }.getOrNull())
                }.getOrElse { AppEntry(pkg, pkg, null) }
            }
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            state = HardwareState.read(context)
            fun live(key: String): Boolean {
                val at = Settings.Global.getLong(context.contentResolver, key, 0)
                val age = SystemClock.elapsedRealtime() - at
                return at > 0 && age in 0..45000
            }
            moduleActive = live("focuspen_module_heartbeat")
            ipeActive = live("focuspen_ipe_heartbeat")
            delay(1500)
        }
    }

    fun save(next: PenConfig) {
        if (config == null) return
        config = next
        val generation = ++saveGeneration
        saveStatus = s(R.string.saving)
        val appContext = context.applicationContext
        persistence.execute {
            val failure = runCatching { ConfigStore.save(appContext, next) }.exceptionOrNull()
            persistenceCallbacks.post {
                if (attached.get() && generation == saveGeneration) {
                    saveStatus = appContext.getString(if (failure == null) R.string.saved else R.string.failed)
                    if (failure != null) result = saveStatus to (failure.message ?: failure.toString())
                }
            }
        }
    }
    fun edit(pkg: String?, change: (PenConfig) -> Unit) {
        val next = config?.copy() ?: return
        val profile = if (pkg == null) next else next.appConfig(pkg) ?: return
        change(profile)
        save(next)
    }
    fun chooseApp(kind: String, pkg: String? = null, button: String = PenConfig.B1, slot: String = PenConfig.SHORT) {
        pickerProfile = pkg
        pickerButton = button
        pickerSlot = slot
        pickerQuery = ""
        picker = kind
    }
    fun runTask(action: () -> String, title: String) {
        if (busy) return
        busy = true
        scope.launch {
            try { result = title to withContext(Dispatchers.IO) { action() } }
            catch (failure: CancellationException) { throw failure }
            catch (failure: Exception) { result = s(R.string.failed) to (failure.message ?: failure.toString()) }
            finally { busy = false }
        }
    }
    fun openUrl(url: String) {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
            .onFailure { result = s(R.string.failed) to s(R.string.browser_error) }
    }
    fun goBack() {
        when {
            picker != null -> picker = null
            devicePage -> devicePage = false
            tab == 1 && profilePkg != null -> profilePkg = null
            tab != 0 -> tab = 0
        }
    }
    BackHandler(picker != null || devicePage || (tab == 1 && profilePkg != null) || tab != 0) { goBack() }
    val cfg = config
    // 恢复的导航状态不能在其他 ROM 重新打开 ColorOS 专属页面。
    val systemPage = devicePage && rom == RomProfile.Family.COLOROS
    LaunchedEffect(rom) { if (rom != RomProfile.Family.COLOROS) devicePage = false }
    val selected = profilePkg.takeIf { tab == 1 && !systemPage }
    val current = if (selected == null) cfg else cfg?.appConfig(selected)
    val pageKey = if (systemPage) "device" else if (selected != null) "profile:$selected" else "tab:$tab"

    Box(Modifier.fillMaxSize()) {
            PenPage(tab, systemPage, selected != null, pageKey, onTab = { tab = it; devicePage = false }, onBack = ::goBack) {
                if (cfg == null) item { InfoCard(saveStatus, s(R.string.loading_info)) }
                else if (systemPage) {
                    item { ActionButton(s(R.string.back_default), onClick = { devicePage = false }) }
                    item { InfoCard(s(R.string.device_settings), s(if (rom == RomProfile.Family.COLOROS) R.string.integration_info else R.string.native_system_info), saveStatus) }
                    item { DeviceSettings(cfg, rom, state, moduleActive, ipeActive, busy,
                        onEdit = { change -> edit(null, change) },
                        onInstall = { runTask({ RootTools.install(context) }, s(R.string.setup_success)) },
                        onLsposed = { runTask({ RootTools.openLsposed(); s(lsposedInfoResource(rom)) }, s(R.string.open_lsposed)) },
                        onSystemPage = {
                            runCatching { SystemSettings.open(context) }
                                .onFailure { result = s(R.string.failed) to (it.message ?: it.toString()) }
                        }) }
                } else when (tab) {
                    0 -> {
                        item { InfoCard(s(R.string.tab_default), s(R.string.default_info), saveStatus) }
                        item { ProfileEditor(cfg, false, catalog, { change -> edit(null, change) },
                            { button, slot -> chooseApp("launch", null, button, slot) }) }
                        item { ActionButton(s(R.string.reset_default), onClick = {
                            confirmation = Confirmation(s(R.string.reset_default), s(R.string.reset_confirm)) {
                                edit(null) { replaceSettings(it, PenConfig.defaults()) }
                            }
                        }) }
                        item { GestureGuide() }
                        if (rom == RomProfile.Family.COLOROS) item { PenCard {
                            Preference(s(R.string.device_settings), s(R.string.device_settings_info), arrow = true,
                                onClick = { devicePage = true })
                        } }
                    }
                    1 -> if (selected != null && current != null) {
                        item { ActionButton(s(R.string.back_apps), onClick = { profilePkg = null }) }
                        item { InfoCard(catalog[selected]?.label ?: selected, selected + "\n" + s(R.string.app_info), saveStatus) }
                        item { ProfileEditor(current, true, catalog, { change -> edit(selected, change) },
                            { button, slot -> chooseApp("launch", selected, button, slot) }) }
                        item {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                ActionButton(s(R.string.copy_default), onClick = {
                                    confirmation = Confirmation(s(R.string.copy_default), s(R.string.copy_confirm)) {
                                        val latest = config
                                        if (latest != null) edit(selected) { replaceSettings(it, latest) }
                                    }
                                })
                                ActionButton(s(R.string.delete_profile), onClick = {
                                    confirmation = Confirmation(s(R.string.delete_profile), s(R.string.delete_confirm)) {
                                        config?.copy()?.let { next -> next.removeAppConfig(selected); save(next) }
                                        profilePkg = null
                                    }
                                })
                            }
                        }
                        item { GestureGuide() }
                    } else {
                        item { InfoCard(s(R.string.apps_title), s(R.string.apps_info), resources.getString(R.string.profile_count, cfg.appPackages().size)) }
                        item { ActionButton(s(R.string.add_app), primary = true, onClick = { chooseApp("add") }) }
                        item { AppSearch(appQuery, { appQuery = it }, s(R.string.search_profiles)) }
                        val filtered = cfg.appPackages().map { catalog[it] ?: AppEntry(it, it, null) }
                            .filter { matches(it, appQuery) }.sortedBy { it.label }
                        if (filtered.isEmpty()) item { InfoCard(s(if (cfg.appPackages().isEmpty()) R.string.no_profiles else R.string.no_matches),
                            s(if (cfg.appPackages().isEmpty()) R.string.no_profiles_info else R.string.no_matches_info)) }
                        items(filtered, key = { it.pkg }) { app ->
                            PenCard {
                                val profile = cfg.appConfig(app.pkg)
                                AppRow(app, profile?.let { resources.getString(R.string.profile_summary, ownershipLabel(it, PenConfig.B1, context),
                                    ownershipLabel(it, PenConfig.B2, context)) }, onClick = { profilePkg = app.pkg })
                            }
                        }
                    }
                    else -> {
                        item { InfoCard(s(R.string.app_name), s(R.string.about_info), resources.getString(R.string.version, BuildConfig.VERSION_NAME)) }
                        item { TextCard(s(R.string.usage_title), listOf(s(R.string.usage_enable), s(R.string.usage_profiles), s(R.string.usage_native))) }
                        item { TextCard(s(R.string.declaration_title), listOf(s(R.string.declaration_tested), s(R.string.declaration_community))) }
                        item { PenCard {
                            Preference(s(R.string.source_project), "BaerYiTo/XiaomiSmartPenPlus · Apache-2.0", arrow = true,
                                onClick = { openUrl("https://github.com/BaerYiTo/XiaomiSmartPenPlus") })
                            Preference(s(R.string.ui_project), "compose-miuix-ui/miuix · Apache-2.0", arrow = true,
                                onClick = { openUrl("https://github.com/compose-miuix-ui/miuix") })
                        } }
                        item { PenCard {
                            Preference(s(R.string.licenses), s(R.string.license_info), arrow = true, enabled = !busy, onClick = {
                                runTask({ listOf("Project-NOTICE.txt", "NOTICE.txt", "Miuix-LICENSE.txt", "Apache-2.0.txt", "Project-LICENSE.txt")
                                    .joinToString("\n\n") { file -> file + "\n\n" + context.assets.open("third_party/$file").bufferedReader().use { it.readText() } }
                                }, s(R.string.licenses))
                            })
                            Preference(s(R.string.diagnostics), arrow = true, enabled = !busy,
                                onClick = { runTask(RootTools::diagnostics, s(R.string.diagnostics)) })
                        } }
                    }
                }
                item { Spacer(Modifier.height(10.dp)) }
            }
        if (picker != null) AppPicker(
            title = s(if (picker == "add") R.string.choose_app else R.string.choose_launch_app),
            description = s(if (picker == "add") R.string.choose_profile_info else R.string.choose_launch_info),
            apps = apps, configured = if (picker == "add") cfg?.appPackages()?.toSet().orEmpty() else emptySet(),
            loaded = appsLoaded, error = appLoadError, enabled = cfg != null, query = pickerQuery, onQuery = { pickerQuery = it },
            onDismiss = { picker = null }, onRetry = { appLoadGeneration++ }, onSelect = { app ->
                if (config == null) return@AppPicker
                if (picker == "add") {
                    config?.copy()?.let { next ->
                        if (next.appConfig(app.pkg) == null) { next.ensureAppConfig(app.pkg); save(next) }
                        profilePkg = app.pkg
                        tab = 1
                    }
                } else edit(pickerProfile) { it.setAction(pickerButton, pickerSlot, PenConfig.ACTION_LAUNCH_PREFIX + app.pkg) }
                picker = null
            })
    }
    OverlayDialog(show = confirmation != null, onDismissRequest = { confirmation = null },
        title = confirmation?.title, summary = confirmation?.body) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(s(R.string.cancel), onClick = { confirmation = null }, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            TextButton(s(R.string.confirm), onClick = { confirmation?.apply?.invoke(); confirmation = null },
                modifier = Modifier.weight(1f).heightIn(min = 48.dp), colors = ButtonDefaults.textButtonColorsPrimary())
        }
    }
    OverlayDialog(show = result != null, onDismissRequest = { result = null }, title = result?.first) {
        val bodyScroll = rememberScrollState()
        LaunchedEffect(result) { bodyScroll.scrollTo(0) }
        Text(text = result?.second.orEmpty(), fontSize = 14.sp,
            modifier = Modifier.fillMaxWidth()
                .heightIn(max = (LocalConfiguration.current.screenHeightDp * .38f).coerceAtLeast(80f).dp)
                .verticalScroll(bodyScroll))
        Spacer(Modifier.height(12.dp))
        TextButton(s(R.string.confirm), onClick = { result = null }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
            colors = ButtonDefaults.textButtonColorsPrimary())
    }
}

@Composable
private fun PenPage(tab: Int, device: Boolean, appProfile: Boolean, pageKey: String, onTab: (Int) -> Unit,
                    onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val pages = rememberSaveableStateHolder()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val expandedRail = maxWidth >= 1000.dp
        Row(Modifier.fillMaxSize()) {
            NavigationRail(expanded = expandedRail, expandedWidth = 216.dp) {
                val icons = listOf(MiuixIcons.Home, MiuixIcons.GridView, MiuixIcons.Info)
                listOf(R.string.tab_default, R.string.tab_apps, R.string.tab_about).forEachIndexed { index, id ->
                    NavigationRailItem(selected = tab == index, onClick = { onTab(index) },
                        icon = icons[index], label = stringResource(id), modifier = Modifier.heightIn(min = 56.dp))
                }
            }
            Box(Modifier.weight(1f).fillMaxHeight()) {
                pages.SaveableStateProvider(pageKey) {
                    PageContent(device, appProfile, onBack, content)
                }
            }
        }
    }
}

@Composable
private fun PageContent(device: Boolean, appProfile: Boolean, onBack: () -> Unit, content: LazyListScope.() -> Unit) {
    val list = rememberLazyListState()
    val scrollBehavior = MiuixScrollBehavior(state = rememberTopAppBarState(),
        canScroll = { list.canScrollForward || list.canScrollBackward })
    val density = LocalDensity.current
    val entrance = remember { Animatable(0f) }
    val entranceOffset = with(density) { 10.dp.toPx() }
    // 页面卸载即取消旧动效；Compose 的 MotionDurationScale 同时控制缩短或关闭动画。
    LaunchedEffect(entrance) { entrance.animateTo(1f, tween(220, easing = FastOutSlowInEasing)) }
    Scaffold(popupHost = {}, topBar = {
        TopAppBar(
            title = stringResource(if (device) R.string.device_settings else R.string.app_name),
            subtitle = stringResource(if (device) R.string.device_subtitle else if (appProfile) R.string.profile_subtitle else R.string.main_subtitle),
            scrollBehavior = scrollBehavior,
            navigationIcon = {
                if (device || appProfile) IconButton(onClick = onBack, modifier = Modifier.size(48.dp)) {
                    Icon(MiuixIcons.Back, stringResource(R.string.back))
                }
            },
        )
    }) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding), contentAlignment = Alignment.TopCenter) {
            LazyColumn(Modifier.widthIn(max = 1040.dp).fillMaxSize()
                .graphicsLayer { alpha = entrance.value; translationY = entranceOffset * (1f - entrance.value) }
                .nestedScroll(scrollBehavior.nestedScrollConnection), state = list,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
        }
    }
}

@Composable
private fun ProfileEditor(config: PenConfig, appProfile: Boolean, apps: Map<String, AppEntry>,
                          onEdit: ((PenConfig) -> Unit) -> Unit, onLaunch: (String, String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            if (maxWidth >= 800.dp) Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                for (button in listOf(PenConfig.B1, PenConfig.B2)) ButtonCard(Modifier.weight(1f), config, button, apps, onEdit, onLaunch)
            } else Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                for (button in listOf(PenConfig.B1, PenConfig.B2)) ButtonCard(Modifier, config, button, apps, onEdit, onLaunch)
            }
        }
        PenCard {
            Column(Modifier.padding(18.dp)) {
                SectionTitle(stringResource(R.string.timing))
                Spacer(Modifier.height(6.dp))
                SecondaryText(stringResource(if (appProfile) R.string.timing_profile else R.string.timing_default))
                Spacer(Modifier.height(20.dp))
                TimingRow(stringResource(R.string.long_threshold), config.longPressMs.toInt(), 200..1500, stringResource(R.string.long_hint)) {
                    onEdit { profile -> profile.longPressMs = it.toLong() }
                }
                Spacer(Modifier.height(20.dp))
                TimingRow(stringResource(R.string.double_window), config.doubleWindowMs.toInt(), 150..800, stringResource(R.string.double_hint)) {
                    onEdit { profile -> profile.doubleWindowMs = it.toLong() }
                }
            }
        }
    }
}

@Composable
private fun ButtonCard(modifier: Modifier, config: PenConfig, button: String, apps: Map<String, AppEntry>,
                       onEdit: ((PenConfig) -> Unit) -> Unit, onLaunch: (String, String) -> Unit) {
    PenCard(modifier) {
        ToggleRow(stringResource(if (button == PenConfig.B1) R.string.key_lower else R.string.key_upper), config.takeover(button),
            stringResource(if (config.takeover(button)) R.string.takeover_on else R.string.takeover_off)) {
            onEdit { profile -> profile.setTakeover(button, it) }
        }
        for ((slot, id) in listOf(PenConfig.SHORT to R.string.gesture_short, PenConfig.LONG to R.string.gesture_long, PenConfig.DOUBLE to R.string.gesture_double)) {
            val action = config.action(button, slot)
            ActionPreference(stringResource(id), action, apps, onSelect = { chosen ->
                if (chosen == "launch") onLaunch(button, slot) else onEdit { it.setAction(button, slot, chosen) }
            })
            if (action.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)) Preference(stringResource(R.string.change_target),
                action.removePrefix(PenConfig.ACTION_LAUNCH_PREFIX), arrow = true, onClick = { onLaunch(button, slot) })
        }
    }
}

@Composable
private fun ActionPreference(title: String, action: String, apps: Map<String, AppEntry>, onSelect: (String) -> Unit) {
    val options = PenConfig.ACTIONS.toList() + "launch"
    val launching = action.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)
    val labels = options.map { actionLabel(if (it == "launch" && launching) action else it, apps) }
    OverlayDropdownPreference(items = labels, selectedIndex = if (launching) options.lastIndex else options.indexOf(action).coerceAtLeast(0),
        title = title, summary = actionSummary(action), maxHeight = 420.dp,
        onSelectedIndexChange = { index -> options.getOrNull(index)?.let(onSelect) })
}

@Composable
private fun PenCard(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier.fillMaxWidth(), insideMargin = PaddingValues(0.dp),
        colors = CardDefaults.defaultColors(color = tint ?: MiuixTheme.colorScheme.surfaceContainer)) {
        Column(content = content)
    }
}

@Composable
private fun InfoCard(title: String, detail: String, status: String? = null) {
    val colors = MiuixTheme.colorScheme
    PenCard(tint = colors.primary.copy(alpha = .09f)) {
        Column(Modifier.padding(20.dp)) {
            Text(text = title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurface)
            Spacer(Modifier.height(8.dp))
            SecondaryText(detail)
            if (status != null) {
                Spacer(Modifier.height(12.dp))
                Text(text = status, fontSize = 13.sp, color = colors.primary,
                    modifier = Modifier.background(colors.primary.copy(alpha = .10f), RoundedCornerShape(8.dp)).padding(horizontal = 9.dp, vertical = 5.dp))
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(text = title, modifier = Modifier.semantics { heading() }, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun SecondaryText(text: String) {
    Text(text = text, fontSize = 13.sp, lineHeight = 20.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
}

@Composable
private fun TextCard(title: String, paragraphs: List<String>) {
    PenCard {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionTitle(title)
            paragraphs.forEach { SecondaryText(it) }
        }
    }
}

@Composable
private fun GestureGuide() = TextCard(stringResource(R.string.gesture_guide),
    listOf(stringResource(R.string.gesture_guide_actions), stringResource(R.string.gesture_guide_native)))

@Composable
private fun ActionButton(title: String, primary: Boolean = false, onClick: () -> Unit) {
    TextButton(title, onClick = onClick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        colors = if (primary) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors())
}

@Composable
private fun Preference(title: String, summary: String? = null, value: String? = null, enabled: Boolean = true,
                       arrow: Boolean = false, modifier: Modifier = Modifier, onClick: (() -> Unit)? = null) {
    val end: @Composable RowScope.() -> Unit = {
        if (value != null) Text(value, modifier = Modifier.widthIn(max = 180.dp), fontSize = 14.sp,
            color = MiuixTheme.colorScheme.onSurfaceVariantSummary, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
    if (arrow) ArrowPreference(title = title, summary = summary, modifier = modifier, enabled = enabled, onClick = onClick, endActions = end)
    else BasicComponent(title = title, summary = summary, modifier = modifier, enabled = enabled, onClick = onClick, endActions = end)
}

@Composable
private fun ToggleRow(title: String, checked: Boolean, summary: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    SwitchPreference(checked = checked, onCheckedChange = onChange, title = title, summary = summary, enabled = enabled)
}

@Composable
private fun TimingRow(title: String, value: Int, range: IntRange, hint: String, onSave: (Int) -> Unit) {
    val colors = MiuixTheme.colorScheme
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(text = title, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        Text(text = stringResource(R.string.ms, value), fontSize = 16.sp, fontWeight = FontWeight.Medium, color = colors.primary)
    }
    Spacer(Modifier.height(6.dp))
    SecondaryText(hint)
    Slider(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = title }, value = value.toFloat(),
        onValueChange = { raw ->
            val next = ((raw / 10f).roundToInt() * 10).coerceIn(range)
            if (next != value) onSave(next)
        }, valueRange = range.first.toFloat()..range.last.toFloat(), steps = (range.last - range.first) / 10 - 1)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        SecondaryText(stringResource(R.string.ms, range.first))
        SecondaryText(stringResource(R.string.ms, range.last))
    }
}

@Composable
private fun AppSearch(query: String, onQuery: (String) -> Unit, placeholder: String) {
    TextField(value = query, onValueChange = onQuery, label = placeholder, singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun AppRow(app: AppEntry, extra: String? = null, enabled: Boolean = true, onClick: () -> Unit) {
    ArrowPreference(title = app.label, summary = app.pkg + if (extra == null) "" else "\n" + extra, startAction = {
        if (app.icon != null) Image(app.icon.asImageBitmap(), null, Modifier.size(38.dp))
        else Text(text = app.label.take(1), fontSize = 19.sp, color = MiuixTheme.colorScheme.primary,
            modifier = Modifier.size(38.dp).background(MiuixTheme.colorScheme.primary.copy(alpha = .12f), RoundedCornerShape(10.dp)).padding(8.dp))
    }, enabled = enabled, onClick = onClick)
}

@Composable
private fun AppPicker(title: String, description: String, apps: List<AppEntry>, configured: Set<String>, loaded: Boolean, error: Boolean, enabled: Boolean,
                      query: String, onQuery: (String) -> Unit, onDismiss: () -> Unit, onRetry: () -> Unit, onSelect: (AppEntry) -> Unit) {
    val found = apps.filter { matches(it, query) }
    val listHeight = minOf(360.dp, (found.size.coerceAtLeast(1) * 76).dp)
    val availableHeight = (LocalConfiguration.current.screenHeightDp - 300).coerceAtLeast(120).dp
    OverlayDialog(show = true, onDismissRequest = onDismiss, title = title, summary = description) {
        AppSearch(query, onQuery, stringResource(R.string.search_apps))
        Spacer(Modifier.height(12.dp))
        LazyColumn(Modifier.fillMaxWidth().height(minOf(listHeight, availableHeight)), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!loaded) item { SecondaryText(stringResource(R.string.loading_apps)) }
            else if (error) item { SecondaryText(stringResource(R.string.apps_error)) }
            else if (found.isEmpty()) item { SecondaryText(stringResource(if (apps.isEmpty()) R.string.no_launch_apps else R.string.no_matches)) }
            items(found, key = { it.pkg }) { app ->
                AppRow(app, if (app.pkg in configured) stringResource(R.string.configured) else null, enabled) { onSelect(app) }
            }
        }
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(stringResource(R.string.cancel), onClick = onDismiss, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
            if (error) TextButton(stringResource(R.string.retry), onClick = onRetry, modifier = Modifier.weight(1f).heightIn(min = 48.dp))
        }
    }
}

@Composable
private fun DeviceSettings(cfg: PenConfig, rom: RomProfile.Family, state: HardwareState, moduleActive: Boolean, ipeActive: Boolean, busy: Boolean,
                           onEdit: ((PenConfig) -> Unit) -> Unit, onInstall: () -> Unit, onLsposed: () -> Unit, onSystemPage: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PenCard {
            Column(Modifier.padding(18.dp)) { SectionTitle(stringResource(R.string.device_status)) }
            Preference(stringResource(R.string.detected_system), value = stringResource(when (rom) {
                RomProfile.Family.HYPEROS -> R.string.rom_hyperos
                RomProfile.Family.COLOROS -> R.string.rom_coloros
                RomProfile.Family.UNKNOWN -> R.string.rom_unknown
            }))
            if (rom == RomProfile.Family.COLOROS) {
                Preference(stringResource(R.string.connected), value = stringResource(if (state.connected) R.string.connected else R.string.disconnected))
                Preference(stringResource(R.string.battery), value = if (state.battery < 0) stringResource(R.string.unknown) else "${state.battery}%")
                Preference(stringResource(if (!state.valid) R.string.unknown else if (state.docked) R.string.docked else R.string.undocked), summary = state.mac)
            }
        }
        if (rom == RomProfile.Family.COLOROS) PenCard {
            Column(Modifier.padding(18.dp)) { SectionTitle(stringResource(R.string.system_integration)) }
            ToggleRow(stringResource(R.string.system_integration), cfg.systemIntegration, stringResource(R.string.integration_info)) { onEdit { p -> p.systemIntegration = it } }
            ToggleRow(stringResource(R.string.dock_hint), cfg.dockHint, enabled = cfg.systemIntegration) { onEdit { p -> p.dockHint = it } }
            ToggleRow(stringResource(R.string.lift_hint), cfg.liftHint, enabled = cfg.systemIntegration) { onEdit { p -> p.liftHint = it } }
            ToggleRow(stringResource(R.string.wake_lift), cfg.wakeOnLift, stringResource(R.string.wake_info), cfg.systemIntegration) { onEdit { p -> p.wakeOnLift = it } }
            ToggleRow(stringResource(R.string.wake_dock), cfg.wakeOnDock, enabled = cfg.systemIntegration) { onEdit { p -> p.wakeOnDock = it } }
        }
        PenCard {
            Column(Modifier.padding(18.dp)) { SectionTitle(stringResource(R.string.setup)) }
            Preference(stringResource(R.string.module_status), value = stringResource(if (moduleActive) R.string.active else R.string.pending))
            if (rom == RomProfile.Family.COLOROS) {
                Preference(stringResource(R.string.ipe_status), value = stringResource(if (ipeActive) R.string.active else R.string.pending))
                Preference(stringResource(R.string.install_bridge), stringResource(R.string.install_info), enabled = !busy, arrow = true, onClick = onInstall)
            }
            Preference(stringResource(R.string.open_lsposed), stringResource(lsposedInfoResource(rom)), enabled = !busy, arrow = true, onClick = onLsposed)
            Preference(stringResource(R.string.open_system_page), arrow = true, onClick = onSystemPage)
        }
    }
}

private fun lsposedInfoResource(rom: RomProfile.Family): Int = when (rom) {
    RomProfile.Family.HYPEROS -> R.string.lsposed_info_hyperos
    RomProfile.Family.COLOROS -> R.string.lsposed_info
    RomProfile.Family.UNKNOWN -> R.string.lsposed_info_unknown
}

private fun matches(app: AppEntry, query: String): Boolean = query.trim().let { it.isBlank() || app.label.contains(it, true) || app.pkg.contains(it, true) }

private fun ownershipLabel(profile: PenConfig, button: String, context: android.content.Context): String =
    context.getString(if (profile.owns(button)) R.string.owned else R.string.native_action)

private fun replaceSettings(target: PenConfig, source: PenConfig) {
    for (button in listOf(PenConfig.B1, PenConfig.B2)) {
        target.setTakeover(button, source.takeover(button))
        for (slot in PenConfig.SLOTS) target.setAction(button, slot, source.action(button, slot))
    }
    target.longPressMs = source.longPressMs
    target.doubleWindowMs = source.doubleWindowMs
}

@Composable
private fun actionLabel(action: String, apps: Map<String, AppEntry>): String {
    val id = when (action) {
        "none" -> R.string.none; "volume_up" -> R.string.volume_up; "volume_down" -> R.string.volume_down
        "play_pause" -> R.string.play_pause; "back" -> R.string.action_back; "home" -> R.string.home
        "recents" -> R.string.recents; "page_up" -> R.string.page_up; "page_down" -> R.string.page_down
        "swipe_up" -> R.string.swipe_up; "swipe_down" -> R.string.swipe_down
        "swipe_left" -> R.string.swipe_left; "swipe_right" -> R.string.swipe_right
        "screenshot_partial" -> R.string.screenshot_partial; "passthrough" -> R.string.passthrough
        else -> R.string.launch_app
    }
    val label = stringResource(id)
    return if (action.startsWith(PenConfig.ACTION_LAUNCH_PREFIX)) stringResource(R.string.launch_label,
        apps[action.removePrefix(PenConfig.ACTION_LAUNCH_PREFIX)]?.label ?: action.removePrefix(PenConfig.ACTION_LAUNCH_PREFIX)) else label
}

@Composable
private fun actionSummary(action: String): String? = when (action) {
    "none" -> stringResource(R.string.none_info)
    "page_up", "page_down" -> stringResource(R.string.page_info)
    "swipe_up", "swipe_down", "swipe_left", "swipe_right" -> stringResource(R.string.swipe_info)
    "passthrough" -> stringResource(R.string.passthrough_info)
    else -> null
}
