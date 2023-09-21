package me.jddev0.ep.recipe;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import me.jddev0.ep.EnergizedPowerMod;
import me.jddev0.ep.block.ModBlocks;
import me.jddev0.ep.codec.ArrayCodec;
import net.minecraft.fluid.Fluid;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.Recipe;
import net.minecraft.recipe.RecipeSerializer;
import net.minecraft.recipe.RecipeType;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

public class ThermalGeneratorRecipe implements Recipe<SimpleInventory> {
    private final Fluid[] input;
    private final long energyProduction;

    public ThermalGeneratorRecipe(Fluid[] input, long energyProduction) {
        this.input = input;
        this.energyProduction = energyProduction;
    }

    public Fluid[] getInput() {
        return input;
    }

    public long getEnergyProduction() {
        return energyProduction;
    }

    @Override
    public boolean matches(SimpleInventory container, World level) {
        return false;
    }

    @Override
    public ItemStack craft(SimpleInventory container, DynamicRegistryManager registryAccess) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean fits(int width, int height) {
        return true;
    }

    @Override
    public ItemStack getResult(DynamicRegistryManager registryAccess) {
        return ItemStack.EMPTY;
    }

    @Override
    public ItemStack createIcon() {
        return new ItemStack(ModBlocks.THERMAL_GENERATOR_ITEM);
    }

    @Override
    public boolean isIgnoredInRecipeBook() {
        return true;
    }

    @Override
    public RecipeSerializer<?> getSerializer() {
        return Serializer.INSTANCE;
    }

    @Override
    public RecipeType<?> getType() {
        return Type.INSTANCE;
    }

    public static final class Type implements RecipeType<ThermalGeneratorRecipe> {
        private Type() {}

        public static final Type INSTANCE = new Type();
        public static final String ID = "thermal_generator";
    }

    public static final class Serializer implements RecipeSerializer<ThermalGeneratorRecipe> {
        private Serializer() {}

        public static final Serializer INSTANCE = new Serializer();
        public static final Identifier ID = new Identifier(EnergizedPowerMod.MODID, "thermal_generator");

        private final Codec<ThermalGeneratorRecipe> CODEC_SINGLE_FLUID = RecordCodecBuilder.create((instance) -> {
            return instance.group(Registries.FLUID.getCodec().fieldOf("input").forGetter((recipe) -> {
                return recipe.input[0];
            }), Codec.LONG.fieldOf("energy").forGetter((recipe) -> {
                return recipe.energyProduction;
            })).apply(instance, (f, e) -> new ThermalGeneratorRecipe(new Fluid[] {f}, e));
        });

        private final Codec<ThermalGeneratorRecipe> CODEC_FLUID_ARRAY = RecordCodecBuilder.create((instance) -> {
            return instance.group(new ArrayCodec<>(Registries.FLUID.getCodec(), Fluid[]::new).fieldOf("input").forGetter((recipe) -> {
                return recipe.input;
            }), Codec.LONG.fieldOf("energy").forGetter((recipe) -> {
                return recipe.energyProduction;
            })).apply(instance, ThermalGeneratorRecipe::new);
        });

        @Override
        public Codec<ThermalGeneratorRecipe> codec() {
            return Codec.either(CODEC_FLUID_ARRAY, CODEC_SINGLE_FLUID).
                    xmap(e -> e.left().orElseGet(() -> e.right().orElseThrow()), Either::left);
        }

        @Override
        public ThermalGeneratorRecipe read(PacketByteBuf buffer) {
            int fluidCount = buffer.readInt();
            Fluid[] input = new Fluid[fluidCount];
            for(int i = 0;i < fluidCount;i++)
                input[i] = Registries.FLUID.get(buffer.readIdentifier());

            long energyProduction = buffer.readLong();

            return new ThermalGeneratorRecipe(input, energyProduction);
        }

        @Override
        public void write(PacketByteBuf buffer, ThermalGeneratorRecipe recipe) {
            buffer.writeInt(recipe.getInput().length);
            for(Fluid fluid:recipe.input) {
                Identifier fluidId = Registries.FLUID.getId(fluid);
                if(fluidId == null || fluidId.equals(new Identifier("empty")))
                    throw new IllegalArgumentException("Unregistered fluid '" + fluid + "'");

                buffer.writeIdentifier(fluidId);
            }

            buffer.writeLong(recipe.energyProduction);
        }
    }
}
