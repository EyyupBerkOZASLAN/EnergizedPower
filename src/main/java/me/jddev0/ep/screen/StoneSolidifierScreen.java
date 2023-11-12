package me.jddev0.ep.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.*;
import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.ChangeStoneSolidifierRecipeIndexC2SPacket;
import me.jddev0.ep.recipe.StoneSolidifierRecipe;
import me.jddev0.ep.util.FluidUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import net.minecraftforge.client.extensions.common.IClientFluidTypeExtensions;
import net.minecraftforge.fluids.FluidStack;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@OnlyIn(Dist.CLIENT)
public class StoneSolidifierScreen extends AbstractGenericEnergyStorageContainerScreen<StoneSolidifierMenu> {
    public StoneSolidifierScreen(StoneSolidifierMenu menu, Inventory inventory, Component component) {
        super(menu, inventory, component,
                "tooltip.energizedpower.recipe.energy_required_to_finish.txt",
                new ResourceLocation(EnergizedPowerMod.MODID, "textures/gui/container/stone_solidifier.png"), 8, 17);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int mouseButton) {
        if(mouseButton == 0) {
            boolean clicked = false;

            int diff = 0;

            //Up button
            if(isHovering(85, 19, 11, 12, mouseX, mouseY)) {
                diff = 1;
                clicked = true;
            }

            //Down button
            if(isHovering(116, 19, 11, 12, mouseX, mouseY)) {
                diff = -1;
                clicked = true;
            }

            if(diff != 0) {
                ModMessages.sendToServer(new ChangeStoneSolidifierRecipeIndexC2SPacket(menu.getBlockEntity().getBlockPos(),
                        diff == 1));
            }

            if(clicked)
                minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.f));
        }

        return super.mouseClicked(mouseX, mouseY, mouseButton);
    }

    @Override
    protected void renderBg(PoseStack poseStack, float partialTick, int mouseX, int mouseY) {
        super.renderBg(poseStack, partialTick, mouseX, mouseY);

        int x = (width - imageWidth) / 2;
        int y = (height - imageHeight) / 2;

        for(int i = 0;i < 2;i++) {
            renderFluidMeterContent(i, poseStack, x, y);
            renderFluidMeterOverlay(i, poseStack, x, y);
        }

        renderCurrentRecipeOuput(poseStack, x, y);

        renderButtons(poseStack, x, y, mouseX, mouseY);

        renderProgressArrows(poseStack, x, y);
    }

    private void renderFluidMeterContent(int tank, PoseStack poseStack, int x, int y) {
        RenderSystem.enableBlend();
        poseStack.pushPose();

        poseStack.translate(x + (tank == 0?44:152), y + 17, 0);

        renderFluidStack(tank, poseStack);

        poseStack.popPose();
        RenderSystem.setShaderColor(1.f, 1.f, 1.f, 1.f);
        RenderSystem.disableBlend();
    }

    private void renderFluidStack(int tank, PoseStack poseStack) {
        FluidStack fluidStack = menu.getFluid(tank);
        if(fluidStack.isEmpty())
            return;

        int capacity = menu.getTankCapacity(tank);

        Fluid fluid = fluidStack.getFluid();
        IClientFluidTypeExtensions fluidTypeExtensions = IClientFluidTypeExtensions.of(fluid);
        ResourceLocation stillFluidImageId = fluidTypeExtensions.getStillTexture(fluidStack);
        TextureAtlasSprite stillFluidSprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS).
                apply(stillFluidImageId);

        int fluidColorTint = fluidTypeExtensions.getTintColor(fluidStack);

        int fluidMeterPos = 52 - ((fluidStack.getAmount() <= 0 || capacity == 0)?0:
                (Math.min(fluidStack.getAmount(), capacity - 1) * 52 / capacity + 1));

        RenderSystem.setShaderTexture(0, InventoryMenu.BLOCK_ATLAS);

        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.setShaderColor((fluidColorTint >> 16 & 0xFF) / 255.f,
                (fluidColorTint >> 8 & 0xFF) / 255.f, (fluidColorTint & 0xFF) / 255.f,
                (fluidColorTint >> 24 & 0xFF) / 255.f);

        Matrix4f mat = poseStack.last().pose();

        for(int yOffset = 52;yOffset > fluidMeterPos;yOffset -= 16) {
            int height = Math.min(yOffset - fluidMeterPos, 16);

            float u0 = stillFluidSprite.getU0();
            float u1 = stillFluidSprite.getU1();
            float v0 = stillFluidSprite.getV0();
            float v1 = stillFluidSprite.getV1();
            v0 = v0 - ((16 - height) / 16.f * (v0 - v1));

            Tesselator tesselator = Tesselator.getInstance();
            BufferBuilder bufferBuilder = tesselator.getBuilder();
            bufferBuilder.begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_TEX);
            bufferBuilder.vertex(mat, 0, yOffset, 0).uv(u0, v1).endVertex();
            bufferBuilder.vertex(mat, 16, yOffset, 0).uv(u1, v1).endVertex();
            bufferBuilder.vertex(mat, 16, yOffset - height, 0).uv(u1, v0).endVertex();
            bufferBuilder.vertex(mat, 0, yOffset - height, 0).uv(u0, v0).endVertex();
            tesselator.end();
        }
    }

    private void renderFluidMeterOverlay(int tank, PoseStack poseStack, int x, int y) {
        RenderSystem.setShaderTexture(0, TEXTURE);
        blit(poseStack, x + (tank == 0?44:152), y + 17, 176, 53, 16, 52);
    }

    private void renderCurrentRecipeOuput(PoseStack poseStack, int x, int y) {
        StoneSolidifierRecipe currentRecipe = menu.getCurrentRecipe();
        if(currentRecipe == null)
            return;

        ItemStack output = currentRecipe.getOutput();
        if(!output.isEmpty()) {
            poseStack.pushPose();
            poseStack.translate(0.f, 0.f, 100.f);

            itemRenderer.renderAndDecorateItem(poseStack, output, x + 98, y + 17, 98 + 17 * this.imageWidth);

            poseStack.popPose();

            RenderSystem.setShaderTexture(0, TEXTURE);
        }
    }

    private void renderButtons(PoseStack poseStack, int x, int y, int mouseX, int mouseY) {
        RenderSystem.setShaderTexture(0, TEXTURE);

        //Up button
        if(isHovering(85, 19, 11, 12, mouseX, mouseY)) {
            blit(poseStack, x + 85, y + 19, 176, 135, 11, 12);
        }

        //Down button
        if(isHovering(116, 19, 11, 12, mouseX, mouseY)) {
            blit(poseStack, x + 116, y + 19, 187, 135, 11, 12);
        }
    }

    private void renderProgressArrows(PoseStack poseStack, int x, int y) {
        RenderSystem.setShaderTexture(0, TEXTURE);

        if(menu.isCraftingActive()) {
            blit(poseStack, x + 69, y + 45, 176, 106, menu.getScaledProgressArrowSize(), 14);
            blit(poseStack, x + 143 - menu.getScaledProgressArrowSize(), y + 45,
                    196 - menu.getScaledProgressArrowSize(), 120, menu.getScaledProgressArrowSize(), 14);
        }
    }

    @Override
    protected void renderTooltip(PoseStack poseStack, int mouseX, int mouseY) {
        super.renderTooltip(poseStack, mouseX, mouseY);

        for(int i = 0;i < 2;i++) {
            //Fluid meter

            if(isHovering(i == 0?44:152, 17, 16, 52, mouseX, mouseY)) {
                List<Component> components = new ArrayList<>(2);

                boolean fluidEmpty =  menu.getFluid(i).isEmpty();

                int fluidAmount = fluidEmpty?0:menu.getFluid(i).getAmount();

                Component tooltipComponent = Component.translatable("tooltip.energizedpower.fluid_meter.content_amount.txt",
                        FluidUtils.getFluidAmountWithPrefix(fluidAmount), FluidUtils.getFluidAmountWithPrefix(menu.getTankCapacity(i)));

                if(!fluidEmpty) {
                    tooltipComponent = Component.translatable(menu.getFluid(i).getTranslationKey()).append(" ").
                            append(tooltipComponent);
                }

                components.add(tooltipComponent);

                renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
            }
        }

        //Current recipe
        StoneSolidifierRecipe currentRecipe = menu.getCurrentRecipe();
        if(currentRecipe != null) {
            if(isHovering(98, 17, 16, 16, mouseX, mouseY)) {
                ItemStack output = currentRecipe.getOutput();
                if(!output.isEmpty()) {
                    List<Component> components = new ArrayList<>(2);
                    components.add(Component.translatable("tooltip.energizedpower.count_with_item.txt", output.getCount(),
                            output.getHoverName()));

                    renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
                }
            }
        }

        //Up button
        if(isHovering(85, 19, 11, 12, mouseX, mouseY)) {
            List<Component> components = new ArrayList<>(2);
            components.add(Component.translatable("tooltip.energizedpower.stone_solidifier.btn.up"));

            renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
        }

        //Down button
        if(isHovering(116, 19, 11, 12, mouseX, mouseY)) {
            List<Component> components = new ArrayList<>(2);
            components.add(Component.translatable("tooltip.energizedpower.stone_solidifier.btn.down"));

            renderTooltip(poseStack, components, Optional.empty(), mouseX, mouseY);
        }
    }
}
