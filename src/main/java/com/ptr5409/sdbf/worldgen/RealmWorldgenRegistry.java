package com.ptr5409.sdbf.worldgen;

import com.mojang.serialization.Codec;
import com.ptr5409.sdbf.TreeRealmMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.structure.StructureType;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePieceType;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

/** 仅注册树维度需要的生成器和建筑类型。 */
public final class RealmWorldgenRegistry {
    private static final DeferredRegister<Codec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, TreeRealmMod.MOD_ID);
    private static final DeferredRegister<Codec<? extends BiomeSource>> BIOMES =
            DeferredRegister.create(Registries.BIOME_SOURCE, TreeRealmMod.MOD_ID);
    private static final DeferredRegister<StructureType<?>> STRUCTURES =
            DeferredRegister.create(Registries.STRUCTURE_TYPE, TreeRealmMod.MOD_ID);
    private static final DeferredRegister<StructurePieceType> PIECES =
            DeferredRegister.create(Registries.STRUCTURE_PIECE, TreeRealmMod.MOD_ID);

    public static final RegistryObject<Codec<WorldTreeSeaBiomeSource>> WORLD_TREE_SEA_BIOMES =
            BIOMES.register("world_tree_sea", () -> WorldTreeSeaBiomeSource.CODEC);
    public static final RegistryObject<Codec<WorldTreeSeaChunkGenerator>> WORLD_TREE_SEA_GENERATOR =
            GENERATORS.register("world_tree_sea", () -> WorldTreeSeaChunkGenerator.CODEC);
    public static final RegistryObject<StructureType<WorldTreeSmallStructure>> WORLD_TREE_SMALL =
            STRUCTURES.register("world_tree_small", () -> () -> WorldTreeSmallStructure.CODEC);
    public static final RegistryObject<StructurePieceType> WORLD_TREE_SMALL_PIECE =
            PIECES.register("world_tree_small", () -> WorldTreeSmallStructure.Piece::new);
    public static final RegistryObject<StructureType<WorldTreeDungeonStructure>> WORLD_TREE_DUNGEON =
            STRUCTURES.register("world_tree_dungeon", () -> () -> WorldTreeDungeonStructure.CODEC);
    public static final RegistryObject<StructurePieceType> WORLD_TREE_DUNGEON_PIECE =
            PIECES.register("world_tree_dungeon", () -> WorldTreeDungeonStructure.Piece::new);

    private RealmWorldgenRegistry() {}

    public static void register(IEventBus bus) {
        GENERATORS.register(bus);
        BIOMES.register(bus);
        STRUCTURES.register(bus);
        PIECES.register(bus);
    }
}
