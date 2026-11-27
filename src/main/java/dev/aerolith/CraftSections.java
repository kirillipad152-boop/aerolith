package dev.aerolith;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.AABB;

/**
 * Spatial index of crafts by 16x16x16 world section (SectionPos -> residents) so collisions,
 * raycasts, fluids, and placement checks touch only nearby hulls instead of scanning every craft.
 */
final class CraftSections {
    private static final int MAX_LOCAL_SECTIONS = 64;

    private final Map<CraftEntity, Bounds> byCraft = new IdentityHashMap<>();
    private final Long2ObjectOpenHashMap<ObjectOpenHashSet<CraftEntity>> bySection = new Long2ObjectOpenHashMap<>();
    private final ObjectOpenHashSet<CraftEntity> wide = new ObjectOpenHashSet<>();
    private final ThreadLocal<ReferenceOpenHashSet<CraftEntity>> seenScratch = ThreadLocal.withInitial(ReferenceOpenHashSet::new);

    private record Bounds(int x0, int y0, int z0, int x1, int y1, int z1) {
        static Bounds of(AABB box) {
            return new Bounds(
                    SectionPos.blockToSectionCoord(box.minX),
                    SectionPos.blockToSectionCoord(box.minY),
                    SectionPos.blockToSectionCoord(box.minZ),
                    SectionPos.blockToSectionCoord(box.maxX),
                    SectionPos.blockToSectionCoord(box.maxY),
                    SectionPos.blockToSectionCoord(box.maxZ));
        }

        boolean local() {
            return (long) (x1 - x0 + 1) * (y1 - y0 + 1) * (z1 - z0 + 1) <= MAX_LOCAL_SECTIONS;
        }
    }

    void update(CraftEntity craft) {
        if (craft.isRemoved() || craft.hull() == null) {
            remove(craft);
            return;
        }
        Bounds next = Bounds.of(craft.getBoundingBox());
        Bounds old = byCraft.get(craft);
        if (next.equals(old)) return;
        removeLinks(craft, old);
        byCraft.put(craft, next);
        if (!next.local()) {
            wide.add(craft);
            return;
        }
        for (int x = next.x0; x <= next.x1; x++) {
            for (int y = next.y0; y <= next.y1; y++) {
                for (int z = next.z0; z <= next.z1; z++) {
                    bySection.computeIfAbsent(SectionPos.asLong(x, y, z), key -> new ObjectOpenHashSet<>()).add(craft);
                }
            }
        }
    }

    void remove(CraftEntity craft) {
        Bounds old = byCraft.remove(craft);
        if (old != null) removeLinks(craft, old);
    }

    private void removeLinks(CraftEntity craft, Bounds old) {
        if (old == null) return;
        if (!old.local()) {
            wide.remove(craft);
            return;
        }
        for (int x = old.x0; x <= old.x1; x++) {
            for (int y = old.y0; y <= old.y1; y++) {
                for (int z = old.z0; z <= old.z1; z++) {
                    long key = SectionPos.asLong(x, y, z);
                    ObjectOpenHashSet<CraftEntity> section = bySection.get(key);
                    if (section != null) {
                        section.remove(craft);
                        if (section.isEmpty()) bySection.remove(key);
                    }
                }
            }
        }
    }

    boolean anyNear(AABB box, Predicate<CraftEntity> hit) {
        if (bySection.isEmpty() && wide.isEmpty()) return false;
        int x0 = SectionPos.blockToSectionCoord(box.minX);
        int y0 = SectionPos.blockToSectionCoord(box.minY);
        int z0 = SectionPos.blockToSectionCoord(box.minZ);
        int x1 = SectionPos.blockToSectionCoord(box.maxX);
        int y1 = SectionPos.blockToSectionCoord(box.maxY);
        int z1 = SectionPos.blockToSectionCoord(box.maxZ);
        if (x0 == x1 && y0 == y1 && z0 == z1 && wide.isEmpty()) {
            ObjectOpenHashSet<CraftEntity> section = bySection.get(SectionPos.asLong(x0, y0, z0));
            if (section == null) return false;
            for (CraftEntity craft : section) {
                if (hit.test(craft)) return true;
            }
            return false;
        }
        ReferenceOpenHashSet<CraftEntity> seen = seenScratch.get();
        seen.clear();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    ObjectOpenHashSet<CraftEntity> section = bySection.get(SectionPos.asLong(x, y, z));
                    if (section == null) continue;
                    for (CraftEntity craft : section) {
                        if (seen.add(craft) && hit.test(craft)) {
                            seen.clear();
                            return true;
                        }
                    }
                }
            }
        }
        for (CraftEntity craft : wide) {
            if (hit.test(craft)) {
                seen.clear();
                return true;
            }
        }
        seen.clear();
        return false;
    }

    void forEachNear(AABB box, Consumer<CraftEntity> visit) {
        if (bySection.isEmpty() && wide.isEmpty()) return;
        int x0 = SectionPos.blockToSectionCoord(box.minX);
        int y0 = SectionPos.blockToSectionCoord(box.minY);
        int z0 = SectionPos.blockToSectionCoord(box.minZ);
        int x1 = SectionPos.blockToSectionCoord(box.maxX);
        int y1 = SectionPos.blockToSectionCoord(box.maxY);
        int z1 = SectionPos.blockToSectionCoord(box.maxZ);
        if (x0 == x1 && y0 == y1 && z0 == z1 && wide.isEmpty()) {
            ObjectOpenHashSet<CraftEntity> section = bySection.get(SectionPos.asLong(x0, y0, z0));
            if (section != null) {
                for (CraftEntity craft : section) visit.accept(craft);
            }
            return;
        }
        ReferenceOpenHashSet<CraftEntity> seen = seenScratch.get();
        seen.clear();
        for (int x = x0; x <= x1; x++) {
            for (int y = y0; y <= y1; y++) {
                for (int z = z0; z <= z1; z++) {
                    ObjectOpenHashSet<CraftEntity> section = bySection.get(SectionPos.asLong(x, y, z));
                    if (section == null) continue;
                    for (CraftEntity craft : section) {
                        if (seen.add(craft)) visit.accept(craft);
                    }
                }
            }
        }
        for (CraftEntity craft : wide) visit.accept(craft);
        seen.clear();
    }
}
