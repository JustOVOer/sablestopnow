package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.SablestopNow;

import java.util.UUID;

/**
 * **测试对象标记** —— 由 debug tab 里那两个按钮设置 ✓。
 *
 * <h2>为什么需要它</h2>
 *
 * 排查"凸包与视觉差 0.3 格"这类问题时，场上往往有**多个**实体与**多个**物理结构 ✓，
 * 而逐步轨迹（{@code HullTrace}）会把它们**全部**记下来 ✗ —— 读数里混着别人的数据 ✓
 * 分不清哪一行才是我们要看的那一个 ✗。
 *
 * <p>所以加两个标记 ✓：
 * <ul>
 *   <li><b>测试圆柱</b> ✓ —— 我们要观察的那个实体（凸包）✓</li>
 *   <li><b>测试物理结构</b> ✓ —— 压在它上面 / 放在下面的那个结构 ✓</li>
 * </ul>
 *
 * <h2>标记之后发生什么（两者都要 ✓ 用户要求）</h2>
 * <ol>
 *   <li><b>筛选追踪</b> ✓ —— {@code HullTrace} 只记被标记的对象 ✓ 读数立刻干净 ✓</li>
 *   <li><b>显示状态</b> ✓ —— 日志里打出标记 ✓ 且模型界面顶栏显示当前标记 ✓
 *       （这样你能当场确认"标对了没有" ✓）</li>
 * </ol>
 *
 * <h2>为什么放在 client.model 包</h2>
 *
 * 标记由**客户端界面**设置 ✓（debug tab 的按钮 ✓），
 * 但**服务端的追踪**要读它 ✗ —— 所以这里只放**纯数据** ✓，
 * 由 {@code HullTrace} 通过 {@link #cylinderMountId()} 之类的方法读取 ✓。
 * 两个标记都是"最后一次点击的那个" ✓ —— 再点一次同一个按钮即清除 ✓（按钮本身就是开关 ✓）。
 */
public final class TestMarks {

    private TestMarks() {
    }

    /** 被标记为"测试圆柱"的实体（solid）UUID ✓；null = 未标记 ✓。 */
    private static volatile UUID cylinderSolid;

    /** 被标记为"测试圆柱"的实体所在子结构的 id（= 刚体 id ✓）；-1 = 未标记 ✓。 */
    private static volatile int cylinderMountId = -1;

    /** 被标记为"测试物理结构"的子结构 UUID ✓；null = 未标记 ✓。 */
    private static volatile UUID structureId;

    /** 被标记为"测试物理结构"的刚体 id ✓；-1 = 未标记 ✓。 */
    private static volatile int structureMountId = -1;

    // ---------------------------------------------------------------- 圆柱

    /**
     * 标记/取消标记"测试圆柱" ✓ —— 同一个对象再标一次就是取消 ✓。
     *
     * @return true 表示现在是"已标记" ✓；false 表示刚被取消 ✓
     */
    public static boolean markCylinder(final UUID solidId, final int mountId) {
        if (solidId == null) {
            return false;
        }
        if (solidId.equals(cylinderSolid)) {
            cylinderSolid = null;
            cylinderMountId = -1;
            SablestopNow.LOGGER.warn("[test-mark] 取消标记测试圆柱");
            return false;
        }
        cylinderSolid = solidId;
        cylinderMountId = mountId;
        SablestopNow.LOGGER.warn("[test-mark] 标记测试圆柱: solid={} mountId={}", shortId(solidId), mountId);
        return true;
    }

    public static UUID cylinderSolid() {
        return cylinderSolid;
    }

    public static int cylinderMountId() {
        return cylinderMountId;
    }

    public static boolean hasCylinder() {
        return cylinderSolid != null;
    }

    // ---------------------------------------------------------------- 结构

    public static boolean markStructure(final UUID subLevelId, final int mountId) {
        if (subLevelId == null) {
            return false;
        }
        if (subLevelId.equals(structureId)) {
            structureId = null;
            structureMountId = -1;
            SablestopNow.LOGGER.warn("[test-mark] 取消标记测试物理结构");
            return false;
        }
        structureId = subLevelId;
        structureMountId = mountId;
        SablestopNow.LOGGER.warn("[test-mark] 标记测试物理结构: sub={} mountId={}",
                shortId(subLevelId), mountId);
        return true;
    }

    public static UUID structureId() {
        return structureId;
    }

    public static int structureMountId() {
        return structureMountId;
    }

    public static boolean hasStructure() {
        return structureId != null;
    }

    // ---------------------------------------------------------------- 工具

    /** 顶栏 HUD 用的一行状态 ✓（未标记时返回 null ✓ 界面就不画 ✓）。 */
    public static String hudLine() {
        if (!hasCylinder() && !hasStructure()) {
            return null;
        }
        final StringBuilder sb = new StringBuilder("debug:");
        if (hasCylinder()) {
            sb.append(" 圆柱=").append(shortId(cylinderSolid)).append("(#").append(cylinderMountId).append(')');
        }
        if (hasStructure()) {
            sb.append(" 结构=").append(shortId(structureId)).append("(#").append(structureMountId).append(')');
        }
        return sb.toString();
    }

    /** 重建/卸载时清掉失效的标记 ✓（避免追踪一个已经不存在的刚体 ✗）。 */
    public static void clearAll() {
        cylinderSolid = null;
        cylinderMountId = -1;
        structureId = null;
        structureMountId = -1;
    }

    private static String shortId(final UUID id) {
        return id == null ? "-" : id.toString().substring(0, 8);
    }
}
