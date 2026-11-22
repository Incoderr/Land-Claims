package com.landclaims;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.landclaims.command.Msg;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.AttackEntityEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.level.block.BreakBlockEvent;

/** Stops players outside the owning team from changing claimed chunks. Server side only. */
@EventBusSubscriber(modid = LandClaims.MODID)
public final class ClaimProtection {
    /** Don't spam the "claimed" warning: at most once per second per player. */
    private static final int WARN_COOLDOWN_TICKS = 20;
    private static final Map<UUID, Integer> lastWarning = new HashMap<>();

    private ClaimProtection() {
    }

    /** True if the player must not touch this position. Sends a warning when it returns true. */
    private static boolean denied(Player player, LevelAccessor levelAccessor, BlockPos pos) {
        if (!(player instanceof ServerPlayer serverPlayer) || !(levelAccessor instanceof ServerLevel level)) {
            return false;
        }
        ClaimData data = ClaimData.get(level.getServer());
        ChunkPos chunk = ChunkPos.containing(pos);
        if (data.canAccess(player.getUUID(), level.dimension(), chunk)) {
            return false;
        }
        warn(serverPlayer, data.teamAt(level.dimension(), chunk));
        return true;
    }

    private static void warn(ServerPlayer player, Team owner) {
        Integer last = lastWarning.get(player.getUUID());
        if (last == null || player.tickCount - last >= WARN_COOLDOWN_TICKS || player.tickCount < last) {
            lastWarning.put(player.getUUID(), player.tickCount);
            player.sendOverlayMessage(Msg.error("protected", owner == null ? "?" : owner.name()));
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onBreak(BreakBlockEvent event) {
        if (denied(event.getPlayer(), event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof Player player && denied(player, event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // Covers chests, doors, buttons, machines, and items used on blocks (buckets, hoes, axes, flint and steel).
        if (denied(event.getEntity(), event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onInteractEntity(PlayerInteractEvent.EntityInteract event) {
        // Item frames, armor stands, animals, villagers, minecarts with chests.
        if (isProtectedEntity(event.getTarget()) && denied(event.getEntity(), event.getLevel(), event.getTarget().blockPosition())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onAttack(AttackEntityEvent event) {
        Entity target = event.getTarget();
        if (isProtectedEntity(target) && denied(event.getEntity(), target.level(), target.blockPosition())) {
            event.setCanceled(true);
        }
    }

    /** Monsters and players can always be hit. Everything else (animals, frames, stands) is protected. */
    private static boolean isProtectedEntity(Entity entity) {
        return !(entity instanceof Enemy) && !(entity instanceof Player);
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onTrample(BlockEvent.FarmlandTrampleEvent event) {
        if (event.getEntity() instanceof Player player && denied(player, event.getLevel(), event.getPos())) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onExplosion(ExplosionEvent.Detonate event) {
        if (!LandClaimsConfig.PROTECT_FROM_EXPLOSIONS.getAsBoolean() || !(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ClaimData data = ClaimData.get(level.getServer());
        var dimension = level.dimension();
        event.getAffectedBlocks().removeIf(pos -> data.ownerOf(dimension, ChunkPos.containing(pos)) != null);
    }

    @SubscribeEvent
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        Player player = event.getEntity();
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            ClaimData.get(server).rememberName(player.getUUID(), player.getGameProfile().name());
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        lastWarning.remove(event.getEntity().getUUID());
    }
}
