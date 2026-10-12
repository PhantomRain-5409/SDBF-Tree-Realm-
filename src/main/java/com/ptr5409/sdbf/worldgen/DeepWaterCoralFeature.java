package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.configurations.NoneFeatureConfiguration;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.List;
import java.util.Random;

/** 深水海床按连续噪声带在海沟深处生成原版珊瑚群。 */
final class DeepWaterCoralFeature {
    private static final int CELL=16;

    static BlockPos candidate(WorldTreeField field,int cx,int cz){
        Random random=new Random(field.pointSeed(cx,0x434f5241,cz));
        if(random.nextDouble()>.38)return null;
        for(int attempt=0;attempt<8;attempt++){
            int x=cx*CELL+random.nextInt(CELL),z=cz*CELL+random.nextInt(CELL);
            var column=field.column(x,z);
            if(suitable(field,x,z)&&column.kind(column.floor+1)==WorldTreeField.Kind.WATER)return new BlockPos(x,column.floor+1,z);
        }
        return null;
    }

    static boolean suitable(WorldTreeField field,int x,int z){
        int floor=field.floorAt(x,z);
        if(floor>field.seaLevel-24)return false;
        double density=field.coralDensityAt(x,z);
        return field.narrowTrenchAt(x,z)?density>.42:floor<=field.seaLevel-48&&density>.76;
    }

    static void decorate(WorldGenLevel level,WorldTreeSeaChunkGenerator generator,ChunkPos chunk,List<BoundingBox> protectedBoxes){
        BlockPos at=candidate(generator.field(),chunk.x,chunk.z);
        if(at==null)return;
        RandomSource random=RandomSource.create(generator.field().pointSeed(at.getX(),0x52454546,at.getZ()));
        int count=generator.field().narrowTrenchAt(at.getX(),at.getZ())?6+random.nextInt(4):1+random.nextInt(2);
        for(int i=0;i<count;i++){
            int x=chunk.getMinBlockX()+random.nextInt(16),z=chunk.getMinBlockZ()+random.nextInt(16);
            if(i==0){x=at.getX();z=at.getZ();}
            var column=generator.field().column(x,z);
            if(!suitable(generator.field(),x,z))continue;
            BlockPos origin=new BlockPos(x,column.floor+1,z);
            BoundingBox bounds=new BoundingBox(x-8,origin.getY()-1,z-8,x+8,origin.getY()+12,z+8);
            boolean allowed=true;
            for(BoundingBox box:protectedBoxes)if(bounds.intersects(box)){allowed=false;break;}
            if(!allowed)continue;
            for(int dy=0;dy<10;dy++){
                BlockPos plant=origin.above(dy);var state=level.getBlockState(plant);
                if(state.is(net.minecraft.world.level.block.Blocks.KELP)||state.is(net.minecraft.world.level.block.Blocks.KELP_PLANT)
                        ||state.is(net.minecraft.world.level.block.Blocks.SEAGRASS)||state.is(net.minecraft.world.level.block.Blocks.TALL_SEAGRASS))
                    level.setBlock(plant,net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(),2);
            }
            var feature=switch(random.nextInt(3)){
                case 0->Feature.CORAL_TREE;
                case 1->Feature.CORAL_CLAW;
                default->Feature.CORAL_MUSHROOM;
            };
            feature.place(NoneFeatureConfiguration.INSTANCE,level,generator,random,origin);
        }
    }

    private DeepWaterCoralFeature(){}
}
