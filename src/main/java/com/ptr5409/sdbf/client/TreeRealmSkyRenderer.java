package com.ptr5409.sdbf.client;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.ptr5409.sdbf.TreeRealmMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

/** 绘制静态的绿色深穹渐变与树冠光束，不加入周期波纹。 */
@Mod.EventBusSubscriber(modid=TreeRealmMod.MOD_ID,value=Dist.CLIENT)
public final class TreeRealmSkyRenderer {
    private static final ResourceLocation DIMENSION=new ResourceLocation(TreeRealmMod.MOD_ID,"tree_realm");
    private static final float RADIUS=100.0F;
    private static final float[] ELEVATIONS={-90.0F,-48.0F,-24.0F,0.0F,22.0F,45.0F,67.0F,82.0F,90.0F};

    private TreeRealmSkyRenderer() {}

    @SubscribeEvent
    public static void render(RenderLevelStageEvent event) {
        if(event.getStage()!=RenderLevelStageEvent.Stage.AFTER_SKY)return;
        ClientLevel level=Minecraft.getInstance().level;
        if(level==null||!DIMENSION.equals(level.dimension().location()))return;
        draw(event.getPoseStack(),event.getPartialTick());
    }

    private static void draw(PoseStack pose,float partialTick) {
        Matrix4f matrix=pose.last().pose();
        Tesselator tessellator=Tesselator.getInstance();
        BufferBuilder builder=tessellator.getBuilder();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        builder.begin(VertexFormat.Mode.QUADS,DefaultVertexFormat.POSITION_COLOR);
        dome(builder,matrix);
        crownRays(builder,matrix);
        BufferUploader.drawWithShader(builder.end());
        RenderSystem.depthMask(true);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
        RenderSystem.setShaderColor(1.0F,1.0F,1.0F,1.0F);
    }

    private static void dome(BufferBuilder builder,Matrix4f matrix) {
        int segments=48;
        for(int ring=0;ring<ELEVATIONS.length-1;ring++) {
            float lower=(float)Math.toRadians(ELEVATIONS[ring]);
            float upper=(float)Math.toRadians(ELEVATIONS[ring+1]);
            for(int i=0;i<segments;i++) {
                float a0=(float)(Math.PI*2*i/segments),a1=(float)(Math.PI*2*(i+1)/segments);
                vertex(builder,matrix,sphere(lower,a0),color(lower));
                vertex(builder,matrix,sphere(lower,a1),color(lower));
                vertex(builder,matrix,sphere(upper,a1),color(upper));
                vertex(builder,matrix,sphere(upper,a0),color(upper));
            }
        }
    }

    private static void crownRays(BufferBuilder builder,Matrix4f matrix) {
        int rays=14;
        for(int ray=0;ray<rays;ray++) {
            float az=(float)(Math.PI*2*(ray+.23)/rays);
            float previousElevation=7.0F;
            float previousCenter=az;
            float previousWidth=.020F+.004F*(ray%3);
            for(int step=1;step<=8;step++) {
                float elevation=7.0F+step*9.0F;
                float center=az+.045F*(float)Math.sin(ray*1.7+step*.55);
                float width=.018F+.004F*(float)Math.sin(ray*2.1+step*.7);
                addRayBand(builder,matrix,previousElevation,elevation,previousCenter,center,previousWidth,width);
                previousElevation=elevation;previousCenter=center;previousWidth=width;
            }
        }
    }

    private static void addRayBand(BufferBuilder builder,Matrix4f matrix,float low,float high,
                                   float lowCenter,float highCenter,float lowWidth,float highWidth) {
        float[] offsets={-1.0F,-.38F,.38F,1.0F};
        float[] alpha={0.0F,.055F,.07F,0.0F};
        for(int i=0;i<offsets.length-1;i++) {
            vertex(builder,matrix,sphere((float)Math.toRadians(low),lowCenter+offsets[i]*lowWidth),rayColor(alpha[i]));
            vertex(builder,matrix,sphere((float)Math.toRadians(low),lowCenter+offsets[i+1]*lowWidth),rayColor(alpha[i+1]));
            vertex(builder,matrix,sphere((float)Math.toRadians(high),highCenter+offsets[i+1]*highWidth),rayColor(alpha[i+1]));
            vertex(builder,matrix,sphere((float)Math.toRadians(high),highCenter+offsets[i]*highWidth),rayColor(alpha[i]));
        }
    }

    private static float[] rayColor(float alpha) {
        return new float[]{.24F,.72F,.39F,alpha};
    }

    private static float[] color(float elevation) {
        float t=Math.max(0.0F,Math.min(1.0F,(elevation+(float)Math.toRadians(48.0))/(float)Math.toRadians(138.0)));
        t=t*t*(3.0F-2.0F*t);
        float r=.008F+.095F*t,g=.028F+.30F*t,b=.024F+.18F*t;
        float alpha=.98F-.08F*t;
        return new float[]{r,g,b,alpha};
    }

    private static float[] sphere(float elevation,float azimuth) {
        float horizontal=(float)Math.cos(elevation);
        return new float[]{RADIUS*horizontal*(float)Math.sin(azimuth),RADIUS*(float)Math.sin(elevation),RADIUS*horizontal*(float)Math.cos(azimuth)};
    }

    private static void vertex(VertexConsumer consumer,Matrix4f matrix,float[] position,float[] color) {
        consumer.vertex(matrix,position[0],position[1],position[2]).color(
                toByte(color[0]),toByte(color[1]),toByte(color[2]),toByte(color[3])).endVertex();
    }

    private static int toByte(float value) {
        return Math.max(0,Math.min(255,Math.round(value*255.0F)));
    }
}
