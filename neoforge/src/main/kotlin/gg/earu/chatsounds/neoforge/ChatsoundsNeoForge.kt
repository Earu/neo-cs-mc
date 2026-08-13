package gg.earu.chatsounds.neoforge

import gg.earu.chatsounds.Chatsounds
import gg.earu.chatsounds.data.DataLoader
import net.neoforged.bus.api.IEventBus
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.fml.ModContainer
import net.neoforged.fml.common.Mod
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent
import net.neoforged.fml.loading.FMLEnvironment
import net.neoforged.fml.loading.FMLPaths
import net.neoforged.neoforge.common.NeoForge
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent

@Mod(Chatsounds.MOD_ID)
class ChatsoundsNeoForge(container: ModContainer, modBus: IEventBus) {
    init {
        Chatsounds.init(
            NeoForgePlatform(
                configDir = FMLPaths.CONFIGDIR.get().resolve("chatsounds"),
                isClient = FMLEnvironment.getDist().isClient,
                modVersion = container.modInfo.version.toString(),
            )
        )

        modBus.register(ModBusEvents)
        ServerEvents.wire()
        NeoForge.EVENT_BUS.register(ServerEvents)
        if (FMLEnvironment.getDist().isClient) {
            NeoForge.EVENT_BUS.register(ClientEvents)
        }
    }

    object ModBusEvents {
        @SubscribeEvent
        fun onRegisterPayloads(event: RegisterPayloadHandlersEvent) {
            Payloads.register(event)
        }

        @SubscribeEvent
        fun onClientSetup(@Suppress("UNUSED_PARAMETER") event: FMLClientSetupEvent) {
            gg.earu.chatsounds.ClientConfig.load()
            gg.earu.chatsounds.data.Blacklist.load()
            ClientEvents.wireLongMessages()
            // List compilation is fully async; playback and completion gate on DataLoader state.
            DataLoader.startup()
        }
    }
}
