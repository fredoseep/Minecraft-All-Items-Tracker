package org.fredoseep;

import net.querz.nbt.io.NBTUtil;
import net.querz.nbt.tag.CompoundTag;
import net.querz.nbt.tag.ListTag;
import net.querz.nbt.tag.Tag;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

public class NbtService {

    public Set<String> scanFile(File file) {
        Set<String> itemsFound = new HashSet<>();
        if (!file.exists()) return itemsFound;

        try {
            Tag<?> rootTag = NBTUtil.read(file).getTag();
            if (rootTag instanceof CompoundTag root) {
                // 1. 尝试寻找 Inventory 列表 (Playerdata 标准结构)
                if (root.containsKey("Inventory")) {
                    scanListDeeply(root.getListTag("Inventory"), itemsFound);
                }

                // 2. 尝试寻找 Data.Player.Inventory (Level.dat 标准结构)
                if (root.containsKey("Data")) {
                    CompoundTag data = root.getCompoundTag("Data");
                    if (data.containsKey("Player")) {
                        CompoundTag player = data.getCompoundTag("Player");
                        if (player.containsKey("Inventory")) {
                            scanListDeeply(player.getListTag("Inventory"), itemsFound);
                        }
                    }
                }
            }
        } catch (IOException e) {
            // 文件可能正在被游戏写入，暂时忽略
        }
        return itemsFound;
    }

    /**
     * 递归扫描列表中的每一项
     */
    private void scanListDeeply(ListTag<?> list, Set<String> targetSet) {
        if (list == null) return;
        for (Tag<?> t : list) {
            if (t instanceof CompoundTag itemTag) {
                scanItemTag(itemTag, targetSet);
            }
        }
    }

    /**
     * 核心逻辑：分析单个物品 NBT，并递归查找内部物品
     */
    private void scanItemTag(CompoundTag itemTag, Set<String> targetSet) {
        if (itemTag == null) return;

        // 1. 获取当前层级的物品 ID
        String id = itemTag.getString("id");
        if (id != null && !id.isEmpty()) {
            targetSet.add(id);
        }

        // 2. 深度递归：潜影盒 (Shulker Box)
        // 结构: tag -> BlockEntityTag -> Items
        if (itemTag.containsKey("tag")) {
            CompoundTag tag = itemTag.getCompoundTag("tag");
            if (tag.containsKey("BlockEntityTag")) {
                CompoundTag blockEntity = tag.getCompoundTag("BlockEntityTag");
                if (blockEntity.containsKey("Items")) {
                    scanListDeeply(blockEntity.getListTag("Items"), targetSet);
                }
            }

            // 兼容旧版 Bundle 或其他 Mod 物品的 tag 存储
            if (tag.containsKey("Items")) {
                scanListDeeply(tag.getListTag("Items"), targetSet);
            }
        }

        // 3. 深度递归：1.21+ 收纳袋 (Bundle) 及其他组件
        // 1.20.5+ 引入了 "components" 标签，收纳袋内容通常在 "minecraft:bundle_contents"
        // 由于结构复杂，我们采用“暴力递归”策略：
        // 检查 "components" 下所有的 ListTag，如果里面也是 CompoundTag，就尝试解析
        if (itemTag.containsKey("components")) {
            CompoundTag components = itemTag.getCompoundTag("components");
            // 遍历组件里的所有 Key
            for (String key : components.keySet()) {
                Tag<?> componentData = components.get(key);

                // 如果组件数据是一个列表 (例如 bundle_contents 是一个列表)
                if (componentData instanceof ListTag<?> innerList) {
                    scanListDeeply(innerList, targetSet);
                }
                // 如果组件数据是复合标签 (例如 container)
                else if (componentData instanceof CompoundTag innerComp) {
                    // 有些容器数据可能藏在下一层
                    if (innerComp.containsKey("Items")) {
                        scanListDeeply(innerComp.getListTag("Items"), targetSet);
                    }
                }
            }
        }
    }
}