# Sable 坐标系对照表与防错机制

> **为什么有这份文档**：Sable 的实现**到处涉及不同的坐标系** ✓，
> "把某个量当成另一套系的"是本项目**最高频、最难察觉**的一类 bug ✗。
> 它在数值上不报错 ✓、类型上也看不出来 ✓，只表现为"手感不对"这种间接症状 ✗。
> 本项目已经**反复犯过同一类错误** ✓ —— 这份文档就是为了终止这个循环 ✓。

---

## 1. 各来源属于哪套系（**实测得出，不要凭直觉** ✗）

| 量 | 属于 | 量级 | 备注 |
|---|---|---|---|
| `Rapier3D.getPose(level, id, out)` | **世界** ✓ | ~10²–10³ | 与 `bodyState` 的 position 一致 ✓ |
| `massTracker.getCenterOfMass()` | **plot 绝对** ✗✗ | **~2.048e7** | **最容易踩的一个** ✓ |
| `massTracker.getInertiaTensor()` | 绕自身质心 ✓ | ~10²–10⁴ | 语义是局部 ✓ |
| 喂给 `attachConvex` 的 `points` | **刚体局部** ✓ | ±数十 | = `mesh − COM`（两个 plot 绝对量相减 ✓ 结果自然是局部 ✓）|
| Rapier 内部 collider 坐标 | **刚体局部，原点 = 质心** ✓ | ±数十 | 见下 §2 ✓ |
| `ForceTotal` 的 `localForce/localTorque` | **plot 局部** ✓ | — | 曾把世界系受力点送进来 ✗ |
| 装置 `setLocalBounds` | **方块单位 0..16** ✓ | — | 一个 16³ 段 ✓ |
| Sable `collider.rs` 的 `WORLD_SIZE` | ±3e7 ✓ | — | 地面 AABB 是 6e7 的立方体 ✓ |

## 2. 最重要的一条规则

```
Rapier 的刚体坐标系，其**原点就是质心** ✓
```
证据（都在 `depends/sable-main` 里 ✓）：
- `marten/algo.rs`：collider 坐标 = `local − center_of_mass`
- `marten/collider.rs`：`local_com = Vec3::ZERO`

**推论（本项目栽过跟头的那一条 ✓）**：
> "跟踪器在**刚体局部系**里的质心"**恒为 (0,0,0)** ✓
> 拿它的 **plot 绝对坐标**去参与任何局部计算都是错的 ✗

## 3. 已发生的真实事故（都归档在此，避免重犯 ✓）

### 事故 1 · 转动惯量被放大 1e17 倍 ✗✗（最严重 ✓）
- **位置**：`SolidMassComposition.combine` 的平行轴项
- **做法**：把 tracker 的 `getCenterOfMass()`（plot 绝对 ~2.048e7 ✗）与实体的 AABB 中心
  （刚体局部 ±6 ✓）**一起**送进平行轴定理
- **后果**：`d ≈ 2e7` → `d² ≈ 4e14` → `m·d² ≈ 1.7e17` ✗
- **症状**：**"任意时候都难以旋转，弹跳一次只转 1~2 度"** ✓ —— **完全没有报错** ✗
- **为什么难查**：`mass` 只是**求和** ✓ 不碰坐标 ✓ 所以质量一直"看起来对" ✓（实测 408.484 ✓）；
  而 `bodyState` **不返回惯量** ✗ → 诊断看不到它 ✓
- **修法**：tracker 的局部质心用 `(0,0,0)` ✓

### 事故 2 · 把质心当成刚体位姿 ✗
- **位置**：`createConvexBody` 的 pose 参数
- **做法**：传了 `massTracker` 的质心 ✗ 而不是 `Rapier3D.getPose(...)` ✓
- **后果**：凸包跑到 **754 万格** 外 ✓ → Sable 以"extreme Y coordinate range"删掉整个子结构 ✗
- **修法**：用 `getPose` 读刚体的**世界位姿** ✓（并保证新刚体与该刚体同 pose ⇒ 两个局部系重合 ✓）

### 事故 3 · 世界系受力点送进 plot 局部队列 ✗
- **位置**：`ForcePointService`
- **后果**：力矩被放大两千万倍 ✓ → 结构瞬间获得 ~1e8 m/s ✓ → 被 Sable 按"Y 越界"删除 ✗
- **修法**：两侧都用 plot 局部量 ✓（并保留力臂上限 `MAX_TORQUE_ARM = 8.0` 兜底 ✓）

### 事故 4 · 事故 1 的**同一个错**，在"合并/缩放质量"那条路上又来了一次 ✗✗（2026-10-03 ✓）
- **位置**：`RapierPhysicsPipelineMixin.composeWithSolids`（我们的 mixin ✗）
- **做法**：`new Part(tracker.getMass(), c.x(), c.y(), c.z(), …)` ✗ ——
  `c = tracker.getCenterOfMass()` 是 **plot 绝对（~2.048e7）** ✗
  而同一列表里的实体部分是**刚体局部**（±6 ✓）✗
- **为什么没早发现**：这条路**只在"子结构被合并"时才走** ✗
  （`MergedMassTracker.uploadData` → `onStatsChanged` → 我们的 redirect ✓）
  —— 单结构测试（圆柱 / 圆环）根本碰不到它 ✗✓
- **后果（实测 ✓）**：一根 **半径 3 / 长 12 / 壁厚 0.5 的圆管**被合并质量时：
  - 两个部分相距 **2.8976e7** ✗ ⇒ 惯量按 1e17 量级算 ✗
  - **合出来的质心落在 2e7 处** ✗ ⇒ 物理彻底错乱 ✓
  - 症状：**「质量手感不足」** ✗ + **「竖直落地插进地下 9 格」** ✗✓
  - 而坐标系守卫 `Check.FrameViolation` **当场抛异常** ✓
    ⇒ `Sable ticking physics` **崩服 + 客户端断线** ✗✓（`crash-reports\…-server.txt` ✓）
- **修法**：与事故 1 **完全一样** —— tracker 在刚体局部系里的质心是 **`(0,0,0)`** ✓✓
- **⇒ 教训（这条比事故本身重要 ✓）**：
  **同一个坐标系错误可以在多处独立存在** ✗ ——
  修好一处**不等于**修好了这一类 ✓。
  **动坐标系时，必须把所有 `combine` 的调用点一起过一遍** ✓
  （全项目只有两处 ✓：`VoxelCollisionService` ✓ 与这条 mixin ✓ —— 现在两处都是 `(0,0,0)` ✓）。

## 4. 防错机制（按强度排序 ✓）

### ① 量级断言（已实现 ✓ `com.ovo.sablestopnow.physics.Check`）
```java
Check.local(v, "parallel-axis arm");        // 要求 |v| < 1e4 ✓ 否则抛 FrameViolation
Check.plot (v, "tracker centre of mass");   // 要求 |v| > 1e6 ✓
Check.sameFrame(a, "a", b, "b");            // 两个向量必须同系 ✓
```
- **已接入**：`SolidMassComposition.combine` ✓ ——
  任意两个部分的质心距离 > `LOCAL_MAX` 就**当场抛异常** ✓✓
  **⇒ 事故 1 会被立刻抓到 ✓ 而不是靠"手感难旋转"这种间接症状** ✓
- **阈值依据**：局部量最多几十格 ✓ 而 plot 绝对量 1e6 起步 ✓ → 两者差 4 个数量级 ✓ 阈值很安全 ✓

### ② 命名带系别（零成本 ✓ 建议一律照做 ✓）
`comPlot` ✓ `centreLocal` ✓ `poseWorld` ✓ `pointsLocal` ✓
—— 读代码时**一眼看得出** ✓ 也让人在 review 时更容易发现混用 ✓

### ③ 边界处转换一次，绝不让原始 Vector3d 跨系传递 ✓
在每个读写 Sable 的地方**显式转换** ✓ 且只转一次 ✓（一行可见 ✓）

### ④ 边界诊断日志（已实现 ✓ 就是那五行 `[diag]` ✓）
```
[diag] hull span x=[…] y=[…] z=[…] | centre=(…)
[diag] getPose(mountId=N) = pos(…) quat(…)
[diag] massTracker COM=(…)
[diag] struct body: mass=… pos=… colliders=…
```
**⇒ 正是这五行让"plot 绝对 vs 世界"的差异第一次暴露出来** ✓✓ **⇒ 保留，不要删** ✓

### ⑤ 离线断言（项目规则要求 ✓）
纯逻辑（如 `combine`）必须能被离线断言覆盖 ✓ ——
**建议补一条**：给 `combine` 喂一个 plot 量级的质心 ✓ **断言它抛 `FrameViolation`** ✓
（这样事故 1 就**不可能被重新引入** ✓）

## 5. 一句话总结

> **先问"这个数属于哪套系"，再写公式** ✓
> **任何跨系相减之前，先过一遍 `Check`** ✓
> **局部量必须"小"（< 1e4），plot 绝对量必然"大"（> 1e6）** ✓
> **刚体坐标系的原点就是质心 ⇒ 跟踪器的局部质心恒为 (0,0,0)** ✓
