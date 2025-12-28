package io.github.nkymz.containersearchcommand;

import io.github.nkymz.containersearchcommand.SearchCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ContainerSearchCommand implements ModInitializer {
    // 定义 Mod ID，方便日志和资源引用
    public static final String MOD_ID = "containersearch";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitialize() {
        // 在服务器启动时注册指令
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            SearchCommand.register(dispatcher, registryAccess);
        });

        LOGGER.info("ContainerSearchCommand initialized!");
    }
}