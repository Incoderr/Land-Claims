package com.landclaims;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.landclaims.command.Msg;
import com.landclaims.data.ClaimData;
import com.landclaims.data.Team;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.DispensibleContainerItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.piston.PistonStructureResolver;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.event.level.PistonEvent;
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

    /**
     * Buckets pick their target with their own ray trace, not through a block click,
     * so check both the block they hit (picking up) and the one in front of it (pouring).
     */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onUseItem(PlayerInteractEvent.RightClickItem event) {
        if (!(event.getItemStack().getItem() instanceof DispensibleContainerItem)) {
            return;
        }
        Player player = event.getEntity();
        BlockHitResult hit = Item.getPlayerPOVHitResult(event.getLevel(), player, ClipContext.Fluid.SOURCE_ONLY);
        if (hit.getType() != HitResult.Type.BLOCK) {
            return;
        }
        BlockPos target = hit.getBlockPos();
        if (denied(player, event.getLevel(), target) || denied(player, event.getLevel(), target.relative(hit.getDirection()))) {
            event.setCancellationResult(InteractionResult.FAIL);
            event.setCanceled(true);
        }
    }

    /**
     * True if something moving from {@code from} into {@code to} would cross into land of another owner:
     * from the wild into a claim, from one team's claim into another's, or out of a claim into the wild.
     * Moves within one chunk or between chunks of the same owner are always fine.
     */
    public static boolean crossesClaimBorder(ServerLevel level, BlockPos from, BlockPos to) {
        ChunkPos fromChunk = ChunkPos.containing(from);
        ChunkPos toChunk = ChunkPos.containing(to);
        if (fromChunk.equals(toChunk)) {
            return false;
        }
        ClaimData data = ClaimData.get(level.getServer());
        return !Objects.equals(data.ownerOf(level.dimension(), fromChunk), data.ownerOf(level.dimension(), toChunk));
    }

    /** Fluids may leave a claim, but not flow into land owned by someone else. */
    public static boolean fluidMayFlow(ServerLevel level, BlockPos from, BlockPos to) {
        ChunkPos fromChunk = ChunkPos.containing(from);
        ChunkPos toChunk = ChunkPos.containing(to);
        if (fromChunk.equals(toChunk)) {
            return true;
        }
        ClaimData data = ClaimData.get(level.getServer());
        UUID target = data.ownerOf(level.dimension(), toChunk);
        return target == null || target.equals(data.ownerOf(level.dimension(), fromChunk));
    }

    /** A piston may not move or break anything in land owned by someone other than the piston's owner. */
    @SubscribeEvent(priority = EventPriority.HIGH)
    public static void onPiston(PistonEvent.Pre event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        BlockPos piston = event.getPos();
        if (event.getPistonMoveType().isExtend && crossesClaimBorder(level, piston, event.getFaceOffsetPos())) {
            event.setCanceled(true);
            return;
        }
        PistonStructureResolver structure = event.getStructureHelper();
        if (structure == null || !structure.resolve()) {
            return;
        }
        Direction push = structure.getPushDirection();
        for (BlockPos pos : structure.getToPush()) {
            if (crossesClaimBorder(level, piston, pos) || crossesClaimBorder(level, piston, pos.relative(push))) {
                event.setCanceled(true);
                return;
            }
        }
        for (BlockPos pos : structure.getToDestroy()) {
            if (crossesClaimBorder(level, piston, pos)) {
                event.setCanceled(true);
                return;
            }
        }
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
