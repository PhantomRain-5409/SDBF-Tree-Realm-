package com.ptr5409.sdbf.command;

import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.ptr5409.sdbf.worldgen.WorldTreeSeaChunkGenerator;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.ChatFormatting;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraftforge.event.RegisterCommandsEvent;

/** 为测试提供根岛安全落点传送。 */
public final class TreeRealmCommand {
    private static final ResourceKey<Level> REALM = ResourceKey.create(
            Registries.DIMENSION, new ResourceLocation("sdbf", "tree_realm"));
    private static final SimpleCommandExceptionType MISSING = new SimpleCommandExceptionType(
            Component.translatable("commands.sdbf.tree_realm.missing"));
    private static final SimpleCommandExceptionType UNSAFE = new SimpleCommandExceptionType(
            Component.translatable("commands.sdbf.tree_realm.unsafe"));
    private static final SimpleCommandExceptionType NO_STUMP = new SimpleCommandExceptionType(
            Component.translatable("commands.sdbf.tree_realm.stump_missing"));
    private static final SimpleCommandExceptionType STUMPS_DISABLED = new SimpleCommandExceptionType(
            Component.translatable("commands.sdbf.tree_realm.stumps_disabled"));

    private TreeRealmCommand() {}

    public static void register(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("tree_realm")
                .requires(source -> source.hasPermission(2))
                .then(Commands.literal("locate_stump")
                    .executes(context -> locateStump(context.getSource(),4096))
                    .then(Commands.argument("radius",IntegerArgumentType.integer(64,8192))
                        .executes(context -> locateStump(context.getSource(),IntegerArgumentType.getInteger(context,"radius")))))
                .executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    ServerLevel target = player.server.getLevel(REALM);
                    if (target == null) throw MISSING.create();
                    BlockPos landing;
                    try {
                        landing = WorldTreeSeaChunkGenerator.findArrival(target, 0, 0);
                    } catch (IllegalStateException error) {
                        throw UNSAFE.create();
                    }
                    target.getChunk(landing);
                    if (!target.getBlockState(landing.below()).isCollisionShapeFullBlock(target, landing.below())
                            || !target.getFluidState(landing).isEmpty()
                            || !target.noCollision(player, player.getBoundingBox().move(
                                    landing.getX() + 0.5 - player.getX(), landing.getY() - player.getY(),
                                    landing.getZ() + 0.5 - player.getZ()))) {
                        throw UNSAFE.create();
                    }
                    player.teleportTo(target, landing.getX() + 0.5, landing.getY(),
                            landing.getZ() + 0.5, player.getYRot(), player.getXRot());
                    player.setDeltaMovement(0, 0, 0);
                    player.fallDistance = 0;
                    context.getSource().sendSuccess(() -> Component.translatable(
                            "commands.sdbf.tree_realm.arrived"), false);
                    return 1;
                }));
    }
    private static int locateStump(CommandSourceStack source,int radius) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerLevel realm=source.getServer().getLevel(REALM);
        if(realm==null||!(realm.getChunkSource().getGenerator() instanceof WorldTreeSeaChunkGenerator generator))throw MISSING.create();
        if(!generator.sunkenStumpsEnabled())throw STUMPS_DISABLED.create();
        BlockPos origin=BlockPos.containing(source.getPosition());
        BlockPos at=generator.findNearestSunkenStump(origin,radius).orElseThrow(NO_STUMP::create);
        Component coordinates=Component.literal("["+at.getX()+", "+at.getY()+", "+at.getZ()+"]").withStyle(style -> style
            .withColor(ChatFormatting.GREEN).withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND,
                "/execute in sdbf:tree_realm run tp @s "+at.getX()+" "+at.getY()+" "+at.getZ()))
            .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,Component.translatable("commands.sdbf.tree_realm.stump_teleport"))));
        int distance=(int)Math.round(Math.hypot(at.getX()-(double)origin.getX(),at.getZ()-(double)origin.getZ()));
        source.sendSuccess(() -> Component.translatable("commands.sdbf.tree_realm.stump_found",coordinates,distance),false);
        return 1;
    }
}
