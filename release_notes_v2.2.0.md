# PromptNote v2.2.0 - Material 3 Expressive 深度视觉重构与 Awesome Design 赋能版

🎉 本版本正式引入 Google 官方最新的 **Material 3 Expressive (M3 Expressive)** 设计规范体系与 **Awesome Design Skills** 开源设计资产库，对 PromptNote 全局 UI 进行系统性重构与美化！

---

### 🎨 1. Awesome Design Skills & M3 Expressive 开源规范落地
- **技能与设计规范本地集成**：
  - 成功将开源项目 `m3-expressive-design-skill` 与 `awesome-design-skills` 深度引入项目根目录 `.agents/skills/`，全面建立遵循 M3 官方数值基准的规范契约（`AGENTS.md`）。
  - 全套遵循 10 阶圆角规范、光学圆角比例法则（`inner = outer - padding`）与 Expressive 色彩/动效基准。

---

### 💎 2. 主页与卡片美学重构（M3 Expressive Containment & Hero FAB）
- **M3 Expressive Hero 动作按钮（Hero FAB）**：
  - 告别单调的正圆浮动按钮，升级为 Material 3 Expressive 标志性的 **20dp 饱满圆角方（Squircle / `CornerLargeIncreased`）**。
  - 表面映射为高对比度 `colorPrimaryContainer` 配 `colorOnPrimaryContainer`，结合 `4dp` 多层柔和阴影，形成界面视觉焦点的「Hero Moment」。
- **全新 M3 抽屉式新建便签选择器（Card-based Type Chooser）**：
  - 彻底摒弃传统系统单调的列表对话框，采用 28dp 顶圆角底抽屉，内置两张高质感 M3 Expressive 大卡片：
    - **富文本便签**：搭载专属排版图标与功能引导，点按附带弹簧下压反馈。
    - **HTML 便签**：搭载代码形态图标与语法高亮特性简介。
- **便签卡片容器升级（Containment）**：
  - 卡片圆角统一升级为 M3 Expressive 标志性 **20dp (`CornerLargeIncreased`)**，表面采用 `colorSurfaceContainerLow` 与 0.8dp 微描边，悬浮层级提升至 1dp。
  - **光学圆角法则（Optical Roundness）**：内部徽章尺寸从 8dp 智能微调为 **6dp**（`20dp 容器 - 14dp 内边距 = 6dp`），内外弧度视觉完美同心和谐。
  - **色彩分级强化**：置顶徽章升级为 `colorTertiaryContainer` / `colorOnTertiaryContainer` 专属警醒色；便签类型徽章升级为 `colorSecondaryContainer` / `colorOnSecondaryContainer`。

---

### 🌊 3. 官方 Expressive 弹性动效引擎（Spring Physics）
- **M3 Expressive Fast Spatial 弹簧曲线**：
  - 注入官方标准的 `cubic-bezier(0.42, 1.67, 0.21, 0.90)`（阻尼 0.6，刚度 800），按键与卡片抬起时带有 1.67x 自然过冲回弹，手感活泼灵动。
- **设置页卡片容器同步**：
  - 设置界面所有分区卡片升级为 20dp 柔和圆角与低表面容器色，与全局 M3 Expressive 保持高度一致。
