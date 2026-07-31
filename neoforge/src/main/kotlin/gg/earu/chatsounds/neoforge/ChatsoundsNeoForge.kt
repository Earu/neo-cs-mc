package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.DataLoader
import net.minecraftforge.common.MinecraftForge
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import net.minecraftforge.fml.loading.FMLEnvironment
import net.minecraftforge.fml.loading.FMLPaths
import thedarkcolour.kotlinforforge.forge.MOD_BUS

@Mod(Chatsounds.MOD_ID)
class ChatsoundsNeoForge {
    init {
        Chatsounds.init(
            NeoForgePlatform(
                configDir = FMLPaths.CONFIGDIR.get().resolve("chatsounds"),
                isClient = FMLEnvironment.dist.isClient,
                modVersion = ModList.get().getModContainerById(Chatsounds.MOD_ID)
                    .map { it.modInfo.version.toString() }.orElse("dev"),
            )
        )

        Payloads.register()
        ServerEvents.wire()
        MinecraftForge.EVENT_BUS.register(ServerEvents)
        if (FMLEnvironment.dist.isClient) {
            MinecraftForge.EVENT_BUS.register(ClientEvents)
        }

        MOD_BUS.addListener { _: FMLClientSetupEvent ->
            gg.earu.chatsounds.ClientConfig.load()
            gg.earu.chatsounds.data.Blacklist.load()
            // List compilation is fully async; playback and completion gate on DataLoader state.
            DataLoader.startup()
        }
    }
}
