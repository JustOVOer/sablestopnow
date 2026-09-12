package com.ovo.sablestopnow.client;

import com.ovo.sablestopnow.client.gui.ModConfigScreen;
import net.neoforged.fml.ModContainer;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * 客户端专属：把 NeoForge 模组列表里的「配置」按钮指向本模组的自定义设置界面。
 *
 * <p>放在 client 专属类里、只在 {@code FMLEnvironment.dist.isClient()} 分支调用，
 * 避免服务端加载到 {@link IConfigScreenFactory} 这类客户端类。
 */
public final class ClientConfigScreens {

    private ClientConfigScreens() {
    }

    public static void register(final ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class,
                (IConfigScreenFactory) (mod, parent) -> new ModConfigScreen(parent));
    }
}
