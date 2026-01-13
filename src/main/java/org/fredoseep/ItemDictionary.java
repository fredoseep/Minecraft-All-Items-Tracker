package org.fredoseep;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class ItemDictionary {
    private final Set<String> allKnownItems;

    public ItemDictionary(Path itemFilePath) {
        this.allKnownItems = loadItems(itemFilePath);
    }

    private Set<String> loadItems(Path path) {

        File absoluteFile = path.toAbsolutePath().toFile();
        if (!absoluteFile.exists()) {
            javax.swing.JOptionPane.showMessageDialog(null,
                    "大兄弟，我找不到文件！\n" +
                            "我试图读取的路径是:\n" +
                            absoluteFile.getAbsolutePath() +
                            "\n\n请确认该路径下真的有 items.txt 吗？"
            );
            return new HashSet<>();
        }

        Set<String> items = new HashSet<>();

        // 1. 检查文件是否存在
        if (!Files.exists(path)) {
            System.err.println("警告: 数据文件未找到 -> " + path.toAbsolutePath());
            return items;
        }

        try {
            System.out.println("正在解析物品数据库: " + path.getFileName());

            // 2. 读取全部内容 (Java 11+ 特性，非常方便)
            String jsonContent = Files.readString(path);

            // 3. 使用正则提取 ID
            // 解释: \"(minecraft:[a-z0-9_]+)\"\s*:
            // \"       -> 匹配开头的引号
            // (...)    -> 捕获组，提取里面的内容
            // minecraft:[a-z0-9_]+ -> 匹配 minecraft: 开头，后面跟着字母数字或下划线
            // \"       -> 匹配结尾的引号
            // \s* -> 允许引号和冒号之间有空格
            // :        -> 关键！匹配冒号，确保这是一个 Key 而不是 Value
            Pattern pattern = Pattern.compile("\"(minecraft:[a-z0-9_]+)\"\\s*:");
            Matcher matcher = pattern.matcher(jsonContent);

            while (matcher.find()) {
                String id = matcher.group(1);

                // 4. 过滤掉不需要的物品
                // 空气不能进背包，必须剔除，否则永远无法达成 100%
                if (!id.equals("minecraft:air") && !id.equals("minecraft:cave_air") && !id.equals("minecraft:void_air")) {
                    items.add(id);
                }
            }

            System.out.println(">>> 成功加载 " + items.size() + " 个物品 ID");

        } catch (IOException e) {
            e.printStackTrace();
            System.err.println("读取文件出错！");
        }

        return items;
    }

    public Set<String> getAllItems() {
        return Collections.unmodifiableSet(allKnownItems);
    }

    public int getTotalCount() {
        return allKnownItems.size();
    }
}