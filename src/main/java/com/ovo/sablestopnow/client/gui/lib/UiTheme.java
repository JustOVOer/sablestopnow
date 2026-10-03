package com.ovo.sablestopnow.client.gui.lib;

import net.minecraft.client.gui.Font;

/**
 * GUI 库的主题：颜色、字号缩放、间距、行高。
 *
 * <p>配色取自 {@code newGUI/草图.png} 与 {@code newConfigGUI/GUI*.png} 的取色结果
 * （主蓝 {@code #262E8E}、强调蓝 {@code #3F48CC}/{@code #2C80E1}、钢蓝 {@code #7092BE}），
 * 与模组设置界面保持一致。
 *
 * <p><b>字号缩放</b>：原版字体固定 9px 高，行距压不下去。库里所有文本一律按
 * {@link #fontScale}（默认 0.75）缩放渲染，于是行高可以做到 7px，一行能塞下的内容明显变多。
 * 所有涉及文本宽高的计算都必须走 {@link #textW}/{@link #lineH}，不要直接用 {@code font.width}。
 */
public class UiTheme {

    // ---- 颜色（ARGB） ----
    // 取色自 newGUI/草图.png 与 newConfigGUI/GUI*.png，并按用户反馈整体调深一档
    // （要求「更深一点的普蓝」）：普蓝 ≈ #1A2168 / #141B58，强调色也一起压深，
    // 否则深底 + 亮强调会显得脏。
    /** 面板底色。 */
    public int panel = 0xF21A2168;
    /** 更深一层的底色（列表背景、输入框背景）。 */
    public int panelDark = 0xF0111648;
    /** 稍亮的底（行悬停）。 */
    public int panelLight = 0xF0262E86;
    /** 顶栏 / 标题栏底色。 */
    public int header = 0xFF121849;
    /** 强调色（选中、主按钮）。 */
    public int accent = 0xFF2E3AA8;
    /** 强调色悬停。 */
    public int accentHover = 0xFF3A6FD8;
    /** 强调色按下。 */
    public int accentPress = 0xFF223F9E;
    /** 选中行底色。 */
    public int select = 0xCC223F9E;
    /** 边框。 */
    public int border = 0xFF4A56A8;
    /** 弱边框（行分隔）。 */
    public int borderSoft = 0x552E3AA8;
    /** 正文。 */
    public int text = 0xFFFFFFFF;
    /** 次要文字。 */
    public int textDim = 0xFFB9C0EE;
    /** 禁用文字。 */
    public int textDisabled = 0xFF7A80B0;
    /** 危险（删除）。 */
    public int danger = 0xFFE05252;
    /** 成功 / 开启。 */
    public int ok = 0xFF57D07A;
    /** 半透明遮罩（弹层背景）。 */
    public int scrim = 0x99000000;
    /** 滚动条滑块。 */
    public int scrollbar = 0x99B9C0EE;
    /** 钢蓝（次要强调，参考图里的 #7092BE）。 */
    public int steel = 0xFF7092BE;

    /** 按钮点击扫光的浅蓝（用户指定：点击后从左到右刷成浅蓝色）。 */
    public int sweep = 0xFF9FD8FF;
    /** 扫光前缘的亮边。 */
    public int sweepEdge = 0xFFD8EEFF;
    /** 扫光覆盖区的字体颜色（浅蓝底上必须是深色才读得清）。 */
    public int sweepText = 0xFF0B1533;

    /**
     * 文本缩放。
     *
     * <p>⚠ <b>默认改成 1.0（原版字号）</b>：原版字体是<b>点阵</b>字体，非整数倍缩放会被线性过滤糊掉
     * （用户实测反馈「文字缩放导致渲染不清」）。要更紧凑的行距应该压 padding 与行高，而不是缩放点阵字。
     * 仍然保留这个配置项（设置 → 界面字号缩放），想要更密就自己调 0.9/0.8，代价就是发虚。
     */
    public float fontScale = 1.0f;
    /** 标准行高（未缩放像素前的基准 9px 乘缩放，最小 7）。 */
    public int baseLine = 9;
    /** 标准行高。 */
    public int rowH = 11;
    /** 水平内边距。 */
    public int padX = 4;
    /** 垂直内边距。 */
    public int padY = 3;
    /** 元素间距。 */
    public int gap = 2;
    /** 面板圆角（用切角近似，0 = 直角）。 */
    public int radius = 2;

    private static UiTheme current = new UiTheme();

    public static UiTheme current() {
        return current;
    }

    public static void setCurrent(final UiTheme theme) {
        current = theme == null ? new UiTheme() : theme;
    }

    /** 文本高度（含缩放）。 */
    public int lineH() {
        return Math.max(7, Math.round(baseLine * fontScale));
    }

    /** 文本宽度（含缩放）。 */
    public int textW(final Font font, final String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return Math.round(font.width(text) * fontScale);
    }

    /** 该宽度能放下多少个字符（粗略，用于裁剪缓存）。 */
    public int fitChars(final Font font, final String text, final int maxWidth) {
        if (text == null) {
            return 0;
        }
        int w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += font.width(String.valueOf(text.charAt(i)));
            if (Math.round(w * fontScale) > maxWidth) {
                return i;
            }
        }
        return text.length();
    }
}
