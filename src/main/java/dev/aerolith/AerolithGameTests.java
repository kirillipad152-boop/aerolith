package dev.aerolith;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.RotatedPillarBlock;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.FurnaceBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.util.FakePlayer;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(Aerolith.ID)
@PrefixGameTestTemplate(false)
public final class AerolithGameTests {
    @GameTest(template = "test_pad", timeoutTicks = 120)
    public static void assembleLandSaveAndDock(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int z = 1; z <= 8; z++) {
            for (int x = 1; x <= 8; x++) test.setBlock(x, 1, z, Blocks.SMOOTH_STONE_SLAB);
        }
        BlockPos low = new BlockPos(4, 6, 4);
        BlockPos high = low.above();
        BlockState log = Blocks.OAK_LOG.defaultBlockState().setValue(RotatedPillarBlock.AXIS, Direction.Axis.X);
        test.setBlock(low, log);
        test.setBlock(high, Blocks.RED_WOOL);
        CraftEntity craft = assembleWithTool(test, player, low, high);
        test.assertBlockPresent(Blocks.AIR, low);
        test.assertBlockPresent(Blocks.AIR, high);
        double floorTop = test.absolutePos(new BlockPos(4, 1, 4)).getY() + 0.5;

        test.startSequence().thenWaitUntil(() -> {
            test.assertTrue(Math.abs(craft.getY() - (floorTop + 0.5)) < 0.08, "Hull did not rest on the half-height slab");
            test.assertTrue(craft.speed() < 0.25 && craft.spin() < 0.3, "Hull did not settle");
        }).thenExecute(() -> {
            CompoundTag saved = craft.saveWithoutId(new CompoundTag());
            CraftEntity restored = Aerolith.CRAFT.get().create(level);
            test.assertTrue(restored != null, "Cannot recreate a hull");
            restored.load(saved);
            test.assertTrue(java.util.Set.copyOf(restored.blocks()).equals(java.util.Set.copyOf(craft.blocks())) && restored.canEdit(player)
                    && restored.position().distanceToSqr(craft.position()) < 0.000001, "Hull state was not saved losslessly");

            BlockPos dock = StructureTransfer.dockingAnchor(craft);
            level.setBlockAndUpdate(dock.above(), Blocks.DIAMOND_BLOCK.defaultBlockState());
            test.assertTrue(!StructureTransfer.dock(level, player, craft) && !craft.isRemoved(), "Dock wrote through an obstruction");
            test.assertTrue(level.getBlockState(dock.above()).is(Blocks.DIAMOND_BLOCK), "An existing block was overwritten");
            level.setBlockAndUpdate(dock.above(), Blocks.AIR.defaultBlockState());
            test.assertTrue(StructureTransfer.dock(level, player, craft) && craft.isRemoved(),
                    "Stopped hull did not dock at " + dock + " with reference y=" + craft.getY()
                            + ", speed=" + craft.speed() + ", spin=" + craft.spin());
            test.assertTrue(level.getBlockState(dock) == log && level.getBlockState(dock.above()).is(Blocks.RED_WOOL),
                    "Docking did not preserve exact blocks and state");
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void storageKeepsItsItemsThroughAssemblingAndDocking(GameTestHelper test) {
        test.assertTrue(test.getLevel().getRecipeManager().byKey(ResourceLocation.fromNamespaceAndPath(Aerolith.ID, "assembler")).isPresent(),
                "The survival crafting recipe is missing");
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        BlockPos p = new BlockPos(4, 4, 4);
        test.setBlock(p, Blocks.OAK_PLANKS);
        test.setBlock(p.above(), Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(test.absolutePos(p.above()));
        chest.setItem(3, new ItemStack(Items.DIAMOND, 5));
        CraftEntity craft = assembleWithTool(test, player, p, p.above());
        test.assertBlockPresent(Blocks.AIR, p.above());
        var plot = craft.plotLevel();
        var inPlot = plot.getBlockEntity(craft.plotPos(BlockPos.ZERO.above()));
        test.assertTrue(inPlot instanceof net.minecraft.world.level.block.entity.ChestBlockEntity stored && stored.getItem(3).is(Items.DIAMOND)
                && stored.getItem(3).getCount() == 5, "The chest did not keep its items in the plot");
        test.assertTrue(level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, new net.minecraft.world.phys.AABB(test.absolutePos(p)).inflate(6)).isEmpty(),
                "Assembling a chest must not spill what is in it");
        test.assertTrue(StructureTransfer.dock(level, player, craft), "The craft with a chest did not dock");
        var restored = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(craft.blockPosition().above());
        BlockPos dockAt = StructureTransfer.dockingAnchor(craft);
        var back = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(dockAt.above());
        test.assertTrue(back != null && back.getItem(3).is(Items.DIAMOND) && back.getItem(3).getCount() == 5,
                "The chest did not get its items back when the craft docked");
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void partialHullVolumeGeometryAndMaterials(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        BlockPos slabPos = new BlockPos(4, 4, 4);
        BlockState bottom = Blocks.OAK_SLAB.defaultBlockState().setValue(SlabBlock.TYPE, SlabType.BOTTOM);
        BlockState top = bottom.setValue(SlabBlock.TYPE, SlabType.TOP);
        var pair = new Hull(List.of(new CraftBlock(BlockPos.ZERO, bottom), new CraftBlock(BlockPos.ZERO.east(), bottom)));
        var split = new Hull(List.of(new CraftBlock(BlockPos.ZERO, bottom), new CraftBlock(BlockPos.ZERO.east(), top)));
        test.assertTrue(Math.abs(surfaceArea(pair) - 7.0) < 1e-7 && Math.abs(surfaceArea(split) - 8.0) < 1e-7,
                "Only physically covered faces should be removed from aerodynamic area");
        BlockState stair = Blocks.OAK_STAIRS.defaultBlockState();
        test.setBlock(slabPos, bottom);
        test.setBlock(slabPos.east(), stair);
        CraftEntity craft = assembleWithTool(test, player, slabPos, slabPos.east());
        test.assertTrue(Math.abs(craft.hull().mass - 600.0) < 1e-7 && craft.collisionBoxes().size() == 3,
                "A half slab and a 3/4 stair must use their real volume and boxes");
        test.assertTrue(craft.hull().localCom.x > 0.5 && craft.hull().localCom.y < -0.1,
                "Half-height shapes must affect COM");
        var coefficients = new FrictionConfig(List.of("minecraft:oak_slab=0.1", " minecraft:oak_slab = 0.27 ",
                "minecraft:oak_stairs=0.4", "minecraft:oak_stairs=NaN"));
        var packed = craft.hull().colliders(coefficients);
        test.assertTrue(packed.boxes().length == 2 * Hull.COLLIDER
                && java.util.Arrays.equals(packed.frictionCells(), new double[] {0.27, 0.4}), "The merged slab/stair box lost its per-block friction");
        test.assertTrue(StructureTransfer.dock(level, player, craft), "Partial hull failed to dock");
        test.assertTrue(level.getBlockState(test.absolutePos(slabPos)) == bottom
                && level.getBlockState(test.absolutePos(slabPos.east())) == stair, "Partial block state was not retained");
        test.succeed();
    }

    private static double surfaceArea(Hull hull) {
        double sum = 0.0;
        for (int i = 0; i < hull.surfaces.length; i += 8) if (hull.surfaces[i] == 0) sum += hull.surfaces[i + 7];
        return sum;
    }

    @GameTest(template = "test_pad", timeoutTicks = 160)
    public static void itemRestsOnSolidDeck(GameTestHelper test) {
        CraftEntity craft = plankSquare(test, false);
        var item = test.spawnItem(Items.STICK, 5.5f, 6f, 5.5f);
        test.startSequence().thenWaitUntil(() ->
                test.assertTrue(Math.abs(item.getY() - (craft.getY() + 0.5)) < 0.1 && item.getDeltaMovement().lengthSqr() < 0.01,
                        "Dropped item did not stop on the hull deck, y=" + item.getY() + " deck=" + (craft.getY() + 0.5))
        ).thenExecute(() -> { item.discard(); craft.discard(); }).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 160)
    public static void itemFallsThroughHullGap(GameTestHelper test) {
        CraftEntity craft = plankSquare(test, true);
        var item = test.spawnItem(Items.STICK, 5.5f, 6f, 5.5f);
        double floorTop = craft.getY() - 0.5;
        test.startSequence().thenWaitUntil(() ->
                test.assertTrue(Math.abs(item.getY() - floorTop) < 0.1,
                        "Item was blocked by empty space in the hull, y=" + item.getY() + " floor=" + floorTop)
        ).thenExecute(() -> { item.discard(); craft.discard(); }).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 60)
    public static void brokenMiddleSplitsCraftIntoTwoBodiesWithSameMomentum(GameTestHelper test) {
        floor(test);
        for (int x = 3; x <= 7; x++) test.setBlock(x, 2, 5, Blocks.OAK_PLANKS);
        CraftEntity craft = assembleWithTool(test, editor(test.getLevel()), new BlockPos(3, 2, 5), new BlockPos(7, 2, 5));
        var middle = craft.blocks().stream().filter(b -> b.offset().equals(BlockPos.ZERO)).findFirst().orElseThrow();
        CraftEditing.remove(craft, List.of(middle));
        var parts = test.getLevel().getEntities(Aerolith.CRAFT.get(), craft.getBoundingBox().inflate(8), c -> true);
        test.assertTrue(parts.size() == 2, "Removing the middle block must leave two crafts, got " + parts.size());
        test.assertTrue(parts.stream().mapToInt(c -> c.blocks().size()).sum() == 4, "No block may be lost or duplicated");
        test.assertTrue(parts.stream().allMatch(c -> Hull.connected(c.blocks())), "Each piece must be connected");
        parts.forEach(CraftEntity::discard);
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 60)
    public static void playerPlacesAndBreaksBlocksWithTheCrosshair(GameTestHelper test) {
        floor(test);
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        ServerPlayer player = editor(test.getLevel());
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(5, 2, 5));
        Vec3 above = craft.position().add(0, 2.5, 0);
        player.setPos(above.x, above.y - player.getEyeHeight(), above.z);
        player.setXRot(90);
        ItemStack stack = new ItemStack(Items.STONE_BRICKS, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(craft.blocks().size() == 2 && stack.getCount() == 2, "A block item right-clicked on the top face must be placed above");
        test.assertTrue(craft.blocks().stream().anyMatch(b -> b.offset().equals(BlockPos.ZERO.above()) && b.state().is(Blocks.STONE_BRICKS)),
                "The new block must sit in the cell above the hit block");

        player.setPos(player.getX(), player.getY() + 1, player.getZ());
        craft.skipAttackInteraction(player);
        test.assertTrue(craft.blocks().size() == 2, "In survival one punch must not break a block at once");
        test.assertTrue(craft.pendingPushes() > 0, "A punch must push the craft");
        for (int i = 0; i < 4000 && craft.blocks().size() == 2; i++) craft.skipAttackInteraction(player);
        test.assertTrue(craft.blocks().size() == 1 && craft.blocks().getFirst().state().is(Blocks.OAK_PLANKS),
                "Hitting for long enough must remove the nearest block along the ray");
        var items = test.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, craft.getBoundingBox().inflate(3));
        test.assertTrue(items.isEmpty(), "Stone bricks mined by hand must not drop, as in vanilla");
        for (int i = 0; i < 4000 && !craft.isRemoved(); i++) craft.skipAttackInteraction(player);
        test.assertTrue(craft.isRemoved(), "Breaking the last block must remove the craft");
        items = test.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, craft.getBoundingBox().inflate(3));
        test.assertTrue(!items.isEmpty(), "Planks mined by hand must drop their item in survival");
        items.forEach(net.minecraft.world.entity.Entity::discard);
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 200)
    public static void fragmentsOfASpinningFallingCraftStayAboveTheFloor(GameTestHelper test) {
        // Spinning pieces fly several blocks sideways, so the floor is wide; the test area is capped by barriers at 12.
        for (int x = -15; x <= 25; x++) {
            for (int z = -15; z <= 25; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
        for (int x = 3; x <= 7; x++) test.setBlock(x, 10, 5, Blocks.WHITE_WOOL);
        ServerPlayer owner = editor(test.getLevel());
        CraftEntity craft = assembleWithTool(test, owner, new BlockPos(3, 10, 5), new BlockPos(7, 10, 5));
        WorldPhysics physics = WorldPhysics.of(test.getLevel());
        physics.impulseAt(craft, new Vec3(0, 0, 400), craft.position().add(2, 0, 0));
        double floorTop = test.absolutePos(new BlockPos(0, 2, 0)).getY();
        test.runAfterDelay(6, () -> {
            var middle = craft.blocks().stream().filter(b -> b.offset().equals(BlockPos.ZERO)).findFirst().orElseThrow();
            CraftEditing.remove(craft, List.of(middle));
        });
        test.startSequence().thenIdle(120).thenExecute(() -> {
            // Only this test's pieces: other tests leave crafts lying on the ground below the test floor.
            var parts = test.getLevel().getEntities(Aerolith.CRAFT.get(), craft.getBoundingBox().inflate(40),
                    c -> owner.getUUID().equals(c.ownerId()));
            test.assertTrue(parts.size() == 2, "Expected two pieces, got " + parts.size());
            for (CraftEntity part : parts) {
                test.assertTrue(part.getBoundingBox().minY > floorTop - 0.1,
                        "A fallen piece sank into the floor: minY=" + part.getBoundingBox().minY + " floor=" + floorTop);
            }
            parts.forEach(CraftEntity::discard);
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void uniformBlocksMergeIntoFewCollidersWithTheSameMass(GameTestHelper test) {
        var planks = Blocks.OAK_PLANKS.defaultBlockState();
        var stone = Blocks.STONE.defaultBlockState();
        var settings = new FrictionConfig(List.of());
        var slab = new java.util.ArrayList<CraftBlock>();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) slab.add(new CraftBlock(new BlockPos(x, 0, z), planks));
        }
        Hull flat = new Hull(slab);
        var uniform = flat.colliders(settings);
        double[] merged = uniform.boxes();
        test.assertTrue(merged.length == Hull.COLLIDER && merged[3] == 1.5 && merged[4] == 0.5 && merged[5] == 1.5,
                "A 3x1x3 slab of one material must be a single box, got " + merged.length / Hull.COLLIDER);
        test.assertTrue(uniform.frictionCells().length == 0, "A uniform box needs no lookup table");

        var mixed = new java.util.ArrayList<>(slab);
        mixed.set(4, new CraftBlock(BlockPos.ZERO, stone));
        var geometry = new Hull(mixed).colliders(settings);
        double wood = settings.contact(planks).mu(), rock = settings.contact(stone).mu();
        test.assertTrue(geometry.boxes().length == Hull.COLLIDER && geometry.boxes()[6] == Hull.MIXED
                && java.util.Arrays.equals(geometry.frictionCells(), new double[] {wood, wood, wood, wood, rock, wood, wood, wood, wood}),
                "A stone among planks must keep its friction at the middle of the same collider");
        var springy = new java.util.ArrayList<>(slab);
        springy.set(4, new CraftBlock(BlockPos.ZERO, Blocks.SLIME_BLOCK.defaultBlockState()));
        test.assertTrue(new Hull(springy).colliders(settings).boxes().length / Hull.COLLIDER == 1 + 4,
                "Slime in the middle must stay separate: the ring around it needs four boxes");

        double volumeMass = 0;
        double[] masses = new Hull(mixed).masses();
        for (int i = 0; i < masses.length; i += 7) volumeMass += 8 * masses[i + 3] * masses[i + 4] * masses[i + 5] * masses[i + 6];
        test.assertTrue(Math.abs(volumeMass - new Hull(mixed).mass) < 1e-6, "Merging must not change the mass");
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 60)
    public static void serverAcceptsTheClientsBlockChoiceWhenItsOwnRayMisses(GameTestHelper test) {
        floor(test);
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        ServerPlayer player = editor(test.getLevel());
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(5, 2, 5));
        Vec3 spot = craft.position().add(2, 1, 0);
        player.setPos(spot.x, spot.y - player.getEyeHeight(), spot.z);
        player.setXRot(-60); // looking up and away: the server's own ray finds nothing
        test.assertTrue(CraftEditing.nearest(test.getLevel(), player.getEyePosition(), player.getViewVector(1f), 4.5, 1f) == null,
                "The test needs a view that misses the craft");
        var top = new CraftClick(false, false, craft.getId(), BlockPos.ZERO, Direction.UP, new Vec3(0, 0.5, 0));
        var claimed = CraftClick.claimed(player, top, player.blockInteractionRange());
        test.assertTrue(claimed != null && claimed.craft() == craft, "A block in reach must be accepted from the client");
        test.assertTrue(CraftClick.claimed(player, new CraftClick(false, false, craft.getId(), BlockPos.ZERO.east(), Direction.UP,
                new Vec3(1, 0.5, 0)), 4.5) == null, "A block that is not part of the craft must be refused");
        test.assertTrue(CraftClick.claimed(player, new CraftClick(false, false, craft.getId(), BlockPos.ZERO, Direction.UP,
                new Vec3(40, 0.5, 0)), 4.5) == null, "A hit point far out of reach must be refused");
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.STONE_BRICKS, 2));
        craft.interact(player, InteractionHand.MAIN_HAND, claimed.target());
        test.assertTrue(craft.blocks().size() == 2 && craft.blocks().stream().anyMatch(b -> b.offset().equals(BlockPos.ZERO.above())),
                "The client's choice must decide where the block goes");
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 60)
    public static void creativePlayersBreakABlockWithOneHit(GameTestHelper test) {
        floor(test);
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        test.setBlock(6, 2, 5, Blocks.OAK_PLANKS);
        ServerPlayer player = editor(test.getLevel());
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(6, 2, 5));
        player.setGameMode(net.minecraft.world.level.GameType.CREATIVE);
        Vec3 above = craft.position().add(0.5, 2.5, 0);
        player.setPos(above.x, above.y - player.getEyeHeight(), above.z);
        player.setXRot(90);
        int before = craft.blocks().size();
        craft.skipAttackInteraction(player);
        test.assertTrue(craft.blocks().size() == before - 1, "Creative must break the block in one hit");
        test.assertTrue(test.getLevel().getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class,
                craft.getBoundingBox().inflate(3)).isEmpty(), "Creative breaking must not drop items");
        test.getLevel().getEntities(Aerolith.CRAFT.get(), craft.getBoundingBox().inflate(10), c -> player.getUUID().equals(c.ownerId()))
                .forEach(CraftEntity::discard);
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void theFarEndOfALongCraftCanBeAimedAt(GameTestHelper test) {
        // Vanilla entity queries only look two blocks past a section holding the entity's origin, so the ends of a long
        // craft used to be unreachable.
        for (int x = -10; x < 30; x++) test.getLevel().setBlock(test.absolutePos(new BlockPos(x, 2, 5)), Blocks.OAK_PLANKS.defaultBlockState(), 3);
        ServerPlayer player = editor(test.getLevel());
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Aerolith.ASSEMBLER.get()));
        player.setShiftKeyDown(false);
        useTool(player, test.absolutePos(new BlockPos(-10, 2, 5)));
        player.setShiftKeyDown(true);
        useTool(player, test.absolutePos(new BlockPos(29, 2, 5)));
        CraftEntity craft = HullCollisions.crafts(test.getLevel()).stream().filter(c -> player.getUUID().equals(c.ownerId()))
                .findFirst().orElse(null);
        test.assertTrue(craft != null && craft.blocks().size() == 40, "The long craft was not assembled");
        for (double end : new double[] {-9.5, 29.5}) {
            Vec3 eye = test.absoluteVec(new Vec3(end, 4.0, 5.5));
            var found = CraftEditing.nearest(test.getLevel(), eye, new Vec3(0, -1, 0), 4.5, 1f);
            test.assertTrue(found != null && found.craft() == craft, "A block at the end x=" + end + " of a long craft cannot be targeted");
        }
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void aTiltedBlockInAPitIsHitFromAbove(GameTestHelper test) {
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) {
                test.setBlock(x, 1, z, Blocks.SANDSTONE);
                boolean ring = x == 3 || x == 7 || z == 3 || z == 7;
                for (int y = 2; y <= 4; y++) test.setBlock(x, y, z, ring ? Blocks.SANDSTONE : Blocks.AIR);
            }
        }
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        test.setBlock(5, 3, 5, Blocks.OAK_PLANKS);
        ServerPlayer player = editor(test.getLevel());
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(5, 3, 5));
        int lowest = craft.blocks().stream().mapToInt(c -> c.offset().getY()).min().orElse(0);
        CraftEditing.remove(craft, craft.blocks().stream().filter(b -> b.offset().getY() != lowest).toList());
        test.assertTrue(craft.blocks().size() == 1, "Expected a single-block craft");
        double[] state = craft.nativeState();
        var tilt = new org.joml.Quaternionf().rotateAxis((float) Math.toRadians(40), 0.6f, 0.2f, 0.77f).normalize();
        state[3] = tilt.x; state[4] = tilt.y; state[5] = tilt.z; state[6] = tilt.w;
        craft.applyNative(state, 0);
        Vec3 center = CraftEditing.worldCenter(craft, craft.blocks().getFirst().offset());
        int tried = 0;
        var bad = new java.util.ArrayList<String>();
        for (double dx = -0.5; dx <= 0.5; dx += 0.25) {
            for (double dz = -0.5; dz <= 0.5; dz += 0.25) {
                for (double up : new double[] {1.8, 2.6, 3.4}) {
                    Vec3 eye = center.add(dx, up, dz);
                    Vec3 view = center.subtract(eye).normalize();
                    player.setPos(eye.x, eye.y - player.getEyeHeight(), eye.z);
                    player.setYRot((float) Math.toDegrees(Math.atan2(-view.x, view.z)));
                    player.setYHeadRot(player.getYRot());
                    player.setXRot((float) Math.toDegrees(-Math.asin(view.y)));
                    tried++;
                    var found = CraftEditing.nearest(test.getLevel(), player.getEyePosition(), player.getViewVector(1f), 4.5, 1f);
                    if (found == null) {
                        var direct = CraftEditing.target(craft, player.getEyePosition(), player.getViewVector(1f), 4.5, 1f);
                        var area = new net.minecraft.world.phys.AABB(player.getEyePosition(), player.getEyePosition().add(player.getViewVector(1f).scale(4.5))).inflate(1);
                        bad.add("no craft hit at " + eye + " direct=" + (direct != null) + " bbox=" + craft.getBoundingBox().intersects(area)
                                + " view=" + player.getViewVector(1f) + " want=" + view + " eyeNow=" + player.getEyePosition());
                        continue;
                    }
                    var world = player.pick(4.5, 1f, false);
                    double distance = found.target().pick().distance();
                    if (world.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK
                            && world.getLocation().distanceTo(player.getEyePosition()) < distance - 0.3) {
                        bad.add("terrain in front at " + eye + " world=" + world.getLocation().distanceTo(player.getEyePosition()) + " craft=" + distance);
                    }
                }
            }
        }
        craft.discard();
        test.assertTrue(bad.isEmpty(), bad.size() + " of " + tried + " clicks would be lost, e.g. " + bad.stream().limit(3).toList());
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 200)
    public static void aCraftDroppedOntoAnotherOneDoesNotBounce(GameTestHelper test) {
        floor(test);
        for (int x = 5; x <= 6; x++) {
            test.setBlock(x, 2, 5, Blocks.OAK_PLANKS);
            test.setBlock(x, 10, 5, Blocks.OAK_PLANKS);
        }
        ServerPlayer player = editor(test.getLevel());
        CraftEntity lower = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(6, 2, 5));
        CraftEntity upper = assembleWithTool(test, player, new BlockPos(5, 10, 5), new BlockPos(6, 10, 5));
        double[] fastestUp = {0};
        double[] fastestDown = {0};
        test.startSequence().thenWaitUntil(() -> {
            double vy = upper.comVelocity().y;
            fastestUp[0] = Math.max(fastestUp[0], vy);
            fastestDown[0] = Math.min(fastestDown[0], vy);
            test.assertTrue(fastestDown[0] < -8 && upper.speed() < 0.05 && lower.speed() < 0.05,
                    "The dropped craft has not come to rest yet");
        }).thenExecute(() -> {
            test.assertTrue(fastestUp[0] < 0.6, "A craft that fell at " + fastestDown[0] + " m/s rebounded upward at " + fastestUp[0] + " m/s");
            upper.discard();
            lower.discard();
        }).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 400)
    public static void blocksOfACraftRunVanillaTicks(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int x = 3; x <= 6; x++) {
            test.setBlock(x, 2, 4, Blocks.OAK_PLANKS);
            test.setBlock(x, 2, 5, Blocks.OAK_PLANKS);
        }
        test.setBlock(3, 3, 4, Blocks.FURNACE);
        test.setBlock(5, 3, 4, Blocks.REDSTONE_LAMP);
        test.setBlock(5, 3, 5, Blocks.LEVER.defaultBlockState().setValue(net.minecraft.world.level.block.LeverBlock.FACE,
                net.minecraft.world.level.block.state.properties.AttachFace.FLOOR));
        test.setBlock(6, 3, 4, Blocks.STONE);
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(3, 2, 4), new BlockPos(6, 3, 5));
        var plot = craft.plotLevel();
        BlockPos furnacePos = craft.plotPos(craft.cells().entrySet().stream().filter(e -> e.getValue().is(Blocks.FURNACE)).findFirst().orElseThrow().getKey());
        BlockPos lamp = craft.plotPos(craft.cells().entrySet().stream().filter(e -> e.getValue().is(Blocks.REDSTONE_LAMP)).findFirst().orElseThrow().getKey());
        BlockPos lever = craft.plotPos(craft.cells().entrySet().stream().filter(e -> e.getValue().is(Blocks.LEVER)).findFirst().orElseThrow().getKey());
        var furnace = (net.minecraft.world.level.block.entity.FurnaceBlockEntity) plot.getBlockEntity(furnacePos);
        test.assertTrue(furnace != null, "The furnace of a craft has no block entity");
        furnace.setItem(0, new ItemStack(Items.RAW_IRON));
        furnace.setItem(1, new ItemStack(Items.COAL));
        ((net.minecraft.world.level.block.LeverBlock) Blocks.LEVER).pull(plot.getBlockState(lever), plot, lever, null);
        test.assertTrue(plot.getBlockState(lamp).getValue(BlockStateProperties.LIT), "The lever of a craft did not light its lamp");
        ((net.minecraft.world.level.block.LeverBlock) Blocks.LEVER).pull(plot.getBlockState(lever), plot, lever, null);
        test.startSequence()
                .thenWaitUntil(() -> test.assertTrue(!plot.getBlockState(lamp).getValue(BlockStateProperties.LIT),
                        "The lamp of a craft did not get its scheduled tick"))
                .thenWaitUntil(() -> test.assertTrue(furnace.getItem(2).is(Items.IRON_INGOT), "The furnace of a craft did not smelt"))
                .thenExecute(() -> craft.discard()).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 100)
    public static void towStaffMovesACraftOnlyWhileHeldInCreative(GameTestHelper test) {
        floor(test);
        var player = editor(test.getLevel());
        for (int x = 4; x <= 6; x++) {
            test.setBlock(x, 2, 5, Blocks.OAK_PLANKS);
            for (int z = 4; z <= 6; z++) test.setBlock(x, 4, z, Blocks.STONE);
        }
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(4, 2, 5), new BlockPos(6, 2, 5));
        double startY = craft.getY();
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Aerolith.TOW_STAFF.get()));
        player.setPos(craft.position().add(0, 0.65 - player.getEyeHeight(), -3));
        player.setYRot(0);
        player.setXRot(12);
        player.setGameMode(GameType.SURVIVAL);
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(!player.isUsingItem(), "The staff grabbed a craft in survival");
        player.setGameMode(GameType.CREATIVE);
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(player.isUsingItem(), "The staff did not grab an aimed-at craft in creative");
        player.setXRot(-35);
        test.startSequence().thenIdle(32).thenExecute(() -> {
            test.assertTrue(craft.getY() - startY > 0.6, "The craft did not follow the staff upwards");
            test.assertTrue(craft.getBoundingBox().maxY < test.absolutePos(new BlockPos(5, 4, 5)).getY() + 0.12,
                    "The staff dragged the craft through the ceiling");
            player.releaseUsingItem();
        }).thenWaitUntil(() -> test.assertTrue(craft.getY() - startY < 0.25, "The released craft did not fall"))
                .thenExecute(craft::discard).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 140)
    public static void towStaffPinsTheHeldCraftWithoutLosingItsCollisions(GameTestHelper test) {
        floor(test);
        ServerLevel level = test.getLevel();
        ServerPlayer player = editor(level);
        player.setGameMode(GameType.CREATIVE);
        List<CraftBlock> cube = List.of(new CraftBlock(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState()));
        CraftEntity craft = StructureTransfer.create(level, cube, test.absoluteVec(new Vec3(5.5, 6.5, 5.5)), player.getUUID());
        test.assertTrue(craft != null, "Cannot create the craft to pin");
        player.setPos(craft.position().add(0, -player.getEyeHeight(), -3));
        player.setYRot(0);
        player.setXRot(0);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Aerolith.TOW_STAFF.get()));
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(player.isUsingItem(), "The staff did not start holding the craft");
        player.setXRot(-30);
        Vec3[] pinned = {null};
        CraftEntity[] landing = {null};
        WorldPhysics physics = WorldPhysics.of(level);
        test.startSequence().thenIdle(8).thenExecute(() -> {
            test.assertTrue(craft.speed() > 0.5, "The test craft must be moving when pinned");
            player.setYRot(90); // No longer aiming at it: the press still applies to the held craft.
            physics.toggleAnchor(player);
            test.assertTrue(craft.anchored() && !player.isUsingItem() && craft.speed() == 0 && craft.spin() == 0,
                    "Pinning must stop both the grip and all motion");
            test.assertTrue(!physics.impulseAt(craft, new Vec3(0, 2000, 0), craft.position()), "A pinned craft accepted an impulse");
            pinned[0] = craft.position();
            CraftEntity savedCopy = Aerolith.CRAFT.get().create(level);
            savedCopy.load(craft.saveWithoutId(new CompoundTag()));
            test.assertTrue(savedCopy.anchored(), "The pinned state was not saved");
            landing[0] = StructureTransfer.create(level, cube, pinned[0].add(0, 3, 0), UUID.randomUUID());
            test.assertTrue(landing[0] != null, "Cannot create the falling craft");
        }).thenWaitUntil(() -> {
            test.assertTrue(pinned[0].equals(craft.position()), "The pinned craft drifted");
            test.assertTrue(Math.abs(landing[0].getY() - pinned[0].y - 1.0) < 0.1 && landing[0].speed() < 0.2,
                    "A falling craft did not land on the pinned craft");
        }).thenExecute(() -> {
            landing[0].discard();
            player.setPos(craft.position().add(0, -player.getEyeHeight(), -3));
            player.setYRot(0);
            player.setXRot(0);
            physics.toggleAnchor(player);
            test.assertTrue(!craft.anchored() && craft.speed() == 0, "Unpinning restored old momentum or did not work");
        }).thenWaitUntil(() -> test.assertTrue(craft.getY() < pinned[0].y - 0.5, "The unpinned craft did not fall"))
                .thenExecute(craft::discard).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 100)
    public static void litTntAppearsOnTheDeckAndDamagesItsCraft(GameTestHelper test) {
        floor(test);
        var level = test.getLevel();
        var player = editor(level);
        for (int x = 4; x <= 6; x++) for (int z = 4; z <= 6; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        test.setBlock(5, 3, 5, Blocks.TNT);
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(4, 2, 4), new BlockPos(6, 3, 6));
        BlockPos offset = BlockPos.ZERO.above();
        Vec3 at = CraftEditing.worldCenter(craft, offset);
        player.setPos(at.add(0, 2 - player.getEyeHeight(), 0));
        player.setXRot(90);
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.FLINT_AND_STEEL));
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(craft.plotLevel().getBlockState(craft.plotPos(offset)).isAir(), "The lit TNT block was not removed");
        var primed = level.getEntitiesOfClass(PrimedTnt.class, craft.getBoundingBox().inflate(4));
        test.assertTrue(primed.size() == 1, "Primed TNT did not appear in the visible world");
        PrimedTnt tnt = primed.getFirst();
        test.assertTrue(tnt.getOwner() == player && tnt.getFuse() == 80 && tnt.position().distanceTo(at.subtract(0, 0.5, 0)) < 0.01,
                "Primed TNT lost its igniter, fuse or deck position");
        var hidden = craft.plotLevel().getEntitiesOfClass(PrimedTnt.class, new net.minecraft.world.phys.AABB(craft.plotPos(offset)).inflate(1));
        test.assertTrue(hidden.isEmpty(), "A duplicate TNT was left in the hidden plot");
        test.startSequence().thenIdle(20).thenExecute(() -> {
            test.assertTrue(tnt.getY() >= craft.getBoundingBox().maxY - 0.12, "Primed TNT fell through the deck");
            tnt.setFuse(1);
        }).thenWaitUntil(() -> test.assertTrue(tnt.isRemoved() && (craft.isRemoved() || craft.blocks().size() < 9),
                "TNT exploded without damaging the raft")).thenExecute(craft::discard).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 300)
    public static void waterPouredIntoACraftFlowsAndStaysInIt(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        }
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) {
                if (x == 3 || x == 7 || z == 3 || z == 7) test.setBlock(x, 3, z, Blocks.OAK_PLANKS);
            }
        }
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(3, 2, 3), new BlockPos(7, 3, 7));
        var plot = craft.plotLevel();
        BlockPos middle = craft.plotPos(new BlockPos(0, 1, 0));
        plot.setBlockAndUpdate(middle, Blocks.WATER.defaultBlockState());
        test.startSequence().thenWaitUntil(() -> {
            long wet = craft.cells().values().stream().filter(s -> s.is(Blocks.WATER)).count();
            test.assertTrue(wet >= 5, "Water poured into a craft did not spread across it, wet cells: " + wet);
            test.assertTrue(craft.blocks().stream().noneMatch(b -> b.state().is(Blocks.WATER)),
                    "Water must not become part of the hull");
        }).thenExecute(() -> craft.discard()).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 400)
    public static void waterNeverLeavesTheBoxOfACraft(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) {
                test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
                if (x == 3 || x == 7 || z == 3 || z == 7) test.setBlock(x, 3, z, Blocks.OAK_PLANKS);
            }
        }
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(3, 2, 3), new BlockPos(7, 3, 7));
        var plot = craft.plotLevel();
        // Open the middle of one wall, so water can reach the edge of the craft, and pour into the centre.
        var gap = craft.blocks().stream().filter(b -> b.offset().equals(new BlockPos(2, 1, 0))).findFirst().orElseThrow();
        CraftEditing.remove(craft, List.of(gap));
        plot.setBlockAndUpdate(craft.plotPos(new BlockPos(0, 1, 0)), Blocks.WATER.defaultBlockState());
        test.startSequence().thenWaitUntil(() -> {
            long wet = craft.cells().values().stream().filter(CraftEntity::isFluid).count();
            test.assertTrue(wet >= 9, "Water did not fill the craft, wet cells: " + wet);
        }).thenIdle(60).thenExecute(() -> {
            for (var cell : craft.cells().entrySet()) {
                if (CraftEntity.isFluid(cell.getValue())) {
                    test.assertTrue(craft.holdsFluid(craft.plotPos(cell.getKey())), "Water spread out of the craft to " + cell.getKey());
                }
            }
            craft.discard();
        }).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 200)
    public static void entitiesSwimInTheWaterOfACraft(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int x = 3; x <= 7; x++) {
            for (int z = 3; z <= 7; z++) {
                test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
                if (x == 3 || x == 7 || z == 3 || z == 7) test.setBlock(x, 3, z, Blocks.OAK_PLANKS);
            }
        }
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(3, 2, 3), new BlockPos(7, 3, 7));
        var plot = craft.plotLevel();
        for (int x = -1; x <= 1; x++) {
            for (int z = -1; z <= 1; z++) plot.setBlockAndUpdate(craft.plotPos(new BlockPos(x, 1, z)), Blocks.WATER.defaultBlockState());
        }
        var wet = test.spawn(net.minecraft.world.entity.EntityType.PIG, 5, 4, 5);
        wet.setPos(CraftEditing.worldCenter(craft, new BlockPos(0, 1, 0)));
        var dry = test.spawn(net.minecraft.world.entity.EntityType.PIG, 12, 4, 5);
        // The raft is dropped, and falls faster than a pig sinks.
        test.startSequence().thenIdle(8).thenWaitUntil(() -> test.assertTrue(craft.speed() < 0.1, "The raft has not landed")).thenIdle(8).thenExecute(() -> {
            test.assertTrue(wet.isInWater(), "An entity in the water of a craft does not swim");
            test.assertTrue(!dry.isInWater(), "An entity beside the craft is in water");
            wet.discard();
            dry.discard();
            craft.discard();
        }).thenSucceed();
    }

    @GameTest(template = "test_pad", batch = "aerolith_height_limit", timeoutTicks = 80)
    public static void wideCraftFallsNearTheBottomOfTheWorld(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        // This hull is wider than a fixture; use open flat terrain away from other test structures.
        Vec3 center = test.absoluteVec(new Vec3(5.5, 0, 97.5));
        var chunk = new net.minecraft.world.level.ChunkPos(BlockPos.containing(center));
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) level.setChunkForced(chunk.x + x, chunk.z + z, true);
        Vec3 start = new Vec3(center.x, level.getMinBuildHeight() + 6.0, center.z);
        List<CraftBlock> blocks = new java.util.ArrayList<>();
        for (int x = -25; x <= 25; x++) blocks.add(new CraftBlock(new BlockPos(x, 0, 0), Blocks.OAK_PLANKS.defaultBlockState()));
        CraftEntity craft = StructureTransfer.create(level, blocks, start, UUID.randomUUID());
        test.assertTrue(craft != null, "Cannot make a wide craft");
        test.startSequence().thenWaitUntil(() -> test.assertTrue(craft.getY() < start.y - 0.5,
                "Wide craft froze at low altitude (y=" + craft.getY() + ", simulated=" + WorldPhysics.of(level).isSimulated(craft) + ")"))
                .thenExecute(craft::discard).thenSucceed();
    }

    /**
     * The engine benchmark. It only runs when AEROLITH_BENCH is set (AEROLITH_BENCH=1 ./gradlew runGameTestServer), alone in its
     * own batch so that no other test loads the server while it is timed; the results are in the log under "Benchmark".
     */
    @GameTest(template = "test_pad", batch = "aerolith_bench", timeoutTicks = 4000)
    public static void engineBenchmark(GameTestHelper test) {
        if (System.getenv("AEROLITH_BENCH") == null) {
            test.succeed();
            return;
        }
        var level = test.getLevel();
        Vec3 center = test.absoluteVec(new Vec3(5, 1, 5));
        var chunk = new net.minecraft.world.level.ChunkPos(BlockPos.containing(center));
        for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) level.setChunkForced(chunk.x + x, chunk.z + z, true);
        int[][] scenarios = {{1, 60, 0}, {16, 16, 0}, {48, 8, 0}, {16, 16, 1}};
        var sequence = test.startSequence();
        for (int[] scenario : scenarios) {
            String[] error = {null};
            sequence = sequence.thenExecute(() -> Bench.start(level, center, scenario[0], scenario[1], 300, scenario[2] == 1,
                    (ok, report) -> {
                        if (!ok) error[0] = report;
                        Aerolith.LOG.info("\n{}", report);
                    })).thenWaitUntil(() -> test.assertTrue(!Bench.running(), "benchmark running"))
                    .thenExecute(() -> test.assertTrue(error[0] == null, error[0]));
        }
        sequence.thenExecute(() -> {
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) level.setChunkForced(chunk.x + x, chunk.z + z, false);
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void breakingAChestOfACraftSpillsItsContentsIntoTheWorld(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        for (int x = 3; x <= 5; x++) test.setBlock(x, 2, 4, Blocks.OAK_PLANKS);
        test.setBlock(4, 3, 4, Blocks.CHEST);
        var chest = (net.minecraft.world.level.block.entity.ChestBlockEntity) level.getBlockEntity(test.absolutePos(new BlockPos(4, 3, 4)));
        chest.setItem(0, new ItemStack(Items.APPLE, 4));
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(3, 2, 4), new BlockPos(5, 3, 4));
        var block = craft.blocks().stream().filter(b -> b.state().is(Blocks.CHEST)).findFirst().orElseThrow();
        player.setGameMode(net.minecraft.world.level.GameType.SURVIVAL);
        player.setPos(craft.position().add(0, 5, 0));
        Vec3 aim = CraftEditing.worldCenter(craft, block.offset());
        player.setPos(aim.x, aim.y + 2 - player.getEyeHeight(), aim.z);
        player.setXRot(90);
        for (int i = 0; i < 400 && craft.blocks().stream().anyMatch(b -> b.state().is(Blocks.CHEST)); i++) craft.skipAttackInteraction(player);
        test.assertTrue(craft.blocks().stream().noneMatch(b -> b.state().is(Blocks.CHEST)), "The chest of a craft could not be broken");
        var items = level.getEntitiesOfClass(net.minecraft.world.entity.item.ItemEntity.class, craft.getBoundingBox().inflate(4));
        test.assertTrue(items.stream().anyMatch(i -> i.getItem().is(Items.APPLE) && i.getItem().getCount() == 4),
                "What a broken chest held must appear next to the craft in the world, found " + items);
        test.assertTrue(items.stream().anyMatch(i -> i.getItem().is(Items.CHEST)), "A broken chest must drop itself");
        items.forEach(net.minecraft.world.entity.Entity::discard);
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void craftsSavedBeforePlotStorageMoveIntoAPlot(GameTestHelper test) {
        var level = test.getLevel();
        var saved = new CompoundTag();
        var list = new net.minecraft.nbt.ListTag();
        BlockState[] states = {Blocks.OAK_PLANKS.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.RED_WOOL.defaultBlockState()};
        for (int i = 0; i < states.length; i++) {
            var entry = new CompoundTag();
            entry.putLong("Offset", new BlockPos(i, 0, 0).asLong());
            entry.put("State", net.minecraft.nbt.NbtUtils.writeBlockState(states[i]));
            list.add(entry);
        }
        saved.put("Hull", list);
        UUID owner = UUID.randomUUID();
        saved.putUUID("Owner", owner);
        CraftEntity craft = Aerolith.CRAFT.get().create(level);
        craft.load(saved);
        test.assertTrue(craft.plotId() >= 0 && craft.blocks().size() == 3 && craft.hull() != null, "An old craft did not move into a plot");
        for (int i = 0; i < states.length; i++) {
            test.assertTrue(craft.plotLevel().getBlockState(craft.plotPos(new BlockPos(i, 0, 0))) == states[i], "Block " + i + " was not written to the plot");
        }
        var again = new CompoundTag();
        craft.saveWithoutId(again);
        test.assertTrue(again.contains("PlotId") && !again.contains("Hull"), "The migrated craft must save in the new format");
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad", batch = "aerolith_spawn", timeoutTicks = 200)
    public static void spawnCommandMakesAsManyCraftsOfThatSize(GameTestHelper test) {
        var level = test.getLevel();
        BlockPos at = test.absolutePos(new BlockPos(5, 60, 5));
        var chunk = new net.minecraft.world.level.ChunkPos(at);
        for (int x = -3; x <= 3; x++) for (int z = -3; z <= 3; z++) level.setChunkForced(chunk.x + x, chunk.z + z, true);
        var source = level.getServer().createCommandSourceStack().withLevel(level).withPosition(Vec3.atCenterOf(at)).withSuppressedOutput();
        // Something in the way: the place is passed over, not filled.
        BlockPos rock = at.above().south(4);
        level.setBlockAndUpdate(rock, Blocks.STONE.defaultBlockState());
        var commands = level.getServer().getCommands();
        commands.performPrefixedCommand(source, "aerolith spawn 40 3 2 5 minecraft:stone");
        commands.performPrefixedCommand(source, "aerolith spawn 1 101 101 101");
        commands.performPrefixedCommand(source, "aerolith spawn 1 2 2 2 minecraft:torch");
        test.startSequence().thenIdle(40).thenExecute(() -> {
            var made = HullCollisions.crafts(level).stream().filter(c -> c.getTags().contains(CraftSpawner.TAG)).toList();
            test.assertTrue(made.size() == 40, "Expected 40 crafts, got " + made.size());
            for (CraftEntity craft : made) {
                var b = craft.hull().localBounds;
                test.assertTrue(craft.blocks().size() == 30 && b.getXsize() == 3 && b.getYsize() == 2 && b.getZsize() == 5
                        && WorldPhysics.of(level).isSimulated(craft), "A craft has the wrong size or is not simulated: " + b);
            }
            test.assertTrue(level.getBlockState(rock).is(Blocks.STONE), "A block in the way is gone");
            made.forEach(CraftEntity::discard);
            level.setBlockAndUpdate(rock, Blocks.AIR.defaultBlockState());
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void boundsOfATurnedCraftTouchItsCorners(GameTestHelper test) {
        CraftEntity craft = StructureTransfer.create(test.getLevel(), List.of(new CraftBlock(BlockPos.ZERO, Blocks.STONE.defaultBlockState()),
                new CraftBlock(BlockPos.ZERO.above(), Blocks.STONE.defaultBlockState())), test.absoluteVec(new Vec3(5, 6, 5)), UUID.randomUUID());
        var turned = new org.joml.Quaternionf().rotationXYZ(0.4f, 1.9f, -0.8f);
        double[] state = craft.nativeState();
        state[3] = turned.x;
        state[4] = turned.y;
        state[5] = turned.z;
        state[6] = turned.w;
        craft.applyNative(state, 0);
        net.minecraft.world.phys.AABB expected = null;
        for (int i = 0; i < 8; i++) {
            Vec3 p = craft.position().add(Hull.rotate(turned, new Vec3((i & 1) - 0.5, (i & 2) - 0.5, (i >> 2) - 0.5)));
            var point = new net.minecraft.world.phys.AABB(p, p);
            expected = expected == null ? point : expected.minmax(point);
        }
        var box = craft.getBoundingBox();
        test.assertTrue(box.getMinPosition().distanceTo(expected.getMinPosition()) < 1e-5
                && box.getMaxPosition().distanceTo(expected.getMaxPosition()) < 1e-5, "Expected " + expected + ", got " + box);
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void firstImpulseCannotCrossAnUnclaimedTerrainTile(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        // Above the barriers around the test: where a tile begins, and so where the cube starts, may be beyond them.
        BlockPos here = test.absolutePos(new BlockPos(5, 40, 5));
        var chunk = new net.minecraft.world.level.ChunkPos(here);
        for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) level.setChunkForced(chunk.x + x, chunk.z + z, true);
        int wallX = Math.floorDiv(here.getX() + Terrain.TILE / 2, Terrain.TILE) * Terrain.TILE;
        BlockPos wall = new BlockPos(wallX, here.getY(), here.getZ());
        level.setBlockAndUpdate(wall, Blocks.STONE.defaultBlockState());
        Vec3 start = new Vec3(wallX - 3.125, wall.getY() + 0.5, wall.getZ() + 0.5);
        CraftEntity craft = StructureTransfer.create(level, List.of(new CraftBlock(BlockPos.ZERO, Blocks.OAK_PLANKS.defaultBlockState())),
                start, UUID.randomUUID());
        test.assertTrue(craft != null && WorldPhysics.of(level).impulseAt(craft, new Vec3(craft.hull().mass * 80, 0, 0), start),
                "Could not launch test cube");
        test.startSequence().thenIdle(2).thenExecute(() -> {
            test.assertTrue(craft.getX() > start.x + 1 && craft.getX() <= wallX - 0.45,
                    "The first impulse must reach the wall without crossing it: x=" + craft.getX() + ", wall=" + wallX);
            craft.discard();
            level.setBlockAndUpdate(wall, Blocks.AIR.defaultBlockState());
        }).thenSucceed();
    }

    @GameTest(template = "test_pad")
    public static void earlyPlotLookupPreservesSavedChestButNotDiscardedChest(GameTestHelper test) {
        var plot = Plots.level(test.getLevel().getServer());
        int id = Plots.allocate(plot);
        BlockPos pos = PlotGrid.origin(id);
        long key = PlotGrid.chunkKey(pos);
        var disk = plot.getChunkSource().chunkMap;
        try {
            Plots.write(plot, pos, List.of(new Plots.Moved(BlockPos.ZERO, Blocks.CHEST.defaultBlockState(), null)));
            PlotChunks.keep(plot, key);
            ((ChestBlockEntity) plot.getBlockEntity(pos)).setItem(2, new ItemStack(Items.DIAMOND, 7));
            PlotChunks.letGo(plot, key);
            disk.flushWorker();
            test.assertTrue(disk.read(new net.minecraft.world.level.ChunkPos(key)).join().isPresent(), "No plot chunk was saved");
            // An ordinary block lookup, before any craft claims its saved chunks.
            test.assertTrue(plot.getBlockEntity(pos) instanceof ChestBlockEntity chest && chest.getItem(2).is(Items.DIAMOND)
                    && chest.getItem(2).getCount() == 7, "An early lookup hid a saved chest behind an empty chunk");
            PlotChunks.discard(plot, key);
            test.assertTrue(plot.getBlockState(pos).isAir(), "A discarded plot remained visible");
            PlotChunks.letGo(plot, key);
            PlotChunks.save(plot, true);
            disk.flushWorker();
            test.assertTrue(disk.read(new net.minecraft.world.level.ChunkPos(key)).join().isEmpty(), "Looking at an empty plot cancelled its deletion");
            test.succeed();
        } finally {
            PlotChunks.discard(plot, key);
            Plots.release(plot, id);
        }
    }

    @GameTest(template = "test_pad")
    public static void entitySpawningItemsAreNotSpentOnACraft(GameTestHelper test) {
        var level = test.getLevel();
        ServerPlayer player = editor(level);
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        test.setBlock(6, 2, 5, Blocks.OAK_PLANKS);
        CraftEntity craft = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(6, 2, 5));
        var frames = new ItemStack(Items.ITEM_FRAME, 3);
        player.setItemInHand(InteractionHand.MAIN_HAND, frames);
        Vec3 above = craft.position().add(0, 2.5, 0);
        player.setPos(above.x, above.y - player.getEyeHeight(), above.z);
        player.setXRot(90);
        craft.interact(player, InteractionHand.MAIN_HAND);
        test.assertTrue(frames.getCount() == 3, "An item frame was spent on a craft");
        craft.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void everyBlockWithAShapeCanBeAimedAtOnACraft(GameTestHelper test) {
        var failures = new java.util.ArrayList<String>();
        int tested = 0;
        BlockState stone = Blocks.STONE.defaultBlockState();
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (state.isAir() || CraftEntity.isFluid(state)) continue;
                var boxes = Materials.boxes(state).isEmpty() ? Materials.outline(state) : Materials.boxes(state);
                if (boxes.isEmpty()) continue;
                tested++;
                BlockPos at = new BlockPos(6, 0, 0);
                Hull hull = new Hull(List.of(new CraftBlock(BlockPos.ZERO, stone), new CraftBlock(at, state)));
                for (var box : boxes) {
                    Vec3 center = box.getCenter().add(at.getX() - 0.5, at.getY() - 0.5, at.getZ() - 0.5);
                    for (Direction side : Direction.values()) {
                        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
                        var pick = hull.pick(center.add(normal.scale(3)), normal.scale(-1), 6);
                        if (pick == null || pick.block().state() != state) failures.add(state + " from " + side);
                    }
                }
            }
        }
        test.assertTrue(tested > 500, "Too few block states were tested: " + tested);
        test.assertTrue(failures.isEmpty(), failures.size() + " block shapes cannot be aimed at, e.g. " + failures.stream().limit(6).toList());
        test.succeed();
    }

    @GameTest(template = "test_pad")
    public static void everySupportedBlockStateCanBePickedFromEverySide(GameTestHelper test) {
        var failures = new java.util.ArrayList<String>();
        int tested = 0;
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                if (!Materials.supported(state)) continue;
                tested++;
                Hull hull = new Hull(List.of(new CraftBlock(BlockPos.ZERO, state)));
                for (var box : Materials.boxes(state)) {
                    Vec3 center = box.getCenter().subtract(0.5, 0.5, 0.5);
                    for (Direction side : Direction.values()) {
                        Vec3 normal = Vec3.atLowerCornerOf(side.getNormal());
                        var pick = hull.pick(center.add(normal.scale(3)), normal.scale(-1), 6);
                        if (pick == null || pick.block().state() != state) {
                            failures.add(state + " from " + side);
                        }
                    }
                }
            }
        }
        test.assertTrue(tested > 100, "Too few block states were tested: " + tested);
        test.assertTrue(failures.isEmpty(), failures.size() + " of " + tested + " states cannot be targeted, e.g. "
                + failures.stream().limit(5).toList());
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 60)
    public static void assemblingInsideAnotherCraftIsRefused(GameTestHelper test) {
        floor(test);
        test.setBlock(5, 2, 5, Blocks.OAK_PLANKS);
        ServerPlayer player = editor(test.getLevel());
        CraftEntity first = assembleWithTool(test, player, new BlockPos(5, 2, 5), new BlockPos(5, 2, 5));
        // The world block now overlaps the craft that stands in the same space.
        test.setBlock(5, 2, 5, Blocks.STONE);
        BlockPos at = test.absolutePos(new BlockPos(5, 2, 5));
        test.assertTrue(StructureTransfer.assemble(test.getLevel(), player, at, at) == null,
                "Assembling blocks that overlap another craft would launch both");
        test.assertBlockPresent(Blocks.STONE, new BlockPos(5, 2, 5));
        first.discard();
        test.succeed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 100)
    public static void creatureOnDeckCountsAsLoad(GameTestHelper test) {
        floor(test);
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) test.setBlock(x, 2, z, Blocks.OAK_PLANKS);
        }
        CraftEntity craft = assembleWithTool(test, editor(test.getLevel()), new BlockPos(4, 2, 4), new BlockPos(6, 2, 6));
        var pig = test.spawn(net.minecraft.world.entity.EntityType.PIG, 5.5f, 4.5f, 5.5f);
        test.startSequence().thenWaitUntil(() -> test.assertTrue(
                HullCollisions.supported(craft).stream().anyMatch(s -> s.entity() == pig) && CraftLoads.mass(pig) > 50,
                "A pig standing on the deck must be a supported load")
        ).thenExecute(() -> { pig.discard(); craft.discard(); }).thenSucceed();
    }

    private static void floor(GameTestHelper test) {
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
    }

    @GameTest(template = "test_pad", batch = "aerolith_heavy_stack", timeoutTicks = 230)
    public static void goldRaftsStayOnAnOakRaftWithoutJitter(GameTestHelper test) {
        floor(test);
        Vec3 at = Vec3.atCenterOf(test.absolutePos(new BlockPos(5, 2, 5)));
        var wood = new ArrayList<CraftBlock>();
        var gold = new ArrayList<CraftBlock>();
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                BlockPos cell = new BlockPos(x, 0, z);
                wood.add(new CraftBlock(cell, Blocks.OAK_PLANKS.defaultBlockState()));
                if (Math.abs(x) <= 1 && Math.abs(z) <= 1) gold.add(new CraftBlock(cell, Blocks.GOLD_BLOCK.defaultBlockState()));
            }
        }
        var level = test.getLevel();
        var stack = new ArrayList<CraftEntity>();
        CraftEntity deck = StructureTransfer.create(level, wood, at.add(0, 0.5, 0), UUID.randomUUID());
        test.assertTrue(deck != null, "Could not create the oak raft");
        stack.add(deck);
        test.startSequence().thenIdle(60).thenExecute(() -> {
            for (int i = 0; i < 6; i++) {
                CraftEntity raft = StructureTransfer.create(level, gold, at.add(0, 1.4 + i * 1.25, 0), UUID.randomUUID());
                test.assertTrue(raft != null, "Could not create a gold raft");
                stack.add(raft);
            }
        }).thenExecuteFor(100, () -> {
            double lower = at.y - 1;
            for (CraftEntity raft : stack) {
                test.assertTrue(raft.getY() - lower > 0.92, "Raft sank into its support: " + (raft.getY() - lower) + " m between centres");
                lower = raft.getY();
            }
        }).thenExecuteFor(25, () -> {
            for (int i = 0; i < stack.size(); i++) {
                CraftEntity raft = stack.get(i);
                test.assertTrue(Math.abs(raft.getY() - (at.y + i)) < 0.08 && raft.speed() < 0.02 && raft.spin() < 0.02,
                        "Raft " + i + " is not still at its resting height: y=" + raft.getY() + ", speed=" + raft.speed());
            }
        }).thenExecute(() -> stack.forEach(CraftEntity::discard)).thenSucceed();
    }

    private static CraftEntity plankSquare(GameTestHelper test, boolean hole) {
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
        for (int x = 4; x <= 6; x++) {
            for (int z = 4; z <= 6; z++) test.setBlock(x, 2, z, hole && x == 5 && z == 5 ? Blocks.AIR : Blocks.OAK_PLANKS);
        }
        return assembleWithTool(test, editor(test.getLevel()), new BlockPos(4, 2, 4), new BlockPos(6, 2, 6));
    }

    @GameTest(template = "test_pad", timeoutTicks = 120)
    public static void thinBlockLandsOnItsOwnShape(GameTestHelper test) {
        for (int x = 2; x <= 8; x++) {
            for (int z = 2; z <= 8; z++) test.setBlock(x, 1, z, Blocks.STONE);
        }
        BlockPos drop = new BlockPos(5, 6, 5);
        test.setBlock(drop, Blocks.WHITE_CARPET);
        CraftEntity craft = assembleWithTool(test, editor(test.getLevel()), drop, drop);
        double expectedCenter = test.absolutePos(new BlockPos(5, 1, 5)).getY() + 1 + 0.5;
        test.startSequence().thenWaitUntil(() ->
                test.assertTrue(Math.abs(craft.getY() - expectedCenter) < 0.04 && craft.speed() < 0.2,
                        "A carpet collided as a full block instead of a thin plate")
        ).thenExecute(craft::discard).thenSucceed();
    }

    @GameTest(template = "test_pad", timeoutTicks = 240)
    public static void woodFloatsInMinecraftWater(GameTestHelper test) {
        ServerLevel level = test.getLevel();
        for (int z = 1; z <= 9; z++) {
            for (int x = 1; x <= 9; x++) {
                test.setBlock(x, 1, z, Blocks.STONE);
                for (int y = 2; y <= 4; y++) {
                    test.setBlock(x, y, z, x == 1 || x == 9 || z == 1 || z == 9 ? Blocks.STONE : Blocks.WATER);
                }
            }
        }
        BlockPos drop = new BlockPos(5, 7, 5);
        test.setBlock(drop, Blocks.OAK_PLANKS);
        CraftEntity craft = assembleWithTool(test, editor(level), drop, drop);
        double surface = test.absolutePos(new BlockPos(5, 4, 5)).getY() + 8.0 / 9.0;
        test.startSequence().thenWaitUntil(() -> {
            test.assertTrue(Math.abs(craft.getY() - surface) < 0.16 && craft.speed() < 0.12,
                    "A 480 kg/m³ block did not float half-submerged");
        }).thenExecute(craft::discard).thenSucceed();
    }

    private static ServerPlayer editor(ServerLevel level) {
        return new FakePlayer(level, new GameProfile(UUID.randomUUID(), "AerolithTest"));
    }

    private static CraftEntity assembleWithTool(GameTestHelper test, ServerPlayer player, BlockPos a, BlockPos b) {
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Aerolith.ASSEMBLER.get()));
        player.setShiftKeyDown(false);
        useTool(player, test.absolutePos(a));
        player.setShiftKeyDown(true);
        useTool(player, test.absolutePos(b));
        player.setShiftKeyDown(false);
        var crafts = test.getLevel().getEntities(Aerolith.CRAFT.get(),
                new net.minecraft.world.phys.AABB(test.absolutePos(a)).inflate(3), craft -> craft.canEdit(player));
        test.assertTrue(crafts.size() == 1, "Assembler item did not produce exactly one craft");
        return crafts.getFirst();
    }

    private static void useTool(ServerPlayer player, BlockPos pos) {
        InteractionHand main = InteractionHand.MAIN_HAND;
        player.gameMode.useItemOn(player, player.serverLevel(), player.getMainHandItem(), main,
                new BlockHitResult(Vec3.atCenterOf(pos), Direction.UP, pos, false));
    }
}
