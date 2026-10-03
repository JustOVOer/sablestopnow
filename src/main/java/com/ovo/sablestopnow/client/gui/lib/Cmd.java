package com.ovo.sablestopnow.client.gui.lib;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 命令总线：<b>所有状态变更的唯一入口</b>。
 *
 * <h2>为什么要有它</h2>
 * 「事件是通知，不是命令」——控件不应该在事件处理里直接去改别的控件或直接发包。
 * 事件只说明「玩家做了什么」，真正的改动被包成一条<b>具名命令</b>，从这里出去。这样得到三件事：
 * <ul>
 *   <li><b>可观测</b>：每条命令都留痕，出问题能看出「点了按钮但命令没派发」还是
 *       「命令派发了但没人接收」——这两种在画面上完全一样；</li>
 *   <li><b>可回放</b>：{@link #history()} 是一串名字，把它原样重放就能复现操作序列；</li>
 *   <li><b>防拼写错</b>：{@link #declare} 登记「这个界面本该派发哪些命令」，
 *       {@link UiLint} 就能报出「声明了但从没跑过」和「跑了但没声明」。</li>
 * </ul>
 *
 * <p>约定：命令体里只写「改状态 / 发包」这类一次性的动作，<b>不要再派发命令</b>
 * （嵌套派发会让 {@link #history()} 变成一团乱麻）。确实需要嵌套时，内层命令必须带自己的名字。
 */
public final class Cmd {

    /** 历史长度上限（够回溯一次完整的操作序列，又不会无限增长）。 */
    private static final int HISTORY_LIMIT = 256;

    private static final Set<String> DECLARED = new LinkedHashSet<>();
    private static final List<String> HISTORY = new ArrayList<>();
    private static final java.util.Map<String, Integer> COUNTS = new java.util.LinkedHashMap<>();

    private static boolean dispatching;

    private Cmd() {
    }

    /**
     * 登记一条命令名。
     *
     * <p>在界面搭建时调用，声明「这些命令由我负责派发」。自检据此找出
     * 「写了没调用」——这正是用户反馈里「经常有写了没调用」的那一类问题。
     */
    public static void declare(final String... names) {
        for (final String name : names) {
            DECLARED.add(name);
        }
    }

    /** 派发一条命令（唯一的状态变更入口）。 */
    public static void run(final String name, final Runnable body) {
        COUNTS.merge(name, 1, Integer::sum);
        HISTORY.add(name);
        if (HISTORY.size() > HISTORY_LIMIT) {
            HISTORY.remove(0);
        }
        if (dispatching) {
            // 嵌套派发是设计错误，但没必要让玩家崩掉：照常执行，自检会把它标出来。
            UiLint.noteNestedCommand(name);
        }
        dispatching = true;
        try {
            body.run();
        } finally {
            dispatching = false;
        }
    }

    public static List<String> history() {
        return new ArrayList<>(HISTORY);
    }

    public static Set<String> declared() {
        return new LinkedHashSet<>(DECLARED);
    }

    public static int count(final String name) {
        return COUNTS.getOrDefault(name, 0);
    }

    public static java.util.Map<String, Integer> counts() {
        return new java.util.LinkedHashMap<>(COUNTS);
    }

    @Nullable
    public static String last() {
        return HISTORY.isEmpty() ? null : HISTORY.get(HISTORY.size() - 1);
    }

    public static void reset() {
        DECLARED.clear();
        HISTORY.clear();
        COUNTS.clear();
        dispatching = false;
    }
}
