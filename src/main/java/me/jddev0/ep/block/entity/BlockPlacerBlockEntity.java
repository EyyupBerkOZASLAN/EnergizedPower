package me.jddev0.ep.block.entity;

import me.jddev0.ep.block.BlockPlacerBlock;
import me.jddev0.ep.block.entity.handler.InputOutputItemHandler;
import me.jddev0.ep.config.ModConfigs;
import me.jddev0.ep.energy.EnergyStoragePacketUpdate;
import me.jddev0.ep.energy.ReceiveOnlyEnergyStorage;
import me.jddev0.ep.machine.configuration.ComparatorMode;
import me.jddev0.ep.machine.configuration.ComparatorModeUpdate;
import me.jddev0.ep.machine.configuration.RedstoneMode;
import me.jddev0.ep.machine.configuration.RedstoneModeUpdate;
import me.jddev0.ep.networking.ModMessages;
import me.jddev0.ep.networking.packet.EnergySyncS2CPacket;
import me.jddev0.ep.screen.BlockPlacerMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.EnergyUtils;
import me.jddev0.ep.util.InventoryUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.IntTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.DirectionalPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class BlockPlacerBlockEntity extends BlockEntity implements MenuProvider, EnergyStoragePacketUpdate, RedstoneModeUpdate,
        ComparatorModeUpdate {
    private static final List<@NotNull ResourceLocation> PLACEMENT_BLACKLIST = ModConfigs.COMMON_BLOCK_PLACER_PLACEMENT_BLACKLIST.getValue();

    private static final int ENERGY_USAGE_PER_TICK = ModConfigs.COMMON_BLOCK_PLACER_ENERGY_CONSUMPTION_PER_TICK.getValue();

    private final ItemStackHandler itemHandler = new ItemStackHandler(1) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if(slot == 0) {
                return stack.getItem() instanceof BlockItem;
            }

            return super.isItemValid(slot, stack);
        }

        @Override
        public void setStackInSlot(int slot, @NotNull ItemStack stack) {
            if(slot == 0) {
                ItemStack itemStack = getStackInSlot(slot);
                if(level != null && !stack.isEmpty() && !itemStack.isEmpty() && !ItemStack.isSameItemSameTags(stack, itemStack))
                    resetProgress(worldPosition, level.getBlockState(worldPosition));
            }

            super.setStackInSlot(slot, stack);
        }

        @Override
        public int getSlotLimit(int slot) {
            return 1;
        }
    };
    private final IItemHandler itemHandlerSided = new InputOutputItemHandler(itemHandler, (i, stack) -> true, i -> false);

    private final ReceiveOnlyEnergyStorage energyStorage;

    protected final ContainerData data;
    private int progress;
    private int maxProgress = ModConfigs.COMMON_BLOCK_PLACER_PLACEMENT_DURATION.getValue();
    private int energyConsumptionLeft = -1;
    private boolean hasEnoughEnergy;
    private boolean inverseRotation;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public BlockPlacerBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.BLOCK_PLACER_ENTITY.get(), blockPos, blockState);

        energyStorage = new ReceiveOnlyEnergyStorage(0, ModConfigs.COMMON_BLOCK_PLACER_CAPACITY.getValue(),
                ModConfigs.COMMON_BLOCK_PLACER_TRANSFER_RATE.getValue()) {
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
        data = new ContainerData() {
            @Override
            public int get(int index) {
                return switch(index) {
                    case 0, 1 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.progress, index);
                    case 2, 3 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.maxProgress, index - 2);
                    case 4, 5 -> ByteUtils.get2Bytes(BlockPlacerBlockEntity.this.energyConsumptionLeft, index - 4);
                    case 6 -> hasEnoughEnergy?1:0;
                    case 7 -> inverseRotation?1:0;
                    case 8 -> redstoneMode.ordinal();
                    case 9 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> BlockPlacerBlockEntity.this.progress = ByteUtils.with2Bytes(
                            BlockPlacerBlockEntity.this.progress, (short)value, index
                    );
                    case 2, 3 -> BlockPlacerBlockEntity.this.maxProgress = ByteUtils.with2Bytes(
                            BlockPlacerBlockEntity.this.maxProgress, (short)value, index - 2
                    );
                    case 4, 5, 6 -> {}
                    case 7 -> BlockPlacerBlockEntity.this.inverseRotation = value != 0;
                    case 8 -> BlockPlacerBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 9 -> BlockPlacerBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int getCount() {
                return 10;
            }
        };
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.energizedpower.block_placer");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        ModMessages.sendToPlayer(new EnergySyncS2CPacket(energyStorage.getEnergy(), energyStorage.getCapacity(),
                getBlockPos()), (ServerPlayer)player);

        return new BlockPlacerMenu(id, inventory, this, this.data);
    }

    public int getRedstoneOutput() {
        return switch(comparatorMode) {
            case ITEM -> InventoryUtils.getRedstoneSignalFromItemStackHandler(itemHandler);
            case FLUID -> 0;
            case ENERGY -> EnergyUtils.getRedstoneSignalFromEnergyStorage(energyStorage);
        };
    }

    public @Nullable IItemHandler getItemHandlerCapability(@Nullable Direction side) {
        if(side == null)
            return itemHandler;

        return itemHandlerSided;
    }

    public @Nullable IEnergyStorage getEnergyStorageCapability(@Nullable Direction side) {
        return energyStorage;
    }

    @Override
    protected void saveAdditional(CompoundTag nbt) {
        nbt.put("inventory", itemHandler.serializeNBT());
        nbt.put("energy", energyStorage.saveNBT());

        nbt.put("recipe.progress", IntTag.valueOf(progress));
        nbt.put("recipe.energy_consumption_left", IntTag.valueOf(energyConsumptionLeft));

        nbt.putBoolean("inverse_rotation", inverseRotation);

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());

        super.saveAdditional(nbt);
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        itemHandler.deserializeNBT(nbt.getCompound("inventory"));
        energyStorage.loadNBT(nbt.get("energy"));

        progress = nbt.getInt("recipe.progress");
        energyConsumptionLeft = nbt.getInt("recipe.energy_consumption_left");

        inverseRotation = nbt.getBoolean("inverse_rotation");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    public void drops(Level level, BlockPos worldPosition) {
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for(int i = 0;i < itemHandler.getSlots();i++)
            inventory.setItem(i, itemHandler.getStackInSlot(i));

        Containers.dropContents(level, worldPosition, inventory);
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, BlockPlacerBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        if(!blockEntity.redstoneMode.isActive(state.getValue(BlockPlacerBlock.POWERED)))
            return;

        if(hasRecipe(blockEntity)) {
            if(blockEntity.energyConsumptionLeft < 0)
                blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK * blockEntity.maxProgress;

            if(ENERGY_USAGE_PER_TICK <= blockEntity.energyStorage.getEnergy()) {
                blockEntity.hasEnoughEnergy = true;

                if(blockEntity.progress < 0 || blockEntity.maxProgress < 0 || blockEntity.energyConsumptionLeft < 0) {
                    //Reset progress for invalid values

                    blockEntity.resetProgress(blockPos, state);
                    setChanged(level, blockPos, state);

                    return;
                }

                blockEntity.energyStorage.setEnergy(blockEntity.energyStorage.getEnergy() - ENERGY_USAGE_PER_TICK);
                blockEntity.energyConsumptionLeft -= ENERGY_USAGE_PER_TICK;

                if(blockEntity.progress < blockEntity.maxProgress)
                    blockEntity.progress++;

                if(blockEntity.progress >= blockEntity.maxProgress) {
                    ItemStack itemStack = blockEntity.itemHandler.getStackInSlot(0);
                    if(itemStack.isEmpty()) {
                        blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK;
                        setChanged(level, blockPos, state);

                        return;
                    }

                    BlockPos blockPosPlacement = blockEntity.getBlockPos().relative(blockEntity.getBlockState().getValue(BlockPlacerBlock.FACING));

                    BlockItem blockItem = (BlockItem)itemStack.getItem();
                    final Direction direction;

                    if(blockEntity.inverseRotation) {
                        direction = switch(state.getValue(BlockPlacerBlock.FACING)) {
                            case DOWN -> Direction.UP;
                            case UP -> Direction.DOWN;
                            case NORTH -> Direction.SOUTH;
                            case SOUTH -> Direction.NORTH;
                            case WEST -> Direction.EAST;
                            case EAST -> Direction.WEST;
                        };
                    }else {
                        direction = state.getValue(BlockPlacerBlock.FACING);
                    }

                    InteractionResult result = blockItem.place(new DirectionalPlaceContext(level, blockPosPlacement, direction, itemStack, direction) {
                        @Override
                        public @NotNull Direction getNearestLookingDirection() {
                            return direction;
                        }

                        @Override
                        public @NotNull Direction @NotNull [] getNearestLookingDirections() {
                            return switch (direction) {
                                case DOWN ->
                                        new Direction[] { Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST, Direction.UP };
                                case UP ->
                                        new Direction[] { Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST };
                                case NORTH ->
                                        new Direction[] { Direction.NORTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.SOUTH };
                                case SOUTH ->
                                        new Direction[] { Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN, Direction.NORTH };
                                case WEST ->
                                        new Direction[] { Direction.WEST, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.EAST };
                                case EAST ->
                                        new Direction[] { Direction.EAST, Direction.SOUTH, Direction.UP, Direction.DOWN, Direction.NORTH, Direction.WEST };
                            };
                        }

                        @Override
                        public boolean replacingClickedOnBlock() {
                            return false;
                        }
                    });

                    if(result == InteractionResult.FAIL) {
                        blockEntity.energyConsumptionLeft = ENERGY_USAGE_PER_TICK;
                        setChanged(level, blockPos, state);

                        return;
                    }

                    blockEntity.itemHandler.setStackInSlot(0, itemStack);
                    blockEntity.resetProgress(blockPos, state);
                }

                setChanged(level, blockPos, state);
            }else {
                blockEntity.hasEnoughEnergy = false;
                setChanged(level, blockPos, state);
            }
        }else {
            blockEntity.resetProgress(blockPos, state);
            setChanged(level, blockPos, state);
        }
    }

    private void resetProgress(BlockPos blockPos, BlockState state) {
        progress = 0;
        energyConsumptionLeft = -1;
        hasEnoughEnergy = true;
    }

    private static boolean hasRecipe(BlockPlacerBlockEntity blockEntity) {
        ItemStack itemStack = blockEntity.itemHandler.getStackInSlot(0);
        if(itemStack.isEmpty())
            return false;

        if(!(itemStack.getItem() instanceof BlockItem blockItemStack))
            return false;

        return !PLACEMENT_BLACKLIST.contains(BuiltInRegistries.BLOCK.getKey(blockItemStack.getBlock()));
    }

    public void setInverseRotation(boolean inverseRotation) {
        this.inverseRotation = inverseRotation;
        setChanged(level, getBlockPos(), getBlockState());
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
    public void setNextRedstoneMode() {
        redstoneMode = RedstoneMode.fromIndex(redstoneMode.ordinal() + 1);
        setChanged();
    }

    @Override
    public void setNextComparatorMode() {
        do {
            comparatorMode = ComparatorMode.fromIndex(comparatorMode.ordinal() + 1);
        }while(comparatorMode == ComparatorMode.FLUID); //Prevent the FLUID comparator mode from being selected
        setChanged();
    }
}