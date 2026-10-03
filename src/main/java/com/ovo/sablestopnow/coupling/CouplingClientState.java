package com.ovo.sablestopnow.coupling;

import com.ovo.sablestopnow.network.CouplingNetworking;
import com.ovo.sablestopnow.server.CouplingData;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 耦合类配合（比例 / 齿轮）的客户端镜像。
 *
 * <p>与其它客户端状态同一模式：服务端权威、全量同步、按 id 现查
 * （同步会换新实例，界面里不能长期持有引用）。
 */
public final class CouplingClientState {

    private static final Map<ResourceLocation, List<CouplingData.RatioGroup>> RATIOS = new HashMap<>();
    private static final Map<ResourceLocation, List<CouplingData.GearCoupling>> GEARS = new HashMap<>();

    private CouplingClientState() {
    }

    public static void set(final ResourceLocation dimension, final List<CouplingData.RatioGroup> ratios,
                           final List<CouplingData.GearCoupling> gears) {
        RATIOS.put(dimension, new ArrayList<>(ratios));
        GEARS.put(dimension, new ArrayList<>(gears));
    }

    public static List<CouplingData.RatioGroup> ratios() {
        return RATIOS.getOrDefault(dimension(), List.of());
    }

    public static List<CouplingData.GearCoupling> gears() {
        return GEARS.getOrDefault(dimension(), List.of());
    }

    private static ResourceLocation dimension() {
        final var level = Minecraft.getInstance().level;
        return level == null ? ResourceLocation.parse("minecraft:overworld") : level.dimension().location();
    }

    /** 某个结构牵扯到的耦合（树状图挂载用）。 */
    public static List<Object> ofBody(final UUID body) {
        final List<Object> out = new ArrayList<>();
        for (final CouplingData.RatioGroup group : ratios()) {
            if (group.touches(body)) {
                out.add(group);
            }
        }
        for (final CouplingData.GearCoupling coupling : gears()) {
            if (coupling.touches(body)) {
                out.add(coupling);
            }
        }
        return out;
    }

    // ============ 发包 ============

    public static void requestCreateRatio(final List<com.ovo.sablestopnow.mate.MateRef> refs) {
        CouplingNetworking.send(CouplingNetworking.createRatio(new ArrayList<>(refs)));
    }

    public static void requestCreateGear(final List<com.ovo.sablestopnow.mate.MateRef> refs,
                                         final int teethA, final int teethB, final boolean reversed) {
        CouplingNetworking.send(CouplingNetworking.createGear(new ArrayList<>(refs), teethA, teethB, reversed));
    }

    public static void requestSetRatio(final UUID groupId, final int memberIndex, final double k) {
        CouplingNetworking.send(CouplingNetworking.setRatio(groupId, memberIndex, k));
    }

    public static void requestSetGear(final UUID groupId, final int teethA, final int teethB,
                                      final boolean reversed) {
        CouplingNetworking.send(CouplingNetworking.setGear(groupId, teethA, teethB, reversed));
    }

    public static void requestRemove(final UUID groupId, final boolean gear) {
        CouplingNetworking.send(CouplingNetworking.remove(groupId, gear));
    }

    public static void clear() {
        RATIOS.clear();
        GEARS.clear();
    }
}
