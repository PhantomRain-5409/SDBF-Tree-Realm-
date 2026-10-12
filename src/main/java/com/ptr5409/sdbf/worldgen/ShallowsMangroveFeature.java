package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.features.TreeFeatures;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.List;
import java.util.Random;

/** 浅滩密集候选网格，调用原版红树生成器并避让建筑。 */
final class ShallowsMangroveFeature {
    private static final int CELL=24;

    static BlockPos candidate(WorldTreeField field,int cellX,int cellZ){
        Random random=new Random(field.mangroveSeed(cellX,cellZ));
        if(random.nextDouble()>.95)return null;
        int x=cellX*CELL+8+random.nextInt(9),z=cellZ*CELL+8+random.nextInt(9);
        int floor=field.floorAt(x,z);
        if(floor<field.seaLevel-8||floor>field.seaLevel-2||field.vegetationAt(x,z)<.20)return null;
        for(int dz=-4;dz<=4;dz+=4)for(int dx=-4;dx<=4;dx+=4)
            if(Math.abs(field.floorAt(x+dx,z+dz)-floor)>4)return null;
        return new BlockPos(x,field.seaLevel,z);
    }

    static boolean place(WorldGenLevel level,WorldTreeSeaChunkGenerator generator,BlockPos at,List<BoundingBox> protectedBoxes){
        BoundingBox bounds=new BoundingBox(at.getX()-16,at.getY()-10,at.getZ()-16,at.getX()+16,at.getY()+35,at.getZ()+16);
        for(BoundingBox box:protectedBoxes)if(bounds.intersects(box))return false;
        var random=RandomSource.create(generator.field().pointSeed(at.getX(),0x4d414e47,at.getZ()));
        var key=random.nextFloat()<.15?TreeFeatures.TALL_MANGROVE:TreeFeatures.MANGROVE;
        return level.registryAccess().registryOrThrow(Registries.CONFIGURED_FEATURE)
                .getHolderOrThrow(key).value().place(level,generator,random,at);
    }

    static void decorate(WorldGenLevel level,WorldTreeSeaChunkGenerator generator,ChunkPos chunk,List<BoundingBox> protectedBoxes){
        for(int cellX=Math.floorDiv(chunk.getMinBlockX(),CELL);cellX<=Math.floorDiv(chunk.getMaxBlockX(),CELL);cellX++)
            for(int cellZ=Math.floorDiv(chunk.getMinBlockZ(),CELL);cellZ<=Math.floorDiv(chunk.getMaxBlockZ(),CELL);cellZ++){
                BlockPos at=candidate(generator.field(),cellX,cellZ);
                if(at!=null&&(at.getX()>>4)==chunk.x&&(at.getZ()>>4)==chunk.z)place(level,generator,at,protectedBoxes);
            }
    }

    private ShallowsMangroveFeature(){}
}
