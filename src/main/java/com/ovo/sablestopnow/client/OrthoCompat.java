package com.ovo.sablestopnow.client;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * 与 **OrthoCamera**（1.21.1 NeoForge 非官方移植，modId = {@code orthocamera}）的兼容层 ✓
 *
 * <h2>为什么用反射而不是直接 import</h2>
 * 本项目的离线校验脚本（{@code tools/verify-all.ps1}）用的是自己的 javac 类路径，
 * 不包含外部 mod 的 jar；直接 import 会让整条离线门禁挂掉（实测：FULL_TYPECHECK 失败）。
 * 反射只按**字符串**找类，门禁和"前置没装"两种情况都不会炸 —— 拿不到就静默降级。
 * {@code neoforge.mods.toml} 里仍然声明了 required 依赖（客户端），所以正常游玩时一定在。
 *
 * <h2>做什么</h2>
 * 进草图 → 打开正交（SolidWorks 的草图默认是正交视图，没有透视变形）；
 * 退草图 → 恢复玩家进入前的设置（玩家自己开着的正交不会因为我们被关掉）。
 */
public final class OrthoCompat {

    private static final String MOD_CLASS =
            "com.dimaskama.orthocamera.client.OrthoCameraMod";

    private OrthoCompat() {
    }

    /** 进入草图前的 enabled 原值；null = 当前不是"我们强制正交"的状态 */
    private static Boolean savedEnabled;
    /** 上一帧是否在编辑草图（只处理状态跳变） */
    private static boolean wasEditing;

    /** 每帧由渲染器调用：只在"进入/退出草图"那一帧做事 ✓ */
    public static void onFrame(final boolean editing) {
        if (editing == wasEditing) {
            return;
        }
        wasEditing = editing;
        if (editing) {
            enter();
        } else {
            exit();
        }
    }

    private static Object config() {
        try {
            final Class<?> c = Class.forName(MOD_CLASS);
            final Field f = c.getField("CONFIG");
            return f.get(null);
        } catch (final Throwable t) {
            return null;
        }
    }

    private static boolean readEnabled(final Object cfg) {
        try {
            return cfg.getClass().getField("enabled").getBoolean(cfg);
        } catch (final Throwable t) {
            return false;
        }
    }

    private static void writeEnabled(final Object cfg, final boolean on) {
        try {
            cfg.getClass().getField("enabled").setBoolean(cfg, on);
            final Method dirty = cfg.getClass().getMethod("setDirty", boolean.class);
            dirty.invoke(cfg, true);
            final Method save = cfg.getClass().getMethod("save");
            save.invoke(cfg);
        } catch (final Throwable t) {
            com.ovo.sablestopnow.SablestopNow.LOGGER.warn("[ortho] write failed", t);
        }
    }

    /** 进草图：开正交，并记住原状态 */
    private static void enter() {
        if (savedEnabled != null) {
            return;
        }
        final Object cfg = config();
        if (cfg == null) {
            return; // 前置没装：静默降级
        }
        final boolean now = readEnabled(cfg);
        savedEnabled = now;
        if (!now) {
            writeEnabled(cfg, true);
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info(
                "[ortho] sketch enter: ortho on (was {})", now);
    }

    /** 退草图：恢复进入前的状态（玩家自己开的不会被我们关掉） */
    private static void exit() {
        final Boolean prev = savedEnabled;
        savedEnabled = null;
        if (prev == null) {
            return;
        }
        final Object cfg = config();
        if (cfg == null) {
            return;
        }
        if (readEnabled(cfg) != prev) {
            writeEnabled(cfg, prev);
        }
        com.ovo.sablestopnow.SablestopNow.LOGGER.info("[ortho] sketch exit: restored {}", prev);
    }
}
