# 焦点笔 Plus

为小米灵感触控笔第二代提供笔键映射、应用独立配置和系统适配的 LSPosed 模块。界面统一使用 Miuix。

**2.1.0** · Android 包名：`com.inkdye.xiaomipen` · versionCode：`22`

## 声明

澎湃 OS 与 ColorOS 均测试通过；**仅支持小米灵感触控笔第二代**。不同设备、系统版本和第三方 ROM 的兼容性可能不同。

本项目为社区独立维护，与小米及其关联公司无隶属、授权或背书关系。相关名称及商标归其权利人所有，仅用于说明兼容性。项目不提供小米 ROM 提取的闭源组件；ColorOS 系统类在设备运行时通过反射调用。

**应用包名提示：**原仓库版本使用 `xiaomismartpen.plus`，2.1.0 使用 `com.inkdye.xiaomipen`。Android 将其视为不同应用，无法覆盖安装或自动迁移旧包中的应用数据；升级前请保留旧版本及配置。

## 功能

- 默认笔键配置和按应用独立配置，支持单击、长按、双击及时间阈值。
- 支持音量、媒体、系统导航、翻页、滑动、区域截图、透传和启动应用等动作。
- 自动识别 HyperOS / MIUI、ColorOS / OPlus，并应用对应的按键和系统桥接策略。
- 所有页面使用 Miuix。ColorOS 设备可从默认配置页进入手写笔系统设置；其他系统隐藏此入口。
- 配置保存在设备本地，不含遥测、广告或云同步。

## 安装

在已安装 LSPosed 的 Root 设备上安装 APK，在 LSPosed 启用模块并勾选“系统框架”（`android` / `system_server`），然后重启。ColorOS 的手写笔系统集成还需勾选 `com.oplus.ipemanager` 并授予应用所需 Root 权限。

## 构建与验证

需要 JDK 17 或更高、Android SDK Platform 37.0 和 Build Tools 37.0.0。首次构建需联网下载 Gradle 与依赖。

```bash
python backend-tests/run_tests.py
bash gradlew :app:assembleDebug :app:lintDebug --console=plain
```

Windows PowerShell：

```powershell
python backend-tests/run_tests.py
.\gradlew.bat :app:assembleDebug :app:lintDebug --console=plain
```

正式版需配置 `SMARTPEN_KEYSTORE`、`SMARTPEN_STORE_PASSWORD`、`SMARTPEN_KEY_ALIAS` 和 `SMARTPEN_KEY_PASSWORD`。不要将密钥库或密码提交到仓库。未提供发行签名时，调试签名 APK 不能覆盖由其他签名安装的 APK。

JVM 回归覆盖配置存储、按键控制、ROM 识别、动作路由和系统桥接，无需设备。HyperOS 与 ColorOS 的具体版本和实机验证范围以发布说明为准。

## 许可

项目源码采用 Apache-2.0 许可，详见 [LICENSE](LICENSE) 和 [NOTICE](NOTICE)。第三方组件的许可和来源随应用保留在 `app/src/main/assets/third_party/`。
