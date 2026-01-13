package org.fredoseep;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableRowSorter;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

public class MainFrame extends JFrame {

    // UI Components
    private JProgressBar progressBar;
    private JLabel statusLabel;
    private JLabel pathLabel;
    private JLabel timeSinceLabel;

    private JTable itemTable;
    private DefaultTableModel tableModel;
    private TableRowSorter<DefaultTableModel> sorter;

    private JCheckBox showMissingCheck;
    private JTextField searchField;

    // Logic
    private final ItemDictionary dictionary;
    private TrackerManager currentManager;
    private TrackerManager.TrackerStats lastStats;
    private Timer uiRefreshTimer;
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");

    public MainFrame(ItemDictionary dictionary) {
        this.dictionary = dictionary;
        setTitle("Minecraft 1.21 Item Tracker (Ultimate Edition)");
        setSize(1000, 750);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setLocationRelativeTo(null);

        initUI();
        startUiTimer();
    }

    private void initUI() {
        // === Top: Path Selection ===
        JPanel topControlPanel = new JPanel(new BorderLayout(10, 10));
        topControlPanel.setBorder(BorderFactory.createTitledBorder("World Selection"));
        pathLabel = new JLabel("No world selected");
        pathLabel.setForeground(Color.BLUE);
        JButton changeSaveBtn = new JButton("Change World...");
        changeSaveBtn.addActionListener(e -> openSaveSelector());
        topControlPanel.add(pathLabel, BorderLayout.CENTER);
        topControlPanel.add(changeSaveBtn, BorderLayout.EAST);

        // === Filter Panel ===
        JPanel filterPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 15, 5));
        filterPanel.add(new JLabel("Search:"));
        searchField = new JTextField(20);
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            public void insertUpdate(DocumentEvent e) { updateFilters(); }
            public void removeUpdate(DocumentEvent e) { updateFilters(); }
            public void changedUpdate(DocumentEvent e) { updateFilters(); }
        });
        filterPanel.add(searchField);

        showMissingCheck = new JCheckBox("Show Missing Only (Hides Ignored)");
        showMissingCheck.addActionListener(e -> updateFilters());
        filterPanel.add(showMissingCheck);

        // 增加一个提示文本
        JLabel tipLabel = new JLabel("(Right-click item to Ignore)");
        tipLabel.setForeground(Color.GRAY);
        tipLabel.setFont(new Font("Arial", Font.ITALIC, 12));
        filterPanel.add(tipLabel);

        // === Status Panel ===
        JPanel statusPanel = new JPanel(new BorderLayout(5, 5));
        statusPanel.setBorder(BorderFactory.createEmptyBorder(5, 10, 5, 10));
        statusLabel = new JLabel("Waiting for data...", SwingConstants.CENTER);
        statusLabel.setFont(new Font("Arial", Font.BOLD, 16));
        progressBar = new JProgressBar(0, 100);
        progressBar.setStringPainted(true);
        progressBar.setPreferredSize(new Dimension(800, 25));
        timeSinceLabel = new JLabel("Last save: Unknown", SwingConstants.RIGHT);
        timeSinceLabel.setFont(new Font("Monospaced", Font.PLAIN, 12));
        timeSinceLabel.setForeground(Color.GRAY);

        statusPanel.add(statusLabel, BorderLayout.NORTH);
        statusPanel.add(progressBar, BorderLayout.CENTER);
        statusPanel.add(timeSinceLabel, BorderLayout.SOUTH);

        // === Table Area ===
        String[] columns = {"Item ID", "Status"};
        tableModel = new DefaultTableModel(columns, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
            @Override
            public Class<?> getColumnClass(int columnIndex) { return String.class; }
        };

        itemTable = new JTable(tableModel) {
            @Override
            public String getToolTipText(MouseEvent e) {
                int row = rowAtPoint(e.getPoint());
                if (row > -1 && lastStats != null) {
                    int modelRow = convertRowIndexToModel(row);
                    String itemId = (String) tableModel.getValueAt(modelRow, 0);

                    // 获取时间数据
                    TrackerManager.ItemTimeline timeline = lastStats.collectedMap().get(itemId);

                    if (timeline != null) {
                        String firstSeenStr = timeFormat.format(new Date(timeline.firstSeen));
                        String lastSeenStr = timeFormat.format(new Date(timeline.lastSeen));

                        // 判断逻辑：如果 LastSeen 和现在很接近（比如1分钟内），说明“正在背包里”
                        // 这只是为了 UI 显示更友好，不影响核心逻辑
                        long now = System.currentTimeMillis();
                        String statusSuffix = "";
                        if (now - timeline.lastSeen < 60000) {
                            statusSuffix = " <span style='color:green'>(In Inventory)</span>";
                        } else {
                            statusSuffix = " <span style='color:gray'>(In Storage/Lost)</span>";
                        }

                        return "<html>" +
                                "<div style='padding:5px; font-size:10px'>" +
                                "<b>Item:</b> " + itemId + "<br>" +
                                "<b>First Tracked:</b> " + firstSeenStr + "<br>" +
                                "<b>Last Seen:</b> " + lastSeenStr + statusSuffix +
                                "</div></html>";
                    }
                }
                return super.getToolTipText(e);
            }
        };
        itemTable.setFont(new Font("Segoe UI", Font.PLAIN, 14));
        itemTable.setRowHeight(24);

        // 1. 设置颜色渲染器 (处理忽略颜色)
        itemTable.setDefaultRenderer(String.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                int modelRow = table.convertRowIndexToModel(row);
                String status = (String) tableModel.getValueAt(modelRow, 1);

                c.setFont(c.getFont().deriveFont(Font.BOLD));

                if ("COLLECTED".equals(status)) {
                    c.setForeground(new Color(0, 150, 0)); // Green
                } else if ("IGNORED".equals(status)) {
                    c.setForeground(new Color(200, 160, 0)); // Yellow/Orange
                } else {
                    c.setForeground(Color.RED); // Red
                }
                return c;
            }
        });

        // 2. 添加右键菜单 (忽略功能)
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem ignoreItem = new JMenuItem("Toggle Ignore Status");
        ignoreItem.addActionListener(e -> {
            int selectedRow = itemTable.getSelectedRow();
            if (selectedRow != -1 && currentManager != null) {
                // 获取选中的物品ID
                int modelRow = itemTable.convertRowIndexToModel(selectedRow);
                String itemId = (String) tableModel.getValueAt(modelRow, 0);
                // 调用 Manager 切换状态
                currentManager.toggleIgnore(itemId);
            }
        });
        popupMenu.add(ignoreItem);
        itemTable.setComponentPopupMenu(popupMenu);

        // 为了更好的体验，右键点击时自动选中该行
        itemTable.addMouseListener(new MouseAdapter() {
            public void mousePressed(MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    int r = itemTable.rowAtPoint(e.getPoint());
                    if (r >= 0 && r < itemTable.getRowCount()) {
                        itemTable.setRowSelectionInterval(r, r);
                    }
                }
            }
        });

        sorter = new TableRowSorter<>(tableModel);
        itemTable.setRowSorter(sorter);

        JPanel northContainer = new JPanel();
        northContainer.setLayout(new BoxLayout(northContainer, BoxLayout.Y_AXIS));
        northContainer.add(topControlPanel);
        northContainer.add(filterPanel);
        northContainer.add(statusPanel);

        add(northContainer, BorderLayout.NORTH);
        add(new JScrollPane(itemTable), BorderLayout.CENTER);
    }

    private void startUiTimer() {
        uiRefreshTimer = new Timer(1000, e -> {
            if (lastStats != null && lastStats.lastSaveTime() > 0) {
                long diff = System.currentTimeMillis() - lastStats.lastSaveTime();
                long seconds = diff / 1000;

                String timeText;
                if (seconds < 60) {
                    timeText = seconds + "s ago";
                } else {
                    timeText = (seconds / 60) + "m " + (seconds % 60) + "s ago";
                }

                timeSinceLabel.setText("Save file modified: " + timeText);

                // 如果超过 5 分钟 (300秒) 存档没变动，提示变红
                if (seconds > 300) {
                    timeSinceLabel.setForeground(Color.RED);
                    timeSinceLabel.setText("Save file modified: " + timeText + " (Try /save-all)");
                } else {
                    timeSinceLabel.setForeground(new Color(0, 100, 0)); // Dark Green
                }
            }
        });
        uiRefreshTimer.start();
    }

    private void updateFilters() {
        String text = searchField.getText();
        boolean missingOnly = showMissingCheck.isSelected();
        List<RowFilter<Object, Object>> filters = new ArrayList<>();

        if (text != null && !text.trim().isEmpty()) {
            filters.add(RowFilter.regexFilter("(?i)" + text, 0));
        }

        if (missingOnly) {
            // 逻辑: 显示 Missing Only = 状态必须是 "MISSING"
            // 这意味着 "COLLECTED" 和 "IGNORED" 都会被隐藏
            filters.add(new RowFilter<Object, Object>() {
                @Override
                public boolean include(Entry<?, ?> entry) {
                    String status = (String) entry.getValue(1);
                    return "MISSING".equals(status);
                }
            });
        }

        if (filters.isEmpty()) sorter.setRowFilter(null);
        else sorter.setRowFilter(RowFilter.andFilter(filters));
    }

    public void startTracking(File saveDir) {
        if (currentManager != null) currentManager.stop();
        pathLabel.setText("Tracking: " + saveDir.getName());
        pathLabel.setToolTipText(saveDir.getAbsolutePath());
        statusLabel.setText("Scanning...");
        currentManager = new TrackerManager(saveDir.getAbsolutePath(), dictionary);
        currentManager.setOnUpdateCallback(this::updateView);
        currentManager.startScanning();
    }

    private void openSaveSelector() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setCurrentDirectory(new File(System.getProperty("user.home"), "AppData/Roaming/.minecraft/saves"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            startTracking(chooser.getSelectedFile());
        }
    }

    public void updateView(TrackerManager.TrackerStats stats) {
        this.lastStats = stats;
        SwingUtilities.invokeLater(() -> {
            progressBar.setMaximum(stats.totalCount());
            progressBar.setValue(stats.collectedCount());
            double percent = (double) stats.collectedCount() / stats.totalCount() * 100;
            statusLabel.setText(String.format("Progress: %d / %d (%.2f%%)", stats.collectedCount(), stats.totalCount(), percent));
            updateTableData(stats);
            // 每次数据更新时，重新应用一次过滤器，以防忽略状态改变后没有即时隐藏
            updateFilters();
        });
    }

    private void updateTableData(TrackerManager.TrackerStats stats) {
        // 保存当前滚动条位置和选中状态（简易版：可能会丢失选中）
        tableModel.setRowCount(0);
        List<String> allItems = new ArrayList<>(stats.allItemsSet());
        allItems.sort(String::compareTo);

        for (String item : allItems) {
            String status;
            if (stats.collectedMap().containsKey(item)) {
                status = "COLLECTED";
            } else if (stats.ignoredSet().contains(item)) {
                status = "IGNORED";
            } else {
                status = "MISSING";
            }
            tableModel.addRow(new Object[]{item, status});
        }
    }
}