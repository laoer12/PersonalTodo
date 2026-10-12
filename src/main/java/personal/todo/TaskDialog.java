package personal.todo;

import javax.swing.*;
import javax.swing.border.*;
import java.awt.*;
import java.time.*;
import java.time.format.*;
import java.util.*;
import java.util.List;

final class TaskDialog extends JDialog {
    private final JTextField title = Theme.field(), date = Theme.field(), time = Theme.field();
    private final JComboBox<String> category = new JComboBox<>(new String[]{"工作", "学习", "生活", "健康"});
    private final JComboBox<String> repeat = new JComboBox<>(Model.REPEAT_NAMES), priority = new JComboBox<>(Model.PRIORITIES);
    private final JTextArea notes = Theme.area(3), steps = Theme.area(4);
    private final JCheckBox reminder = Theme.check("到点提醒", false);
    private final Model.Task original;
    private final boolean template;
    private Model.Task result;

    TaskDialog(Window owner, Model.Task task, LocalDate selected, boolean template) {
        super(owner, task == null ? (template ? "新建每日模板" : "新建待办") : "编辑" + (template ? "模板" : "待办"), ModalityType.APPLICATION_MODAL);
        this.original = task; this.template = template;
        setDefaultCloseOperation(DISPOSE_ON_CLOSE); setSize(570, 750); setMinimumSize(new Dimension(540, 640)); setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout(0, 16)); root.setBorder(new EmptyBorder(24, 28, 22, 28)); setContentPane(root);
        JPanel heading = Theme.column(); heading.add(Theme.heading(getTitle(), 23)); Theme.gap(heading, 6);
        heading.add(Theme.label(template ? "保存常用安排，需要时一键加入当天。" : "给一天留出清晰的安排，也给自己留一点余地。", 12, Theme.MUTED)); root.add(heading, BorderLayout.NORTH);
        JPanel form = Theme.column();
        addField(form, "任务名称 *", title); title.setName("taskTitle");
        category.setEditable(true); category.setName("taskCategory"); repeat.setName("taskRepeat");
        priority.setName("taskPriority"); date.setName("taskDate"); time.setName("taskTime");
        steps.setName("taskSteps"); notes.setName("taskNotes"); reminder.setName("taskReminder");
        JPanel first = Theme.panel(new GridLayout(1, 2, 16, 0)); first.add(wrap("分类", category)); first.add(wrap("优先级", priority));
        Theme.bounded(first, 67); form.add(first); Theme.gap(form, 14);
        JPanel second = Theme.panel(new GridLayout(1, 2, 16, 0)); second.add(wrap("开始日期 · YYYY-MM-DD", date)); second.add(wrap("重复规则", repeat));
        Theme.bounded(second, 67); form.add(second); Theme.gap(form, 14);
        JPanel alarm = Theme.panel(new BorderLayout(16, 0)); alarm.add(reminder, BorderLayout.WEST); alarm.add(time, BorderLayout.CENTER);
        time.setToolTipText("24 小时制，例如 09:30"); time.setEnabled(false); reminder.addActionListener(e -> time.setEnabled(reminder.isSelected()));
        addField(form, "提醒时间 · HH:mm（24 小时制）", alarm);
        JScrollPane stepsPane = Theme.scroll(steps); stepsPane.setBorder(new LineBorder(Theme.LINE)); addField(form, "子任务 · 每行一项，最多 50 项", stepsPane);
        JScrollPane notesPane = Theme.scroll(notes); notesPane.setBorder(new LineBorder(Theme.LINE)); addField(form, "备注", notesPane);
        if (template) { repeat.setSelectedIndex(0); repeat.setEnabled(false); date.setEnabled(false); }
        else if (task != null && !task.repeat.equals("once")) {
            JLabel tip = Theme.label("修改日期或重复规则会影响整个计划，完成记录会保留。", 11, Theme.ORANGE);
            Theme.bounded(tip, 24); form.add(tip);
        }
        root.add(Theme.scroll(form), BorderLayout.CENTER);
        JPanel actions = Theme.panel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.add(Theme.button("取消", false, this::dispose));
        JButton save = Theme.button("保存" + (template ? "模板" : "待办"), true, this::save); save.setName("saveTask"); actions.add(save); root.add(actions, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(save);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        date.setText((task == null ? selected : task.start).toString()); time.setText("09:00");
        if (task != null) {
            title.setText(task.title); notes.setText(task.notes); category.setSelectedItem(task.category); priority.setSelectedIndex(task.priority);
            repeat.setSelectedIndex(Arrays.asList(Model.REPEATS).indexOf(task.repeat));
            steps.setText(String.join("\n", task.steps.stream().map(s -> s.title).toList()));
            if (task.time != null) { reminder.setSelected(true); time.setEnabled(true); time.setText(task.time.toString()); }
        }
        addWindowListener(new java.awt.event.WindowAdapter() { @Override public void windowOpened(java.awt.event.WindowEvent e) { title.requestFocusInWindow(); } });
    }
    private JPanel wrap(String text, JComponent control) {
        JPanel p = Theme.panel(new BorderLayout(0, 7)); p.add(Theme.label(text, 12, Theme.MUTED), BorderLayout.NORTH); p.add(control); return p;
    }
    private void addField(JPanel form, String text, JComponent control) {
        JPanel p = wrap(text, control); int height = control instanceof JScrollPane ? (control == null ? 100 : control.getPreferredSize().height + 28) : 70;
        Theme.bounded(p, height); form.add(p); Theme.gap(form, 14);
    }
    private void save() {
        try {
            String text = title.getText().strip(); if (text.isEmpty() || text.length() > 160) throw new IllegalArgumentException("请输入 1～160 字的任务名称。");
            LocalDate start;
            try { start = LocalDate.parse(date.getText().strip(), DateTimeFormatter.ISO_LOCAL_DATE); }
            catch (DateTimeException e) { throw new IllegalArgumentException("日期格式应为 YYYY-MM-DD，例如 2026-10-09。"); }
            LocalTime alarm = null;
            if (reminder.isSelected()) {
                try {
                    String value = time.getText().strip();
                    if (!value.matches("[0-2][0-9]:[0-5][0-9]")) throw new DateTimeException("format");
                    alarm = LocalTime.parse(value, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT));
                } catch (DateTimeException e) { throw new IllegalArgumentException("提醒时间应为 00:00～23:59，例如 09:30。"); }
            }
            String group = Objects.toString(category.getEditor().getItem(), "生活").strip();
            if (group.isEmpty()) group = "生活"; if (group.length() > 40) throw new IllegalArgumentException("分类最多 40 字。");
            if (notes.getText().length() > 10000) throw new IllegalArgumentException("备注最多 10000 字。");
            List<String> lines = steps.getText().lines().map(String::strip).filter(s -> !s.isEmpty()).toList();
            if (lines.size() > 50 || lines.stream().anyMatch(s -> s.length() > 200)) throw new IllegalArgumentException("最多 50 个子任务，每项最多 200 字。");
            Model.Task t = new Model.Task(); t.title = text; t.start = start; t.category = group;
            t.repeat = template ? "once" : Model.REPEATS[repeat.getSelectedIndex()]; t.time = alarm; t.priority = priority.getSelectedIndex(); t.notes = notes.getText();
            if (original != null) {
                t.id = original.id; t.end = original.end;
                if (t.repeat.equals("weekly") && original.repeat.equals("weekly")) t.weekDays.addAll(Model.effectiveWeekDays(original));
                t.done.addAll(original.done); t.deferred.addAll(original.deferred); t.notified.addAll(original.notified);
                if (!Objects.equals(t.time, original.time)) t.notified.remove(LocalDate.now());
                if (t.end != null && t.end.isBefore(t.start)) throw new IllegalArgumentException("开始日期不能晚于计划的结束日期。");
            }
            // Match each unchanged subtask once, so editing text never transfers another item's history.
            List<Model.Step> remaining = original == null ? new ArrayList<>() : new ArrayList<>(original.steps);
            for (String line : lines) {
                Model.Step s = new Model.Step(); s.title = line;
                for (var it = remaining.iterator(); it.hasNext();) {
                    Model.Step old = it.next(); if (old.title.equals(line)) { s.done.addAll(old.done); it.remove(); break; }
                }
                t.steps.add(s);
            }
            result = t; dispose();
        } catch (IllegalArgumentException e) { JOptionPane.showMessageDialog(this, e.getMessage(), "请检查输入", JOptionPane.WARNING_MESSAGE); }
    }
    Model.Task showDialog() { setVisible(true); return result; }
}
