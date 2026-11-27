package dev.aerolith;

import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

public final class TowStaffItem extends Item {
    static final double RANGE = 128;
    static final double MIN_DISTANCE = 2;

    TowStaffItem(Properties properties) {
        super(properties);
    }

    static ItemStack held(Player player) {
        if (player.getMainHandItem().is(Aerolith.TOW_STAFF.get())) return player.getMainHandItem();
        return player.getOffhandItem().is(Aerolith.TOW_STAFF.get()) ? player.getOffhandItem() : ItemStack.EMPTY;
    }

    static CraftEditing.Located aimed(Player player, float partial) {
        Vec3 eye = player.getEyePosition(partial);
        var aimed = CraftEditing.nearest(player.level(), eye, player.getViewVector(partial), RANGE, partial);
        if (aimed == null) return null;
        double distance = aimed.target().pick().distance();
        HitResult wall = player.pick(distance, partial, false);
        return wall.getType() == HitResult.Type.BLOCK && wall.getLocation().distanceTo(eye) < distance - 0.001 ? null : aimed;
    }

    @Override
    public boolean canDestroyBlock(ItemStack stack, BlockState state, Level level, BlockPos pos, LivingEntity user) {
        return false;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, net.minecraft.world.item.component.TooltipDisplay display, Consumer<Component> tooltip, TooltipFlag flag) {
        tooltip.accept(Component.translatable("item.aerolith.tow_staff.tooltip"));
        tooltip.accept(Component.translatable("item.aerolith.tow_staff.controls"));
        tooltip.accept(Component.translatable("item.aerolith.tow_staff.adjust", Component.keybind("key.aerolith.rotate_staff")));
    }
}
