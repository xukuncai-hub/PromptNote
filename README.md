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

主界面为彩色便签卡片流；编辑器为极简风格，底部悬浮工具栏；预览页支持刷新、复制代码、系统分享。

## 构建

环境要求：JDK 17、Android SDK（Platform 35 + Build-Tools 35.0.0）

```bash
# Windows
gradlew.bat assembleDebug

# macOS / Linux
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。

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
└── App.kt               启动时应用主题设置
```
