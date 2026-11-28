package dev.aerolith;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.FunctionGameTestInstance;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.gametest.framework.TestData;
import net.minecraft.gametest.framework.TestEnvironmentDefinition;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Rotation;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.RegisterGameTestsEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

final class GameTests {
    private static final DeferredRegister<Consumer<GameTestHelper>> FUNCTIONS = DeferredRegister.create(Registries.TEST_FUNCTION, Aerolith.ID);
    private static final List<Test> TESTS = new ArrayList<>();
    private static final Identifier PAD = Identifier.fromNamespaceAndPath(Aerolith.ID, "test_pad");
    /** The tests build well outside their 12-metre template; this keeps the next test's area out of reach of the last one's. */
    private static final int PADDING = 40;
    private record Test(ResourceKey<Consumer<GameTestHelper>> function, String batch, int ticks, boolean sky) {}

    static void register(IEventBus bus) {
        add("assemblelandsaveanddock", "default", 120, false, AerolithGameTests::assembleLandSaveAndDock);
        add("storagekeepsitsitemsthroughassemblinganddocking", "default", 100, false, AerolithGameTests::storageKeepsItsItemsThroughAssemblingAndDocking);
        add("partialhullvolumegeometryandmaterials", "default", 100, false, AerolithGameTests::partialHullVolumeGeometryAndMaterials);
        add("commonmaterialshavedensity", "default", 100, false, AerolithGameTests::commonMaterialsHaveDensity);
        add("fencesreconnectwhenassembledanddocked", "default", 100, false, AerolithGameTests::fencesReconnectWhenAssembledAndDocked);
        add("stonewallsuselowarmsofftheworldandreconnectondocking", "default", 100, false, AerolithGameTests::stoneWallsUseLowArmsOffTheWorldAndReconnectOnDocking);
        add("faceadjacentcellsstayonecraftwithoutshapecontact", "default", 100, false, AerolithGameTests::faceAdjacentCellsStayOneCraftWithoutShapeContact);
        add("openinggatekeepsthecraftanditsmass", "default", 100, false, AerolithGameTests::openingGateKeepsTheCraftAndItsMass);
        add("craftlandsatvisiblefenceheight", "default", 160, false, AerolithGameTests::craftLandsAtVisibleFenceHeight);
        add("itemrestsonsoliddeck", "default", 160, false, AerolithGameTests::itemRestsOnSolidDeck);
        add("itemfallsthroughhullgap", "default", 160, false, AerolithGameTests::itemFallsThroughHullGap);
        add("brokenmiddlesplitscraftintotwobodieswithsamemomentum", "default", 60, false, AerolithGameTests::brokenMiddleSplitsCraftIntoTwoBodiesWithSameMomentum);
        add("playerplacesandbreaksblockswiththecrosshair", "default", 60, false, AerolithGameTests::playerPlacesAndBreaksBlocksWithTheCrosshair);
        add("fragmentsofaspinningfallingcraftstayabovethefloor", "default", 200, false, AerolithGameTests::fragmentsOfASpinningFallingCraftStayAboveTheFloor);
        add("uniformblocksmergeintofewcolliderswiththesamemass", "default", 100, false, AerolithGameTests::uniformBlocksMergeIntoFewCollidersWithTheSameMass);
        add("serveracceptstheclientsblockchoicewhenitsownraymisses", "default", 60, false, AerolithGameTests::serverAcceptsTheClientsBlockChoiceWhenItsOwnRayMisses);
        add("creativeplayersbreakablockwithonehit", "default", 60, false, AerolithGameTests::creativePlayersBreakABlockWithOneHit);
        add("thefarendofalongcraftcanbeaimedat", "default", 100, false, AerolithGameTests::theFarEndOfALongCraftCanBeAimedAt);
        add("atiltedblockinapitishitfromabove", "default", 100, false, AerolithGameTests::aTiltedBlockInAPitIsHitFromAbove);
        add("acraftdroppedontoanotheronedoesnotbounce", "default", 200, false, AerolithGameTests::aCraftDroppedOntoAnotherOneDoesNotBounce);
        add("blocksofacraftrunvanillaticks", "default", 400, false, AerolithGameTests::blocksOfACraftRunVanillaTicks);
        add("towstaffmovesacraftonlywhileheldincreative", "default", 100, false, AerolithGameTests::towStaffMovesACraftOnlyWhileHeldInCreative);
        add("towstaffpinstheheldcraftwithoutlosingitscollisions", "default", 140, false, AerolithGameTests::towStaffPinsTheHeldCraftWithoutLosingItsCollisions);
        add("towstaffadjustsdistanceandrotates", "default", 100, false, AerolithGameTests::towStaffAdjustsDistanceAndRotates);
        add("towstaffkeepsupwithafastcursor", "default", 100, false, AerolithGameTests::towStaffKeepsUpWithAFastCursor);
        add("littntappearsonthedeckanddamagesitscraft", "default", 100, false, AerolithGameTests::litTntAppearsOnTheDeckAndDamagesItsCraft);
        add("waterpouredintoacraftflowsandstaysinit", "default", 300, false, AerolithGameTests::waterPouredIntoACraftFlowsAndStaysInIt);
        add("waterneverleavestheboxofacraft", "default", 400, false, AerolithGameTests::waterNeverLeavesTheBoxOfACraft);
        add("entitiesswiminthewaterofacraft", "default", 200, false, AerolithGameTests::entitiesSwimInTheWaterOfACraft);
        add("widecraftfallsnearthebottomoftheworld", "aerolith_height_limit", 80, false, AerolithGameTests::wideCraftFallsNearTheBottomOfTheWorld);
        add("craftsfallfromanyheightandarenottakenbythevoid", "aerolith_height_limit", 200, false, AerolithGameTests::craftsFallFromAnyHeightAndAreNotTakenByTheVoid);
        add("enginebenchmark", "aerolith_bench", 4000, false, AerolithGameTests::engineBenchmark);
        add("cubesbenchmark", "aerolith_bench", 4000, false, CubeRaftsBenchmark::run);
        add("breakingachestofacraftspillsitscontentsintotheworld", "default", 100, false, AerolithGameTests::breakingAChestOfACraftSpillsItsContentsIntoTheWorld);
        add("craftssavedbeforeplotstoragemoveintoaplot", "default", 100, false, AerolithGameTests::craftsSavedBeforePlotStorageMoveIntoAPlot);
        add("spawncommandmakesasmanycraftsofthatsize", "aerolith_spawn", 200, false, AerolithGameTests::spawnCommandMakesAsManyCraftsOfThatSize);
        add("boundsofaturnedcrafttouchitscorners", "default", 100, false, AerolithGameTests::boundsOfATurnedCraftTouchItsCorners);
        add("firstimpulsecannotcrossanunclaimedterraintile", "default", 100, false, AerolithGameTests::firstImpulseCannotCrossAnUnclaimedTerrainTile);
        add("placingaboveaplotsectionupdatesthehullimmediately", "default", 100, false, AerolithGameTests::placingAboveAPlotSectionUpdatesTheHullImmediately);
        add("earlyplotlookuppreservessavedchestbutnotdiscardedchest", "default", 100, false, AerolithGameTests::earlyPlotLookupPreservesSavedChestButNotDiscardedChest);
        add("entityspawningitemsarenotspentonacraft", "default", 100, false, AerolithGameTests::entitySpawningItemsAreNotSpentOnACraft);
        add("everyblockwithashapecanbeaimedatonacraft", "default", 100, false, AerolithGameTests::everyBlockWithAShapeCanBeAimedAtOnACraft);
        add("everysupportedblockstatecanbepickedfromeveryside", "default", 100, false, AerolithGameTests::everySupportedBlockStateCanBePickedFromEverySide);
        add("assemblinginsideanothercraftisrefused", "default", 60, false, AerolithGameTests::assemblingInsideAnotherCraftIsRefused);
        add("creatureondeckcountsasload", "default", 100, false, AerolithGameTests::creatureOnDeckCountsAsLoad);
        add("goldraftsstayonanoakraftwithoutjitter", "aerolith_heavy_stack", 230, false, AerolithGameTests::goldRaftsStayOnAnOakRaftWithoutJitter);
        add("thinblocklandsonitsownshape", "default", 120, false, AerolithGameTests::thinBlockLandsOnItsOwnShape);
        add("woodfloatsinminecraftwater", "default", 240, false, AerolithGameTests::woodFloatsInMinecraftWater);
        add("rotatedcontactandsliderespectterrain", "default", 100, false, HullCollisionGameTests::rotatedContactAndSlideRespectTerrain);
        add("crossaxesandhullopeningsstayempty", "default", 100, false, HullCollisionGameTests::crossAxesAndHullOpeningsStayEmpty);
        add("placementseesbothsidesofacolumnandtrackshullchanges", "default", 100, false, HullCollisionGameTests::placementSeesBothSidesOfAColumnAndTracksHullChanges);
        add("trackingrestorescraftresources", "default", 100, false, CraftTrackingGameTests::trackingRestoresCraftResources);
        add("craftssurviveaslowengineandaserverstall", "default", 600, false, CraftTrackingGameTests::craftsSurviveASlowEngineAndAServerStall);
        add("interpolatedboundsfollowrenderedpose", "default", 100, false, CraftTrackingGameTests::interpolatedBoundsFollowRenderedPose);
        add("slabsupportsandcarriesitems", "default", 100, false, HullCollisionGameTests::slabSupportsAndCarriesItems);
        add("fencekeepsvanillabarrierbutnoghostmassorselection", "default", 100, false, HullCollisionGameTests::fenceKeepsVanillaBarrierButNoGhostMassOrSelection);
        add("translatingsidepushesinsteadofpassingthrough", "default", 100, false, HullCollisionGameTests::translatingSidePushesInsteadOfPassingThrough);
        add("translatinghullpusheseveryentityinacrowd", "default", 100, false, HullCollisionGameTests::translatingHullPushesEveryEntityInACrowd);
        add("playercrouchesandpacketsrespectdeck", "default", 100, false, HullCollisionGameTests::playerCrouchesAndPacketsRespectDeck);
        add("creaturewalksupatilteddeckwithoutslidingback", "default", 100, false, HullCollisionGameTests::creatureWalksUpATiltedDeckWithoutSlidingBack);
        add("walkingplayerdoesnotloadortiltcraft", "default", 100, false, HullCollisionGameTests::walkingPlayerDoesNotLoadOrTiltCraft);
        add("creaturewalkingintoahullpushesit", "default", 100, false, HullCollisionGameTests::creatureWalkingIntoAHullPushesIt);
        add("fastrafpushesplayerswithouttunneling", "default", 100, false, FastRaftCollisionGameTests::fastRaftPushesPlayersWithoutTunneling);
        add("fastspinningraftbeyondoldstepcappushesplayer", "default", 100, false, FastRaftCollisionGameTests::fastSpinningRaftBeyondOldStepCapPushesPlayer);
        add("tiltedfastrafpushesgroundedplayerforward", "default", 100, false, FastRaftCollisionGameTests::tiltedFastRaftPushesGroundedPlayerForward);
        add("physicsassemblerblockmakessetsrangeandunmakesacraft", "default", 300, false, PhysicsGameTests::physicsAssemblerBlockMakesSetsRangeAndUnmakesACraft);
        add("boxindexfindsexactlywhatascanfinds", "default", 100, false, PhysicsGameTests::boxIndexFindsExactlyWhatAScanFinds);
        add("terrainclaimvisitsonlychangedtiles", "default", 100, false, PhysicsGameTests::terrainClaimVisitsOnlyChangedTiles);
        add("raftwithredstoneassemblesanddocksback", "aerolith_redstone", 300, false, PhysicsGameTests::raftWithRedstoneAssemblesAndDocksBack);
        add("redstoneonacraftdoesnotrebuilditshull", "aerolith_redstone", 300, false, PhysicsGameTests::redstoneOnACraftDoesNotRebuildItsHull);
        add("blocksdroppedonaraftneversinkintoit", "default", 900, false, PhysicsGameTests::blocksDroppedOnARaftNeverSinkIntoIt);
        FUNCTIONS.register(bus);
        bus.addListener(GameTests::registerInstances);
    }

    private static void add(String name, String batch, int ticks, boolean sky, Consumer<GameTestHelper> test) {
        var function = FUNCTIONS.register(name, () -> test);
        TESTS.add(new Test(function.unwrapKey().orElseThrow(), batch, ticks, sky));
    }

    private static void registerInstances(RegisterGameTestsEvent event) {
        var environments = new HashMap<String, Holder<TestEnvironmentDefinition<?>>>();
        for (Test test : TESTS) {
            var environment = environments.computeIfAbsent(test.batch,
                    batch -> event.registerEnvironment(Identifier.fromNamespaceAndPath(Aerolith.ID, batch)));
            event.registerTest(test.function.identifier(), new FunctionGameTestInstance(test.function,
                    new TestData<>(environment, Level.OVERWORLD, PAD, test.ticks, 0, true, Rotation.NONE,
                            false, 1, 1, test.sky, PADDING)));
        }
    }
}
