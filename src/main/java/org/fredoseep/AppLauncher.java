package org.fredoseep;

import javax.swing.*;
import java.io.File;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AppLauncher {

    // 默认扫描根目录
    private static final String SAVES_ROOT_DIR = "C:\\Users\\lenovo\\Downloads\\.minecraft\\saves";

    public static void main(String[] args) {
        try { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()); } catch (Exception ignored) {}

        // 1. 加载物品库
        ItemDictionary dictionary = new ItemDictionary(Path.of("items.txt"));
        if (dictionary.getTotalCount() == 0) {
            JOptionPane.showMessageDialog(null, "警告: items.txt 为空或未找到！");
        }

        // 2. 初始化界面 (传入 dictionary)
        MainFrame frame = new MainFrame(dictionary);
        frame.setVisible(true);

        // 3. 尝试自动寻找存档
        File autoFoundSave = findLatestWorld(SAVES_ROOT_DIR);

        if (autoFoundSave != null) {
            System.out.println("自动载入存档: " + autoFoundSave.getName());
            // 直接调用 UI 的方法开始追踪
            frame.startTracking(autoFoundSave);
        } else {
            // 如果没找到，弹窗提示用户手动选
            JOptionPane.showMessageDialog(frame, "未自动检测到 'New World' 系列存档。\n请点击界面顶部的按钮手动选择。");
        }
    }

    // (保持原有的 findLatestWorld 方法不变)
    private static File findLatestWorld(String rootPath) {
        File root = new File(rootPath);
        if (!root.exists() || !root.isDirectory()) return null;
        File[] candidates = root.listFiles((dir, name) ->
                new File(dir, name).isDirectory() && name.startsWith("New World")
        );
        if (candidates == null || candidates.length == 0) return null;

        File bestMatch = null;
        int maxIndex = -1;
        Pattern pattern = Pattern.compile("^New World(?: \\((\\d+)\\))?$");

        for (File folder : candidates) {
            Matcher matcher = pattern.matcher(folder.getName());
            if (matcher.matches()) {
                int index = 0;
                String numStr = matcher.group(1);
                if (numStr != null) {
                    try { index = Integer.parseInt(numStr); } catch (NumberFormatException ignored) {}
                }
                if (index > maxIndex) {
                    maxIndex = index;
                    bestMatch = folder;
                }
            }
        }
        return bestMatch;
    }
}