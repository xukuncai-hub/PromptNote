# PromptNote

一款安卓便签应用：既能像普通便签一样记录文字，也能直接保存并预览 AI 生成的 HTML 代码。

## 下载

从 [Releases](https://github.com/xukuncai-hub/PromptNote/releases) 下载最新 APK（Android 8.0+）。

## 功能

**便签管理**
- 双列网格 / 单列列表，一键切换并记住偏好
- 彩色卡片（OPPO 便签风格）、便签置顶、全文搜索
- 支持导入本地 HTML 文件

**两种便签类型**
- 文字便签：所见即所得富文本编辑，支持加粗 / 斜体 / 下划线 / 标题 / 列表 / 对齐 / 文字颜色等
- HTML 便签：粘贴 AI 生成的代码，WebView 直接渲染预览（支持 JS、缩放、https 混合内容），可复制代码 / 分享

**其他**
- 深色模式：跟随系统，或在设置中手动指定
- 设置页：主题、默认视图、清空数据、版本信息
- 数据保存在应用私有目录（JSON 文件），无需联网、无账号

## 界面

主界面为彩色便签卡片流，支持双列网格 / 单列列表切换。

编辑器为极简风格，顶栏常驻返回、撤销重做、预览与完成。

底部悬浮工具栏**跟随键盘收放**：键盘收起时收成一个「编辑」胶囊，几乎不压正文、方便阅读；
键盘弹出时展开成完整工具条，停在键盘正上方。点胶囊即可开始编辑，按返回先收键盘、再按才退出。

展开后的工具条随便签类型切换：

- 文字便签：一级只放字符样式与两个面板入口，其余功能收进「段落」和「格式」两个互斥面板，
  常规屏宽下无需横向滚动；面板展开时正文自动避让。
- HTML 便签：缩进、格式化、清空与代码片段插入；撤销重做统一在顶栏，不在底部重复。

预览页支持刷新、复制代码、系统分享。

## 构建

环境要求：JDK 17、Android SDK（Platform 35 + Build-Tools 35.0.0）

```bash
# 调试包
gradlew.bat assembleDebug          # Windows
./gradlew assembleDebug            # macOS / Linux

# 发布包
gradlew.bat assembleRelease
```

调试产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

release 开启了 R8 代码混淆与资源压缩（约 1.5 MB），混淆规则见 `app/proguard-rules.pro`。
产物为 `app/build/outputs/apk/release/app-release.apk`。

未配置正式签名时，release 会退回 debug 签名，产出的包可直接安装调试。
**正式分发前请换成自己的 keystore** —— debug key 是公开的，任何人都能伪造更新包：

```bash
keytool -genkeypair -v -keystore promptnote.jks -keyalg RSA \
  -keysize 2048 -validity 10000 -alias promptnote
```

生成后在 `local.properties` 中补上这四项即可自动启用（该文件不进版本库，无需改动构建脚本）：

```properties
RELEASE_STORE_FILE=../promptnote.jks
RELEASE_STORE_PASSWORD=...
RELEASE_KEY_ALIAS=promptnote
RELEASE_KEY_PASSWORD=...
```

> 国内网络建议在 `settings.gradle.kts` 中配置阿里云 Maven 镜像加速依赖下载。

## 技术栈

- Kotlin + ViewBinding，Material 3（DayNight）
- WebView：HTML 渲染 + `contentEditable` 富文本编辑
- RecyclerView + GridLayoutManager（视图切换）
- 文件存储：应用私有目录 JSON
- minSdk 26 / targetSdk 35

## 目录结构

```
app/src/main/java/com/htmlnotes/app/
├── MainActivity.kt      首页：卡片列表、搜索、置顶、视图切换
├── EditorActivity.kt    编辑器：文字富文本 / HTML 源码
├── PreviewActivity.kt   HTML 预览：渲染、刷新、复制、分享
├── SettingsActivity.kt  设置：主题、默认视图、清空数据
├── ProjectStore.kt      存储：JSON 文件读写、排序、置顶
├── Project.kt           数据模型（文字 / HTML 两种类型）
├── ProjectAdapter.kt    便签卡片适配器（DiffUtil 增量更新）
├── CodeEditText.kt      代码编辑器控件：行号栏、自动缩进、撤销重做
├── NoteHtml.kt          富文本页面的 HTML / CSS 模板
├── HtmlText.kt          HTML 与纯文本处理工具（预编译正则）
├── CursorKeeper.kt      键盘收起时清除输入焦点
├── ThemeHelper.kt       8 套主题色的运行时切换
├── ViewAnimExt.kt       按钮按压缩放触感
└── App.kt               启动时应用主题设置
```
