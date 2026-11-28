package dev.aerolith.api;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

/**
 * NeoForge bus events and high-frequency physics step listener hooks for Aerolith crafts.
 */
public final class AerolithEvents {
    private static final List<StepListener> POST_STEP_LISTENERS = new CopyOnWriteArrayList<>();

    private AerolithEvents() {}

    @FunctionalInterface
    public interface StepListener {
        /** Invoked after {@code level}'s native physics tick finishes applying poses. */
        void onPostStep(ServerLevel level);
    }

    public static void addPostStepListener(StepListener listener) {
        POST_STEP_LISTENERS.add(Objects.requireNonNull(listener, "listener"));
    }

    public static void removePostStepListener(StepListener listener) {
        POST_STEP_LISTENERS.remove(listener);
    }

    public static boolean hasPostStepListeners() {
        return !POST_STEP_LISTENERS.isEmpty();
    }

    public static void dispatchPostStep(ServerLevel level) {
        if (POST_STEP_LISTENERS.isEmpty()) return;
        for (StepListener listener : POST_STEP_LISTENERS) {
            listener.onPostStep(level);
        }
    }

    /** Fired on {@code NeoForge.EVENT_BUS} after a craft has been assembled or spawned into a level. */
    public static final class CraftAssembledEvent extends Event {
        private final ServerLevel level;
        private final CraftHandle craft;
        @Nullable private final ServerPlayer actor;

        public CraftAssembledEvent(ServerLevel level, CraftHandle craft, @Nullable ServerPlayer actor) {
            this.level = level;
            this.craft = craft;
            this.actor = actor;
        }

        public ServerLevel level() { return level; }
        public CraftHandle craft() { return craft; }
        @Nullable public ServerPlayer actor() { return actor; }
    }

    /** Fired on {@code NeoForge.EVENT_BUS} when a disconnected fragment breaks off an existing craft. */
    public static final class CraftSplitEvent extends Event {
        private final ServerLevel level;
        private final CraftHandle parent;
        private final CraftHandle fragment;

        public CraftSplitEvent(ServerLevel level, CraftHandle parent, CraftHandle fragment) {
            this.level = level;
            this.parent = parent;
            this.fragment = fragment;
        }

        public ServerLevel level() { return level; }
        public CraftHandle parent() { return parent; }
        public CraftHandle fragment() { return fragment; }
    }

    /** Cancellable event fired before a craft docks and turns back into world blocks. */
    public static final class CraftDisassembleEvent extends Event implements ICancellableEvent {
        private final ServerLevel level;
        private final CraftHandle craft;
        @Nullable private final ServerPlayer actor;

        public CraftDisassembleEvent(ServerLevel level, CraftHandle craft, @Nullable ServerPlayer actor) {
            this.level = level;
            this.craft = craft;
            this.actor = actor;
        }

        public ServerLevel level() { return level; }
        public CraftHandle craft() { return craft; }
        @Nullable public ServerPlayer actor() { return actor; }
    }
}
