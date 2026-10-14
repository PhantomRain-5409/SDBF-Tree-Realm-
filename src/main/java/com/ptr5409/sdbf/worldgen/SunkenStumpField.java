package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 试验性沉没巨桩几何，逐列合并到当前区块，不跨区块写入。 */
final class SunkenStumpField {
    static final int CELL=512,REACH=360;
    record Pool(Set<Long> water,Set<Long> bank){
        static long key(int x,int z){return ((long)x<<32)^(z&0xffffffffL);}
        boolean contains(int x,int z){return water.contains(key(x,z));}
        boolean shore(int x,int z){return bank.contains(key(x,z));}
    }
    record Stump(int x,int z,int floor,int top,int radius,long seed,WorldTreeField.Tree tree,List<Pool> pools){
        BlockPos cutPosition(){
            var center=tree.trunkCenter(top);
            return new BlockPos((int)Math.round(center.x()),top+3,(int)Math.round(center.z()));
        }
        double unit(int px,int pz,int salt){
            return (WorldTreeField.mix(seed^((long)px*341873128712L)^((long)pz*132897987541L)^salt)>>>11)*0x1.0p-53;
        }
        double patch(int px,int pz,int size,int salt){
            int cx=Math.floorDiv(px,size),cz=Math.floorDiv(pz,size);
            double dx=Math.floorMod(px,size)/(double)size,dz=Math.floorMod(pz,size)/(double)size;
            dx=dx*dx*(3-2*dx);dz=dz*dz*(3-2*dz);
            double a=unit(cx,cz,salt)*(1-dx)+unit(cx+1,cz,salt)*dx;
            double b=unit(cx,cz+1,salt)*(1-dx)+unit(cx+1,cz+1,salt)*dx;
            return a*(1-dz)+b*dz;
        }
        int cutHeight(int px,int pz){
            if(waterAt(px,pz))return top-1;
            var center=tree.trunkCenter(top);
            int height=top;
            if(Math.hypot(px-center.x(),pz-center.z())<=radius*.64){
                double relief=patch(px,pz,5,0x435554);
                height+=relief<.32?-1:relief>.68?1:0;
            }
            for(var pool:pools)if(pool.shore(px,pz))return Math.max(top,height);
            return height;
        }
        boolean waterAt(int px,int pz){
            for(var pool:pools)if(pool.contains(px,pz))return true;
            return false;
        }
    }
    record Span(int low,int high){}
    record Column(int top,BlockState surface,BlockState underSurface,BlockState plant,List<Span> wood){
        boolean woodAt(int y){
            for(var span:wood)if(y>=span.low&&y<=span.high)return true;
            return false;
        }
        BlockState state(int y,BlockState trunkWood){
            if(top!=Integer.MIN_VALUE){
                if(y==top+1)return plant;
                if(y==top)return surface;
            }
            if(!woodAt(y))return null;
            if(y==top-1&&underSurface!=null)return underSurface;
            return top!=Integer.MIN_VALUE&&y>=top-5?Blocks.STRIPPED_DARK_OAK_LOG.defaultBlockState():trunkWood;
        }
    }
    private final WorldTreeField field;
    private final ConcurrentHashMap<Long,Optional<Stump>> cache=new ConcurrentHashMap<>();
    SunkenStumpField(WorldTreeField field){this.field=field;}
    Stump candidate(int cx,int cz){
        long key=((long)cx<<32)^(cz&0xffffffffL);
        var result=cache.computeIfAbsent(key,k->Optional.ofNullable(create(cx,cz)));
        if(cache.size()>256){int n=0;for(long old:cache.keySet()){cache.remove(old);if(++n==32)break;}}
        return result.orElse(null);
    }
    private Stump create(int cx,int cz){
        Random random=new Random(field.pointSeed(cx,0x5354554d,cz));
        int x=0,z=0,floor=0;boolean found=false;
        int top=field.seaLevel+2+random.nextInt(17);long shapeSeed=random.nextLong();
        for(int attempt=0;attempt<8;attempt++){
            x=cx*CELL+48+random.nextInt(CELL-96);z=cz*CELL+48+random.nextInt(CELL-96);
            floor=field.floorAt(x,z);
            if(floor<=field.seaLevel-40&&oceanGap(x,z,45,top)){found=true;break;}
        }
        if(!found)return null;
        var tree=field.sunkenTree(x,z,floor,shapeSeed);
        tree.prepareGeometry();
        return new Stump(x,z,floor,top,(int)Math.ceil(tree.radius),shapeSeed,tree,createPools(tree,top,random));
    }
    /** 断面随机积水：连通的不规则浅坑，检查池底、池岸后才保留。 */
    private List<Pool> createPools(WorldTreeField.Tree tree,int top,Random random){
        int roll=random.nextInt(100),count=roll<32?0:roll<82?1:roll<97?2:3;
        List<Pool> pools=new ArrayList<>();Set<Long> occupied=new HashSet<>();
        var center=tree.trunkCenter(top);
        for(int i=0;i<count;i++){
            double angle=random.nextDouble()*Math.PI*2,distance=Math.sqrt(random.nextDouble())*tree.radius*.60;
            int px=(int)Math.round(center.x()+Math.cos(angle)*distance),pz=(int)Math.round(center.z()+Math.sin(angle)*distance);
            int size=random.nextDouble()<.55?1+random.nextInt(2):5+random.nextInt(24);
            Set<BlockPos> cells=new HashSet<>();List<BlockPos> frontier=new ArrayList<>();
            frontier.add(new BlockPos(px,0,pz));
            while(cells.size()<size&&!frontier.isEmpty()){
                var cell=frontier.remove(random.nextInt(frontier.size()));
                if(!cells.add(cell))continue;
                for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                    var next=cell.relative(direction);
                    if(Math.abs(next.getX()-px)<=6&&Math.abs(next.getZ()-pz)<=6&&!cells.contains(next)&&!frontier.contains(next))frontier.add(next);
                }
            }
            Set<Long> water=new HashSet<>(),bank=new HashSet<>();
            for(var cell:cells)water.add(Pool.key(cell.getX(),cell.getZ()));
            boolean enclosed=true;
            for(var cell:cells)for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
                int bx=cell.getX()+dx,bz=cell.getZ()+dz;long key=Pool.key(bx,bz);
                if(occupied.contains(key))enclosed=false;
                if(!water.contains(key))bank.add(key);
                for(int y=top-2;y<=top+1;y++)if(!tree.trunkEnvelopeContains(bx,y,bz))enclosed=false;
            }
            if(enclosed){
                pools.add(new Pool(Set.copyOf(water),Set.copyOf(bank)));
                occupied.addAll(water);occupied.addAll(bank);
            }
        }
        return List.copyOf(pools);
    }
    private boolean oceanGap(int x,int z,int radius,int top){
        int water=0;
        for(int dz=-96;dz<=96;dz+=48)for(int dx=-96;dx<=96;dx+=48)
            if(field.floorAt(x+dx,z+dz)<=field.seaLevel-20)water++;
        if(water<20)return false;
        for(int dz=-radius-2;dz<=radius+2;dz+=4)for(int dx=-radius-2;dx<=radius+2;dx+=4){
            var column=field.column(x+dx,z+dz);
            if(column.floor>field.seaLevel-24||column.kind(field.seaLevel-1)!=WorldTreeField.Kind.WATER
                    ||column.kind(top+2)!=WorldTreeField.Kind.AIR)return false;
        }
        int open=0;
        for(int dz=-48;dz<=48;dz+=24)for(int dx=-48;dx<=48;dx+=24){
            var column=field.column(x+dx,z+dz);boolean clear=true;
            for(int y=210;y<=WorldTreeField.MAX_Y;y++)if(column.woodAt(y)||column.leavesAt(y)){clear=false;break;}
            if(clear)open++;
            else if(dx==0&&dz==0)return false;
        }
        return open>=18;
    }
    Optional<BlockPos> nearest(int x,int z,int radius){
        if(radius<1||radius>8192)throw new IllegalArgumentException("Search radius must be 1..8192");
        int cx=Math.floorDiv(x,CELL),cz=Math.floorDiv(z,CELL),reach=(int)Math.ceil(radius/(double)CELL)+1;
        double bestDistance=(double)radius*radius;Stump best=null;
        for(int ring=0;ring<=reach;ring++){
            for(int dz=-ring;dz<=ring;dz++)for(int dx=-ring;dx<=ring;dx++){
                if(Math.max(Math.abs(dx),Math.abs(dz))!=ring)continue;
                var stump=candidate(cx+dx,cz+dz);if(stump==null)continue;
                var cut=stump.cutPosition();
                double distance=(cut.getX()-(double)x)*(cut.getX()-(double)x)+(cut.getZ()-(double)z)*(cut.getZ()-(double)z);
                if(distance<=bestDistance){bestDistance=distance;best=stump;}
            }
            double boundary=Math.min(Math.min(x-(cx-ring)*(double)CELL,(cx+ring+1)*(double)CELL-x),
                Math.min(z-(cz-ring)*(double)CELL,(cz+ring+1)*(double)CELL-z));
            if(Math.max(0,boundary-48)*Math.max(0,boundary-48)>bestDistance)break;
        }
        return best==null?Optional.empty():Optional.of(best.cutPosition());
    }
    List<Stump> nearby(int x,int z){
        List<Stump> result=new ArrayList<>();
        for(int cz=Math.floorDiv(z-REACH,CELL);cz<=Math.floorDiv(z+REACH,CELL);cz++)
            for(int cx=Math.floorDiv(x-REACH,CELL);cx<=Math.floorDiv(x+REACH,CELL);cx++){
                var stump=candidate(cx,cz);
                if(stump!=null&&Math.abs(stump.x-x)<=REACH+8&&Math.abs(stump.z-z)<=REACH+8)result.add(stump);
            }
        return result;
    }
    Column column(int x,int z,List<Stump> candidates){
        if(candidates.isEmpty())return null;
        int top=Integer.MIN_VALUE;BlockState surface=null,underSurface=null,plant=null;
        List<Span> spans=new ArrayList<>();
        for(var stump:candidates){
            var tree=stump.tree;
            boolean nearTrunk=Math.abs(x-tree.x)<=tree.radius*1.7+Math.abs(tree.leanX)+35
                &&Math.abs(z-tree.z)<=tree.radius*1.7+Math.abs(tree.leanZ)+35;
            int cut=nearTrunk?stump.cutHeight(x,z):stump.top;
            var segments=tree.segmentsAt(x,z);
            int low=nearTrunk?stump.floor:cut+1,high=nearTrunk?cut:WorldTreeField.MIN_Y;
            List<WorldTreeField.Segment> local=new ArrayList<>();
            for(var segment:segments)if(segment.containsXZ(x,z)&&segment.minY<=stump.top){
                local.add(segment);low=Math.min(low,(int)Math.floor(segment.minY));high=Math.max(high,(int)Math.ceil(segment.maxY));
            }
            low=Math.max(WorldTreeField.MIN_Y+1,low);high=Math.min(cut,high);
            int start=Integer.MIN_VALUE;
            for(int y=low;y<=high;y++){
                boolean wood=nearTrunk&&(y>=stump.top-5?tree.trunkEnvelopeContains(x,y,z):tree.trunkContains(x,y,z));
                if(!wood)for(var segment:local)if(segment.contains(x,y,z)){wood=true;break;}
                if(wood&&start==Integer.MIN_VALUE)start=y;
                if(!wood&&start!=Integer.MIN_VALUE){spans.add(new Span(start,y-1));start=Integer.MIN_VALUE;}
                if(wood&&y==cut&&cut>=top){
                    top=cut;
                    double patch=stump.patch(x,z,9,0x534f494c),flora=stump.unit(x,z,0x464c4f52);
                    surface=(patch<.40?Blocks.STRIPPED_DARK_OAK_LOG:patch<.63?Blocks.PODZOL:Blocks.MOSS_BLOCK).defaultBlockState();
                    plant=null;underSurface=null;
                    if(stump.waterAt(x,z)){
                        surface=Blocks.PODZOL.defaultBlockState();plant=Blocks.WATER.defaultBlockState();
                    }else if(flora<.075){
                        surface=Blocks.PODZOL.defaultBlockState();
                        plant=(flora<.045?Blocks.BROWN_MUSHROOM:Blocks.RED_MUSHROOM).defaultBlockState();
                    }else if(flora<.095){
                        surface=Blocks.MOSS_BLOCK.defaultBlockState();plant=Blocks.FLOWERING_AZALEA.defaultBlockState();
                    }else if(patch>=.40&&flora<.16)plant=Blocks.FERN.defaultBlockState();
                    else if(patch>=.40&&flora<.20)plant=Blocks.GRASS.defaultBlockState();
                    else if(flora<.40)plant=Blocks.MOSS_CARPET.defaultBlockState();
                    if(surface.is(Blocks.PODZOL))underSurface=Blocks.ROOTED_DIRT.defaultBlockState();
                    else if(surface.is(Blocks.MOSS_BLOCK))underSurface=Blocks.MOSS_BLOCK.defaultBlockState();
                }
            }
            if(start!=Integer.MIN_VALUE)spans.add(new Span(start,high));
        }
        return spans.isEmpty()?null:new Column(top,surface,underSurface,plant,List.copyOf(spans));
    }
}
