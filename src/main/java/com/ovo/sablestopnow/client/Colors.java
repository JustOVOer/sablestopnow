package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNowConfig;

/**
 * **可调颜色**的统一入口 ✓（item 2）。
 *
 * <p>所有渲染颜色的唯一来源：读 {@link SablestopNowConfig} 里的 8 项十六进制值，
 * 解析成渲染用的 `float[]{r,g,b,a}`（0..1）✓ 解析失败一律回退到内置默认值，
 * 坏配置不会把渲染搞崩 ✓
 */
public final class Colors {

    private Colors() {
    }

    /** 草图线（编辑中） */
    public static float[] sketchLine() {
        return parse(SablestopNowConfig.INSTANCE.colorSketchLine.get(), 0xFF3FF2D9);
    }

    /** 完全定义的草图线 */
    public static float[] fullyDefined() {
        return parse(SablestopNowConfig.INSTANCE.colorFullyDefined.get(), 0xFF59FF52);
    }

    /** 选中 */
    public static float[] selected() {
        return parse(SablestopNowConfig.INSTANCE.colorSelected.get(), 0xFFFF8C26);
    }

    /** 悬停 */
    public static float[] hover() {
        return parse(SablestopNowConfig.INSTANCE.colorHover.get(), 0xFFFFFFFF);
    }

    /** 参考几何 */
    public static float[] reference() {
        return parse(SablestopNowConfig.INSTANCE.colorReference.get(), 0xFF9EA8C7);
    }

    /** 尺寸标注 */
    public static float[] dimension() {
        return parse(SablestopNowConfig.INSTANCE.colorDimension.get(), 0xFFFFFFFF);
    }

    /** 草图网格（grey 值 = RGB 平均，alpha 单独传） */
    public static int gridArgb() {
        return (int) Long.parseLong(safeHex(
                SablestopNowConfig.INSTANCE.colorGrid.get(), "FFC8C8C8"), 16);
    }

    /** 模型操作预览线框 */
    public static float[] preview() {
        return parse(SablestopNowConfig.INSTANCE.colorPreview.get(), 0xFFFFD400);
    }

    /** 十六进制 → float[]{r,g,b,a} ✓（解析失败用 fallback）。 */
    public static float[] parse(final String hex, final int fallback) {
        final int argb = (int) Long.parseLong(safeHex(hex, String.format("%08X", fallback)), 16);
        return new float[] {
                ((argb >> 16) & 0xFF) / 255.0f,
                ((argb >> 8) & 0xFF) / 255.0f,
                (argb & 0xFF) / 255.0f,
                ((argb >>> 24) & 0xFF) / 255.0f,
        };
    }

    private static String safeHex(final String hex, final String fallback) {
        if (hex == null) {
            return fallback;
        }
        String s = hex.trim();
        if (s.startsWith("#")) {
            s = s.substring(1);
        }
        if (s.length() == 6) {
            s = "FF" + s; // 省略 alpha ⇒ 不透明 ✓
        }
        if (s.length() != 8 || !s.matches("[0-9a-fA-F]{8}")) {
            return fallback;
        }
        return s;
    }
}
