package com.ovo.sablestopnow.client.gui.lib;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 一份<b>可观察的状态</b>——GUI 库的唯一真相单位。
 *
 * <h2>为什么需要它</h2>
 * 之前的库是「控件自己持值」：{@link Slider} 有自己的 {@code value}、{@link TextField} 有自己的
 * {@code text}、{@link Checkbox} 用自己的 supplier。于是同一个业务量在界面上有<b>好几份副本</b>，
 * 联动只能靠人手写「A 改了去改 B、B 改了去改 A」，一旦漏掉一处就永久不一致 ——
 * 用户实测反馈的「设置施力点时滑动条和输入框没有联动」就是这么来的。
 *
 * <p>改成 {@code Prop} 之后：控件<b>只读</b>它、只发命令改它，滑条和输入框绑<b>同一个</b> Prop，
 * 于是联动是<b>派生</b>出来的，不存在「忘了同步」这种失败模式。
 *
 * <h2>约定</h2>
 * <ul>
 *   <li>{@link #set} 对<b>相等的值不通知</b>。服务端回声、重建面板时的重复赋值因此不会引发事件风暴
 *       （否则「面板重建 → 写 Prop → 触发重建」会自激）。</li>
 *   <li>通知期间再次 {@link #set} 同一个 Prop 会被推迟到本轮通知结束后再广播，
 *       监听者不会在遍历过程中被重入打断。</li>
 *   <li>{@link #batch} 把一批写入合并成一次广播（事务），用于「一次改多个字段」的场合。</li>
 *   <li>每个 Prop 都在 {@link #ALL} 里登记，{@link UiLint} 靠它找出
 *       「写了没人读」「多处写入」「从未写入」这类低级错误。</li>
 * </ul>
 *
 * @param <T> 值类型；比较用 {@link Objects#equals}，所以最好是不可变值（String/Double/Boolean/record）
 */
public final class Prop<T> {

    /** 全部登记过的 Prop（调试/自检用；同一个 key 只会有一个实例）。 */
    private static final Map<String, Prop<?>> ALL = new LinkedHashMap<>();

    // ---- 事务/重入控制 ----
    private static int batchDepth;
    private static final List<Runnable> DEFERRED = new ArrayList<>();

    private final String key;
    private T value;
    private long version;
    private int writes;
    private int reads;
    @Nullable
    private String lastWriter;
    @Nullable
    private String declaredBy;

    private final List<Consumer<T>> listeners = new ArrayList<>();
    private boolean notifying;
    private boolean renotify;

    private Prop(final String key, final T initial) {
        this.key = key;
        this.value = initial;
    }

    // ============ 工厂 ============

    /**
     * 取（或创建）一个具名状态。
     *
     * <p>用 key 而不是每次 {@code new}：模型模式的左栏每 12 tick 就整棵重建一次，
     * 若状态跟着控件一起新建，既会丢掉用户正在编辑的内容，也会让调试注册表无限膨胀。
     * 状态必须活在<b>控件树之外</b>。
     */
    @SuppressWarnings("unchecked")
    public static synchronized <T> Prop<T> of(final String key, final T initial) {
        final Prop<?> existing = ALL.get(key);
        if (existing != null) {
            if (initial != null && existing.value == null && existing.writes == 0) {
                ((Prop<T>) existing).value = initial;
            }
            return (Prop<T>) existing;
        }
        final Prop<T> prop = new Prop<>(key, initial);
        prop.declaredBy = caller();
        ALL.put(key, prop);
        return prop;
    }

    /** 值类型不匹配时也强行换成新实例（只在自检报告里提示，不静默错乱）。 */
    @SuppressWarnings("unchecked")
    public static synchronized <T> Prop<T> ofTyped(final String key, final Class<T> type, final T initial) {
        final Prop<?> existing = ALL.get(key);
        if (existing != null && (existing.value == null || type.isInstance(existing.value))) {
            return (Prop<T>) existing;
        }
        final Prop<T> prop = new Prop<>(key, initial);
        prop.declaredBy = caller();
        ALL.put(key, prop);
        return prop;
    }

    /**
     * 控件内部的私有状态：<b>不登记</b>进全局注册表。
     *
     * <p>为什么需要这个口子：{@link TextField} 在没有外部真值时要有一份自己的文本。
     * 那份文本是控件实现细节（业务状态应该在控件树之外），而控件会随界面反复重建 ——
     * 登记的话每开一次设置界面就多留一个永远没人再用的 Prop。
     * 自检要看的「状态」是业务状态，不是控件的内部缓冲。
     */
    public static <T> Prop<T> local(final String key, final T initial) {
        return new Prop<>(key, initial);
    }

    /**
     * 派生状态：只读、由 {@code source} 推出来。
     *
     * <p>「联动是派生而不是同步」这条原则的载体：显示用的格式化文本、筛选后的列表、
     * 「是否可点」都可以这样长出来，而不是各自存一份再手工对齐。
     */
    public static <S, T> Prop<T> derived(final String key, final Prop<S> source, final Function<S, T> fn) {
        final Prop<T> out = of(key, fn.apply(source.get()));
        source.subscribe(s -> out.set(fn.apply(s)));
        return out;
    }

    // ============ 读写 ============

    public String key() {
        return key;
    }

    public T get() {
        reads++;
        return value;
    }

    /** 只读快照（不计入读取统计，供自检使用）。 */
    public T peek() {
        return value;
    }

    /**
     * 写入。
     *
     * @return true 表示值真的变了（并已广播）
     */
    public boolean set(final T newValue) {
        if (Objects.equals(value, newValue)) {
            return false;
        }
        value = newValue;
        version++;
        writes++;
        lastWriter = caller();
        broadcast(newValue);
        return true;
    }

    /** 就地把当前值改掉（记录一次写入，便于自检定位到写入点）。 */
    public void mutate(final Consumer<T> fn) {
        final T next = value;
        fn.accept(next);
        // 就地改的对象自己不会变「引用」，这里只是记账：调用方改完自己调 set/notify。
        writes++;
        lastWriter = caller();
        broadcast(next);
    }

    /** 值没变但需要通知（例如对象被就地修改过）。 */
    public void touch() {
        version++;
        broadcast(value);
    }

    public long version() {
        return version;
    }

    public int writes() {
        return writes;
    }

    public int reads() {
        return reads;
    }

    public int subscriberCount() {
        return listeners.size();
    }

    @Nullable
    public String lastWriter() {
        return lastWriter;
    }

    @Nullable
    public String declaredBy() {
        return declaredBy;
    }

    /** 订阅；返回值取消订阅。 */
    public Runnable subscribe(final Consumer<T> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    private void broadcast(final T v) {
        if (batchDepth > 0) {
            // 事务进行中：广播推迟到事务提交（规则 7：批量更新，避免事件风暴）
            DEFERRED.add(() -> broadcastNow(v));
            return;
        }
        broadcastNow(v);
    }

    private void broadcastNow(final T v) {
        if (notifying) {
            renotify = true;
            return;
        }
        notifying = true;
        try {
            do {
                renotify = false;
                for (int i = 0; i < listeners.size(); i++) {
                    listeners.get(i).accept(v);
                }
            } while (renotify);
        } finally {
            notifying = false;
        }
    }

    /** 事务：期间的所有写入合并到提交时一次性广播。 */
    public static void batch(final Runnable body) {
        batchDepth++;
        try {
            body.run();
        } finally {
            batchDepth--;
            if (batchDepth == 0) {
                while (!DEFERRED.isEmpty()) {
                    final List<Runnable> copy = new ArrayList<>(DEFERRED);
                    DEFERRED.clear();
                    for (final Runnable r : copy) {
                        r.run();
                    }
                }
            }
        }
    }

    // ============ 自检支持 ============

    public static synchronized List<Prop<?>> all() {
        return new ArrayList<>(ALL.values());
    }

    public static synchronized void reset() {
        ALL.clear();
        DEFERRED.clear();
        batchDepth = 0;
    }

    @Override
    public String toString() {
        return key + "=" + value;
    }

    /** 写入点（自检开启时才有值）。 */
    @Nullable
    private static String caller() {
        if (!UiLint.recording()) {
            return null;
        }
        try {
            return StackWalker.getInstance().walk(s -> s
                    .filter(f -> !f.getClassName().equals(Prop.class.getName()))
                    .findFirst()
                    .map(f -> {
                        final String cn = f.getClassName();
                        return cn.substring(cn.lastIndexOf('.') + 1) + "." + f.getMethodName();
                    })
                    .orElse(null));
        } catch (final Throwable t) {
            return null;
        }
    }
}
