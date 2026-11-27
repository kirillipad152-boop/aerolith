package dev.aerolith;

import java.util.Locale;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.commands.arguments.blocks.BlockStateArgument;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.core.Vec3i;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

final class PhysicsCommands {
    private PhysicsCommands() {}

    static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("aerolith")
                .then(Commands.literal("inspect").then(Commands.argument("craft", EntityArgument.entity()).executes(context -> {
                    if (!(EntityArgument.getEntity(context, "craft") instanceof CraftEntity craft) || craft.hull() == null) return unsuitable(context);
                    context.getSource().sendSuccess(() -> Component.translatable("message.aerolith.inspect",
                            craft.blocks().size(), format(craft.hull().mass), format(craft.speed()), format(craft.spin())), false);
                    return 1;
                })))
                .then(Commands.literal("assemble").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("from", BlockPosArgument.blockPos())
                                .then(Commands.argument("to", BlockPosArgument.blockPos()).executes(context -> {
                                    var source = context.getSource();
                                    // The console, command blocks and functions have no player of their own.
                                    net.minecraft.server.level.ServerPlayer player = source.getEntity() instanceof net.minecraft.server.level.ServerPlayer own
                                            ? own : net.neoforged.neoforge.common.util.FakePlayerFactory.getMinecraft(source.getLevel());
                                    return StructureTransfer.assemble(source.getLevel(), player,
                                            BlockPosArgument.getLoadedBlockPos(context, "from"),
                                            BlockPosArgument.getLoadedBlockPos(context, "to")) != null ? 1 : 0;
                                }))))
                .then(Commands.literal("spawn").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("count", IntegerArgumentType.integer(1, WorldPhysics.MAX_BODIES))
                        .then(side("x").then(side("y").then(side("z").executes(context -> spawn(context, Blocks.OAK_PLANKS.defaultBlockState()))
                        .then(Commands.argument("block", BlockStateArgument.block(event.getBuildContext()))
                                .executes(context -> spawn(context, BlockStateArgument.getBlock(context, "block").getState()))))))))
                .then(Commands.literal("perf").requires(s -> s.hasPermission(2))
                        .executes(context -> {
                            context.getSource().sendSuccess(() -> Component.literal(Perf.report("server.")), false);
                            return 1;
                        })
                        .then(Commands.literal("reset").executes(context -> {
                            if (Bench.running()) {
                                context.getSource().sendFailure(Component.literal("Cannot reset server timers during a benchmark"));
                                return 0;
                            }
                            Perf.reset("server.");
                            return 1;
                        })))
                .then(Commands.literal("bench").requires(s -> s.hasPermission(2))
                        .then(Commands.argument("crafts", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, WorldPhysics.MAX_BODIES))
                         .then(Commands.argument("side", com.mojang.brigadier.arguments.IntegerArgumentType.integer(2, Bench.MAX_SIDE))
                        .then(Commands.argument("ticks", com.mojang.brigadier.arguments.IntegerArgumentType.integer(20, 6000)).executes(context -> bench(context, false))
                        .then(Commands.literal("water").executes(context -> bench(context, true)))))))
                .then(vector(Commands.literal("wind").requires(s -> s.hasPermission(2)), 40.0, (context, value) -> {
                    WorldPhysics.of(context.getSource().getLevel()).wind(value);
                    context.getSource().sendSuccess(() -> Component.translatable("message.aerolith.wind",
                            format(value.x), format(value.y), format(value.z)), true);
                    return 1;
                })));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> vector(LiteralArgumentBuilder<CommandSourceStack> branch, double limit,
            java.util.function.ToIntBiFunction<CommandContext<CommandSourceStack>, Vec3> apply) {
        return branch.then(Commands.argument("x", DoubleArgumentType.doubleArg(-limit, limit))
                .then(Commands.argument("y", DoubleArgumentType.doubleArg(-limit, limit))
                .then(Commands.argument("z", DoubleArgumentType.doubleArg(-limit, limit)).executes(context ->
                        apply.applyAsInt(context, new Vec3(DoubleArgumentType.getDouble(context, "x"),
                                DoubleArgumentType.getDouble(context, "y"), DoubleArgumentType.getDouble(context, "z")))))));
    }

    private static RequiredArgumentBuilder<CommandSourceStack, Integer> side(String name) {
        return Commands.argument(name, IntegerArgumentType.integer(1, CraftSpawner.MAX_SIDE));
    }

    private static int spawn(CommandContext<CommandSourceStack> context, BlockState state) {
        return CraftSpawner.start(context.getSource(), IntegerArgumentType.getInteger(context, "count"),
                new Vec3i(IntegerArgumentType.getInteger(context, "x"), IntegerArgumentType.getInteger(context, "y"),
                        IntegerArgumentType.getInteger(context, "z")), state);
    }

    private static int bench(CommandContext<CommandSourceStack> context, boolean water) {
        var source = context.getSource();
        int crafts = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "crafts");
        int side = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "side");
        int ticks = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(context, "ticks");
        if (!Bench.start(source.getLevel(), source.getPosition(), crafts, side, ticks, water, (ok, report) -> {
            Aerolith.LOG.info("\n{}", report);
            if (ok) source.sendSuccess(() -> Component.literal(report), false);
            else source.sendFailure(Component.literal(report));
        })) return 0;
        source.sendSuccess(() -> Component.literal("Benchmark started: " + ticks + " measured ticks"), false);
        return 1;
    }

    private static int unsuitable(CommandContext<CommandSourceStack> context) {
        context.getSource().sendFailure(Component.translatable("message.aerolith.not_craft"));
        return 0;
    }

    private static String format(double n) {
        return String.format(Locale.ROOT, "%.1f", n);
    }
}
