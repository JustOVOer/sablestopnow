package com.ovo.sablestopnow.client.model;

import net.minecraft.client.Minecraft;

import java.util.Locale;

/**
 * **模型视图/草图的集中诊断日志** ✓（用户要求：日志抓全一点，一次测试就能定位问题）
 *
 * <p>为什么单独一个类：以前诊断散在七八处，每个人只管自己那一块，
 * 结果出现"画面正交但拾取按透视算"这种**跨模块**的问题时，日志里两个模块各说各话，
 * 拼不出完整现场。这里每秒打**一行**完整快照 —— 相机、正交、投影来源、
 * 鼠标射线、草图层面的 (u,v)、拾取容差、当前吸附的图元，全在同一行里。
 *
 * <p>只在模型模式打开时打（{@link ModelMode#isOn()}），1 Hz，开销可忽略。
 */
public final class ModelDiag {

    private ModelDiag() {
    }

    private static final long INTERVAL_MS = 1000L;
    private static long lastMs;

    private static final String F = "%.3f";

    /** 每秒调用一次（渲染事件里）✓ */
    public static void tick(final Minecraft mc) {
        final long now = System.currentTimeMillis();
        if (now - lastMs < INTERVAL_MS) {
            return;
        }
        lastMs = now;
        try {
            final StringBuilder sb = new StringBuilder(320);
            sb.append("[diag]");
            // ---- 相机 ----
            sb.append(" cam=(yaw=").append(String.format(Locale.ROOT, F, ModelCamera.yaw()))
                    .append(" pitch=").append(String.format(Locale.ROOT, F, ModelCamera.pitch()))
                    .append(" roll=").append(String.format(Locale.ROOT, F, ModelCamera.roll()))
                    .append("/").append(String.format(Locale.ROOT, F, ModelCamera.rollGoal()))
                    .append(" dist=").append(String.format(Locale.ROOT, "%.1f", ModelCamera.distance()))
                    .append(")");
            // ---- 正交 ----
            sb.append(" ortho=").append(com.ovo.sablestopnow.client.OrthoCompat.stateSummary());
            // ---- 投影/矩阵 ----
            sb.append(" proj=").append(com.ovo.sablestopnow.client.OrthoCompat.projectionSource())
                    .append(" useMatrices=").append(ModelCamera.usingMatrices() ? 1 : 0)
                    .append(" wpp=").append(String.format(Locale.ROOT, "%.5f", ModelCamera.worldPerPixel(mc)));
            // ---- 鼠标射线 + 草图坐标 + 容差 + 悬停 ----
            sb.append(' ').append(com.ovo.sablestopnow.client.SketchClient.diagSummary(mc));
            com.ovo.sablestopnow.SablestopNow.LOGGER.info(sb.toString());
        } catch (final Throwable t) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[diag] failed", t);
        }
    }
}
