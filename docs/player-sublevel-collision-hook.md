# 玩家 ↔ 物理体碰撞：Sable 2.0.4 实现路径与「拖拽中幽灵化」可行 Hook（技术报告）

目标：找出「某个 sub-level 正在被物理手杖拖拽时，让它对玩家不参与碰撞」的**服务端、按体、运行时开关**的最干净实现点。

- 结论先行：**唯一的碰撞漏斗是 `dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.collide(...)`**（静态方法），它只被 Sable 自己的 `Entity.move` 重定向调用。
- **Sable / Simulated 都没有任何「按 sub-level 关闭实体碰撞」的公开 API 或内部标志**（见 §2）。
- 但**玩家的真实碰撞判定在客户端**（ServerPlayer 分支是早退的假地面），所以纯服务端 Mixin 无法阻止「拖拽者自己被顶出去」；必须把开关同步到客户端，做成 **common（双端）Mixin + 服务端权威状态同步**（mod 已有的 `SyncNoCollisionPayload` 基建可以直接升级复用）。
- 推荐实现：在 `collide` 里对 `Sable.HELPER.getAllIntersecting(...)` 的返回值做一次 `@ModifyExpressionValue` 过滤。**只影响实体碰撞**，不影响手杖射线选取 / 挖方块 / getInBlockState / 寻路 / checkInsideBlocks。

---

## 1. 玩家 ↔ sub-level 碰撞到底怎么走

### 1.1 谁调用 `collide(...)` —— 只有一个调用点（已用反编译字节码核实）

`SubLevelEntityCollision.collide(Entity, Vec3, Vec3, LevelReusedVectors)` 的唯一调用者：

`depends/sable-main/common/src/main/java/dev/ryanhcode/sable/mixin/entity/entity_sublevel_collision/EntityMixin.java`

```java
@Mixin(value = Entity.class, priority = 1100)                       // L42
public abstract class EntityMixin implements EntityMovementExtension {
  @Redirect(method = "move(Lnet/minecraft/world/entity/MoverType;Lnet/minecraft/world/phys/Vec3;)V",
            at = @At(value = "INVOKE",
              target = "Lnet/minecraft/world/entity/Entity;collide(Lnet/minecraft/world/phys/Vec3;)Lnet/minecraft/world/phys/Vec3;")) // L109-110
  public Vec3 sable$collideRedirect(final Entity entity, final Vec3 collisionMotion) {
      ...
      this.sable$collisionInfo = SubLevelEntityCollision.collide(   // L136
              entity, motion, velocity, ((LevelExtension) this.level).sable$getJOMLSink());
      ...
      final Vec3 beforeVanillaCollision = this.sable$collisionInfo.motion;
      final Vec3 afterVanillaCollision  = this.collide(beforeVanillaCollision); // L158 原版碰撞
```

即：**`Entity.move(MoverType, Vec3)` → (Sable 重定向) → `SubLevelEntityCollision.collide`**，随后才调用原版 `Entity.collide`。

原版侧只有世界方块：

`net/minecraft/world/entity/Entity.java`（1.21.1）
```java
public void move(MoverType type, Vec3 pos) { ... Vec3 vec3 = this.collide(pos); ... }   // L619 / L639
private Vec3 collide(Vec3 vec) {                                                         // L901
    List<VoxelShape> list = this.level().getEntityCollisions(this, aabb.expandTowards(vec));
    Vec3 vec3 = vec.lengthSqr() == 0.0 ? vec : collideBoundingBox(this, vec, aabb, this.level(), list);
...
private static List<VoxelShape> collectColliders(...) {                                   // L958
    builder.addAll(level.getBlockCollisions(entity, boundingBox));                        // L970
```

**关键事实（已核实）：整个 Sable 源码树里没有任何 `CollisionGetter`/`getBlockCollisions` 的 mixin。**
所以 **sub-level 的方块对原版 `getBlockCollisions` 永远不可见** —— 所有实体的 sub-level 方块碰撞都只发生在 `SubLevelEntityCollision.collide` 内部。

（核实方式：`grep -r "getBlockCollisions|getCollisions\(|CollisionGetter" depends/sable-main` → 仅命中 `entity_pathfinding/PathfindingContextMixin.java` 的 import。）

### 1.2 ServerPlayer 分支 = 早退 + 假地面（**服务端不做方块碰撞**）

`SubLevelEntityCollision.java` L50-66：

```java
public static CollisionInfo collide(final Entity entity, final Vec3 collisionMotionMoj,
                                    final Vec3 velocityMotionMoj, final LevelReusedVectors sink) {
    if (entity instanceof ServerPlayer) {
        final CollisionInfo collisionInfo = new CollisionInfo();
        collisionInfo.motion = collisionMotionMoj;                       // 原样返回，不做任何 MTV 推出
        final SubLevel trackingSubLevel = Sable.HELPER.getTrackingSubLevel(entity);
        if (trackingSubLevel != null) {
            entity.setOnGround(true);                                    // 假地面
            collisionInfo.verticalCollisionBelow = true;
            collisionInfo.verticalCollision = true;
            collisionInfo.trackingSubLevel = trackingSubLevel;
            if (entity.getDeltaMovement().y < 0) {
                entity.setDeltaMovement(entity.getDeltaMovement().multiply(1.0, 0.0, 1.0)); // 清掉下落速度
            }
        }
        return collisionInfo;                                            // ← 直接 return
    }
    ...
```

谁触发了它（1.21.1 反编译源码，`ServerGamePacketListenerImpl.handleMovePlayer`）：

```java
boolean flag1 = this.player.verticalCollisionBelow;                       // L935
this.player.move(MoverType.PLAYER, new Vec3(d6, d7, d8));                 // L936  → 进入上面的分支
...
if (!this.player.isChangingDimension() && d10 > 0.0625
    && !this.player.isSleeping()
    && !this.player.gameMode.isCreative()                                 // ← 被 Sable 重定向为恒 true
    && this.player.gameMode.getGameModeForPlayer() != GameType.SPECTATOR) { flag2 = true; }
...
if (this.player.noPhysics || this.player.isSleeping()
    || (!flag2 || !serverlevel.noCollision(this.player, aabb)) && ...) {  // flag2 恒 false → 永远接受客户端位置
    this.player.absMoveTo(d0, d1, d2, f, f1);                             // L959
    ...
    this.clientIsFloating = d7 >= -0.03125 && !flag1 && ... && this.noBlocksAround(this.player); // L961
```

`depends/.../mixin/entity/entity_sublevel_collision/ServerGamePacketListenerImplMixin.java` L17-20：
```java
@Redirect(method = "handleMovePlayer", at = @At(value = "INVOKE",
          target = "Lnet/minecraft/server/level/ServerPlayerGameMode;isCreative()Z"))
private boolean sable$ignoreCreativeModeForSubLevelCollision(final ServerPlayerGameMode instance) { return true; }
```

### 1.3 那 ServerPlayer 的位置到底谁说了算？——**客户端**

* 客户端（`mixin/entity/entities_stick_sublevels/player/LocalPlayerMixin.java` L28-40）：玩家在跟踪某个 sub-level 时，**发给服务端的是 plot 局部坐标**：
  ```java
  final SubLevel trackingSubLevel = Sable.HELPER.getTrackingSubLevel(this);
  if (trackingSubLevel != null && !trackingSubLevel.isRemoved()) {
      final Vec3 pos = this.position();
      this.sable$oldPos = pos;
      final Vec3 localPosition = trackingSubLevel.logicalPose().transformPositionInverse(pos);
      ((EntityMovementExtension) this).sable$setPosField(localPosition);   // 只改发送用的 position 字段
  }
  ```
* 服务端（`mixin/entity/entities_stick_sublevels/player/ServerboundMovePlayerPacketMixin.java` L40-65）：把 packet 的 plot 坐标**反投影回世界坐标**，并据此设置 tracking：
  ```java
  final SubLevel subLevel = Sable.HELPER.getContaining(player.level(), this.x, this.z);
  ...
  ((EntityMovementExtension) player).sable$setTrackingSubLevel(subLevel);
  if (subLevel != null) {
      final Vector3d newPos = subLevel.logicalPose().transformPosition(new Vector3d(this.x, this.y, this.z));
      this.x = newPos.x; this.y = newPos.y; this.z = newPos.z;
  }
  ```

**推论（重要）**：`LocalPlayer` 不是 `ServerPlayer`，所以在客户端走的是 `collide` 的**完整路径**；玩家的顶出/站立/被推动全部在客户端算完再上报，服务端只做「假地面」与合法性放行。
→ **纯服务端 Mixin 无法让拖拽者自己「穿过去」**；要让拖拽者/其它玩家都不被顶，Mixin 必须同时作用于客户端，且「哪些体正在被拖拽」这份状态必须同步到客户端（mod 已有现成的 S2C 基建，见 §3/§4）。

### 1.4 `Sable.HELPER.getTrackingSubLevel(entity)` 的作用

`depends/.../ActiveSableCompanion.java` L433-436：
```java
@Override
public @Nullable SubLevel getTrackingSubLevel(final Entity entity) {
    return ((EntityMovementExtension) entity).sable$getTrackingSubLevel();
}
```
实现是 `mixin/entity/entity_sublevel_collision/EntityMixin.java` 里的 `@Unique private SubLevel sable$trackingSubLevel`（L58-59），即 **「这个实体当前站在/被固定在哪个 sub-level 上」的持久状态**。写入点：
* `sable$collideRedirect` L140-151（碰撞垂直命中时设；`trackingSubLevel == null` 且非 ServerPlayer 时清空）；
* `sable$tickInject` L219-241（坐在载具/处于 plot 内时刷新）；
* `ServerboundMovePlayerPacketMixin`（服务端按 move 包刷新）；
* `player_freezing/ServerPlayerMixin`、`neoforge/entity_swimming/EntityMixin` 等。

它在 collide 里被用于：(a) ServerPlayer 分支的假地面；(b) 「把实体从 sub-level 里踢出去」(L68-74)；(c) 加到 broad-phase 结果集 (L106-108)；(d) tracking 分支的坐标回填 (L236-259)。

**该字段被非常多系统读取**（寻路、`getOnPos`、渲染、`handleMovePlayer` 的 moved-too-quickly 豁免、骑乘继承位移……），因此**不要**为了本需求去动它（见 §3 风险）。

---

## 2. 有没有现成的「排除某个体与实体碰撞」API/标志？——**没有**

已逐一核查：

| 候选 | 结论 |
|---|---|
| `EntityExtension` / `EntityMovementExtension`（`sable$...`） | 只有 pos/tracking/collisionInfo 读写，无碰撞过滤接口（`mixinterface/EntityExtension.java` 全文 9 行） |
| `SubLevelEntityCollisionContext` / `TheFasterEntityCollisionContext` | 只是 `CollisionContext` 的缓存实现（`isDescending`/`isAbove`/`canStandOnFluid`），无过滤语义 |
| `SubLevelEntityUtil` (`api/entity/EntitySubLevelUtil.java`) | 只有 `setOldPosNoMovement` / `kickEntity` / `shouldKick` / `getCustomEntityOrientation(=null)` |
| `Sable.HELPER` (=`ActiveSableCompanion`) 全部 53 个公开方法 | 无任何 ignore/exclude/filter 参数；`getAllIntersecting(Level, BoundingBox3dc)` 是唯一 broad-phase 入口 |
| `SubLevel` 公开 API（javap 核实） | 只有 pose / plot / bounds / uuid / name / removed，**没有 ghost/sensor/collision 开关** |
| 全库搜索 `ghost|sensor|noEntityCollision|collisionFilter|collidesWith|isCollidable` | `common/src/main/java` 下 0 命中（仅 sculk vibration 的无关词） |
| rapier 后端 `setContactsEnabled` | **只是「两个 sub-level 之间」通过 constraint 关闭接触**：`sable_rapier/.../constraint/RapierConstraintHandle.java:21` → `Rapier3D.setConstraintContactsEnabled(...)`。rapier 里根本没有 MC 实体物理体（`sable_rapier/src/main/java` 全树 `entity` 只命中 `BlockEntity`），所以对「玩家」无能为力 |
| rapier collision groups | 硬编码在 Rust（`lib.rs` 的 `LEVEL_GROUP` / `rope.rs` 的 `ROPE_GROUP`），无 Java 侧 per-body 接口 |

> 附注：本 mod 现有 `StaffEnhanceServer.ghostTick`（L257-350）正是用 `GenericConstraint` + `handle.setContactsEnabled(false)` 实现「体↔体」幽灵，这条路**只能解决 Sable 体之间的接触，不能解决玩家**——与上面结论一致。

Simulated / Aeronautics 侧：**没有任何针对被拖拽体与玩家碰撞的处理**。
`PhysicsStaffServerHandler` 只有 `drag/stopDragging/toggleLock/isLocked`；`PhysicsStaffSubLevelObserver`（Sable `SubLevelObserver`）只转发 add/remove/tick。全库 grep `SubLevelEntityCollision|EntityMovementExtension|CollisionContext` 在 Simulated 侧只命中只读用法，无碰撞改动。

---

## 3. 最干净的 Hook 与各方案风险

### ✅ 推荐（方案 A）：`@ModifyExpressionValue` 过滤 `collide` 里的 broad-phase 结果

* 目标类：`dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision`
* 目标方法：`collide(Lnet/minecraft/world/entity/Entity;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;Ldev/ryanhcode/sable/api/math/LevelReusedVectors;)Ldev/ryanhcode/sable/sublevel/entity_collision/SubLevelEntityCollision$CollisionInfo;`
* 注入点（**已用 javap 在运行时 jar 上核实字节码**）：该方法内 `getAllIntersecting` 只有一处调用（offset 306）：
  `invokevirtual dev/ryanhcode/sable/ActiveSableCompanion.getAllIntersecting:(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;`
* 命中 L99-101 的 `for (final SubLevel subLevel : Sable.HELPER.getAllIntersecting(level, considerationBounds))`；过滤后该体不进入 L195 的 `for (final SubLevel subLevel : intersecting)` 主循环 → 无 MTV、无 firstCollisions、无 onGround、无 inheritedMotion。
* 注意：**不要**同时改 L104/L106-108 的 tracking 逻辑，也不要 null 掉 `getTrackingSubLevel`（见方案 C/D 风险）。

MixinExtras 已在本工程可用（`build` 里 NeoForge 自带 0.5.3；`mixin/input/MouseHandlerStaffEnhanceMixin.java` 已 import `com.llamalad7.mixinextras.sugar.Local`），`com/llamalad7/mixinextras/injector/ModifyExpressionValue.class` 已确认存在于 `mixinextras-neoforge-0.5.3.jar`。

```java
@Mixin(value = dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision.class, remap = false)
public class SubLevelEntityCollisionGhostMixin {

    @ModifyExpressionValue(
        method = "collide",
        require = 1,
        remap = false,
        at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"))
    private static Iterable<SubLevel> sablestopnow$skipGhosted(
            final Iterable<SubLevel> original,
            final Entity entity, final Vec3 motion, final Vec3 velocity,
            final dev.ryanhcode.sable.api.math.LevelReusedVectors sink) {
        if (PhysicsGhosts.isEmpty() || !(entity instanceof Player)) return original;
        List<SubLevel> filtered = null;
        for (final SubLevel sub : original) {
            if (PhysicsGhosts.contains(sub.getUniqueId())) {
                if (filtered == null) filtered = new ArrayList<>(4);
                continue;
            }
            if (filtered != null) filtered.add(sub);
        }
        return filtered != null ? filtered : original;   // 无命中则零分配
    }
}
```
（`@ModifyExpressionValue` 的 handler 可追加目标方法的参数；`expect` 默认 1，正好匹配这里的唯一调用点。若参数捕获有兼容问题，可退化为 `@Redirect` 同签名，或去掉 `instanceof Player` 守卫改成对所有实体生效。）

**风险（低）**：
* 只影响「实体 vs sub-level 方块碰撞」，**不影响**：手杖射线选取（`mixin/clip_overwrite/BlockGetterMixin.clip` 自己调 `getAllIntersecting`）、挖方块、`Entity.getInBlockState`、`Entity.getOnPos`（`entities_stick_sublevels/effects/EntityMixin` L131-159）、寻路、`CanFallAtleastHelper`（L25-29）、`checkInsideBlocks`（`entities_in_blocks/EntityMixin` L35-73）、渲染。
* 保留 tracking ⇒ **玩家仍会被该体「带着走」**：`entities_stick_sublevels/player/ServerPlayerMixin.tick`（L30-51）每 tick 按 pose 差量搬玩家；`LocalPlayer` 侧还有 inherited velocity 机制。也就是「不再被顶出/不再站在上面，但仍随体平移」。若这一条也不可接受，只能再动 tracking，风险见方案 D。
* 拖拽者若站在被幽灵化的体上会掉下去（客户端也算「无碰撞」，这是需求要的）；同时 `Entity.getOnPos` 仍会把该体方块当成脚下支撑（plot 局部 BlockPos），会有少量残留交互（脚步声/`updateEntityAfterFallOn` 类）——可另开一个 hook 处理（见下）。
* 需要**双端生效**：只挂服务端 ⇒ 拖拽者自己仍被客户端顶出。

### ❌ 方案 B：Hook `Sable.HELPER.getAllIntersecting(...)`（`ActiveSableCompanion`）全局过滤

**高风险，不要做。** 该方法是共享 broad-phase，被上述 6+ 处复用；全局过滤会让被幽灵化的体**直接无法被手杖射线选中 / 无法继续拖拽与锁定**（`BlockGetterMixin.clip` L102 就靠它），并且会让 `getInBlockState` / `getOnPos` / `checkInsideBlocks`（仙人掌、火、传送门效果）一起失效。

### ❌ 方案 C：直接跳过 `collide` 的 ServerPlayer 早退分支

（例如 `@Inject(method="collide", at=@At("HEAD"), cancellable=true)` 返回一个 `motion=collisionMotionMoj` 的 `CollisionInfo`。）
* 语义上等价于「服务端不再把该玩家视为站在这个体上」，但**风险明确**：`handleMovePlayer` L961 的 `clientIsFloating` 判定在船上恒成立（`noBlocksAround(player)==true`、`verticalCollisionBelow` 不再被设置），持续 80 tick 会被判定「floating too long」并踢出（L270-275）。
* 而且**没必要**：客户端一旦幽灵化，客户端会停止 tracking，move 包改发世界坐标，服务端 `getTrackingSubLevel` 自然变 null，假地面分支自然不再命中。
* 若坚持要做：只能 HEAD 早退（`motion` 必须赋值，`EntityMixin` L157 会读 `.motion`；`inheritedMotion/firstCollisions` 可为 null，L189/L60 都有 null 检查），**绝不能**用「返回 null tracking 子级」的方式。

### ❌ 方案 D：把 `getTrackingSubLevel` 的两处调用（L55 / L68）改成「被幽灵化则返回 null」

（`@ModifyExpressionValue(..., expect = 2)` 可命中两处。）
* 会**顺带清空实体的 `sable$trackingSubLevel` 字段**（`EntityMixin.sable$collideRedirect` L140-151：`else if (!(entity instanceof ServerPlayer)) this.sable$trackingSubLevel = null;`）。
* 后果链：`ServerGamePacketListenerImplMixin`(stick_sublevels) L31-38 的 `isChangingDimension()` 豁免失效 → 1.21.1 `handleMovePlayer` L915-923 的「moved too quickly」会把高速船上的玩家 `teleport()` 回 firstGood 位置 → 反复橡皮筋。
* 同时丢掉骑乘继承、`getOnPos` 覆盖、边界回退等行为。**不建议**。

### ❌ 方案 E：从玩家侧下手（属性/`isSpectator`/传送到体外）

要么过宽（该玩家对所有方块都不碰），要么可见跳变，都不能满足「只对本 sub-level 生效」。

### 可选加强：让「完全幽灵」更彻底
若希望被拖拽体对玩家**连方块内部效果也没有**，可在 `entities_in_blocks/EntityMixin.checkInsideBlocks` 的 `getAllIntersecting`（L40）加同一过滤；若希望玩家不再被「边缘回退」逻辑影响，可在 `CanFallAtleastHelper.canFallAtleastWithSubLevels`（L29）加同一过滤。两者都在 common 侧、都只读 UUID，可复用同一个注册表。

---

## 4. 服务端如何知道「这个体正在被拖拽」

### 4.1 Simulated 没有干净的公开 API/observer（已核实）
`dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler` 的全部公开成员：
`get(ServerLevel)`、`tick()`、`physicsTick(SubLevelPhysicsSystem)`、`drag(UUID,UUID,Vector3dc,Vector3dc,Quaterniondc)`、`stopDragging(UUID)`、`toggleLock(UUID)`、`isLocked(SubLevel)`、`applyLockIfNeeded(SubLevel)`、`removeLock(SubLevel)`、`sendAllData(Player)`、`save(...)`。
拖拽会话本身是 **private**：
```java
private final Map<UUID, Lock> locks = new Object2ObjectOpenHashMap<>();                 // L51
private final Map<UUID, DragSession> draggingSessions = new Object2ObjectOpenHashMap<>(); // L52
...
private static class DragSession {                                                     // L276
    private final ServerSubLevel subLevel;                                              // L282
```
且会话的移除不止在 `stopDragging`：
```java
public void tick() {                                        // L110
    while (iter.hasNext()) {
        ...
        if (player == null || !PhysicsStaffItem.isHolding(player)) {
            session.onRemoved(); iter.remove(); this.markDraggingSessionsDirty(); continue;  // L119-124
        }
        if (session.isMarkedForRemoval()) { session.onRemoved(); iter.remove(); ... }
```
（服务端入口：`PhysicsStaffDragPacket.handle` L36-42 每 tick → `handler.drag(...)`；`PhysicsStaffActionPacket.handle` L66-68 STOP_DRAG → `stopDragging`。）

客户端侧倒是有公开读取点：`PhysicsStaffClientHandler.getDragSession()`（L411-413，仅本地玩家）以及 private `serverDragSessions`（L65，其它玩家；L415-422 有 public setter；可用其中 anchor + `Sable.HELPER.getContaining(level, anchor)` 反查体，正如 L272 渲染光束那样）。

### 4.2 建议：服务端权威计算 + 复用现有 S2C 同步（而不是让客户端各自读 Simulated）
1. **继续用现有的 `StaffEnhanceServer.currentlyDragged(level)`（反射读 `draggingSessions`）**（`StaffEnhanceServer.java` L466-495）。它已经覆盖了 `tick()` 自动移除、`stopDragging`、以及本模组整组拖拽；换其它读法反而要补 `tick()` 这条移除路径。
   * 但它每次调用都新建 `HashSet` 并做反射 → **不要放进碰撞热路径**；在 `StaffEnhanceServer.serverFeatures(server)`（已每 tick 调用，L537-552）里**每 tick 算一次并缓存**即可（碰撞侧只做 `UUID` 的 `Set.contains`）。
   * 更"干净但更侵入"的替代：`@Mixin` `PhysicsStaffServerHandler`，`@Shadow` 私有 `draggingSessions`，再 `@Mixin(targets="dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler$DragSession")` + `@Shadow private ServerSubLevel subLevel` 暴露 getter（零反射，代价是 2 个额外 Mixin 依赖 Simulated 的私有内部结构）。
2. **状态同步**：服务端算出「当前生效的幽灵集」后推给客户端。本 mod 已有完整基建：
   * 服务端 `StaffCollisionData`（`server/StaffCollisionData.java`，SavedData，per-level `Set<UUID>`）；
   * `StaffEnhanceNetworking.SyncNoCollisionPayload`（L804-830）→ 客户端 `StaffEnhanceClientHandler.setNoCollision(dimension, ids)`（L878-885），客户端已有 `getNoCollision()` 集合。
   * ⚠️ 但 `SyncNoCollisionPayload` 的语义是「**手动标记**」集（渲染图标 + 手动 toggle UI 的乐观更新都基于它，见 `StaffEnhanceClientHandler` L857-875）。如果把「拖拽中动态幽灵集」塞进同一个集合，UI 会把被拖拽的体误判为「已标记」→ 建议**新增一个 payload**（如 `SyncActiveGhostsPayload(dimension, ids)`），或在该 payload 里加第二个列表字段。
   * 建议语义：`activeGhosts = marked ∪ (dragged if 自动幽灵开关开)`，其中 `marked` 是手动 per-体开关（已有），`dragged` 只含**正在被拖拽的体**（满足「只对当前被拖拽的体生效」）。变化时才广播（比较集合），并给新登录玩家补发。

### 4.3 Mixin 侧读取（双端同一份状态，避免 common Mixin 引用客户端类）
新增一个 common 小注册表（放在非 client 包），服务端在计算后写入、客户端在 payload handler 里写入，Mixin 只读它：

```java
public final class PhysicsGhosts {                       // 双端共用的无客户端依赖类
    private static volatile Set<UUID> GHOSTS = Set.of(); // 客户端 tick 线程/服务端线程都会读，用不可变快照 + volatile
    public static boolean isEmpty() { return GHOSTS.isEmpty(); }
    public static boolean contains(final UUID id) { return GHOSTS.contains(id); }
    public static void set(final Collection<UUID> ids) { GHOSTS = ids.isEmpty() ? Set.of() : Set.copyOf(ids); }
    public static void clear() { GHOSTS = Set.of(); }
}
```
* 服务端：`StaffEnhanceServer.serverFeatures` 每 tick 计算 → `PhysicsGhosts.set(...)` + （变化时）广播 payload。
* 客户端：`StaffEnhanceClientHandler.setNoCollision`/新 payload handler → `PhysicsGhosts.set(ids)`；掉线/切维度时 `clear()`。
* `SubLevel.getUniqueId()` 在 `ClientSubLevel`/`ServerSubLevel` 上都有（common 基类），同 UUID 比较双端通用。
* 新 Mixin 必须注册进 `src/main/resources/sablestopnow.mixins.json` 的 **`"mixins"`（common）** 数组（当前只有 `"client"` 一个输入 Mixin；`"server": []`）。

---

## 一句话结论
**有可行 hook**：`SubLevelEntityCollision.collide` 内对 `Sable.HELPER.getAllIntersecting(level, considerationBounds)` 的返回值做 `@ModifyExpressionValue` 过滤（`remap = false`，`require = 1`），过滤条件来自 mod 自己维护、服务端权威、双端同步的 UUID 集合；**不要**动 `getTrackingSubLevel`/tracking 字段，**不要**动全局 `getAllIntersecting`，**不要**跳过 ServerPlayer 假地面分支。唯一必须接受的残留行为是：被幽灵化的体仍会通过 tracking 把站在上面的玩家「平移带着走」（不再顶出、不再阻挡），若要连这个也去掉，会触发 moved-too-quickly 橡皮筋与 floating 踢出风险（方案 D）。
