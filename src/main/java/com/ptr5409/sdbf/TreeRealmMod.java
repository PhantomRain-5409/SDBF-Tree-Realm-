package com.ptr5409.sdbf;

import com.ptr5409.sdbf.command.TreeRealmCommand;
import com.ptr5409.sdbf.worldgen.RealmWorldgenRegistry;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** 巨树之境的注册入口。 */
@Mod(TreeRealmMod.MOD_ID)
public final class TreeRealmMod {
    public static final String MOD_ID = "sdbf";

    public TreeRealmMod() {
        RealmWorldgenRegistry.register(FMLJavaModLoadingContext.get().getModEventBus());
        MinecraftForge.EVENT_BUS.addListener(TreeRealmCommand::register);
    }
}
