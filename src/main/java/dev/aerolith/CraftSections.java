package dev.aerolith;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;

/** Server-thread lookup for placement checks; unlike vanilla, it uses every column the hull reaches. */
final class CraftSections {
    private final Map<CraftEntity, Columns> byCraft = new IdentityHashMap<>();
    private final Long2ObjectOpenHashMap<Set<CraftEntity>> byColumn = new Long2ObjectOpenHashMap<>();
    // shortcut: scan wide hulls directly, so a 255-block hull cannot flood the index; index them too if this scan becomes hot.
    private final Set<CraftEntity> wide = new ObjectOpenHashSet<>();

    private record Columns(int x0, int z0, int x1, int z1) {
        static Columns of(AABB box) {
            return new Columns(SectionPos.posToSectionCoord(box.minX), SectionPos.posToSectionCoord(box.minZ),
                    SectionPos.posToSectionCoord(box.maxX), SectionPos.posToSectionCoord(box.maxZ));
        }

        boolean local() {
            return x1 - x0 <= 1 && z1 - z0 <= 1;
        }
    }

    void update(CraftEntity craft) {
        Columns next = Columns.of(craft.getBoundingBox());
        if (next.equals(byCraft.get(craft))) return;
        remove(craft);
        byCraft.put(craft, next);
        if (!next.local()) {
            wide.add(craft);
            return;
        }
        for (int x = next.x0; x <= next.x1; x++) {
            for (int z = next.z0; z <= next.z1; z++) {
                byColumn.computeIfAbsent(ChunkPos.asLong(x, z), key -> new ObjectOpenHashSet<>()).add(craft);
            }
        }
    }

    void remove(CraftEntity craft) {
        Columns old = byCraft.remove(craft);
        if (old == null) return;
        if (!old.local()) {
            wide.remove(craft);
            return;
        }
        for (int x = old.x0; x <= old.x1; x++) {
            for (int z = old.z0; z <= old.z1; z++) {
                long key = ChunkPos.asLong(x, z);
                Set<CraftEntity> section = byColumn.get(key);
                section.remove(craft);
                if (section.isEmpty()) byColumn.remove(key);
            }
        }
    }

    boolean anyNear(AABB box, Predicate<CraftEntity> hit) {
        Columns query = Columns.of(box);
        if (query.x1 - query.x0 > 16 || query.z1 - query.z0 > 16) {
            for (CraftEntity craft : byCraft.keySet()) if (hit.test(craft)) return true;
            return false;
        }
        for (int x = query.x0; x <= query.x1; x++) {
            for (int z = query.z0; z <= query.z1; z++) {
                Set<CraftEntity> section = byColumn.get(ChunkPos.asLong(x, z));
                if (section != null) {
                    for (CraftEntity craft : section) if (hit.test(craft)) return true;
                }
            }
        }
        for (CraftEntity craft : wide) if (hit.test(craft)) return true;
        return false;
    }
}
