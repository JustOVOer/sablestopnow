# 第三方声明（Third-Party Notices）

本项目（SableStopNow / **Aeronautics: Tweaks & Toolkit**）以 **MIT** 许可发布，见 `LICENSE.md`。

下面列出我们**参考或使用**过的第三方作品及其许可。凡"吸收了思路/算法"的地方，
即使没有逐行拷贝代码，也在此留档 —— 这是 MIT 与学术惯例都要求的诚实做法，
也避免以后有人翻到某段算法却不知道它从哪来。

---

## 1. UnityNonConvexMeshColliders

- **用途**：非凸网格 → 碰撞代理的近似方案（体素化 + 贪心合并 + 逐格内外判定）
- **来源**：https://github.com/JohannHotzel/UnityNonConvexMeshColliders
- **许可**：MIT License，Copyright (c) 2025 Johann
- **我们实际采用了什么**：
  - **"内外判定用多个方向的射线投票"** 这个**思路**。
    它的 `VoxelCollider.IsPointInsideMeshByTrianglesLocal` 对 right / up / forward
    三个方向各投一条射线再投票，用来压掉"射线擦过共面或共边"导致的误判。
  - **没有拷贝它的任何代码**（它是 C# / Unity，我们是 Java / Minecraft）。
    我们的实现也不一样：它在**每个格**上投 3 条射线（O(格子数 × 三角形数)），
    我们是**每条线算一整列/行**再做跨度填充、最后逐格表决（O(格线数 × 三角形数)），
    复杂度低一个量级。见 `sketch/SolidCollision.java` 的 `occupancy` / `fillAlong`。
- **没有采用的部分**：`DecompositionCollider`（按体素切三角形生成 MeshCollider）与
  `PoissonDiscCollider`（表面撒点生成球体）—— 我们的物理只支持盒体碰撞，
  这两种没有对应物。**贪心合并**（先沿 x、再沿 y、再沿 z 长大）是公开的通用做法
  （"greedy meshing"），我们按标准形式独立实现，与它的 `CreateMergedBoxCollidersLocal`
  结构相同，属于同一公开算法而非其独创。

### MIT License 原文（必须随附）

```
MIT License

Copyright (c) 2025 Johann

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

---

## 2. 只读参考、**未使用其代码**的源码树（放在 `depends/`，不入库）

以下是**为了查清 API 语义**而阅读的上游源码。我们**没有**把它们的代码复制进本项目，
它们也不参与编译（`/depends/` 已在 `.gitignore` 里）。

| 项目 | 用途 | 许可 |
|---|---|---|
| **Sable**（`depends/sable-main`，含 Rust 源码与原生库） | 物理 API 的真实语义（关节锚点/朝向、`addBox` 建立独立刚体、`contacts_enabled` 默认值、质量聚合范围） | 见其仓库 |
| **Create: Simulated / Aeronautics**（`depends/Simulated-Project-main`） | 依赖方的接口形状 | 见其仓库 |
| **Veil**（`depends/Veil-1.21`） | 渲染/管线接口 | 见其仓库 |
| **NeoForge 文档 / sable-schematic-api** | 平台 API | 各自的许可 |

⚠ **这些目录里的代码受其各自许可约束**：本项目只"读"它们来确认行为，
**不复制、不再分发**。若要真的采用其中某段实现，必须先把对应许可与声明补进本文件。

---

## 3. 为 NURBS 方案做过的**许可调研**（结论：均不引入）

见 `docs/nurbs-unification-design.md` §2。这里只登记结论与依据，因为**我们一行代码都没用**。

| 项目 | 许可 | 结论与依据 |
|---|---|---|
| **Open CASCADE Technology (OCCT)** | **LGPL-2.1-only + [OCCT-exception-1.0](https://spdx.org/licenses/OCCT-exception-1.0.html)** | **不引入**。该例外原文（`OCCT_LGPL_EXCEPTION.txt`）**只覆盖"头文件内容被编进目标码"**这一件事，**并未**使库本身脱离 LGPL-2.1。链进 MIT 的 mod 要承担 LGPL 的**重新链接义务**，且需 JNI 多平台原生层、体积达数百 MB —— 与 mod 的单 jar 分发形态不合。 |
| **SISL**（SINTEF） | LGPL / GPL 双许可 | **不引入**（同上：LGPL 的重新链接义务 + 原生依赖）。 |
| **`harmanpa/jgeom`** | 状态陈旧，且 Xj3D 邮件列表存在专门的 ["Licensing Issue with jgeom library in Xj3D"](https://web3d.org/pipermail/source_web3d.org/2012-July/000199.html) 讨论 | **不引入**（协议本身有争议）。 |
| **`praseedpai/NurbsCGLibrary2002`** | 2002 年作品、依赖 Java3D | **不引入**。 |
| **`com.github.virtuald:curvesapi`** | Apache-2.0 | **范围不符**：二维 Bézier/多项式折线（表格绘图用），不是 NURBS 曲面。 |

### 我们实际做了什么

`src/main/java/com/ovo/sablestopnow/sketch/nurbs/` 下的实现是**自己写的**，
依据是公开的数学定义与教科书算法（Piegl & Tiller, *The NURBS Book*：Cox–de Boor 递推、
有理形式、商法则求导、弦高细分判据）。**数学公式与算法步骤不受版权保护，也不存在代码复制**，
因此本文件**不需要**为它附加任何第三方许可文本。

> 之所以仍然登记这张表：**下一个人不该再花一遍时间重新调研**，而且"我们评估过但没采用"
> 这个结论本身就是需要留档的决策依据。

---

## 4. 碰撞/贴图对接调研的许可登记（见 `docs/nurbs-collision-texture-integration.md`）

同样**一行代码都没用**，只登记结论与红线，避免后人重复调研、也避免误抄。

| 项目 | 许可 | 结论 |
|---|---|---|
| **Sable 本体**（`depends/sable-main` 等） | **Polyform Shield 1.0.0**（**非开源**，含竞业限制条款） | ⛔ **绝不复制任何代码**。我们此前只"读"它确认 API 语义，这是正确且必须继续的做法 |
| **CoMISo** | **GPL**（官方页面明写） | ⛔ 红线，不读代码 |
| OpenFlipper / **gprMax** / CGAL（多数模块） | GPL 系 | ⛔ |
| Unreal Engine 材质/着色器源码 | UE EULA（专有） | ⛔ 仅引用第三方讲解文章 |
| **libigl** | **MPL-2.0**（文件级 copyleft） | ⚠ 只读思路，不复制文件 |
| **Open CASCADE (OCCT)** | LGPL-2.1 + OCCT 例外 | ⚠ 见 §3；只吸思路 |
| **Rapier / parry** | **Apache-2.0** | ✅ 可安全参考/复用 |
| **Coal** | **BSD-3-Clause** | ✅ |
| **OpenVDB** | **Apache-2.0** | ✅ |
| **Instant Meshes** | **BSD-3-Clause** | ✅ |
| **BFF / GeometryCollective** | **MIT** | ✅ |
| **NurbsSharp** | **MIT** | ✅（我们未使用，仅作对照参考） |
| **bgolus triplanar shader** | **The Unlicense**（公有领域） | ✅ 最宽松 |
| Defold biplanar mapping / Hex-Tiling·STF 配套代码 | **未知** | ❓ 复制前必须逐项核实 |

⚠ **本文件的作用是"防误抄"，不是"许可清单"**：登记为 ✅ 的项目也只是**允许参考**，
本项目实际并未引入任何第三方几何/贴图库（理由见 `docs/nurbs-unification-design.md` §2）。
