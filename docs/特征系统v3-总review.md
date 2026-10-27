# 特征系统 v3 · 总 review（M2–M6）

> 状态：**M2–M6 全部落地**（除 item 11 的"渲染应用"一处，见 §4 已知限制）。
> 全程**未启动客户端**；每一步都跑 `tools/verify.sh` 全绿 + 按 `docs/GUI规范-自查清单.md` 自查 + 单独提交推送。

---

## 1. 做了什么（按 item）

| item | 内容 | 关键实现 |
|---|---|---|
| 4 | 树序 = 操作顺序，↑↓ 调整 + 依赖校验，冲突拒绝 | `sketch/FeatureDeps`（序号唯一 / 草图存在 / 切割在实心之后 / 基准面与圆角在源特征之后 / 镜像与阵列在源特征之后）；树行右侧 ↑/↓/🗑 子行 |
| 6 | 树行悬停高亮对应特征 | `TreeView.hoveredKey` → `ModelUi.syncFeatureHover` → `FeatureHighlight` → 渲染侧橙色描边（只存 id，不动选择集） |
| 6.5 | 点行进入左侧编辑 / 行内删除 | 拉伸行点击预填 depth1/depth2/mode；旋转行预填角度；🗑 行直接 `SolidNetworking.sendRemove` |
| 2 | 通用调色 GUI | 配置新增 `colors` 段 8 项（AARRGGBB）+ `client/Colors` 统一解析 + `ColorPaletteScreen`（HSV 三滑条 / 十六进制 / 复位 / 色块预览）+ 设置新增"调色"大类（随机染料图标）+ 渲染每帧 `reloadColors()` |
| 3 | 文字图元 | `Sketch.TextNote`（矩形 + 多行文字 + 颜色/缩放/绕左上角旋转，进撤销快照与 NBT `"texts"`）；`Tool.TEXT` 两次点击拉矩形；`TextNoteEditScreen`（自定义多行文本区，**回车换行**）；`Font.drawInBatch` 画成面向相机的世界文字（退出草图仍显示） |
| 7 | 基准面特征 | `Solid.Shape.Plane`（中心 + 单位法线 + **可负**距离 + 源面）；上边栏入口 + `PlaneCreateScreen`（改动实时预览）+ 半透明面片渲染 + 树行"基准面N" + 右栏点它即可开草图；依赖：必须在其源特征之后 |
| 8 | 圆角 | `Solid.Shape.Fillet(radius, EdgeRef[])`；`FilletGeom` 用 **N=6 阶梯盒**近似四分之一圆柱（碰撞与 CSG 渲染天然一致）；上边栏入口 + 点棱 + 半径框 + 圆角弧预览；依赖：在源特征之后；凹棱/斜棱第一版返回空并提示 |
| 9 | 镜像 | `Solid.Shape.Mirror(平面 + 源特征)`；`placeAll` 反射盒子/线框/网格（法线取反 + 绕序翻转）并作为**镜像特征自己的 Placed** ⇒ 树里只有"镜像N"一行；上边栏入口 + 点面定面 + 右栏选源 + **再点一次面 = 确认** + 黄色预览 |
| 10 | 阵列 | `Solid.Shape.Dir/Pattern`；`PatternTool` 状态机（点 1–3 条棱定方向 / 右栏选源 / 再点第一条棱弹 `PatternCreateScreen` 设次数与间距（可负）/ 预览 / 创建）；N×M 个副本归在"阵列N"一行 |
| 11 | 连接纹理 | `SolidAppearance.connected` 分量（编码/解码/等价比较 + NBT `lookConnected`）+ 贴图面板**"连接纹理"复选框**（绑定 Prop，只有"应用"才写实体）+ `client/CtmTiles`（8 邻域掩码 → 47 格图集格号 → UV 矩形） |

**顺带**（M5 期间）：`Shape.Mirror/Pattern/Fillet/Plane` 全部补齐 `boxes/mesh/edges/volume/orientedBoxes` 的空分支与占位 sketch id，保证既有链路（持久化/网络/客户端状态/树/依赖）零改动复用。

---

## 2. 验证结果

- 每次提交前都跑 `tools/verify.sh`，**全部 PASS**
- 最终态：`FULL_TYPECHECK=OK`、`SKETCH_MODEL_CHECK` **2283** 项、`SKETCH_DATA_CHECK` 148、`GUI_SPEC_CHECK` 111、`GUI_LANG_CHECK` 156、中英语言键 **767/767**、未定义键 **0**
- 新增离线用例覆盖：特征顺序/依赖（6 条）、撤销快照（4 条）、圆角阶梯盒（4 条）

---

## 3. GUI 自查（按 `docs/GUI规范-自查清单.md`）

- 新增的四个界面（`ColorPaletteScreen`、`TextNoteEditScreen`、`PlaneCreateScreen`、`PatternCreateScreen`）都是**模态 + 有取消/关闭路径**，**没有**任何"调试置顶"开关
- 面板内"待定值 + 应用按钮"的既有纪律被沿用（贴图、连接纹理复选框）
- 关闭面板/取消时：预览对象释放、选择清空（圆角/镜像/阵列都实现了 `FeaturePreview.clear()`）
- 没有引入每帧重建面板的路径（沿用内容指纹去重）

---

## 4. 已知限制（重要，必须记录）

1. **item 11 的"边框"是几何侧实现**：勾选"连接纹理"后，按模型自己的**面邻接**给每个面算边框段（有邻居的边不画、没邻居的边画内缩线）⇒ 面/棱/角三种情形天然正确 ✓
   但**没有采样外部"有边框贴图"**（`CtmTiles` 的 47 格图集数学已备好，真正逐格取图需要在资源管理器里注册 ctm 精灵源，属独立工程）⇒ 视觉上是"几何描边"而不是"贴图边框"。
2. **圆角**：第一版仅**轴对齐凸棱**；凹棱与斜棱不生效（有提示）。阶梯 6 级是近似（不是解析圆柱面）。
3. **镜像/阵列**：平面法线按**轴对齐**处理（反射 AABB 仍是 AABB）；斜平面会退化。阵列只做**线性双向**（圆周阵列未做）。
4. **基准面**：距离输入用的是**模态小框**（与设计文档写的"左侧面板"有一处有意偏差，便于保证"取消 ⇒ 预览消失"）。
5. 文字图元是**面向相机**的公告牌（不是贴合草图平面），旋转绕左上角作用在公告牌平面内。
6. 阵列产物共用**同一个实体 id**（不单独占树行），所以不能单独选中/删除其中一个副本（与镜像同）。

---

## 5. 提交索引

M2：`d7e1d73`（FeatureDeps + 树 ↑↓/删除/点击进入）、`5ddc7d3`（悬停高亮）
M3：`bed45c6`（调色配置+Colors）、`459ac5c`（调色板 GUI）、`bce3f04`（文字图元）
M4：`9094232`（Plane 数据层）、`e9473c1`（基准面交互/预览/渲染/开草图）
M5：`69f32f6`（FilletGeom+用例）、`d4130af`（Fillet 接入模型）、`00ff083`（圆角交互）、`aabff56`（Mirror 模型）、`83355f7`（镜像交互）、`26a2f25`（Pattern 模型）、`c5fa213`（阵列交互）
M6：`f9c166e`（CtmTiles + connected 数据）、`92491d9`（连接纹理复选框）
