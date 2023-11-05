package me.jddev0.ep.block.entity;

import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.energy.ExtractOnlyEnergyStorage;
import me.jddev0.ep.fluid.FluidStoragePacketUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.networking.packet.FluidSyncS2CPacket;
import me.jddev0.ep.recipe.ThermalGeneratorRecipe;
import me.jddev0.ep.screen.ThermalGeneratorMenu;
import me.jddev0.ep.util.ByteUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.Fluid;
import net.neoforged.neoforge.common.capabilities.Capability;
import net.neoforged.neoforge.common.capabilities.Capabilities;
import net.neoforged.neoforge.common.util.LazyOptional;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;

public class ThermalGeneratorBlockEntity extends BlockEntity implements MenuProvider, EnergyStoragePacketUpdate,
        FluidStoragePacketUpdate {
    private static final int MAX_EXTRACT = ModConfigs.COMMON_THERMAL_GENERATOR_TRANSFER_RATE.getValue();

    public static final float ENERGY_PRODUCTION_MULTIPLIER = ModConfigs.COMMON_THERMAL_GENERATOR_ENERGY_PRODUCTION_MULTIPLIER.getValue();

    private final ExtractOnlyEnergyStorage energyStorage;
    private final LazyOptional<IEnergyStorage> lazyEnergyStorage;

    private final FluidTank fluidStorage;
    private final LazyOptional<IFluidHandler> lazyFluidStorage;

    protected final ContainerData data;

    public ThermalGeneratorBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.THERMAL_GENERATOR_ENTITY.get(), blockPos, blockState);
        energyStorage = new ExtractOnlyEnergyStorage(0, ModConfigs.COMMON_THERMAL_GENERATOR_CAPACITY.getValue(),
                MAX_EXTRACT) {
            @Override
            protected void onChange() {
                setChanged();

                if(level != null && !level.isClientSide())
                    ModMessages.sendToPlayersWithinXBlocks(
                            new EnergySyncS2CPacket(energy, capacity, getBlockPos()),
                            getBlockPos(), level.dimension(), 32
                    );
            }
        };
        fluidStorage = new FluidTank(ModConfigs.COMMON_THERMAL_GENERATOR_FLUID_TANK_CAPACITY.getValue() * 1000) {
            @Override
            protected void onContentsChanged() {
                setChanged();

                if(level != null && !level.isClientSide())
                    ModMessages.sendToPlayersWithinXBlocks(
                            new FluidSyncS2CPacket(fluid, capacity, getBlockPos()),
                            getBlockPos(), level.dimension(), 32
                    );
            }

            @Override
            public boolean isFluidValid(FluidStack stack) {
                if(!super.isFluidValid(stack) || level == null)
                    return false;

                List<RecipeHolder<ThermalGeneratorRecipe>> recipes = level.getRecipeManager().getAllRecipesFor(ThermalGeneratorRecipe.Type.INSTANCE);

                return recipes.stream().map(RecipeHolder::value).map(ThermalGeneratorRecipe::getInput).
                        anyMatch(inputs -> Arrays.stream(inputs).anyMatch(input -> stack.getFluid() == input));
            }
        };
        data = new ContainerData() {
            @Override
            public int get(int index) {
                if(level == null || index > 1)
                    return 0;

                List<RecipeHolder<ThermalGeneratorRecipe>> recipes = level.getRecipeManager().getAllRecipesFor(ThermalGeneratorRecipe.Type.INSTANCE);

                int rawProduction = 0;
                outer:
                for(RecipeHolder<ThermalGeneratorRecipe> recipe:recipes) {
                    for(Fluid fluid:recipe.value().getInput()) {
                        if(ThermalGeneratorBlockEntity.this.fluidStorage.getFluid().getFluid() == fluid) {
                            rawProduction = recipe.value().getEnergyProduction();
                            rawProduction = (int)(rawProduction * ENERGY_PRODUCTION_MULTIPLIER);

                            break outer;
                        }
                    }
                }

                //Calculate real production (raw production is in x FE per 1000 mB, use fluid amount without cap)
                int productionLeft = (int)(rawProduction * ThermalGeneratorBlockEntity.this.fluidStorage.getFluidAmount() / 1000.f);

                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(productionLeft, index);
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> {}
                }
            }

            @Override
            public int getCount() {
                return 2;
            }
        };

        lazyEnergyStorage = LazyOptional.of(() -> energyStorage);
        lazyFluidStorage = LazyOptional.of(() -> fluidStorage);
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.energizedpower.thermal_generator");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        ModMessages.sendToPlayer(new EnergySyncS2CPacket(energyStorage.getEnergy(), energyStorage.getCapacity(), getBlockPos()), (ServerPlayer)player);
        ModMessages.sendToPlayer(new FluidSyncS2CPacket(fluidStorage.getFluid(), fluidStorage.getCapacity(), worldPosition), (ServerPlayer)player);

        return new ThermalGeneratorMenu(id, inventory, this, this.data);
    }

    public int getRedstoneOutput() {
        float fullnessPercent = 0;
        boolean isEmptyFlag = true;

        FluidStack fluid = fluidStorage.getFluid();
        if(!fluid.isEmpty()) {
            fullnessPercent = (float)fluid.getAmount() / fluidStorage.getCapacity();
            isEmptyFlag = false;
        }

        return Math.min(Mth.floor(fullnessPercent * 14.f) + (isEmptyFlag?0:1), 15);
    }

    @Override
    public @NotNull <T> LazyOptional<T> getCapability(@NotNull Capability<T> cap, @Nullable Direction side) {
        if(cap == Capabilities.ENERGY) {
            return lazyEnergyStorage.cast();
        }else if(cap == Capabilities.FLUID_HANDLER) {
            return lazyFluidStorage.cast();
        }

        return super.getCapability(cap, side);
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        nbt.put("energy", energyStorage.saveNBT());
        nbt.put("fluid", fluidStorage.writeToNBT(new CompoundTag()));

        super.saveAdditional(nbt);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        energyStorage.loadNBT(nbt.get("energy"));
        fluidStorage.readFromNBT(nbt.getCompound("fluid"));
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, ThermalGeneratorBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        List<RecipeHolder<ThermalGeneratorRecipe>> recipes = level.getRecipeManager().getAllRecipesFor(ThermalGeneratorRecipe.Type.INSTANCE);

        int rawProduction = 0;
        outer:
        for(RecipeHolder<ThermalGeneratorRecipe> recipe:recipes) {
            for(Fluid fluid:recipe.value().getInput()) {
                if(blockEntity.fluidStorage.getFluid().getFluid() == fluid) {
                    rawProduction = recipe.value().getEnergyProduction();
                    rawProduction = (int)(rawProduction * ENERGY_PRODUCTION_MULTIPLIER);

                    break outer;
                }
            }
        }

        if(rawProduction > 0 && blockEntity.energyStorage.getEnergy() < blockEntity.energyStorage.getCapacity()) {
            //Calculate real production (raw production is in x FE per 1000 mB, 50 mB of fluid can be consumed per tick)
            int production = (int)(rawProduction * (Math.min(blockEntity.fluidStorage.getFluidAmount(), 50) / 1000.f));

            //Cap production
            production = Math.min(production, blockEntity.energyStorage.getCapacity() - blockEntity.energyStorage.getEnergy());

            int fluidAmount = (int)((float)production/rawProduction * 1000);

            //Re-calculate energy production (Prevents draining of not enough fluid)
            production = (int)(rawProduction * fluidAmount / 1000.f);

            blockEntity.fluidStorage.drain(fluidAmount, IFluidHandler.FluidAction.EXECUTE);

            blockEntity.energyStorage.setEnergy(Math.min(blockEntity.energyStorage.getCapacity(),
                    blockEntity.energyStorage.getEnergy() + production));
        }

        transferEnergy(level, blockPos, state, blockEntity);
    }

    private static void transferEnergy(Level level, BlockPos blockPos, BlockState state, ThermalGeneratorBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        List<IEnergyStorage> consumerItems = new LinkedList<>();
        List<Integer> consumerEnergyValues = new LinkedList<>();
        int consumptionSum = 0;
        for(Direction direction:Direction.values()) {
            BlockPos testPos = blockPos.relative(direction);

            BlockEntity testBlockEntity = level.getBlockEntity(testPos);
            if(testBlockEntity == null)
                continue;

            LazyOptional<IEnergyStorage> energyStorageLazyOptional = testBlockEntity.getCapability(Capabilities.ENERGY, direction.getOpposite());
            if(!energyStorageLazyOptional.isPresent())
                continue;

            IEnergyStorage energyStorage = energyStorageLazyOptional.orElse(null);
            if(!energyStorage.canReceive())
                continue;

            int received = energyStorage.receiveEnergy(Math.min(MAX_EXTRACT, blockEntity.energyStorage.getEnergy()), true);
            if(received <= 0)
                continue;

            consumptionSum += received;
            consumerItems.add(energyStorage);
            consumerEnergyValues.add(received);
        }

        List<Integer> consumerEnergyDistributed = new LinkedList<>();
        for(int i = 0;i < consumerItems.size();i++)
            consumerEnergyDistributed.add(0);

        int consumptionLeft = Math.min(MAX_EXTRACT, Math.min(blockEntity.energyStorage.getEnergy(), consumptionSum));
        blockEntity.energyStorage.extractEnergy(consumptionLeft, false);

        int divisor = consumerItems.size();
        outer:
        while(consumptionLeft > 0) {
            int consumptionPerConsumer = consumptionLeft / divisor;
            if(consumptionPerConsumer == 0) {
                divisor = Math.max(1, divisor - 1);
                consumptionPerConsumer = consumptionLeft / divisor;
            }

            for(int i = 0;i < consumerEnergyValues.size();i++) {
                int consumptionDistributed = consumerEnergyDistributed.get(i);
                int consumptionOfConsumerLeft = consumerEnergyValues.get(i) - consumptionDistributed;

                int consumptionDistributedNew = Math.min(consumptionOfConsumerLeft, Math.min(consumptionPerConsumer, consumptionLeft));
                consumerEnergyDistributed.set(i, consumptionDistributed + consumptionDistributedNew);
                consumptionLeft -= consumptionDistributedNew;
                if(consumptionLeft == 0)
                    break outer;
            }
        }

        for(int i = 0;i < consumerItems.size();i++) {
            int energy = consumerEnergyDistributed.get(i);
            if(energy > 0)
                consumerItems.get(i).receiveEnergy(energy, false);
        }
    }

    public FluidStack getFluid() {
        return fluidStorage.getFluid();
    }

    public int getTankCapacity() {
        return fluidStorage.getCapacity();
    }

    public int getEnergy() {
        return energyStorage.getEnergy();
    }

    public int getCapacity() {
        return energyStorage.getCapacity();
    }

    @Override
    public void setEnergy(int energy) {
        energyStorage.setEnergyWithoutUpdate(energy);
    }

    @Override
    public void setCapacity(int capacity) {
        energyStorage.setCapacityWithoutUpdate(capacity);
    }

    @Override
    public void setFluid(FluidStack fluidStack) {
        fluidStorage.setFluid(fluidStack);
    }

    @Override
    public void setTankCapacity(int capacity) {
        fluidStorage.setCapacity(capacity);
    }
}