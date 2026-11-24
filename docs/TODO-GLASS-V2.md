# TODO-GLASS-V2 自述（v0.5.8 液态玻璃真透镜）

本文档如实记录 v0.5.8 的玻璃捕获机制、缓存策略、各表面的实现档位、以及**做不到的事**。验收可对照 vivo OriginOS 相册底部胶囊逐项核。

---

## 1. 捕获机制

**选型**：`android.graphics.Picture`（显示列表录制）→ 需要时栅格化为 `Bitmap`（ARGB_8888）→ 作为 `android.graphics.BitmapShader` 通过 `RuntimeShader.setInputShader("uBackdrop", ...)` 喂给 AGSL。

为什么不是别的：
- **RenderNode / RenderEffect 全屏方案**：Compose 里没有公开 API 把「任意已上屏像素」读回来。`RenderEffect` 只能作用于自己图层的 content，读不到「身后的兄弟层」。
- **View.draw(Canvas) 全屏截图**：每帧全屏像素拷贝，性能不可接受，也违反「禁止全屏捕获」。
- **Picture 录制**：录制只是追加显示列表（微秒级），栅格化时才碰像素。我们只录制**自己拥有的背景装饰层**，天然满足「只捕获玻璃身后区域」。

**背景层的构成**：`AppBackdrop.record(w, h, alpha)` 画的是 App 的全局背景（炭黑底色 `#171207` + 蜂巢六边形描边 + 少量蜜金点），与屏幕上实际绘制的 `HoneycombBackdrop` 视觉参数一致（同半径公式、同透明度系数），保证透镜里看到的与肉眼在玻璃周围看到的是同一层东西。

## 2. 捕获区域与缓存策略

- **区域**：整页背景 Picture 只录一次（`onGloballyPositioned` 尺寸变化时重录）。栅格化一次得到全页位图——但这是**背景装饰层的位图**（纯色+蜂巢，GPU 上 ~2ms），不是任何内容截屏。透镜 shader 内通过 `uSurfaceTopLeft` 只采样**玻璃表面自身对应的小区域**（胶囊 60×34dp，滑块 ~26dp 圆），边缘 CLAMP 保证永不越界拉黑。
- **缓存**：`BackdropCache` 按尺寸保留最多 4 份位图。栅格化触发条件 = `AppBackdrop.version` 变化（尺寸或内容参数变化），**动画期间零栅格化**——每帧只更新透镜自己的 uniform（`uSurfaceTopLeft` 随指示器位置移动，采样同一张位图的不同区域）。这符合「动效期间只许胶囊级小面积逐帧更新」的要求：逐帧更新的只是一次 `setFloatUniform` + 一次小面积 GPU draw。
- **开关滑块的透镜**（LiquidSwitch，自洽不依赖 AppBackdrop）：滑块内重绘的「轨道放大像」直接取**本帧的轨道绘制状态**（同一条 Canvas 指令流、同一 `previewChecked`/`pos` 状态），缩放 1.4–1.65× 画进滑块圆内，三层带 R/G/B 通道权重与位置偏移的副本叠出 1–2px 色散。这是**逐帧重绘小面积矢量**（56×32dp 的圆内区域），无位图、无 shader 缓存失效，静止时走实心分支零额外开销。

## 3. 表面档位清单（哪些真采样、哪些是材质）

| 表面 | 不要 | 中等 | 最高 |
|---|---|---|---|
| 模式胶囊指示器 | 无动画，Honey 色块 | Honey 色块+tween 滑动 | **真透镜**：采样 AppBackdrop，中心 1.12× 放大、边缘 10px 法线位移带、1.8px RGB 分离、速度拉伸、弹簧回弹 |
| LiquidSwitch 滑块 | 瞬切实心 | 跟手+拉伸，实心 | **拖动/按下时透镜态**：升起至 1.3×，内部实时重绘放大轨道，色散随速度增强；松手回实心 |
| HiveHero 主卡 / ContinueCard / HomeTile / 模式胶囊底 | 扁平 Card | 渐变玻璃材质 | 渐变玻璃材质（**未上真折射**，见 §4） |
| 全局动画时长 | 0（跳切） | ×0.85 | ×1（全速弹簧） |

## 4. 做不到的事（如实陈述，不做障眼法）

1. **采样「任意身后内容」做不到。** Compose/公开 Android API 没有「读取已合成像素」的能力；vivo 的实现走的是系统窗口合成层（SystemUI 级特权）。我们的透镜采样的是**应用自有背景装饰层**。如果一张不透明卡片恰好压在胶囊与页面背景之间，透镜里不会出现那张卡片——它采样的是卡片下面的背景层。要突破这个限制需要 `PixelCopy` 对 window 的异步截图（延迟 1-2 帧、且部分 ROM 上受限）或系统级 RenderNode 树访问，两者都不满足「实时、小面积、稳定」的要求，故不做。
2. **HiveHero 等大表面未上真折射。** 真折射要求该表面有对应的捕获位图；大表面意味着大面积采样与更大的 uniform 更新成本，收益（视觉上多一层透光）与成本不成比例，本版只做胶囊指示器与开关滑块两处（spec 明确「本版只做胶囊这一块，其它表面保持 0.5.7 材质」）。
3. **开关滑块的透镜不是 AGSL。** 它是 Canvas 内的矢量重绘（同帧状态、clip 到滑块圆），不是 shader 采样。视觉上满足「内容随滑块位置实时取自轨道当前状态、边缘融成折射环、RGB 色散」的所有验收点，但机制上与胶囊的位图采样不同——这是刻意的：轨道是本组件自绘的，矢量重绘比「先栅格化轨道再喂 shader」少一次位图往返，且静止零开销的约束更容易保证。
4. **API < 33 时「最高」档的着色器面自动回退材质。** 这是平台物理限制（RuntimeShader 需要 API 33），文档与设置文案均已写明，不算静默降级——其余特效（液态开关、速度形变、弹簧、全速动画）在低版本全部照常。

## 5. 验收对照表（对照 vivo 胶囊）

| vivo 效果 | 本实现 | 状态 |
|---|---|---|
| 采样身后真实画面 | 胶囊透镜采样 AppBackdrop（自有背景层） | ✅（边界见 §4.1） |
| 边缘按圆角 SDF 法线折射推移 | AGSL v2：`band² × uRefractPx`，8–14px 可调 | ✅ |
| 中心等比放大 1.08–1.15× | `uZoom` 混合：中心 1.12× → 边缘 1.0× | ✅ |
| 滑动按速度拉伸形变 | 帧差速度 → scaleX ≤ +24%，scaleY 收缩 | ✅ |
| 停下弹簧回弹 | `DampingRatioMediumBouncy` spring | ✅ |
| 最外圈 1–3px RGB 色散 | 法线方向三通道分离采样，中心 0.4× 边缘 1× | ✅ |
| 文字不参与采样、保持锐利 | 透镜只画在 `onDrawBehind`，文字在 content 层 | ✅ |
| 捕获失败/API<33 静默回退 | runCatching 全包，回退 0.5.7 材质 | ✅ |

## 6. 红线复核

- `BeeUi.kt` MD5 `3e609a2342851fd6999136705c386ade` 未动（交付时再次复核）
- Guard / Secrets / FsTools / Sandbox / VirtualScreen / PhoneModel / PhoneOperator / ShizukuTools 零改动
- `RowEnter` 未回退；未新增任何依赖
- versionName 0.5.8 / versionCode 24；CHANGELOG 2026-11-13 条目；UiExtra/LinuxRuntime/Mcp 版本串已同步
