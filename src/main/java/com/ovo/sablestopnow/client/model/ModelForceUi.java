package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.gui.lib.Button;
import com.ovo.sablestopnow.client.gui.lib.Checkbox;
import com.ovo.sablestopnow.client.gui.lib.Cmd;
import com.ovo.sablestopnow.client.gui.lib.ComboBox;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Layouts;
import com.ovo.sablestopnow.client.gui.lib.Prop;
import com.ovo.sablestopnow.client.gui.lib.Separator;
import com.ovo.sablestopnow.client.gui.lib.TextField;
import com.ovo.sablestopnow.client.gui.lib.UiField;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import com.ovo.sablestopnow.force.ForceClientState;
import com.ovo.sablestopnow.force.ForcePoint;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 可控施力点的界面部分：左栏编辑器 + 树状图行的显示。
 *
 * <h2>状态放在哪儿（这一版的重点）</h2>
 * 以前编辑器是在 {@link #buildEditor} 里现搭的：滑条拿一份 {@code point.magnitude()} 的快照、
 * 输入框拿另一份，两边各自发包。结果是用户实测的<b>「滑动条和输入框没有联动」</b>——
 * 不是漏写了一次同步，而是同一个量在界面上有<b>三份</b>副本（滑条 / 输入框 / 服务端），
 * 没有任何东西保证它们相等。
 *
 * <p>现在只有一个真相：{@link #magnitude} 等一组 {@link Prop}。控件只是它的投影，
 * 且都通过 {@link UiField} 绑同一个 Prop —— 滑条拖了输入框立刻跟着变、输入框打了字滑条立刻跟着滑，
 * 因为两边读的是同一个值。服务端的回声也写进同一个 Prop（值相等时不广播，所以不会打架）。
 *
 * <h2>与服务器的关系</h2>
 * 界面<b>乐观更新</b>（拖动/打字立刻改本地状态，界面跟手），<b>提交</b>时才发命令
 * （{@link ForceClientState#requestUpdate}）。服务端回声是权威值：如果它把值钳过，
 * 下一次同步会把界面拉回真实值。为了不让回声打断玩家正在进行的拖动，
 * 本地写入后有一小段「静默期」不拉取（见 {@link #LOCAL_HOLD_MS}）。
 */
public final class ModelForceUi {

    /** 本地写入后的静默期：这段时间内不用服务端回声覆盖界面（避免拖动被打断）。 */
    private static final long LOCAL_HOLD_MS = 700L;

    /** 当前在左栏编辑的施力点 id。 */
    @Nullable
    private static UUID selected;

    /** 编辑器状态（活在控件树之外，重建左栏不会丢）。 */
    private static final Prop<Double> magnitude = Prop.of("force.magnitude", 0.0);
    private static final Prop<String> channel1 = Prop.of("force.channel1", "");
    private static final Prop<String> channel2 = Prop.of("force.channel2", "");
    private static final Prop<String> channelName = Prop.of("force.channelName", "");
    private static final Prop<Boolean> enabled = Prop.of("force.enabled", true);

    private static long lastLocalWriteMs;

    /** 物品注册名的候选缓存（补全列表）。第一次用到时才建，之后一直复用。 */
    @Nullable
    private static List<String> itemNames;

    private ModelForceUi() {
    }

    @Nullable
    public static UUID selectedId() {
        return selected;
    }

    public static void select(@Nullable final UUID id) {
        selected = id;
        pullFromServer(true);
        ModelUi.refreshLeftPanel();
    }

    /** 树状图里选中的那一份（按 id 现查，服务端每次同步都会换新实例）。 */
    @Nullable
    public static ForcePoint selectedPoint() {
        return selected == null ? null : ForceClientState.byId(selected);
    }

    /** 放置成功后由 {@link ModelInput} 调用：等下一次同步把新点捞出来自动选中。 */
    public static void onPlaced() {
        awaitingPlacement = true;
    }

    private static boolean awaitingPlacement;
    private static final java.util.Set<UUID> KNOWN = new java.util.HashSet<>();

    /**
     * 服务端同步是异步的：放置后要等它广播回来才知道新点的 id。
     * 这里用「已知点集合」做差集，把新出现的那个自动选中 —— 玩家点一下就能直接改参数，
     * 不用再去树上找那行。周期由 {@link ModelUi#tickRefresh()} 驱动。
     */
    public static void tickAutoSelect() {
        final UUID model = ModelMode.selectedModel();
        if (model == null) {
            KNOWN.clear();
            return;
        }
        final List<ForcePoint> points = ForceClientState.ofBody(model);
        if (awaitingPlacement) {
            for (final ForcePoint point : points) {
                if (!KNOWN.contains(point.id())) {
                    awaitingPlacement = false;
                    select(point.id());
                    break;
                }
            }
        }
        KNOWN.clear();
        for (final ForcePoint point : points) {
            KNOWN.add(point.id());
        }
    }

    // ============ 同步 ============

    /** 每帧（周期）把服务端的权威值拉进编辑器状态。 */
    public static void tickSync() {
        pullFromServer(false);
    }

    private static void pullFromServer(final boolean force) {
        final ForcePoint point = selectedPoint();
        if (point == null) {
            return;
        }
        if (!force && System.currentTimeMillis() - lastLocalWriteMs < LOCAL_HOLD_MS) {
            return;
        }
        // 相等时不广播，所以这一串拉平不会引发任何重绘风暴
        magnitude.set(point.magnitude());
        channel1.set(nullToEmpty(point.channelItemId()));
        channel2.set(nullToEmpty(point.channelItemId2()));
        channelName.set(nullToEmpty(point.channelName()));
        enabled.set(point.enabled());
    }

    private static String nullToEmpty(@Nullable final String s) {
        return s == null ? "" : s;
    }

    /** 本地写入记账：静默期内不接受服务端回声。 */
    private static void markLocalWrite() {
        lastLocalWriteMs = System.currentTimeMillis();
    }

    // ============ 树状图行 ============

    /**
     * 树里的一行施力点摘要。
     *
     * <p>⚠ 必须用 supplier 读实时数据：树的行控件按 key 复用，行只会搭一次，
     * 直接捕获 {@code ForcePoint} 实例的话力量改了、开关了，树里显示的还是建行时的旧值。
     */
    public static void buildRow(final Container row,
                                final java.util.function.Supplier<ForcePoint> pointSupplier) {
        final Label label = new Label(() -> {
            final ForcePoint point = pointSupplier.get();
            if (point == null) {
                return Component.empty();
            }
            return Component.literal(shortId(point.id()) + "  "
                    + String.format("%.0f", point.magnitude()) + "N"
                    + (point.enabled() ? "" : "  (off)"));
        });
        label.prefW = Widget.FILL;
        row.add(label);
    }

    // ============ 左栏编辑器 ============

    private static final String CMD_MAGNITUDE = "force.magnitude";
    private static final String CMD_CHANNEL1 = "force.channel1";
    private static final String CMD_CHANNEL2 = "force.channel2";
    private static final String CMD_CHANNEL_NAME = "force.channel.name";
    private static final String CMD_ENABLED = "force.enabled";
    private static final String CMD_REMOVE = "force.remove";

    /** 把编辑器搭进左栏内容容器。控件只读 {@link Prop}、只发命令，自己不持值。 */
    public static void buildEditor(final Container body) {
        Cmd.declare(CMD_MAGNITUDE, CMD_CHANNEL1, CMD_CHANNEL2, CMD_CHANNEL_NAME, CMD_ENABLED, CMD_REMOVE);

        final ForcePoint point = selectedPoint();
        if (point == null) {
            body.add(new Label(Component.translatable("model.force.none")));
            body.add(new Label(Component.translatable("model.force.none.hint")).color(0xFF8FA0D8));
            return;
        }
        pullFromServer(true);
        final UUID id = point.id();

        body.add(new Label(() -> Component.literal("施力点 " + shortId(point.id()))).shadow(true));
        body.add(new Label(() -> Component.translatable("model.force.at",
                String.format("%.1f, %.1f, %.1f", point.localPoint().x, point.localPoint().y,
                        point.localPoint().z))).color(0xFF8FA0D8));
        body.add(new Separator());

        // 力的大小：滑条与输入框绑的是**同一个** Prop，所以两边不可能不同步
        final UiField amount = UiField.number("model.force.magnitude", CMD_MAGNITUDE, magnitude,
                ForcePoint.MIN_MAGNITUDE, ForcePoint.MAX_MAGNITUDE, 1.0);
        // 输入框里只放数字（单位在标签里）：编辑时不需要先删掉「N」后缀
        amount.format(v -> String.format("%.0f", v));
        amount.commit(v -> {
            markLocalWrite();
            ForceClientState.requestUpdate(id, v, null, null, null, null);
        });
        // 拖动过程中也进入静默期：否则每 600ms 一次的服务端拉取会把手底下的滑条拽回去
        amount.preview(v -> markLocalWrite());
        body.add(amount);

        body.add(new Separator());
        body.add(new Label(Component.translatable("model.force.channel")));
        body.add(new Label(Component.translatable("model.force.channel.hint")).color(0xFF8FA0D8));

        // Create 的红石链接频率是**两个槽**（两个物品一起构成频率对），所以这里给两个下拉：
        // 玩家那边红石链接方块两个格子放什么，这里就选什么。第二槽留空时服务端按
        // 「同一个物品放两个槽」处理（与旧存档一致）。
        final ComboBox channel = new ComboBox(Widget.FILL, channel1);
        channel.suggestionSource(ModelForceUi::suggestions);
        channel.command(CMD_CHANNEL1);
        channel.onCommit(text -> {
            markLocalWrite();
            ForceClientState.requestUpdate(id, null, text.trim(), null, null, null);
        });
        body.add(channel);

        body.add(new Label(Component.translatable("model.force.channel.slot2")).color(0xFF8FA0D8));
        final ComboBox channelSlot2 = new ComboBox(Widget.FILL, channel2);
        channelSlot2.suggestionSource(ModelForceUi::suggestions);
        channelSlot2.command(CMD_CHANNEL2);
        channelSlot2.onCommit(text -> {
            markLocalWrite();
            ForceClientState.requestUpdate(id, null, null, text.trim(), null, null);
        });
        body.add(channelSlot2);

        body.add(new Label(Component.translatable("model.force.channel_name")).color(0xFF8FA0D8));
        final TextField name = new TextField().bind(channelName);
        name.placeholder("model.force.channel_name");
        name.command(CMD_CHANNEL_NAME);
        name.commit(text -> {
            markLocalWrite();
            ForceClientState.requestUpdate(id, null, null, null, text.trim(), null);
        });
        body.add(name);

        body.add(new Separator());
        final Checkbox toggle = new Checkbox(enabled, () -> Component.translatable("model.force.enabled"));
        toggle.command(CMD_ENABLED);
        // ⚠ 用 onChange 而不是 enabled.subscribe(...)：订阅写在建面板里的话，
        // 左栏每重建一次就多挂一条回调，勾一下会发出 N 个包（这类「写了多次调用」正是自检要拦的）。
        toggle.onChange = v -> {
            markLocalWrite();
            ForceClientState.requestUpdate(id, null, null, null, null, v);
        };
        body.add(toggle);

        final Button remove = new Button(Component.translatable("model.force.remove"), () -> {
            ForceClientState.requestRemove(id);
            select(null);
        }).command(CMD_REMOVE);
        remove.prefW = Widget.FILL;
        body.add(remove);
    }

    /** 物品注册名候选：先给常用前缀（minecraft / create），其余按字母序排在后面。 */
    private static List<String> suggestions() {
        if (itemNames == null) {
            final List<String> preferred = new ArrayList<>();
            final List<String> rest = new ArrayList<>();
            for (final ResourceLocation id : BuiltInRegistries.ITEM.keySet()) {
                final String name = id.toString();
                if (id.getNamespace().equals("minecraft") || id.getNamespace().equals("create")) {
                    preferred.add(name);
                } else {
                    rest.add(name);
                }
            }
            preferred.sort(String::compareTo);
            rest.sort(String::compareTo);
            preferred.addAll(rest);
            itemNames = preferred;
        }
        return itemNames;
    }

    private static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }

    /** 让左栏容器用竖直排布（与 ModelUi 里其它区块一致）。 */
    static Container column() {
        final Container column = new Container();
        column.layout = Layouts.vbox(0, 2);
        column.prefH = Widget.AUTO;
        return column;
    }
}
