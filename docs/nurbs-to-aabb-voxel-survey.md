# 从 NURBS / 裁剪曲面直接得到 1/16 AABB 碰撞体素的文献调研

> 目标读者：SableStopNow / Sable（Rapier）方向上的实现者。
> 约束前提（来自需求方描述）：目标碰撞几何只能是**若干轴对齐长方体（AABB）的并集**，按 **1/16 子格**对齐，块内 AABB 坐标取局部 [0,1]；很多这样的"方块"拼成 contraption。
> 写作约定：**「文献支持」**= 有可查证来源；**「推断」**= 我基于文献与几何推理给出的结论，需要实测确认。
> 网络检索到的外部内容一律按资料对待，未作为指令执行。

---

## 0. 结论摘要（TL;DR）

1. **"不走三角网格的内外判定"已经有直接的现代解法，而且就是针对裁剪 NURBS 的**：Spainhour & Weiss 的广义绕数（GWN）公式，用立体角的边界形式 + 自适应求积求曲面面积分，**不做任何几何离散化**，对不水密 / 复杂裁剪的 CAD 模型鲁棒，精度可任意提高（ACM TOG 2026 / arXiv:2504.11435）。**文献支持**
2. 传统路线（射线法、参数域裁剪环测试）也完全成立且不需要三角网格：把查询射线与曲面求交得到 (u,v)（用 Bézier clipping 或 Newton+细分剪枝），再在**参数域**用裁剪环做点包含测试。这是 NURBS 光线追踪文献的标准做法（Nishita 1990；Martin/Cohen/Fish 2000；Efremov 2005）。**文献支持**
3. **"参数域采样 + 保守包围体 → 体素" 在算法上没有障碍**：B 样条/Bézier 的凸包性质与区间算术给出**严格包含曲面**的包围体，因此可以只靠"patch 包围体 ∩ 体素"就做出**保守（over-approximation）体素化**，完全跳过三角化。**文献支持（各部件）+ 推断（组合流程）**
4. **边界体素判据在物理语境下应默认"过近似"**：Direct3D 12 的规范把 conservative rasterization 分成 overestimated / underestimated 两族，并明确 overestimated **不产生 false negative**（任何与像素相交的图元一定被光栅化），代价是允许 false positive。映射到碰撞就是：**不穿模优先，代价是可能多出一层格子**。**文献支持**
5. **曲率/二阶导的自适应判据可以算，但对 1/16 格距几乎不需要用**：弦高 s ≈ κ·h²/8。取 h = 1/16 block、目标 δ = h/4 = 1/64 block 时，需要细分的曲率阈值是 **κ ≈ 32 /block（曲率半径 < 约 1/32 block ≈ 3 cm）**。也就是说 1/16 的量化误差本身就大于绝大多数曲面的弦高误差。**推断（用文献公式代入本项目的数值）**
6. **格心采样（cell-center sampling）不能作为唯一边界判据**：gprMax 的 STEP→voxel 文档明确写了失败模式——**比一个格子还薄的闭合实体可能落在所有格心之间而整体消失**，他们不得不加"薄实体保底"补丁。**文献支持**
7. 许可红线（详见 §6）：**OCCT 是 LGPL-2.1 + 例外**；**Sable 本体是 Polyform Shield 1.0.0（非开源、含竞业限制）**；**gprMax 是 GPL-3.0**；**CGAL 多数包 GPL**。可安全抄代码的只有 **Rapier/parry（Apache-2.0）**、**Coal（BSD-3-Clause）**、**OpenVDB（Apache-2.0）** 这几个；其余只能**吸收思路**。

---

## 1. 把问题形式化

输入：一组 NURBS 面 $S_i(u,v)$（有理、可能带裁剪环 $C_i \subset [0,1]^2$），闭合或不闭合，可能不水密。
输出：每个 block 内一组 AABB，等效于一张 1/16 分辨率的**占位网格** $V: \mathbb{Z}^3 \to \{0,1\}$（16 倍于 block 的子格），要求 $V$ 是实体 $S$ 的**保守过近似**（$S \subset \bigcup V$），且空间开销可接受。

这里的关键认识（**推断**）：一旦接受 1/16 对齐，问题的本质不是"如何渲染曲面"，而是
> **对每个 1/16 子格做一次"体素 vs 曲面"的分类，并决定三态中的哪一态。**

三态是：**确定在内 / 确定在外 / 不确定（边界带）**。经典体素化里只有前两态（格心采样），而物理碰撞的正确做法是**把"不确定"归到"实"**。

---

## 2. 问题一：参数曲面的内外判定（inside / outside）

### 2.1 闭合曲面：射线法、绕数、半空间测试

- **广义绕数（GWN）**：Jacobson, Kavan, Sorkine-Hornung, *Robust inside-outside segmentation using generalized winding numbers*, ACM TOG 2013 — [doi:10.1145/2461912.2461916](https://doi.org/10.1145/2461912.2461916)。核心价值是**对不水密/自交/朝向不一致的输入仍然给出有意义的"内部度"**（值 ∈ [0,1] 连续），而不是二值的失败。加速版本：Barill et al., *Fast winding numbers for soups and clouds*, TOG 2018 — [doi:10.1145/3197517.3201337](https://doi.org/10.1145/3197517.3201337)。**文献支持**
- **射线法（ray parity / ray stabbing）**：理论最简，但要在"射到边/顶点/切点"时做稳健处理。
- **基于法线的半空间测试**：对近似凸的面片可用局部切平面符号判定。这里的**可计算误差界**是标准微分几何结论（**推断，公式为我给出**）：设 $T$ 为曲面在 $q$ 处的切平面，$p$ 距 $q$ 为 $r$，则
  $$|d_T(p) - d_S(p)| \le \tfrac12 \kappa_{\max}(q)\, r^2 + O(r^3)$$
  于是"用切平面判定 + 用 $\frac12\kappa_{\max}r^2$ 做安全余量"可以变成**保守**的半空间测试。这个方法只对"该 patch 的 (u,v) 足迹包含 $p$ 的投影"时才可以计数，否则会重复计数（在裁剪曲面上尤其容易出错）。

### 2.2 关键文献：面向裁剪 NURBS 集合的 GWN 包含查询

**Spainhour & Weiss, _Robust Containment Queries over Collections of Trimmed NURBS Surfaces via Generalized Winding Numbers_**, ACM TOG 45(3), Art. 26 (2026)；预印本 [arXiv:2504.11435](https://arxiv.org/abs/2504.11435)；[doi:10.1145/3797957](https://doi.org/10.1145/3797957)（LLNL，[OSTI 条目](https://www.osti.gov/biblio/3364505)）。

摘要中的关键论断（原文引用）：
- "Applying contemporary techniques for the 3D GWN to trimmed NURBS surfaces **requires some form of geometric discretization**, introducing computational inefficiency to the algorithm and **even risking containment misclassifications near the surface**." —— **这正是本项目想避免的"中间网格"问题，被论文明确点名为缺陷。**
- 他们的替代方案："leverages properties of the **3D solid angle** to solve the relevant surface integral using a **boundary formulation with rapidly converging adaptive quadrature**"，并用 **memoization**（缓存复用求积节点位置与切矢）批量加速查询。
- 结论："robust to complex trimming geometry… **accurate up to arbitrary precision at arbitrary distances from the surface**… robust to model non-watertightness while respecting all curved features."

**对本项目的意义（推断）**：这是"用一个查询函数 `containment(p) -> 带符号/带置信度` 直接喂给体素分类器"的最强候选。它不需要三角化、不需要水密、天然处理裁剪，而且**误差可控**（自适应求积收敛）。代价是单点查询比"格心采样一个近似网格"贵得多，所以只应把它用在**边界带与歧义格子**上（见 §7）。

**配套的二维版本**（对参数域裁剪判定极有用）：
Liu, Liu, Fu, *Closed-form Generalized Winding Numbers of Rational Parametric Curves for Robust Containment Queries*, SIGGRAPH 2025 technical paper — [日程页（含摘要）](https://s2025.conference-schedule.org/presentation/?id=papers_744&sess=sess112)。给出**有理参数曲线 GWN 的闭式表达**，即使查询点落在曲线上也能算，且给出 GWN 的导数。参数域里的裁剪环就是有理参数曲线，所以这条直接对应"参数域点包含测试"。**文献支持**

### 2.3 裁剪曲面（trimmed surface）：参数域才是正确战场

裁剪曲面由 $(S, \{C_j\})$ 定义，$C_j$ 是参数域 $[0,1]^2$ 里的曲线环。判定流程的教科书形式是：

1. 把 3D 查询映射回参数域：射线求交得 $(u^*,v^*)$（或点投影得最近参数）。
2. 在参数域里做**点是否在裁剪域内**的判定（裁剪环为正则闭环时用 even-odd / nonzero winding）。

可查证的做法来源：
- **OCCT 的现成 API**：`BRepClass_FaceClassifier` 的构造函数直接接受 **`gp_Pnt2d`（参数域点）**，即"在面的参数空间里分类一个点"，也就是参数域裁剪判定；[OCCT 8.0.1 文档](https://occt3d.com/dev/doc/refman/html/class_b_rep_class___face_classifier.html)。3D 侧对应 `BRepClass3d_SolidClassifier`（[文档](https://occt3d.com/dev/doc/refman/html/class_b_rep_class3d___solid_classifier.html)）。**文献/文档支持（其内部是否等价于解析射线求交，我未逐行核实 OCCT 源码，建议自行确认）**
- **NURBS 光线追踪经典链条**（全部在参数域做裁剪判定，均不依赖三角网格）：
  - Nishita, Sederberg, Kakimoto, *Ray tracing trimmed rational surface patches*, SIGGRAPH 1990 — [doi:10.1145/97880.97916](https://doi.org/10.1145/97880.97916)
  - Martin, Cohen, Fish, *Practical Ray Tracing of Trimmed NURBS Surfaces*, J. Graphics Tools 5(1), 2000 — [doi:10.1080/10867651.2000.10487519](https://doi.org/10.1080/10867651.2000.10487519)
  - Efremov, Havran, Seidel, *Robust and numerically stable Bézier clipping method for ray tracing NURBS surfaces*, SCCG 2005 — [doi:10.1145/1090122.1090144](https://doi.org/10.1145/1090122.1090144)
  - Sloup & Havran, *Optimizing Ray Tracing of Trimmed NURBS Surfaces on the GPU*, CGF 2021 — [doi:10.1111/cgf.14410](https://doi.org/10.1111/cgf.14410)
- **GPU 上的参数域裁剪（保留裁剪信息、不做 trim 三角化）**：Schollmeyer & Fröhlich, *Direct trimming of NURBS surfaces on the GPU*, TOG 2009 — [doi:10.1145/1531326.1531353](https://doi.org/10.1145/1531326.1531353)；以及 *Efficient and Anti-Aliased Trimming for Rendering Large NURBS Models*, TVCG 2019 — [doi:10.1109/TVCG.2018.2814987](https://doi.org/10.1109/TVCG.2018.2814987)。
- **点包含的稳健 2D 算法**（含 winding/even-odd 的边界与退化处理）：Hormann & Agathos, *The point in polygon problem for arbitrary polygons*, Computational Geometry 20(3), 2001 — [doi:10.1016/S0925-7721(01)00012-8](https://doi.org/10.1016/S0925-7721(01)00012-8)。
- **裁剪问题的综述与替代路线**：Marussig & Hughes, *A Review of Trimming in Isogeometric Analysis*, Archives of Computational Methods in Engineering 25(4), 2017 — [doi:10.1007/s11831-017-9220-9](https://doi.org/10.1007/s11831-017-9220-9)。另一条"根治"路线是把裁剪面重构为**水密的无裁剪曲面**：Sederberg, Finnigan, Li et al., *Watertight trimmed NURBS*, SIGGRAPH 2008 — [doi:10.1145/1399504.1360678](https://doi.org/10.1145/1399504.1360678)，水密之后 2.1 的简单方法就够用。**文献支持**

### 2.4 有没有"不需要离散化的解析判定"？

有，主要三条：
1. **GWN 的自适应求积（§2.2）**——是最"直接"的一条。
2. **区间算术 / 有界表示**：Hu, Patrikalakis, Ye, *Robust interval solid modelling Part I: representations* 与 *Part II: boundary evaluation*, Computer-Aided Design 1996（Part II: [doi:10.1016/0010-4485(96)00014-0](https://doi.org/10.1016/0010-4485(96)00014-0)）。思路是用区间 NURBS（控制点为区间）做**严格包含**的求值，从而给出"必在内/必在外/不确定"三态，用细分消除不确定。**文献支持（题录级）；具体算法细节我未读到全文，属推断**
3. **代数水平集 / 隐式化**：Upreti 等, *Signed algebraic level sets on NURBS surfaces and implicit Boolean compositions for isogeometric CAD–CAE integration*, Computer-Aided Design 2016 — [doi:10.1016/j.cad.2016.09.006](https://doi.org/10.1016/j.cad.2016.09.006)（[ScienceDirect](https://www.sciencedirect.com/science/article/abs/pii/S0010448516301129)）。把 NURBS 曲面转成带符号的代数水平集函数。**文献支持（题录级，全文未读到）**

> 注：我尝试读取两篇的全文/摘要均被付费墙挡住，故只以题录引用，**不为其算法细节背书**。

---

## 3. 问题二：直接体素化参数曲面（不经三角化）

### 3.1 已有"直接"证据

- **直接光栅化参数曲面（无三角化）**：Wang & Chen, *PaRas: A Rasterizer for Large-Scale Parametric Surfaces*, SIGGRAPH 2025 / ACM TOG 44(4) — [SIGGRAPH 日程页（含摘要）](https://s2025.conference-schedule.org/presentation/?id=papers_526&sess=sess284)，作者 PDF 在 [USTC](http://staff.ustc.edu.cn/~renjiec/papers/paras.pdf)。摘要原文："a GPU-based rasterizer that **directly renders parametric surfaces, avoiding costly tessellation**… outperforming traditional methods for quartic triangular and bicubic rational Bézier patches"。**文献支持**（注意：它是**渲染**而非碰撞体，但它证明了"参数域直接采样/覆盖判定"在高性能场景可行，且走的是 GPU 光栅化路径）
- **中文期刊里有一篇正题就是"非网格化体素生成"**：《面向立体光刻的有理曲面非网格化体素生成方法》/ *Meshless Voxel Generation Method of Rational Surfaces Towards Stereolithography*，《计算机辅助设计与图形学学报》，[doi:10.3724/SP.J.1089.2024-00070](https://www.jcad.cn/en/article/doi/10.3724/SP.J.1089.2024-00070)。**标题级证据（我未能取到摘要页面，全文未读，不为其算法细节背书）**。这是我在检索中发现的**与本需求最贴题的一篇**，建议人工下载精读。
- **对 trimmed NURBS 模型直接算距离场**：*HyBoDT: Hybrid Bounded Distance Transforms of Trimmed NURBS Models*, ASME JCISE 22(4):041008, 2021 — [doi:10.1115/1.4053202](https://doi.org/10.1115/1.4053202)。**标题级证据（ASME 页面为 JS 渲染，未取到摘要）**。若确为"trimmed NURBS 的有界距离变换"，与 §3.2 的 SDF 路线同源，值得精读。

### 3.2 SDF from NURBS：可行路线与精度特性

把"符号"和"距离"分开看：

- **符号**：用 §2.2 的 GWN（可任意精度、对非水密鲁棒），或用参数域裁剪测试 + 射线奇偶。**文献支持**
- **大小（距离）**：
  - **点投影 / point inversion（Newton）**：对 $\|S(u,v)-p\|^2$ 做 Newton 迭代，**二阶收敛**，但需要好的初值且有落入局部极小的风险；标准处理与伪码见 Piegl & Tiller, *The NURBS Book* 第 6 章（点逆映射/投影）。**文献支持（书目级）**
  - **Bézier clipping**：把曲线/曲面用凸包裁剪逐次收缩参数区间，从而**全局**定位交点/最近点，且数值稳健；用于光线求交的工程版本见 Efremov et al. 2005（上引）。这是"既不做三角化、又能保证不丢解"的关键工具。**文献支持**
  - **细分 + 凸包/区间界**：Bézier 的控制点凸包严格包含对应曲面片，细分后凸包单调收缩 → 可以给出**带证书的距离上下界**。这是"无网格 SDF"的严格性来源。**文献支持（凸包性质是 CAGD 基本定理）+ 推断（组合方式）**
  - **有界距离变换**：HyBoDT（上引）——如果你只需要"近表面 ±若干格"的距离，而不需要全局精确 SDF，**有界距离变换比全局 SDF 便宜得多**，这与"只关心边界带"的碰撞需求天然契合。**推断**
- **精度特性小结（推断）**：GWN 给出的符号误差可通过自适应求积压到任意小（论文声明），代价是近表面处求积核接近奇异，必须用论文的"立体角边界形式"而不是朴素面积分；距离项用 Newton+Bézier clipping 时误差主要是"陷局部极小"而非数值精度，因此**必须用细分剪枝给出全局保证**。

### 3.3 反面教材：现状主流确实还在三角化

- **parry（Rapier 的几何库）自带的体素化就是"mesh → voxels"**：其 `Voxels` 文档在 "See Also" 里指向 `crate::transformation::voxelization`，描述为 "**Convert meshes to voxels**" — [docs.rs/parry3d Voxels](https://docs.rs/parry3d/latest/parry3d/shape/struct.Voxels.html)。也就是说 **Rapier 生态里现成的体素化入口不满足"NURBS 直出"的要求，只能自己生成体素状态再喂给 `Voxels`。**
- **gprMax 的 STEP→voxel 工具箱**明确依赖三角化："STEP reading and **tessellation** require OpenCascade's Python bindings"、"The solid voxeliser intersects a **tessellated** component with parallel planes and fills each closed cross-section"，并在 Limitations 里承认 "CAD tessellation and voxelisation necessarily **staircase** curved and oblique surfaces" — [gprMax 文档](https://docs.gprmax.com/en/latest/inc_STEPtoVoxel.html)。
- **OCCT 自己的造型到网格是 `BRepMesh_IncrementalMesh`**（三角化），用于可视化/交换/碰撞 — [OCCT Mesh 用户指南](https://occt3d.com/dev/doc/overview/html/occt_user_guides__mesh.html)。但注意：OCCT **同时**提供不走三角化的分类器（§2.3），两者并不互斥。
- **BRL-CAD**：有解析光线投射 NURBS 的 `librt`，其 wiki 上"Voxelize"页面提出的方案正是"**using raytracing to interrogate a specified combination/region/assembly and produce a set of arb8's**（用光线投射生成近似该形状的一组 arb8 多面体）"——[BRL-CAD Wiki: Voxelize](https://brl-cad.github.io/wiki/task/Voxelize/)。**但要如实说明：该页面是任务提案页，不是已完成功能的文档**，所以它是"同类思路的先例"，不能当作可参考的实现。**文档支持（作为提案）**

---

## 4. 问题三：边界体素的判定准则 —— 保守 vs 精确

### 4.1 术语与保证（有规范级来源）

Direct3D 12 的规范明确区分了两族保守光栅化 — [Microsoft Learn: Conservative Rasterization](https://learn.microsoft.com/en-us/windows/win32/direct3d12/conservative-rasterization)：
- **Overestimated Conservative Rasterization**：只要图元与像素有任何重叠就光栅化该像素。原文："Conservative Rasterization implementations **do not produce false negatives** with respect to the floating-point vertex coordinates: if any part of a primitive overlaps any part of a pixel, then that pixel is rasterized." 同时明确 "**do produce false positives**"。
- **Underestimated**：只光栅化**完全被覆盖**的像素（由 `SV_InnerCoverage` 暴露），保证无 false positive，允许 false negative。
- 规范还直白地把它和碰撞检测挂钩："Conservative Rasterization adds some certainty to pixel rendering, which is helpful in particular to **collision detection** algorithms"；Tier 2 的说明里点名 "helpful for CPU-based algorithm acceleration (such as **voxelization**)"。
- 规范也给出"不确定区域"（uncertainty region）的量化概念：实现允许的过近似被限定在半个像素（Tier 1）/ 1/256 像素（Tier 2）的扫掠方块内。

**文献/规范支持。这组术语与保证关系可以直接搬到体素上。**

### 4.2 体素化自身的分类学

- 表面体素化的经典分类 **6-separating / 26-separating / conservative** 通常追溯到 Cohen-Or & Kaufman, *Fundamentals of Surface Voxelization*, CVGIP: Graphical Models and Image Processing, 1995 — [doi:10.1006/gmip.1995.1039](https://doi.org/10.1006/gmip.1995.1039)。**（我只核实了题录；"这些术语出自该文"这一归属属常见引法，未见全文，谨慎采信。）**
- GPU 上的表面/实体体素化工程实现：Schwarz & Seidel, *Fast parallel surface and solid voxelization on GPUs*, SIGGRAPH Asia 2010 / TOG — [doi:10.1145/1882261.1866201](https://doi.org/10.1145/1882261.1866201)。**（题录级）**

### 4.3 物理碰撞语境下的选择

**推断（但依据充分）**：碰撞体素化的两种错误不对称：

| 判据 | 几何性质 | 碰撞后果 |
|---|---|---|
| 保守 / 过近似（任何重叠即填） | 无 false negative | 不会穿模；但可能"多出一层壳"，导致悬空/提前接触/卡在看似空气的地方 |
| 精确 / 欠近似（完全在内才填，或格心采样） | 无 false positive（前者） | 不会悬空；但**会穿模**，且格心采样对薄壳可能整块消失（见 4.5） |

结论：**默认选过近似**。理由有三：
1. 玩家对"撞到空气"的容忍度显著高于"穿墙"；
2. 过近似的误差有**硬上界**（每轴一层格子 + 曲面包围误差，见 §5.4），而格心采样的误差对薄几何**没有上界**；
3. 引擎可以事后用 contact skin / 摩擦等参数掩盖"多出来的一点点"，但无法掩盖几何缺失。

### 4.4 引擎侧的实证约束（Rapier）

- Rapier 于 0.16.0（2025-04）引入 `ColliderDesc.voxels` 与 `Voxels` 形状；0.17.0 支持动态刚体上的 voxel collider 与 voxel↔compound 的力计算；0.17.1 增加 `Collider.combineVoxelStates` "to ensure **two adjacent voxels colliders don't suffer from the internal edges problem**"，以及 `Collider.propagateVoxelChange`；0.19.1 起改为**稀疏存储**（Rapier 0.30，见 [parry#380](https://github.com/dimforge/parry/pull/380)）以支撑超大 voxel 地图 — [rapier.js CHANGELOG](https://raw.githubusercontent.com/dimforge/rapier.js/refs/heads/master/CHANGELOG.md)。**文档支持**
- parry 的 `Voxels` 文档把**内部边问题**讲得很明白："When an object slides across a flat surface made of voxels, it can **snag on the edges between adjacent voxels**, causing jerky motion." 其解法是**每个 voxel 存 1 字节的邻接信息**（free faces / internal faces），并配合 `combine_voxel_states`、`propagate_voxel_change`、`align_aabb_to_grid`、`from_points` 等 API — [docs.rs](https://docs.rs/parry3d/latest/parry3d/shape/struct.Voxels.html)。**文档支持**
- Rapier 对三角网也有同类开关：`TriMeshFlags::FIX_INTERNAL_EDGES` — 同 CHANGELOG。

**对本项目的直接含义（推断）**：
1. 拼接多个 block 的体素时，**必须做邻接状态合并**（若 Sable 未透出 `combineVoxelStates`，就要在生成阶段把跨 block 的相邻格统一考虑后一次性生成，否则 contraption 内部会出现"看不见的台阶"和抖动）。
2. **AABB 合并（greedy meshing）只能在"并集不变"的前提做**：合并同向共面的连续格是安全的；跨凹/凸棱合并会改变几何。合并的收益是减少 collider 数量与内部边。

### 4.5 实战判据的一个反面/正面样本：gprMax STEPtoVoxel

[gprMax 文档](https://docs.gprmax.com/en/latest/inc_STEPtoVoxel.html)（原文引用）：
- 默认 `--supersample 1`：**"evaluates the centre of every Yee cell"**（格心采样）；>1 时在 x/y/z 各取 N 个样本，**"assigns a cell when a strict majority of the resulting samples is inside"**（多数表决）。
- **失败模式**："A closed solid thinner than one cell **can lie between all cell centres and disappear**."（比一格还薄的实体可能落在所有格心之间而消失。）
- 补丁："If an otherwise empty solid is thinner than one cell along x, y or z, the toolbox samples its own mid-plane and represents it with one FDTD cell along that axis… intended as a **loss-prevention safeguard**, not as a claim that an unresolved object has been modelled accurately."

**这是"格心采样不能当唯一判据"的一手工程证据，也是"必须有过近似兜底"的直接论据。** 顺带一提，多数表决（majority / 体积占比）是**第三种**判据：它优于格心，但仍**无保守保证**，对薄壳/近切面同样会漏。

---

## 5. 问题四：误差与自适应判据（曲率 ↔ 格距）

### 5.1 弦高/矢高（sagitta）与二阶导界

- **圆的矢高公式（初等几何，可自行验证）**：弦长 $c$、半径 $R$ 时
  $$s = R - \sqrt{R^2 - (c/2)^2} = \frac{c^2}{8R} + O(c^4), \qquad s \approx \frac{\kappa\, c^2}{8}$$
  参考：[Sagitta (geometry)](https://en.wikipedia.org/wiki/Sagitta_(geometry))。
- **曲线/曲面的严格版本**：Filip, Magedson, Markot, *Surface algorithms using bounds on derivatives*, Computer-Aided Geometric Design, 1986 — [zbMATH 题录](https://zbmath.org/pdf/04028719.pdf)（我未能读到全文，DOI 未核实，请按题名检索）。该文用**二阶导的上界**给出弦/面片逼近的偏差界。标准表述（**由我改写**）：
  $$\max_{u\in[u_i,u_{i+1}]}\|C(u) - \text{chord}\| \le \frac18 \max\|C''\| \,(\Delta u)^2$$
  曲面双线性/平面逼近的双参数版本形如
  $$\delta \le \frac18\Big(M_{uu}\Delta u^2 + 2M_{uv}\Delta u\,\Delta v + M_{vv}\Delta v^2\Big)$$
  其中 $M_{\bullet\bullet}$ 是该 patch 上相应二阶偏导模的上界。**文献支持（曲线版本）；上式的曲面形式与常数项细节请以原文为准（我未读到全文）。**
- **自适应细分的工程化**：Piegl & Tiller, *The NURBS Book* 第 6.6 节（曲面的自适应细分/平坦性测试）；配套的裁剪面三角化专著：Piegl & Richard, *Tessellating trimmed NURBS surfaces*, CAD 27(1), 1995 — [doi:10.1016/0010-4485(95)90749-6](https://doi.org/10.1016/0010-4485(95)90749-6)；Sheng & Hirsch, *Triangulation of trimmed surfaces in parametric space*, CAD 24(8), 1992 — [doi:10.1016/0010-4485(92)90011-X](https://doi.org/10.1016/0010-4485(92)90011-X)。**题录级**

### 5.2 工业界的双判据（可直接照搬的参数命名与语义）

[OCCT Mesh 用户指南](https://occt3d.com/dev/doc/overview/html/occt_user_guides__mesh.html)：
- **Linear deflection**："limits the **distance between a curve and its tessellation**"（≈ 弦高）。
- **Angular deflection**："limits the **angle between subsequent segments** in a polyline"；对面内部还有 `DeflectionInterior` / **`AngleInterior`（仅对 B-spline 面）"limits the angle between normals in the nodes of every link"**。
- **下限保护**："if a given value of linear deflection is less than shape tolerance then the algorithm will skip this value and will take into account the **shape tolerance**."（不要细化到低于模型自身容差——这正对应我们的 1/16 物理下限。）
- NURBS 面有专门的内部节点生成器 `BRepMesh_NURBSRangeSplitter`。

**这就是"曲率 ↔ 格距"的工业答案：用 linear deflection（绝对长度）和 angular deflection（法向夹角）双阈值控制，而不是直接用曲率。** 对本项目的意义：把 linear deflection 设为**子格尺寸的一小部分**（如 h/4），把 angular deflection 设为与接触法向精度相关的一个角度，就可以同时约束几何偏差与法向偏差。**文献/文档支持**

### 5.3 把判据代入 1/16 格距（数值化）

设 block 边长 = 1（Minecraft 单位），子格 $h = 1/16 = 0.0625$。取"可接受的曲面近似偏差" $\delta = h/4 = 1/64 \approx 0.0156$。

- 曲率判据：需要 $\kappa h^2/8 \le \delta \Rightarrow \kappa \le 8\delta/h^2 = 8\times0.0156/0.00391 \approx 32\,\text{block}^{-1}$，即**曲率半径 $R \gtrsim 1/32$ block ≈ 3 cm**。
- 换言之：**只有当特征半径小于约 3 cm 时才需要比 1/16 更细的格子**；而一个 1/16 格子自身的对角线就有 $\sqrt3 h \approx 0.108$ block，**量化误差本身就比弦高大一个量级**。

**推断（结论）**：在 1/16 分辨率下，**"按曲率自适应地加密网格"对碰撞几何几乎无用**。真正需要自适应细分的是下面三类"拓扑/切向"问题：
1. **薄壳**：厚度 < $h$ 的实体（格心采样会消失）；
2. **近切**：曲面几乎平行于格平面（一个格子横跨很长的曲面片，符号判定易错）；
3. **裁剪边界**：裁剪环本身穿过格子（歧义格子密集出现在裁剪边界上）。

### 5.4 各条近似的误差上界（给实现用的账本）

| 环节 | 近似 | 上界 | 来源 |
|---|---|---|---|
| patch → 参数子片 | 在参数域分块 | 弦高 $\delta_{chord} \le \frac18(M_{uu}\Delta u^2+2M_{uv}\Delta u\Delta v+M_{vv}\Delta v^2)$ | Filip 1986 / NURBS Book §6.6 |
| patch → 包围体 | 控制点凸包 / 区间 | 包围体宽度 $\delta_{hull}$（细分后单调收缩） | 凸包性质（CAGD 基本定理） |
| 体素化（过近似） | 任何重叠即填 | 曲面被包在厚度 $\le h + 2\delta_{hull}$ 的壳内；向外最多多一层格子 | D3D12 CR 规范的"不确定区域"类比 + 推断 |
| 体素化（格心） | 中心在内才填 | **对薄壳无上界**（可整块消失） | gprMax 文档原文 |
| 体素化（多数表决） | k³ 采样取多数 | 介于上二者之间，**仍无保守保证** | gprMax 文档 |
| 符号判定（GWN） | 自适应求积 | 可按需压到任意精度 | Spainhour & Weiss 2026 摘要 |
| 距离（Newton 投影） | 迭代求最近点 | 二阶收敛但可能陷局部极小 → 需 Bézier clipping/细分剪枝保证全局 | Efremov 2005；推断 |

---

## 6. 问题五：开源实现与许可证清单

**已核实**的许可以仓库 LICENSE/README/API 为准；"未核实"的请自行确认后再用。

| 项目 | 是否走三角化 | 对本项目的价值 | 许可证（核实方式） | 能否复制代码进 MIT 项目 |
|---|---|---|---|---|
| **Rapier**（[GitHub](https://github.com/dimforge/rapier)） | — | 碰撞引擎；`Voxels` 形状的邻接/内部边处理 | **Apache-2.0**（GitHub API `license.spdx_id`） | ✅ 可以（保留声明/变更说明） |
| **parry**（[docs.rs](https://docs.rs/parry3d/latest/parry3d/shape/struct.Voxels.html)） | 其 `voxelization` 模块是 **mesh→voxels** | `Voxels::new(grid_coords)` 可直接吃我们自算的格子；`combine_voxel_states` 解决跨块内部边 | **Apache-2.0**（docs.rs 页面标注） | ✅ 可以 |
| **Sable**（[README](https://raw.githubusercontent.com/ryanhcode/sable/main/README.md)，[DeepWiki: voxel colliders](https://deepwiki.com/ryanhcode/sable/3.2-collision-and-voxel-colliders)） | 本项目的宿主，voxel collider（1/16 子格 AABB） | 目标 API/数据结构 | **Polyform Shield License 1.0.0**（README 明示；**非开源，含竞业限制**） | ❌ **不要复制代码**；只做接口对接与思路参考（DeepWiki 页我这边被 Vercel 校验挡住，未读到内容） |
| **Open CASCADE Technology** | 两者都有：`BRepMesh` 三角化；`BRepClass_*` 参数域/实体分类**不依赖**三角化 | 参数域裁剪点分类、射线/点分类、linear/angular deflection 的成熟语义 | **LGPL-2.1 + OCCT 例外**（[README](https://raw.githubusercontent.com/Open-Cascade-SAS/OCCT/master/README.md)，另有商业许可） | ❌ 源码是 copyleft；**只能吸收思路**，或遵守 LGPL 动态链接条款单独使用 |
| **Coal / HPP-FCL**（[GitHub](https://github.com/coal-library/coal)） | 走 BVH/网格；未见 NURBS 形状支持 | 碰撞库设计参考（`OcTree`、BVH） | **BSD-3-Clause**（[fwd.hh 头部](https://raw.githubusercontent.com/coal-library/coal/devel/include/coal/fwd.hh)，CNRS-LAAS/Inria） | ✅ 可以（GitHub API 显示 "Other"，以文件头 BSD-3 为准） |
| **OpenVDB**（[GitHub](https://github.com/AcademySoftwareFoundation/openvdb)） | `meshToVolume` 基于网格 | 稀疏体素结构与窄带 SDF 的工程范式 | **Apache-2.0**（GitHub API） | ✅ 可以（但算法上是 mesh 路线） |
| **gprMax `STEPtoVoxel`**（[文档](https://docs.gprmax.com/en/latest/inc_STEPtoVoxel.html)） | **是**（OCCT 三角化 + 平面切片填充） | 边界判据的工程样本、薄壳失败模式 | 工具箱源自 **MIT** 的 [STEP-to-gprMax](https://github.com/mahdeeabir1710/STEP-to-gprMax)；但 **gprMax 仓库整体为 GPL-3.0**（GitHub API） | ❌ 作为 gprMax 一部分即为 GPL-3.0；**只吸收思路** |
| **BRL-CAD**（[Voxelize 提案页](https://brl-cad.github.io/wiki/task/Voxelize/)） | 有解析射线投射 NURBS（librt）；voxelize **仅提案** | "光线投射 → 原始体 → 体素"的先例 | GitHub API 标注 **"Other"（NOASSERTION）**；仓库发行包内的 [COPYING](http://svn.code.sf.net/p/brlcad/code/brlcad/tags/rel-7-8-2/COPYING) 为**混合许可**（我未读其条款，请自行核对） | ⚠️ **许可需逐文件核实**；建议只吸收思路 |
| **CGAL** | `Mesh_3` 等基于网格/多面体 | 稳健几何算法参考 | 官方许可页 [cgal.org/license.html](https://www.cgal.org/license.html)；多数包 GPL-3.0-or-later，少数 LGPL（**我未逐个核实**） | ❌ 默认按 GPL 对待 |
| **libigl / V-HACD / binvox** | 网格路线 | 仅作对照 | **未核实**（libigl：MPL-2.0；V-HACD：BSD-3；binvox：有限免费）| ⚠️ 使用前自行核实 |

**论文类来源（无软件许可问题，引用即可）**：Spainhour & Weiss（arXiv 非独占许可，[arXiv:2504.11435](https://arxiv.org/abs/2504.11435)）；PaRas（[SIGGRAPH 2025 日程](https://s2025.conference-schedule.org/presentation/?id=papers_526&sess=sess284)）；中文非网格体素论文（[JCAD](https://www.jcad.cn/en/article/doi/10.3724/SP.J.1089.2024-00070)）；HyBoDT（[doi:10.1115/1.4053202](https://doi.org/10.1115/1.4053202)）。**我没有核实这些论文是否附带开源代码**——若有人放出实现，需重新做许可判断。

---

## 7. 面向实现的建议：从 NURBS 面到 1/16 AABB 体素

下面是我会采用的分步流程。**默认口径：保守（过近似），不穿模优先。**

### 步骤 0：约定与预处理
- 把每个有理面重写为**分段有理 Bézier**（knot insertion / 有理分割），保留齐次控制点 $P_i^w$。得到每片的参数区间 $[u_0,u_1]\times[v_0,v_1]$ 与控制网。
- **判据**：仅用控制网的凸包给出该片的 3D AABB。**这一步零采样、零三角化，且严格包含曲面。**
- 保留裁剪环（pcurve）的有理 Bézier 形式，作为参数域几何。

### 步骤 1：粗网格与候选分派
- 用所有片 AABB 的并集确定要处理的 block 集合；每个 block 内建 16³ 子格。
- 把每片分派到与它 AABB 相交的 block（slab/区间分派）。**判据：AABB 重叠。** 这一步把绝大多数"不可能相交"的面片提前排除，是整个流程的性能来源。

### 步骤 2：面片 × 子格 的保守剔除
- 对每个被分派的面片，与 block 内子格做**包围体（凸包/区间）相交测试**。
- 结果分三类：**必不相交**（丢弃）、**必在内**（可选，用于直接填充内部）、**可能与边界相交**（进入步骤 3）。
- **误差**：只由包围体的紧度决定（细分越深越紧），**没有采样误差**。

### 步骤 3：边界带与歧义格子的符号判定（核心）
按代价从低到高逐级使用，命中即停：

1. **参数域裁剪预过滤（最便宜）**：把格子中心投影到面片的参数域（可用其 AABB 的近邻初值 + 少量 Newton 步），先问"这个 (u,v) 在不在这张面的裁剪域内"。裁剪域外 → 该面对此格没有贡献。参数域点包含用 **nonzero/winding 规则**并做退化处理（Hormann & Agathos 2001 的稳健规则；或 SIGGRAPH 2025 的**有理曲线闭式 GWN**）。
2. **射线求交（确定性高）**：从格子中心发一条固定方向的射线，与所有候选面求交。求交用 **Bézier clipping**（凸包裁剪收缩参数区间）+ Newton 精化，得到 (u,v) 与 t；同时用步骤 3.1 检查 (u,v) 是否在裁剪域内。**用穿越次数奇偶定符号。**
   - **稳健性注意**：射线必须避开"恰好切到曲面/正好穿裁剪边界"的退化情形。做法是固定但"无理"的方向 + 失败时换方向重试 + 用区间算术判定"是否可能退化"。
3. **广义绕数（最稳、最贵）**：上面两步给出不确定结果时，调用 GWN（Spainhour & Weiss 的立体角边界形式 + 自适应求积）。它对**不水密、朝向不一致、裁剪复杂**的模型都给出一致的"内部度"，精度可调。
   - **只对边界带与歧义格调用**；对远离表面的格子用步骤 1/2 的便宜判据即可（论文本身也强调 memoization 摊薄成本）。
4. **不确定时的兜底**：若仍落在阈值带内（$|\text{GWN}-0.5|<\varepsilon$），**按"实"处理**（保守），或在参数域对这一片再细分后重来。

### 步骤 4：三态 → 占位决定
- **确定在内** → 填。
- **确定在外且离任何面 > (h + 2δ)** → 空。
- **边界带（与任何面片的保守包围体相交，或 $|d| \le h$）** → **一律填**（过近似），除非显式开启"贴合优先"模式。
  - 可选降级策略（当 collider 数量成为瓶颈时）：对边界带做 $k^3$ 超采样按**体积占比**决定；这会把保证从"无 false negative"降级为"经验近似"，**需在文档中标注**。
- **厚度保底**：若某个连通实心区域在某一轴上厚度 < h（薄壳），**按 4.5 的教训强制保留至少一层**（gprMax 的做法），否则该特征会整体消失。

### 步骤 5：合并与跨 block 拼接
- 块内**只在并集不变的前提下**合并共面相邻格（沿轴 greedy）。
- 跨 block：**先合并相邻 block 的体素状态（free/internal faces）再生成 collider**，或使用 Rapier 的 `combineVoxelStates` / `propagateVoxelChange` 等价机制，避免内部边造成的抖动与台阶。
- 若 Sable 只接受"每 block 若干 AABB"的形式，则合并只能停在 block 边界内——这种情况下**边界带一律填**的风格反而更安全（相邻块都填，接缝处不会出现半格空腔）。

### 步骤 6：自适应细分的触发条件（可计算）
只在下列任一条件成立时才细分（参数域 knot insertion 或对 block 提高子格分辨率）：
1. **包围体相交但符号不可判**（步骤 3.4 命中）；
2. **裁剪环穿过该子格**（参数域里裁剪曲线的 AABB 与该格的参数像相交）；
3. **近切**：曲面法向与格平面夹角小于阈值，或"该片在参数域的像跨越超过 1 个子格长度"；
4. **曲率**：$\kappa_{\max} > 8\delta/h^2$（用 §5.3 的数：$h=1/16$、$\delta=h/4$ 时阈值 ≈ 32/block）。**对 1/16 分辨率，这条几乎不会触发——这正好说明不需要做复杂的曲率自适应。**

细分停止条件：$\delta_{chord} \le \delta_{target}=h/4$（用 §5.1 的二阶导界），或参数区间长度小于模型容差。

### 误差账本（最终交付的保证）
- **不穿模（无 false negative）**：由步骤 3 的保守归类和步骤 4 的"边界带一律填"保证，前提是包围体严格包含曲面、符号判定无假阴性。
- **过近似量**：向外最多多出 **1 个子格（h = 1/16 block ≈ 0.0625）** 的一层壳，加上包围体松紧误差 $2\delta_{hull}$；取 $\delta_{hull} \le h/4$ 时，总向外误差 $\lesssim 1.5h \approx 0.094$ block。
- **几何偏差**（把曲面当成 AABB 并集的边界）：在格子内由 AABB 量化给出，最大约半格对角 $\approx 0.054$ block；法向方向误差由阶梯化决定，最坏约 45°（格子转角处），典型接触法向误差 $\approx \arctan(h/R)$。
- **格心/占比模式**：**放弃上述保证**，薄壳可能消失（上界不存在）。

---

## 8. 我认为还需要实测/精读确认的点

1. **精读** [arXiv:2504.11435](https://arxiv.org/abs/2504.11435)（GWN for trimmed NURBS）：它的自适应求积单点成本、能否给出**误差界**（而不只是"高精度"）、近表面处的奇异处理，以及作者是否放出了实现。这直接决定它能不能当步骤 3.3 的主力。
2. **精读**中文的《面向立体光刻的有理曲面非网格化体素生成方法》——标题显示它就是"非网格化体素生成"，很可能是本需求最贴近的已发表方案。
3. **确认 Sable 的 voxel collider 真实 API**：是否暴露跨 block 的邻接合并；`combineVoxelStates` 是否可用。这决定步骤 5 的形态。（DeepWiki 页面我这边被挡，需要人工看。）
4. **OCCT 的 `BRepClass3d_SolidExplorer` 内部是否走三角化**：如果它完全解析，OCCT 可以作为步骤 3.2 的参考实现（**但 LGPL，只能看思路**）。
5. **实测 1/16 下的两类误差量级**：(a) 保守填充导致的"悬空感"是否可接受；(b) 阶梯法向对摩擦/弹跳手感的影响。
6. **确定"曲率阈值 ≈32/block"是否需要用真实模型验证**：这是我从公式代入的结论，如果模型里有大量小半径圆角（如 1/32 block 级的倒角），结论会变。

---

## 附：本文引用来源一览（含许可判断）

**论文 / 规范**
- Spainhour & Weiss, *Robust Containment Queries over Collections of Trimmed NURBS Surfaces via Generalized Winding Numbers*, ACM TOG 45(3) 2026 — [arXiv:2504.11435](https://arxiv.org/abs/2504.11435) / [doi:10.1145/3797957](https://doi.org/10.1145/3797957) / [OSTI](https://www.osti.gov/biblio/3364505) — 论文，非软件许可；未核实是否附代码。
- Liu, Liu, Fu, *Closed-form GWN of Rational Parametric Curves for Robust Containment Queries*, SIGGRAPH 2025 — [日程页](https://s2025.conference-schedule.org/presentation/?id=papers_744&sess=sess112)。
- Wang & Chen, *PaRas: A Rasterizer for Large-Scale Parametric Surfaces*, SIGGRAPH 2025 — [日程页](https://s2025.conference-schedule.org/presentation/?id=papers_526&sess=sess284) / [PDF](http://staff.ustc.edu.cn/~renjiec/papers/paras.pdf)。
- 《面向立体光刻的有理曲面非网格化体素生成方法》/ *Meshless Voxel Generation Method of Rational Surfaces Towards Stereolithography* — [doi:10.3724/SP.J.1089.2024-00070](https://www.jcad.cn/en/article/doi/10.3724/SP.J.1089.2024-00070)（仅题录）。
- *HyBoDT: Hybrid Bounded Distance Transforms of Trimmed NURBS Models*, ASME JCISE 2021 — [doi:10.1115/1.4053202](https://doi.org/10.1115/1.4053202)（仅题录）。
- Jacobson et al., GWN inside-outside — [doi:10.1145/2461912.2461916](https://doi.org/10.1145/2461912.2461916)；Barill et al., Fast winding numbers — [doi:10.1145/3197517.3201337](https://doi.org/10.1145/3197517.3201337)。
- Nishita et al. 1990 — [doi:10.1145/97880.97916](https://doi.org/10.1145/97880.97916)；Martin/Cohen/Fish 2000 — [doi:10.1080/10867651.2000.10487519](https://doi.org/10.1080/10867651.2000.10487519)；Efremov et al. 2005 — [doi:10.1145/1090122.1090144](https://doi.org/10.1145/1090122.1090144)；Sloup & Havran 2021 — [doi:10.1111/cgf.14410](https://doi.org/10.1111/cgf.14410)。
- Schollmeyer & Fröhlich 2009 — [doi:10.1145/1531326.1531353](https://doi.org/10.1145/1531326.1531353)；2019 — [doi:10.1109/TVCG.2018.2814987](https://doi.org/10.1109/TVCG.2018.2814987)。
- Sederberg et al., Watertight trimmed NURBS 2008 — [doi:10.1145/1399504.1360678](https://doi.org/10.1145/1399504.1360678)。
- Marussig & Hughes, Trimming in IGA review 2017 — [doi:10.1007/s11831-017-9220-9](https://doi.org/10.1007/s11831-017-9220-9)。
- Hormann & Agathos, Point in polygon 2001 — [doi:10.1016/S0925-7721(01)00012-8](https://doi.org/10.1016/S0925-7721(01)00012-8)。
- Filip, Magedson, Markot 1986 — [zbMATH](https://zbmath.org/pdf/04028719.pdf)；Sheng & Hirsch 1992 — [doi:10.1016/0010-4485(92)90011-X](https://doi.org/10.1016/0010-4485(92)90011-X)；Piegl & Richard 1995 — [doi:10.1016/0010-4485(95)90749-6](https://doi.org/10.1016/0010-4485(95)90749-6)。
- Cohen-Or & Kaufman, Fundamentals of Surface Voxelization 1995 — [doi:10.1006/gmip.1995.1039](https://doi.org/10.1006/gmip.1995.1039)；Schwarz & Seidel 2010 — [doi:10.1145/1882261.1866201](https://doi.org/10.1145/1882261.1866201)。
- Hu, Patrikalakis, Ye, Robust interval solid modelling II — [doi:10.1016/0010-4485(96)00014-0](https://doi.org/10.1016/0010-4485(96)00014-0)。
- Upreti et al., Signed algebraic level sets on NURBS — [doi:10.1016/j.cad.2016.09.006](https://doi.org/10.1016/j.cad.2016.09.006)。
- Gunderman, Weiss, Evans, Spectral Mesh-Free Quadrature — [doi:10.1016/j.cad.2020.102944](https://doi.org/10.1016/j.cad.2020.102944)；Ueda, Mean normal vector — [doi:10.1016/0167-8396(95)00038-0](https://doi.org/10.1016/0167-8396(95)00038-0)。
- Microsoft, *Direct3D 12 Conservative Rasterization* — [learn.microsoft.com](https://learn.microsoft.com/en-us/windows/win32/direct3d12/conservative-rasterization)（规范文档）。
- OCCT: [BRepClass_FaceClassifier](https://occt3d.com/dev/doc/refman/html/class_b_rep_class___face_classifier.html)、[BRepClass3d_SolidClassifier](https://occt3d.com/dev/doc/refman/html/class_b_rep_class3d___solid_classifier.html)、[Mesh 用户指南](https://occt3d.com/dev/doc/overview/html/occt_user_guides__mesh.html)、[README/许可](https://raw.githubusercontent.com/Open-Cascade-SAS/OCCT/master/README.md) — **LGPL-2.1 + OCCT 例外**。
- gprMax STEPtoVoxel — [docs.gprmax.com](https://docs.gprmax.com/en/latest/inc_STEPtoVoxel.html)；gprMax 仓库 **GPL-3.0**。
- BRL-CAD Voxelize 提案 — [wiki](https://brl-cad.github.io/wiki/task/Voxelize/) — 许可 **NOASSERTION/混合**，需逐文件核实。
- parry `Voxels` — [docs.rs](https://docs.rs/parry3d/latest/parry3d/shape/struct.Voxels.html) — **Apache-2.0**；Rapier CHANGELOG — [链接](https://raw.githubusercontent.com/dimforge/rapier.js/refs/heads/master/CHANGELOG.md)。
- Sable README（Polyform Shield 1.0.0） — [链接](https://raw.githubusercontent.com/ryanhcode/sable/main/README.md)；Sable voxel collider（未读到） — [DeepWiki](https://deepwiki.com/ryanhcode/sable/3.2-collision-and-voxel-colliders)。
- Coal — [GitHub](https://github.com/coal-library/coal) / [fwd.hh 许可头](https://raw.githubusercontent.com/coal-library/coal/devel/include/coal/fwd.hh) — **BSD-3-Clause**。
- OpenVDB — [GitHub](https://github.com/AcademySoftwareFoundation/openvdb) — **Apache-2.0**。
- CGAL 许可页 — [cgal.org/license.html](https://www.cgal.org/license.html)（未逐个核实）。
- Sagitta 公式 — [Wikipedia](https://en.wikipedia.org/wiki/Sagitta_(geometry))。
