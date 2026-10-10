package com.ovo.sablestopnow.client.gui;

/**
 * 配置界面的**分组键**（唯一来源 ✓ / docs/gui-统一.md 的 B 项）。
 *
 * <h2>为什么这里能用枚举、而"选项名"不行</h2>
 * <ul>
 *   <li><b>分组键</b>是**纯应用侧**的 ✓ —— 只用来：① 给选项声明归属 ✓ ② 给界面排序 ✓
 *       ③ 拼 i18n 键 ✓。它不与 TOML 键绑定 ✗ ⇒ 所以可以做成枚举 ✓，
 *       调用点写 {@link #FORCE} 而不是 {@code "gui.sablestopnow.section.force"} ✓
 *       ⇒ 打错字**编译期就报** ✓✓（这正是 B 想要的效果 ✓）。</li>
 *   <li><b>选项名</b>不行 ✗：TOML 键必须是字符串（{@code define("hull_inset", …)}）✓，
 *       拼 i18n 键也依赖它 ✓ ⇒ 即使做成枚举，{@code enum.name()} 仍是字符串 ✗ ⇒
 *       打错字只是换了个地方 ✓。那一类的防线是**离线断言** ✓
 *       （{@code tools/GuiLangCheck.ps1} 的 I7 ✓：每个选项的 label/desc 必须在双语里存在 ✓）。</li>
 * </ul>
 *
 * <h2>每个枚举值的 {@code key()} 必须与原先的字面量完全一致</h2>
 * 否则已有配置文件/已有翻译会失配 ✗ ⇒ {@code tools/GuiSpecCheck.ps1} 会核对 ✓。
 */
public enum Sections {

    /** 力过滤 / 阈值 / 排除组。 */
    FORCE("force"),
    /** 组装相关（锁定新结构 / 放置碰撞检查 / 拆分确认）。 */
    ASSEMBLY("assembly"),
    /** 超速锁定。 */
    SPEED("speed"),
    /** 凸包 / 凸分解。 */
    HULL("hull"),
    /** 手杖：核心开关。 */
    STAFF_CORE("staff_core"),
    /** 手杖：描边。 */
    STAFF_OUTLINE("staff_outline"),
    /** 手杖：操控（旋转/滚轮/居中）。 */
    STAFF_CONTROL("staff_control"),
    /** 手杖：缩放。 */
    STAFF_SCALE("staff_scale"),
    /** 手杖：碰撞。 */
    STAFF_COLLISION("staff_collision"),
    /** 手杖：按键入口。 */
    STAFF_KEYS("staff_keys"),
    /** 护目镜：描边。 */
    OUTLINE("outline"),
    /** 护目镜：坐标轴。 */
    AXIS("axis"),
    /** 护目镜：界面自身。 */
    UI("ui");

    /** i18n 键前缀（与既有 lang 文件一致 ✓ 不可改动 ✗）。 */
    private static final String PREFIX = "gui.sablestopnow.section.";

    private final String id;

    Sections(final String id) {
        this.id = id;
    }

    /** 短名（与既有 lang 键的后缀一致 ✓）。 */
    public String id() {
        return this.id;
    }

    /** 完整 i18n 键 ✓（{@code ConfigOption#section(...)} 与界面排序都用它 ✓）。 */
    public String key() {
        return PREFIX + this.id;
    }

    /** 从完整键反查 ✓（检查器/迁移用 ✓ 找不到返回 {@code null} ✓）。 */
    public static Sections fromKey(final String key) {
        if (key == null) {
            return null;
        }
        for (final Sections value : values()) {
            if (value.key().equals(key)) {
                return value;
            }
        }
        return null;
    }
}
