package dev.aerolith;

import com.mojang.logging.LogUtils;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(Aerolith.ID)
public final class Aerolith {
    public static final String ID = "aerolith";
    static final Logger LOG = LogUtils.getLogger();
    private static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(ID);
    private static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, ID);
    public static final DeferredItem<AssemblerItem> ASSEMBLER =
            ITEMS.registerItem("assembler", AssemblerItem::new, props -> props.stacksTo(1));
    public static final DeferredItem<TowStaffItem> TOW_STAFF =
            ITEMS.registerItem("tow_staff", TowStaffItem::new, props -> props.stacksTo(1));
    public static final DeferredHolder<EntityType<?>, EntityType<CraftEntity>> CRAFT =
            ENTITIES.register("craft", id -> EntityType.Builder.<CraftEntity>of(CraftEntity::new, MobCategory.MISC)
                    .sized(1.0f, 1.0f).clientTrackingRange(10).updateInterval(1).build(ResourceKey.create(Registries.ENTITY_TYPE, id)));

    public Aerolith(IEventBus modBus, ModContainer container) {
        ITEMS.register(modBus);
        ENTITIES.register(modBus);
        AerolithParts.register(modBus);
        GameTests.register(modBus);
        container.registerConfig(ModConfig.Type.SYNCED, FrictionConfig.SPEC, "aerolith-server.toml");
        modBus.addListener(Aerolith::creativeTab);
        modBus.addListener(AerolithParts::creativeTab);
        modBus.addListener(PilotInput::register);
        NeoForge.EVENT_BUS.addListener(WorldPhysics::onJoin);
        NeoForge.EVENT_BUS.addListener(WorldPhysics::onLeave);
        NeoForge.EVENT_BUS.addListener(WorldPhysics::onTick);
        NeoForge.EVENT_BUS.addListener(WorldPhysics::onLoad);
        NeoForge.EVENT_BUS.addListener(WorldPhysics::onUnload);
        NeoForge.EVENT_BUS.addListener(PhysicsCommands::register);
    }

    private static void creativeTab(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ASSEMBLER.get());
            event.accept(TOW_STAFF.get());
        }
    }
}
