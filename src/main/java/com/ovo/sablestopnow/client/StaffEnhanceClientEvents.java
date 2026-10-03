package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.SablestopNow;
import com.ovo.sablestopnow.client.gui.lib.UiLint;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.loading.FMLPaths;
import net.neoforged.neoforge.client.event.ClientTickEvent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 客户端事件接线：每 tick 驱动 {@link StaffEnhanceClientHandler} 状态清理，
 * 以及 GUI 自检的启动自测与手动导出。
 */
@EventBusSubscriber(modid = SablestopNow.MOD_ID, value = Dist.CLIENT)
public final class StaffEnhanceClientEvents {

    /** 启动自检只跑一次（检查器本身是不是坏的）。 */
    private static boolean selfTested;

    private StaffEnhanceClientEvents() {
    }

    @SubscribeEvent
    public static void onClientTick(final ClientTickEvent.Post event) {
        StaffEnhanceClientHandler.tick();
        if (!selfTested) {
            selfTested = true;
            runSelfTest();
        }
        while (StaffKeyMappings.UI_LINT.consumeClick()) {
            dumpLint();
        }
    }

    /**
     * 资源重载（F3+T、换材质包）时丢掉贴图缓存。
     *
     * <p>⚠ 不清的话缓存里的 {@code TextureAtlasSprite} 会指向**已经不存在的图集位置** ——
     * 表现是"换材质包之后模型的贴图全变成乱七八糟的花纹"，
     * 而且只有重启游戏才好，很难联想到是缓存的问题。
     */
    @SubscribeEvent
    public static void onResourceReload(
            final net.neoforged.neoforge.client.event.RegisterClientReloadListenersEvent event) {
        event.registerReloadListener(new net.minecraft.server.packs.resources.ResourceManagerReloadListener() {
            @Override
            public void onResourceManagerReload(
                    final net.minecraft.server.packs.resources.ResourceManager manager) {
                SolidTextureCache.invalidate();
            }
        });
    }

    /**
     * 用合成用例验证 {@link UiLint} 的每一项检查确实会触发。
     *
     * <p>一个「永远报无问题」的检查器和「真的没问题」长得一模一样，所以这里故意造出已知缺陷，
     * 要求检查器全部报出来；漏报就直接在启动日志里喊出来 —— 别等到实机测试才发现检查器坏了。
     */
    private static void runSelfTest() {
        try {
            final UiLint.SelfTest result = UiLint.selfTest();
            if (result.pass()) {
                SablestopNow.LOGGER.info("[ui-lint] 自检通过（{} 项检查）", result.checks());
            } else {
                SablestopNow.LOGGER.error("[ui-lint] 自检失败：{}", result.failures());
            }
        } catch (final Throwable t) {
            SablestopNow.LOGGER.error("[ui-lint] 自检本身抛异常", t);
        }
    }

    /** 导出当前所有界面的体检报告（F9）：写日志 + {@code run/ui-lint.txt}。 */
    private static void dumpLint() {
        final String report = UiLint.report();
        SablestopNow.LOGGER.info("[ui-lint] 报告已导出（{} 行）", report.lines().count());
        for (final String line : report.split("\n")) {
            SablestopNow.LOGGER.info("[ui-lint] {}", line);
        }
        try {
            final Path out = FMLPaths.GAMEDIR.get().resolve("ui-lint.txt");
            Files.writeString(out, report, StandardCharsets.UTF_8);
            SablestopNow.LOGGER.info("[ui-lint] 已写入 {}", out.toAbsolutePath());
        } catch (final Throwable t) {
            SablestopNow.LOGGER.warn("[ui-lint] 写文件失败", t);
        }
    }
}
