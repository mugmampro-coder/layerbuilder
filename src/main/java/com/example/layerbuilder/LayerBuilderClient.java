package com.example.layerbuilder;

import com.mojang.blaze3d.platform.InputConstants;
import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.world.SchematicWorldHandler;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LayerBuilderClient implements ClientModInitializer {
    private static final double REACH = 4.5;
    private static final int TICKS_BETWEEN_PLACEMENTS = 2;
    private static final int MAX_CANDIDATES_TRIED_PER_TICK = 8;

    private static final Set<String> IGNORED = Set.of(
            "powered", "lit", "waterlogged", "distance", "persistent", "open", "triggered",
            "occupied", "bottom", "unstable", "attached",
            "north", "east", "south", "west", "up", "down");

    private static final float[][] ROTATIONS = {
            {0f, 0f}, {90f, 0f}, {180f, 0f}, {270f, 0f}, {0f, 90f}, {0f, -90f}};

    private record Candidate(BlockPos pos, BlockState want, int slot, double score) {}
    private record Plan(BlockHitResult hit, float yaw, float pitch, int slot) {}

    private static KeyMapping toggleKey;
    private static boolean enabled = false;
    private static int cooldown = 0;
    private static int tickCounter = 0;
    private static final Set<Long> failed = new HashSet<>();

    @Override
    public void onInitializeClient() {
        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyMapping(
                "key.layerbuilder.toggle", InputConstants.Type.KEYSYM, GLFW.GLFW_KEY_K, KeyMapping.Category.MISC));
        ClientTickEvents.END_CLIENT_TICK.register(LayerBuilderClient::tick);
    }

    private static void tick(Minecraft mc) {
        while (toggleKey.consumeClick()) {
            enabled = !enabled;
            failed.clear();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("Layer Builder: " + (enabled ? "ON" : "OFF")), true);
            }
        }
        LocalPlayer player = mc.player;
        ClientLevel level = mc.level;
        if (!enabled || player == null || level == null || mc.gameMode == null || mc.screen != null) return;

        if (++tickCounter % 40 == 0) failed.clear();
        if (--cooldown > 0) return;
        cooldown = TICKS_BETWEEN_PLACEMENTS;

        var schematic = SchematicWorldHandler.getSchematicWorld();
        var range = DataManager.getRenderLayerRange();
        if (schematic == null || range == null) return;

        Vec3 eye = player.getEyePosition();
        BlockPos base = player.blockPosition();
        int r = (int) Math.ceil(REACH);
        List<Candidate> candidates = new ArrayList<>();

        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos pos = base.offset(dx, dy, dz);
                    if (failed.contains(pos.asLong())) continue;
                    double dist = eye.distanceToSqr(Vec3.atCenterOf(pos));
                    if (dist > REACH * REACH) continue;
                    if (!range.isPositionWithinRange(pos)) continue;

                    BlockState want = schematic.getBlockState(pos);
                    if (want.isAir()) continue;
                    if (!level.getBlockState(pos).isAir()) continue;

                    Item item = want.getBlock().asItem();
                    if (item == Items.AIR) continue;
                    int slot = findHotbarSlot(player, item);
                    if (slot < 0) continue;

                    candidates.add(new Candidate(pos, want, slot, pos.getY() * 1000.0 + dist));
                }
            }
        }
        candidates.sort(Comparator.comparingDouble(Candidate::score));

        int tries = 0;
        for (Candidate c : candidates) {
            if (tries++ >= MAX_CANDIDATES_TRIED_PER_TICK) break;
            Plan plan = findPlan(level, player, c);
            if (plan == null) {
                failed.add(c.pos().asLong());
                continue;
            }
            execute(mc, player, plan);
            return;
        }
    }

    private static Plan findPlan(ClientLevel level, LocalPlayer player, Candidate c) {
        BlockPos target = c.pos();
        Block block = c.want().getBlock();
        ItemStack stack = player.getInventory().getItem(c.slot());
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        try {
            for (Direction d : Direction.values()) {
                BlockPos nb = target.relative(d);
                if (!isSafeSupport(level, nb)) continue;
                Direction side = d.getOpposite();

                boolean horizontal = side.getAxis().isHorizontal();
                double[] heights = horizontal ? new double[]{0.25, 0.75} : new double[]{0.5};
                for (double fy : heights) {
                    Vec3 hitVec = horizontal
                            ? new Vec3(nb.getX() + 0.5 + side.getStepX() * 0.5, nb.getY() + fy, nb.getZ() + 0.5 + side.getStepZ() * 0.5)
                            : new Vec3(nb.getX() + 0.5, nb.getY() + 0.5 + side.getStepY() * 0.5, nb.getZ() + 0.5);
                    BlockHitResult hit = new BlockHitResult(hitVec, side, nb, false);

                    for (float[] rot : ROTATIONS) {
                        player.setYRot(rot[0]);
                        player.setXRot(rot[1]);
                        BlockPlaceContext ctx = new BlockPlaceContext(player, InteractionHand.MAIN_HAND, stack, hit);
                        if (!ctx.canPlace() || !ctx.getClickedPos().equals(target)) continue;

                        BlockState result = block.getStateForPlacement(ctx);
                        if (result == null || !matches(c.want(), result)) continue;
                        if (!result.canSurvive(level, target)) continue;
                        if (!level.isUnobstructed(result, target, CollisionContext.of(player))) continue;
                        return new Plan(hit, rot[0], rot[1], c.slot());
                    }
                }
            }
        } finally {
            player.setYRot(oldYaw);
            player.setXRot(oldPitch);
        }
        return null;
    }

    private static void execute(Minecraft mc, LocalPlayer player, Plan plan) {
        var conn = mc.getConnection();
        if (conn == null) return;
        float oldYaw = player.getYRot();
        float oldPitch = player.getXRot();
        int oldSlot = player.getInventory().getSelectedSlot();

        player.setYRot(plan.yaw());
        player.setXRot(plan.pitch());
        conn.send(new ServerboundMovePlayerPacket.Rot(plan.yaw(), plan.pitch(), player.onGround(), player.horizontalCollision));
        player.getInventory().setSelectedSlot(plan.slot());

        InteractionResult result = mc.gameMode.useItemOn(player, InteractionHand.MAIN_HAND, plan.hit());
        if (result.consumesAction()) player.swing(InteractionHand.MAIN_HAND);

        player.getInventory().setSelectedSlot(oldSlot);
        player.setYRot(oldYaw);
        player.setXRot(oldPitch);
        conn.send(new ServerboundMovePlayerPacket.Rot(oldYaw, oldPitch, player.onGround(), player.horizontalCollision));
    }

    private static boolean matches(BlockState want, BlockState got) {
        if (want.getBlock() != got.getBlock()) return false;
        boolean stairs = want.getBlock() instanceof StairBlock;
        for (Property<?> p : want.getProperties()) {
            String name = p.getName();
            if (IGNORED.contains(name)) continue;
            if (stairs && name.equals("shape")) continue;
            if (!got.hasProperty(p)) return false;
            if (!want.getValue(p).equals(got.getValue(p))) return false;
        }
        return true;
    }

    private static boolean isSafeSupport(ClientLevel level, BlockPos pos) {
        BlockState s = level.getBlockState(pos);
        if (s.isAir() || !s.getFluidState().isEmpty() || s.canBeReplaced()) return false;
        if (!s.isCollisionShapeFullBlock(level, pos)) return false;
        if (level.getBlockEntity(pos) != null) return false;
        Block b = s.getBlock();
        return b != Blocks.CRAFTING_TABLE && b != Blocks.NOTE_BLOCK;
    }

    private static int findHotbarSlot(LocalPlayer player, Item item) {
        for (int i = 0; i < 9; i++) {
            ItemStack s = player.getInventory().getItem(i);
            if (!s.isEmpty() && s.getItem() == item) return i;
        }
        return -1;
    }
        }
