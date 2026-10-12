package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.StructureManager;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;


/** 按区块裁剪的自然地物及树干苔藓。 */
public final class WorldTreeEcology {
    static final int CELL=48, REACH=44, CACHE_LIMIT=256;
    public enum Type {
        TRUNK_MOSS, SHELF_FUNGI, BRANCH_GARDEN, CANOPY_NEST,
        HOLLOW_LOG, HUMUS_MAT, FOREST_POOL,
        ROOT_RAFT, LILY_COLONY, DRIFTWOOD, DEEP_DRIFTWOOD,
        SUNKEN_LOG, CRYSTAL_REEF, SEAGRASS_MEADOW,
        MOSS_ROOT_STEPS, SUBMERGED_ROOTLETS, ROOT_DETRITUS
    }
    enum Mode { OPEN, EARTH, BARK, PLANT }
    record Voxel(BlockPos pos,BlockState state,Mode mode) {}
    record Plan(Type type,BlockPos origin,List<Voxel> blocks,BoundingBox bounds,Map<Long,List<Voxel>> byChunk) {}
    private final WorldTreeField f;
    private final BlockState wood,leaves,crystal;
    private final ConcurrentHashMap<Long,List<Plan>> cache=new ConcurrentHashMap<>();
    private volatile Palette palette;
    WorldTreeEcology(WorldTreeField f,BlockState wood,BlockState leaves,BlockState crystal){this.f=f;this.wood=wood;this.leaves=leaves;this.crystal=crystal;}
    private Palette palette(){
        Palette p=palette;
        if(p==null)synchronized(this){if(palette==null)palette=new Palette();p=palette;}
        return p;
    }
    static BlockState block(String id,BlockState fallback){
        var key=new ResourceLocation(id);
        return BuiltInRegistries.BLOCK.containsKey(key)?BuiltInRegistries.BLOCK.get(key).defaultBlockState():fallback;
    }
    @SuppressWarnings({"rawtypes","unchecked"})
    static BlockState property(BlockState state,String name,String value){
        Property prop=state.getBlock().getStateDefinition().getProperty(name);
        if(prop==null)return state;
        Optional parsed=prop.getValue(value);
        return parsed.isPresent()?(BlockState)state.setValue(prop,(Comparable)parsed.get()):state;
    }
    private static final class Palette {
        final BlockState peat=block("biomemakeover:mossy_peat",Blocks.MOSS_BLOCK.defaultBlockState());
        final BlockState humus=block("biomemakeover:peat",Blocks.PODZOL.defaultBlockState());
        final BlockState mushroom=block("biomemakeover:purple_glowshroom",Blocks.BROWN_MUSHROOM.defaultBlockState());
        final BlockState reed=block("biomemakeover:reed",Blocks.GRASS.defaultBlockState());
        final BlockState cattail=block("biomemakeover:cattail",Blocks.FERN.defaultBlockState());
        final BlockState smallLily=block("biomemakeover:small_lily_pad",Blocks.LILY_PAD.defaultBlockState());
        final BlockState waterLily=block("biomemakeover:water_lily",Blocks.LILY_PAD.defaultBlockState());
        final BlockState hugeLily=block("twilightforest:huge_lily_pad",Blocks.LILY_PAD.defaultBlockState());
        final BlockState hugeFlower=block("twilightforest:huge_water_lily",Blocks.LILY_PAD.defaultBlockState());
        final BlockState hollow=property(block("quark:hollow_ancient_log",Blocks.DARK_OAK_LOG.defaultBlockState()),"axis","x");
    }
    List<Plan> plans(int cx,int cz){
        long key=((long)cx<<32)^(cz&0xffffffffL);
        List<Plan> result=cache.computeIfAbsent(key,k->create(cx,cz));
        if(cache.size()>CACHE_LIMIT){int n=0;for(Long old:cache.keySet()){cache.remove(old);if(++n==64)break;}}
        return result;
    }
    void clearCache(){cache.clear();}
    private List<Plan> create(int cx,int cz){
        var r=new Random(f.pointSeed(cx,0x45c010,cz));
        List<Plan> result=new ArrayList<>(4);
        int x=cx*CELL+12+r.nextInt(24),z=cz*CELL+12+r.nextInt(24);
        var c=f.column(x,z);
        if(r.nextDouble()<.74){
            Type type;
            if(c.zone==WorldTreeField.Zone.ROOTS)type=new Type[]{Type.HOLLOW_LOG,Type.HUMUS_MAT,Type.FOREST_POOL}[r.nextInt(3)];
            else if(c.floor>=f.seaLevel-18)type=new Type[]{Type.ROOT_RAFT,Type.LILY_COLONY,Type.SEAGRASS_MEADOW}[r.nextInt(3)];
            else {double chance=r.nextDouble();type=chance<.12?Type.CRYSTAL_REEF:chance<.48?Type.SUNKEN_LOG:Type.SEAGRASS_MEADOW;}
            Builder b=new Builder(type,new BlockPos(x,c.floor+1,z),r.nextLong());
            switch(type){
                case HOLLOW_LOG -> log(b,false,false);
                case HUMUS_MAT -> groundPatch(b,false);
                case FOREST_POOL -> pool(b);
                case ROOT_RAFT -> raft(b);
                case LILY_COLONY -> lilies(b);
                case SUNKEN_LOG -> log(b,true,false);
                case CRYSTAL_REEF -> reef(b);
                case SEAGRASS_MEADOW -> meadow(b,13);
                default -> throw new IllegalStateException();
            }
            b.finish().ifPresent(result::add);
        }
        waterDriftPlans(cx,cz,result);
        deepDriftPlans(cx,cz,result);
        trunkColonies(cx,cz,result);
        for(boolean upper:new boolean[]{false,true,false,true}){
            if(r.nextDouble()>(upper?.95:.84))continue;
            Type type=upper?(r.nextDouble()<.35?Type.TRUNK_MOSS:Type.BRANCH_GARDEN)
                :new Type[]{Type.MOSS_ROOT_STEPS,Type.SUBMERGED_ROOTLETS,Type.ROOT_DETRITUS}[r.nextInt(3)];
            BlockPos anchor=null;
            if(type==Type.TRUNK_MOSS){
                for(var tree:f.nearby(cx*CELL+24,cz*CELL+24)){
                    if(Math.abs(tree.x-(cx*CELL+24))>85||Math.abs(tree.z-(cz*CELL+24))>85)continue;
                    for(int attempt=0;attempt<48&&anchor==null;attempt++){
                        var v=tree.ecologyTrunkSurface(f.seaLevel+35+r.nextInt(145),r.nextDouble()*Math.PI*2);
                        if(v!=null&&trunkStep((int)v.x(),(int)v.y(),(int)v.z())&&Math.floorDiv((int)v.x(),CELL)==cx&&Math.floorDiv((int)v.z(),CELL)==cz)anchor=new BlockPos((int)v.x(),(int)v.y(),(int)v.z());
                    }
                    if(anchor!=null)break;
                }
            }
            for(int attempt=0;attempt<7&&anchor==null&&type!=Type.TRUNK_MOSS;attempt++){
                int ax=cx*CELL+10+r.nextInt(28),az=cz*CELL+10+r.nextInt(28);
                var ac=f.column(ax,az);
                int y=ac.ecologySurface(upper?135:Math.max(ac.floor+2,f.seaLevel-28),upper?285:f.seaLevel+35,false);
                if(y!=Integer.MIN_VALUE)anchor=new BlockPos(ax,y,az);
            }
            if(anchor!=null){
                Builder b=new Builder(type,anchor,r.nextLong());
                switch(type){
                    case TRUNK_MOSS -> trunkMoss(b);
                    case BRANCH_GARDEN -> garden(b);
                    case MOSS_ROOT_STEPS -> rootPatch(b,false);
                    case SUBMERGED_ROOTLETS -> rootlets(b);
                    case ROOT_DETRITUS -> rootPatch(b,true);
                    default -> throw new IllegalStateException();
                }
                b.finish().ifPresent(result::add);
            }
        }
        return List.copyOf(result);
    }

    /** 浅滩弯曲浮木使用独立水面候选。 */
    private void waterDriftPlans(int cx,int cz,List<Plan> result){
        int grid=32,minX=cx*CELL,minZ=cz*CELL;
        for(int gx=Math.floorDiv(minX,grid);gx<=Math.floorDiv(minX+CELL-1,grid);gx++)
            for(int gz=Math.floorDiv(minZ,grid);gz<=Math.floorDiv(minZ+CELL-1,grid);gz++){
                Random random=new Random(f.pointSeed(gx,0x44524946,gz));
                if(random.nextDouble()>.85)continue;
                int x=gx*grid+12+random.nextInt(8);
                int z=gz*grid+12+random.nextInt(8);
                if(Math.floorDiv(x,CELL)!=cx||Math.floorDiv(z,CELL)!=cz)continue;
                if(f.column(x,z).zone!=WorldTreeField.Zone.SHALLOWS||!surfaceWater(x,z))continue;
                Builder b=new Builder(Type.DRIFTWOOD,new BlockPos(x,f.seaLevel-1,z),random.nextLong());
                log(b,false,true);
                b.finish().ifPresent(result::add);
            }
    }

    private record DriftCandidate(int x,int z,long seed){}
    private List<DriftCandidate> driftCandidates(int cx,int cz){
        Random random=new Random(f.pointSeed(cx,0x44454550,cz));
        List<DriftCandidate> candidates=new ArrayList<>();
        for(int i=0;i<7;i++){
            int x=cx*CELL+random.nextInt(CELL),z=cz*CELL+random.nextInt(CELL);long seed=random.nextLong();
            double density=f.driftDensityAt(x,z);
            if(random.nextDouble()<.03+.65*Math.pow(density,3))candidates.add(new DriftCandidate(x,z,seed));
        }
        return candidates;
    }
    private void deepDriftPlans(int cx,int cz,List<Plan> result){
        List<DriftCandidate> neighbors=new ArrayList<>();
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)neighbors.addAll(driftCandidates(cx+dx,cz+dz));
        for(var candidate:driftCandidates(cx,cz)){
            boolean clear=true;
            for(var other:neighbors)if(other.seed<candidate.seed&&Math.hypot(other.x-candidate.x,other.z-candidate.z)<14){clear=false;break;}
            if(!clear||!f.coastalDeepWater(candidate.x,candidate.z)||!surfaceWater(candidate.x,candidate.z))continue;
            Builder b=new Builder(Type.DEEP_DRIFTWOOD,new BlockPos(candidate.x,f.seaLevel-2,candidate.z),candidate.seed);
            deepDriftwood(b);b.finish().ifPresent(result::add);
        }
    }

    private void deepDriftwood(Builder b){
        int length=8+b.r.nextInt(5);boolean alongX=b.r.nextBoolean();
        BlockState log=Blocks.DARK_OAK_LOG.defaultBlockState().setValue(BlockStateProperties.AXIS,alongX?Direction.Axis.X:Direction.Axis.Z);
        for(int i=0;i<length;i++)for(int side=0;side<2;side++){
            int x=b.origin.getX()+(alongX?i-length/2:side),z=b.origin.getZ()+(alongX?side:i-length/2);
            if(f.column(x,z).zone!=WorldTreeField.Zone.DEPTHS||!surfaceWater(x,z)
                    ||f.column(x,z).kind(f.seaLevel+1)!=WorldTreeField.Kind.AIR)return;
        }
        for(int i=0;i<length;i++)for(int side=0;side<2;side++){
            int x=b.origin.getX()+(alongX?i-length/2:side),z=b.origin.getZ()+(alongX?side:i-length/2);
            b.put(x,f.seaLevel-2,z,log,Mode.OPEN);
            b.put(x,f.seaLevel-1,z,log,Mode.OPEN);
            double decoration=b.r.nextDouble();
            if(decoration<.14)b.plant(x,f.seaLevel,z,palette().mushroom);
            else if(decoration<.22)b.plant(x,f.seaLevel,z,Blocks.RED_MUSHROOM.defaultBlockState());
            else if(decoration<.60)b.plant(x,f.seaLevel,z,Blocks.MOSS_CARPET.defaultBlockState());
        }
    }
    private final class Builder {
        final Type type;final BlockPos origin;final Random r;final long seed;final double co,si;
        final LinkedHashMap<BlockPos,Voxel> blocks=new LinkedHashMap<>();
        Builder(Type type,BlockPos origin,long seed){this.type=type;this.origin=origin;this.seed=seed;r=new Random(seed);double angle=(seed>>>11)*0x1.0p-53*Math.PI*2;co=Math.cos(angle);si=Math.sin(angle);}
        long hash(int x,int y,int z){return WorldTreeField.mix(seed^((long)x*341873128712L)^((long)z*132897987541L)^y);}
        boolean shape(int dx,int dz,double rx,double rz){
            double a=Math.atan2(dz,dx),edge=1+.17*Math.sin(a*3+seed%37)+.10*Math.cos(a*5-seed%19);
            double jitter=(hash(origin.getX()+dx,0,origin.getZ()+dz)&15)/90.;
            return dx*dx/(rx*rx)+dz*dz/(rz*rz)<edge-jitter;
        }
        double unit(int x,int z,int salt){return (hash(x,salt,z)>>>11)*0x1.0p-53;}
        double patchNoise(double x,double z,int salt){
            int ix=(int)Math.floor(x),iz=(int)Math.floor(z);double tx=x-ix,tz=z-iz;
            tx=tx*tx*(3-2*tx);tz=tz*tz*(3-2*tz);
            double a=unit(ix,iz,salt)*(1-tx)+unit(ix+1,iz,salt)*tx;
            double c=unit(ix,iz+1,salt)*(1-tx)+unit(ix+1,iz+1,salt)*tx;
            return a*(1-tz)+c*tz;
        }
        
        double cover(int dx,int dz,double rx,double rz,double fringe){
            double u=co*dx-si*dz,v=si*dx+co*dz;
            double warp=(patchNoise(dx*.12,dz*.12,41)-.5)*fringe*.65
                +(patchNoise(dx*.041,dz*.041,71)-.5)*fringe*.45;
            double q=Math.hypot(u/rx,v/rz),length=Math.hypot(u,v);
            double distance=(q>1e-6?length-length/q:-Math.min(rx,rz))+warp;
            double t=Math.max(0,Math.min(1,distance/fringe));
            return 1-t*t*(3-2*t);
        }
        void put(int x,int y,int z,BlockState state,Mode mode){
            if(y<=WorldTreeField.MIN_Y||y>=WorldTreeField.MAX_Y||Math.abs(x-origin.getX())>REACH||Math.abs(z-origin.getZ())>REACH)return;
            var col=f.column(x,z);var kind=col.kind(y);
            boolean earth=kind==WorldTreeField.Kind.SOIL||kind==WorldTreeField.Kind.DIRT||kind==WorldTreeField.Kind.MUD||kind==WorldTreeField.Kind.GRAVEL||kind==WorldTreeField.Kind.STONE;
            Voxel planned=blocks.get(new BlockPos(x,y,z));
            boolean excavated=planned!=null&&planned.state.isAir();
            if(mode==Mode.BARK?kind!=WorldTreeField.Kind.WOOD:mode==Mode.EARTH?(!earth||y<col.floor-3):(!excavated&&kind!=WorldTreeField.Kind.AIR&&kind!=WorldTreeField.Kind.WATER&&!(type==Type.CANOPY_NEST&&kind==WorldTreeField.Kind.LEAVES)))return;
            if(state.hasProperty(BlockStateProperties.WATERLOGGED))state=state.setValue(BlockStateProperties.WATERLOGGED,y<f.seaLevel);
            BlockPos p=new BlockPos(x,y,z);blocks.put(p,new Voxel(p,state,mode));
        }
        void plant(int x,int y,int z,BlockState state){
            if(state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)){
                if(f.column(x,z).kind(y+1)!=WorldTreeField.Kind.AIR)return;
                put(x,y,z,state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.LOWER),Mode.PLANT);
                put(x,y+1,z,state.setValue(BlockStateProperties.DOUBLE_BLOCK_HALF,DoubleBlockHalf.UPPER),Mode.PLANT);
            }else put(x,y,z,state,Mode.PLANT);
        }
        void greenery(int x,int y,int z,boolean wet){
            int h=(int)Math.floorMod(hash(x,y,z),100);
            if(h>67)return;
            BlockState s=wet?(h<22?palette().cattail:palette().reed):h<9?palette().mushroom:h<15?Blocks.BLUE_ORCHID.defaultBlockState():h<48?Blocks.FERN.defaultBlockState():Blocks.GRASS.defaultBlockState();
            if(wet&&s.is(palette().reed.getBlock())&&f.column(x,z).kind(y)!=WorldTreeField.Kind.WATER)s=Blocks.FERN.defaultBlockState();
            plant(x,y,z,s);
        }
        Optional<Plan> finish(){
            if(blocks.size()<5)return Optional.empty();
            int minX=Integer.MAX_VALUE,minY=minX,minZ=minX,maxX=Integer.MIN_VALUE,maxY=maxX,maxZ=maxX;
            for(var p:blocks.keySet()){minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
            Map<Long,List<Voxel>> split=new HashMap<>();
            for(Voxel v:blocks.values())split.computeIfAbsent(ChunkPos.asLong(v.pos.getX()>>4,v.pos.getZ()>>4),k->new ArrayList<>()).add(v);
            split.replaceAll((k,v)->List.copyOf(v));
            return Optional.of(new Plan(type,origin,List.copyOf(blocks.values()),new BoundingBox(minX,minY,minZ,maxX,maxY,maxZ),Map.copyOf(split)));
        }
    }
    private void groundPatch(Builder b,boolean small){
        int core=small?5+b.r.nextInt(3):14+b.r.nextInt(7),fringe=small?5:10+b.r.nextInt(4);
        int radius=Math.min(REACH,core+fringe+4);
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            double w=b.cover(dx,dz,core,core*.85,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;var c=f.column(x,z);
            if(c.zone!=WorldTreeField.Zone.ROOTS||c.kind(c.floor+1)!=WorldTreeField.Kind.AIR)continue;
            if(w>.48&&b.unit(x,z,101)<w){
                double soil=b.patchNoise(x*.16,z*.16,117);
                b.put(x,c.floor,z,soil<.32?palette().humus:soil<.49?palette().peat:soil<.78?Blocks.MOSS_BLOCK.defaultBlockState():Blocks.PODZOL.defaultBlockState(),Mode.EARTH);
            }
            if(b.unit(x,z,123)<w*(w<.8?.82:.24))b.plant(x,c.floor+1,z,Blocks.MOSS_CARPET.defaultBlockState());
            else if(b.unit(x,z,127)<w*.82)b.greenery(x,c.floor+1,z,false);
        }
    }
    private void pool(Builder b){
        int radius=5+b.r.nextInt(3),y=b.origin.getY()-2;
        Set<BlockPos> wet=new HashSet<>();
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++)if(b.shape(dx,dz,radius*.82,radius*.68))wet.add(new BlockPos(b.origin.getX()+dx,y,b.origin.getZ()+dz));
        for(BlockPos p:wet)for(Direction d:Direction.Plane.HORIZONTAL){
            BlockPos edge=p.relative(d);var c=f.column(edge.getX(),edge.getZ());
            if(c.floor<y||c.floor>y+3||c.woodAt(y)||c.woodAt(y+1))return;
        }
        for(BlockPos p:wet){
            int x=p.getX(),z=p.getZ();var c=f.column(x,z);
            b.put(x,c.floor+1,z,Blocks.AIR.defaultBlockState(),Mode.OPEN);
            b.put(x,c.floor+2,z,Blocks.AIR.defaultBlockState(),Mode.OPEN);
            for(int yy=c.floor;yy>y;yy--)b.put(x,yy,z,Blocks.AIR.defaultBlockState(),Mode.EARTH);
            b.put(x,y-2,z,Blocks.CLAY.defaultBlockState(),Mode.EARTH);
            b.put(x,y-1,z,Blocks.MUD.defaultBlockState(),Mode.EARTH);
            b.put(x,y,z,Blocks.WATER.defaultBlockState(),Mode.EARTH);
        }
        for(int dz=-radius-1;dz<=radius+1;dz++)for(int dx=-radius-1;dx<=radius+1;dx++){
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;BlockPos p=new BlockPos(x,y,z);
            if(wet.contains(p)||!Direction.Plane.HORIZONTAL.stream().anyMatch(d->wet.contains(p.relative(d))))continue;
            var c=f.column(x,z);b.put(x,c.floor,z,(b.hash(x,y,z)&1)==0?Blocks.ROOTED_DIRT.defaultBlockState():Blocks.MUD.defaultBlockState(),Mode.EARTH);b.greenery(x,c.floor+1,z,true);
        }
    }
    private void log(Builder b,boolean submerged,boolean floating){
        int length=7+b.r.nextInt(submerged?15:10);boolean alongX=b.r.nextBoolean();int offset=length/2;
        if(floating)for(int i=-offset-3;i<=offset+3;i++)for(int side=-3;side<=3;side++){
            int x=b.origin.getX()+(alongX?i:side),z=b.origin.getZ()+(alongX?side:i);
            if(f.column(x,z).zone!=WorldTreeField.Zone.SHALLOWS||!surfaceWater(x,z)||f.column(x,z).kind(f.seaLevel+1)!=WorldTreeField.Kind.AIR)return;
        }
        int centerY=floating?f.seaLevel-1:b.origin.getY();
        if(!floating){
            int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;
            for(int i=-offset;i<=offset;i++){int x=b.origin.getX()+(alongX?i:0),z=b.origin.getZ()+(alongX?0:i);int y=f.column(x,z).floor;min=Math.min(min,y);max=Math.max(max,y);}
            if(max-min>5)return;
            centerY=min+2;
        }
        BlockState log=property(wood,"axis",alongX?"x":"z");
        for(int i=-offset;i<=offset;i++){
            int bend=(int)Math.round(Math.sin(i*.24+b.seed%7)*1.2),x=b.origin.getX()+(alongX?i:bend),z=b.origin.getZ()+(alongX?bend:i);
            int cy=centerY+(int)Math.round(.55*Math.sin(i*.35));
            for(int side=-1;side<=1;side++)for(int dy=-1;dy<=1;dy++){
                if((Math.abs(i)==offset&&((b.hash(x+side,dy,z)&3)==0))||!floating&&side==0&&dy==0)continue;
                b.put(x+(alongX?0:side),cy+dy,z+(alongX?side:0),log,Mode.OPEN);
            }
            if(!submerged&&!floating){b.put(x,cy,z,property(palette().hollow,"axis",alongX?"x":"z"),Mode.OPEN);if((i&2)==0){b.put(x,cy+2,z,Blocks.MOSS_CARPET.defaultBlockState(),Mode.PLANT);}}
            if(submerged&&i%3==0)aquatic(b,x,cy+2,z,2+b.r.nextInt(5));
            if(floating&&i%4==0)b.put(x,f.seaLevel+1,z,Blocks.MOSS_CARPET.defaultBlockState(),Mode.PLANT);
            if(i%5==0){int s=b.r.nextBoolean()?1:-1;for(int n=1;n<=3;n++)b.put(x+(alongX?0:s*n),cy,z+(alongX?s*n:0),wood,Mode.OPEN);}
        }
        if(submerged)meadow(b,7);else if(!floating)groundPatch(b,true);
    }
    private boolean surfaceWater(int x,int z){return f.column(x,z).kind(f.seaLevel-1)==WorldTreeField.Kind.WATER&&f.column(x,z).kind(f.seaLevel)==WorldTreeField.Kind.AIR;}
    private void raft(Builder b){
        int core=7+b.r.nextInt(4),fringe=6+b.r.nextInt(3),radius=core+fringe+3;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            double w=b.cover(dx,dz,core,core*.76,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;if(!surfaceWater(x,z))continue;
            long h=b.hash(x,0,z);
            if(b.unit(x,z,139)>w*.92){if(b.unit(x,z,149)<w*.24)b.plant(x,f.seaLevel,z,palette().smallLily);continue;}
            b.put(x,f.seaLevel-1,z,Blocks.MANGROVE_ROOTS.defaultBlockState(),Mode.OPEN);
            if(w>.8&&b.unit(x,z,151)<.76){b.put(x,f.seaLevel,z,(h&3)==0?palette().humus:palette().peat,Mode.OPEN);patchPlants(b,x,f.seaLevel+1,z,true);}
            else b.plant(x,f.seaLevel,z,Blocks.MOSS_CARPET.defaultBlockState());
            if((h&15)==1)for(int dy=2;dy<4+(h>>>8)%4;dy++)b.put(x+(dy>3?1:0),f.seaLevel-dy,z,Blocks.MANGROVE_ROOTS.defaultBlockState(),Mode.OPEN);
            if(w<.8&&(h&3)==0){
                b.put(x,f.seaLevel-2,z,Blocks.ROOTED_DIRT.defaultBlockState(),Mode.OPEN);
                b.plant(x,f.seaLevel-1,z,palette().reed);
            }
        }
    }
    private void lilies(Builder b){
        int core=13+b.r.nextInt(6),fringe=10,radius=core+fringe+3;
        List<WaterPatch> neighbors=waterNeighbors(b);
        for(int dz0=-radius;dz0<=radius;dz0++)for(int dx0=-radius;dx0<=radius;dx0++){
            double w=b.cover(dx0,dz0,core,core*.8,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx0,z=b.origin.getZ()+dz0;
            if(b.unit(x,z,157)>w*.08)continue;
            boolean huge=w>.72&&b.unit(x,z,163)<.38&&palette().hugeLily.hasProperty(BlockStateProperties.HORIZONTAL_FACING);
            if(!surfaceWater(x,z)||!lilySpace(b,neighbors,x,z))continue;
            if(huge){
                boolean clear=true;for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)if(!surfaceWater(x+dx,z+dz)||!lilySpace(b,neighbors,x+dx,z+dz)||b.blocks.containsKey(new BlockPos(x+dx,f.seaLevel,z+dz)))clear=false;
                if(!clear)continue;
                String[] pieces={"nw","ne","sw","se"};
                for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++)b.plant(x+dx,f.seaLevel,z+dz,property(property(palette().hugeLily,"facing","north"),"piece",pieces[dx+dz*2]));
            }else if(!b.blocks.containsKey(new BlockPos(x,f.seaLevel,z)))b.plant(x,f.seaLevel,z,w>.8&&b.unit(x,z,167)<.12?palette().hugeFlower:b.unit(x,z,173)<.6?palette().smallLily:palette().waterLily);
        }
    }
    private record WaterPatch(Builder builder,int core,int fringe){}
    private List<WaterPatch> waterNeighbors(Builder owner){
        List<WaterPatch> result=new ArrayList<>();int cx=Math.floorDiv(owner.origin.getX(),CELL),cz=Math.floorDiv(owner.origin.getZ(),CELL);
        for(int dz=-1;dz<=1;dz++)for(int dx=-1;dx<=1;dx++){
            if(dx==0&&dz==0)continue;
            int cellX=cx+dx,cellZ=cz+dz;Random r=new Random(f.pointSeed(cellX,0x45c010,cellZ));
            int x=cellX*CELL+12+r.nextInt(24),z=cellZ*CELL+12+r.nextInt(24);var c=f.column(x,z);
            if(r.nextDouble()>=.74||c.floor>=f.seaLevel||c.floor<f.seaLevel-18)continue;
            Type type=new Type[]{Type.ROOT_RAFT,Type.LILY_COLONY,Type.SEAGRASS_MEADOW}[r.nextInt(3)];Builder neighbor=new Builder(type,new BlockPos(x,c.floor+1,z),r.nextLong());
            if(type==Type.ROOT_RAFT)result.add(new WaterPatch(neighbor,7+neighbor.r.nextInt(4),6+neighbor.r.nextInt(3)));
            else if(type==Type.LILY_COLONY)result.add(new WaterPatch(neighbor,0,0));
        }
        return result;
    }
    private boolean lilySpace(Builder owner,List<WaterPatch> neighbors,int x,int z){
        double ownDistance=Math.hypot(x-owner.origin.getX(),z-owner.origin.getZ());
        for(WaterPatch patch:neighbors){
            Builder other=patch.builder;int dx=x-other.origin.getX(),dz=z-other.origin.getZ();
            if(other.type==Type.ROOT_RAFT){
                double w=other.cover(dx,dz,patch.core,patch.core*.76,patch.fringe);
                if(w>0&&other.unit(x,z,139)<=w*.92)return false;
            }else{
                double distance=Math.hypot(dx,dz);
                if(distance<ownDistance||distance==ownDistance&&other.seed<owner.seed)return false;
            }
        }
        return true;
    }
    private void aquatic(Builder b,int x,int y,int z,int height){
        int top=y;while(top<y+height&&top<f.seaLevel-2&&f.column(x,z).kind(top)==WorldTreeField.Kind.WATER&&!b.blocks.containsKey(new BlockPos(x,top,z)))top++;
        if(top==y)return;
        if(height<=2){b.plant(x,y,z,Blocks.SEAGRASS.defaultBlockState());return;}
        for(int yy=y;yy<top;yy++)b.plant(x,yy,z,(yy==top-1?Blocks.KELP:Blocks.KELP_PLANT).defaultBlockState());
    }
    private void meadow(Builder b,int radius){
        int core=radius>=13?24+b.r.nextInt(7):9+b.r.nextInt(4),fringe=radius>=13?12:7;
        radius=Math.min(REACH,core+fringe+2);
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            double w=b.cover(dx,dz,core,core*.8,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;var c=f.column(x,z);long h=b.hash(x,0,z);
            if(c.floor>f.seaLevel-8||b.unit(x,z,179)>w*.68||c.kind(c.floor+1)!=WorldTreeField.Kind.WATER)continue;
            if(w>.65&&(h&15)==2)b.put(x,c.floor,z,Blocks.CLAY.defaultBlockState(),Mode.EARTH);
            aquatic(b,x,c.floor+1,z,w>.55&&(h&3)==0?3+(int)(w*(3+(h>>>6)%13)):1);
        }
    }
    private void reef(Builder b){
        int radius=3+b.r.nextInt(3),crystals=0;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            if(!b.shape(dx,dz,radius,radius*.85))continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;var c=f.column(x,z);if(c.floor>f.seaLevel-24)continue;
            long h=b.hash(x,0,z);int height=1+(int)((h>>>8)%3)+(dx*dx+dz*dz<5?2:0);
            for(int dy=1;dy<=height;dy++)b.put(x,c.floor+dy,z,(h&3)==0?Blocks.TUFF.defaultBlockState():Blocks.COBBLED_DEEPSLATE.defaultBlockState(),Mode.OPEN);
            if((h&7)==0&&crystals<5){b.put(x,c.floor+height+1,z,crystal,Mode.OPEN);crystals++;}
        }
    }
    /** 将斑块嵌入外层树皮，层架菌群另外向外生长。 */
    private void trunkColonies(int cx,int cz,List<Plan> result){
        for(var tree:f.nearby(cx*CELL+24,cz*CELL+24)){
            if(Math.abs(tree.x-(cx*CELL+24))>110||Math.abs(tree.z-(cz*CELL+24))>110)continue;
            Random random=new Random(f.pointSeed(tree.cellX,0x46554e47,tree.cellZ));
            int groups=2+random.nextInt(2);
            List<Plan> colonies=new ArrayList<>();
            for(int i=0;i<groups;i++){
                int y=(int)Math.min(tree.fork-25,f.seaLevel+45+i*36+random.nextInt(9));
                double angle=random.nextDouble()*Math.PI*2;long seed=random.nextLong();
                Plan chosen=null;
                for(int attempt=0;attempt<12;attempt++){
                    var surface=tree.ecologyTrunkSurface(y,angle+attempt*Math.PI/6);
                    if(surface==null)continue;
                    Builder b=new Builder(Type.SHELF_FUNGI,new BlockPos((int)surface.x(),y,(int)surface.z()),seed);
                    if(shelfFungi(b)<4)continue;
                    var plan=b.finish().orElse(null);if(plan==null)continue;
                    boolean clear=true;
                    for(var other:colonies)if(!separatedColonies(plan.bounds,other.bounds)){clear=false;break;}
                    if(clear){chosen=plan;break;}
                }
                if(chosen!=null){
                    colonies.add(chosen);
                    if(Math.floorDiv(chosen.origin.getX(),CELL)==cx&&Math.floorDiv(chosen.origin.getZ(),CELL)==cz)result.add(chosen);
                }
            }
        }
    }
    private boolean separatedColonies(BoundingBox a,BoundingBox b){
        int dx=Math.max(0,Math.max(a.minX()-b.maxX()-1,b.minX()-a.maxX()-1));
        int dy=Math.max(0,Math.max(a.minY()-b.maxY()-1,b.minY()-a.maxY()-1));
        int dz=Math.max(0,Math.max(a.minZ()-b.maxZ()-1,b.minZ()-a.maxZ()-1));
        return dx*dx+dy*dy+dz*dz>=144;
    }
    private Direction outward(Builder b){
        for(Direction d:Direction.Plane.HORIZONTAL)if(f.column(b.origin.getX()+d.getStepX(),b.origin.getZ()+d.getStepZ()).kind(b.origin.getY())==WorldTreeField.Kind.AIR)return d;
        return null;
    }
    private BlockPos barkAt(Builder b,Direction out,int side,int y){
        Direction across=out.getClockWise();
        int bx=b.origin.getX()+across.getStepX()*side,bz=b.origin.getZ()+across.getStepZ()*side;
        for(int k=16;k>=-16;k--){
            int x=bx+out.getStepX()*k,z=bz+out.getStepZ()*k;
            if(f.column(x,z).kind(y)==WorldTreeField.Kind.WOOD&&f.column(x+out.getStepX(),z+out.getStepZ()).kind(y)==WorldTreeField.Kind.AIR)return new BlockPos(x,y,z);
        }
        return null;
    }
    private boolean trunkStep(int x,int y,int z){
        if(f.column(x,z).kind(y)!=WorldTreeField.Kind.WOOD||f.column(x,z).kind(y+1)!=WorldTreeField.Kind.AIR)return false;
        for(Direction d:Direction.Plane.HORIZONTAL)
            if(f.column(x+d.getStepX(),z+d.getStepZ()).kind(y+1)==WorldTreeField.Kind.WOOD)return true;
        return false;
    }
    private void trunkMoss(Builder b){
        int radius=2+b.r.nextInt(2),count=0;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            if(!b.shape(dx,dz,radius,radius*.8)||count>=16)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;
            for(int y=b.origin.getY()+2;y>=b.origin.getY()-2;y--){
                if(!trunkStep(x,y,z))continue;
                b.put(x,y,z,b.unit(x,z,221)<.2?Blocks.PODZOL.defaultBlockState():Blocks.MOSS_BLOCK.defaultBlockState(),Mode.BARK);
                if(b.unit(x,z,223)<.45)b.plant(x,y+1,z,Blocks.BROWN_MUSHROOM.defaultBlockState());
                else b.plant(x,y+1,z,Blocks.MOSS_CARPET.defaultBlockState());
                count++;break;
            }
        }
    }
    private int shelfFungi(Builder b){
        Direction out=outward(b);if(out==null)return 0;
        Direction across=out.getClockWise();int layers=4+b.r.nextInt(2),placed=0;
        for(int layer=0;layer<layers;layer++){
            int y=b.origin.getY()+layer*5,width=10+b.r.nextInt(3),depth=7+b.r.nextInt(3);
            BlockPos[] contour=new BlockPos[width*2+1];
            for(int side=-width;side<=width;side++)contour[side+width]=barkAt(b,out,side,y);
            int[] inner=new int[contour.length],edge=new int[contour.length];
            java.util.Arrays.fill(inner,Integer.MIN_VALUE);
            for(int i=0;i<contour.length;i++)if(contour[i]!=null)
                inner[i]=contour[i].getX()*out.getStepX()+contour[i].getZ()*out.getStepZ();
            for(int i=1;i<inner.length-1;i++)if(inner[i]==Integer.MIN_VALUE){
                int left=i-1,right=i+1;
                while(left>=0&&inner[left]==Integer.MIN_VALUE)left--;
                while(right<inner.length&&inner[right]==Integer.MIN_VALUE)right++;
                if(left>=0&&right<inner.length&&right-left<=4)
                    inner[i]=(int)Math.round(inner[left]+(inner[right]-(double)inner[left])*(i-left)/(right-left));
            }
            java.util.Arrays.fill(edge,Integer.MIN_VALUE);
            for(int i=0;i<inner.length;i++){
                if(inner[i]==Integer.MIN_VALUE)continue;
                int side=i-width;double smooth=0,weight=0;
                for(int k=Math.max(0,i-4);k<=Math.min(inner.length-1,i+4);k++){
                    if(inner[k]==Integer.MIN_VALUE)continue;
                    double w=5-Math.abs(k-i);smooth+=inner[k]*w;weight+=w;
                }
                int extension=(int)Math.round(depth*Math.sqrt(Math.max(0,1-side*side/(double)(width*width))));
                edge[i]=Math.max(inner[i]+1,(int)Math.round(smooth/weight)+extension);
            }
            for(int i=1;i<inner.length;i++)if(inner[i]!=Integer.MIN_VALUE&&inner[i-1]!=Integer.MIN_VALUE){
                int bridge=Math.max(inner[i],inner[i-1])+1;
                edge[i]=Math.max(edge[i],bridge);edge[i-1]=Math.max(edge[i-1],bridge);
            }
            int[] rounded=edge.clone();
            for(int i=0;i<edge.length;i++)if(edge[i]!=Integer.MIN_VALUE)
                for(int k=Math.max(0,i-2);k<=Math.min(edge.length-1,i+2);k++)
                    if(edge[k]!=Integer.MIN_VALUE)rounded[i]=Math.max(rounded[i],edge[k]-2*Math.abs(k-i));
            int before=b.blocks.size(),attached=0;
            for(int side=-width;side<=width;side++){
                int i=side+width;if(inner[i]==Integer.MIN_VALUE)continue;
                double q=side*side/(double)(width*width);
                int reach=rounded[i]-inner[i];
                int bx=b.origin.getX()+across.getStepX()*side,bz=b.origin.getZ()+across.getStepZ()*side;
                int originProjection=bx*out.getStepX()+bz*out.getStepZ();
                int start=b.blocks.size();
                for(int step=1;step<=reach;step++){
                    int radial=inner[i]-originProjection+step;
                    int x=bx+out.getStepX()*radial,z=bz+out.getStepZ()*radial;
                    BlockState cap=Blocks.BROWN_MUSHROOM_BLOCK.defaultBlockState().setValue(BlockStateProperties.DOWN,false);
                    b.put(x,y,z,cap,Mode.OPEN);
                    if(step<reach-1&&q<.8)b.put(x,y-1,z,cap,Mode.OPEN);
                }
                if(b.blocks.size()>start)attached++;
            }
            retainConnectedShelf(b,y);
            if(attached>=5&&b.blocks.size()-before>=40)placed++;
        }
        return placed;
    }
    private void retainConnectedShelf(Builder b,int y){
        Set<BlockPos> remaining=new HashSet<>(),largest=Set.of();
        for(var p:b.blocks.keySet())if(p.getY()==y)remaining.add(p);
        while(!remaining.isEmpty()){
            Set<BlockPos> component=new HashSet<>();var queue=new ArrayDeque<BlockPos>();
            var first=remaining.iterator().next();remaining.remove(first);queue.add(first);
            while(!queue.isEmpty()){
                var p=queue.removeFirst();component.add(p);
                for(Direction direction:Direction.Plane.HORIZONTAL){
                    var next=p.relative(direction);
                    if(remaining.remove(next))queue.addLast(next);
                }
            }
            if(component.size()>largest.size())largest=component;
        }
        Set<BlockPos> connected=largest;
        b.blocks.keySet().removeIf(p->p.getY()==y&&!connected.contains(p)||p.getY()==y-1&&!connected.contains(p.above()));
    }
    private BlockState patchSoil(Builder b,int x,int z,boolean root){
        double q=b.patchNoise(x*.13,z*.13,root?237:229);
        return q<.40?Blocks.PODZOL.defaultBlockState():q>.80?palette().humus:Blocks.MOSS_BLOCK.defaultBlockState();
    }
    private void patchPlants(Builder b,int x,int y,int z,boolean wet){
        double choice=b.unit(x,z,241);
        if(choice<.10){b.put(x,y,z,leaves,Mode.OPEN);if(choice<.035)b.put(x,y+1,z,leaves,Mode.OPEN);}
        else if(choice<.24)b.plant(x,y,z,palette().mushroom);
        else if(choice<.30)b.plant(x,y,z,Blocks.RED_MUSHROOM.defaultBlockState());
        else if(choice<.67)b.greenery(x,y,z,wet);
        else if(choice<.9)b.plant(x,y,z,Blocks.MOSS_CARPET.defaultBlockState());
    }
    private void garden(Builder b){
        int core=8+b.r.nextInt(5),fringe=7,radius=core+fringe+3;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            double w=b.cover(dx,dz,core,core*.8,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;var c=f.column(x,z);int y=c.ecologySurface(b.origin.getY()-6,b.origin.getY()+6,false);
            if(y==Integer.MIN_VALUE||c.kind(y+1)!=WorldTreeField.Kind.AIR||c.kind(y+2)!=WorldTreeField.Kind.AIR)continue;
            if(w>.66&&b.unit(x,z,181)<w){
                b.put(x,y+1,z,patchSoil(b,x,z,false),Mode.OPEN);patchPlants(b,x,y+2,z,false);
            }else if(b.unit(x,z,191)<w*.82)b.plant(x,y+1,z,Blocks.MOSS_CARPET.defaultBlockState());
        }
    }
    private void rootPatch(Builder b,boolean detritus){
        int core=detritus?6+b.r.nextInt(4):14+b.r.nextInt(5),fringe=detritus?6:9,radius=core+fringe+3;
        for(int dz=-radius;dz<=radius;dz++)for(int dx=-radius;dx<=radius;dx++){
            double w=b.cover(dx,dz,core,detritus?core*.8:6,fringe);if(w<=0)continue;
            int x=b.origin.getX()+dx,z=b.origin.getZ()+dz;var c=f.column(x,z);int y=c.ecologySurface(b.origin.getY()-9,b.origin.getY()+9,false);
            if(y==Integer.MIN_VALUE||y<f.seaLevel-1)continue;
            long h=b.hash(x,y,z);
            if(w>.66&&b.unit(x,z,193)<w){
                b.put(x,y+1,z,detritus&&(h&3)==0?wood:patchSoil(b,x,z,true),Mode.OPEN);
                patchPlants(b,x,y+2,z,false);
                if(detritus&&(h&7)==0)b.put(x,y+2,z,leaves,Mode.OPEN);
            }else if(b.unit(x,z,197)<w*.84)b.plant(x,y+1,z,Blocks.MOSS_CARPET.defaultBlockState());
        }
    }
    private void rootlets(Builder b){
        if(b.origin.getY()>f.seaLevel+13||f.column(b.origin.getX(),b.origin.getZ()).floor>f.seaLevel-3)return;
        int count=3+b.r.nextInt(3);
        for(int n=0;n<count;n++){
            double angle=b.r.nextDouble()*Math.PI*2;int length=7+b.r.nextInt(10);
            BlockPos last=b.origin;
            for(int i=1;i<=length;i++){
                double t=i/(double)length,turn=angle+.75*Math.sin(t*2.4+n);
                int x=b.origin.getX()+(int)Math.round(Math.cos(turn)*i*.85),z=b.origin.getZ()+(int)Math.round(Math.sin(turn)*i*.85);
                int floor=f.column(x,z).floor,y=(int)Math.round(b.origin.getY()-(b.origin.getY()-floor+1)*t*t);
                BlockPos end=new BlockPos(x,y,z);int steps=Math.max(Math.abs(x-last.getX()),Math.max(Math.abs(y-last.getY()),Math.abs(z-last.getZ())));
                BlockPos previous=last;
                for(int k=1;k<=Math.max(1,steps);k++){
                    double a=k/(double)Math.max(1,steps);int xx=(int)Math.round(last.getX()+(x-last.getX())*a),yy=(int)Math.round(last.getY()+(y-last.getY())*a),zz=(int)Math.round(last.getZ()+(z-last.getZ())*a);
                    b.put(xx,previous.getY(),previous.getZ(),i<length*.6?wood:Blocks.MANGROVE_ROOTS.defaultBlockState(),Mode.OPEN);
                    b.put(xx,previous.getY(),zz,i<length*.6?wood:Blocks.MANGROVE_ROOTS.defaultBlockState(),Mode.OPEN);
                    b.put(xx,yy,zz,i<length*.6?wood:Blocks.MANGROVE_ROOTS.defaultBlockState(),Mode.OPEN);
                    if(i<4)b.put(xx+1,yy,zz,wood,Mode.OPEN);
                    previous=new BlockPos(xx,yy,zz);
                }
                last=end;
            }
        }
    }
    List<Plan> forChunk(ChunkPos cp){
        List<Plan> result=new ArrayList<>();
        for(int z=Math.floorDiv(cp.getMinBlockZ()-REACH,CELL);z<=Math.floorDiv(cp.getMaxBlockZ()+REACH,CELL);z++)
            for(int x=Math.floorDiv(cp.getMinBlockX()-REACH,CELL);x<=Math.floorDiv(cp.getMaxBlockX()+REACH,CELL);x++)
                for(Plan plan:plans(x,z))if(plan.bounds.intersects(cp.getMinBlockX(),cp.getMinBlockZ(),cp.getMaxBlockX(),cp.getMaxBlockZ()))result.add(plan);
        return result;
    }
    static boolean allowed(Plan plan,List<BoundingBox> protectedBoxes){
        for(BoundingBox box:protectedBoxes)if(plan.bounds.intersects(box))return false;
        return true;
    }
    static boolean area(Type type){
        return switch(type){case HUMUS_MAT,BRANCH_GARDEN,ROOT_RAFT,LILY_COLONY,SEAGRASS_MEADOW,MOSS_ROOT_STEPS,ROOT_DETRITUS->true;default->false;};
    }
    static boolean allowedVoxel(Voxel v,List<BoundingBox> boxes){
        if(boxes.isEmpty())return true;
        BlockPos p=v.pos;BoundingBox group=new BoundingBox(p.getX(),p.getY(),p.getZ(),p.getX(),p.getY(),p.getZ());
        var piece=v.state.getBlock().getStateDefinition().getProperty("piece");
        if(piece!=null&&BuiltInRegistries.BLOCK.getKey(v.state.getBlock()).toString().equals("twilightforest:huge_lily_pad")){
            String part=v.state.getValue(piece).toString().toLowerCase(Locale.ROOT);
            int x=p.getX()-(part.contains("e")?1:0),z=p.getZ()-(part.contains("s")?1:0);
            group=new BoundingBox(x,p.getY(),z,x+1,p.getY(),z+1);
        }else if(v.state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)){
            int y=p.getY()-(v.state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF)==DoubleBlockHalf.UPPER?1:0);
            group=new BoundingBox(p.getX(),y,p.getZ(),p.getX(),y+1,p.getZ());
        }
        for(BoundingBox box:boxes)if(box.intersects(group))return false;
        return true;
    }
    static List<BoundingBox> protectedBoxes(WorldGenLevel level,ChunkPos cp,StructureManager structures){
        List<BoundingBox> protectedBoxes=new ArrayList<>();Set<Object> seen=new HashSet<>();
        for(int dz=-2;dz<=2;dz++)for(int dx=-2;dx<=2;dx++){
            ChunkPos neighbor=new ChunkPos(cp.x+dx,cp.z+dz);
            var references=level.getChunk(neighbor.x,neighbor.z,ChunkStatus.STRUCTURE_REFERENCES).getAllReferences();
            for(var entry:references.entrySet())for(long key:entry.getValue()){
                ChunkPos startPos=new ChunkPos(key);
                if(!level.hasChunk(startPos.x,startPos.z)){
                    protectedBoxes.add(new BoundingBox(neighbor.getMinBlockX(),level.getMinBuildHeight(),neighbor.getMinBlockZ(),neighbor.getMaxBlockX(),level.getMaxBuildHeight()-1,neighbor.getMaxBlockZ()).inflatedBy(12));continue;
                }
                var start=structures.getStartForStructure(SectionPos.of(startPos,level.getMinSection()),entry.getKey(),level.getChunk(startPos.x,startPos.z,ChunkStatus.STRUCTURE_STARTS));
                if(start!=null&&start.isValid()&&seen.add(start))protectedBoxes.add(start.getBoundingBox().inflatedBy(12));
            }
        }
        return protectedBoxes;
    }
    void decorate(WorldGenLevel level,ChunkAccess chunk,List<BoundingBox> protectedBoxes){
        ChunkPos cp=chunk.getPos();List<Plan> plans=forChunk(cp);if(plans.isEmpty())return;
        Set<BlockPos> changed=new HashSet<>();
        for(Plan plan:plans){
            boolean overlay=area(plan.type);
            if(!overlay&&!allowed(plan,protectedBoxes))continue;
            for(int pass=0;pass<2;pass++)for(Voxel v:plan.byChunk.getOrDefault(cp.toLong(),List.of())){
                if((v.mode==Mode.PLANT)!=(pass==1))continue;
                if(overlay&&!allowedVoxel(v,protectedBoxes))continue;
                BlockPos p=v.pos;
                BlockState target=v.state;
                if(v.mode==Mode.PLANT&&(target.is(Blocks.GRASS)||target.is(Blocks.TALL_GRASS))
                        &&p.getY()>=f.seaLevel&&p.getY()<=f.seaLevel+3
                        &&(chunk.getBlockState(p.below()).is(Blocks.MUD)||MudflatPlants.mudPatch(f,p.getX(),p.getZ())))
                    target=Blocks.BROWN_MUSHROOM.defaultBlockState();
                if(v.mode==Mode.PLANT&&(target.is(Blocks.BROWN_MUSHROOM)||target.is(Blocks.LILY_OF_THE_VALLEY))
                        &&p.getY()>=f.seaLevel&&p.getY()<=f.seaLevel+3
                        &&(chunk.getBlockState(p.below()).is(Blocks.MUD)||MudflatPlants.mudPatch(f,p.getX(),p.getZ()))
                        &&!MudflatPlants.shoreFlowers(f,p.getX(),p.getZ(),f.pointSeed(p.getX(),0,p.getZ())&Long.MAX_VALUE))continue;
                BlockState old=chunk.getBlockState(p);
                if(old.hasBlockEntity())continue;
                if(v.mode==Mode.BARK){
                    if(!old.equals(wood)||f.column(p.getX(),p.getZ()).kind(p.getY())!=WorldTreeField.Kind.WOOD||!allowedVoxel(v,protectedBoxes))continue;
                }else if(old.is(BlockTags.LOGS)||old.is(wood.getBlock())||old.is(BlockTags.LEAVES)&&plan.type!=Type.CANOPY_NEST)continue;
                boolean replaceable=old.isAir()||old.is(Blocks.WATER)||old.is(Blocks.GRASS)||old.is(Blocks.FERN)||old.is(Blocks.TALL_GRASS)||old.is(Blocks.LARGE_FERN)||old.is(BlockTags.FLOWERS)||old.is(Blocks.SEAGRASS)||old.is(Blocks.TALL_SEAGRASS)||old.is(Blocks.KELP)||old.is(Blocks.KELP_PLANT)||old.is(Blocks.LILY_PAD);
                if(v.mode==Mode.EARTH){if(!(old.is(BlockTags.DIRT)||old.is(Blocks.MUD)||old.is(Blocks.GRAVEL)||old.is(Blocks.CLAY)||old.is(Blocks.DEEPSLATE)))continue;}
                else if(v.mode!=Mode.BARK&&!replaceable&&!(plan.type==Type.CANOPY_NEST&&old.is(BlockTags.LEAVES)))continue;
                if(v.mode==Mode.PLANT&&target.is(Blocks.BROWN_MUSHROOM)&&chunk.getBlockState(p.below()).is(Blocks.MUD)){
                    if(!MudflatPlants.mushroom(level,chunk,p))continue;
                }else{
                    if(v.mode==Mode.PLANT&&!target.canSurvive(level,p))continue;
                    chunk.setBlockState(p,target,false);
                }
                changed.add(p);changed.add(p.above());changed.add(p.above(2));
            }
        }
        var ordered=new ArrayList<>(changed);ordered.sort(Comparator.comparingInt(BlockPos::getY));
        for(BlockPos p:ordered){
            BlockState s=chunk.getBlockState(p);
            if((s.getBlock() instanceof net.minecraft.world.level.block.BushBlock||s.getBlock() instanceof net.minecraft.world.level.block.GrowingPlantBlock)&&!s.canSurvive(level,p))
                chunk.setBlockState(p,s.getFluidState().createLegacyBlock(),false);
        }
    }
}
