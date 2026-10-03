package com.ovo.sablestopnow.client.model;

import com.ovo.sablestopnow.client.gui.lib.Button;
import com.ovo.sablestopnow.client.gui.lib.Checkbox;
import com.ovo.sablestopnow.client.gui.lib.Container;
import com.ovo.sablestopnow.client.gui.lib.Label;
import com.ovo.sablestopnow.client.gui.lib.Separator;
import com.ovo.sablestopnow.client.gui.lib.TextField;
import com.ovo.sablestopnow.client.gui.lib.Widget;
import com.ovo.sablestopnow.coupling.CouplingClientState;
import com.ovo.sablestopnow.mate.RatioRule;
import com.ovo.sablestopnow.server.CouplingData;
import net.minecraft.network.chat.Component;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * 耦合类配合（比例 / 齿轮）的左栏编辑器。
 *
 * <h2>比例</h2>
 * 列出每个从动面一行：一个数字框（该面的 k）+ 它对应的参考描述。
 * k 的含义是「该面到基准面的距离 / 第一个从动面到基准面的距离」，创建时按初始状态自动算出，
 * 这里改的就是它 —— 改完下一子步就生效（服务端是权威，界面只反映同步回来的值）。
 *
 * <h2>齿轮</h2>
 * 两格齿数 + 反转复选框；实时显示算出来的传动比与它意味着什么
 * （比值 0.5 = A 转 2 圈 B 反向转 1 圈）。
 */
public final class ModelCouplingUi {

    @Nullable
    private static UUID selectedRatio;
    @Nullable
    private static UUID selectedGear;

    private ModelCouplingUi() {
    }

    public static void selectRatio(final UUID id) {
        selectedRatio = id;
        selectedGear = null;
        ModelUi.refreshLeftPanel();
    }

    public static void selectGear(final UUID id) {
        selectedGear = id;
        selectedRatio = null;
        ModelUi.refreshLeftPanel();
    }

    public static void clearSelection() {
        selectedRatio = null;
        selectedGear = null;
    }

    public static boolean hasSelection() {
        return selectedRatio != null || selectedGear != null;
    }

    /** 树里选中项的 id（决定删除键删谁）。 */
    @Nullable
    public static UUID selectedId() {
        return selectedRatio != null ? selectedRatio : selectedGear;
    }

    public static boolean selectedIsGear() {
        return selectedGear != null;
    }

    @Nullable
    private static CouplingData.RatioGroup ratio() {
        if (selectedRatio == null) {
            return null;
        }
        for (final CouplingData.RatioGroup group : CouplingClientState.ratios()) {
            if (group.id().equals(selectedRatio)) {
                return group;
            }
        }
        return null;
    }

    @Nullable
    private static CouplingData.GearCoupling gear() {
        if (selectedGear == null) {
            return null;
        }
        for (final CouplingData.GearCoupling coupling : CouplingClientState.gears()) {
            if (coupling.id().equals(selectedGear)) {
                return coupling;
            }
        }
        return null;
    }

    /** 删除当前选中的耦合（树里 / Delete 键都走这里）。 */
    public static void deleteSelected() {
        if (selectedRatio != null) {
            CouplingClientState.requestRemove(selectedRatio, false);
        } else if (selectedGear != null) {
            CouplingClientState.requestRemove(selectedGear, true);
        }
        clearSelection();
        ModelUi.refreshLeftPanel();
    }

    // ============ 左栏 ============

    public static void buildEditor(final Container body) {
        final CouplingData.RatioGroup group = ratio();
        if (group != null) {
            buildRatio(body, group);
            return;
        }
        final CouplingData.GearCoupling coupling = gear();
        if (coupling != null) {
            buildGear(body, coupling);
            return;
        }
        body.add(new Label(Component.translatable("model.coupling.none")));
    }

    private static void buildRatio(final Container body, final CouplingData.RatioGroup group) {
        body.add(new Label(Component.translatable("model.coupling.ratio")).shadow(true));
        body.add(new Label(Component.translatable("model.coupling.base",
                group.driver().describe())).color(0xFF8FA0D8));
        body.add(new Label(Component.translatable("model.coupling.k_hint")).color(0xFF8FA0D8));
        body.add(new Separator());

        for (int i = 0; i < group.members().size(); i++) {
            final CouplingData.RatioMember member = group.members().get(i);
            final int index = i;
            body.add(new Label(() -> Component.literal("#" + (index + 1) + "  "
                    + ModelUi.shortId(member.ref().body()))).color(0xFFCFD8FF));
            final TextField k = new TextField(format(member.ratio()));
            k.numeric();
            k.commit(text -> {
                try {
                    final double value = Double.parseDouble(text.trim());
                    if (RatioRule.isValidRatio(value)) {
                        CouplingClientState.requestSetRatio(group.id(), index, value);
                    } else {
                        ModelMode.prompt("model.coupling.bad_k");
                    }
                } catch (final NumberFormatException ignored) {
                    ModelMode.prompt("model.coupling.bad_k");
                }
            });
            body.add(k);
        }

        body.add(new Separator());
        body.add(deleteButton());
    }

    private static void buildGear(final Container body, final CouplingData.GearCoupling coupling) {
        body.add(new Label(Component.translatable("model.coupling.gear")).shadow(true));
        body.add(new Label(Component.translatable("model.coupling.gear_axes")).color(0xFF8FA0D8));
        body.add(new Separator());

        body.add(new Label(Component.translatable("model.coupling.teeth_a")));
        final TextField teethA = new TextField(String.valueOf(coupling.teethA()));
        teethA.integer();
        teethA.commit(text -> applyGear(coupling, text, null, null));
        body.add(teethA);

        body.add(new Label(Component.translatable("model.coupling.teeth_b")));
        final TextField teethB = new TextField(String.valueOf(coupling.teethB()));
        teethB.integer();
        teethB.commit(text -> applyGear(coupling, null, text, null));
        body.add(teethB);

        final Checkbox reversed = new Checkbox(() -> {
            final CouplingData.GearCoupling live = gear();
            return live != null && live.reversed();
        }, value -> applyGear(coupling, null, null, value),
                () -> Component.translatable("gui.sablestopnow.mate.reverse"));
        body.add(reversed);

        final Double ratio = coupling.ratio();
        body.add(new Label(() -> {
            final CouplingData.GearCoupling live = gear();
            final Double liveRatio = live == null ? null : live.ratio();
            return liveRatio == null
                    ? Component.translatable("model.coupling.bad_teeth")
                    : Component.translatable("model.coupling.ratio_value",
                    String.format("%.4f", liveRatio), String.format("%.2f", 1.0 / Math.abs(liveRatio)));
        }).color(ratio == null ? 0xFFE0A050 : 0xFFB9C0EE));

        body.add(new Separator());
        body.add(deleteButton());
    }

    /** 齿数/反转的提交：三个字段各自回车时只带自己那一个新值，其余沿用当前值。 */
    private static void applyGear(final CouplingData.GearCoupling coupling, @Nullable final String teethA,
                                  @Nullable final String teethB, @Nullable final Boolean reversed) {
        try {
            final int a = teethA == null ? coupling.teethA() : Integer.parseInt(teethA.trim());
            final int b = teethB == null ? coupling.teethB() : Integer.parseInt(teethB.trim());
            final boolean flip = reversed == null ? coupling.reversed() : reversed;
            if (!com.ovo.sablestopnow.mate.GearRule.validTeeth(a)
                    || !com.ovo.sablestopnow.mate.GearRule.validTeeth(b)) {
                ModelMode.prompt("model.coupling.bad_teeth");
                return;
            }
            CouplingClientState.requestSetGear(coupling.id(), a, b, flip);
        } catch (final NumberFormatException ignored) {
            ModelMode.prompt("model.coupling.bad_teeth");
        }
    }

    private static Button deleteButton() {
        final Button remove = new Button(Component.translatable("model.coupling.remove"),
                ModelCouplingUi::deleteSelected);
        remove.prefW = Widget.FILL;
        return remove;
    }

    private static String format(final double value) {
        if (Math.abs(value - Math.rint(value)) < 1.0e-9) {
            return String.valueOf((long) Math.rint(value));
        }
        return String.format("%.4f", value);
    }
}
