/*
package io.github.nkymz.containersearchcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

import java.util.Timer;
import java.util.TimerTask;

public class SearchCommand {

    // 定义常用的容器名称建议
    private static final SuggestionProvider<ServerCommandSource> CONTAINER_SUGGESTIONS = (context, builder) ->
            CommandSource.suggestMatching(new String[]{
                    "chest", "barrel", "shulker_box", "trapped_chest", "dispenser", "dropper", "hopper"
            }, builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(CommandManager.literal("fr")
                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                        // 分支 A: 仅物品 (默认半径10)
                        .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), null, 10))

                        // 分支 B: 物品 + 半径 (检测到第二个参数是整数)
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), null, IntegerArgumentType.getInteger(ctx, "radius")))
                        )

                        // 分支 C: 物品 + 容器名 (检测到第二个参数是字符串)
                        // 使用 string() 类型以兼容更多字符，并添加 .suggests() 实现补全
                        .then(CommandManager.argument("containerName", StringArgumentType.string())
                                .suggests(CONTAINER_SUGGESTIONS) // <--- 这里添加了自动补全

                                // 子分支 C1: 只有容器名 (默认半径10)
                                .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), StringArgumentType.getString(ctx, "containerName"), 10))

                                // 子分支 C2: 容器名 + 半径
                                .then(CommandManager.argument("radius_final", IntegerArgumentType.integer(1, 500))
                                        .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), StringArgumentType.getString(ctx, "containerName"), IntegerArgumentType.getInteger(ctx, "radius_final")))
                                )
                        )
                )
        );
    }

private static int executeSearch(CommandContext<ServerCommandSource> ctx, Item targetItem, String containerNameFilter, int radius) {
    try {
        ServerCommandSource source = ctx.getSource();
        // 1. 半径检查
        if (radius > 75) { // 其实优化后开到 100-200 都没问题，但为了安全保持 75
            source.sendFeedback(() -> Text.literal("错误：搜索半径不能超过 75！").formatted(Formatting.RED), false);
            return 0;
        }

        ServerPlayerEntity player = source.getPlayerOrThrow();
        BlockPos playerPos = player.getBlockPos();
        ServerWorld world = source.getWorld();

        source.sendFeedback(() -> Text.literal("正在搜索半径 " + radius + " 内的 " + targetItem.getName().getString() + "...").formatted(Formatting.GRAY), false);

        boolean foundAny = false;

        // --- 优化核心开始 ---

        // 2. 计算涉及的区块范围 (Chunk Coordinates)
        // 坐标除以 16 (右移4位) 得到区块坐标
        int minChunkX = (playerPos.getX() - radius) >> 4;
        int maxChunkX = (playerPos.getX() + radius) >> 4;
        int minChunkZ = (playerPos.getZ() - radius) >> 4;
        int maxChunkZ = (playerPos.getZ() + radius) >> 4;

        // 3. 定义搜索范围的包围盒 (用于过滤掉区块里超出半径的边缘方块)
        // 我们依然使用立方体范围 (Box)
        int minX = playerPos.getX() - radius;
        int maxX = playerPos.getX() + radius;
        int minY = playerPos.getY() - radius;
        int maxY = playerPos.getY() + radius;
        int minZ = playerPos.getZ() - radius;
        int maxZ = playerPos.getZ() + radius;

        // 4. 遍历区块 (而不是遍历方块)
        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {

                // 关键优化：只获取已加载的区块。如果去加载未加载的区块，服务器会瞬间卡死。
                // getChunk(x, z, status, create) -> create=false 表示不强制生成/加载
                if (!world.isChunkLoaded(cx, cz)) {
                    continue;
                }

                // 获取区块对象
                var chunk = world.getChunk(cx, cz);

                // 5. 直接获取区块内的所有方块实体 (Map<BlockPos, BlockEntity>)
                // 这一步直接拿到了名单，不用去敲门了
                for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                    BlockPos pos = blockEntity.getPos();

                    // 6. 坐标范围检查
                    // 因为区块包含 16x16 的区域，可能会有一些方块虽然在区块内，但超出了我们的 radius 范围
                    if (pos.getX() < minX || pos.getX() > maxX ||
                            pos.getY() < minY || pos.getY() > maxY ||
                            pos.getZ() < minZ || pos.getZ() > maxZ) {
                        continue;
                    }

                    // 7. 检查是否为容器 (Inventory)
                    if (blockEntity instanceof Inventory inventory) {

                        // --- 容器名过滤器 (逻辑不变) ---
                        if (containerNameFilter != null) {
                            String blockId = net.minecraft.registry.Registries.BLOCK
                                    .getId(blockEntity.getCachedState().getBlock()) // 使用 CachedState 更快
                                    .toString();

                            String filterKey = containerNameFilter.contains(":")
                                    ? containerNameFilter.split(":")[1]
                                    : containerNameFilter;

                            if (!blockId.toLowerCase().contains(filterKey.toLowerCase())) {
                                continue;
                            }
                        }
                        // -----------------------------

                        // 8. 扫描内容
                        int count = scanInventory(inventory, targetItem);

                        if (count > 0) {
                            foundAny = true;
                            sendFoundMessage(source, pos, blockEntity.getCachedState().getBlock().getName().getString(), count, targetItem);
                            highlightBlock(source, pos);
                        }
                    }
                }
            }
        }
        // --- 优化核心结束 ---

        if (!foundAny) {
            source.sendFeedback(() -> Text.literal("未在附近找到该物品。").formatted(Formatting.RED), false);
        }

        return Command.SINGLE_SUCCESS;
    } catch (Exception e) {
        e.printStackTrace();
        return 0;
    }
}
    /**
     * 扫描库存，支持 1.21.4 的潜影盒 (ContainerComponent) 和 收纳袋 (BundleContentsComponent)
     */
/*
    private static int scanInventory(Inventory inventory, Item targetItem) {
        int totalCount = 0;

        for (int i = 0; i < inventory.size(); i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // 1. 直接匹配
            if (stack.isOf(targetItem)) {
                totalCount += stack.getCount();
            }

            // 2. 检查潜影盒 (ContainerComponent)
            ContainerComponent containerData = stack.get(DataComponentTypes.CONTAINER);
            if (containerData != null) {
                totalCount += (int) containerData.stream()
                        .filter(innerStack -> innerStack.isOf(targetItem))
                        .mapToInt(ItemStack::getCount)
                        .sum();
            }

            // 3. 检查收纳袋 (BundleContentsComponent) - 1.21.4 新增重点
            BundleContentsComponent bundleData = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundleData != null) {
                // Bundle 的 stream() 有点不同，通常直接 iterate
                for (int j = 0; j < bundleData.size(); j++) {
                    ItemStack innerStack = bundleData.get(j);
                    if (innerStack.isOf(targetItem)) {
                        totalCount += innerStack.getCount();
                    }
                }
            }
        }
        return totalCount;
    }

    private static void sendFoundMessage(ServerCommandSource source, BlockPos pos, String containerName, int count, Item item) {
        String coordsText = String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());

        Text message = Text.literal("发现 ")
                .append(Text.literal(String.valueOf(count)).formatted(Formatting.GOLD))
                .append(" 个 ")
                .append(Text.translatable(item.getTranslationKey()).formatted(Formatting.AQUA))
                .append(" 位于 ")
                .append(Text.literal(containerName).formatted(Formatting.GREEN))
                .append(" ")
                .append(Text.literal(coordsText)
                        .formatted(Formatting.YELLOW, Formatting.UNDERLINE)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击传送")))
                        )
                );

        source.sendFeedback(() -> message, false);
    }

    // 在容器内部生成小海晶灯核心
    private static void highlightBlock(ServerCommandSource source, BlockPos pos) {
        ServerWorld world = source.getWorld();
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);

        display.setPos(pos.getX(), pos.getY(), pos.getZ());
        display.setBlockState(Blocks.SEA_LANTERN.getDefaultState()); // 核心材质
        display.setGlowing(true); // 开启透视轮廓

        float scale = 0.4f;
        float offset = (1.0f - scale) / 2.0f;

        display.setTransformation(new AffineTransformation(
                new Vector3f(offset, offset, offset),
                null,
                new Vector3f(scale, scale, scale),
                null
        ));

        world.spawnEntity(display);

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                source.getServer().execute(() -> {
                    if (!display.isRemoved()) display.discard();
                });
            }
        }, 10000);
    }
}
 */

package io.github.nkymz.containersearchcommand;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.command.CommandRegistryAccess;
import net.minecraft.command.CommandSource;
import net.minecraft.command.argument.ItemStackArgumentType;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.BundleContentsComponent;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.AffineTransformation;
import net.minecraft.util.math.BlockPos;
import org.joml.Vector3f;

import java.util.Timer;
import java.util.TimerTask;

public class SearchCommand {

    private static final SuggestionProvider<ServerCommandSource> CONTAINER_SUGGESTIONS = (context, builder) ->
            CommandSource.suggestMatching(new String[]{
                    "chest", "barrel", "shulker_box", "trapped_chest", "dispenser", "dropper", "hopper"
            }, builder);

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher, CommandRegistryAccess registryAccess) {
        dispatcher.register(CommandManager.literal("fr")
                .then(CommandManager.argument("item", ItemStackArgumentType.itemStack(registryAccess))
                        .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), null, 10))
                        .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 500))
                                .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), null, IntegerArgumentType.getInteger(ctx, "radius")))
                        )
                        .then(CommandManager.argument("containerName", StringArgumentType.string())
                                .suggests(CONTAINER_SUGGESTIONS)
                                .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), StringArgumentType.getString(ctx, "containerName"), 10))
                                .then(CommandManager.argument("radius_final", IntegerArgumentType.integer(1, 500))
                                        .executes(ctx -> executeSearch(ctx, ItemStackArgumentType.getItemStackArgument(ctx, "item").getItem(), StringArgumentType.getString(ctx, "containerName"), IntegerArgumentType.getInteger(ctx, "radius_final")))
                                )
                        )
                )
        );
    }

    private static int executeSearch(CommandContext<ServerCommandSource> ctx, Item targetItem, String containerNameFilter, int radius) {
        try {
            ServerCommandSource source = ctx.getSource();
            // 半径安全检查
            if (radius > 75) {
                source.sendFeedback(() -> Text.literal("错误：搜索半径不能超过 75！").formatted(Formatting.RED), false);
                return 0;
            }

            ServerPlayerEntity player = source.getPlayerOrThrow();
            BlockPos playerPos = player.getBlockPos();
            ServerWorld world = source.getWorld();

            source.sendFeedback(() -> Text.literal("正在搜索半径 " + radius + " 内的 " + targetItem.getName().getString() + "...").formatted(Formatting.GRAY), false);

            // 【优化 1】 字符串预处理：移出循环
            // 如果用户没有输入过滤器，finalFilter 为 null
            // 如果输入了，我们提前把它处理成 "纯净的小写关键词" (比如用户输 minecraft:Chest -> 存成 chest)
            final String finalFilterKey;
            if (containerNameFilter != null) {
                String key = containerNameFilter.contains(":") ? containerNameFilter.split(":")[1] : containerNameFilter;
                finalFilterKey = key.toLowerCase();
            } else {
                finalFilterKey = null;
            }

            boolean foundAny = false;

            // 区块遍历计算
            int minChunkX = (playerPos.getX() - radius) >> 4;
            int maxChunkX = (playerPos.getX() + radius) >> 4;
            int minChunkZ = (playerPos.getZ() - radius) >> 4;
            int maxChunkZ = (playerPos.getZ() + radius) >> 4;

            // 边界坐标
            int minX = playerPos.getX() - radius;
            int maxX = playerPos.getX() + radius;
            int minY = playerPos.getY() - radius;
            int maxY = playerPos.getY() + radius;
            int minZ = playerPos.getZ() - radius;
            int maxZ = playerPos.getZ() + radius;

            // 开始遍历区块
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {

                    // 只检查已加载区块
                    if (!world.isChunkLoaded(cx, cz)) continue;

                    var chunk = world.getChunk(cx, cz);

                    // 遍历该区块内的方块实体
                    for (BlockEntity blockEntity : chunk.getBlockEntities().values()) {
                        BlockPos pos = blockEntity.getPos();

                        // 快速坐标剔除
                        if (pos.getX() < minX || pos.getX() > maxX ||
                                pos.getY() < minY || pos.getY() > maxY ||
                                pos.getZ() < minZ || pos.getZ() > maxZ) {
                            continue;
                        }

                        if (blockEntity instanceof Inventory inventory) {

                            // 【优化 2】 使用预处理好的字符串进行比较
                            if (finalFilterKey != null) {
                                // 直接获取 ID 字符串，避免创建多余对象
                                String blockId = Registries.BLOCK.getId(blockEntity.getCachedState().getBlock()).getPath();
                                if (!blockId.contains(finalFilterKey)) {
                                    continue;
                                }
                            }

                            // 扫描内容
                            int count = scanInventory(inventory, targetItem);

                            if (count > 0) {
                                foundAny = true;
                                sendFoundMessage(source, pos, blockEntity.getCachedState().getBlock().getName().getString(), count, targetItem);
                                highlightBlock(source, pos);
                            }
                        }
                    }
                }
            }

            if (!foundAny) {
                source.sendFeedback(() -> Text.literal("未在附近找到该物品。").formatted(Formatting.RED), false);
            }

            return Command.SINGLE_SUCCESS;
        } catch (Exception e) {
            e.printStackTrace();
            return 0;
        }
    }

    /**
     * 【优化 3】 极致性能版：移除 Stream 流，全改用 For 循环
     * 避免了创建 Stream 对象和 Lambda 表达式的开销
     */
    private static int scanInventory(Inventory inventory, Item targetItem) {
        int totalCount = 0;
        int size = inventory.size(); // 缓存 inventory 大小

        for (int i = 0; i < size; i++) {
            ItemStack stack = inventory.getStack(i);
            if (stack.isEmpty()) continue;

            // 1. 直接匹配
            if (stack.isOf(targetItem)) {
                totalCount += stack.getCount();
            }

            // 2. 检查潜影盒 (ContainerComponent)
            // 只有当物品可能是容器时才去获取组件 (虽然 .get 内部已经很快了，但可以省去判断)
            ContainerComponent containerData = stack.get(DataComponentTypes.CONTAINER);
            if (containerData != null) {
                // 手动遍历 List，代替 stream().mapToInt().sum()
                for (ItemStack innerStack : containerData.iterateNonEmpty()) {
                    if (innerStack.isOf(targetItem)) {
                        totalCount += innerStack.getCount();
                    }
                }
            }

            // 3. 检查收纳袋 (BundleContentsComponent)
            BundleContentsComponent bundleData = stack.get(DataComponentTypes.BUNDLE_CONTENTS);
            if (bundleData != null) {
                // 1.21.4 Bundle 遍历方法
                for (int j = 0; j < bundleData.size(); j++) {
                    ItemStack innerStack = bundleData.get(j);
                    if (innerStack.isOf(targetItem)) {
                        totalCount += innerStack.getCount();
                    }
                }
            }
        }
        return totalCount;
    }

    private static void sendFoundMessage(ServerCommandSource source, BlockPos pos, String containerName, int count, Item item) {
        String coordsText = String.format("[%d, %d, %d]", pos.getX(), pos.getY(), pos.getZ());

        Text message = Text.literal("发现 ")
                .append(Text.literal(String.valueOf(count)).formatted(Formatting.GOLD))
                .append(" 个 ")
                .append(Text.translatable(item.getTranslationKey()).formatted(Formatting.AQUA))
                .append(" 位于 ")
                .append(Text.literal(containerName).formatted(Formatting.GREEN))
                .append(" ")
                .append(Text.literal(coordsText)
                        .formatted(Formatting.YELLOW, Formatting.UNDERLINE)
                        .styled(style -> style
                                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, "/tp @s " + pos.getX() + " " + pos.getY() + " " + pos.getZ()))
                                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Text.literal("点击传送")))
                        )
                );

        source.sendFeedback(() -> message, false);
    }

    private static void highlightBlock(ServerCommandSource source, BlockPos pos) {
        ServerWorld world = source.getWorld();
        DisplayEntity.BlockDisplayEntity display = new DisplayEntity.BlockDisplayEntity(EntityType.BLOCK_DISPLAY, world);

        display.setPos(pos.getX(), pos.getY(), pos.getZ());
        display.setBlockState(Blocks.SEA_LANTERN.getDefaultState());
        display.setGlowing(true);

        float scale = 0.4f;
        float offset = (1.0f - scale) / 2.0f;

        display.setTransformation(new AffineTransformation(
                new Vector3f(offset, offset, offset),
                null,
                new Vector3f(scale, scale, scale),
                null
        ));

        world.spawnEntity(display);

        new Timer().schedule(new TimerTask() {
            @Override
            public void run() {
                source.getServer().execute(() -> {
                    if (!display.isRemoved()) display.discard();
                });
            }
        }, 10000);
    }
}