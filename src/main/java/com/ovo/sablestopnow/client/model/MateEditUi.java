package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.MateClientState;
import com.ovo.sablestopnow.client.MateTargeting;
import com.ovo.sablestopnow.client.gui.lib.Button;
import com.ovo.sablestopnow.client.gui.lib.Checkbox;
import com.ovo.sablestopnow.client.gui.lib.Cmd;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Dropdown;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Prop;
import com.ovo.sablestopnow.client.gui.lib.Separator;
import com.ovo.sablestopnow.client.gui.lib.TextField;
import com.ovo.sablestopnow.client.gui.lib.UiField;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import com.ovo.sablestopnow.mate.Mate;
import com.ovo.sablestopnow.mate.MateFrames;
import com.ovo.sablestopnow.mate.MateRef;
import com.ovo.sablestopnow.mate.MateType;
import com.ovo.sablestopnow.network.MateNetworking;
import dev.ryanhcode.sable.sublevel.SubLevel;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 配合的**创建页与编辑页**（同一个页面）。
 *
 * <h2>为什么两件事合成一件</h2>
 * 用户的要求原话是「对以后所有功能，创建页面就是编辑页面」。这条合并不是省代码，而是消掉一整类问题：
 * 分成两页时，创建页能设的东西编辑页改不了（或者反过来），玩家学了两套；而且「先建成默认值、
 * 想改再去左栏找」这件事本身就会让人以为功能没做完。
 *
 * <p>所以这里只有一组控件：类型下拉框 + 按类型长出来的数值/区间输入框 + 翻转 + 名字。
 * <ul>
 *   <li><b>编辑已有配合</b>：改动立刻发命令，服务端是权威；</li>
 *   <li><b>创建流程</b>：同样的控件改的是一份<b>草稿</b>，点「完成」时整套草稿随创建命令一起发出去。</li>
 * </ul>
 *
 * <h2>服务端拒绝时界面怎么办</h2>
 * 改类型/改数值都可能被服务端拒绝（过约束、重复、吸附太远……）。这里<b>不做乐观锁定</b>：
 * 改动写进 {@link Prop} 之后有一小段静默期，静默期一过就从服务端同步的值重新拉一遍 ——
 * 命令被接受时拉回来的是新值（界面不动），被拒绝时拉回来的是旧值（界面自动退回）。
 * 于是「冲突了就拒绝更改」这件事在界面上是<b>看得出来</b>的，而不是留下一个假的成功状态。
 */
public final class MateEditUi {

    /** 本地改动后的静默期：这段时间内不用服务端同步覆盖界面。 */
    private static final long LOCAL_HOLD_MS = 700L;

    private static final Prop<String> TYPE = Prop.of("mate.edit.type", MateType.COINCIDENT.id());
    private static final Prop<Double> VALUE = Prop.of("mate.edit.value", 0.0);
    private static final Prop<Double> VALUE2 = Prop.of("mate.edit.value2", 0.0);
    private static final Prop<Boolean> FLIP = Prop.of("mate.edit.flip", false);
    private static final Prop<String> NAME = Prop.of("mate.edit.name", "");

    private static long lastLocalWriteMs;

    private static final String CMD_TYPE = "mate.edit.type";
    private static final String CMD_VALUE = "mate.edit.value";
    private static final String CMD_VALUE2 = "mate.edit.value2";
    private static final String CMD_FLIP = "mate.edit.flip";
    private static final String CMD_NAME = "mate.edit.name";
    private static final String CMD_REMOVE = "mate.edit.remove";

    private MateEditUi() {
    }

    // ============ 草稿 / 同步 ============

    /** 创建流程的草稿类型 id（进左栏指纹，换类型要换输入框）。 */
    public static String draftTypeId() {
        return TYPE.get();
    }

    /** 开始一次创建：把草稿复位到这个类型的默认值。 */
    public static void beginCreate(final MateType initial) {
        final MateType type = initial == null ? MateType.COINCIDENT : initial;
        TYPE.set(type.id());
        VALUE.set(type.defaultValue());
        VALUE2.set(type.defaultValue2());
        FLIP.set(false);
        NAME.set("");
        flipTouched = false;
        markLocalWrite();
    }

    /** 玩家手动拨过翻转没有。没拨过时，翻转跟着「自动对齐建议」走。 */
    private static boolean flipTouched;

    /**
     * 刷新「自动对齐建议」。
     *
     * <p>面法线是朝材料外面的：把 A 的顶面配到 B 的底面时，同向对齐会把 B 翻 180° 倒扣上去。
     * 服务端建配合时本来会调 {@link MateFrames#suggestFlip} 挑「转动量最小」的那一个 ——
     * 但创建页现在把玩家看到的 {@code flip} 直接发过去，所以<b>建议值必须先在界面上体现出来</b>，
     * 否则复选框显示「没勾」而实际效果是翻转的（用户实测反馈的第 2 条）。
     *
     * <p>玩家一旦自己拨过复选框，就不再覆盖他的选择；换类型时重新给建议
     * （建议本身依赖类型）。
     */
    public static void refreshSuggestion() {
        if (flipTouched) {
            return;
        }
        final MateType type = MateType.byId(TYPE.get());
        if (type == null || !type.supportsAlignment()) {
            FLIP.set(false);
            return;
        }
        final List<MateRef> picked = ModelMode.picked();
        if (picked.size() < 2) {
            return;
        }
        final SubLevel a = MateTargeting.byId(picked.get(0).body());
        final SubLevel b = MateTargeting.byId(picked.get(1).body());
        if (a == null || b == null) {
            return;
        }
        FLIP.set(MateFrames.suggestFlip(a, b, type, picked.get(0), picked.get(1)));
    }

    /** 每帧（周期）把服务端的权威值拉进编辑状态。 */
    public static void tickSync() {
        pullFromServer(false);
    }

    private static void pullFromServer(final boolean force) {
        final Mate mate = MateClientState.highlightedMate();
        if (mate == null) {
            return;
        }
        if (!force && System.currentTimeMillis() - lastLocalWriteMs < LOCAL_HOLD_MS) {
            return;
        }
        // 相等时不广播，所以这一串拉平不会引发重绘风暴
        TYPE.set(mate.type().id());
        VALUE.set(mate.value());
        VALUE2.set(mate.value2());
        FLIP.set(mate.flip());
        NAME.set(mate.name() == null ? "" : mate.name());
    }

    private static void markLocalWrite() {
        lastLocalWriteMs = System.currentTimeMillis();
    }

    /**
     * 「完成」：把整套草稿随创建命令发出去。
     *
     * <p>类型、数值/区间、翻转、名字一次带齐 —— 这正是「创建页就是编辑页」的意义：
     * 玩家在确认之前填的东西不会白填。
     *
     * @return 是否可以发送（类型无效时 false）
     */
    public static boolean sendCreate() {
        final MateType type = MateType.byId(TYPE.get());
        if (type == null) {
            return false;
        }
        markLocalWrite();
        VeilPacketManager.server().sendPacket(MateNetworking.MateCommandPayload.create(
                type.id(), VALUE.get(), VALUE2.get(), FLIP.get(), NAME.get()));
        return true;
    }

    /** 编辑已有配合时的当前类型；草稿流程下就是草稿类型。 */
    @Nullable
    public static MateType currentType() {
        return MateType.byId(TYPE.get());
    }

    // ============ 页面本体 ============

    /** 把创建页/编辑页搭进左栏内容容器。{@code mate} 为 null 表示这是创建流程（编辑草稿）。 */
    public static void buildEditor(final Container body, @Nullable final Mate mate) {
        Cmd.declare(CMD_TYPE, CMD_VALUE, CMD_VALUE2, CMD_FLIP, CMD_NAME, CMD_REMOVE);
        final boolean editing = mate != null;
        if (editing) {
            // 打开编辑页时先把服务端的当前值拉进来（否则会显示上一个配合的残留）
            pullFromServer(true);
        } else {
            // 创建页：把「自动对齐建议」先在界面上体现出来，做到所见即所得
            refreshSuggestion();
        }

        body.add(new Label(Component.translatable(editing ? "mate.edit.title" : "mate.create.title"))
                .shadow(true));
        body.add(new Label(editing
                ? Component.literal(shortId(mate.id()))
                : Component.translatable("mate.create.hint")).color(0xFF8FA0D8));
        body.add(new Separator());

        // ---- 类型下拉框：创建页与编辑页是同一个 ----
        body.add(new Label(Component.translatable("mate.edit.type")));
        final List<MateType> types = applicableTypes(mate);
        final Dropdown<String> typeBox = new Dropdown<>(Widget.FILL, TYPE);
        typeBox.id("mate.type");
        for (final MateType type : types) {
            typeBox.option(type.id(), "mate.type." + type.id());
        }
        typeBox.onChange(id -> {
            markLocalWrite();
            final MateType picked = MateType.byId(id);
            if (picked != null && !editing) {
                // 草稿：换类型顺带把数值复位成新类型的默认值，否则会拿上一个类型的量纲去建
                VALUE.set(picked.defaultValue());
                VALUE2.set(picked.defaultValue2());
                // 建议值依赖类型，换类型时重新给（玩家拨过就尊重他的选择）
                flipTouched = false;
            }
            if (editing) {
                Cmd.run(CMD_TYPE, () -> VeilPacketManager.server().sendPacket(
                        MateNetworking.MateCommandPayload.changeType(mate.id(), id)));
            }
            ModelUi.refreshLeftPanel();
        });
        body.add(typeBox);

        // ---- 按类型长出来的数值输入框 ----
        final MateType current = MateType.byId(TYPE.get());
        if (current == null) {
            return;
        }
        if (current.needsValue()) {
            final boolean ranged = current.needsSecondValue();
            final double step = current.valueKind() == MateType.ValueKind.ANGLE ? 1.0 : 0.05;
            final UiField main = UiField.number(ranged ? "mate.edit.range_min" : "mate.edit.value",
                    CMD_VALUE, VALUE, current.minValue(), current.maxValue(), step);
            main.format(v -> current.valueKind() == MateType.ValueKind.ANGLE
                    ? String.format("%.1f°", v) : String.format("%.2f", v));
            main.preview(v -> markLocalWrite());
            main.commit(v -> {
                markLocalWrite();
                if (editing) {
                    Cmd.run(CMD_VALUE, () -> VeilPacketManager.server().sendPacket(
                            ranged ? MateNetworking.MateCommandPayload.setRange(mate.id(), v, VALUE2.get())
                                    : MateNetworking.MateCommandPayload.setValue(mate.id(), v)));
                }
            });
            body.add(main);

            if (ranged) {
                final UiField second = UiField.number("mate.edit.range_max", CMD_VALUE2, VALUE2,
                        current.minValue(), current.maxValue(), step);
                second.format(v -> String.format("%.2f", v));
                second.preview(v -> markLocalWrite());
                second.commit(v -> {
                    markLocalWrite();
                    if (editing) {
                        Cmd.run(CMD_VALUE2, () -> VeilPacketManager.server().sendPacket(
                                MateNetworking.MateCommandPayload.setRange(mate.id(), VALUE.get(), v)));
                    }
                });
                body.add(second);
            }
        }

        // ---- 翻转（只有支持对齐的类型才有）----
        if (current.supportsAlignment()) {
            final Checkbox flipBox = new Checkbox(FLIP,
                    () -> Component.translatable("gui.sablestopnow.mate.reverse"));
            flipBox.id("mate.flip");
            flipBox.onChange = v -> {
                markLocalWrite();
                flipTouched = true;
                if (editing) {
                    Cmd.run(CMD_FLIP, () -> VeilPacketManager.server().sendPacket(
                            MateNetworking.MateCommandPayload.setFlip(mate.id(), v)));
                }
            };
            body.add(flipBox);
        }

        // ---- 名字 ----
        body.add(new Label(Component.translatable("mate.edit.name")).color(0xFF8FA0D8));
        final TextField nameField = new TextField().bind(NAME);
        nameField.id("mate.name");
        nameField.placeholder("mate.edit.name.hint");
        nameField.commit(text -> {
            markLocalWrite();
            if (editing) {
                Cmd.run(CMD_NAME, () -> VeilPacketManager.server().sendPacket(
                        MateNetworking.MateCommandPayload.rename(mate.id(), text)));
            }
        });
        body.add(nameField);

        // ---- 删除（只有已有配合才有）----
        if (editing) {
            body.add(new Separator());
            final Button remove = new Button(Component.translatable("model.tool.delete_mate"),
                    () -> ModelUi.deleteSelectedMate()).command(CMD_REMOVE);
            remove.prefW = Widget.FILL;
            body.add(remove);
        }
    }

    /**
     * 这个配合可以换成哪些类型。
     *
     * <p>判据与创建时一致（两端都支持），但<b>永远包含当前类型</b> ——
     * 否则下拉框会显示一个列表里没有的值，看着像坏了。
     */
    private static List<MateType> applicableTypes(@Nullable final Mate mate) {
        final List<MateType> out = new ArrayList<>();
        if (mate != null && !out.contains(mate.type())) {
            out.add(mate.type());
        }
        final List<MateRef> picked = ModelMode.picked();
        final MateRef a = mate != null ? mate.a() : (picked.size() > 0 ? picked.get(0) : null);
        final MateRef b = mate != null ? mate.b() : (picked.size() > 1 ? picked.get(1) : null);
        for (final MateType type : MateType.values()) {
            if (out.contains(type)) {
                continue;
            }
            if (a == null || b == null) {
                out.add(type);
                continue;
            }
            if (a.supports(type) && b.supports(type)) {
                out.add(type);
            }
        }
        return out;
    }

    private static String shortId(final UUID id) {
        return id.toString().substring(0, 8);
    }
}
