package dev.aerolith;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;

public final class AssemblerItem extends Item {
    AssemblerItem(Properties properties) {
        super(properties);
    }

    @Override
    public InteractionResult onItemUseFirst(ItemStack stack, UseOnContext context) {
        // NeoForge's later useOn path starts a placement snapshot; transfer owns its own rollback.
        if (context.getLevel().isClientSide) return InteractionResult.SUCCESS;
        if (!(context.getPlayer() instanceof ServerPlayer player) || player.isSpectator()) return InteractionResult.PASS;
        ServerLevel level = player.serverLevel();
        BlockPos clicked = context.getClickedPos();
        String dimension = level.dimension().location().toString();
        if (!player.isSecondaryUseActive()) {
            CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                tag.putLong("AerolithCorner", clicked.asLong());
                tag.putString("AerolithDimension", dimension);
            });
            at(player, "corner", clicked);
        } else {
            CompoundTag selection = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
            if (!selection.contains("AerolithCorner", Tag.TAG_LONG)) {
                message(player, "no_corner");
            } else if (!dimension.equals(selection.getString("AerolithDimension"))) {
                message(player, "wrong_dimension");
            } else if (StructureTransfer.assemble(level, player, BlockPos.of(selection.getLong("AerolithCorner")), clicked) != null) {
                CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
                    tag.remove("AerolithCorner");
                    tag.remove("AerolithDimension");
                });
            }
        }
        return InteractionResult.CONSUME;
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        tooltip.add(Component.translatable("item.aerolith.assembler.tooltip"));
    }

    static void at(ServerPlayer player, String key, BlockPos pos) {
        message(player, key, pos.getX(), pos.getY(), pos.getZ());
    }

    static void message(ServerPlayer player, String key, Object... args) {
        player.displayClientMessage(Component.translatable("message.aerolith." + key, args), true);
    }
}
