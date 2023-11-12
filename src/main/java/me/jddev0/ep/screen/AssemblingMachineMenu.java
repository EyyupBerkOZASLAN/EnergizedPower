package me.jddev0.ep.screen;

import me.jddev0.ep.block.ModBlocks;
import me.jddev0.ep.block.entity.AssemblingMachineBlockEntity;
import me.jddev0.ep.inventory.ConstraintInsertSlot;
import me.jddev0.ep.recipe.AssemblingMachineRecipe;
import me.jddev0.ep.util.ByteUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.recipe.RecipeEntry;
import net.minecraft.screen.ArrayPropertyDelegate;
import net.minecraft.screen.PropertyDelegate;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.ScreenHandlerContext;
import net.minecraft.screen.slot.Slot;
import net.minecraft.world.World;

import java.util.Arrays;

public class AssemblingMachineMenu extends ScreenHandler implements EnergyStorageConsumerIndicatorBarMenu {
    private final AssemblingMachineBlockEntity blockEntity;
    private final Inventory inv;
    private final World level;
    private final PropertyDelegate data;

    public AssemblingMachineMenu(int id, PlayerInventory inv, PacketByteBuf buffer) {
        this(id, inv.player.getWorld().getBlockEntity(buffer.readBlockPos()), inv, new SimpleInventory(5) {
            @Override
            public boolean isValid(int slot, ItemStack stack) {
                return switch(slot) {
                    case 0, 1, 2, 3 -> inv.player.getWorld() == null || inv.player.getWorld().getRecipeManager().
                            listAllOfType(AssemblingMachineRecipe.Type.INSTANCE).stream().
                            map(RecipeEntry::value).map(AssemblingMachineRecipe::getInputs).anyMatch(inputs ->
                                    Arrays.stream(inputs).map(AssemblingMachineRecipe.IngredientWithCount::input).
                                            anyMatch(ingredient -> ingredient.test(stack)));
                    case 4 -> false;
                    default -> super.isValid(slot, stack);
                };
            }
        }, new ArrayPropertyDelegate(9));
    }

    public AssemblingMachineMenu(int id, BlockEntity blockEntity, PlayerInventory playerInventory, Inventory inv,
                                 PropertyDelegate data) {
        super(ModMenuTypes.ASSEMBLING_MACHINE_MENU, id);

        this.blockEntity = (AssemblingMachineBlockEntity)blockEntity;

        this.inv = inv;
        checkSize(this.inv, 5);
        checkDataCount(data, 9);
        this.level = playerInventory.player.getWorld();
        this.inv.onOpen(playerInventory.player);
        this.data = data;

        addPlayerInventory(playerInventory);
        addPlayerHotbar(playerInventory);

        addSlot(new ConstraintInsertSlot(this.inv, 0, 62, 19));
        addSlot(new ConstraintInsertSlot(this.inv, 1, 44, 37));
        addSlot(new ConstraintInsertSlot(this.inv, 2, 80, 37));
        addSlot(new ConstraintInsertSlot(this.inv, 3, 62, 55));
        addSlot(new ConstraintInsertSlot(this.inv, 4, 134, 37));

        addProperties(this.data);
    }

    @Override
    public long getEnergy() {
        return blockEntity.getEnergy();
    }

    @Override
    public long getCapacity() {
        return blockEntity.getCapacity();
    }

    @Override
    public long getEnergyIndicatorBarValue() {
        return ByteUtils.from2ByteChunks((short)data.get(4), (short)data.get(5), (short)data.get(6), (short)data.get(7));
    }

    /**
     * @return Same as isCrafting but energy requirements are ignored
     */
    public boolean isCraftingActive() {
        return ByteUtils.from2ByteChunks((short)data.get(0), (short)data.get(1)) > 0;
    }

    public boolean isCrafting() {
        return ByteUtils.from2ByteChunks((short)data.get(0), (short)data.get(1)) > 0 && data.get(8) == 1;
    }

    public int getScaledProgressArrowSize() {
        int progress = ByteUtils.from2ByteChunks((short)data.get(0), (short)data.get(1));
        int maxProgress = ByteUtils.from2ByteChunks((short)data.get(2), (short)data.get(3));
        int progressArrowSize = 24;

        return (maxProgress == 0 || progress == 0)?0:progress * progressArrowSize / maxProgress;
    }

    @Override
    public ItemStack quickMove(PlayerEntity player, int index) {
        Slot sourceSlot = slots.get(index);
        if(sourceSlot == null || !sourceSlot.hasStack())
            return ItemStack.EMPTY;

        ItemStack sourceItem = sourceSlot.getStack();
        ItemStack sourceItemCopy = sourceItem.copy();

        if(index < 4 * 9) {
            //Player inventory slot -> Merge into tile inventory
            if(!insertItem(sourceItem, 4 * 9, 4 * 9 + 4, false)) {
                //"+4" instead of "+5": Do not allow adding to output slot
                return ItemStack.EMPTY;
            }
        }else if(index < 4 * 9 + 5) {
            //Tile inventory slot -> Merge into player inventory
            if(!insertItem(sourceItem, 0, 4 * 9, false)) {
                return ItemStack.EMPTY;
            }
        }else {
            throw new IllegalArgumentException("Invalid slot index");
        }

        if(sourceItem.getCount() == 0)
            sourceSlot.setStack(ItemStack.EMPTY);
        else
            sourceSlot.markDirty();

        sourceSlot.onTakeItem(player, sourceItem);

        return sourceItemCopy;
    }

    @Override
    public boolean canUse(PlayerEntity player) {
        return canUse(ScreenHandlerContext.create(level, blockEntity.getPos()), player, ModBlocks.ASSEMBLING_MACHINE);
    }

    private void addPlayerInventory(Inventory playerInventory) {
        for(int i = 0;i < 3;i++) {
            for(int j = 0;j < 9;j++) {
                addSlot(new Slot(playerInventory, j + i * 9 + 9, 8 + j * 18, 88 + i * 18));
            }
        }
    }

    private void addPlayerHotbar(Inventory playerInventory) {
        for(int i = 0;i < 9;i++) {
            addSlot(new Slot(playerInventory, i, 8 + i * 18, 146));
        }
    }

    public BlockEntity getBlockEntity() {
        return blockEntity;
    }
}