package me.jddev0.ep.integration.emi;

import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.render.EmiTexture;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.block.ModBlocks;
import me.jddev0.ep.block.entity.CrystalGrowthChamberBlockEntity;
import me.jddev0.ep.recipe.CrystalGrowthChamberRecipe;
import net.minecraft.client.MinecraftClient;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.List;

public class CrystalGrowthChamberEMIRecipe implements EmiRecipe {
    public static final Identifier SIMPLIFIED_TEXTURE = new Identifier(EnergizedPowerMod.MODID, "textures/block/crystal_growth_chamber_side.png");
    public static final EmiStack ITEM = EmiStack.of(ModBlocks.CRYSTAL_GROWTH_CHAMBER_ITEM);
    public static final EmiRecipeCategory CATEGORY = new EmiRecipeCategory(new Identifier(EnergizedPowerMod.MODID, "crystal_growth_chamber"),
            ITEM, new EmiTexture(SIMPLIFIED_TEXTURE, 0, 0, 16, 16, 16, 16, 16, 16));

    private final Identifier id;
    private final List<EmiIngredient> input;
    private final List<EmiStack> output;
    private final int ticks;

    public CrystalGrowthChamberEMIRecipe(RecipeEntry<CrystalGrowthChamberRecipe> recipe) {
        this.id = recipe.id();
        this.input = List.of(EmiIngredient.of(recipe.value().getInput(), recipe.value().getInputCount()));
        this.output = List.of(EmiStack.of(recipe.value().getMaxOutputCount()));
        this.ticks = (int)(recipe.value().getTicks() * CrystalGrowthChamberBlockEntity.RECIPE_DURATION_MULTIPLIER);
    }

    @Override
    public EmiRecipeCategory getCategory() {
        return CATEGORY;
    }

    @Override
    public Identifier getId() {
        return id;
    }

    @Override
    public List<EmiIngredient> getInputs() {
        return input;
    }

    @Override
    public List<EmiStack> getOutputs() {
        return output;
    }

    @Override
    public int getDisplayWidth() {
        return 98;
    }

    @Override
    public int getDisplayHeight() {
        return 38;
    }

    @Override
    public void addWidgets(WidgetHolder widgets) {
        Identifier texture = new Identifier(EnergizedPowerMod.MODID, "textures/gui/container/crystal_growth_chamber.png");
        widgets.addTexture(texture, 0, 0, 98, 38, 47, 30);

        widgets.addSlot(input.get(0), 0, 4).drawBack(false);
        widgets.addSlot(output.get(0), 76, 4).drawBack(false).recipeContext(this);

        Text ticksText = Text.translatable("recipes.energizedpower.info.ticks", ticks);
        widgets.addText(ticksText.asOrderedText(),
                widgets.getWidth() - MinecraftClient.getInstance().textRenderer.getWidth(ticksText),
                widgets.getHeight() - MinecraftClient.getInstance().textRenderer.fontHeight, Formatting.WHITE.getColorValue(), false);
    }
}