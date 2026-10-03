# 图集环境下任意曲面（含 NURBS）的通用无缝贴图 —— 多轮网络调研报告

> 调研目标：Minecraft 1.21.1 / NeoForge mod（MIT），贴图来自原版方块图集（block atlas），
> UV 超出 [0,1] 会串到邻块贴图，因此**不能靠纹理 REPEAT 平铺**；
> 项目已有 NURBS 曲面核心（可求值、解析法线、按弦高自适应细分），
> 渲染走 Minecraft `RenderType` + 原版多重缓冲/着色器体系。
>
> 本文中：
> - **【来源】** = 有可查证链接支持的结论；
> - **【推断】** = 我基于来源做的推理，未经直接验证；
> - **【待实测】** = 需要在本项目里跑一遍才能确认的工程细节。
>
> 网络内容只作为资料引用，未当作指令执行。

---

## 0. 结论速览（先看这段）

| 路线 | 可行性 | 代价 | 结论 |
|---|---|---|---|
| A. 把方块贴图拷成**独立 REPEAT 纹理** + 自定义 RenderType | 高 | 每张纹理 1 个 GL texture + 1 个 draw call | **推荐主路线** |
| B. 保留图集，**自定义着色器手写 wrap**（传未回绕 UV，fragment 里 `fract` + `textureGrad`） | 高 | 需要自定义 core shader | **推荐并行路线 / 兜底** |
| C. `GL_TEXTURE_2D_ARRAY` | 低（1.21.1） | 要绕过 `GlStateManager`，状态缓存不同步 | **不推荐** |
| D. 几何切割（面片不跨贴图边界） | 中 | 顶点数爆炸、破坏自适应细分 | 只作为局部手段 |
| E. 预先把贴图 N×N 平铺成一张大图再打包 | 低 | 内存/分辨率固定，周期写死 | 只适合特例 |

---

## 1. CAD 业界的"贴图映射类型"到底有哪几种

### 1.1 各家文档的映射类型清单【来源】

**3ds Max（`UVWmap` 修改器，`maptype` 0–6）** —— 这份文档的措辞最完整，直接给了每种映射的畸变位置：

- `0 Planar`：从单一平面投影，"像一个幻灯机"。**只在物体一侧需要贴图时有用**；斜向映射多个面或对称物体的两个面也有用。
- `1 Cylindrical`：从圆柱投影包裹物体。"除非使用无缝贴图，**位图边缘相接处会看到接缝**"；适合近似圆柱的物体。
- `2 Spherical`：从球投影。"**你会看到一条接缝，以及在球的两极处（位图边缘汇聚处）的映射奇点**"；适合近似球体的物体。
- `3 Shrink Wrap`：用球面映射但截掉四角、全部汇到**单个极点**，"只有一个奇点"，用来**隐藏映射奇点**。
- `4 Box`：从立方体六面投影，每面是一个平面映射；"**每个面从法线最接近它自己的那个盒面映射**"（即按法线选轴）。
- `5 Face`：给物体的**每个面**贴一份完整贴图拷贝。
- `6 XYZ to UVW`：把 3D 程序化坐标映到 UVW，"把程序纹理粘到表面上，表面拉伸则程序贴图一起拉伸"。

参数：`length/width/height`（gizmo 尺寸）、`utile/vtile/wtile`（三轴平铺次数）、`uflip/vflip/wflip`、`axis`（gizmo 哪个轴对齐物体 Z）、`gizmo` 的 position/rotation/scale、`realWorldMapSize`（真实世界尺寸）。
来源：<https://help.autodesk.com/cloudhelp/2020/ENU/3DSMax-MAXScript/files/GUID-68BA1E0B-8A81-476C-9E41-0C15FE911872.htm>

**Autodesk Navisworks `Material Mapping`**（面向 CAD 导入模型）：
- 概念表述很好：*"每种 texture space 选项都在物件外罩一个假想的边界几何体，并把贴图尽可能'收缩包裹（shrink-wrap）'到下面的几何上"*；程序会**自动从四种 texture space 里挑最合适的**（Box / Planar / Cylindrical / Spherical），也可手改。
- Planar：*"从投影方向看不变形，但投到曲面并从侧面看会变形；图像不会缩放到物体尺寸"*，最常用于平面。
- Box："**图像在物体的每一侧重复**"。
- Spherical："**贴图上下边缘被压缩到球体的'南北极'一个点上**"。
- Cylindrical："**左右边缘包在一起，但上下边缘不会**；图像高度沿圆柱轴缩放"。
- 另有 `Explicit mapping`：几何自带 UV 时**默认优先用几何自带的显式 UV**，而不是 Planar 之类的空间映射。
来源：<https://help.autodesk.com/cloudhelp/2023/ENU/Navisworks/files/GUID-44F3A8BD-CDE2-492C-9D3D-2EA334EBB580.htm>

**PTC Creo Render Studio**（德文页，正文可抓）：
- `Planar`："用于**平坦、不复杂**的对象和曲面……基于投影，可用于把材质分配到多个面"。
- `Zylindrisch`："用于**圆润**的对象和曲面……把坐标系定向成 **Y 轴与圆柱轴重合**效果更准"。
- `Sphärisch`："用于**球形**对象和曲面……把 Y 轴与球轴重合效果更准"。
- `Rechteck (Box)`："用于想从**六个面**贴图的对象。**U 值是 X 坐标，V 值是 Y 坐标，Z 坐标被忽略**。在一个点上具有**最大 U、V 值的那个盒侧面**被选来做平面投影。"
- `Parametrisch (Parametric)`：*"按曲面的 **u-v 坐标**进行映射……打开网格线就能轻易判断某曲面是否适合用 Parametric。**如果曲面网格线正常，Parametric 就是合适的映射方式**"*；会**给每个面各贴一份完整的材质纹理副本**，多选面时每面独立。
来源：<http://support.ptc.com/help/creo/creo_render/r6.0/german/render/photorender/fundamentals/fund_four_sub/Types_of_Mapping.html>

**Rhino**（文档最全，且有"Surface"这一档）：
- 默认无通道时用 **Surface mapping**："**把贴图拉伸覆盖到物体上**"，"默认的 surface mapping 方法**用每个面自身的控制点结构**来定向棋盘格"，并明确指出 **"注意棋盘纹理在多曲面（polysurface）的接缝两侧并不匹配"**。
- 可选：Planar / Box / Box(sides only) / Spherical / Cylindrical / Capped cylindrical / **Custom**（用另一个 surface/mesh 当映射体）。
- 关键参数：`XYZ position / rotation / size`（含 `Lock`、`1,1,1`、`x=y=z`、"拟合到对象"）、**`UVW offset`**、**`UVW repeat`**（三方向重复次数，可 Lock 联动）、`UVW rotation`。
- Custom mapping 的投影方式有 **Closest point** 和 **Ray**（沿法线打射线）两种。
来源（英文）：<https://docs.mcneel.com/rhino/5/help/en-us/properties/texturemapping.htm>
来源（繁中，正文可抓）：<http://docs.mcneel.com/rhino/mac/help/zh-tw/properties/texturemapping.htm>

**SOLIDWORKS（主程序 Appearances → Mapping 选项卡）**：
官方帮助页存在但**被 Akamai 拒绝访问（HTTP 403）**，我抓不到正文，只能确认页面存在：
<https://help.solidworks.com/2026/English/SolidWorks/Sldworks/r_appearances_pm_mapping_tab.htm>
旧版：<https://help.solidworks.com/2011/English/SolidWorks/Sldworks/LegacyHelp/Sldworks/PhotoWorks/HIDD_DVE_MATERIALS_MAPPING.htm>
镜像：<https://my.solidworks.com/reader/onlinehelp/2021%252Fenglish%252Fswconnected%252Fswdotworks%252Fhidd_dve_materials_mapping.htm/>

**第三方来源给出了 SOLIDWORKS Visualize 的映射模式清单**【来源】：
- `UV`：用模型几何定义映射，沿设计曲线走，"有时什么都看不见"；
- `Box`：适合长方体；`Planar`：单一方向投影；`Spherical`：球形；**`Radial`**：圆盘/放射状；`Cylindrical`：管状；**`Perspective`**：从当前相机视角投影（可 Fit to view 更新）。
- 尺寸控制三档（很关键）：**`Fit to Part`**（拟合到零件包围盒，默认）/ **`Set to World Scale`**（世界尺度，"让同一贴图在多处渲染时尺寸一致"）/ `Manipulate Texture`（手动拖拽）。
来源：<https://www.innova-systems.co.uk/how-to-map-scale-textures-in-solidworks-visualize/>

**【推断】** 把以上拉齐，CAD 界的映射类型其实就是三族：
1. **空间投影族**（Planar / Box / Cylindrical / Spherical / Radial / Perspective / Shrink-wrap）——不看曲面自身参数，只看世界/局部/相机坐标。参数永远是"一个定位定向的 gizmo（位置/旋转/尺寸）+ 三轴平铺次数 + 轴向选择"。
2. **几何自带参数族**（Rhino `Surface`、Creo `Parametric`、Navisworks `Explicit`、SW Visualize `UV`）——直接吃 (u,v) 参数域。**这三家文档都同时承认它的核心缺陷：跨面/跨接缝不连续、畸变随控制点分布而变。** Creo 甚至给出可见的判据（"网格线正常就能用"）。
3. **重映射/自定义族**（Rhino `Custom`、`Unwrap`、`UVEditor`；3ds Max `Face`、`XYZ to UVW`）——用一个参照体或展开图去"重新参数化"。

对项目的直接启发：**CAD 把"尺度统一"这件事和"映射类型"分开了**——`Set to World Scale` / `realWorldMapSize` / `UVW repeat` 是独立于映射类型的参数。我们也可以这样分层：映射类型只负责"怎么把 3D 点变成 UV 方向"，尺度由一个独立的"世界尺寸→UV 缩放"常量管住。

---

## 2. 弧长重参数化：曲线怎么做，曲面怎么做

### 2.1 为什么 NURBS 的自然参数不是弧长【来源】

B-spline/NURBS 的参数区间是**节点向量**决定的，`u` 的均匀步长**不对应**曲线上均匀的弧长步长。弧长函数

```
s(u) = ∫_{u0}^{u} |C'(t)| dt
```

一般**没有解析原函数**，必须数值积分再求逆。这是标准教科书内容：Piegl & Tiller, *The NURBS Book*, 2nd ed., Springer, 1997（Springer 官方书目页见来源清单）。

**【推断】标准做法（教科书级，广泛实现）**：
1. 用 **Gauss–Legendre 求积**数值算 `s(u)`（被积函数 `|C'(t)|` 是多项式/有理函数，GL 求积对它有很高精度）；
2. 用 **Newton–Raphson** 对 `s(u) - s_target = 0` 求逆（`s'(u) = |C'(u)|` 已知，导数好写，收敛快）；
3. 得到 `u = s^{-1}(σ)` 后，把 `u` 换成归一化弧长 `σ/L` 就是弧长参数化。

这条链路的每一个零件都有公开实现可对照。例如一个 MIT 许可的 .NET NURBS 库明确把 *"curve length, **arc-length parameterization/sampling**, closest points, curvature, normals"* 列为核心特性：
- NURBS Sharp（**MIT License**）：<https://nyarurato.github.io/NurbsSharp/index.html>
- 同类参考：geomdl / NURBS-Python <https://github.com/orbingol/NURBS-Python>
- Kodatuno <http://www-mm.hm.t.kanazawa-u.ac.jp/research/kodatuno/>

### 2.2 弧长参数化对贴图意味着什么【来源 + 推断】

**【来源】** 贴图拉伸的量化标准是 **stretch metric（L2 拉伸度量）**，来自 Sander, Snyder, Gortler, Hoppe, *Texture Mapping Progressive Meshes*, SIGGRAPH 2001（DOI `10.1145/383259.383307`）。它把每个三角形上的纹理拉伸定义为参数化映射 Jacobian 的奇异值 σ1 ≥ σ2 的函数（拉伸 = √(面积加权后的 σ1²+σ2²)/2 形式的 L2 范数）。同组的 *Signal-Specialized Parameterization*（Sander et al., 2002）和 Sander 的 *stretch metric* 是这一族的标准术语。

**【推断】** 对**曲线**而言，"均匀弧长参数化"就是让 |dC/dσ| ≡ 1，此时 1D 情形的 stretch metric 恰好取到理论最小值 1（无拉伸）。所以弧长重参数化不是为了"更好看"，而是**把 1D 拉伸精确降到 0**。
- 代价：`s(u)` 是数值的，UV 的求值链变成"参数 → 数值求逆 → 弧长"，比直接线性映射贵；可以**预先在细分顶点上算好 UV** 一次性摊掉。
- 还有一层误差：**弦长 ≠ 弧长**。用多边形近似的弦长和代替真弧长在细分区段足够密时误差 O(h²)。这正好和项目已有的"按弦高容差自适应细分"配套：细分越密，弦长近似越好。

### 2.3 对**曲面**的推广：没有唯一的"正确"答案【来源 + 推断】

这是本次调研里最需要说清楚的一点：**曲线有唯一弧长参数化，曲面没有。** 因为曲面上没有单参数的自然度量方向。

公开文献里能站得住的几条推广：

**(a) 张量积 / 等参曲线重参数化（最便宜的工程做法）【推断，有间接来源】**
分别沿 u 方向和 v 方向，对一族等参曲线做弧长重参数化，得到 `U(u)`、`V(v)`，再取张量积。这是 B-spline 曲面的天然结构（节点向量本身就是张量积的），工程上只是"跑 2N 次曲线弧长重参数化"。
- 优点：便宜、可实现、对**接近可展/接近平面**的曲面几乎能把畸变压到很小。
- 局限：当 u 向和 v 向的尺度耦合（扭曲面、鞍面）时，张量积无法同时正化两个方向，残下的是**切变（shear）**畸变，不是尺度畸变。**【推断】** 对"贴 16×16 方块贴图"这种目标，切变通常比尺度不统一更能接受（看起来只是花纹斜了，不是被压扁）。

**(b) 面积重参数化 / 等面积参数化**
目标是让 |∂S/∂u × ∂S/∂v| ≡ const。
- 【来源】"area-preserving parameterization"在 CAD/NURBS 圈有专门文献，例如 *Improving the area-preserving parameterization of rational Bézier surfaces by rational bilinear transformation*（ScienceDirect，见来源清单）。这类工作的措辞是"面积保持"而不是"无缝"。
- 【来源】Boundary First Flattening（BFF）在工程实现上直接**可视化面积/角度畸变**：`Conformal Distortion` 模式报"平均与最大 **quasi-conformal distortion**（1 表示无畸变）"，`Area Distortion` 模式报"平均与最大 **log conformal factor**（0 表示无畸变）"。BFF 还明确写着：*"在数学上保证这个面积畸变是最低的……不可能再用别的 flattening 软件降低面积畸变。但可以通过加切割和 cone singularity 来降低。"*
  <https://github.com/GeometryCollective/boundary-first-flattening>（**MIT License**）
- 【来源】BFF 论文：Sawhney & Crane, *Boundary First Flattening*, ACM TOG，<https://arxiv.org/abs/1704.06873>

**(c) 畸变的理论下界：不可能全消【来源】**
BFF 文档写得很直白：*"一般来说，不可能把曲面完美展平。**某个地方必然存在角度或面积的畸变。**"*
**【推断】** 对我们的含义是：不要追求"零畸变"，要追求"畸变可控 + 接缝不可见 + 尺度大致统一"。这是一个可交付的目标，而"完全无缝无畸变"不是。

**(d) 曲面拉伸的度量【来源】**
用参数化 Jacobian 的奇异值（σ1, σ2）表达：等比缩放 = σ1=σ2；σ1/σ2 是角度/共形畸变；σ1σ2 是面积畸变。这是 LSCM / ABF / stretch metric 一族的通用语言。
- 【来源】Sheffer, Praum, Rose, *Mesh Parameterization Methods and Their Applications*, 2006；以及 Floater & Hormann, *Surface Parameterization: A Tutorial and Survey*, 2005（两者都是标准综述）。
- 参数化教程页（Hormann 维护，含 *Planar, Non-Linear Methods* 讲义）：<https://www.inf.usi.ch/hormann/parameterization/NonLinear2008.pdf>

### 2.4 一句话回答"重参数化后畸变能降到什么程度"

- **曲线：能降到 0**（弧长参数化，|dC/dσ|≡1）。【推断，基于定义】
- **张量积曲面：能消掉尺度畸变，消不掉切变。** 对可展面/近平面近似为 0；对强扭曲面残下切变。【推断】
- **一般曲面：存在不可消除的下界**，这是共形/等距参数化的基本事实，BFF 文档与综述都这么讲。【来源】

---

## 3. 图集不能 repeat 的根本解法 —— 逐条评估

### 3.0 先确认问题的性质（有官方 bug 记录）【来源】

Mojang 官方 issue 跟踪器里有两条直接相关的：

- **[MC-156088] Models extending beyond 0 or 16 with no specified UV will cause atlas bleeding** —— <https://mojira.dev/MC-156088>
  正文：*"当贴图被调用但它不存在时……**图集里随机别的方块纹理被采样**，留下一堆乱七八糟的贴图。"*
  并且报告者写明了**期望行为**：
  > *"贴图应该**自己重复**（例如石头贴图重复 9 次，而不是 1 次 + 8 张别的）。**这是首选的解决方案**，因为这样就能在不复制元素、不让贴图包含自己多次的情况下创建可重复的贴图。"*
  该 issue 状态 **Open**，影响版本明确列出 **1.21.1**（以及 1.14.3、1.21.4、1.21.6、1.21.10…）。
- **[MC-236285] Model UV is not limited to 0-16, bleeding texture atlas** —— <https://mojira.dev/MC-236285>（Resolved）

**结论【来源】**：Vanilla **没有**实现 UV 环绕采样；UV 折进子矩形（你们的 `frac`）是当前唯一的 vanilla 语义。Mojang 自己认为"让它 repeat"才是正解，但没做。

**关于"为什么会串色"的机制【来源 + 推断】**：图集是一张 GL 纹理，wrap mode 是 GL sampler state，作用于**整张图集**而不是某个 sprite。所以只要 UV 出界，它就在图集内部绕行/夹取，落到邻居 sprite 上。**【推断】** vanilla 对图集设了 clamp（否则出界会绕到图集另一头），但对 sprite 之间的边界无效——这正是 MC-156088 描述的"邻块贴图"现象。

---

### 3.1 路线 A：把某张方块贴图拷进自己的 REPEAT 独立纹理

#### 3.1.1 在 1.21.1 里可行吗？——**可行，而且比我预期的更"顺"**

我核对了 1.21.1 的映射（mappings.dev，本质是官方 Mojang 映射的公开镜像）：

1. **`SimpleTexture` 明确带 `clamp` 参数**【来源】
   `net.minecraft.client.renderer.texture.SimpleTexture` 有私有方法
   `upload(NativeImage image, boolean blur, boolean clamp)`（Yarn 参数名就是 `clamp`）。
   <https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/SimpleTexture.html>
   **【推断】**：`clamp=false` 时不会设置 `GL_CLAMP_TO_EDGE`；而 OpenGL 里 `GL_TEXTURE_WRAP_S/T` 的**初始值就是 `GL_REPEAT`**（见 <https://docs.gl/gl4/glTexParameter> 中 `GL_TEXTURE_WRAP_S` 条目）。
   → **用 `SimpleTexture(ResourceLocation)` 从资源包路径加载一张单张方块贴图（且不写 `clamp:true` 的 mcmeta），它天然就是 REPEAT wrap。**

2. **`AbstractTexture` 在 1.21.1 里没有 wrap 设置器**【来源】
   只有 `setFilter(boolean bilinear, boolean mipmap)`、`getId()`、`bind()`、`load()`、`close()`。
   <https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/AbstractTexture.html>
   → 【推断】MC 不会主动改写单张纹理的 wrap；REPEAT 能保持住。

3. **`.mcmeta` 的 `clamp` 字段确实存在**【来源】
   NeoForge 官方文档给了格式：`{"blur": true, "clamp": true, "gui": {...}, "animation": {...}}`。
   <https://docs.neoforged.net/docs/1.21.1/resources/client/textures/>
   **注意**：该页有一句 *"Currently specified by the codec, but unused otherwise both in the files and in code"*，与我在 (1) 里看到的 `SimpleTexture.upload(image, blur, clamp)` 矛盾。**【待实测】**：需要在游戏里确认单张纹理的 `clamp` 到底有没有生效（写一个 `clamp:true` 的 mcmeta 看是否有变化）。

4. **自定义 `RenderType` 可以绑定任意 ResourceLocation 纹理**【来源】
   `RenderStateShard$TextureStateShard` 的公开构造函数是 **`(ResourceLocation id, boolean blur, boolean mipmap)`**。
   <https://mappings.dev/1.21.1/net/minecraft/client/renderer/RenderStateShard$TextureStateShard.html>
   而项目现在用的是 `RenderType.entityCutoutNoCull(InventoryMenu.BLOCK_ATLAS)`（见 `src/main/java/com/ovo/sablestopnow/client/ModRenderTypes.java:92-93`），也就是把图集 RL 传给了同一个 `TextureStateShard` 机制。**【推断】** 换成自己的 RL 是同一条路，风险很低。

5. **兜底手段**：即使 REPEAT 没生效，也可以在 `bind()` 之后直接用
   `GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL11.GL_REPEAT)`（`_texParameter(int,int,int)` 在 1.21.1 存在）。
   <https://mappings.dev/1.21.1/com/mojang/blaze3d/platform/GlStateManager.html>

#### 3.1.2 代价【来源 + 推断】

- **每种方块一张 GL 纹理**：原版 16×16 RGBA ≈ 1 KB + mipmap，**内存可忽略**（几百种也就几 MB）。真正的成本是**纹理对象数量与切换次数**，不是字节数。
- **批处理被打断**：`MultiBufferSource.BufferSource` 按 `RenderType` 聚合，**不同 `TextureStateShard` = 不同 RenderType = 不同 draw call**。【推断】所以"一个面用一种方块贴图"会退化成一次一个 draw call——但**如果整张曲面上同一种方块贴图占多数（很常见）**，draw call 数就等于"用到的方块材质种类数"，仍然很小。
- **缓存策略**【推断】：`Map<ResourceLocation, RenderType>` 懒加载 + LRU；只对"真正被用在曲面上的方块类型"建纹理。`ResourceLocation` → 纹理的注册要走 `TextureManager`；也可以自己 `NativeImage.read(...)` + `GlStateManager._texImage2D` 直接上传（更可控，但要自己管 mipmap 和生命周期）。
- **动态纹理/重载**：资源包重载时要重建。**【待实测】** `AbstractTexture.registerTexture(...)`（1.21.1 有这个方法）看起来正是为这种"按 RL 自动注册/重载"准备的。
- **mipmap 反而变好了**【推断】：图集里 mipmap 会把邻块混进来（这是 atlas bleeding 的另一面），独立纹理的 mipmap 是干净的。这算额外收益。

**结论**：路线 A **没有原理性障碍**，是 1.21.1 上最直接、最可控的方案。

---

### 3.2 路线 B：保留图集，**在着色器里手写 wrap**（最重要的一个发现）

**【推断，但逻辑链很短】** 你们现在的问题描述是：

> "一个面如果跨越多张贴图宽度，**四个角的 `frac` 各自回绕**、插值出垃圾"

这句话说明 **`frac` 是在顶点阶段（或 CPU 阶段）做的**，然后把折回的 UV 交给光栅器插值。**这就是 bug 的根因**：插值必须发生在"未回绕的连续 UV 空间"里，回绕只能发生在**采样前的最后一刻**。

正确做法：
1. 顶点阶段输出**未回绕的实数 UV**（单位 = 方块贴图宽度的倍数，比如 x 从 0 到 7.3）；
2. 顶点属性里带上"这张贴图在图集里的子矩形"（`u0,v0,u1,v1`）作为常量；
3. **fragment shader** 里做：
   `uv_wrapped = fract(uv)` → 再映射到子矩形 → 采样。
4. **mipmap 必须手算导数**：`fract()` 在回绕处导数会跳变，`texture()` 自动求导会在接缝处选错 mip → 一条细线。
   必须用 **`textureGrad(tex, uv_wrapped_mapped, dFdx(uv_unwrapped)*scale, dFdy(uv_unwrapped)*scale)`**，即用**未回绕 UV 的导数**。这是"shader 里手写 wrap"的标准坑，GLSL 规范里 `textureGrad` 就是为此存在的（<https://docs.gl/gl4/textureGrad>）。

**可行性**【来源】：
- 1.21.1 支持自定义 core shader + `ShaderInstance`：NeoForge 的 `RegisterShadersEvent` + `shaders/core/*.json` + `.vsh/.fsh`。
  <https://pr-61.neoforged-docs-previews.pages.dev/docs/rendering/modelextensions/rendertypes/>
  （以及中文实操指南 <https://doc.ideafox.top/docs/a-dai/mods-dev/render/render_guide>，其 §10 详细介绍了 `ShaderInstance` 与默认 uniform 注入）
- 顶点格式需要能携带足够信息：可以用 `NEW_ENTITY` 的 UV0 存"未回绕 UV"，再用一个额外属性（自定义 `VertexFormat`）存子矩形；或者把子矩形压进 `overlay`/`uv2`（不推荐，语义混乱）。**【待实测】** 自定义 `VertexFormat` + `VertexFormatElement` 在 1.21.1 是否与 `RenderType.create` 完全兼容。

**代价**：
- 需要自己写并维护一个 core shader（含雾、光照、alpha 裁剪、overlay 等 vanilla 语义的复刻或精简）。
- 图集 sprite 边界的**双线性过滤**问题：`fract(uv)` 落在 sprite 边界 0.5 像素内时，双线性会采到邻居。【待实测】MC 的 stitcher 对 sprite 是否有 padding/extrusion —— 若有，这个问题基本不存在；若无，需要在子矩形里内缩半像素，视觉上会略微缩小平铺周期。
- 无法复用 vanilla 的 `RENDERTYPE_ENTITY_CUTOUT_NO_CULL_SHADER`，等于自己扛一套状态。

**但它的优势很大**：**不需要给每种方块建独立 GL 纹理，draw call 不增加，图集照用**。

---

### 3.3 路线 C：`GL_TEXTURE_2D_ARRAY` —— 1.21.1 上**不推荐**

用户原话提到"1.21.5+ 才有新的 GpuTexture 体系"，我做了核对：

- **`com.mojang.blaze3d.textures.GpuTexture` 确实存在，但只在 1.21.11 映射里有**【来源】
  <https://mappings.dev/1.21.11/com/mojang/blaze3d/textures/GpuTexture.html>
  **【推断】** 这是 1.21.5+ 引入的渲染抽象层；1.21.1 还是老的 `GlStateManager` 直连 GL 的写法。
- **1.21.1 的 `GlStateManager` 没有 `_texImage3D`**【来源】
  我在 1.21.1 `GlStateManager` 的完整成员表里全文检索 `texImage3D` / `texSubImage3D` / `GL_TEXTURE_2D_ARRAY`，**零命中**；只有 `_texImage2D`、`_texSubImage2D`、`_texParameter`、`_bindTexture(int)`、`_activeTexture`、`_genTexture`。
  <https://mappings.dev/1.21.1/com/mojang/blaze3d/platform/GlStateManager.html>
- **`GlStateManager$TextureState` 只跟踪一个 `binding` 字段**（单张 2D 绑定），没有"按 target 分别记账"的能力。
  <https://mappings.dev/1.21.1/com/mojang/blaze3d/platform/GlStateManager$TextureState.html>
  → 【推断】如果你绕过它直接 `GL30.glBindTexture(GL_TEXTURE_2D_ARRAY, id)`，MC 的 `GlStateManager` 缓存会与实际 GL 状态不一致，后续 vanilla 渲染可能绑错纹理。要么每次手动 `glActiveTexture` 后用 `GlStateManager._bindTexture` 恢复，要么承担不确定的状态污染风险。
- **vanilla 没有 `sampler2DArray` 的核心着色器**【推断，基于 1.21.1 未引入 GpuTexture 且 vanilla 未使用 2D array】。要自己写 `ShaderInstance` + `#version 150` 以上的 shader（1.21.1 的 core shader 是 GLSL 150）。

**结论**：技术上不是不可能（LWJGL 的 `GL30` 直接可用），但要在 1.21.1 上**同时**解决"状态缓存不同步 + 自定义 shader + 自建纹理数组 + 与 vanilla 多重缓冲协作"四件事，**收益（把 N 个 draw call 变成 1 个）与风险不成比例**。而且 1.21.5+ 的 `GpuTexture` 体系会把这一层重写，等于投向一个会过期的 API。

**如果非常在意 draw call**：**【推断】** 更好的折中是用一个**"按需合成的小图集"**——把你实际用到的 K 种方块贴图在运行期拼成一张 N×N 的方形网格纹理（每格 16×16，网格间距 = 一个贴图宽度），wrap 设为 REPEAT，网格尺寸取 2 的幂。这样：**一次 draw call 覆盖 K 种材质**，且因为网格是完整周期，`fract` 后落在哪一格都能采到正确的贴图。代价：K 有上限（受最大纹理尺寸与显存限制），且需要一次运行期合成（几十毫秒级，可放在资源重载后）。**这个方案在公开资料里我没找到现成先例，属于推断，需要实测。**

---

### 3.4 路线 D：几何切割（在贴图边界处细分）

**【推断】** 这条路的定义有点含混，有两种理解：

**(a) 每个面片不跨多张"贴图宽度"** —— 这只是把 UV 限制在 [0,1] 附近。代价：一个跨 7.3 个周期的面要被切成 8 片；如果 NURBS 细分本身有几十×几十的网格，切完顶点数会膨胀一个数量级，且切割线是**规则的 UV 整数格点**，与自适应细分的层次结构冲突（要么破坏自适应，要么产生 T-junction）。**局限**：完全没解决"UV 在 [0,1] 内时接缝处的插值问题"——相邻两片各自 `frac` 后，共享边上的 UV 一个是 0.99、一个是 0.01，**缝还在**，除非把 UV 直接写成 1.0 和 1.0（那就是"复制元素"而不是"平铺"，MC-156088 的期望行为明确说了这正是它想避免的）。

**(b) 沿着"贴图边界"切割并让每片恰好是一个贴图周期** —— 这样 UV 恰好是 [0,1]，可以用图集子矩形。**【推断】** 这是**唯一让切割真正成立**的形式，但代价是：把曲面切成与贴图周期对齐的图块，**细分密度被贴图周期绑架**（16 方块像素 = 1 个 MC 方块，所以每个图块 1 方块大，网格密度变成"每方块一个面片"级别），基本等于放弃自适应细分。

**结论**：切割只能作为**局部纠错手段**（比如处理极小的退化面片），不能作为主路线。

---

### 3.5 路线 E：其它思路

1. **重打包成"单张贴图 + REPEAT"**：把要用的方块贴图预先把 N×N 平铺烘焙成一张大图，再当普通纹理用。**【推断】** 只适合"周期固定、分辨率要求低"的特例（例如远处地形）。周期写死后不能随视角缩放，等于放弃了 LOD。
2. **运行期合成小图集**：见 3.3 末段。**【推断】** 值得一试，是 A 和 C 之间的甜点。
3. **`SpriteContents` / 图集的"动态扩充"**：把方块贴图塞进一个**专用图集**（自己建一个 `TextureAtlas`），并且让它在拼图时**每格间距留一个贴图宽度的空白**，从而 `fract` 后总能落在正确格子里。**【推断】** 比路线 E-2 更"正规"但更复杂。
4. **顶点色/额外属性里带"周期索引"**：把 UV 拆成 `(floor(uv), fract(uv))`，用两个属性传输，fragment 里重组。**【推断】** 与路线 B 等价，只是更省顶点属性位。**注意**：`floor` 分量的插值仍会跨面线性变化，需要 `flat` 限定符（GLSL 150 支持 `flat`）。**【待实测】** MC 的 `VertexFormatElement` 是否支持 `flat` 语义——很可能是"不支持"，那么就要用 `noperspective`/自定义方式，风险上升。

---

## 4. 无缝参数化的公开算法：哪些思路值得吸收

### 4.1 问题家族地图【来源】

| 工作 | 年份/出处 | 核心思想 | 链接 |
|---|---|---|---|
| QuadCover | CGF 2007, Kälberer/Nieser/Polthier | **branched covering（分支覆盖）** + 交叉场（cross field）+ 整数格映射：把曲面"展开"到一个多层的覆盖空间里，使每层上的参数化都是规则的 | [Wiley DOI 10.1111/j.1467-8659.2007.01060.x](https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1467-8659.2007.01060.x) · [作者版 PDF](http://www.mi.fu-berlin.de/en/math/groups/ag-geom/publications/db/KNP07-QuadCover.pdf) |
| Periodic Global Parameterization | ACM TOG 2006, Ray/Li/Lévy/Sheffer/Alliez | **周期函数 + 跨接缝的平移/旋转跳变（matching）**：允许参数化在接缝两侧差一个整数格平移，从而"缝不可见" | [ACM DOI 10.1145/1183287.1183297](https://dl.acm.org/doi/10.1145/1183287.1183297) · [HAL](https://in2p3.hal.science/inria-00104853v1) |
| Mixed-Integer Quadrangulation | SIGGRAPH 2009, Bommes/Zimmer/Kobbelt | 把方向场→整数格的问题写成**混合整数优化** | [scholar.archive.org](https://scholar.archive.org/work/mzdkham4rvesxa27df77gtesaa) |
| PolyCube-Maps | SIGGRAPH 2004, Tarini et al. | 用**轴对齐的多立方体**当作参数域，把曲面映到 polycube 的面上；天然六向、天然整数对齐 | [ACM DOI 10.1145/1015706.1015810](https://dl.acm.org/doi/10.1145/1015706.1015810) |
| Instant Field-Aligned Meshes | SIGGRAPH Asia 2015, Jakob/Panozzo et al. | 方向场局部平滑 + 抽取整数格面片；**局部、可扩展、无全局优化** | [项目页](https://igl.ethz.ch/projects/instant-meshes/) |
| Boundary First Flattening | ACM TOG 2018, Sawhney & Crane | 共形展平 + **cone singularity** 降低面积畸变 + **seamless map（消除跨切割的整数平移跳变）** | [arXiv 1704.06873](https://arxiv.org/abs/1704.06873) · [GitHub](https://github.com/GeometryCollective/boundary-first-flattening) |
| Seamless: seam erasure… | SIGGRAPH 2017, Liu/Ferguson/Jacobson/Gingold | 把"接缝"从几何中解耦出去 | [项目页](https://cragl.cs.gmu.edu/seamless/) · [Semantic Scholar](https://www.semanticscholar.org/paper/f01edf7e9134948465ceb95c2feca59ab6f98e36) |
| Seamless Parametrization with Cone and Partial Loop Control | ACM TOG 2023 | cone 与部分环路控制的实用化 | [ACM PDF](https://dl.acm.org/doi/pdf/10.1145/3600087) |
| LSCM | ACM TOG 2002, Lévy et al. | 最小二乘共形映射，**只要两个 pinned 顶点就能解** | 综述见 Floater & Hormann 2005 |
| ABF/ABF++ | ACM TOG 2003/2005, Sheffer & de Sturler | 角度制约束求解，角度畸变极小 | 同上 |

### 4.2 【推断】可以"只抽思路"的四条

**思路 1：接缝允许"整数周期跳变"（Periodic Global Parameterization / Seamless 的核心）**
这条对项目**极其实用**。你们不需要做全局参数化，只需要在**面片/图块接缝处**保证：
> 接缝两侧的 UV 只差一个 (整数, 整数) 的平移。

因为方块贴图是**整数周期可平铺**的（16×16 的 oak_planks 自身在边界上连续），
**UV 差整数 → 视觉上完全相同 → 缝不可见。**
这就是 "seamless" 的全部可操作含义，也是 BFF 里 **`Remove Seams`** 按钮在做的事：BFF 文档说，
cone 角度为 π/2 的整数倍时，跨切割的**网格线方向不变，但会左右平移，产生可见的缝**；按下 `Remove Seams` 可以把平移消掉。<https://github.com/GeometryCollective/boundary-first-flattening>
→ **【推断】落地做法**：对每对相邻面片，算完各自的 UV 后，把后者的 UV 平移一个最接近的整数向量，使接缝两侧对齐。这是一个 O(面片数) 的后处理，几乎零成本，能消掉大部分接缝。

**思路 2：cone singularity——"把畸变集中到少数几个点"（BFF / Kharevych 等）**
BFF 文档：*"cone singularity 的角色很像服装设计里的'省道（darts）'"*；"差的 cone 位置反而会增加面积畸变"，也可自动放置。
→ **【推断】落地做法**：不要试图让整张曲面尺度统一；允许在"NURBS 面片的退化/高曲率角落"放一个奇点，让畸变集中在那里（表现为一个小范围的花纹挤压），而不是弥散到整张面上。对贴方块贴图来说，把畸变藏在一个 16×16 像素的角落里几乎看不出来。

**思路 3：PolyCube / 按法线聚类成 6 个轴（Tarini 2004）**
PolyCube-Maps 的核心是"用轴对齐的立方体面作参数域"。
→ **【推断】落地做法**：这正好是"改进版 box mapping"的严格版。如果某个 NURBS 面片实在无法做弧长参数化（例如极度扭曲或退化），退化成"按平均法线选 6 个轴之一做平面投影"，并让相邻面片选择同一个轴以避免缝。成本极低，是很好的兜底。

**思路 4：方向场引导（QuadCover / Instant Meshes）**
Instant Meshes 的卖点是"**局部**方向场平滑 + 无全局优化"，可扩展性来自局部性。
→ **【推断】落地做法**：不实现完整方向场优化，只做"**局部对齐**"——在每个面片上，让 UV 的 u 方向尽量对齐曲面在该点的某个主曲率方向或参数切线方向，并**让相邻面片的 u 方向差尽量是 90° 的整数倍**（这样跨缝时花纹是旋转对齐而不是乱掉）。

### 4.3 明确说"不要碰"的

- **完整的混合整数优化（CoMISo / Mixed-Integer Quadrangulation）**：需要 MILP 求解器，代价远超收益。CoMISo 本体是 **GPL**（见 §5 许可表），对 MIT 项目是红线。
- **全局 Periodic Global Parameterization**：需要跨整个网格的周期匹配优化，属于"研究级"工作量，对"一个 mod 里的曲面贴图"完全过度设计。

---

## 5. triplanar 的已知改进

### 5.1 基础的 triplanar 与它为什么有缝【来源】

Triplanar 的本质（UE5 `WorldAlignedTexture` 的拆解写得很清楚）：
- 把世界坐标投影到 XY / XZ / YZ 三个平面，得到三套 UV；
- 用**世界空间法线的各分量绝对值**做权重（mask）；
- 三次采样后按 mask 混合。

UE5 的实现细节（**有来源，且能解释"硬接缝"从哪来**）：
> WorldAlignedTexture 的 mask 用世界法线分量，并通过**两次顺序 Lerp** 混合：
> 第一次用 `N.x` 在 YZ 与 XZ 之间选主导平面，第二次用 `N.z` 把 XY 混进结果。
> 「**1. cosine ≠ 等份**」：法线分量按角度余弦变化，所以**原始分量值不是这一对里的等份**——你以为 45° 时是 50/50，实际上不是；用 `|N.x|/(|N.x|+|N.y|)`（LinearMask）才在 45° 真正达到 0.5/0.5，而 CosineMask 会在一个面上先出现 0.5 再"挪"到另一个面。
> 「**2. 第三投影有'粘性'**」：这个混合顺序让最后一个投影（XY）拿到略多的份额。
来源：<https://80.lv/articles/ue5-triplanar-deep-dive-from-worldalignedtexture-to-high-quality-normals-part-1>
（原讲座：<https://dev.epicgames.com/community/learning/tutorials/qz522/unreal-engine-buceo-profundo-de-triplnar-en-ue5-de-worldalignedtexture-a-normales-de-calidad-alta>）

**【推断】硬接缝的三个来源**：
1. **轴切换处的导数不连续**：两套 UV 在 45° 附近交叉，`max` 型混合会产生 C0 但不 C1 的接缝线；
2. **上面说的"cosine ≠ 等份"**：在非 45° 就发生权重交叉，接缝线位置与直觉不符；
3. **三路采样本身在切换带上"半透明叠加"**：两个不同相位的花纹叠加会互相冲淡（看起来像一条模糊的暗带）。

### 5.2 工业上的处理办法（按代价从低到高）

**(a) 归一化权重 + 提高混合指数**
`w = pow(|n|, k); w /= (w.x+w.y+w.z)`。`k` 越大越靠近硬边，越小越糊。这是所有引擎的默认手段。
**【来源】** UE5 的 `Contrast` 参数就是干这个的（同上 80.lv 文章），文档明确说"调整 Contrast 会改变混合的平滑度"。

**(b) UDN / Whiteout 混合（修对角线伪影）**
**【来源】** Ben Golus, *Normal Mapping for a Triplanar Shader*：
- 文章：<https://medium.com/@bgolus/normal-mapping-for-a-triplanar-shader-10bf39dca05a>
- 示例 shader 仓库：<https://github.com/bgolus/Normal-Mapping-for-a-Triplanar-Shader> —— **License: The Unlicense（公有领域）**（GitHub API 返回 `"license": {"key": "unlicense", "spdx_id": "Unlicense"}`）
**【推断】** "Whiteout blend" 的思路是：不要对**已经解码的法线向量**做线性插值（线性插值会缩短向量、把法线拉向切空间 Z 轴，产生可见的对角亮带），而是：
1. 先把三个投影的法线都"白化（whiteout）"——即把切空间法线重映射成"相对该投影的 Z 轴"的形式，让三者的 Z 分量都朝正；
2. 用**权重之和**而不是加权平均来混合，得到的向量长度自动接近 1，不需要再归一化；
3. 对 albedo 也用同一套权重结构。
效果是**消除 45° 对角线上的"过暗/过亮"带**。

**(c) Biplanar mapping（少一次采样）**
**【来源】** 有公开实现与说明，例如 Defold 的 biplanar 材质：
<https://github.com/selimanac/defold-biplanar-mapping>（**License: 未知**，README 未声明；仓库不在 GitHub 的标准 license 检测结果里，需自行确认）
它的做法：**只用两次投影**——侧面纹理走 X 与 Z 两个投影、按 `|n.x|`/`|n.z|` 混合（**biplanar** 而非 triplanar），顶面纹理单独走一次 XZ 俯视投影；最后按 `smoothstep(blend_start, blend_end, n.y)` 在"侧面"和"顶面"之间过渡。
**【推断】** 对 Minecraft 风格（**顶面 vs 侧面是两种不同贴图**，比如草方块）这其实非常合适：它天然对应"顶/侧"两种材质，而且采样数从 3 降到 2+1。**但注意：X/Z 两个投影的混合仍然有 45° 缝**（就是 triplanar 少了一个轴的情形），所以它不解决缝，只是省采样。

**(d) 随机化 / Hex-Tiling（打散"平铺重复感"，不是消缝）**
- **Hex-Tiling**【来源】Mikkelsen, *Practical Real-Time Hex-Tiling*, JCGT vol.11 no.3：<https://jcgt.org/published/0011/03/05/>（作者版 PDF：<https://raw.githubusercontent.com/mmikk/mmikk.github.io/master/papers3d/mm_hex_compressed.pdf>）。思路是**用一张"控制/查找"纹理做随机混合**，让每块采样到的图案随机旋转/偏移，从而打破重复感，同时保持无缝。
- **Stochastic Texture Filtering (STF)**【来源】Wyman & Panteleev, JCGT vol.12 no.2：<https://jcgt.org/published/0012/02/01/>，以及 arXiv <http://arxiv.org/pdf/2407.06107>。思路是用**随机采样**替代 mipmap，把"欠采样"变成噪声。
- **改进版**：Wronski et al., *Improved Stochastic Texture Filtering Through Sample Reuse*：<https://research.nvidia.com/labs/rtr/publication/wronski2025stfreuse/stf_sample_reuse.pdf>
- **直方图保持混合**【来源】Heitz & Neyret, *High-Performance By-Example Noise using a Histogram-Preserving Blending Operator*, HPG 2018：<https://inria.hal.science/hal-01824773v1/file/HPN2018.pdf>
**【推断】** 这些手段解决的是"同一张 16×16 贴图被拉伸到大面积上看起来太规律"，**不是**解决轴切换缝，也不是解决图集不能 repeat。对项目的优先级应该低于 §3 的路线选择。

### 5.3 代价对照表【推断，基于上述来源的技术描述】

| 方法 | 纹理采样数 | ALU | 额外资源 | 主要缺陷 |
|---|---|---|---|---|
| 3× triplanar + 归一化权重 | 3 | 低 | 无 | 轴切换缝（可调宽度但不可消） |
| 3× + Whiteout/UDN 混合 | 3 | 中 | 无 | 法线正确了，**图案相位不连续仍在** |
| Biplanar（X/Z + 顶面） | 2+1 | 中低 | 无 | 仍有 45° 缝；需要"顶/侧"两套材质 |
| Hex-Tiling | 1 + 1 查找 | 中 | 一张控制纹理 | 需要额外纹理；对**图集内**子矩形更难做（要用 locator 映射回子矩形） |
| Stochastic（STF） | ≥ 数次 | 高 | 噪声/低差异序列 | 引入噪声；须处理 mip；图集场景下子矩形映射复杂 |
| 手写 wrap（路线 B） | 1 | 低 | 无 | 需自定义 shader + `textureGrad` |

---

## 6. 面向实现的建议

### 6.1 推荐路线

**主线：路线 A（独立 REPEAT 纹理）+ 改进的 triplanar 兜底 + 接缝整数对齐后处理。**
**并行验证：路线 B（着色器手写 wrap）作为"不想加纹理"时的替代方案。**

**为什么不选 C / D / E**：
- `GL_TEXTURE_2D_ARRAY` 在 1.21.1 上没有引擎侧支持（`GlStateManager` 无 `_texImage3D`、纹理状态缓存只记一个 2D 绑定、没有 `sampler2DArray` 核心着色器），要突破三层抽象，且 1.21.5 会重写这一层。**性价比不成立。**
- 几何切割会把细分密度绑死到 16 像素的贴图周期上，等于放弃自适应细分的全部收益，而且**不能解决共享边界上的 `frac` 跳变**。
- 重打包成单张贴图会把平铺周期写死。

**为什么 A 是主线**：
1. **它把"图集约束"整个移除**，而不是绕过。之后 UV 可以取任意实数，"跨 N 张贴图宽度"这件事从"问题"变成"常态"。
2. **在 1.21.1 上的改动面很小**：项目已经在用 `RenderType.entityCutoutNoCull(RL)` 这条路，`TextureStateShard(RL, blur, mipmap)` 是同一个机制；REPEAT 是这个机制的默认行为（`SimpleTexture.upload(..., clamp=false)` + GL 默认 `GL_REPEAT`）。
3. **它顺带修好了 mipmap**：图集里的 mipmap 会把邻块混进来，独立纹理不会。
4. **draw call 的代价可控**：等于"曲面上用到的方块材质种类数"，跟"曲面被切多少片"无关。

### 6.2 分步落地

**第 0 步（先验证两个假设，半天）**
1. `SimpleTexture` 加载单张 `minecraft:textures/block/oak_planks.png`（不带 `clamp:true` 的 mcmeta），用 `GlStateManager._texParameter` 把 wrap 显式设成 `GL_REPEAT`，用一个自定义 `RenderType` 画一个 UV 从 0 到 7.3 的四边形。**确认真的平铺了、且没有串色**。
2. 确认 `TextureStateShard(RL, false, true)` 的 `mipmap=true` 在 REPEAT 下 mip 链是干净的。
> 这一步是整个路线的**唯一真正的未知**。如果它通过，后面全是常规工程。

**第 1 步：UV 生成本身改成"输出实数周期"（1–2 天）**
- 把现在"在顶点/CPU 里 `frac`"的代码**全部删掉**；顶点阶段只输出**未回绕**的 UV。
- 如果第 0 步失败（必须留在图集里），立刻切换到路线 B：保留未回绕 UV 到 fragment，在那里 `fract` + `textureGrad`。

**第 2 步：NURBS 参数域 → 尺度统一 UV（3–5 天）**
- **曲线级**：对每个等参方向做弧长重参数化（Gauss–Legendre 求 `s(u)` + Newton 求逆）。缓存一张 `u → σ` 的单调表，UV 求值变成查表插值。**不要每次求值都跑 Newton。**
- **曲面级**：先用**张量积**（u 向和 v 向各自独立重参数化）。这是最小改动、最大收益的一步：它把"尺度随参数变化"消掉。
- 缩放常量：`uv_world = uv_arclength / T`，`T` = 方块贴图的世界尺寸（1 个 MC 方块 = 16 像素 → `T = 1.0` 方块单位）。这一步就让"尺度统一"成立了。
- **验收标准**：在一张测试用的 NURBS 面上，贴上 `oak_planks`，网格线间距的最大/最小比值应该接近 1（可以类比 BFF 的 `Conformal Distortion` 显示，自己写一个着色器模式把 σ1/σ2 和 σ1σ2 画出来）。**【推断】** 目标定在"肉眼看不出尺度差"而不是"数学最优"。

**第 3 步：接缝的整数对齐（1–2 天）**
- 遍历相邻面片对（共享边/共享参数边界），把后者的 UV 平移最接近的整数向量，使共享边上的 UV 对齐。
- 用并查集把整张曲面连成一个整体，避免"平移了 A-B，破坏了 B-C"。
- **这是 PGP / Seamless / BFF `Remove Seams` 的可操作内核**，成本 O(面片数)，收益最大。

**第 4 步：兜底与打磨（按需）**
- 对无法参数化的退化面片（面积≈0、雅可比奇异、极窄长条）：退化成"按面片平均法线选轴做平面投影"（PolyCube 思路），并强制与相邻面片同轴以避免新缝。
- 如果仍保留 triplanar 作为可选模式：把权重改成归一化的 `pow(|n|,k)`，混合换成 **Whiteout/UDN**（`bgolus` 仓库是 **Unlicense**，可以自由参考）。
- 如果"平铺重复感"成为问题（大面积同款贴图），再考虑 Hex-Tiling；**在引入独立纹理（路线 A）之后，Hex-Tiling 的实现难度会大幅下降**，因为它不再需要处理图集子矩形。

**第 5 步（可选，仅当 draw call 成为瓶颈）**
- 运行期把用到的 K 种方块贴图合成一张 N×N 的 REPEAT 网格纹理（每格一个周期），把 draw call 从 K 降到 1。**【推断，需实测】** 注意 K 受最大纹理尺寸限制，且需要重新走一遍 mipmap 生成。

### 6.3 三个必须记住的工程要点

1. **`frac` 只能出现在 fragment shader 的最后一步。** 任何在顶点阶段/CPU 阶段的 `frac` 都会重新引入"四个角的 `frac` 各自回绕、插值出垃圾"这个原始 bug。
2. **手写 wrap 必须配 `textureGrad`**，用**未回绕 UV 的导数**，否则接缝处会出现一条 mip 选错导致的细线。
3. **弧长重参数化要预计算成表**，不要放进每帧的求值热路径。

---

## 7. 来源与许可清单

### 7.1 有来源支持的结论 → 链接

**CAD 映射类型**
- 3ds Max `UVWmap` 全部 7 种映射类型 + 每种畸变描述：<https://help.autodesk.com/cloudhelp/2020/ENU/3DSMax-MAXScript/files/GUID-68BA1E0B-8A81-476C-9E41-0C15FE911872.htm>
- 3ds Max SDK `Mapping Type (Mapping Group)`：<https://help.autodesk.com/cloudhelp/2024/ENU/Max-Developer-Help/3ds_max_sdk_features/rendering/textures_maps/mapping_type_mapping_group.html>
- Navisworks `Material Mapping`（含 Explicit mapping 与自动选型）：<https://help.autodesk.com/cloudhelp/2023/ENU/Navisworks/files/GUID-44F3A8BD-CDE2-492C-9D3D-2EA334EBB580.htm>
- PTC Creo Render Studio `Mapping-Typen`：<http://support.ptc.com/help/creo/creo_render/r6.0/german/render/photorender/fundamentals/fund_four_sub/Types_of_Mapping.html>
- Rhino Texture Mapping（含 Surface mapping 的接缝缺陷、UVW repeat/offset/rotation）：<https://docs.mcneel.com/rhino/5/help/en-us/properties/texturemapping.htm>
- Rhino 貼圖軸（繁中）：<http://docs.mcneel.com/rhino/mac/help/zh-tw/properties/texturemapping.htm>
- SOLIDWORKS Appearances – Mapping Tab（**HTTP 403，仅能确认页面存在**）：<https://help.solidworks.com/2026/English/SolidWorks/Sldworks/r_appearances_pm_mapping_tab.htm> ／镜像 <https://my.solidworks.com/reader/onlinehelp/2021%252Fenglish%252Fswconnected%252Fswdotworks%252Fhidd_dve_materials_mapping.htm/>
- SOLIDWORKS Visualize 映射模式与 Fit to Part / Set to World Scale：<https://www.innova-systems.co.uk/how-to-map-scale-textures-in-solidworks-visualize/> ／ <https://www.javelin-tech.com/blog/2025/11/how-to-fit-textures-to-parts-in-solidworks-visualize/>（后者 Cloudflare 403）

**曲线/曲面重参数化与畸变**
- Piegl & Tiller, *The NURBS Book*, 2nd ed., Springer, 1997（标准教材；本次未抓到可直接引用的正文页）
- Sander, Snyder, Gortler, Hoppe, *Texture Mapping Progressive Meshes*, SIGGRAPH 2001, DOI `10.1145/383259.383307`：<https://www.eecs.harvard.edu/~sjg/papers/tmpm.pdf>
- Floater & Hormann, *Surface Parameterization: A Tutorial and Survey*（2005）；Hormann 参数化讲义目录：<https://www.inf.usi.ch/hormann/parameterization/NonLinear2008.pdf>
- Sheffer, Praun, Rose, *Mesh Parameterization Methods and Their Applications*（2006）
- Area-preserving parameterization of rational Bézier surfaces：<https://www.sciencedirect.com/science/article/pii/S1524070325000256>
- BFF 的畸变度量与"数学上面积畸变最低"的声明：<https://github.com/GeometryCollective/boundary-first-flattening>

**Minecraft 1.21.1 渲染管线**
- MC-156088（图集串色 + "首选方案是让它 repeat" + 影响 1.21.1 + 至今 Open）：<https://mojira.dev/MC-156088>
- MC-236285：<https://mojira.dev/MC-236285>
- NeoForge 1.21.1 Textures 文档（`.mcmeta` 的 blur/clamp）：<https://docs.neoforged.net/docs/1.21.1/resources/client/textures/>
- NeoForge Render Types 文档：<https://pr-61.neoforged-docs-previews.pages.dev/docs/rendering/modelextensions/rendertypes/>
- 1.21.1 `AbstractTexture`（无 wrap 设置器）：<https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/AbstractTexture.html>
- 1.21.1 `SimpleTexture.upload(NativeImage, boolean blur, boolean clamp)`：<https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/SimpleTexture.html>
- 1.21.1 `RenderStateShard$TextureStateShard(ResourceLocation, boolean, boolean)`：<https://mappings.dev/1.21.1/net/minecraft/client/renderer/RenderStateShard$TextureStateShard.html>
- 1.21.1 `GlStateManager`（**无 `_texImage3D`**）：<https://mappings.dev/1.21.1/com/mojang/blaze3d/platform/GlStateManager.html>
- 1.21.1 `GlStateManager$TextureState`（单一 `binding` 字段）：<https://mappings.dev/1.21.1/com/mojang/blaze3d/platform/GlStateManager$TextureState.html>
- 1.21.1 `TextureAtlas`（`extends AbstractTexture`，含 `LOCATION_BLOCKS`、`mipLevel`）：<https://mappings.dev/1.21.1/net/minecraft/client/renderer/texture/TextureAtlas.html>
- 1.21.11 `com.mojang.blaze3d.textures.GpuTexture`（**1.21.5+ 才有的新体系**）：<https://mappings.dev/1.21.11/com/mojang/blaze3d/textures/GpuTexture.html>
- OpenGL `glTexParameter`（`GL_TEXTURE_WRAP_*` 初始值 `GL_REPEAT`）：<https://docs.gl/gl4/glTexParameter>
- `textureGrad`：<https://docs.gl/gl4/textureGrad>
- Minecraft Wiki 贴图图集分类：<https://minecraft.wiki/w/Category:Texture_atlases>
- NeoForge 渲染开发中文指南（`ShaderInstance` / `RegisterRenderBuffersEvent` / 线程边界）：<https://doc.ideafox.top/docs/a-dai/mods-dev/render/render_guide>
- Iris 关于自定义图集纹理的 issue（1.21.5+ 的 gaux/atlas 变化）：<https://github.com/IrisShaders/Iris/issues/2626>

**无缝参数化**
- QuadCover：<https://onlinelibrary.wiley.com/doi/abs/10.1111/j.1467-8659.2007.01060.x> ／ 作者版 <http://www.mi.fu-berlin.de/en/math/groups/ag-geom/publications/db/KNP07-QuadCover.pdf>
- Periodic Global Parameterization：<https://dl.acm.org/doi/10.1145/1183287.1183297> ／ <https://in2p3.hal.science/inria-00104853v1>
- Mixed-Integer Quadrangulation：<https://scholar.archive.org/work/mzdkham4rvesxa27df77gtesaa>
- PolyCube-Maps：<https://dl.acm.org/doi/10.1145/1015706.1015810>
- Instant Field-Aligned Meshes：<https://igl.ethz.ch/projects/instant-meshes/>
- Boundary First Flattening：<https://arxiv.org/abs/1704.06873> ／ <https://github.com/GeometryCollective/boundary-first-flattening>
- Seamless (Liu/Ferguson/Jacobson/Gingold)：<https://cragl.cs.gmu.edu/seamless/>
- Seamless Parametrization with Cone and Partial Loop Control：<https://dl.acm.org/doi/pdf/10.1145/3600087>
- Conformal Flattening by Curvature Prescription and Metric Scaling（cone 的起源）：<http://www.cs.technion.ac.il/~gotsman/AmendedPubl/Miri/EG08_Conf.pdf>
- Conformal Equivalence of Triangle Meshes（BFF 借用的"全局无缝"技术）：<http://multires.caltech.edu/pubs/ConfEquiv.pdf>

**Triplanar**
- bgolus, *Normal Mapping for a Triplanar Shader*：<https://medium.com/@bgolus/normal-mapping-for-a-triplanar-shader-10bf39dca05a>
- UE5 `WorldAlignedTexture` / `WorldAlignedNormal` 完整拆解（cosine≠等份、第三投影粘性、Contrast、Local Space、镜像）：<https://80.lv/articles/ue5-triplanar-deep-dive-from-worldalignedtexture-to-high-quality-normals-part-1>
- 同一讲座的 Epic 官方社区版：<https://dev.epicgames.com/community/learning/tutorials/qz522/>
- Biplanar（Defold 实现）：<https://github.com/selimanac/defold-biplanar-mapping>
- Mikkelsen, *Practical Real-Time Hex-Tiling*, JCGT vol.11 no.3：<https://jcgt.org/published/0011/03/05/>
- Wyman & Panteleev, *Stochastic Texture Filtering*, JCGT vol.12 no.2：<https://jcgt.org/published/0012/02/01/> ／ <http://arxiv.org/pdf/2407.06107>
- Wronski et al., *Improved STF Through Sample Reuse*：<https://research.nvidia.com/labs/rtr/publication/wronski2025stfreuse/stf_sample_reuse.pdf>
- Heitz & Neyret, *Histogram-Preserving Blending Operator*, HPG 2018：<https://inria.hal.science/hal-01824773v1/file/HPN2018.pdf>

### 7.2 许可判定（MIT 项目只能吸收思路，不得复制代码）

| 项目 / 来源 | 许可 | 对 MIT 项目的可用性 |
|---|---|---|
| **libigl** <https://github.com/libigl/libigl> | **MPL-2.0** | 文件级 copyleft。可参考思路；若复制文件需保留 MPL。**建议只读不抄。** |
| **Instant Meshes** <https://github.com/wjakob/instant-meshes> | **BSD-3-Clause**（已核实 LICENSE.txt 正文） | 宽松，可用 |
| **Boundary First Flattening (BFF)** <https://github.com/GeometryCollective/boundary-first-flattening> | **MIT** | 宽松，可用 |
| **NURBS Sharp (NurbsSharp)** <https://nyarurato.github.io/NurbsSharp/> | **MIT** | 宽松，可用（实现了弧长参数化，是很好的对照） |
| **CoMISo** <https://www.graphics.rwth-aachen.de/software/comiso/> | **GPL**（官方页面明写 "released under the **GPL license**，商用需联系"） | ⛔ **红线，不可复制任何代码**，只能读论文思路 |
| **OpenFlipper**（CoMISo 插件宿主） | GPL 系 | ⛔ 同上 |
| **bgolus/Normal-Mapping-for-a-Triplanar-Shader** <https://github.com/bgolus/Normal-Mapping-for-a-Triplanar-Shader> | **The Unlicense**（GitHub API 核实） | 公有领域，最宽松，可自由参考 |
| **Stochastic Texture Filtering 参考实现**（Wyman） | 学术论文；配套代码许可**未知**（未核实到仓库） | 引用论文思路；复制代码前须核实 |
| **Hex-Tiling（mmikk）** | 论文 JCGT（开放获取）；作者代码许可**未知** | 引用论文；复制代码前须核实 |
| **Defold biplanar mapping** <https://github.com/selimanac/defold-biplanar-mapping> | **未知**（README 未声明许可） | 只读思路，不要复制 |
| **Unreal Engine 材质/shader 源码** | **Unreal Engine EULA**（专有，非开源） | ⛔ 不可复制。80.lv 文章是第三方讲解，可作思路引用 |
| **NeoForge 文档** <https://docs.neoforged.net> | **MIT**（页脚 "Copyright © 2026, under the MIT license"） | 可用 |
| **Minecraft 本体 / mappings.dev 映射表** | Mojang 专有；映射名为社区产物 | 只用于查阅 API 签名，不复制代码 |
| **Autodesk / PTC / SOLIDWORKS / McNeel 帮助文档** | 各厂商专有文档 | 仅引用，不复制 |
| **Mikkelsen / Wyman / Heitz 论文正文** | 学术出版（ACM/JCGT） | 引用思路；正文与图表版权归出版方 |

---

## 8. 我**没有**验证到的东西（诚实清单）

1. **SOLIDWORKS 官方 Mapping 选项卡的正文**：`help.solidworks.com` 与 `my.solidworks.com` 都返回 403（Akamai），我用的是第三方（Innova Systems）的 Visualize 映射清单与 Autodesk/PTC/Rhino 的对照。SOLIDWORKS 主程序的映射类型清单可能与 Visualize 不完全一致。
2. **`.mcmeta` 的 `clamp` 字段在 1.21.1 里到底有没有生效**：NeoForge 文档说"specified by the codec, but unused"，但从 `SimpleTexture.upload(image, blur, clamp)` 的参数名看它是被用的。**两者矛盾，需要实测。**
3. **图集 sprite 的 padding / extrusion 策略**：如果要在路线 B 里保证边界双线性不串色，需要知道 MC 的 stitcher 有没有给每个 sprite 做边缘外扩。**没有找到权威来源。**
4. **`VertexFormatElement` 是否支持 `flat` / `noperspective` 限定符**：影响路线 B 里"用额外属性传周期索引"的可行性。
5. **各算法"重参数化后畸变降低百分比"的具体数字**：我找到了畸变的**定义**（σ1/σ2、quasi-conformal distortion、log conformal factor）和"存在不可消除下界"的**定性结论**，但**没有找到任何论文给出"重参数化把某类曲面的畸变从 X% 降到 Y%"这种可直接引用的量化结论**。这类数字通常因曲面而异，论文一般给的是图而不是标量。
6. **PolyCube-Maps / QuadCover 的官方实现许可**：未逐一核实（OpenFlipper/CoMISo 系是 GPL，这点已确认）。
7. **运行期合成小图集方案（§3.3 末）的可行性与性能**：纯推断，无先例来源。

---

*报告完成。所有链接均为调研过程中实际访问或搜索结果中出现的 URL；标注为 403/无法抓取的来源已单独说明。*
