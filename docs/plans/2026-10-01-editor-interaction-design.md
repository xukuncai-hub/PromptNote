# 编辑器交互体验升级 — 设计

日期：2026-10-01
约束：**零新依赖**。代码编辑器继续用自绘 `CodeEditText`，富文本继续 WebView `contenteditable`。

## 问题

编辑器已经有键盘驱动的工具栏胶囊↔完整态切换、insets 动画期只平移不改 padding、面板兄弟互斥收起。
剩下六个痛点：

1. 选区与工具栏联动 —— 改的那段字被面板挤走/盖住，没有跟随选区的操作入口
2. 面板层级与遮挡 —— 面板一开正文突然变矮，内容跳走
3. 操作反馈不够 —— 插入链接/撤销/复制全是静默的
4. 手势与快捷操作 —— 没有下滑收起，没有 Ctrl+B/Z
5. 代码模式编辑器 —— 没有语法高亮、没有括号匹配
6. 光标与键盘体验 —— 打字打到视野外不会自动跟

1 和 2 是同一个问题：**你要改的那段字不在视野里的安全位置**。整套设计围绕这一个锚点展开。

## 架构：选区是唯一的事实来源

复用现有的 `refreshState()`（已挂在 `selectionchange / keyup / mouseup / input`），
在末尾追加 `HtmlNotes.onSelection(rectJson)`，回传选区相对 `#ed` 的 `getBoundingClientRect`
与 `collapsed` 标志。

- JS 侧缓存上一次 rect，**没变化就不发** —— 避免每次敲键都过一遍桥。
- 单向、只读、无状态，回调入口带 `isFinishing || isDestroyed` 守卫，不 post 延迟任务。
- 原生把 WebView 局部坐标换算成屏幕坐标后，交给三个消费者：锚定器、浮条、光标跟随。

### 锚定状态机（核心）

```
选区中心进入「遮挡带」（可视底边 - panelHeight 往上）+ 状态 armed
  → 平滑滚到可视区上 1/3，然后 disarm
选区中心离开遮挡带 → 重新 arm
```

折叠光标与展开选区共用。**必须有 armed/disarm**，否则连续打字时光标会被不停往上拽。

### 面板开合的内容补偿

结构不动（面板仍住 dock 里，靠 `weight` 压正文）。问题不是"被盖住"而是"内容跳走"：

- 面板开合前记录选区屏幕 Y，布局落定后再测，差值用 `richWeb.scrollBy(0, dy)` 补偿，
  并跟面板动画同时长（280ms）平滑。
- 视觉结果：面板从下面长出来，正在编辑的那段字**原地不动**地往上顶。

## 分节设计

### S1 选区通道与锚定
见上。附带 `panelHeight` 归零即遮挡带消失，因此"点正文收面板 → 再走锚定"的顺序天然正确。

### S2 面板高度与收起
- 面板最大高度 = 正文可视高 × 45%，超出内部滚动。防止正文只剩一条缝。
- 点正文任意处收起面板：走 `refreshState` 挂点加 `HtmlNotes.onEditorTap()`。
- 面板互斥复用已有 `collapseSiblingsOf`。

### S3 选区浮条
选区非折叠时出现：`B I U | 剪切 复制 全选 | 更多`。
"更多"打开格式面板并锚定，浮条与面板串成一条链。

- 位置：选区上方 8dp 居中；不够翻下方；再不够贴顶边。受 dock 顶边与键盘约束。
- 跟随：`richWeb` 滚动也走 `refreshState`，原生只改 `translationY`，不重排。
- 关掉系统菜单：`setCustomSelectionActionModeCallback` / `setCustomInsertionActionModeCallback`
  返回 `false`。关不掉就退化成"两套并存"，不影响主体。
- 双击选词、长按拖选走 WebView 原生，不重做。

### S4 操作反馈
统一的 `EditorToast` pill，贴 dock 上方，220ms 进/出，同时最多一条（新消息替换旧的）。
承载：已撤销 / 已重做 / 已插入链接 / 已复制 / 已复制全部。
**不承载**常规格式切换 —— 那靠按钮 `isSelected` 持久态 + 按压缩放反馈。

### S5 手势与快捷键
- dock 上垂直拖动 > 阈值且速度够 → 跟手 `translationY`，松手判定收起：先收面板，面板已收则收键盘。
- `Ctrl+B / I / U` → `exec`；`Ctrl+Z / Y` → 撤销重做（富文本与代码模式都接）。
  只在 `event.isCtrlPressed` 时拦截，避免吞 IME 按键。

### S6 代码模式
- 轻量 tokenizer：注释 / 字符串 / 标签名 / 属性名 / 属性值 / 数字 / 关键字 → `ForegroundColorSpan`。
  全量重算于 `afterTextChanged`，超过阈值长度降级为不着色，避免大文件卡顿。
- 括号匹配：光标落在 `(){}[]` 相邻时找配对，在 `onDraw` 里用 `getCharacterBounds` 画高亮；
  配对失败标红。
- 缩进、自动缩进、undo/redo、行号、当前行高亮已有，不动。
- **不做**标签自动闭合（中文 IME 下风险高，YAGNI）。

### S7 光标与键盘
- 打字打到正文可视区外 → 滚到上 1/3（复用锚定状态机的同一套节流）。
- 代码模式补"光标距底边过近时滚一点"。

## 错误处理与生命周期

所有新回调遵循本轮已确立的规矩：

- `runOnUiThread` / `@JavascriptInterface` 入口先 `isFinishing || isDestroyed` 判定
- `postDelayed` 的任务必须在 `onDestroy` 撤掉，且**入口侧守卫**（防止销毁后重新挂上）
- 不在 WebView `destroy()` 之后调 `evaluateJavascript`

## 验证

工程无 lint/detekt/ktlint，验证手段是 `assembleDebug` + `assembleRelease`。
手工验收点：面板开合时选区不动、浮条跟随与翻转、Ctrl+B 生效、代码高亮不卡、
键盘弹出时正文不跳。
