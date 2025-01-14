package me.jddev0.ep.block.entity;

import com.mojang.datafixers.util.Pair;
import me.jddev0.ep.block.AutoCrafterBlock;
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
import me.jddev0.ep.screen.AutoCrafterMenu;
import me.jddev0.ep.util.ByteUtils;
import me.jddev0.ep.util.EnergyUtils;
import me.jddev0.ep.util.InventoryUtils;
import me.jddev0.ep.util.ItemStackUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.*;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.*;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.CraftingContainer;
import net.minecraft.world.inventory.TransientCraftingContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemStackHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

public class AutoCrafterBlockEntity extends BlockEntity implements MenuProvider, EnergyStoragePacketUpdate, RedstoneModeUpdate,
        ComparatorModeUpdate {
    private static final List<@NotNull ResourceLocation> RECIPE_BLACKLIST = ModConfigs.COMMON_AUTO_CRAFTER_RECIPE_BLACKLIST.getValue();

    private boolean secondaryExtractMode;

    private final ReceiveOnlyEnergyStorage energyStorage;
    private final ItemStackHandler itemHandler = new ItemStackHandler(18) {
        @Override
        protected void onContentsChanged(int slot) {
            setChanged();
        }

        @Override
        public boolean isItemValid(int slot, @NotNull ItemStack stack) {
            if(slot < 0 || slot >= 18)
                return super.isItemValid(slot, stack);

            //Slot 0, 1, and 2 are for output items only
            return slot >= 3;
        }
    };
    private final IItemHandler itemHandlerSided = new InputOutputItemHandler(itemHandler, (i, stack) -> i >= 3,
                    i -> secondaryExtractMode?!isInput(itemHandler.getStackInSlot(i)):isOutputOrCraftingRemainderOfInput(itemHandler.getStackInSlot(i)));

    private final SimpleContainer patternSlots = new SimpleContainer(3 * 3) {
        @Override
        public int getMaxStackSize() {
            return 1;
        }
    };
    private final SimpleContainer patternResultSlots = new SimpleContainer(1);
    private final ContainerListener updatePatternListener = container -> updateRecipe();
    private boolean hasRecipeLoaded = false;
    private ResourceLocation recipeIdForSetRecipe;
    private RecipeHolder<CraftingRecipe> craftingRecipe;
    private CraftingContainer oldCopyOfRecipe;
    private final AbstractContainerMenu dummyContainerMenu = new AbstractContainerMenu(null, -1) {
        @Override
        public ItemStack quickMoveStack(Player player, int index) {
            return null;
        }
        @Override
        public boolean stillValid(Player player) {
            return false;
        }
        @Override
        public void slotsChanged(Container container) {}
    };

    public final static int ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT =
            ModConfigs.COMMON_AUTO_CRAFTER_ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT.getValue();

    protected final ContainerData data;
    private int progress;
    private int maxProgress = ModConfigs.COMMON_AUTO_CRAFTER_RECIPE_DURATION.getValue();
    private int energyConsumptionLeft = -1;
    private boolean hasEnoughEnergy;
    private boolean ignoreNBT;

    private @NotNull RedstoneMode redstoneMode = RedstoneMode.IGNORE;
    private @NotNull ComparatorMode comparatorMode = ComparatorMode.ITEM;

    public AutoCrafterBlockEntity(BlockPos blockPos, BlockState blockState) {
        super(ModBlockEntities.AUTO_CRAFTER_ENTITY.get(), blockPos, blockState);

        patternSlots.addListener(updatePatternListener);

        energyStorage = new ReceiveOnlyEnergyStorage(0, ModConfigs.COMMON_AUTO_CRAFTER_CAPACITY.getValue(),
                ModConfigs.COMMON_AUTO_CRAFTER_TRANSFER_RATE.getValue()) {
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
                    case 0, 1 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.progress, index);
                    case 2, 3 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.maxProgress, index - 2);
                    case 4, 5 -> ByteUtils.get2Bytes(AutoCrafterBlockEntity.this.energyConsumptionLeft, index - 4);
                    case 6 -> hasEnoughEnergy?1:0;
                    case 7 -> ignoreNBT?1:0;
                    case 8 -> secondaryExtractMode?1:0;
                    case 9 -> redstoneMode.ordinal();
                    case 10 -> comparatorMode.ordinal();
                    default -> 0;
                };
            }

            @Override
            public void set(int index, int value) {
                switch(index) {
                    case 0, 1 -> AutoCrafterBlockEntity.this.progress = ByteUtils.with2Bytes(
                            AutoCrafterBlockEntity.this.progress, (short)value, index
                    );
                    case 2, 3 -> AutoCrafterBlockEntity.this.maxProgress = ByteUtils.with2Bytes(
                            AutoCrafterBlockEntity.this.maxProgress, (short)value, index - 2
                    );
                    case 4, 5, 6 -> {}
                    case 7 -> AutoCrafterBlockEntity.this.ignoreNBT = value != 0;
                    case 8 -> AutoCrafterBlockEntity.this.secondaryExtractMode = value != 0;
                    case 9 -> AutoCrafterBlockEntity.this.redstoneMode = RedstoneMode.fromIndex(value);
                    case 10 -> AutoCrafterBlockEntity.this.comparatorMode = ComparatorMode.fromIndex(value);
                }
            }

            @Override
            public int getCount() {
                return 11;
            }
        };
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("container.energizedpower.auto_crafter");
    }

    @Nullable
    @Override
    public AbstractContainerMenu createMenu(int id, Inventory inventory, Player player) {
        ModMessages.sendToPlayer(new EnergySyncS2CPacket(energyStorage.getEnergy(), energyStorage.getCapacity(),
                getBlockPos()), (ServerPlayer)player);

        return new AutoCrafterMenu(id, inventory, this, patternSlots, patternResultSlots, data);
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
        nbt.put("pattern", savePatternContainer());
        nbt.put("energy", energyStorage.saveNBT());

        if(craftingRecipe != null)
            nbt.put("recipe.id", StringTag.valueOf(craftingRecipe.id().toString()));

        nbt.put("recipe.progress", IntTag.valueOf(progress));
        nbt.put("recipe.energy_consumption_left", IntTag.valueOf(energyConsumptionLeft));

        nbt.putBoolean("ignore_nbt", ignoreNBT);
        nbt.putBoolean("secondary_extract_mode", secondaryExtractMode);

        nbt.putInt("configuration.redstone_mode", redstoneMode.ordinal());
        nbt.putInt("configuration.comparator_mode", comparatorMode.ordinal());

        super.saveAdditional(nbt);
    }

    private Tag savePatternContainer() {
        ListTag nbtTagList = new ListTag();
        for(int i = 0;i < patternSlots.getContainerSize();i++)  {
            if(!patternSlots.getItem(i).isEmpty()) {
                CompoundTag itemTag = new CompoundTag();
                itemTag.putInt("Slot", i);
                patternSlots.getItem(i).save(itemTag);
                nbtTagList.add(itemTag);
            }
        }

        return nbtTagList;
    }

    @Override
    public void load(@NotNull CompoundTag nbt) {
        super.load(nbt);

        itemHandler.deserializeNBT(nbt.getCompound("inventory"));
        loadPatternContainer(nbt.get("pattern"));
        energyStorage.loadNBT(nbt.get("energy"));

        if(nbt.contains("recipe.id")) {
            Tag tag = nbt.get("recipe.id");

            if(!(tag instanceof StringTag stringTag))
                throw new IllegalArgumentException("Tag must be of type StringTag!");

            recipeIdForSetRecipe = ResourceLocation.tryParse(stringTag.getAsString());
        }

        progress = nbt.getInt("recipe.progress");
        energyConsumptionLeft = nbt.getInt("recipe.energy_consumption_left");

        ignoreNBT = nbt.getBoolean("ignore_nbt");
        secondaryExtractMode = nbt.getBoolean("secondary_extract_mode");

        redstoneMode = RedstoneMode.fromIndex(nbt.getInt("configuration.redstone_mode"));
        comparatorMode = ComparatorMode.fromIndex(nbt.getInt("configuration.comparator_mode"));
    }

    private void loadPatternContainer(Tag tag) {
        if(!(tag instanceof ListTag))
            throw new IllegalArgumentException("Tag must be of type ListTag!");

        patternSlots.removeListener(updatePatternListener);
        ListTag tagList = (ListTag)tag;
        for(int i = 0;i < tagList.size();i++) {
            CompoundTag itemTags = tagList.getCompound(i);
            int slot = itemTags.getInt("Slot");

            if(slot >= 0 && slot < patternSlots.getContainerSize()) {
                patternSlots.setItem(slot, ItemStack.of(itemTags));
            }
        }
        patternSlots.addListener(updatePatternListener);
    }

    public void drops(Level level, BlockPos worldPosition) {
        SimpleContainer inventory = new SimpleContainer(itemHandler.getSlots());
        for(int i = 0;i < itemHandler.getSlots();i++)
            inventory.setItem(i, itemHandler.getStackInSlot(i));

        Containers.dropContents(level, worldPosition, inventory);
    }

    public static void tick(Level level, BlockPos blockPos, BlockState state, AutoCrafterBlockEntity blockEntity) {
        if(level.isClientSide)
            return;

        if(!blockEntity.hasRecipeLoaded) {
            blockEntity.updateRecipe();

            if(blockEntity.craftingRecipe == null)
                blockEntity.resetProgress();
        }

        if(!blockEntity.redstoneMode.isActive(state.getValue(AutoCrafterBlock.POWERED)))
            return;

        int itemCount = 0;
        for(int i = 0;i < blockEntity.patternSlots.getContainerSize();i++)
            if(!blockEntity.patternSlots.getItem(i).isEmpty())
                itemCount++;

        //Ignore empty recipes
        if(itemCount == 0)
            return;

        if(blockEntity.craftingRecipe != null && (blockEntity.progress > 0 || (blockEntity.canInsertItemsIntoOutputSlots() && blockEntity.canExtractItemsFromInput()))) {
            if(!blockEntity.canInsertItemsIntoOutputSlots() || !blockEntity.canExtractItemsFromInput())
                return;

            int energyConsumptionPerTick = itemCount * ENERGY_CONSUMPTION_PER_TICK_PER_INGREDIENT;

            if(blockEntity.progress == 0) {
                if(!blockEntity.canExtractItemsFromInput())
                    return;

                blockEntity.energyConsumptionLeft = energyConsumptionPerTick * blockEntity.maxProgress;
            }

            if(blockEntity.progress < 0 || blockEntity.maxProgress < 0 || blockEntity.energyConsumptionLeft < 0 ||
                    energyConsumptionPerTick < 0) {
                //Reset progress for invalid values

                blockEntity.resetProgress();
                setChanged(level, blockPos, state);

                return;
            }

            if(energyConsumptionPerTick <= blockEntity.energyStorage.getEnergy()) {
                blockEntity.energyStorage.setEnergy(blockEntity.energyStorage.getEnergy() - energyConsumptionPerTick);
                blockEntity.energyConsumptionLeft -= energyConsumptionPerTick;

                blockEntity.progress++;

                if(blockEntity.progress >= blockEntity.maxProgress) {
                    SimpleContainer patternSlotsForRecipe = blockEntity.ignoreNBT?
                            blockEntity.replaceCraftingPatternWithCurrentNBTItems(blockEntity.patternSlots):blockEntity.patternSlots;
                    CraftingContainer copyOfPatternSlots = new TransientCraftingContainer(blockEntity.dummyContainerMenu, 3, 3);
                    for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
                        copyOfPatternSlots.setItem(i, patternSlotsForRecipe.getItem(i));

                    blockEntity.extractItems();
                    blockEntity.craftItem(copyOfPatternSlots);
                }

                setChanged(level, blockPos, state);
            }else {
                blockEntity.hasEnoughEnergy = false;
                setChanged(level, blockPos, state);
            }
        }else {
            blockEntity.resetProgress();
            setChanged(level, blockPos, state);
        }
    }

    private void resetProgress() {
        progress = 0;
        energyConsumptionLeft = -1;
        hasEnoughEnergy = true;
    }

    public void resetProgressAndMarkAsChanged() {
        resetProgress();
        setChanged(level, getBlockPos(), getBlockState());
    }

    public void cycleRecipe() {
        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingContainer copyOfPatternSlots = new TransientCraftingContainer(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            copyOfPatternSlots.setItem(i, patternSlotsForRecipe.getItem(i));

        List<RecipeHolder<CraftingRecipe>> recipes = getRecipesFor(copyOfPatternSlots, level);

        //No recipe found
        if(recipes.isEmpty()) {
            updateRecipe();

            return;
        }

        if(recipeIdForSetRecipe == null)
            recipeIdForSetRecipe = (craftingRecipe == null || craftingRecipe.id() == null)?recipes.get(0).id():
                    craftingRecipe.id();

        for(int i = 0;i < recipes.size();i++) {
            if(Objects.equals(recipes.get(i).id(), recipeIdForSetRecipe)) {
                recipeIdForSetRecipe = recipes.get((i + 1) % recipes.size()).id();

                break;
            }
        }

        updateRecipe();
    }

    public void setRecipeIdForSetRecipe(ResourceLocation recipeIdForSetRecipe) {
        this.recipeIdForSetRecipe = recipeIdForSetRecipe;

        updateRecipe();
    }

    private void updateRecipe() {
        if(level == null)
            return;

        RecipeHolder<CraftingRecipe> oldRecipe = null;
        ItemStack oldResult = null;
        if(hasRecipeLoaded && craftingRecipe != null && oldCopyOfRecipe != null) {
            oldRecipe = craftingRecipe;

            oldResult = craftingRecipe.value() instanceof CustomRecipe?craftingRecipe.value().assemble(oldCopyOfRecipe, level.registryAccess()):
                    craftingRecipe.value().getResultItem(level.registryAccess());
        }

        hasRecipeLoaded = true;

        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingContainer copyOfPatternSlots = new TransientCraftingContainer(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            copyOfPatternSlots.setItem(i, patternSlotsForRecipe.getItem(i));

        Optional<Pair<ResourceLocation, RecipeHolder<CraftingRecipe>>> recipe = getRecipeFor(copyOfPatternSlots, level, recipeIdForSetRecipe);
        if(recipe.isPresent()) {
            craftingRecipe = recipe.get().getSecond();

            //Recipe with saved recipe id does not exist or pattern items are not compatible with recipe
            if(recipeIdForSetRecipe != null && !Objects.equals(craftingRecipe.id(), recipeIdForSetRecipe)) {
                recipeIdForSetRecipe = craftingRecipe.id();
                resetProgress();
            }

            ItemStack resultItemStack = craftingRecipe.value() instanceof CustomRecipe?craftingRecipe.value().assemble(copyOfPatternSlots, level.registryAccess()):
                    craftingRecipe.value().getResultItem(level.registryAccess());

            patternResultSlots.setItem(0, resultItemStack);

            if(oldRecipe != null && oldResult != null && oldCopyOfRecipe != null && (craftingRecipe != oldRecipe || !ItemStack.isSameItemSameTags(resultItemStack, oldResult)))
                resetProgress();

            oldCopyOfRecipe = new TransientCraftingContainer(dummyContainerMenu, 3, 3);
            for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
                oldCopyOfRecipe.setItem(i, copyOfPatternSlots.getItem(i).copy());
        }else {
            recipeIdForSetRecipe = null;

            craftingRecipe = null;

            patternResultSlots.setItem(0, ItemStack.EMPTY);

            oldCopyOfRecipe = null;

            resetProgress();
        }
    }

    private void extractItems() {
        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        List<ItemStack> patternItemStacks = new ArrayList<>(9);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            if(!patternSlotsForRecipe.getItem(i).isEmpty())
                patternItemStacks.add(patternSlotsForRecipe.getItem(i));

        List<ItemStack> itemStacksExtract = ItemStackUtils.combineItemStacks(patternItemStacks);

        for(ItemStack itemStack:itemStacksExtract) {
            for(int i = 0;i < itemHandler.getSlots();i++) {
                ItemStack testItemStack = itemHandler.getStackInSlot(i);
                if(ItemStack.isSameItemSameTags(itemStack, testItemStack)) {
                    ItemStack ret = itemHandler.extractItem(i, itemStack.getCount(), false);
                    if(!ret.isEmpty()) {
                        int amount = ret.getCount();
                        if(amount == itemStack.getCount())
                            break;

                        itemStack.shrink(amount);
                    }
                }
            }
        }
    }

    private void craftItem(CraftingContainer copyOfPatternSlots) {
        if(craftingRecipe == null) {
            resetProgress();

            return;
        }

        List<ItemStack> outputItemStacks = new ArrayList<>(10);

        ItemStack resultItemStack = craftingRecipe.value() instanceof CustomRecipe?craftingRecipe.value().assemble(copyOfPatternSlots, level.registryAccess()):
                craftingRecipe.value().getResultItem(level.registryAccess());

        outputItemStacks.add(resultItemStack);

        for(ItemStack remainingItem:craftingRecipe.value().getRemainingItems(copyOfPatternSlots))
            if(!remainingItem.isEmpty())
                outputItemStacks.add(remainingItem);

        List<ItemStack> itemStacksInsert = ItemStackUtils.combineItemStacks(outputItemStacks);

        List<Integer> emptyIndices = new ArrayList<>(18);
        outer:
        for(ItemStack itemStack:itemStacksInsert) {
            for(int i = 0;i < itemHandler.getSlots();i++) {
                ItemStack testItemStack = itemHandler.getStackInSlot(i);
                if(emptyIndices.contains(i))
                    continue;

                if(testItemStack.isEmpty()) {
                    emptyIndices.add(i);

                    continue;
                }

                if(ItemStack.isSameItemSameTags(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getMaxStackSize() - testItemStack.getCount());
                    if(amount > 0) {
                        itemHandler.setStackInSlot(i, itemHandler.getStackInSlot(i).copyWithCount(testItemStack.getCount() + amount));

                        itemStack.setCount(itemStack.getCount() - amount);

                        if(itemStack.isEmpty())
                            continue outer;
                    }
                }
            }

            //Leftover -> put in empty slot
            if(emptyIndices.isEmpty())
                continue; //Should not happen

            itemHandler.setStackInSlot(emptyIndices.remove(0), itemStack);
        }

        if(ignoreNBT)
            updateRecipe();

        resetProgress();
    }

    private boolean canExtractItemsFromInput() {
        if(craftingRecipe == null)
            return false;

        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        List<ItemStack> patternItemStacks = new ArrayList<>(9);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            if(!patternSlotsForRecipe.getItem(i).isEmpty())
                patternItemStacks.add(patternSlotsForRecipe.getItem(i));

        List<ItemStack> itemStacks = ItemStackUtils.combineItemStacks(patternItemStacks);

        List<Integer> checkedIndices = new ArrayList<>(18);
        outer:
        for(int i = itemStacks.size() - 1;i >= 0;i--) {
            ItemStack itemStack = itemStacks.get(i);

            for(int j = 0;j < itemHandler.getSlots();j++) {
                if(checkedIndices.contains(j))
                    continue;

                ItemStack testItemStack = itemHandler.getStackInSlot(j);
                if(testItemStack.isEmpty()) {
                    checkedIndices.add(j);

                    continue;
                }

                if(ItemStack.isSameItemSameTags(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getCount());
                    checkedIndices.add(j);

                    if(amount == itemStack.getCount()) {
                        itemStacks.remove(i);

                        continue outer;
                    }else {
                        itemStack.shrink(amount);
                    }
                }
            }

            return false;
        }

        return itemStacks.isEmpty();
    }

    private boolean canInsertItemsIntoOutputSlots() {
        if(craftingRecipe == null)
            return false;

        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingContainer copyOfPatternSlots = new TransientCraftingContainer(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            copyOfPatternSlots.setItem(i, patternSlotsForRecipe.getItem(i));

        List<ItemStack> outputItemStacks = new ArrayList<>(10);
        ItemStack resultItemStack = craftingRecipe.value() instanceof CustomRecipe?craftingRecipe.value().assemble(copyOfPatternSlots, level.registryAccess()):
                craftingRecipe.value().getResultItem(level.registryAccess());

        if(!resultItemStack.isEmpty())
            outputItemStacks.add(resultItemStack);

        for(ItemStack remainingItem:craftingRecipe.value().getRemainingItems(copyOfPatternSlots))
            if(!remainingItem.isEmpty())
                outputItemStacks.add(remainingItem);

        List<ItemStack> itemStacks = ItemStackUtils.combineItemStacks(outputItemStacks);

        List<Integer> checkedIndices = new ArrayList<>(18);
        List<Integer> emptyIndices = new ArrayList<>(18);
        outer:
        for(int i = itemStacks.size() - 1;i >= 0;i--) {
            ItemStack itemStack = itemStacks.get(i);
            for(int j = 0;j < itemHandler.getSlots();j++) {
                if(checkedIndices.contains(j) || emptyIndices.contains(j))
                    continue;

                ItemStack testItemStack = itemHandler.getStackInSlot(j);
                if(testItemStack.isEmpty()) {
                    emptyIndices.add(j);

                    continue;
                }

                if(ItemStack.isSameItemSameTags(itemStack, testItemStack)) {
                    int amount = Math.min(itemStack.getCount(), testItemStack.getMaxStackSize() - testItemStack.getCount());

                    if(amount + testItemStack.getCount() == testItemStack.getMaxStackSize())
                        checkedIndices.add(j);

                    if(amount == itemStack.getCount()) {
                        itemStacks.remove(i);

                        continue outer;
                    }else {
                        itemStack.shrink(amount);
                    }
                }
            }

            //Leftover -> put in empty slot
            if(emptyIndices.isEmpty())
                return false;

            int index = emptyIndices.remove(0);
            if(itemStack.getCount() == itemStack.getMaxStackSize())
                checkedIndices.add(index);

            itemStacks.remove(i);
        }

        return itemStacks.isEmpty();
    }

    private boolean isOutputOrCraftingRemainderOfInput(ItemStack itemStack) {
        if(craftingRecipe == null)
            return false;

        SimpleContainer patternSlotsForRecipe = ignoreNBT?replaceCraftingPatternWithCurrentNBTItems(patternSlots):patternSlots;
        CraftingContainer copyOfPatternSlots = new TransientCraftingContainer(dummyContainerMenu, 3, 3);
        for(int i = 0;i < patternSlotsForRecipe.getContainerSize();i++)
            copyOfPatternSlots.setItem(i, patternSlotsForRecipe.getItem(i));

        ItemStack resultItemStack = craftingRecipe.value() instanceof CustomRecipe?craftingRecipe.value().assemble(copyOfPatternSlots, level.registryAccess()):
                craftingRecipe.value().getResultItem(level.registryAccess());

        if(ItemStack.isSameItemSameTags(itemStack, resultItemStack))
            return true;

        for(ItemStack remainingItem:craftingRecipe.value().getRemainingItems(copyOfPatternSlots))
            if(ItemStack.isSameItemSameTags(itemStack, remainingItem))
                return true;

        return false;
    }


    private boolean isInput(ItemStack itemStack) {
        if(craftingRecipe == null)
            return false;

        for(int i = 0;i < patternSlots.getContainerSize();i++)
            if(ignoreNBT?ItemStack.isSameItem(itemStack, patternSlots.getItem(i)):
                    ItemStack.isSameItemSameTags(itemStack, patternSlots.getItem(i)))
                return true;

        return false;
    }

    private SimpleContainer replaceCraftingPatternWithCurrentNBTItems(SimpleContainer container) {
        SimpleContainer copyOfContainer = new SimpleContainer(container.getContainerSize());
        for(int i = 0;i < container.getContainerSize();i++)
            copyOfContainer.setItem(i, container.getItem(i).copy());

        Map<Integer, Integer> usedItemCounts = new HashMap<>(); //slotIndex: usedCount
        outer:
        for(int i = 0;i < copyOfContainer.getContainerSize();i++) {
            ItemStack itemStack = copyOfContainer.getItem(i);
            if(itemStack.isEmpty())
                continue;

            for(int j = 0;j < itemHandler.getSlots();j++) {
                ItemStack testItemStack = itemHandler.getStackInSlot(j).copy();
                int usedCount = usedItemCounts.getOrDefault(j, 0);
                testItemStack.setCount(testItemStack.getCount() - usedCount);
                if(testItemStack.getCount() <= 0)
                    continue;

                if(ItemStack.isSameItemSameTags(itemStack, testItemStack)) {
                    usedItemCounts.put(j, usedCount + 1);
                    continue outer;
                }
            }

            //Same item with same tag was not found -> check for same item without same tag and change if found
            for(int j = 0;j < itemHandler.getSlots();j++) {
                ItemStack testItemStack = itemHandler.getStackInSlot(j).copy();
                int usedCount = usedItemCounts.getOrDefault(j, 0);
                testItemStack.setCount(testItemStack.getCount() - usedCount);
                if(testItemStack.getCount() <= 0)
                    continue;

                if(ItemStack.isSameItem(itemStack, testItemStack)) {
                    usedItemCounts.put(j, usedCount + 1);

                    copyOfContainer.setItem(i, testItemStack.copyWithCount(1));

                    continue outer;
                }
            }

            //Not found at all -> Mot enough input items are present
            return copyOfContainer;
        }

        return copyOfContainer;
    }

    private List<RecipeHolder<CraftingRecipe>> getRecipesFor(CraftingContainer patternSlots, Level level) {
        return level.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING).
                stream().filter(recipe -> !RECIPE_BLACKLIST.contains(recipe.id())).
                filter(recipe -> recipe.value().matches(patternSlots, level)).
                sorted(Comparator.comparing(recipe -> recipe.value().getResultItem(level.registryAccess()).getDescriptionId())).
                toList();
    }

    private Optional<Pair<ResourceLocation, RecipeHolder<CraftingRecipe>>> getRecipeFor(CraftingContainer patternSlots, Level level, ResourceLocation recipeId) {
        List<RecipeHolder<CraftingRecipe>> recipes = getRecipesFor(patternSlots, level);
        Optional<RecipeHolder<CraftingRecipe>> recipe = recipes.stream().filter(r -> r.id().equals(recipeId)).findFirst();

        return recipe.or(() -> recipes.stream().findFirst()).map(r -> Pair.of(r.id(), r));
    }

    public void setIgnoreNBT(boolean ignoreNBT) {
        this.ignoreNBT = ignoreNBT;
        updateRecipe();
        setChanged(level, getBlockPos(), getBlockState());
    }


    public void setSecondaryExtractMode(boolean secondaryExtractMode) {
        this.secondaryExtractMode = secondaryExtractMode;
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