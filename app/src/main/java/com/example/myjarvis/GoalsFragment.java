package com.example.myjarvis;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

public class GoalsFragment extends Fragment {

    private static final int BACKGROUND = 0xFF0E1621;
    private static final int CARD = 0xFF17212B;
    private static final int TILE = 0xFF1E1E1E;
    private static final int STROKE = 0xFF2E7D32;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF8A9BA8;
    private static final int ACCENT = 0xFF00E676;
    private static final int GREEN = 0xFF69C987;
    private static final int YELLOW = 0xFFF0C84A;
    private static final int FIRE = 0xFFFF875F;

    private static final BlockGroup[] BLOCK_GROUPS = {
            new BlockGroup("🌅  Утро", "Запустить день без перегруза", new Block[]{
                    new Block("water", "Вода", "Старт дня"),
                    new Block("breathing", "Дыхание", "Box breathing 4–4–4–4"),
                    new Block("activation", "Физическая активация", "10–20 отжиманий + движение"),
                    new Block("breakfast", "Завтрак + музыка", "Без спешки"),
                    new Block("walk", "Прогулка", "10–20 минут")
            }),
            new BlockGroup("🧠  Развитие", "Выбери собственное направление", new Block[]{
                    new Block("development", "Развитие", "AI / видео / озвучка / другое")
            }),
            new BlockGroup("🎯  Фокус", "Работа над главным", new Block[]{
                    new Block("focus_one", "Главная задача", "Один конкретный результат"),
                    new Block("focus_two", "Рабочая сессия", "Ещё один завершённый блок")
            }),
            new BlockGroup("💪  Тело", "Движение по силам", new Block[]{
                    new Block("pushups", "Отжимания", "10–20"),
                    new Block("squats", "Приседания", "10–20"),
                    new Block("crunches", "Скручивания", "10–20")
            }),
            new BlockGroup("🌙  Завершение дня", "Спокойный финиш", new Block[]{
                    new Block("diary", "Дневник", "Что сделал / не сделал / понял / завтра"),
                    new Block("book", "Книга", "10 минут")
            })
    };

    private static final String[] REST_OPTIONS = {
            "Xbox", "Стрим", "Клипы / Shorts", "Бар / встреча"
    };

    private OfflineDataStore dataStore;
    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private Calendar selectedDay;
    private Calendar visibleMonth;
    private ScrollView scrollView;
    private LinearLayout content;
    private LinearLayout effortChoices;
    private LinearLayout energyChoices;
    private LinearLayout blockContent;
    private LinearLayout restChoices;
    private LinearLayout calendarGrid;
    private TextView selectedDayTitle;
    private TextView daySubtitle;
    private TextView dayModeBadge;
    private TextView dayPercent;
    private TextView dayCount;
    private TextView workCycleStatus;
    private TextView progressedDays;
    private TextView averageCompletion;
    private TextView monthTitle;
    private TextView diaryStatus;
    private TextView diaryAnalysis;
    private EditText developmentInput;
    private EditText diaryInput;
    private OfflineDataStore.DayRecord dayRecord;
    private OfflineVoiceRecognizer diaryRecognizer;
    private OfflineVoiceRecognizer notesRecognizer;
    private ActivityResultLauncher<String> notesPermissionLauncher;
    private LinearLayout longTermGoalList;
    private EditText noteTitleInput;
    private EditText noteContentInput;
    private LinearLayout savedNotes;
    private int voiceRequestId;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        dataStore = new OfflineDataStore(requireContext());
        dataStore.ensureDailyBlocks(defaultDailyBlocks());
        selectedDay = Calendar.getInstance();
        if (savedInstanceState != null) {
            selectedDay.setTimeInMillis(savedInstanceState.getLong("selected_day",
                    System.currentTimeMillis()));
        }
        visibleMonth = (Calendar) selectedDay.clone();
        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startDiaryRecording();
                    } else {
                        Toast.makeText(requireContext(),
                                "Для голосового дневника нужен доступ к микрофону.",
                                Toast.LENGTH_LONG).show();
                    }
                });
        notesPermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startNotesRecording();
                    } else {
                        Toast.makeText(requireContext(),
                                "Для голосовой заметки нужен доступ к микрофону.",
                                Toast.LENGTH_LONG).show();
                    }
                });

        scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);
        content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(12), dp(16), dp(22));
        scrollView.addView(content);

        buildHeader();
        buildDailySummary();
        buildLongTermGoalsCard();
        buildFeelingCard();
        buildEffortCard();
        buildHabitCard();
        buildRestCard();
        buildDiaryCard();
        buildStatisticsCard();
        buildWorkCycleCard();
        buildVoiceNotesCard();
        refreshDay();
        return scrollView;
    }

    private void buildHeader() {
        LinearLayout header = new LinearLayout(requireContext());
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(2), dp(2), dp(2), dp(8));

        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text("VIBE//ВАЙБ", 27, TEXT, true));
        daySubtitle = text("", 14, MUTED, false);
        labels.addView(daySubtitle, params(0, 2, 0, 0));
        header.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView today = chip("Сегодня", false, this::selectToday);
        header.addView(today);
        content.addView(header);
    }

    private void buildDailySummary() {
        LinearLayout card = card();
        selectedDayTitle = text("", 28, TEXT, true);
        card.addView(selectedDayTitle);
        dayModeBadge = text("", 14, MUTED, false);
        card.addView(dayModeBadge, params(0, 4, 0, 12));
        dayPercent = text("", 46, TEXT, true);
        card.addView(dayPercent);
        TextView caption = text("выполнение базовых блоков сегодня", 13, MUTED, false);
        card.addView(caption);

        android.widget.ProgressBar progress = new android.widget.ProgressBar(
                requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(STROKE));
        progress.setTag("daily_progress");
        card.addView(progress, params(0, 12, 0, 0));
        dayCount = text("", 13, MUTED, false);
        dayCount.setGravity(Gravity.END);
        card.addView(dayCount, params(0, 4, 0, 0));
        content.addView(card);
    }

    private void buildLongTermGoalsCard() {
        LinearLayout card = card();
        card.addView(text("Мои цели", 20, TEXT, true));
        card.addView(text("Дедлайн, оставшиеся дни и отметки прогресса — всё офлайн.",
                13, MUTED, false), params(0, 3, 0, 9));
        Button addGoal = new Button(requireContext());
        addGoal.setText("＋  Новая цель");
        addGoal.setTextColor(TEXT);
        addGoal.setBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF315D82));
        addGoal.setOnClickListener(view -> showGoalEditor(null));
        card.addView(addGoal, params(0, 0, 0, 8));
        longTermGoalList = verticalChoices();
        card.addView(longTermGoalList);
        content.addView(card);
        refreshLongTermGoals();
    }

    private void refreshLongTermGoals() {
        if (longTermGoalList == null || !isAdded()) {
            return;
        }
        longTermGoalList.removeAllViews();
        List<OfflineDataStore.Goal> goals = dataStore.getGoals();
        if (goals.isEmpty()) {
            longTermGoalList.addView(text("Пока нет целей на долгий срок.",
                    13, MUTED, false), params(0, 5, 0, 0));
            return;
        }
        for (OfflineDataStore.Goal goal : goals) {
            longTermGoalList.addView(goalCard(goal), params(0, 5, 0, 0));
        }
    }

    private View goalCard(OfflineDataStore.Goal goal) {
        LinearLayout box = new LinearLayout(requireContext());
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(12), dp(10), dp(12), dp(10));
        box.setBackground(background(TILE, STROKE, 14));

        LinearLayout heading = new LinearLayout(requireContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text(goal.title, 16, TEXT, true), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView edit = chip("Изменить", false, () -> showGoalEditor(goal));
        heading.addView(edit);
        box.addView(heading);

        String remaining = goalDaysRemaining(goal.targetDate);
        String term = goalTermLabel(goal.term);
        box.addView(text(term + " · до " + displayGoalDate(goal.targetDate)
                        + " · " + remaining,
                12, MUTED, false), params(0, 4, 0, 5));

        long elapsed = Math.max(1, daysBetween(parseDate(goal.startDate),
                parseDate(goal.targetDate)) + 1L);
        int percent = (int) Math.min(100, goal.progressDays * 100L / elapsed);
        android.widget.ProgressBar progress = new android.widget.ProgressBar(
                requireContext(), null, android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100);
        progress.setProgress(percent);
        progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        progress.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(STROKE));
        box.addView(progress);
        box.addView(text(goal.progressDays + " дней с прогрессом · " + percent
                        + "% дней до срока отмечено", 11, MUTED, false),
                params(0, 3, 0, 5));
        List<OfflineDataStore.GoalProgress> recentProgress =
                dataStore.getGoalProgressHistory(goal.id, 3);
        if (!recentProgress.isEmpty()) {
            StringBuilder progressSummary = new StringBuilder("Последние шаги");
            for (OfflineDataStore.GoalProgress entry : recentProgress) {
                progressSummary.append("\n• ").append(displayGoalDate(entry.date));
                if (!entry.note.trim().isEmpty()) {
                    progressSummary.append(" — ").append(entry.note.trim());
                } else {
                    progressSummary.append(" — шаг отмечен");
                }
            }
            box.addView(text(progressSummary.toString(), 12, MUTED, false),
                    params(0, 2, 0, 5));
        }

        LinearLayout actions = new LinearLayout(requireContext());
        Button mark = new Button(requireContext());
        mark.setText(goal.progressedToday ? "✓  Изменить шаг за сегодня"
                : "＋  Записать шаг за сегодня");
        mark.setAllCaps(false);
        mark.setTextColor(goal.progressedToday ? ACCENT : TEXT);
        mark.setBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD));
        mark.setOnClickListener(view -> showGoalProgressEditor(goal));
        actions.addView(mark, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button delete = new Button(requireContext());
        delete.setText("Удалить");
        delete.setAllCaps(false);
        delete.setTextColor(MUTED);
        delete.setOnClickListener(view -> new AlertDialog.Builder(requireContext())
                .setMessage("Удалить цель «" + goal.title + "» и её отметки прогресса?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    dataStore.deleteGoal(goal.id);
                    refreshLongTermGoals();
                }).show());
        actions.addView(delete, wrapParams(5, 0, 0, 0));
        box.addView(actions);
        return box;
    }

    private void showGoalProgressEditor(OfflineDataStore.Goal goal) {
        String date = dateString(today());
        EditText stepInput = input("Например: занимался 30 минут", false);
        stepInput.setTextColor(TEXT);
        stepInput.setGravity(Gravity.TOP | Gravity.START);
        stepInput.setMinLines(2);
        stepInput.setBackground(background(TILE, STROKE, 12));
        stepInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        stepInput.setText(dataStore.getGoalProgressNote(goal.id, date));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(goal.progressedToday ? "Шаг по цели за сегодня"
                        : "Как ты продвинулся?")
                .setMessage(goal.title)
                .setView(stepInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить шаг", null)
                .create();
        if (goal.progressedToday) {
            dialog.setButton(AlertDialog.BUTTON_NEUTRAL, "Снять отметку сегодня",
                    (ignored, which) -> {
                        dataStore.removeGoalProgress(goal.id, date);
                        refreshLongTermGoals();
                    });
        }
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String note = stepInput.getText().toString().trim();
                    if (note.isEmpty()) {
                        stepInput.setError("Опиши хотя бы один небольшой шаг.");
                        return;
                    }
                    dataStore.markGoalProgress(goal.id, date, note);
                    dialog.dismiss();
                    refreshLongTermGoals();
                }));
        dialog.show();
    }

    private void showGoalEditor(@Nullable OfflineDataStore.Goal existing) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);
        EditText title = input("Например: выучить английский", true);
        title.setTextColor(TEXT);
        if (existing != null) {
            title.setText(existing.title);
        }
        form.addView(title);

        Spinner termPicker = new Spinner(requireContext());
        String[] terms = {"На день", "На неделю", "На месяц", "На год", "На 5 лет"};
        String[] termCodes = {"day", "week", "month", "year", "five_years"};
        termPicker.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, terms));
        if (existing != null) {
            termPicker.setSelection(termPosition(existing.term));
        }
        form.addView(termPicker, params(0, 8, 0, 0));

        Calendar start = existing == null ? today() : parseDate(existing.startDate);
        Calendar[] deadline = {existing == null ? targetForTerm(today(),
                termCodes[termPicker.getSelectedItemPosition()])
                : parseDate(existing.targetDate)};
        TextView dateButton = chip("Срок: " + displayGoalDate(dateString(deadline[0])),
                false, () -> {});
        dateButton.setOnClickListener(view -> new DatePickerDialog(requireContext(),
                        (picker, year, month, day) -> {
                            deadline[0].set(year, month, day);
                            dateButton.setText("Срок: " + displayGoalDate(dateString(deadline[0])));
                        }, deadline[0].get(Calendar.YEAR),
                        deadline[0].get(Calendar.MONTH),
                        deadline[0].get(Calendar.DAY_OF_MONTH)).show());
        form.addView(dateButton, params(0, 8, 0, 0));
        termPicker.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(android.widget.AdapterView<?> parent, View view,
                                       int position, long id) {
                deadline[0] = targetForTerm(start, termCodes[position]);
                dateButton.setText("Срок: " + displayGoalDate(dateString(deadline[0])));
            }

            @Override
            public void onNothingSelected(android.widget.AdapterView<?> parent) {
            }
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? "Новая цель" : "Изменить цель")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = title.getText().toString().trim();
                    if (name.isEmpty()) {
                        title.setError("Напиши название цели.");
                        return;
                    }
                    if (deadline[0].before(start)) {
                        Toast.makeText(requireContext(),
                                "Срок не может быть раньше даты начала.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String term = termCodes[termPicker.getSelectedItemPosition()];
                    if (existing == null) {
                        dataStore.addGoal(name, dateString(start), dateString(deadline[0]), term);
                    } else {
                        dataStore.updateGoal(existing.id, name, dateString(start),
                                dateString(deadline[0]), term);
                    }
                    dialog.dismiss();
                    refreshLongTermGoals();
                }));
        dialog.show();
    }

    private Calendar targetForTerm(Calendar start, String term) {
        Calendar target = (Calendar) start.clone();
        if ("week".equals(term)) {
            target.add(Calendar.DAY_OF_MONTH, 7);
        } else if ("month".equals(term)) {
            target.add(Calendar.MONTH, 1);
        } else if ("year".equals(term)) {
            target.add(Calendar.YEAR, 1);
        } else if ("five_years".equals(term)) {
            target.add(Calendar.YEAR, 5);
        }
        return target;
    }

    private int termPosition(String term) {
        if ("week".equals(term)) {
            return 1;
        }
        if ("month".equals(term)) {
            return 2;
        }
        if ("year".equals(term)) {
            return 3;
        }
        if ("five_years".equals(term)) {
            return 4;
        }
        return 0;
    }

    private String goalTermLabel(String term) {
        switch (term) {
            case "week":
                return "Неделя";
            case "month":
                return "Месяц";
            case "year":
                return "Год";
            case "five_years":
                return "5 лет";
            default:
                return "День";
        }
    }

    private String goalDaysRemaining(String targetDate) {
        int days = daysBetween(today(), parseDate(targetDate));
        if (days < 0) {
            return "просрочена на " + Math.abs(days) + " дн.";
        }
        if (days == 0) {
            return "дедлайн сегодня";
        }
        return "осталось " + days + " дн.";
    }

    private String displayGoalDate(String date) {
        return new SimpleDateFormat("d MMM yyyy", new Locale("ru"))
                .format(parseDate(date).getTime());
    }

    private void buildVoiceNotesCard() {
        LinearLayout card = card();
        card.addView(text("Заметки", 20, TEXT, true));
        card.addView(text("Сохраняются офлайн. Их можно попросить прочитать в чате.",
                13, MUTED, false), params(0, 3, 0, 7));
        noteTitleInput = input("Заголовок заметки", true);
        noteTitleInput.setTextColor(TEXT);
        noteTitleInput.setBackground(background(TILE, STROKE, 12));
        noteTitleInput.setPadding(dp(10), dp(7), dp(10), dp(7));
        card.addView(noteTitleInput);
        noteContentInput = input("Текст заметки", false);
        noteContentInput.setTextColor(TEXT);
        noteContentInput.setGravity(Gravity.TOP | Gravity.START);
        noteContentInput.setMinLines(3);
        noteContentInput.setBackground(background(TILE, STROKE, 12));
        noteContentInput.setPadding(dp(10), dp(8), dp(10), dp(8));
        card.addView(noteContentInput, params(0, 5, 0, 5));

        LinearLayout buttons = new LinearLayout(requireContext());
        Button record = new Button(requireContext());
        record.setText("🎙  Надиктовать");
        record.setAllCaps(false);
        record.setOnClickListener(view -> requestNotesRecording());
        buttons.addView(record, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Button save = new Button(requireContext());
        save.setText("Сохранить заметку");
        save.setAllCaps(false);
        save.setOnClickListener(view -> saveVoiceNote());
        buttons.addView(save, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(buttons);
        savedNotes = verticalChoices();
        card.addView(savedNotes, params(0, 8, 0, 0));
        content.addView(card);
        refreshSavedNotes();
    }

    private void requestNotesRecording() {
        if (notesRecognizer != null) {
            notesRecognizer.stop();
            notesRecognizer = null;
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notesPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        startNotesRecording();
    }

    private void startNotesRecording() {
        if (!isAdded() || notesRecognizer != null) {
            return;
        }
        notesRecognizer = new OfflineVoiceRecognizer(requireContext(),
                new OfflineVoiceRecognizer.Listener() {
                    @Override
                    public void onResult(String text) {
                        notesRecognizer = null;
                        if (isAdded()) {
                            noteContentInput.setText(text);
                            Toast.makeText(requireContext(),
                                    "Голосовая заметка распознана. Сохрани её.",
                                    Toast.LENGTH_SHORT).show();
                        }
                    }

                    @Override
                    public void onError(String message) {
                        notesRecognizer = null;
                        if (isAdded()) {
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                        }
                    }
                });
        notesRecognizer.start();
    }

    private void saveVoiceNote() {
        String note = noteContentInput.getText().toString().trim();
        if (note.isEmpty()) {
            noteContentInput.setError("Добавь текст или надиктуй заметку.");
            return;
        }
        String title = noteTitleInput.getText().toString().trim();
        if (title.isEmpty()) {
            title = "Заметка " + new SimpleDateFormat("d MMM, HH:mm",
                    new Locale("ru")).format(new Date());
        }
        dataStore.addVoiceNote(title, note, dateString(today()));
        noteTitleInput.setText("");
        noteContentInput.setText("");
        refreshSavedNotes();
        Toast.makeText(requireContext(), "Заметка сохранена на устройстве.",
                Toast.LENGTH_SHORT).show();
    }

    private void refreshSavedNotes() {
        if (savedNotes == null) {
            return;
        }
        savedNotes.removeAllViews();
        List<OfflineDataStore.VoiceNote> notes = dataStore.getVoiceNotes();
        if (notes.isEmpty()) {
            savedNotes.addView(text("Сохранённых заметок пока нет.",
                    12, MUTED, false), params(0, 5, 0, 0));
            return;
        }
        for (OfflineDataStore.VoiceNote note : notes) {
            TextView item = text(note.title + " · " + note.createdAt + "\n"
                    + note.content, 13, TEXT, false);
            item.setPadding(dp(10), dp(8), dp(10), dp(8));
            item.setBackground(background(TILE, STROKE, 12));
            item.setOnClickListener(view -> new AlertDialog.Builder(requireContext())
                    .setTitle(note.title)
                    .setMessage(note.content)
                    .setPositiveButton("Закрыть", null)
                    .setNegativeButton("Удалить", (dialog, which) -> {
                        dataStore.deleteVoiceNote(note.id);
                        refreshSavedNotes();
                    }).show());
            savedNotes.addView(item, params(0, 4, 0, 0));
        }
    }

    private void buildFeelingCard() {
        LinearLayout card = card();
        card.addView(text("Состояние / энергия", 15, MUTED, false));
        energyChoices = verticalChoices();
        addChoiceRows(energyChoices, new String[]{"Низкая", "Нормальная", "Высокая"},
                new String[]{"LOW", "NORMAL", "HIGH"}, "energy");
        card.addView(energyChoices, params(0, 8, 0, 0));
        content.addView(card);
    }

    private void buildEffortCard() {
        LinearLayout card = card();
        LinearLayout heading = new LinearLayout(requireContext());
        heading.setGravity(Gravity.CENTER_VERTICAL);
        heading.addView(text("Уровень дня", 19, TEXT, true),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView count = text("", 15, TEXT, true);
        count.setTag("effort_count");
        heading.addView(count);
        card.addView(heading);
        card.addView(text("Оценивай не идеальность, а реальную нагрузку.",
                13, MUTED, false), params(0, 2, 0, 8));
        effortChoices = verticalChoices();
        addChoiceRows(effortChoices,
                new String[]{"🟢  Минимум\nБаза, когда сил мало",
                        "🟡  Норма\nОбычный продуктивный день",
                        "🔥  Максимум\nЕсть энергия — двигаем всё"},
                new String[]{"MINIMUM", "NORMAL", "MAXIMUM"}, "effort");
        card.addView(effortChoices);
        content.addView(card);
    }

    private void buildHabitCard() {
        LinearLayout card = card();
        card.addView(text("Трекер дня", 20, TEXT, true));
        card.addView(text("Отмечай фактический прогресс, без идеальной жизни.",
                13, MUTED, false), params(0, 3, 0, 8));
        developmentInput = input("Что сегодня изучал / практиковал / создал", false);
        developmentInput.setTextColor(TEXT);
        developmentInput.setBackground(background(TILE, STROKE, 12));
        developmentInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        developmentInput.setOnFocusChangeListener((view, hasFocus) -> {
            if (!hasFocus && dayRecord != null) {
                persistDayRecord();
            }
        });
        blockContent = verticalChoices();
        renderDailyBlocks();
        card.addView(blockContent);
        content.addView(card);
    }

    private List<OfflineDataStore.DailyBlock> defaultDailyBlocks() {
        List<OfflineDataStore.DailyBlock> defaults = new java.util.ArrayList<>();
        for (int groupPosition = 0; groupPosition < BLOCK_GROUPS.length; groupPosition++) {
            BlockGroup group = BLOCK_GROUPS[groupPosition];
            String groupId = "group_" + groupPosition;
            for (int position = 0; position < group.blocks.length; position++) {
                Block block = group.blocks[position];
                defaults.add(new OfflineDataStore.DailyBlock(block.id, groupId, group.title,
                        group.caption, block.title, block.subtitle, groupPosition, position));
            }
        }
        return defaults;
    }

    private void renderDailyBlocks() {
        blockContent.removeAllViews();
        List<OfflineDataStore.DailyBlock> blocks = dataStore.getDailyBlocks();
        for (int groupPosition = 0; groupPosition < BLOCK_GROUPS.length; groupPosition++) {
            BlockGroup group = BLOCK_GROUPS[groupPosition];
            String groupId = "group_" + groupPosition;
            int selectedGroupPosition = groupPosition;
            LinearLayout heading = new LinearLayout(requireContext());
            heading.setGravity(Gravity.CENTER_VERTICAL);
            TextView title = text(group.title, 18, TEXT, true);
            heading.addView(title, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            heading.addView(chip("+ Добавить", false,
                    () -> showDailyBlockEditor(null, selectedGroupPosition)));
            blockContent.addView(heading, params(0, 16, 0, 1));
            blockContent.addView(text(group.caption, 13, MUTED, false),
                    params(0, 0, 0, 5));
            for (OfflineDataStore.DailyBlock block : blocks) {
                if (!block.groupId.equals(groupId)) {
                    continue;
                }
                if (block.id.equals("development")) {
                    blockContent.addView(developmentInput, params(0, 0, 0, 4));
                }
                addChecklistBlock(blockContent, block);
            }
        }
    }

    private void showDailyBlockEditor(@Nullable OfflineDataStore.DailyBlock existing,
                                     int initialGroupPosition) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(6), dp(20), 0);
        EditText title = input("Название пункта", true);
        EditText subtitle = input("Подсказка или описание", false);
        if (existing != null) {
            title.setText(existing.title);
            subtitle.setText(existing.subtitle);
        }
        form.addView(title);
        form.addView(subtitle, params(0, 7, 0, 0));
        Spinner groupPicker = new Spinner(requireContext());
        String[] groupTitles = new String[BLOCK_GROUPS.length];
        for (int index = 0; index < BLOCK_GROUPS.length; index++) {
            groupTitles[index] = BLOCK_GROUPS[index].title;
        }
        groupPicker.setAdapter(new android.widget.ArrayAdapter<>(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, groupTitles));
        groupPicker.setSelection(existing == null ? initialGroupPosition
                : existing.groupPosition);
        form.addView(groupPicker, params(0, 7, 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? "Новый пункт" : "Изменить пункт")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String value = title.getText().toString().trim();
                    if (value.isEmpty()) {
                        title.setError("Введи название пункта.");
                        return;
                    }
                    int selectedGroup = groupPicker.getSelectedItemPosition();
                    BlockGroup group = BLOCK_GROUPS[selectedGroup];
                    String id = existing == null ? "custom_" + UUID.randomUUID()
                            : existing.id;
                    OfflineDataStore.DailyBlock saved = new OfflineDataStore.DailyBlock(id,
                            "group_" + selectedGroup, group.title, group.caption, value,
                            subtitle.getText().toString().trim(), selectedGroup,
                            dataStore.getNextDailyBlockPosition("group_" + selectedGroup));
                    dataStore.saveDailyBlock(saved);
                    dialog.dismiss();
                    renderDailyBlocks();
                    refreshDay();
                }));
        dialog.show();
    }

    private void showDailyBlockActions(OfflineDataStore.DailyBlock block) {
        new AlertDialog.Builder(requireContext())
                .setTitle(block.title)
                .setItems(new String[]{"Изменить", "Удалить"}, (dialog, which) -> {
                    if (which == 0) {
                        showDailyBlockEditor(block, block.groupPosition);
                    } else {
                        new AlertDialog.Builder(requireContext())
                                .setMessage("Удалить пункт «" + block.title
                                        + "»? История отметок сохранится.")
                                .setNegativeButton("Отмена", null)
                                .setPositiveButton("Удалить", (confirm, action) -> {
                                    dataStore.deleteDailyBlock(block.id);
                                    renderDailyBlocks();
                                    refreshDay();
                                }).show();
                    }
                }).show();
    }

    private void buildRestCard() {
        LinearLayout card = card();
        card.addView(text("🎮  Отдых", 20, TEXT, true));
        card.addView(text("Игра может оставаться отдыхом.", 13, MUTED, false),
                params(0, 2, 0, 8));
        restChoices = new LinearLayout(requireContext());
        restChoices.setOrientation(LinearLayout.VERTICAL);
        card.addView(restChoices);
        content.addView(card);
    }

    private void buildDiaryCard() {
        LinearLayout card = card();
        card.addView(text("🎙  Дневник дня", 20, TEXT, true));
        card.addView(text("Расскажи голосом, как реально прошёл день.",
                13, MUTED, false), params(0, 3, 0, 6));
        card.addView(text("Просто говори как есть — можно сделать паузу и продолжить.",
                13, MUTED, false), params(0, 0, 0, 8));
        diaryStatus = text("Готов к записи", 13, MUTED, false);
        card.addView(diaryStatus);

        Button recordButton = new Button(requireContext());
        recordButton.setText("🎙  Записать / остановить");
        recordButton.setTextColor(TEXT);
        recordButton.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF344B25));
        recordButton.setOnClickListener(v -> toggleDiaryRecording());
        card.addView(recordButton, params(0, 7, 0, 8));

        diaryInput = input("Здесь появится текст твоего рассказа", false);
        diaryInput.setTextColor(TEXT);
        diaryInput.setGravity(Gravity.TOP | Gravity.START);
        diaryInput.setMinLines(4);
        diaryInput.setBackground(background(TILE, STROKE, 14));
        diaryInput.setPadding(dp(12), dp(12), dp(12), dp(12));
        card.addView(diaryInput, params(0, 0, 0, 8));

        Button saveDiary = new Button(requireContext());
        saveDiary.setText("Сохранить дневник");
        saveDiary.setTextColor(TEXT);
        saveDiary.setBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF315D82));
        saveDiary.setOnClickListener(v -> saveDiary());
        card.addView(saveDiary);

        LinearLayout analysis = newCard();
        analysis.addView(text("🧠  Черновой разбор", 14, TEXT, true));
        diaryAnalysis = text("", 13, MUTED, false);
        analysis.addView(diaryAnalysis, params(0, 5, 0, 0));
        card.addView(analysis, params(0, 10, 0, 0));
        content.addView(card);
    }

    private void buildStatisticsCard() {
        LinearLayout card = card();
        card.addView(text("📊  Статистика", 20, TEXT, true));
        card.addView(text("Считаем выполнение текущей системы, а не «идеальную жизнь».",
                13, MUTED, false), params(0, 3, 0, 8));

        LinearLayout metrics = new LinearLayout(requireContext());
        progressedDays = metric();
        averageCompletion = metric();
        metrics.addView(progressedDays, weightParams(1));
        metrics.addView(averageCompletion, weightParams(1, dp(8)));
        card.addView(metrics);

        LinearLayout monthHeader = new LinearLayout(requireContext());
        monthHeader.setGravity(Gravity.CENTER_VERTICAL);
        monthTitle = text("", 15, TEXT, true);
        monthTitle.setSingleLine(true);
        monthHeader.addView(monthTitle, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        monthHeader.addView(chip("‹", false, () -> changeMonth(-1)),
                wrapParams(4, 0, 0, 0));
        monthHeader.addView(chip("Сегодня", false, this::selectToday),
                wrapParams(5, 0, 0, 0));
        monthHeader.addView(chip("›", false, () -> changeMonth(1)),
                wrapParams(5, 0, 0, 0));
        card.addView(monthHeader, params(0, 14, 0, 7));
        calendarGrid = new LinearLayout(requireContext());
        calendarGrid.setOrientation(LinearLayout.VERTICAL);
        card.addView(calendarGrid);
        content.addView(card);
    }

    private void buildWorkCycleCard() {
        LinearLayout card = card();
        card.addView(text("⚙  Режимы", 19, TEXT, true));
        card.addView(text("Настраиваемый график работы и отдыха.", 13, MUTED, false),
                params(0, 3, 0, 7));
        workCycleStatus = text("", 15, TEXT, true);
        card.addView(workCycleStatus);

        card.addView(chip("Настроить длительность работы и отдыха", false,
                this::configureWorkCycle), params(0, 7, 0, 0));
        LinearLayout actions = new LinearLayout(requireContext());
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.addView(chip("Изменить дату начала цикла", false,
                this::chooseWorkCycleStart), new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView toggle = chip("", false, this::toggleWorkCycle);
        toggle.setTag("work_cycle_toggle");
        actions.addView(toggle, wrapParams(5, 0, 0, 0));
        card.addView(actions, params(0, 5, 0, 0));
        content.addView(card);
    }

    private void addChecklistBlock(LinearLayout parent, OfflineDataStore.DailyBlock block) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(2), dp(4), dp(2));
        row.setBackground(background(TILE, STROKE, 12));

        CheckBox checkBox = new CheckBox(requireContext());
        checkBox.setButtonTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        checkBox.setTag(block.id);
        row.addView(checkBox);

        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(block.title, 15, TEXT, false));
        labels.addView(text(block.subtitle, 12, MUTED, false), params(0, 1, 0, 0));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(chip("⋮", false, () -> showDailyBlockActions(block)),
                wrapParams(4, 0, 0, 0));
        parent.addView(row, params(0, 2, 0, 4));

        setChecklistListener(checkBox, block.id);
    }

    private void setChecklistListener(CheckBox checkBox, String blockId) {
        checkBox.setOnCheckedChangeListener((button, checked) -> {
            if (button.isPressed()) {
                String selectedDate = dateString(selectedDay);
                dataStore.setDailyEntry(selectedDate, blockId, checked);
                if (blockId.equals("diary") && checked
                        && diaryInput.getText().toString().trim().isEmpty()) {
                    diaryInput.setError("Добавь запись или сними отметку");
                    dataStore.setDailyEntry(selectedDate, blockId, false);
                    button.setChecked(false);
                    return;
                }
                refreshDayStats();
                refreshCalendar();
            }
        });
    }

    private void addChoiceRows(LinearLayout parent, String[] titles,
                               String[] values, String field) {
        for (int index = 0; index < titles.length; index++) {
            String value = values[index];
            TextView option = chip(titles[index], false, () -> {
                if (field.equals("effort")) {
                    dayRecord = new OfflineDataStore.DayRecord(value, dayRecord.energy,
                            dayRecord.developmentNote, dayRecord.diary, dayRecord.restActivity);
                } else {
                    dayRecord = new OfflineDataStore.DayRecord(dayRecord.effort, value,
                            dayRecord.developmentNote, dayRecord.diary, dayRecord.restActivity);
                }
                persistDayRecord();
                refreshChoices();
                refreshDayStats();
            });
            option.setTag(value);
            parent.addView(option, params(0, 2, 0, 4));
        }
    }

    private void addRestChoices() {
        restChoices.removeAllViews();
        for (int index = 0; index < REST_OPTIONS.length; index += 2) {
            LinearLayout row = new LinearLayout(requireContext());
            row.setGravity(Gravity.CENTER_VERTICAL);
            for (int item = index; item < Math.min(index + 2, REST_OPTIONS.length); item++) {
                String restOption = REST_OPTIONS[item];
                boolean selected = restOption.equals(dayRecord.restActivity);
                row.addView(chip(restOption, selected, () -> {
                    String activity = restOption.equals(dayRecord.restActivity) ? "" : restOption;
                    dayRecord = new OfflineDataStore.DayRecord(dayRecord.effort, dayRecord.energy,
                            dayRecord.developmentNote, dayRecord.diary, activity);
                    persistDayRecord();
                    addRestChoices();
                }), weightParams(1, item == index ? 0 : dp(6)));
            }
            restChoices.addView(row, params(0, 4, 0, 0));
        }
    }

    private void refreshDay() {
        if (content == null || !isAdded()) {
            return;
        }
        String date = dateString(selectedDay);
        dayRecord = dataStore.getDayRecord(date);
        selectedDayTitle.setText(isToday(selectedDay) ? "Сегодня"
                : new SimpleDateFormat("EEEE, d MMMM", new Locale("ru")).format(selectedDay.getTime()));
        daySubtitle.setText(new SimpleDateFormat("EEEE, d MMMM", new Locale("ru"))
                .format(selectedDay.getTime()));
        updateModeSummary();
        for (OfflineDataStore.DailyBlock block : dataStore.getDailyBlocks()) {
            CheckBox checkBox = blockContent.findViewWithTag(block.id);
            if (checkBox != null) {
                checkBox.setOnCheckedChangeListener(null);
                checkBox.setChecked(dataStore.isDailyEntryDone(date, block.id));
                setChecklistListener(checkBox, block.id);
            }
        }
        developmentInput.setText(dayRecord.developmentNote);
        diaryInput.setText(dayRecord.diary);
        diaryStatus.setText(dayRecord.diary.isEmpty() ? "Готов к записи" : "Дневник сохранён");
        addRestChoices();
        refreshChoices();
        refreshDayStats();
        refreshStatistics();
        refreshCalendar();
    }

    private void updateModeSummary() {
        String modeLabel;
        int color;
        if (dayRecord.effort.equals("MINIMUM")) {
            modeLabel = "🟢  Минимум";
            color = GREEN;
        } else if (dayRecord.effort.equals("MAXIMUM")) {
            modeLabel = "🔥  Максимум";
            color = FIRE;
        } else {
            modeLabel = "🟡  Норма";
            color = YELLOW;
        }
        dayModeBadge.setText(modeLabel + " · " + getWorkShift(selectedDay));
        dayModeBadge.setTextColor(color);
    }

    private void refreshChoices() {
        updateChoiceGroup(effortChoices, dayRecord.effort);
        updateChoiceGroup(energyChoices, dayRecord.energy);
        TextView count = content.findViewWithTag("effort_count");
        if (count != null) {
            count.setText(String.format(Locale.getDefault(), "%d%%",
                    completionPercent(selectedDay)));
        }
        View toggle = content.findViewWithTag("work_cycle_toggle");
        if (toggle instanceof TextView) {
            ((TextView) toggle).setText(isWorkCycleEnabled() ? "Вкл." : "Выкл.");
            toggle.setBackground(background(isWorkCycleEnabled() ? 0xFF29361D : TILE,
                    isWorkCycleEnabled() ? ACCENT : STROKE, 18));
        }
        Calendar anchor = parseDate(dataStore.getSetting("work_cycle_start", dateString(today())));
        if (isWorkCycleEnabled()) {
            int workDays = getWorkCycleWorkDays();
            int restDays = getWorkCycleRestDays();
            int offset = Math.floorMod(daysBetween(anchor, selectedDay), workDays + restDays);
            workCycleStatus.setText(offset < workDays
                    ? "🔴  Работа · день " + (offset + 1) + " из " + workDays
                    : "🟢  Отдых · день " + (offset - workDays + 1) + " из " + restDays);
        } else {
            workCycleStatus.setText("⚪  График выключен · день отмечается как свободный");
        }
    }

    private void updateChoiceGroup(LinearLayout group, String selectedValue) {
        for (int index = 0; index < group.getChildCount(); index++) {
            View child = group.getChildAt(index);
            Object tag = child.getTag();
            if (tag instanceof String) {
                boolean selected = tag.equals(selectedValue);
                child.setBackground(background(selected ? 0xFF20291A : TILE,
                        selected ? ACCENT : STROKE, 14));
            }
        }
    }

    private void refreshDayStats() {
        int total = 0;
        int done = 0;
        String date = dateString(selectedDay);
        for (OfflineDataStore.DailyBlock block : dataStore.getDailyBlocks()) {
            total++;
            if (dataStore.isDailyEntryDone(date, block.id)) {
                done++;
            }
        }
        int percent = total == 0 ? 0 : (done * 100) / total;
        dayPercent.setText(percent + "%");
        dayCount.setText(done + " из " + total);
        ViewGroup progressCard = (ViewGroup) dayPercent.getParent();
        android.widget.ProgressBar bar = progressCard.findViewWithTag("daily_progress");
        if (bar != null) {
            bar.setProgress(percent);
        }
        refreshDiaryAnalysis();
    }

    private void refreshDiaryAnalysis() {
        if (diaryAnalysis == null || diaryInput == null) {
            return;
        }
        String diary = diaryInput.getText().toString().trim().toLowerCase(new Locale("ru"));
        if (diary.isEmpty()) {
            diaryAnalysis.setText("Сохрани дневник, чтобы увидеть наблюдения по выполненным блокам.");
            return;
        }

        int completed = 0;
        List<String> mentionedBlocks = new java.util.ArrayList<>();
        String date = dateString(selectedDay);
        for (OfflineDataStore.DailyBlock block : dataStore.getDailyBlocks()) {
            if (dataStore.isDailyEntryDone(date, block.id)) {
                completed++;
                String title = block.title.trim().toLowerCase(new Locale("ru"));
                if (title.length() > 2 && diary.contains(title)) {
                    mentionedBlocks.add(block.title);
                }
            }
        }

        StringBuilder summary = new StringBuilder("Сегодня отмечено ")
                .append(completed).append(" из ").append(totalBlockCount()).append(" блоков. ");
        if (mentionedBlocks.isEmpty()) {
            summary.append("Запись пока не упоминает отмеченные блоки — добавь, что именно получилось.");
        } else {
            summary.append("В дневнике совпадают отметки: ")
                    .append(android.text.TextUtils.join(", ", mentionedBlocks)).append('.');
        }
        if (diary.length() < 40) {
            summary.append(" Можно дописать, что хочешь улучшить завтра.");
        }
        diaryAnalysis.setText(summary.toString());
    }

    private int completionPercent(Calendar day) {
        String date = dateString(day);
        int total = 0;
        int done = 0;
        for (OfflineDataStore.DailyBlock block : dataStore.getDailyBlocks()) {
            total++;
            if (dataStore.isDailyEntryDone(date, block.id)) {
                done++;
            }
        }
        return total == 0 ? 0 : done * 100 / total;
    }

    private int totalBlockCount() {
        return dataStore.getDailyBlocks().size();
    }

    private void refreshStatistics() {
        progressedDays.setText(dataStore.getTotalProgressDays() + "\nдней с прогрессом");
        averageCompletion.setText(String.format(Locale.getDefault(), "%.0f%%\nв среднем",
                dataStore.getAverageDailyCompletion(totalBlockCount())));
        monthTitle.setText(new SimpleDateFormat("LLLL yyyy", new Locale("ru"))
                .format(visibleMonth.getTime()));
    }

    private void refreshCalendar() {
        if (calendarGrid == null) {
            return;
        }
        calendarGrid.removeAllViews();
        String[] weekdays = {"ПН", "ВТ", "СР", "ЧТ", "ПТ", "СБ", "ВС"};
        LinearLayout labels = calendarRow();
        for (String weekday : weekdays) {
            TextView label = text(weekday, 10, MUTED, false);
            label.setGravity(Gravity.CENTER);
            labels.addView(label, cellParams());
        }
        calendarGrid.addView(labels);

        Map<Integer, OfflineDataStore.DayProgress> progressByDay = new HashMap<>();
        String month = new SimpleDateFormat("yyyy-MM", Locale.US).format(visibleMonth.getTime());
        for (OfflineDataStore.DayProgress progress
                : dataStore.getMonthProgress(month, totalBlockCount())) {
            Calendar date = parseDate(progress.date);
            progressByDay.put(date.get(Calendar.DAY_OF_MONTH), progress);
        }

        Calendar cursor = (Calendar) visibleMonth.clone();
        cursor.set(Calendar.DAY_OF_MONTH, 1);
        int firstCell = (cursor.get(Calendar.DAY_OF_WEEK) + 5) % 7;
        int daysInMonth = cursor.getActualMaximum(Calendar.DAY_OF_MONTH);
        int cells = ((firstCell + daysInMonth + 6) / 7) * 7;
        for (int cell = 0; cell < cells; cell++) {
            if (cell % 7 == 0) {
                calendarGrid.addView(calendarRow());
            }
            LinearLayout row = (LinearLayout) calendarGrid.getChildAt(calendarGrid.getChildCount() - 1);
            int dayNumber = cell - firstCell + 1;
            if (dayNumber < 1 || dayNumber > daysInMonth) {
                row.addView(new View(requireContext()), cellParams());
                continue;
            }
            Calendar date = (Calendar) visibleMonth.clone();
            date.set(Calendar.DAY_OF_MONTH, dayNumber);
            boolean isSelected = sameDay(date, selectedDay);
            OfflineDataStore.DayProgress progress = progressByDay.get(dayNumber);
            int percent = progress == null || progress.total == 0 ? 0
                    : progress.completed * 100 / progress.total;
            String marker = progress != null && progress.completed > 0 ? "\n" + percent + "%" : "";
            TextView day = text(dayNumber + marker, marker.isEmpty() ? 13 : 10,
                    isSelected ? BACKGROUND : percent > 0 ? ACCENT : MUTED, isSelected);
            day.setGravity(Gravity.CENTER);
            day.setMinHeight(dp(42));
            int fill = isSelected ? ACCENT : todayDay(date) ? 0xFF22291B : TILE;
            day.setBackground(background(fill, isSelected ? ACCENT : STROKE, 11));
            day.setOnClickListener(v -> {
                persistDayRecord();
                selectedDay.setTimeInMillis(date.getTimeInMillis());
                refreshDay();
            });
            row.addView(day, cellParams());
        }
    }

    private void saveDiary() {
        String diary = diaryInput.getText().toString().trim();
        dayRecord = new OfflineDataStore.DayRecord(dayRecord.effort, dayRecord.energy,
                developmentInput.getText().toString().trim(), diary, dayRecord.restActivity);
        persistDayRecord();
        boolean hasDiary = !diary.isEmpty();
        dataStore.setDailyEntry(dateString(selectedDay), "diary", hasDiary);
        diaryStatus.setText(hasDiary ? "Дневник сохранён офлайн" : "Запись очищена");
        refreshDay();
        Toast.makeText(requireContext(), hasDiary ? "Дневник сохранён" : "Текст дневника очищен",
                Toast.LENGTH_SHORT).show();
    }

    private void toggleDiaryRecording() {
        if (diaryRecognizer != null) {
            diaryStatus.setText("Сохраняю распознанную речь…");
            diaryRecognizer.stop();
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(), android.Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED) {
            startDiaryRecording();
        } else {
            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startDiaryRecording() {
        int request = ++voiceRequestId;
        diaryStatus.setText("Слушаю офлайн… нажми кнопку, когда закончишь");
        diaryRecognizer = new OfflineVoiceRecognizer(requireContext(),
                new OfflineVoiceRecognizer.Listener() {
                    @Override
                    public void onResult(String transcript) {
                        if (request != voiceRequestId || !isAdded()) {
                            return;
                        }
                        diaryRecognizer = null;
                        String existing = diaryInput.getText().toString().trim();
                        diaryInput.setText(existing.isEmpty() ? transcript : existing + "\n" + transcript);
                        diaryStatus.setText("Речь распознана. Сохрани запись, чтобы она осталась в дневнике.");
                    }

                    @Override
                    public void onError(String message) {
                        if (request != voiceRequestId || !isAdded()) {
                            return;
                        }
                        diaryRecognizer = null;
                        diaryStatus.setText("Голосовую запись не удалось распознать");
                        Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                    }
                });
        diaryRecognizer.start(true);
    }

    private void persistDayRecord() {
        dataStore.updateDayRecord(dateString(selectedDay), dayRecord.effort, dayRecord.energy,
                developmentInput.getText().toString().trim(),
                diaryInput.getText().toString().trim(), dayRecord.restActivity);
    }

    private void selectToday() {
        persistDayRecord();
        selectedDay.setTimeInMillis(System.currentTimeMillis());
        visibleMonth.setTimeInMillis(selectedDay.getTimeInMillis());
        refreshDay();
        scrollView.smoothScrollTo(0, 0);
    }

    private void changeMonth(int amount) {
        visibleMonth.add(Calendar.MONTH, amount);
        visibleMonth.set(Calendar.DAY_OF_MONTH, 1);
        refreshStatistics();
        refreshCalendar();
    }

    private void toggleWorkCycle() {
        boolean enabled = isWorkCycleEnabled();
        dataStore.saveSetting("work_cycle_enabled", enabled ? "false" : "true");
        if (!enabled) {
            dataStore.saveSetting("work_cycle_start", dateString(today()));
        }
        refreshChoices();
        updateModeSummary();
    }

    private void configureWorkCycle() {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.HORIZONTAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText workDays = input("Рабочих дней", true);
        EditText restDays = input("Дней отдыха", true);
        workDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        restDays.setInputType(InputType.TYPE_CLASS_NUMBER);
        workDays.setText(String.valueOf(getWorkCycleWorkDays()));
        restDays.setText(String.valueOf(getWorkCycleRestDays()));
        form.addView(workDays, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        form.addView(restDays, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Длительность цикла")
                .setMessage("Число дней до переключения графика. Можно задать от 1 до 31.")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    int work = parseCycleDays(workDays);
                    int rest = parseCycleDays(restDays);
                    if (work < 1 || work > 31) {
                        workDays.setError("Введите число от 1 до 31.");
                        return;
                    }
                    if (rest < 1 || rest > 31) {
                        restDays.setError("Введите число от 1 до 31.");
                        return;
                    }
                    dataStore.saveSetting("work_cycle_work_days", String.valueOf(work));
                    dataStore.saveSetting("work_cycle_rest_days", String.valueOf(rest));
                    if (isWorkCycleEnabled()) {
                        dataStore.saveSetting("work_cycle_start", dateString(today()));
                    }
                    dialog.dismiss();
                    refreshChoices();
                    updateModeSummary();
                }));
        dialog.show();
    }

    private int parseCycleDays(EditText input) {
        try {
            return Integer.parseInt(input.getText().toString().trim());
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private int getWorkCycleWorkDays() {
        return getCycleDays("work_cycle_work_days");
    }

    private int getWorkCycleRestDays() {
        return getCycleDays("work_cycle_rest_days");
    }

    private int getCycleDays(String key) {
        try {
            return Math.max(1, Math.min(31, Integer.parseInt(
                    dataStore.getSetting(key, "2"))));
        } catch (NumberFormatException exception) {
            return 2;
        }
    }

    private boolean isWorkCycleEnabled() {
        return Boolean.parseBoolean(dataStore.getSetting("work_cycle_enabled", "false"));
    }

    private void chooseWorkCycleStart() {
        Calendar anchor = parseDate(dataStore.getSetting("work_cycle_start", dateString(today())));
        new DatePickerDialog(requireContext(), (picker, year, month, day) -> {
            Calendar selected = Calendar.getInstance();
            selected.set(year, month, day);
            dataStore.saveSetting("work_cycle_start", dateString(selected));
            refreshChoices();
            updateModeSummary();
        }, anchor.get(Calendar.YEAR), anchor.get(Calendar.MONTH),
                anchor.get(Calendar.DAY_OF_MONTH)).show();
    }

    private String getWorkShift(Calendar date) {
        if (!isWorkCycleEnabled()) {
            return "свободный день";
        }
        Calendar anchor = parseDate(dataStore.getSetting("work_cycle_start", dateString(today())));
        int workDays = getWorkCycleWorkDays();
        int restDays = getWorkCycleRestDays();
        int offset = Math.floorMod(daysBetween(anchor, date), workDays + restDays);
        return offset < workDays ? "работа " + (offset + 1) + "/" + workDays
                : "отдых " + (offset - workDays + 1) + "/" + restDays;
    }

    private int daysBetween(Calendar start, Calendar end) {
        GregorianCalendar first = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        first.clear();
        first.set(start.get(Calendar.YEAR), start.get(Calendar.MONTH),
                start.get(Calendar.DAY_OF_MONTH));
        GregorianCalendar second = new GregorianCalendar(TimeZone.getTimeZone("UTC"));
        second.clear();
        second.set(end.get(Calendar.YEAR), end.get(Calendar.MONTH),
                end.get(Calendar.DAY_OF_MONTH));
        return (int) ((second.getTimeInMillis() - first.getTimeInMillis()) / 86400000L);
    }

    private boolean isToday(Calendar date) {
        return sameDay(date, today());
    }

    private boolean todayDay(Calendar date) {
        return isToday(date);
    }

    private boolean sameDay(Calendar first, Calendar second) {
        return first.get(Calendar.YEAR) == second.get(Calendar.YEAR)
                && first.get(Calendar.DAY_OF_YEAR) == second.get(Calendar.DAY_OF_YEAR);
    }

    private Calendar today() {
        return Calendar.getInstance();
    }

    private Calendar parseDate(String value) {
        Calendar date = Calendar.getInstance();
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(value);
            if (parsed != null) {
                date.setTime(parsed);
            }
        } catch (java.text.ParseException exception) {
            date.setTimeInMillis(System.currentTimeMillis());
        }
        return date;
    }

    private String dateString(Calendar date) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(date.getTime());
    }

    private LinearLayout card() {
        return newCard();
    }

    private LinearLayout newCard() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(15), dp(16), dp(15));
        card.setBackground(background(CARD, STROKE, 22));
        return card;
    }

    private LinearLayout verticalChoices() {
        LinearLayout choices = new LinearLayout(requireContext());
        choices.setOrientation(LinearLayout.VERTICAL);
        return choices;
    }

    private LinearLayout calendarRow() {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER);
        row.setOrientation(LinearLayout.HORIZONTAL);
        return row;
    }

    private LinearLayout.LayoutParams cellParams() {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        result.setMargins(dp(2), dp(2), dp(2), dp(2));
        return result;
    }

    private LinearLayout.LayoutParams weightParams(float weight) {
        return weightParams(weight, 0);
    }

    private LinearLayout.LayoutParams weightParams(float weight, int marginStart) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        result.setMargins(marginStart, 0, 0, 0);
        return result;
    }

    private TextView metric() {
        TextView metric = text("", 17, TEXT, true);
        metric.setGravity(Gravity.CENTER_VERTICAL);
        metric.setMinHeight(dp(68));
        metric.setPadding(dp(10), dp(8), dp(6), dp(8));
        metric.setBackground(background(TILE, STROKE, 15));
        return metric;
    }

    private TextView chip(String label, boolean selected, Runnable action) {
        TextView chip = text(label, 13, selected ? ACCENT : MUTED, selected);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(12), dp(9), dp(12), dp(9));
        chip.setBackground(background(selected ? 0xFF20291A : TILE,
                selected ? ACCENT : STROKE, 18));
        chip.setOnClickListener(view -> action.run());
        return chip;
    }

    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(requireContext());
        text.setText(value);
        text.setTextColor(color);
        text.setTextSize(size);
        if (bold) {
            text.setTypeface(null, Typeface.BOLD);
        }
        return text;
    }

    private EditText input(String hint, boolean singleLine) {
        EditText editText = new EditText(requireContext());
        editText.setHint(hint);
        editText.setSingleLine(singleLine);
        editText.setTextSize(14);
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | (singleLine ? InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                : InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        return editText;
    }

    private GradientDrawable background(int color, int strokeColor, int radius) {
        GradientDrawable background = new GradientDrawable();
        background.setColor(color);
        background.setCornerRadius(dp(radius));
        background.setStroke(dp(1), strokeColor);
        return background;
    }

    private LinearLayout.LayoutParams params(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return result;
    }

    private LinearLayout.LayoutParams wrapParams(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        result.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    public void onPause() {
        if (dayRecord != null && developmentInput != null && diaryInput != null
                && dataStore != null) {
            persistDayRecord();
        }
        super.onPause();
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        if (dayRecord != null && developmentInput != null && diaryInput != null) {
            persistDayRecord();
        }
        outState.putLong("selected_day", selectedDay == null
                ? System.currentTimeMillis() : selectedDay.getTimeInMillis());
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onDestroy() {
        voiceRequestId++;
        if (diaryRecognizer != null) {
            diaryRecognizer.stop();
            diaryRecognizer = null;
        }
        if (notesRecognizer != null) {
            notesRecognizer.stop();
            notesRecognizer = null;
        }
        if (dataStore != null) {
            dataStore.close();
        }
        super.onDestroy();
    }

    private static class Block {
        final String id;
        final String title;
        final String subtitle;

        Block(String id, String title, String subtitle) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    private static class BlockGroup {
        final String title;
        final String caption;
        final Block[] blocks;

        BlockGroup(String title, String caption, Block[] blocks) {
            this.title = title;
            this.caption = caption;
            this.blocks = blocks;
        }
    }
}
