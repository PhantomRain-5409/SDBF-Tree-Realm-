package com.ptr5409.sdbf.worldgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraftforge.gametest.GameTestHolder;
import net.minecraftforge.gametest.PrefixGameTestTemplate;
import java.nio.file.Files;
import java.nio.file.Path;

/** 在独立 Forge 环境核对资源注册与实际树维度区块生成。 */
@GameTestHolder("sdbf")
@PrefixGameTestTemplate(false)
public final class TreeRealmSmokeTest {
    @GameTest(template = "empty", timeoutTicks = 1200)
    public static void resourcesAndTerrain(GameTestHelper helper) throws Exception {
        var realm = helper.getLevel().getServer().getLevel(ResourceKey.create(
                Registries.DIMENSION, new ResourceLocation("sdbf", "tree_realm")));
        helper.assertTrue(realm != null, "Missing tree dimension");
        helper.assertTrue(realm.getChunkSource().getGenerator() instanceof WorldTreeSeaChunkGenerator,
                "Wrong chunk generator");
        Path data = Path.of(System.getProperty("sdbf.testData"));
        try (var files = Files.walk(data.resolve("structures"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".nbt")).toList()) {
                CompoundTag tag;
                try (var stream = Files.newInputStream(path)) {
                    tag = NbtIo.readCompressed(stream);
                }
                var palette = tag.getList("palette", 10);
                for (int i = 0; i < palette.size(); i++) {
                    var entry = palette.getCompound(i);
                    var id = new ResourceLocation(entry.getString("Name"));
                    helper.assertTrue(BuiltInRegistries.BLOCK.containsKey(id), "Missing block " + id);
                    var block = BuiltInRegistries.BLOCK.get(id);
                    var properties = entry.getCompound("Properties");
                    for (String key : properties.getAllKeys()) {
                        Property<?> property = block.getStateDefinition().getProperty(key);
                        helper.assertTrue(property != null && property.getValue(properties.getString(key)).isPresent(),
                                "Invalid property " + id + " " + key + "=" + properties.getString(key));
                    }
                }
                String template = data.resolve("structures").relativize(path).toString()
                        .replace('\\', '/').replace(".nbt", "");
                helper.assertTrue(realm.getStructureManager().getOrCreate(new ResourceLocation("sdbf", template))
                        .getSize().getX() > 0, "Empty template " + template);
            }
        }
        try (var files = Files.walk(data.resolve("loot_tables"))) {
            for (Path path : files.filter(p -> p.toString().endsWith(".json")).toList()) {
                String table = data.resolve("loot_tables").relativize(path).toString()
                        .replace('\\', '/').replace(".json", "");
                helper.assertTrue(realm.getServer().getLootData().getLootTable(new ResourceLocation("sdbf", table))
                        != LootTable.EMPTY, "Missing loot table " + table);
            }
        }
        var generator = (WorldTreeSeaChunkGenerator) realm.getChunkSource().getGenerator();
        var tree = generator.field().tree(0, 0);
        int x = (int) Math.round(tree.x + tree.radius * 0.8);
        int z = (int) Math.round(tree.z);
        var chunk = realm.getChunk(x >> 4, z >> 4);
        int wood = 0;
        for (int dx = 0; dx < 16; dx++) for (int dz = 0; dz < 16; dz++) {
            BlockPos at = new BlockPos((x & ~15) + dx, 120, (z & ~15) + dz);
            if (chunk.getBlockState(at).is(Blocks.DARK_OAK_WOOD)) wood++;
        }
        helper.assertTrue(wood > 0, "Giant tree trunk was not generated");
        BlockPos arrival = WorldTreeSeaChunkGenerator.findArrival(realm, 0, 0);
        helper.assertTrue(realm.getBlockState(arrival.below()).isCollisionShapeFullBlock(realm, arrival.below()),
                "Arrival has no solid support");
        helper.assertTrue(realm.getBlockState(arrival).getCollisionShape(realm, arrival).isEmpty()
                        && realm.getBlockState(arrival.above()).getCollisionShape(realm, arrival.above()).isEmpty()
                        && realm.getFluidState(arrival).isEmpty(),
                "Arrival is obstructed");
        int shallowDrift=0,deepDrift=0,trunkMoss=0,shelves=0,nests=0;
        var deepPlans=new java.util.ArrayList<WorldTreeEcology.Plan>();
        var barkPlans=new java.util.ArrayList<WorldTreeEcology.Plan>();
        var shelfPlans=new java.util.ArrayList<WorldTreeEcology.Plan>();
        boolean deepX=false,deepZ=false;
        for(int cx=-8;cx<=8;cx++)for(int cz=-8;cz<=8;cz++){
            for(var plan:generator.ecology().plans(cx,cz)){
                if(plan.type()==WorldTreeEcology.Type.DRIFTWOOD){
                    helper.assertTrue(generator.field().column(plan.origin().getX(),plan.origin().getZ()).zone==WorldTreeField.Zone.SHALLOWS,
                            "Old driftwood is outside shallows");
                    shallowDrift++;
                    helper.assertTrue(plan.origin().getY()==generator.getSeaLevel()-1,"Driftwood is not at water surface");
                }
                if(plan.type()==WorldTreeEcology.Type.DEEP_DRIFTWOOD){
                    deepDrift++;deepPlans.add(plan);
                    var logs=plan.blocks().stream().filter(v->v.state().is(Blocks.DARK_OAK_LOG)).toList();
                    helper.assertTrue(!logs.isEmpty(),"Missing deep driftwood body");
                    var axis=logs.get(0).state().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS);
                    boolean alongX=axis==net.minecraft.core.Direction.Axis.X;
                    deepX|=alongX;deepZ|=!alongX;
                    int width=alongX?plan.bounds().getZSpan():plan.bounds().getXSpan();
                    int length=alongX?plan.bounds().getXSpan():plan.bounds().getZSpan();
                    helper.assertTrue(width==2&&length>=8&&length<=12&&logs.size()==4*length,"Deep driftwood is not a solid 2x2xN log");
                    for(var log:logs){
                        helper.assertTrue(log.pos().getY()>=generator.getSeaLevel()-2&&log.pos().getY()<=generator.getSeaLevel()-1,
                                "Deep driftwood did not sink by one block");
                        helper.assertTrue(generator.field().column(log.pos().getX(),log.pos().getZ()).zone==WorldTreeField.Zone.DEPTHS,
                                "Deep driftwood is outside deep water");
                        helper.assertTrue(generator.field().coastalDeepWater(plan.origin().getX(),plan.origin().getZ()),"Driftwood generated in open ocean");
                        helper.assertTrue(log.state().getValue(net.minecraft.world.level.block.state.properties.BlockStateProperties.AXIS)==axis,
                                "Deep driftwood log axes differ");
                    }
                }
                if(plan.type()==WorldTreeEcology.Type.TRUNK_MOSS){
                    trunkMoss++;
                    barkPlans.add(plan);
                    for(var voxel:plan.blocks())if(voxel.mode()==WorldTreeEcology.Mode.BARK)
                        helper.assertTrue(generator.field().column(voxel.pos().getX(),voxel.pos().getZ()).kind(voxel.pos().getY())==WorldTreeField.Kind.WOOD
                                &&generator.field().column(voxel.pos().getX(),voxel.pos().getZ()).kind(voxel.pos().getY()+1)==WorldTreeField.Kind.AIR,"Trunk patch is not on an exposed ledge");
                    helper.assertTrue(plan.blocks().stream().filter(v->v.mode()==WorldTreeEcology.Mode.BARK).count()<=16,"Trunk patch is too large");
                }
                helper.assertTrue(plan.type()!=WorldTreeEcology.Type.CANOPY_NEST,"Cancelled nest is still generated");
                if(plan.type()==WorldTreeEcology.Type.SHELF_FUNGI){
                    shelves++;shelfPlans.add(plan);
                    helper.assertTrue(plan.blocks().stream().filter(v->v.state().is(Blocks.BROWN_MUSHROOM_BLOCK)).count()>160,"Shelf fungi too small");
                    long layers=plan.blocks().stream().filter(v->v.state().is(Blocks.BROWN_MUSHROOM_BLOCK))
                        .map(v->Math.floorDiv(v.pos().getY()-plan.origin().getY()+1,5)).distinct().count();
                    helper.assertTrue(layers>=4,"Fungi group has fewer than four shelves");
                    for(int layer=0;layer<5;layer++){
                        int shelfY=plan.origin().getY()+layer*5;
                        var remaining=new java.util.HashSet<BlockPos>();
                        for(var voxel:plan.blocks())if(voxel.pos().getY()==shelfY)remaining.add(voxel.pos());
                        if(remaining.isEmpty())continue;
                        var queue=new java.util.ArrayDeque<BlockPos>();
                        var first=remaining.iterator().next();remaining.remove(first);queue.add(first);
                        while(!queue.isEmpty()){
                            var pos=queue.removeFirst();
                            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                                var next=pos.relative(direction);if(remaining.remove(next))queue.addLast(next);
                            }
                        }
                        helper.assertTrue(remaining.isEmpty(),"Shelf breaks apart around a bark corner");
                    }
                    helper.assertTrue(plan.origin().getY()>=100&&plan.origin().getY()<=195,"Fungi group is outside the middle trunk");
                }
                for(var voxel:plan.blocks()){
                    helper.assertTrue((!voxel.state().is(Blocks.BROWN_MUSHROOM_BLOCK)||plan.type()==WorldTreeEcology.Type.SHELF_FUNGI)
                            &&!voxel.state().is(Blocks.RED_MUSHROOM_BLOCK)
                            &&!voxel.state().is(Blocks.MUSHROOM_STEM)
                            &&!voxel.state().is(Blocks.CAVE_VINES)
                            &&!voxel.state().is(Blocks.CAVE_VINES_PLANT),"Removed decoration still generated");
                }
            }
        }
        helper.assertTrue(shallowDrift>0&&deepDrift>0,"Driftwood is missing from shallows or deep water");
        helper.assertTrue(deepX&&deepZ,"Deep driftwood is missing an orientation");
        for(int i=0;i<deepPlans.size();i++)for(int j=0;j<i;j++){
            var a=deepPlans.get(i).origin();var b=deepPlans.get(j).origin();
            helper.assertTrue(Math.hypot(a.getX()-b.getX(),a.getZ()-b.getZ())>=14,"Deep driftwood candidates overlap");
        }
        boolean actualDrift=false;int driftAttempts=0;
        for(var plan:deepPlans){
            if(driftAttempts++>=12||actualDrift)break;
            var logs=plan.blocks().stream().filter(v->v.state().is(Blocks.DARK_OAK_LOG)).toList();
            for(var log:logs)realm.getChunk(log.pos());
            actualDrift=logs.stream().allMatch(v->realm.getBlockState(v.pos()).equals(v.state()));
        }
        helper.assertTrue(actualDrift,"Solid deep driftwood was not naturally generated");
        helper.assertTrue(trunkMoss>0&&shelves>0&&nests==0,"Trunk patches or fungi are missing, or nests remain");
        boolean actualBark=false;int barkAttempts=0;
        for(var plan:barkPlans){
            if(barkAttempts++>=12||actualBark)break;
            for(var voxel:plan.blocks())if(voxel.mode()==WorldTreeEcology.Mode.BARK){
                realm.getChunk(voxel.pos());
                if(realm.getBlockState(voxel.pos()).equals(voxel.state())){actualBark=true;break;}
            }
        }
        helper.assertTrue(actualBark,"Embedded bark patches were not naturally generated");
        for(int i=0;i<shelfPlans.size();i++)for(int j=0;j<i;j++){
            var a=shelfPlans.get(i).bounds();var b=shelfPlans.get(j).bounds();
            double dx=Math.max(0,Math.max(a.minX()-b.maxX()-1,b.minX()-a.maxX()-1));
            double dy=Math.max(0,Math.max(a.minY()-b.maxY()-1,b.minY()-a.maxY()-1));
            double dz=Math.max(0,Math.max(a.minZ()-b.maxZ()-1,b.minZ()-a.maxZ()-1));
            helper.assertTrue(Math.sqrt(dx*dx+dy*dy+dz*dz)>=12,"Separate fungi colonies are too close");
        }
        boolean actualShelf=false;
        for(int cx=-8;cx<=8&&!actualShelf;cx++)for(int cz=-8;cz<=8&&!actualShelf;cz++)
            for(var plan:generator.ecology().plans(cx,cz))if(plan.type()==WorldTreeEcology.Type.SHELF_FUNGI){
                for(long key:plan.byChunk().keySet()){var cp=new net.minecraft.world.level.ChunkPos(key);realm.getChunk(cp.x,cp.z);}
                long count=plan.blocks().stream().filter(v->v.state().is(Blocks.BROWN_MUSHROOM_BLOCK)&&realm.getBlockState(v.pos()).equals(v.state())).count();
                if(count>160){actualShelf=true;break;}
            }
        helper.assertTrue(actualShelf,"Larger attached shelf fungi were not naturally generated");
        var mudCounts=new java.util.HashMap<net.minecraft.world.level.block.Block,Integer>();int mudAttempts=0;
        var requiredMud=java.util.List.of(Blocks.BROWN_MUSHROOM,Blocks.LILY_OF_THE_VALLEY,Blocks.BIG_DRIPLEAF,Blocks.FLOWERING_AZALEA,Blocks.SMALL_DRIPLEAF,Blocks.SEA_PICKLE);
        for(int cx=140;cx<=220&&mudAttempts<40&&!mudCounts.keySet().containsAll(requiredMud);cx++)
            for(int cz=140;cz<=220&&mudAttempts<40&&!mudCounts.keySet().containsAll(requiredMud);cz++){
                int px=cx*16+8,pz=cz*16+8;
                if(!MudflatPlants.mudPatch(generator.field(),px,pz))continue;
                var mudChunk=realm.getChunk(cx,cz);mudAttempts++;
                for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++){
                    int wx=cx*16+dx,wz=cz*16+dz;
                    if(generator.field().zoneAt(wx,wz)!=WorldTreeField.Zone.SHALLOWS)continue;
                    for(int y=generator.getSeaLevel()-3;y<=generator.getSeaLevel()+3;y++){
                        var at=new BlockPos(wx,y,wz);var plant=mudChunk.getBlockState(at);
                        if(requiredMud.contains(plant.getBlock())){
                            mudCounts.merge(plant.getBlock(),1,Integer::sum);
                            helper.assertTrue(plant.canSurvive(realm,at),"Mudflat plant cannot survive: "+plant);
                        }
                        if(MudflatPlants.mudPatch(generator.field(),wx,wz)&&y==generator.getSeaLevel()
                                &&!MudflatPlants.continuousShore(generator.field(),wx,wz))
                            helper.assertTrue(!plant.is(Blocks.BROWN_MUSHROOM)&&!plant.is(Blocks.LILY_OF_THE_VALLEY),"Flowers still grow on isolated mud blocks");
                        if(MudflatPlants.mudPatch(generator.field(),wx,wz)&&y>=generator.getSeaLevel())
                            helper.assertTrue(!plant.is(Blocks.GRASS)&&!plant.is(Blocks.TALL_GRASS),"Grass still grows on mudflat patches");
                    }
                }
            }
        helper.assertTrue(mudCounts.keySet().containsAll(requiredMud),"Mudflat vegetation missing after "+mudAttempts+" chunks: "+mudCounts);
        System.out.println("Mudflat natural plants: "+mudCounts+", candidate chunks="+mudAttempts);
        boolean mangrove=false;
        int attempts=0;
        for(int cx=140;cx<=180&&!mangrove&&attempts<12;cx++)for(int cz=140;cz<=180&&!mangrove&&attempts<12;cz++){
            BlockPos at=ShallowsMangroveFeature.candidate(generator.field(),cx,cz);
            if(at==null||generator.field().column(at.getX(),at.getZ()).kind(generator.getSeaLevel()-1)!=WorldTreeField.Kind.WATER)continue;
            var mangroveChunk=realm.getChunk(at);
            attempts++;
            for(int dx=0;dx<16&&!mangrove;dx++)for(int dz=0;dz<16&&!mangrove;dz++)for(int y=64;y<=95;y++){
                if(mangroveChunk.getBlockState(new BlockPos(mangroveChunk.getPos().getMinBlockX()+dx,y,
                        mangroveChunk.getPos().getMinBlockZ()+dz)).is(Blocks.MANGROVE_LOG)){mangrove=true;break;}
            }
        }
        helper.assertTrue(mangrove,"Vanilla mangroves were not naturally generated after "+attempts+" candidates");
        boolean coral=false;int coralAttempts=0,coralBlocks=0,kelpBlocks=0;
        for(int cx=260;cx<=380&&!coral&&coralAttempts<12;cx++)for(int cz=260;cz<=380&&!coral&&coralAttempts<12;cz++){
            BlockPos at=DeepWaterCoralFeature.candidate(generator.field(),cx,cz);
            if(at==null)continue;
            var coralChunk=realm.getChunk(at);coralAttempts++;
            for(int dx=0;dx<16;dx++)for(int dz=0;dz<16;dz++){
                int px=coralChunk.getPos().getMinBlockX()+dx,pz=coralChunk.getPos().getMinBlockZ()+dz;
                int floor=generator.field().floorAt(px,pz);
                for(int y=floor+1;y<generator.getSeaLevel();y++){
                    var state=coralChunk.getBlockState(new BlockPos(px,y,pz));
                    if(state.is(net.minecraft.tags.BlockTags.CORAL_BLOCKS))coralBlocks++;
                    if(state.is(Blocks.KELP)||state.is(Blocks.KELP_PLANT))kelpBlocks++;
                }
            }
            coral=coralBlocks>0&&kelpBlocks>0;
        }
        helper.assertTrue(coral,"Sparse coral and kelp did not coexist after "+coralAttempts+" candidates");
        helper.assertTrue(coralBlocks<500,"Coral coverage is excessive in sparse test chunks");
        SunkenStumpField.Stump stump=null;
        for(int cx=-12;cx<=12&&stump==null;cx++)for(int cz=-12;cz<=12&&stump==null;cz++)stump=generator.stumps().candidate(cx,cz);
        helper.assertTrue(stump!=null,"No deep-sea stump candidate");
        var center=stump.cutPosition().below(3);
        BlockPos searchOrigin=new BlockPos(stump.x()+900,generator.getSeaLevel(),stump.z()+700);
        var located=generator.findNearestSunkenStump(searchOrigin,2048).orElseThrow();
        SunkenStumpField.Stump nearest=null;double best=2048.0*2048;
        for(int cx=Math.floorDiv(searchOrigin.getX()-2048,SunkenStumpField.CELL);cx<=Math.floorDiv(searchOrigin.getX()+2048,SunkenStumpField.CELL);cx++)
            for(int cz=Math.floorDiv(searchOrigin.getZ()-2048,SunkenStumpField.CELL);cz<=Math.floorDiv(searchOrigin.getZ()+2048,SunkenStumpField.CELL);cz++){
                var candidate=generator.stumps().candidate(cx,cz);if(candidate==null)continue;
                var cut=candidate.cutPosition();
                double distance=Math.pow(cut.getX()-(double)searchOrigin.getX(),2)+Math.pow(cut.getZ()-(double)searchOrigin.getZ(),2);
                if(distance<best){best=distance;nearest=candidate;}
            }
        helper.assertTrue(nearest!=null&&located.equals(nearest.cutPosition()),"Locator did not find the nearest planned stump");
        helper.assertTrue(generator.field().column(stump.x(),stump.z()).highestSolid(true)<210,"Stump is not beneath a canopy gap");
        var source=realm.getServer().createCommandSourceStack().withLevel(realm)
            .withPosition(new net.minecraft.world.phys.Vec3(searchOrigin.getX(),searchOrigin.getY(),searchOrigin.getZ()));
        int commandResult=realm.getServer().getCommands().performPrefixedCommand(source,"tree_realm locate_stump 2048");
        helper.assertTrue(commandResult==1,"Sunken stump locate command failed");
        helper.assertTrue(stump.floor()<=generator.getSeaLevel()-40&&stump.top()>=generator.getSeaLevel()+2
                &&stump.top()<=generator.getSeaLevel()+18,"Stump cut height is outside the varied exposed range");
        var cutHeights=new java.util.HashSet<Integer>();
        int sampledStumps=0,dryStumps=0,multiplePools=0;
        var poolSizes=new java.util.HashSet<Integer>();
        SunkenStumpField.Stump tinyPoolStump=null,largePoolStump=null;
        poolSamples:
        for(int cx=-12;cx<=12;cx++)for(int cz=-12;cz<=12;cz++){
            var candidate=generator.stumps().candidate(cx,cz);
            if(candidate==null)continue;
            cutHeights.add(candidate.top());sampledStumps++;
            if(candidate.pools().isEmpty())dryStumps++;
            if(candidate.pools().size()>1)multiplePools++;
            helper.assertTrue(candidate.pools().size()<=3,"Too many ponds crowd the stump cut");
            for(var pool:candidate.pools()){
                poolSizes.add(pool.water().size());
                if(pool.water().size()<=2&&tinyPoolStump==null)tinyPoolStump=candidate;
                if(pool.water().size()>=5&&largePoolStump==null)largePoolStump=candidate;
            }
            if(sampledStumps>=40&&cutHeights.size()>=10&&dryStumps>0&&multiplePools>0
                    &&poolSizes.contains(1)&&poolSizes.contains(2)&&largePoolStump!=null)break poolSamples;
        }
        helper.assertTrue(cutHeights.size()>=10&&java.util.Collections.max(cutHeights)-java.util.Collections.min(cutHeights)>=12,
                "Stump cut heights remain too uniform");
        helper.assertTrue(dryStumps>0&&multiplePools>0&&poolSizes.contains(1)&&poolSizes.contains(2)&&largePoolStump!=null,
                "Ponds lack natural variation: dry="+dryStumps+", multiple="+multiplePools+", sizes="+poolSizes);
        var disabled=new WorldTreeSeaChunkGenerator((WorldTreeSeaBiomeSource)generator.getBiomeSource(),
                Blocks.DARK_OAK_WOOD.defaultBlockState(),Blocks.DARK_OAK_LEAVES.defaultBlockState(),
                java.util.List.of(Blocks.GRASS_BLOCK.defaultBlockState()),Blocks.AMETHYST_CLUSTER.defaultBlockState(),java.util.Optional.empty(),false);
        var randomState=realm.getChunkSource().randomState();
        helper.assertTrue(disabled.getBaseColumn(center.getX(),center.getZ(),realm,randomState).getBlock(center.getY()).isAir(),
                "Disabling sunken stumps did not restore the original terrain");
        helper.assertTrue(disabled.findNearestSunkenStump(searchOrigin,2048).isEmpty(),"Disabled stump locator returned a target");
        int stumpWood=0,underwaterWood=0,clearAboveCut=0;
        for(int dz=-stump.radius()-20;dz<=stump.radius()+20;dz+=6)for(int dx=-stump.radius()-20;dx<=stump.radius()+20;dx+=6){
            int px=stump.x()+dx,pz=stump.z()+dz;
            var profile=generator.stumps().column(px,pz,generator.stumps().nearby(px,pz));
            if(profile==null)continue;
            var baseColumn=generator.getBaseColumn(px,pz,realm,randomState);
            BlockPos at=new BlockPos(px,stump.top()-4,pz);realm.getChunk(at);
            if(profile.top()!=Integer.MIN_VALUE&&profile.woodAt(at.getY())){
                helper.assertTrue(realm.getBlockState(at).is(Blocks.STRIPPED_DARK_OAK_LOG),"Stump cut surface did not span chunks");
                stumpWood++;
            }
            helper.assertTrue(realm.getBlockState(at).equals(baseColumn.getBlock(at.getY())),"Stump base column differs from generated terrain");
            int y=generator.getSeaLevel()-20;
            if(profile.woodAt(y)){
                helper.assertTrue(realm.getBlockState(new BlockPos(px,y,pz)).is(Blocks.DARK_OAK_WOOD),"Stump does not use the live-tree wood");underwaterWood++;
            }
            helper.assertTrue(realm.getBlockState(new BlockPos(px,stump.top()+4,pz)).isAir(),"Uncut tree remains above the stump");clearAboveCut++;
        }
        helper.assertTrue(stumpWood>20&&underwaterWood>20,"Too little full-size stump geometry was naturally generated: cut="+stumpWood+", underwater="+underwaterWood);
        int redMushrooms=0,azaleas=0,embedded=0,pools=0;
        var relief=new java.util.HashSet<Integer>();
        var cutCenter=stump.tree().trunkCenter(stump.top());
        for(int dz=-stump.radius();dz<=stump.radius();dz+=2)for(int dx=-stump.radius();dx<=stump.radius();dx+=2){
            int px=(int)Math.round(cutCenter.x())+dx,pz=(int)Math.round(cutCenter.z())+dz;
            var profile=generator.stumps().column(px,pz,java.util.List.of(stump));
            if(profile==null||profile.top()==Integer.MIN_VALUE)continue;
            helper.assertTrue(profile.top()>=stump.top()-1&&profile.top()<=stump.top()+1&&profile.top()>generator.getSeaLevel(),
                    "Cut relief is excessive or submerged");
            relief.add(profile.top());
            var at=new BlockPos(px,profile.top(),pz);realm.getChunk(at);
            helper.assertTrue(realm.getBlockState(at).equals(profile.surface()),"Cut relief or soil was not naturally placed");
            if(profile.underSurface()!=null){
                helper.assertTrue(realm.getBlockState(at.below()).equals(profile.underSurface()),"Soil patch is not embedded into stump");embedded++;
            }
            var plant=profile.plant();if(plant==null)continue;
            helper.assertTrue(realm.getBlockState(at.above()).equals(plant),"Cut decoration differs from planned terrain");
            if(plant.is(Blocks.RED_MUSHROOM))redMushrooms++;
            if(plant.is(Blocks.FLOWERING_AZALEA))azaleas++;
            if(plant.is(Blocks.WATER)){
                pools++;
                helper.assertTrue(realm.getBlockState(at).isSolid(),"Cut pool has no sealed floor");
                for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                    var neighbor=at.above().relative(direction);
                    var state=generator.getBaseColumn(neighbor.getX(),neighbor.getZ(),realm,randomState).getBlock(neighbor.getY());
                    helper.assertTrue(state.is(Blocks.WATER)||state.isSolid(),"Cut pool has a leaking bank");
                }
            }else helper.assertTrue(plant.canSurvive(realm,at.above()),"Cut vegetation cannot survive");
        }
        helper.assertTrue(relief.size()==3&&redMushrooms>0&&azaleas>0&&embedded>20,
                "Missing cut ecology: relief="+relief+", red="+redMushrooms+", azalea="+azaleas+", soil="+embedded+", pools="+pools);
        realm.getChunk(stump.cutPosition());
        helper.assertTrue(realm.getBlockState(stump.cutPosition()).isAir()&&realm.getBlockState(stump.cutPosition().above()).isAir(),
                "Stump locator target is obstructed by relief or vegetation");
        System.out.println("Cut ecology natural: relief="+relief+", red="+redMushrooms+", azalea="+azaleas+", embedded="+embedded+", pool="+pools);
        int pondWater=0;
        for(var wetStump:java.util.List.of(tinyPoolStump,largePoolStump))for(var pool:wetStump.pools())for(long key:pool.water()){
            int px=(int)(key>>32),pz=(int)key;
            var at=new BlockPos(px,wetStump.top(),pz);realm.getChunk(at);
            helper.assertTrue(realm.getBlockState(at).is(Blocks.WATER)&&realm.getBlockState(at.below()).is(Blocks.PODZOL),
                    "Random pond water or sealed floor was not naturally generated");
            for(var direction:net.minecraft.core.Direction.Plane.HORIZONTAL){
                var neighbor=at.relative(direction);realm.getChunk(neighbor);
                var state=realm.getBlockState(neighbor);
                helper.assertTrue(state.is(Blocks.WATER)||state.isSolid(),"Irregular pond has a leaking bank");
            }
            pondWater++;
        }
        helper.assertTrue(pondWater>=6,"Too few tiny and irregular pond blocks were naturally sampled");
        System.out.println("Random cut ponds: stumps="+sampledStumps+", dry="+dryStumps+", multiple="+multiplePools+", sizes="+poolSizes+", natural water="+pondWater);
        int rootSamples=0;
        for(var segment:stump.tree().wood){
            if(rootSamples>=6)break;
            double distance=Math.hypot(segment.a.x()-stump.x(),segment.a.z()-stump.z());
            if(distance<80||distance>200||segment.a.y()>=generator.getSeaLevel()-10||segment.a.y()<=WorldTreeField.MIN_Y+2||segment.ra<3)continue;
            int px=(int)Math.round(segment.a.x()),pz=(int)Math.round(segment.a.z()),py=(int)Math.round(segment.a.y());
            var profile=generator.stumps().column(px,pz,generator.stumps().nearby(px,pz));
            helper.assertTrue(profile!=null&&profile.woodAt(py),"Live-tree curved root is missing from stump geometry");
            var at=new BlockPos(px,py,pz);realm.getChunk(at);
            helper.assertTrue(realm.getBlockState(at).is(net.minecraft.tags.BlockTags.LOGS),"Curved stump root was not naturally placed");rootSamples++;
        }
        helper.assertTrue(rootSamples==6,"Insufficient large curved root samples");
        var template=stump.tree();int varied=0;
        for(int y=(int)template.base+10;y<stump.top()-8;y+=7){
            var trunkCenter=template.trunkCenter(y);
            for(int dx=-55;dx<=55;dx+=5)for(int dz=-55;dz<=55;dz+=5){
                int px=(int)Math.round(trunkCenter.x())+dx,pz=(int)Math.round(trunkCenter.z())+dz;
                boolean expected=template.trunkContains(px,y,pz);
                if(!expected)for(var segment:template.segmentsAt(px,pz))if(segment.contains(px,y,pz)){expected=true;break;}
                var profile=generator.stumps().column(px,pz,java.util.List.of(stump));
                helper.assertTrue((profile!=null&&profile.woodAt(y))==expected,"Stump body differs from standard tree geometry");
                varied++;
            }
        }
        helper.assertTrue(varied>1000,"Insufficient standard-tree shape samples");
        System.out.println("Sunken live-tree stump natural: x="+stump.x()+", z="+stump.z()+", cut="+stump.top()+", tree radius="+stump.radius()
            +", curved roots sampled="+rootSamples+", reference voxels="+varied+", cut columns="+clearAboveCut);
        var depths=realm.registryAccess().registryOrThrow(Registries.BIOME).get(new ResourceLocation("sdbf","world_tree_depths"));
        var loop=depths.getAmbientLoop().orElseThrow().value();
        helper.assertTrue(BuiltInRegistries.SOUND_EVENT.getKey(loop).toString().equals("minecraft:ambient.underwater.loop"),
                "Deep water is still using the cave loop");
        System.out.println("Ecology checks: shallow drift="+shallowDrift+", deep drift="+deepDrift
                +", nests="+nests+", shelves="+shelves+", trunk moss="+trunkMoss+", embedded bark natural="+actualBark+", deep drift natural="+actualDrift+", vanilla mangrove attempts="+attempts
                +", shelves natural="+actualShelf+", kelp blocks="+kelpBlocks+", coral blocks="+coralBlocks+", coral attempts="+coralAttempts+", deep sound="+BuiltInRegistries.SOUND_EVENT.getKey(loop));
        helper.succeed();
    }
}
