package personal.todo;

import javax.swing.*;
import javax.swing.border.*;
import javax.swing.event.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;
import java.util.function.*;

final class MainFrame extends JFrame {
    private final Store store;
    Model.Data data;
    private String view = "today", query = "", statusFilter = "全部状态", categoryFilter = "全部分类";
    private LocalDate selected = LocalDate.now(), lastToday = LocalDate.now();
    private final JPanel body = new JPanel(new BorderLayout());
    private final JPanel navigation = Theme.column();
    private final JLabel footer = Theme.label("本地保存 · 离线可用", 11, Theme.MUTED);
    private final javax.swing.Timer reminderTimer;
    private TrayIcon tray;
    private boolean preview, trayHint, rebuilding;
    private JTextArea review;
    private LocalDate reviewDate;
    private javax.swing.Timer reviewTimer;
    private JDialog notification;
    private boolean filtersVisible;
    private boolean reviewExpanded;
    private final Set<String> expandedTasks = new HashSet<>();

    MainFrame(Store store, Model.Data data, boolean preview) {
        super("个人每日计划"); this.store = store; this.data = data; this.preview = preview;
        setIconImage(Theme.icon(64)); setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        Rectangle available = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        setSize(Math.min(1160, available.width - 40), Math.min(900, available.height - 40));
        setMinimumSize(new Dimension(Math.min(960, available.width - 20), Math.min(680, available.height - 20))); setLocationRelativeTo(null);
        JPanel root = new JPanel(new BorderLayout()); root.setBackground(Theme.PAGE); setContentPane(root);
        root.add(sidebar(), BorderLayout.WEST); body.setOpaque(false); body.setBorder(new EmptyBorder(34, 48, 16, 42)); root.add(body);
        addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent e) { closeWindow(); } });
        getRootPane().registerKeyboardAction(e -> newTask(false), KeyStroke.getKeyStroke("control N"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        getRootPane().registerKeyboardAction(e -> navigate("today"), KeyStroke.getKeyStroke("control T"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        if (!preview) installTray();
        reminderTimer = new javax.swing.Timer(20000, e -> tick());
        if (!preview) { reminderTimer.start(); SwingUtilities.invokeLater(this::tick); }
        refresh();
    }
    private JPanel sidebar() {
        JPanel side = new JPanel(new BorderLayout()); side.setBackground(Theme.SIDEBAR); side.setPreferredSize(new Dimension(218, 0));
        side.setBorder(new EmptyBorder(25, 18, 20, 18));
        JPanel brand = Theme.column();
        JPanel brandRow = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0)); brandRow.add(new JLabel(new ImageIcon(Theme.icon(34))));
        JPanel words = Theme.column(); words.setBorder(new EmptyBorder(0, 12, 0, 0)); words.add(Theme.heading("每日计划", 14)); Theme.gap(words, 4);
        words.add(Theme.label("专注今天，保持从容", 10, Theme.MUTED)); brandRow.add(words); brandRow.setAlignmentX(LEFT_ALIGNMENT); brand.add(brandRow); Theme.gap(brand, 28);
        JButton create = Theme.button("+  新建待办", true, () -> newTask(false)); create.setName("newTask"); create.setHorizontalAlignment(SwingConstants.LEFT);
        Theme.bounded(create, 42); brand.add(create); Theme.gap(brand, 24); side.add(brand, BorderLayout.NORTH);
        side.add(navigation, BorderLayout.CENTER);
        JPanel bottom = Theme.column(); JButton folder = Theme.link("打开本地数据目录", () -> {
            try { Files.createDirectories(store.directory); Desktop.getDesktop().open(store.directory.toFile()); } catch (IOException e) { error(e); }
        }); folder.setAlignmentX(LEFT_ALIGNMENT); bottom.add(folder); Theme.gap(bottom, 10);
        bottom.add(Theme.label("  •  本地模式", 11, Theme.MUTED)); Theme.gap(bottom, 12);
        JButton exit = Theme.link("完全退出", this::exit); exit.setAlignmentX(LEFT_ALIGNMENT); bottom.add(exit); side.add(bottom, BorderLayout.SOUTH);
        return side;
    }
    private void navButtons() {
        navigation.removeAll();
        String[][] items = {{"today", "日程", "今天"}, {"tomorrow", "", "明天"}, {"date", "", "选择日期"}, {"plans", "", "全部计划"},
            {"history", "回顾", "历史与复盘"}, {"templates", "", "每日模板"}, {"settings", "偏好", "设置与备份"}};
        for (String[] item : items) {
            if (!item[1].isEmpty()) {
                if (!item[0].equals("today")) Theme.gap(navigation, 18);
                JLabel group = Theme.label("  " + item[1], 11, Theme.MUTED); Theme.bounded(group, 24); navigation.add(group); Theme.gap(navigation, 8);
            }
            JButton b = Theme.nav(item[2], view.equals(item[0]), () -> { if (item[0].equals("date")) chooseDate(); else navigate(item[0]); });
            b.setName("nav-" + item[0]); Theme.bounded(b, 37); navigation.add(b); Theme.gap(navigation, 3);
        }
    }
    void navigate(String target) {
        if (!flushReview()) return;
        view = target; query = ""; statusFilter = "全部状态"; categoryFilter = "全部分类";
        if (target.equals("today")) selected = LocalDate.now();
        if (target.equals("tomorrow")) selected = LocalDate.now().plusDays(1);
        if (target.equals("history")) selected = LocalDate.now().minusDays(1);
        reviewExpanded = target.equals("history");
        refresh();
    }
    private boolean dayView() { return view.equals("today") || view.equals("tomorrow") || view.equals("history"); }
    void refresh() {
        rebuilding = true; review = null; reviewDate = null;
        if (reviewTimer != null) reviewTimer.stop();
        navButtons(); body.removeAll();
        JPanel content = Theme.column();
        switch (view) {
            case "settings" -> settings(content);
            case "templates" -> templates(content);
            default -> tasks(content);
        }
        body.add(content, BorderLayout.CENTER);
        JPanel bottom = Theme.panel(new BorderLayout()); bottom.setBorder(new EmptyBorder(12, 0, 0, 0));
        bottom.add(footer, BorderLayout.WEST); bottom.add(Theme.label("Ctrl + N 新建    ·    Ctrl + T 回到今天", 11, Theme.MUTED), BorderLayout.EAST); body.add(bottom, BorderLayout.SOUTH);
        body.revalidate(); body.repaint(); rebuilding = false;
        if (tray != null) {
            long pending = Model.forDay(data, LocalDate.now()).stream().filter(t -> Model.open(t, LocalDate.now())).count();
            tray.setToolTip("个人每日计划 · 今天还有 " + pending + " 项待办");
        }
    }
    private void header(JPanel content, String title, String subtitle, JButton action) {
        JPanel row = Theme.panel(new BorderLayout(12, 0)); JPanel words = Theme.column(); words.add(Theme.heading(title, 29)); Theme.gap(words, 7);
        words.add(Theme.label(subtitle, 12, Theme.MUTED)); row.add(words);
        if (action != null) { JPanel right = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 0, 8)); right.add(action); row.add(right, BorderLayout.EAST); }
        Theme.bounded(row, 65); content.add(row); Theme.gap(content, 12);
    }
    private void tasks(JPanel content) {
        String title = switch (view) { case "today" -> "今天"; case "tomorrow" -> "明天"; case "history" -> "历史与复盘"; default -> "全部计划"; };
        String subtitle = dayView() ? selected.format(DateTimeFormatter.ofPattern("yyyy 年 M 月 d 日 · EEEE", Locale.CHINA)) : "集中管理单次安排与重复计划。";
        JPanel top = Theme.panel(new BorderLayout(15, 0)); JPanel words = Theme.column(); words.add(Theme.heading(title, 29)); Theme.gap(words, 9); words.add(Theme.label(subtitle, 12, Theme.MUTED)); top.add(words);
        JPanel controls = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 7, 5));
        if (dayView()) { controls.add(Theme.small("‹", () -> shiftDay(-1))); controls.add(Theme.small("›", () -> shiftDay(1))); }
        JButton reminder = Theme.link("●  提醒已开启", () -> navigate("settings")); reminder.setForeground(Theme.INK); controls.add(reminder); top.add(controls, BorderLayout.EAST);
        Theme.bounded(top, 68); content.add(top); Theme.gap(content, 22);
        if (!view.equals("history")) quickAdd(content);
        List<Model.Task> dayTasks = dayView() ? Model.forDay(data, selected) : data.tasks;
        long completed = dayView() ? dayTasks.stream().filter(t -> t.done.contains(selected)).count() : 0;
        JPanel listHeading = Theme.panel(new BorderLayout()); listHeading.add(Theme.heading(dayView() ? "待办" : "计划列表", 16));
        JPanel listActions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        listActions.add(Theme.link(filtersVisible ? "收起筛选" : "筛选", () -> { if (flushReview()) { filtersVisible = !filtersVisible; refresh(); } }));
        if (dayView()) listActions.add(Theme.label(completed + " / " + dayTasks.size() + " 已完成", 12, Theme.MUTED));
        else listActions.add(Theme.label(dayTasks.size() + " 项计划", 12, Theme.MUTED));
        listHeading.add(listActions, BorderLayout.EAST); Theme.bounded(listHeading, 30); content.add(listHeading); Theme.gap(content, 10);
        JComponent progress = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                g.setColor(Theme.LINE); g.fillRect(0, 0, getWidth(), 2);
                g.setColor(Theme.ACCENT); g.fillRect(0, 0, (int)(getWidth() * completed / Math.max(1, dayTasks.size())), 2);
            }
        };
        progress.setPreferredSize(new Dimension(500, 2)); progress.setMinimumSize(new Dimension(0, 2)); Theme.bounded(progress, 2); content.add(progress); Theme.gap(content, 12);
        JPanel filter = Theme.panel(new BorderLayout(14, 0)); JTextField search = Theme.field(); search.setText(query);
        search.setToolTipText("搜索标题、备注或分类"); search.putClientProperty("JTextField.placeholderText", "搜索任务");
        JPanel searchWrap = Theme.panel(new BorderLayout(10, 0)); searchWrap.add(Theme.label("搜索", 12, Theme.MUTED), BorderLayout.WEST); searchWrap.add(search); filter.add(searchWrap);
        JComboBox<String> categories = new JComboBox<>(); categories.addItem("全部分类");
        data.tasks.stream().map(t -> t.category).distinct().sorted().forEach(categories::addItem); categories.setSelectedItem(categoryFilter);
        JComboBox<String> states = new JComboBox<>(new String[]{"全部状态", "未完成", "已完成", "已顺延"}); states.setSelectedItem(statusFilter); states.setEnabled(dayView());
        JPanel options = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        options.add(categories); options.add(states); filter.add(options, BorderLayout.EAST);
        if (filtersVisible) { Theme.bounded(filter, 38); content.add(filter); Theme.gap(content, 12); }
        JPanel list = Theme.column(); list.setBorder(new EmptyBorder(0, 0, 4, 4));
        Runnable updateList = () -> populate(list);
        search.getDocument().addDocumentListener(change(() -> { query = search.getText(); updateList.run(); }));
        categories.addActionListener(e -> { categoryFilter = Objects.toString(categories.getSelectedItem(), "全部分类"); updateList.run(); });
        states.addActionListener(e -> { statusFilter = Objects.toString(states.getSelectedItem(), "全部状态"); updateList.run(); });
        populate(list); JScrollPane scroll = Theme.scroll(list); scroll.setAlignmentX(LEFT_ALIGNMENT); content.add(scroll);
        if (dayView()) { Theme.gap(content, 12); reviewPanel(content); }
    }
    private void quickAdd(JPanel content) {
        JPanel composer = new Theme.Card(new BorderLayout(0, 14)); composer.setBorder(new EmptyBorder(16, 20, 14, 18));
        JTextField text = new Theme.PlaceholderField("添加一个待办…"); text.setFont(Theme.font(14)); text.setBorder(new EmptyBorder(3, 0, 7, 0)); text.setName("quickAdd");
        text.setToolTipText("输入任务后按 Enter；更多设置请点侧栏“新建待办”"); composer.add(text, BorderLayout.NORTH);
        JCheckBox remind = Theme.check("提醒", false); remind.setFont(Theme.font(12));
        JTextField time = Theme.field(); time.setText("09:00"); time.setColumns(4); time.setFont(Theme.font(12)); time.setBorder(new CompoundBorder(new LineBorder(Theme.LINE), new EmptyBorder(5, 7, 5, 7))); time.setEnabled(false);
        remind.addActionListener(e -> time.setEnabled(remind.isSelected()));
        JComboBox<String> repeats = new JComboBox<>(new String[]{"单次", "每天", "工作日", "每周", "每月"});
        JComboBox<String> priorities = new JComboBox<>(new String[]{"无", "低", "中", "高"});
        JPanel options = Theme.panel(new FlowLayout(FlowLayout.LEFT, 10, 0)); options.add(remind); options.add(time); options.add(Theme.label("重复", 11, Theme.MUTED)); options.add(repeats);
        options.add(Box.createHorizontalStrut(8)); options.add(Theme.label("优先级", 11, Theme.MUTED)); options.add(priorities); composer.add(options);
        java.util.List<String> subtaskTitles = new ArrayList<>();
        JPanel actions = Theme.panel(new BorderLayout()); JButton subtask = Theme.small("子任务 · 0", () -> {});
        subtask.addActionListener(e -> {
            JTextArea input = Theme.area(6); input.setText(String.join("\n", subtaskTitles));
            JScrollPane pane = Theme.scroll(input); pane.setPreferredSize(new Dimension(350, 170));
            if (JOptionPane.showConfirmDialog(this, pane, "子任务 · 每行一项", JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;
            List<String> lines = input.getText().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (lines.size() > 50 || lines.stream().anyMatch(s -> s.length() > 200)) { message("最多 50 个子任务，每项最多 200 字。"); return; }
            subtaskTitles.clear(); subtaskTitles.addAll(lines); subtask.setText("子任务 · " + lines.size());
        });
        JPanel left = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0)); left.add(subtask); actions.add(left);
        Runnable add = () -> {
            String title = text.getText().strip();
            if (title.isEmpty() || title.length() > 160) { message("请输入 1～160 字的任务名称。"); return; }
            Model.Task task = new Model.Task(); task.title = title; task.start = dayView() ? selected : LocalDate.now();
            task.repeat = Model.REPEATS[repeats.getSelectedIndex()]; task.priority = priorities.getSelectedIndex();
            if (remind.isSelected()) {
                try { if (!time.getText().strip().matches("[0-2][0-9]:[0-5][0-9]")) throw new DateTimeException("time"); task.time = LocalTime.parse(time.getText().strip()); }
                catch (DateTimeException e) { message("请输入有效提醒时间，例如 09:30（00:00～23:59）。"); return; }
            }
            for (String item : subtaskTitles) { Model.Step step = new Model.Step(); step.title = item; task.steps.add(step); }
            if (mutate(d -> d.tasks.add(task), true)) { JTextField next = findQuickAdd(); if (next != null) next.requestFocusInWindow(); }
        };
        text.addActionListener(e -> add.run());
        JPanel right = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); if (dayView()) right.add(Theme.link("套用模板", this::applyTemplates));
        right.add(Theme.button("添加", true, add)); actions.add(right, BorderLayout.EAST); composer.add(actions, BorderLayout.SOUTH);
        composer.setPreferredSize(new Dimension(600, 150)); composer.setMinimumSize(new Dimension(300, 150)); Theme.bounded(composer, 150); content.add(composer); Theme.gap(content, 26);
    }
    private JTextField findQuickAdd() { return findQuickAdd(body); }
    private JTextField findQuickAdd(Container parent) {
        for (Component c : parent.getComponents()) { if (c instanceof JTextField f && "quickAdd".equals(f.getName())) return f;
            if (c instanceof Container child) { JTextField f = findQuickAdd(child); if (f != null) return f; } }
        return null;
    }
    private void populate(JPanel list) {
        list.removeAll(); List<Model.Task> tasks = dayView() ? Model.forDay(data, selected) : data.tasks.stream().sorted(Comparator.comparing(t -> t.start)).toList();
        String needle = query.strip().toLowerCase(Locale.ROOT); int count = 0;
        for (Model.Task t : tasks) {
            if (!needle.isEmpty() && !(t.title + " " + t.notes + " " + t.category).toLowerCase(Locale.ROOT).contains(needle)) continue;
            if (!categoryFilter.equals("全部分类") && !categoryFilter.equals(t.category)) continue;
            if (dayView() && ((!statusFilter.equals("全部状态")) && !(statusFilter.equals("未完成") && Model.open(t, selected))
                && !(statusFilter.equals("已完成") && t.done.contains(selected)) && !(statusFilter.equals("已顺延") && t.deferred.contains(selected)))) continue;
            JPanel card = taskCard(t, false); Theme.bounded(card, Math.max(91, card.getPreferredSize().height)); list.add(card); count++;
        }
        if (count == 0) {
            JPanel empty = Theme.panel(new BorderLayout()); empty.setBorder(new EmptyBorder(30, 12, 30, 12));
            JPanel words = Theme.column(); words.add(Theme.label(query.isEmpty() ? "暂无待办" : "没有找到匹配的任务", 15, Theme.MUTED)); Theme.gap(words, 10);
            words.add(Theme.label(query.isEmpty() ? "在上方添加，或套用你的每日模板。" : "试试其他关键词或筛选条件。", 12, Theme.MUTED)); empty.add(words);
            Theme.bounded(empty, 140); list.add(empty);
        }
        list.add(Box.createVerticalGlue()); list.revalidate(); list.repaint();
    }
    private JPanel taskCard(Model.Task t, boolean template) {
        boolean dated = dayView() && !template, done = dated && t.done.contains(selected), deferred = dated && t.deferred.contains(selected);
        JPanel card = Theme.panel(new BorderLayout(15, 7));
        card.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.LINE), new EmptyBorder(19, 12, 19, 8)));
        JPanel text = Theme.column();
        JLabel title = Theme.label(done ? "<html><strike>" + Theme.html(t.title) + "</strike></html>" : "<html>" + Theme.html(t.title) + "</html>", 14, done || deferred ? Theme.MUTED : Theme.INK);
        title.setToolTipText(t.title); text.add(title); Theme.gap(text, 8);
        String meta = (t.time == null ? "" : t.time + "  ·  ") + (template ? "每日模板" : Model.repeatName(t.repeat)) + "  ·  " + Model.PRIORITIES[t.priority];
        if (!t.category.isBlank()) meta += "  ·  " + t.category;
        if (!dated && !template) meta += "   ·   " + t.start;
        if (!t.steps.isEmpty()) meta += "  ·  子任务 " + (dated ? t.steps.stream().filter(s -> s.done.contains(selected)).count() + "/" : "") + t.steps.size();
        if (done) meta += "   ·   已完成"; if (deferred) meta += "   ·   已顺延";
        text.add(Theme.label(meta, 11, Theme.MUTED));
        if (!t.notes.isBlank() && expandedTasks.contains(t.id)) { Theme.gap(text, 6); JLabel note = Theme.label(clip(t.notes.replace('\n', ' '), 65), 12, Theme.MUTED); note.setToolTipText(t.notes); text.add(note); }
        if (!t.steps.isEmpty() && expandedTasks.contains(t.id)) {
            Theme.gap(text, 8);
            if (dated) {
                for (int index = 0; index < t.steps.size(); index++) {
                    final int i = index; Model.Step step = t.steps.get(i); JCheckBox check = Theme.check(step.title, step.done.contains(selected)); check.setEnabled(!deferred);
                    check.addActionListener(e -> mutate(d -> {
                        Model.Task task = Model.find(d, t.id); Model.mark(task.steps.get(i).done, selected, check.isSelected());
                        if (!check.isSelected()) task.done.remove(selected);
                    }, true)); text.add(check);
                }
            } else text.add(Theme.label(t.steps.size() + " 个子任务", 11, Theme.MUTED));
        }
        card.add(text);
        if (dated) {
            JCheckBox check = Theme.check("", done); check.setToolTipText("标记当天完成"); check.setEnabled(!deferred);
            check.addActionListener(e -> mutate(d -> {
                Model.Task task = Model.find(d, t.id); Model.mark(task.done, selected, check.isSelected());
                if (check.isSelected()) task.steps.forEach(s -> s.done.add(selected));
            }, true));
            JPanel left = Theme.panel(new BorderLayout()); left.add(check, BorderLayout.NORTH); card.add(left, BorderLayout.WEST);
        }
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 5, 0));
        if (!t.steps.isEmpty() || !t.notes.isBlank()) actions.add(Theme.link(expandedTasks.contains(t.id) ? "收起" : t.steps.isEmpty() ? "详情" : "子任务", () -> {
            if (!flushReview()) return; if (!expandedTasks.add(t.id)) expandedTasks.remove(t.id); refresh();
        }));
        JButton edit = Theme.link("编辑", () -> edit(t, template)); edit.setForeground(Theme.INK); edit.setName("edit-" + t.id); actions.add(edit);
        JButton more = Theme.link("更多", () -> taskMenu(t, template)); actions.add(more);
        JPanel right = Theme.panel(new BorderLayout()); right.add(actions, BorderLayout.NORTH); card.add(right, BorderLayout.EAST);
        Color marker = t.priority == 3 ? new Color(0x47484C) : t.priority == 2 ? new Color(0x999A9E) : t.priority == 1 ? new Color(0xCDCED1) : Theme.WHITE;
        card.setBorder(new CompoundBorder(new MatteBorder(0, 0, 1, 0, Theme.LINE), new CompoundBorder(new EmptyBorder(18, 0, 18, 0),
            new CompoundBorder(new MatteBorder(0, 3, 0, 0, marker), new EmptyBorder(0, 10, 0, 0)))));
        return card;
    }
    private void taskMenu(Model.Task task, boolean template) {
        boolean canDefer = !template && dayView() && Model.open(task, selected);
        Object[] choices = template ? new Object[]{"删除模板", "取消"} : canDefer ? new Object[]{"存为模板", "删除任务", "顺延到下一天", "取消"} : new Object[]{"存为模板", "删除任务", "取消"};
        int answer = JOptionPane.showOptionDialog(this, task.title, "更多操作", JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, choices, choices[choices.length - 1]);
        if (!template && answer == 0) {
            Model.Task copy = Model.copyPlan(task); copy.repeat = "once"; copy.start = LocalDate.now(); copy.end = null;
            if (mutate(d -> d.templates.add(copy), true)) message("已存为每日模板，可在“每日模板”页面管理。");
        } else if (!template && canDefer && answer == 2) { defer(task);
        } else if ((template && answer == 0) || (!template && answer == 1)) {
            if (!confirm("删除“" + task.title + "”？\n" + (template ? "已套用的任务会保留。" : "这个任务在所有日期的记录都会删除。删除前会自动备份。"))) return;
            try { store.snapshot(data, "before-delete"); }
            catch (IOException e) { error(e); return; }
            mutate(d -> (template ? d.templates : d.tasks).removeIf(t -> t.id.equals(task.id)), true);
        }
    }
    private void newTask(boolean template) {
        if (!flushReview()) return;
        Model.Task result = new TaskDialog(this, null, dayView() ? selected : LocalDate.now(), template).showDialog();
        if (result != null) mutate(d -> (template ? d.templates : d.tasks).add(result), true);
    }
    private void edit(Model.Task task, boolean template) {
        if (!flushReview()) return;
        Model.Task result = new TaskDialog(this, task, selected, template).showDialog();
        if (result == null) return;
        if (!template && (!result.start.equals(task.start) || !result.repeat.equals(task.repeat)) &&
            !confirm("此修改会改变这个计划在所有日期的显示范围。\n已有完成记录会保留，修改前也会自动备份。是否继续？")) return;
        try { store.snapshot(data, "before-edit"); } catch (IOException e) { error(e); return; }
        mutate(d -> {
            List<Model.Task> tasks = template ? d.templates : d.tasks;
            for (int i = 0; i < tasks.size(); i++) if (tasks.get(i).id.equals(task.id)) { tasks.set(i, result); break; }
        }, true);
    }
    private void defer(Model.Task task) {
        String tip = Model.occurs(task, selected.plusDays(1)) ? "明天已经有这项重复任务，会使用明天原有的安排。" : "会在 " + selected.plusDays(1) + " 创建一项单次待办。";
        if (!confirm("把“" + task.title + "”顺延到下一天？\n" + tip + "\n当天会保留“已顺延”的记录。")) return;
        mutate(d -> Model.defer(d, task.id, selected), true);
    }
    private void shiftDay(int amount) {
        if (!flushReview()) return; selected = selected.plusDays(amount); view = selected.equals(LocalDate.now()) ? "today" : selected.equals(LocalDate.now().plusDays(1)) ? "tomorrow" : "history"; refresh();
    }
    private void chooseDate() {
        String text = JOptionPane.showInputDialog(this, "输入日期（YYYY-MM-DD）", selected.toString()); if (text == null) return;
        try { LocalDate date = LocalDate.parse(text.strip()); if (!flushReview()) return; selected = date;
            view = date.equals(LocalDate.now()) ? "today" : date.equals(LocalDate.now().plusDays(1)) ? "tomorrow" : "history"; refresh();
        } catch (DateTimeException e) { message("请输入有效日期，例如 2026-10-09。"); }
    }
    private void templates(JPanel content) {
        header(content, "每日模板", "常用安排，按需套用。", Theme.button("+  新建模板", true, () -> newTask(true)));
        JPanel list = Theme.column();
        if (data.templates.isEmpty()) {
            JPanel empty = new Theme.Card(new BorderLayout()); empty.add(Theme.label("例如：晨间阅读、工作日报、晚间运动。也可以把已有任务存为模板。", 13, Theme.MUTED));
            Theme.bounded(empty, 100); list.add(empty);
        }
        for (Model.Task t : data.templates) { JPanel card = taskCard(t, true); Theme.bounded(card, card.getPreferredSize().height); list.add(card); Theme.gap(list, 12); }
        list.add(Box.createVerticalGlue()); content.add(Theme.scroll(list));
        Theme.gap(content, 16); JPanel actions = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0)); actions.add(Theme.button("套用到今天", true, () -> { selected = LocalDate.now(); applyTemplates(); })); Theme.bounded(actions, 44); content.add(actions);
    }
    private void applyTemplates() {
        if (!flushReview()) return;
        if (data.templates.isEmpty()) { message("还没有模板，请先在“每日模板”中新建，或把已有任务存为模板。"); return; }
        JPanel list = Theme.column(); List<JCheckBox> boxes = new ArrayList<>();
        for (Model.Task t : data.templates) { JCheckBox b = Theme.check(t.title, true); boxes.add(b); list.add(b); }
        JScrollPane pane = Theme.scroll(list); pane.setPreferredSize(new Dimension(400, Math.min(350, data.templates.size() * 32 + 20)));
        int answer = JOptionPane.showConfirmDialog(this, pane, "套用模板到 " + selected, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) return;
        List<Model.Task> chosen = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) if (boxes.get(i).isSelected()) chosen.add(data.templates.get(i));
        if (chosen.isEmpty()) return;
        final int[] skipped = {0};
        if (mutate(d -> {
            for (Model.Task template : chosen) {
                if (d.tasks.stream().anyMatch(t -> Model.occurs(t, selected) && t.title.equals(template.title) && t.category.equals(template.category))) { skipped[0]++; continue; }
                Model.Task copy = Model.copyPlan(template); copy.start = selected; copy.end = null; copy.repeat = "once"; d.tasks.add(copy);
            }
        }, true)) message("已添加 " + (chosen.size() - skipped[0]) + " 项到 " + selected + (skipped[0] == 0 ? "。" : "；跳过 " + skipped[0] + " 项当天已有的同名同分类任务。"));
    }
    private void reviewPanel(JPanel content) {
        JPanel panel = Theme.panel(new BorderLayout(0, 7)); panel.setBorder(new CompoundBorder(new MatteBorder(1, 0, 0, 0, Theme.LINE), new EmptyBorder(12, 0, 0, 0)));
        JPanel title = Theme.panel(new BorderLayout()); title.add(Theme.label("一句话复盘", 12, Theme.MUTED));
        JButton toggle = Theme.link(reviewExpanded ? "收起" : "展开复盘", () -> { if (flushReview()) { reviewExpanded = !reviewExpanded; refresh(); } }); toggle.setName("reviewToggle");
        title.add(toggle, BorderLayout.EAST); panel.add(title, BorderLayout.NORTH);
        review = Theme.area(2); review.setBorder(new EmptyBorder(5, 0, 0, 0)); review.setText(data.notes.getOrDefault(selected, "")); reviewDate = selected;
        review.setToolTipText("今天有什么进展？明天想调整什么？输入后自动保存，最多 10000 字。"); if (reviewExpanded) panel.add(Theme.scroll(review));
        reviewTimer = new javax.swing.Timer(700, e -> flushReview()); reviewTimer.setRepeats(false);
        review.getDocument().addDocumentListener(change(() -> { if (!rebuilding) reviewTimer.restart(); }));
        int height = reviewExpanded ? 88 : 36;
        panel.setPreferredSize(new Dimension(600, height)); panel.setMinimumSize(new Dimension(200, height)); Theme.bounded(panel, height); content.add(panel);
    }
    private boolean flushReview() {
        if (review == null || reviewDate == null) return true;
        if (reviewTimer != null) reviewTimer.stop();
        String text = review.getText(); LocalDate date = reviewDate;
        if (text.equals(data.notes.getOrDefault(date, ""))) return true;
        if (text.length() > 10000) { message("每日复盘最多 10000 字，请精简后保存。"); return false; }
        return mutate(d -> { if (text.isBlank()) d.notes.remove(date); else d.notes.put(date, text); }, false, false);
    }
    private void settings(JPanel content) {
        header(content, "设置与备份", "本地保存，离线可用。", null);
        JPanel list = Theme.column();
        JPanel behavior = new Theme.Card(new BorderLayout()); JPanel options = Theme.column(); options.add(Theme.heading("运行与提醒", 16)); Theme.gap(options, 16);
        JCheckBox close = Theme.check("关闭窗口时缩到托盘，继续接收提醒", data.closeToTray); close.setEnabled(tray != null || preview);
        close.addActionListener(e -> mutate(d -> d.closeToTray = close.isSelected(), true)); options.add(close); Theme.gap(options, 8);
        JCheckBox sound = Theme.check("提醒时播放系统提示音", data.sound); sound.addActionListener(e -> mutate(d -> d.sound = sound.isSelected(), true)); options.add(sound); Theme.gap(options, 8);
        JCheckBox startup = Theme.check("开机启动（在托盘运行）", !preview && Startup.enabled()); startup.setEnabled(!preview && Startup.available());
        startup.addActionListener(e -> { try { Startup.set(startup.isSelected()); footer.setText("已更新开机启动设置"); }
            catch (IOException ex) { startup.setSelected(!startup.isSelected()); error(ex); } }); options.add(startup); Theme.gap(options, 14);
        options.add(Theme.label("每 20 秒检查一次提醒；休眠后仅补发最近 30 分钟内的提醒。", 12, Theme.MUTED)); Theme.gap(options, 5);
        options.add(Theme.label("工作日按周一至周五计算，暂不包含节假日调休。", 12, Theme.MUTED)); Theme.gap(options, 12);
        JPanel tests = Theme.panel(new FlowLayout(FlowLayout.LEFT, 0, 0)); tests.add(Theme.button("测试提醒", false, () -> showReminder(List.of("这是一条测试提醒。可以关闭窗口后再次测试托盘提醒。")))); options.add(tests);
        behavior.add(options); Theme.bounded(behavior, 300); list.add(behavior); Theme.gap(list, 16);
        JPanel storage = new Theme.Card(new BorderLayout()); JPanel inside = Theme.column(); inside.add(Theme.heading("数据与备份", 16)); Theme.gap(inside, 12);
        inside.add(Theme.label("自动保存；保留上一次保存以及最近 30 天的每日备份。", 12, Theme.MUTED)); Theme.gap(inside, 8);
        JLabel path = Theme.label(store.directory.toString(), 11, Theme.MUTED); path.setToolTipText(store.directory.toString()); inside.add(path); Theme.gap(inside, 16);
        JPanel buttons = Theme.panel(new FlowLayout(FlowLayout.LEFT, 7, 0)); buttons.add(Theme.button("导出备份", false, this::exportData));
        buttons.add(Theme.button("导入 / 恢复", false, this::importData)); buttons.add(Theme.button("打开数据目录", false, () -> {
            try { Files.createDirectories(store.directory); Desktop.getDesktop().open(store.directory.toFile()); } catch (IOException e) { error(e); }
        })); inside.add(buttons); Theme.gap(inside, 12);
        inside.add(Theme.label("导入会替换当前数据，替换前会另存一份完整备份。", 12, Theme.MUTED)); storage.add(inside);
        Theme.bounded(storage, 220); list.add(storage); Theme.gap(list, 16);
        JPanel about = new Theme.Card(new BorderLayout()); about.add(Theme.label("个人每日计划 1.0  ·  Java 21 / Swing  ·  灵感来自 DailyTodo-Windows", 12, Theme.MUTED)); Theme.bounded(about, 65); list.add(about);
        list.add(Box.createVerticalGlue()); content.add(Theme.scroll(list));
    }
    private JFileChooser chooser() {
        JFileChooser chooser = new JFileChooser(store.directory.toFile()); chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("每日计划备份 (*.json)", "json")); return chooser;
    }
    private void exportData() {
        if (!flushReview()) return; JFileChooser chooser = chooser(); chooser.setSelectedFile(store.directory.resolve("每日计划-" + LocalDate.now() + ".json").toFile());
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath(); if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json")) path = Path.of(path + ".json");
        if (Files.exists(path) && !confirm("文件已存在，是否覆盖？\n" + path)) return;
        try { store.export(data, path); message("备份已导出到：\n" + path); } catch (IOException e) { error(e); }
    }
    private void importData() {
        if (!flushReview()) return; JFileChooser chooser = chooser(); if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        try {
            Model.Data imported = store.read(chooser.getSelectedFile().toPath());
            if (!confirm("将导入 " + imported.tasks.size() + " 个任务、" + imported.templates.size() + " 个模板，替换当前数据。\n当前数据会先备份。是否继续？")) return;
            Path backup = store.snapshot(data, "before-import"); store.save(imported); data = imported; refresh(); message("导入完成。原数据备份：\n" + backup);
        } catch (IOException e) { error(e); }
    }
    private boolean mutate(Consumer<Model.Data> operation, boolean refresh) { return mutate(operation, refresh, true); }
    private boolean mutate(Consumer<Model.Data> operation, boolean refresh, boolean flush) {
        if (flush && !flushReview()) return false;
        try {
            Model.Data candidate = Store.copy(data); operation.accept(candidate); store.save(candidate); data = candidate;
            footer.setText("已保存到本机 · " + LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")));
            if (refresh) refresh(); return true;
        } catch (Exception e) { error(e); if (refresh) refresh(); return false; }
    }
    private void installTray() {
        if (!SystemTray.isSupported()) { data.closeToTray = false; return; }
        PopupMenu menu = new PopupMenu(); MenuItem open = new MenuItem("打开每日计划"), today = new MenuItem("查看今天"), exit = new MenuItem("完全退出");
        menu.add(open); menu.add(today); menu.addSeparator(); menu.add(exit);
        open.addActionListener(e -> SwingUtilities.invokeLater(this::showWindow)); today.addActionListener(e -> SwingUtilities.invokeLater(() -> { navigate("today"); showWindow(); }));
        exit.addActionListener(e -> SwingUtilities.invokeLater(this::exit));
        TrayIcon icon = new TrayIcon(Theme.icon(32), "个人每日计划", menu); icon.setImageAutoSize(true); icon.addActionListener(e -> SwingUtilities.invokeLater(this::showWindow));
        try { SystemTray.getSystemTray().add(icon); tray = icon; } catch (AWTException e) { data.closeToTray = false; }
    }
    void showWindow() { setVisible(true); setExtendedState(JFrame.NORMAL); toFront(); requestFocus(); }
    void startMinimized() { if (tray != null) setVisible(false); else showWindow(); }
    private void closeWindow() {
        if (!flushReview()) return;
        if (tray != null && data.closeToTray) {
            setVisible(false);
            if (!trayHint) { tray.displayMessage("每日计划仍在运行", "双击托盘图标打开；右键选择“完全退出”可停止程序。", TrayIcon.MessageType.INFO); trayHint = true; }
        } else exit();
    }
    void tick() {
        LocalDateTime now = LocalDateTime.now();
        if (!lastToday.equals(now.toLocalDate())) {
            if (!flushReview()) return; lastToday = now.toLocalDate();
            if (view.equals("today")) selected = lastToday; if (view.equals("tomorrow")) selected = lastToday.plusDays(1); refresh();
        }
        List<Model.Task> due = Model.due(data, now); if (due.isEmpty()) return;
        List<String> ids = due.stream().map(t -> t.id).toList();
        if (mutate(d -> ids.forEach(id -> Model.find(d, id).notified.add(now.toLocalDate())), false)) showReminder(due.stream().map(t -> t.title).toList());
    }
    void showReminder(List<String> titles) {
        if (data.sound && !preview) Toolkit.getDefaultToolkit().beep();
        if (tray != null) tray.displayMessage("该做这件事了", String.join("\n", titles), TrayIcon.MessageType.INFO);
        if (notification != null) notification.dispose();
        notification = new JDialog((Frame)null, "每日计划 · 提醒", false); notification.setIconImage(Theme.icon(32)); notification.setAlwaysOnTop(true);
        notification.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        JPanel panel = new JPanel(new BorderLayout(0, 14)); panel.setBorder(new EmptyBorder(22, 24, 20, 24)); panel.add(Theme.heading("该做这件事了", 21), BorderLayout.NORTH);
        JTextArea text = Theme.area(4); text.setText(String.join("\n\n", titles)); text.setEditable(false); text.setOpaque(false); panel.add(Theme.scroll(text));
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.add(Theme.button("稍后处理", false, () -> notification.dispose()));
        actions.add(Theme.button("查看今天", true, () -> { notification.dispose(); navigate("today"); showWindow(); })); panel.add(actions, BorderLayout.SOUTH);
        notification.setContentPane(panel); notification.setSize(420, 270);
        Rectangle screen = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds(); notification.setLocation(screen.x + screen.width - 440, screen.y + screen.height - 290); notification.setVisible(true);
    }
    void exit() {
        if (!flushReview()) return;
        reminderTimer.stop(); if (reviewTimer != null) reviewTimer.stop(); if (notification != null) notification.dispose();
        if (tray != null) SystemTray.getSystemTray().remove(tray); store.release(); dispose();
    }
    private boolean confirm(String text) { return JOptionPane.showConfirmDialog(this, text, "个人每日计划", JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.YES_OPTION; }
    private void message(String text) { JOptionPane.showMessageDialog(this, text, "个人每日计划", JOptionPane.INFORMATION_MESSAGE); }
    private void error(Exception e) { JOptionPane.showMessageDialog(this, "操作未完成，请检查数据目录权限和磁盘空间。\n" + e.getMessage(), "个人每日计划", JOptionPane.ERROR_MESSAGE); }
    private static String clip(String text, int max) { return text.length() > max ? text.substring(0, max) + "…" : text; }
    static DocumentListener change(Runnable action) { return new DocumentListener() {
        public void insertUpdate(DocumentEvent e) { action.run(); } public void removeUpdate(DocumentEvent e) { action.run(); } public void changedUpdate(DocumentEvent e) { action.run(); }
    }; }
}
