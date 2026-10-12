package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.chunk.ChunkAccess;

/** 泥滩干湿交界的小型植物，只写当前区块。 */
final class MudflatPlants {
    static boolean mudPatch(WorldTreeField field,int x,int z){
        int floor=field.floorAt(x,z);
        return floor>=field.seaLevel-3&&floor<field.seaLevel&&field.vegetationAt(x,z)>.60
            &&(field.pointSeed(x,0,z)&Long.MAX_VALUE)%100<18;
    }
    static boolean continuousShore(WorldTreeField field,int x,int z){
        int connected=0,area=0;
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            int floor=field.floorAt(x+dx,z+dz);
            if(floor>=field.seaLevel-1||mudPatch(field,x+dx,z+dz)){
                area++;if(Math.abs(dx)+Math.abs(dz)==1)connected++;
            }
        }
        return connected>=2&&area>=5;
    }
    static boolean shoreFlowers(WorldTreeField field,int x,int z,long hash){
        return continuousShore(field,x,z)&&(hash>>>36)%100<45;
    }
    static void dry(WorldTreeField field,WorldGenLevel level,ChunkAccess chunk,BlockPos pos,long hash){
        int choice=(int)((hash>>>12)%100);
        if(choice<54&&!shoreFlowers(field,pos.getX(),pos.getZ(),hash))return;
        BlockState plant=(choice<30?Blocks.BROWN_MUSHROOM:choice<54?Blocks.LILY_OF_THE_VALLEY:
                choice<58?Blocks.FLOWERING_AZALEA:choice<63?Blocks.BIG_DRIPLEAF:Blocks.MOSS_CARPET).defaultBlockState();
        if(plant.is(Blocks.BIG_DRIPLEAF)){
            int height=1+(int)((hash>>>23)%3);
            for(int dy=0;dy<height;dy++)if(!chunk.getBlockState(pos.above(dy)).isAir())return;
            BlockState below=chunk.getBlockState(pos.below());
            if(!below.is(net.minecraft.tags.BlockTags.BIG_DRIPLEAF_PLACEABLE))return;
            Direction face=Direction.from2DDataValue((int)(hash&3));
            for(int dy=0;dy<height;dy++)chunk.setBlockState(pos.above(dy),
                (dy==height-1?Blocks.BIG_DRIPLEAF:Blocks.BIG_DRIPLEAF_STEM).defaultBlockState()
                    .setValue(BlockStateProperties.HORIZONTAL_FACING,face).setValue(BlockStateProperties.WATERLOGGED,false),false);
            return;
        }
        if(!chunk.getBlockState(pos).isAir())return;
        if(plant.is(Blocks.BROWN_MUSHROOM))mushroom(level,chunk,pos);
        else if(plant.canSurvive(level,pos))chunk.setBlockState(pos,plant,false);
    }
    static boolean mushroom(WorldGenLevel level,ChunkAccess chunk,BlockPos pos){
        BlockState mushroom=Blocks.BROWN_MUSHROOM.defaultBlockState();
        BlockState support=chunk.getBlockState(pos.below());
        boolean change=support.is(Blocks.MUD)&&!mushroom.canSurvive(level,pos);
        if(change)chunk.setBlockState(pos.below(),Blocks.PODZOL.defaultBlockState(),false);
        if(mushroom.canSurvive(level,pos)){chunk.setBlockState(pos,mushroom,false);return true;}
        if(change)chunk.setBlockState(pos.below(),support,false);
        return false;
    }
    static boolean wet(WorldTreeField field,WorldGenLevel level,ChunkAccess chunk,BlockPos pos,long hash){
        if(pos.getY()<field.seaLevel-3||!chunk.getBlockState(pos).is(Blocks.WATER)||(hash>>>28)%100>=9)return false;
        boolean shore=false;
        for(Direction direction:Direction.Plane.HORIZONTAL){
            int x=pos.getX()+direction.getStepX(),z=pos.getZ()+direction.getStepZ();
            if(mudPatch(field,x,z)||field.floorAt(x,z)>=field.seaLevel-1){shore=true;break;}
        }
        if(!shore)return false;
        if((hash>>>16)%3==0){
            var upper=pos.above();
            if(!chunk.getBlockState(upper).isAir()&&!chunk.getBlockState(upper).is(Blocks.WATER))return false;
            BlockState lower=Blocks.SMALL_DRIPLEAF.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING,Direction.from2DDataValue((int)(hash&3)))
                .setValue(BlockStateProperties.WATERLOGGED,true).setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER);
            if(!lower.canSurvive(level,pos))return false;
            BlockState top=lower.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER)
                .setValue(BlockStateProperties.WATERLOGGED,chunk.getBlockState(upper).is(Blocks.WATER));
            chunk.setBlockState(pos,lower,false);chunk.setBlockState(upper,top,false);return true;
        }
        BlockState pickle=Blocks.SEA_PICKLE.defaultBlockState().setValue(BlockStateProperties.PICKLES,1+(int)((hash>>>18)%2));
        if(!pickle.canSurvive(level,pos))return false;
        chunk.setBlockState(pos,pickle,false);return true;
    }
    private MudflatPlants(){}
}
