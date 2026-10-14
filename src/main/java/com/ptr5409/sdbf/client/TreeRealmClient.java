package com.ptr5409.sdbf.client;

import com.ptr5409.sdbf.TreeRealmMod;
import net.minecraft.client.renderer.DimensionSpecialEffects;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RegisterDimensionSpecialEffectsEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

/** 注册巨树之境的客户端天空与雾效。 */
@Mod.EventBusSubscriber(modid=TreeRealmMod.MOD_ID,value=Dist.CLIENT,bus=Mod.EventBusSubscriber.Bus.MOD)
public final class TreeRealmClient {
    private TreeRealmClient() {}

    @SubscribeEvent
    public static void registerEffects(RegisterDimensionSpecialEffectsEvent event) {
        event.register(new ResourceLocation(TreeRealmMod.MOD_ID,"tree_realm"),new TreeRealmEffects());
    }

    private static final class TreeRealmEffects extends DimensionSpecialEffects {
        TreeRealmEffects() {
            super(128.0F,true,SkyType.NONE,false,false);
        }

        @Override
        public net.minecraft.world.phys.Vec3 getBrightnessDependentFogColor(
                net.minecraft.world.phys.Vec3 color,float sunHeight) {
            double factor=.72+.10*sunHeight;
            return new net.minecraft.world.phys.Vec3(
                    color.x*.34*factor+.008,
                    color.y*.78*factor+.025,
                    color.z*.58*factor+.018);
        }

        @Override
        public boolean isFoggyAt(int x,int z) {
            return false;
        }
    }
}
