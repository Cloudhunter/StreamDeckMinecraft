package uk.co.cloudhunter.streamdeckminecraft;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.*;
import net.minecraft.client.renderer.*;
import net.minecraft.client.resources.I18n;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemBow;
import net.minecraft.item.ItemFishingRod;
import net.minecraft.item.ItemStack;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.relauncher.ReflectionHelper;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.logging.log4j.Logger;
import org.lwjgl.input.Mouse;
import uk.co.cloudhunter.streamdeckjava.IStreamDeck;
import uk.co.cloudhunter.streamdeckjava.StreamDeckJava;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.LinkedList;

import static org.lwjgl.opengl.GL11.*;

@Mod(modid = StreamDeckMinecraft.MODID)
public class StreamDeckMinecraft
{
    static final String MODID = "streamdeck";
    private static IStreamDeck deck;

    private static Framebuffer buffer;

    private static RenderItem itemRenderer;

    private static final Object byteSync = new Object();
    private static final Object deckSync = new Object();
    private static final Object renderLock = new Object();

    private static RenderMode mode = RenderMode.BINDINGS;
    private boolean justSwitched;

    private static Logger LOGGER;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent e)
    {
        LOGGER = e.getModLog();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent e)
    {
        deck = StreamDeckJava.getFirstStreamDeck();

        if (deck == null)
        {
            LOGGER.error("StreamDeck not found. Please check it is connected!");
        }

        MinecraftForge.EVENT_BUS.register(this);

        for (int i = 0; i < deck.getNumberOfKeys(); i++)
        {
            deck.setKeyColour(i, 0xFF000000);
        }

        MinecraftForge.EVENT_BUS.register(this);
        Runnable task2 = () -> {
            while (true)
            {
                boolean[] ourRenderChanged;
                synchronized (renderLock)
                {
                    if (!renderChanged)
                    {
                        continue;
                    }
                    ourRenderChanged = rendersChanged;
                    renderChanged = false;
                }
                synchronized (byteSync)
                {
                    if (bytes != null)
                    {
                        int height = 72;
                        int width = 72 * 9;

                        for (int x = 0; x < width; ++x)
                        {
                            int image = (x / 72);
                            if (!ourRenderChanged[image])
                            {
                                continue;
                            }
                            for (int y = 0; y < height; ++y)
                            {
                                int i = (x + y * width) * 4;
                                int realX = 71 - (x % 72);
                                int realY = 71 - y;
                                int realI = (realX + (realY * 72)) * 3;
                                images[image][realI + 2] = bytes.get(i);
                                images[image][realI + 1] = bytes.get(i + 1);
                                images[image][realI] = bytes.get(i + 2);
                            }
                        }
                    }
                }

                synchronized (deckSync)
                {
                    for (int i = 0; i < itemsToRender; i++)
                    {
                        if (!ourRenderChanged[i])
                            continue;
                        rendersChanged[i] = false;

                        deck.setKeyBitmap(i, images[i]);
                    }
                }
            }
        };

        new Thread(task2).start();

        deck.registerKeyListener(keyState ->
        {
            if (keyState.keyChanged(10) && keyState.keyPressed(10))
            {
                mode = mode.next();
                justSwitched = true;
                first = true;
            }

            switch (mode)
            {
                case HOTBAR:
                    for (int i = 0; i < 9; i++)
                    {
                        int row = i / 3;
                        int start = (row + 1) * 5 - 1;
                        int key = start - (i % 3);
                        if (keyState.keyChanged(key) && keyState.keyPressed(key) && Minecraft.getMinecraft().player != null)
                        {
                            Minecraft.getMinecraft().player.inventory.currentItem = i;
                        }
                    }
                    break;
                case BINDINGS:
                    for (int key = 0; key < deck.getNumberOfKeys() - 1; key++)
                    {
                        if (keyState.keyChanged(key))
                        {
                            synchronized (inputQueue)
                            {
                                inputQueue.add(Pair.of((byte)(-50 - key), keyState.keyPressed(key)));
                            }
                        }
                    }
                    break;
            }
        });

        cacheStacks = new ItemStack[9];

        for (int i = 0; i < 9; i++)
            cacheStacks[i] = ItemStack.EMPTY;
    }

    public enum RenderMode
    {
        HOTBAR,
        BINDINGS,
        INFO;

        private static RenderMode[] vals = values();
        public RenderMode next()
        {
            return vals[(this.ordinal()+1) % vals.length];
        }
    }
    
    Field keyBindingListField;
    Field listEntriesField;
    Field keybindingField;
    @SubscribeEvent
    public void guiInit(GuiScreenEvent.InitGuiEvent.Post e)
    {
        GuiScreen gui = e.getGui();
        if (gui instanceof GuiControls)
        {
            if (keyBindingListField == null)
            {
                keyBindingListField = ReflectionHelper.findField(GuiControls.class, "keyBindingList");
                keyBindingListField.setAccessible(true);
                listEntriesField = ReflectionHelper.findField(GuiKeyBindingList.class, "listEntries");
                listEntriesField.setAccessible(true);
                keybindingField = ReflectionHelper.findField(GuiKeyBindingList.KeyEntry.class, "keybinding");
                keybindingField.setAccessible(true);
            }

            try
            {
                GuiKeyBindingList keyBindingList = (GuiKeyBindingList) keyBindingListField.get(gui);
                GuiListExtended.IGuiListEntry[] listEntries = (GuiListExtended.IGuiListEntry[]) listEntriesField.get(keyBindingList);
                for (int i = 0; i < listEntries.length; i++)
                {
                    GuiListExtended.IGuiListEntry entry = listEntries[i];
                    if (entry instanceof GuiKeyBindingList.KeyEntry)
                    {
                        KeyBinding keybinding = (KeyBinding) keybindingField.get(entry);
                        int width = Minecraft.getMinecraft().fontRenderer.getStringWidth(keybinding.getKeyDescription());
                        if (width > maxListLabelWidth)
                        {
                            maxListLabelWidth = width;
                        }
                        listEntries[i] = new KeyEntry(keybinding, (GuiControls) gui);
                    }
                }
            }
            catch (IllegalAccessException e1)
            {
                e1.printStackTrace();
            }
        }
    }
    
    public String ourGetDisplayName(KeyBinding binding)
    {
        if (binding.getKeyCode() <= -150 && binding.getKeyCode() >= -150 - deck.getNumberOfKeys())
        {
            int key = Math.abs(binding.getKeyCode() + 150);
            System.out.println(key / 5);
            key = (((key / 5) + 1) * 5) - (key % 5);
            return I18n.format("streamdeck.key", key);
        }

        return binding.getDisplayName();
    }
    
    private int maxListLabelWidth = 0;

    // Taken from GuiKeyBindingList.KeyEntry - mostly just changing button rendering text
    public class KeyEntry implements GuiListExtended.IGuiListEntry
    {
        /** The keybinding specified for this KeyEntry */
        private final KeyBinding keybinding;
        /** The localized key description for this KeyEntry */
        private final String keyDesc;
        private final GuiButton btnChangeKeyBinding;
        private final GuiButton btnReset;
        private final GuiControls controlsScreen;
        private final Minecraft mc = Minecraft.getMinecraft();

        private KeyEntry(KeyBinding name, GuiControls parent)
        {
            this.controlsScreen = parent;
            this.keybinding = name;
            this.keyDesc = I18n.format(name.getKeyDescription());
            this.btnChangeKeyBinding = new GuiButton(0, 0, 0, 95, 20, I18n.format(name.getKeyDescription()));
            this.btnReset = new GuiButton(0, 0, 0, 50, 20, I18n.format("controls.reset"));
        }

        public void drawEntry(int slotIndex, int x, int y, int listWidth, int slotHeight, int mouseX, int mouseY, boolean isSelected, float partialTicks)
        {
            boolean flag = controlsScreen.buttonId == this.keybinding;
            mc.fontRenderer.drawString(this.keyDesc, x + 90 - maxListLabelWidth, y + slotHeight / 2 - mc.fontRenderer.FONT_HEIGHT / 2, 16777215);
            this.btnReset.x = x + 210;
            this.btnReset.y = y;
            this.btnReset.enabled = this.keybinding.isSetToDefaultValue();
            this.btnReset.drawButton(mc, mouseX, mouseY, partialTicks);
            this.btnChangeKeyBinding.x = x + 105;
            this.btnChangeKeyBinding.y = y;
            this.btnChangeKeyBinding.displayString = ourGetDisplayName(this.keybinding);
            boolean flag1 = false;
            boolean keyCodeModifierConflict = true; // less severe form of conflict, like SHIFT conflicting with SHIFT+G

            if (this.keybinding.getKeyCode() != 0)
            {
                for (KeyBinding keybinding : mc.gameSettings.keyBindings)
                {
                    if (keybinding != this.keybinding && keybinding.conflicts(this.keybinding))
                    {
                        flag1 = true;
                        keyCodeModifierConflict &= keybinding.hasKeyCodeModifierConflict(this.keybinding);
                    }
                }
            }

            if (flag)
            {
                this.btnChangeKeyBinding.displayString = TextFormatting.WHITE + "> " + TextFormatting.YELLOW + this.btnChangeKeyBinding.displayString + TextFormatting.WHITE + " <";
            }
            else if (flag1)
            {
                this.btnChangeKeyBinding.displayString = (keyCodeModifierConflict ? TextFormatting.GOLD : TextFormatting.RED) + this.btnChangeKeyBinding.displayString;
            }

            this.btnChangeKeyBinding.drawButton(mc, mouseX, mouseY, partialTicks);
        }

        /**
         * Called when the mouse is clicked within this entry. Returning true means that something within this entry was
         * clicked and the list should not be dragged.
         */
        public boolean mousePressed(int slotIndex, int mouseX, int mouseY, int mouseEvent, int relativeX, int relativeY)
        {
            if (this.btnChangeKeyBinding.mousePressed(mc, mouseX, mouseY))
            {
                controlsScreen.buttonId = this.keybinding;
                return true;
            }
            else if (this.btnReset.mousePressed(mc, mouseX, mouseY))
            {
                this.keybinding.setToDefault();
                mc.gameSettings.setOptionKeyBinding(this.keybinding, this.keybinding.getKeyCodeDefault());
                KeyBinding.resetKeyBindingArrayAndHash();
                return true;
            }
            else
            {
                return false;
            }
        }

        /**
         * Fired when the mouse button is released. Arguments: index, x, y, mouseEvent, relativeX, relativeY
         */
        public void mouseReleased(int slotIndex, int x, int y, int mouseEvent, int relativeX, int relativeY)
        {
            this.btnChangeKeyBinding.mouseReleased(x, y);
            this.btnReset.mouseReleased(x, y);
        }

        public void updatePosition(int p_192633_1_, int p_192633_2_, int p_192633_3_, float p_192633_4_)
        {
        }
    }

    private LinkedList<Pair<Byte, Boolean>> inputQueue = new LinkedList<>();

    int itemsToRender = 15;

    ByteBuffer bytes;
    byte[][] images = new byte[15][72 * 72 * 3];

    int width = 0;
    int height = 0;

    int backColour = 0xFF000000;
    int selectedColour = 0xFF0000FF;

    boolean[] rendersChanged = new boolean[15];

    boolean shouldRender = false;

    ByteBuffer mouseBuffer = null;

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent e)
    {
        if (e.phase.equals(TickEvent.Phase.END))
            return;

        if (mouseBuffer == null)
        {
            try
            {
                Field field = Mouse.class.getDeclaredField("readBuffer");
                field.setAccessible(true);
                mouseBuffer = (ByteBuffer) field.get(null);
            }
            catch (NoSuchFieldException | IllegalAccessException e1)
            {
                e1.printStackTrace();
            }
        }

        Pair<Byte, Boolean> input = null;

        synchronized (inputQueue)
        {
            if (!inputQueue.isEmpty())
                input = inputQueue.removeFirst();
        }

        if (input != null)
        {
            byte code = input.getLeft();
            boolean pressed = input.getRight();
            System.out.println("Setting " + code + " to " + pressed);

            mouseBuffer.compact();
            mouseBuffer.put(code);
            mouseBuffer.put((byte)(pressed ? 1 : 0));
            mouseBuffer.putInt(0);
            mouseBuffer.putInt(0);
            mouseBuffer.putInt(0);
            mouseBuffer.putLong(1000);
            mouseBuffer.flip();
        }

        shouldRender = true;
    }

    ItemStack[] cacheStacks = null;

    int prevSelected;

    boolean renderChanged = false;
    boolean first = true;

    @SubscribeEvent
    public void tick(TickEvent.RenderTickEvent e)
    {
        if (e.phase.equals(TickEvent.Phase.END))
            return;

        if(!shouldRender)
            return;

        shouldRender = false;

        EntityPlayer entityplayer = Minecraft.getMinecraft().player;

        if (entityplayer == null)
            return;

        if (itemRenderer == null)
            itemRenderer = Minecraft.getMinecraft().getRenderItem();

        if (buffer == null)
        {
            ScaledResolution res = new ScaledResolution(Minecraft.getMinecraft());
            width = res.getScaledWidth() * res.getScaleFactor();
            height = res.getScaledHeight() * res.getScaleFactor();

            // lets find out how many we can fit on one line
            buffer = new Framebuffer(width, height, true);
            buffer.setFramebufferColor(0, 0, 0, 1);
        }
        else if (buffer.framebufferObject == -1)
            return;
        else
        {
            buffer.framebufferClear();
            buffer.bindFramebuffer(true);
        }

        int numOfBytes = 72 * 9 * 72 * 4;

        if (bytes == null)
            bytes = ByteBuffer.allocateDirect(numOfBytes);

        if (justSwitched)
        {
            synchronized (renderLock)
            {
                buffer.framebufferClear();
                renderChanged = true;
                for (int i = 0; i < rendersChanged.length; i++)
                {
                    rendersChanged[i] = true;
                }
                justSwitched = false;

                glReadPixels(0, height - 72, 72 * 9, 72, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                renderChanged = true;
            }

            return;
        }

        switch (mode)
        {
            case HOTBAR:
                hotbarRender(e, entityplayer);
                break;
            case INFO:
                break;
        }


        buffer.unbindFramebuffer();

        Minecraft.getMinecraft().getFramebuffer().bindFramebuffer(true);
    }

    protected void hotbarRender(TickEvent.RenderTickEvent e, EntityPlayer entityplayer)
    {
        int selected = entityplayer.inventory.currentItem;

        boolean[] tempRendersChanged = new boolean[15];
        boolean tempRenderChanged = false;

        for (int l = 0; l < 9; ++l)
        {
            int left = l * 36;
            int top = 0;

            ItemStack item = entityplayer.inventory.mainInventory.get(l);

            boolean render = false;

            if (first)
            {
                render = true;
            }


            if ((selected == l || prevSelected == l) && selected != prevSelected)
                render = true;

            if (item.isItemEqual(cacheStacks[l]) && ItemStack.areItemStackShareTagsEqual(cacheStacks[l], item))
            {
                if (ModConfig.alwaysRender || item.hasEffect() || item.getItem() instanceof ItemBow || item.getItem() instanceof ItemFishingRod)
                {
                    render = true;
                }
            }
            else if (!(cacheStacks[l].isEmpty() && item.isEmpty()))
            {
                render = true;
            }

            if (render)
            {
                if (selected == l)
                {
                    GuiScreen.drawRect(left, top,left + 72, top + 72, selectedColour);
                }
                else
                {
                    GuiScreen.drawRect(left, top,left + 72, top + 72, backColour);
                }
                this.renderHotbarItem(left, top, e.renderTickTime, entityplayer, entityplayer.inventory.mainInventory.get(l), 2.13F);
                tempRendersChanged[l] = true;
                tempRenderChanged = true;
            }
            if (item.isEmpty())
            {
                cacheStacks[l] = ItemStack.EMPTY;
            }
            else
            {
                cacheStacks[l] = item.copy();
            }

        }

        first = false;

        prevSelected = selected;

        if (tempRenderChanged)
        {
            synchronized (renderLock)
            {
                glReadPixels(0, height - 72, 72 * 9, 72, GL_RGBA, GL_UNSIGNED_BYTE, bytes);
                rendersChanged = tempRendersChanged;
                renderChanged = true;
            }
        }

    }

    protected void renderHotbarItem(int x, int y, float p_184044_3_, EntityPlayer player, ItemStack stack, float size)
    {
        if (!stack.isEmpty())
        {
            GlStateManager.enableRescaleNormal();
            GlStateManager.enableBlend();
            GlStateManager.tryBlendFuncSeparate(GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE_MINUS_SRC_ALPHA, GlStateManager.SourceFactor.ONE, GlStateManager.DestFactor.ZERO);
            RenderHelper.enableGUIStandardItemLighting();

            float xLoc = ((float)x / size);
            float yLoc = ((float)y / size);

            GlStateManager.scale(size, size, size);
            GlStateManager.pushMatrix();
            GlStateManager.translate(xLoc, yLoc, 0.0F);
            GlStateManager.enableDepth();
            itemRenderer.renderItemAndEffectIntoGUI(player, stack, 0, 0);
            itemRenderer.renderItemOverlays(Minecraft.getMinecraft().fontRenderer, stack, 0, 0);
            GlStateManager.disableDepth();
            GlStateManager.popMatrix();
            GlStateManager.scale(1.0F / size, 1.0F / size, 1.0F / size);

            RenderHelper.disableStandardItemLighting();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableBlend();
        }
    }
}
