package dev.aerolith;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.level.block.SoundType;

import net.minecraft.world.level.material.MapColor;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredRegister;

/** The one block this mod adds: the survival way to turn a structure into a physics craft. */
public final class AerolithParts {
    private static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(Aerolith.ID);
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(Aerolith.ID);

    public static final DeferredBlock<PhysicsAssemblerBlock> PHYSICS_ASSEMBLER = BLOCKS.registerBlock("physics_assembler",
            PhysicsAssemblerBlock::new, props -> props.mapColor(MapColor.METAL).strength(3.0f, 6.0f).sound(SoundType.METAL));

    static {
        ITEMS.registerSimpleBlockItem(PHYSICS_ASSEMBLER);
    }

    private AerolithParts() {}

    public static void register(IEventBus bus) {
        BLOCKS.register(bus);
        ITEMS.register(bus);
    }

    public static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(PHYSICS_ASSEMBLER.get());
        }
    }
}
