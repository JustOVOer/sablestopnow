# 图标素材（branding/）

**Aeronautics: Tweaks & Toolkit** 的 mod 图标。几何全部取自真实模型文件，不是手搓的：

| 构件 | 来源 | 贴图 |
|---|---|---|
| 物理手杖 | Simulated `assets/simulated/models/item/creative_physics_staff/item.json` | `textures/zapper_staff.png`（16×16，原件） |
| 钢铁齿轮 | Create `assets/create/models/block/cogwheel_shaftless.json`（3 辐条 + 轮缘 + 轮毂） | `textures/cogwheel_steel.png`（32×32，由原件换色） |
| 包裹立方体 ×8 | 程序化生成（内实心 + **负尺寸**外壳，与手杖 `outer_cube` 同构） | `textures/cube_inner.png` / `textures/cube_shell.png`（各 16×16） |

## 文件

| 文件 | 说明 |
|---|---|
| `icon-512.png` | **成品图标**，512×512 透明底 |
| `aeronautics-tweaks-toolkit-icon.bbmodel` | Blockbench 工程（`free` 格式，`format_version` 5.0） |
| `textures/` | 用到的 4 张贴图；`.bbmodel` **不内嵌贴图**，在 Blockbench 里自行拖入 |
| `scene.txt` | 渲染器用的扁平四边形列表（中间产物） |
| `tools/build_scene.py` | 从 mod jar 里取几何 + 组装场景 + 写出 `.bbmodel` 与 `scene.txt` |
| `tools/IconRenderer.java` | 自写软件光栅化渲染器（正交等轴测 + z-buffer + 2× 超采样） |
| `tools/Steelify.java` | 把 Create 齿轮贴图「保形换色」成钢铁色 |
| `tools/Zoom.java` `tools/ScreenCapture.java` | 检查像素画 / 截屏（开发辅助） |

## 重新生成

```bash
# 1) 齿轮贴图换色（钢铁）
java branding/tools/Steelify.java branding/textures/cogwheel.png branding/textures/cogwheel_steel.png
# 2) 组装场景（需要 run/mods 下有航空学与 Create 的 jar）
python branding/tools/build_scene.py          # 加 staff|gear|cubes 可只输出某一部分
# 3) 渲染成品（可选参数：ss、底色、网格色、网格步长、光边色）
java branding/tools/IconRenderer.java branding/scene.txt branding/textures branding/icon-512.png 2 33383D 4A5158 32 9FB6CC
```

## 在 Blockbench 里用

1. 打开 `aeronautics-tweaks-toolkit-icon.bbmodel`（工程格式为 *Free*）。
2. 把 `textures/` 里的 4 张 png 拖进 Blockbench 的 Textures 面板，按同名对齐即可（贴图名就是槽位名）。
3. 结构：`staff` / `gear` / `cube0…cube7` 共 18 个组；每组是一个物件（组上带旋转、以物件中心为轴心），组下是若干 cube 元素。
   > ⚠ Blockbench 打开**同名文件只是切回旧标签页**，不会重新加载；改完文件请先关掉标签页或换个文件名。

## 场景约定（改的时候注意）

- 坐标系是 **y-up**（1 单位 = Blockbench 1 像素，16 = 1 方块）。Java 模型源文件是 y-down，导入时 y 取负，并按 `C·R·C⁻¹` 把 x/z 轴角度取负。
- **手杖**：`R_z(-135°)`，头（能量环/十字）朝左上、杖尾朝右下，`scale = 1.0` —— 原尺寸约 34.5 单位长，正好撑满图标对角线。
- **小立方体 ×8**：绕**手杖上端**的环形轨道摆放，轨道平面⊥手杖轴；轨道中心在轴上 `0.55 × 半长` 处（靠近上端），半径 5.0，另加黄金比小幅沿轴错开。每个是「内实心小立方 + 负尺寸外壳」。
- **齿轮**：`R_x(125°)`（立起来并**上下翻转**）+ `R_y(25°)` 转向右上，scale 0.64，落在左下角空三角里，露出厚度体现 3D。
- **背景**：深灰 `#33383D` + 浅灰网格 `#4A5158`（每 32px 一条）；模型外圈有一道 `#9FB6CC` 的浅色光边，避免深色手杖糊在深灰底上。这些都是渲染器参数，可直接命令行覆盖。

