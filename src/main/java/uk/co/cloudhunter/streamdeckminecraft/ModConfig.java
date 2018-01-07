package uk.co.cloudhunter.streamdeckminecraft;

import net.minecraftforge.common.config.Config;
import net.minecraftforge.common.config.ConfigManager;
import net.minecraftforge.fml.client.event.ConfigChangedEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

@Config(modid = StreamDeckMinecraft.MODID)
@Config.LangKey("streamdeck.config.title")
public class ModConfig
{
    @Config.Comment("Always render items")
    public static boolean alwaysRender = false;

    @Mod.EventBusSubscriber(modid = StreamDeckMinecraft.MODID)
    private static class EventHandler
    {
        @SubscribeEvent
        public static void onConfigChanged(final ConfigChangedEvent.OnConfigChangedEvent event)
        {
            if (event.getModID().equals(StreamDeckMinecraft.MODID))
            {
                ConfigManager.sync(StreamDeckMinecraft.MODID, Config.Type.INSTANCE);
            }
        }
    }
}
