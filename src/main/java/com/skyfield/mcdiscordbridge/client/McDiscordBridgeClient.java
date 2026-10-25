package com.skyfield.mcdiscordbridge.client;

import com.skyfield.mcdiscordbridge.client.emoji.ClientTextureCache;
import com.skyfield.mcdiscordbridge.server.McDiscordBridge;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;
import net.neoforged.neoforge.client.gui.ConfigurationScreen;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;
import net.neoforged.neoforge.event.AddPackFindersEvent;

@Mod(value = McDiscordBridge.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = McDiscordBridge.MODID, value = Dist.CLIENT)
public class McDiscordBridgeClient {
    public McDiscordBridgeClient(ModContainer container) {
        container.registerExtensionPoint(IConfigScreenFactory.class, ConfigurationScreen::new);
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        McDiscordBridge.LOGGER.info("HELLO FROM CLIENT SETUP");
        McDiscordBridge.LOGGER.info("MINECRAFT NAME >> {}", Minecraft.getInstance().getUser().getName());
    }

}
