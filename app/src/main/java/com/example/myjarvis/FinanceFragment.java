package com.example.myjarvis;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
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

import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class FinanceFragment extends Fragment {

    private static final int BACKGROUND = 0xFF0E1621;
    private static final int CARD = 0xFF17212B;
    private static final int TILE = 0xFF1E1E1E;
    private static final int STROKE = 0xFF2E7D32;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int MUTED = 0xFF8A9BA8;
    private static final int GREEN = 0xFF00E676;
    private static final int RED = 0xFFFF6B6B;
    private static final int BLUE = 0xFF5288C1;
    private static final String[] DEFAULT_CATEGORIES = {
            "Дом", "Продукты", "Кафе и еда", "Транспорт", "Покупки", "Здоровье"
    };

    private OfflineDataStore dataStore;
    private LinearLayout content;
    private OfflineVoiceRecognizer voiceRecognizer;
    private ActivityResultLauncher<String> microphonePermissionLauncher;
    private Button voiceButton;
    private int voiceRequestId;
    private String currentMonth;

    @Nullable
    @Override
    public View onCreateView(@NonNull android.view.LayoutInflater inflater,
                             @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        dataStore = new OfflineDataStore(requireContext());
        currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(new Date());
        microphonePermissionLauncher = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) {
                        startVoiceEntry();
                    } else {
                        Toast.makeText(requireContext(),
                                "Для голосового ввода нужен доступ к микрофону.",
                                Toast.LENGTH_LONG).show();
                    }
                });
        ScrollView scrollView = new ScrollView(requireContext());
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(BACKGROUND);
        content = new LinearLayout(requireContext());
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scrollView.addView(content);
        refresh();
        return scrollView;
    }

    private void refresh() {
        dataStore.ensureQuickIncomeCategories(java.util.Arrays.asList("Зарплата", "Инвестиции"));
        content.removeAllViews();
        content.addView(text("Мои деньги", 28, TEXT, true));
        TextView subtitle = text("Личный бюджет · хранится только на устройстве",
                13, MUTED, false);
        content.addView(subtitle, params(0, 3, 0, 14));

        long[] totals = dataStore.getFinanceTotals(currentMonth);
        LinearLayout summary = new LinearLayout(requireContext());
        summary.setOrientation(LinearLayout.HORIZONTAL);
        long balance = totals[0] - totals[1];
        summary.addView(metric("РАСХОДЫ", money(totals[1]), RED), weightParams(1));
        summary.addView(metric("БАЛАНС", money(balance), BLUE), weightParams(1, 5));
        summary.addView(metric("ДОХОДЫ", money(totals[0]), GREEN), weightParams(1, 5));
        content.addView(summary);

        LinearLayout monthBar = new LinearLayout(requireContext());
        monthBar.setGravity(Gravity.CENTER_VERTICAL);
        TextView month = text(displayMonth(currentMonth), 18, TEXT, true);
        monthBar.addView(month, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        monthBar.addView(compactButton("‹", () -> shiftMonth(-1)), wrapParams(4, 0, 0, 0));
        monthBar.addView(compactButton("›", () -> shiftMonth(1)), wrapParams(4, 0, 0, 0));
        content.addView(monthBar, params(0, 16, 0, 10));

        LinearLayout actions = new LinearLayout(requireContext());
        actions.addView(button("＋ Расход", RED, () -> showAddTransactionDialog(false)),
                weightParams(1));
        actions.addView(button("＋ Доход", GREEN, () -> showAddTransactionDialog(true)),
                weightParams(1, 7));
        content.addView(actions);
        voiceButton = button("🎙  Голосовая запись", BLUE, this::requestVoiceEntry);
        content.addView(voiceButton, params(0, 8, 0, 14));

        LinearLayout quickAdd = card();
        quickAdd.addView(text("Быстрые категории", 18, TEXT, true));
        quickAdd.addView(text("Нажми категорию, чтобы добавить доход. Удерживай или нажми ⋮, чтобы изменить список.",
                12, MUTED, false),
                params(0, 3, 0, 8));
        List<String> quickIncomeCategories = dataStore.getQuickIncomeCategories();
        if (quickIncomeCategories.isEmpty()) {
            quickAdd.addView(text("Быстрых доходов пока нет. Добавь категорию ниже.",
                    12, MUTED, false));
        }
        for (int index = 0; index < quickIncomeCategories.size(); index += 2) {
            LinearLayout categoryRow = new LinearLayout(requireContext());
            categoryRow.setGravity(Gravity.CENTER_VERTICAL);
            categoryRow.addView(incomeCategoryCard(quickIncomeCategories.get(index)),
                    weightParams(1));
            if (index + 1 < quickIncomeCategories.size()) {
                categoryRow.addView(incomeCategoryCard(quickIncomeCategories.get(index + 1)),
                        weightParams(1, 6));
            }
            quickAdd.addView(categoryRow, params(0, index == 0 ? 0 : 6, 0, 0));
        }
        quickAdd.addView(chip("＋ Добавить категорию дохода",
                () -> showQuickIncomeCategoryDialog(null)), params(0, 8, 0, 0));
        content.addView(quickAdd, params(0, 0, 0, 12));

        LinearLayout budgets = card();
        budgets.addView(text("Бюджеты по категориям", 18, TEXT, true));
        budgets.addView(text("Нажми на категорию, чтобы задать или изменить лимит.",
                12, MUTED, false), params(0, 3, 0, 6));
        List<OfflineDataStore.CategorySpending> categories =
                dataStore.getCategorySpending(currentMonth);
        if (categories.isEmpty()) {
            budgets.addView(text("Добавленные расходы появятся здесь.",
                    13, MUTED, false), params(0, 8, 0, 0));
        } else {
            for (OfflineDataStore.CategorySpending item : categories) {
                budgets.addView(categoryBudget(item), params(0, 4, 0, 4));
            }
        }
        LinearLayout addCategoryRow = new LinearLayout(requireContext());
        for (int index = 0; index < DEFAULT_CATEGORIES.length; index++) {
            String category = DEFAULT_CATEGORIES[index];
            if (dataStore.getMonthlyBudget(category) == 0 && !containsCategory(categories, category)) {
                TextView categoryChip = chip("+ " + category,
                        () -> showBudgetDialog(category));
                addCategoryRow.addView(categoryChip,
                        weightParams(1, index % 2 == 0 ? 0 : 5));
                if (index % 2 == 1) {
                    budgets.addView(addCategoryRow, params(0, 8, 0, 0));
                    addCategoryRow = new LinearLayout(requireContext());
                }
            }
        }
        if (addCategoryRow.getChildCount() > 0) {
            budgets.addView(addCategoryRow, params(0, 8, 0, 0));
        }
        content.addView(budgets, params(0, 0, 0, 12));

        LinearLayout history = card();
        history.addView(text("История операций", 18, TEXT, true));
        List<OfflineDataStore.Transaction> transactions = dataStore.getTransactions();
        boolean hasMonthTransactions = false;
        for (OfflineDataStore.Transaction transaction : transactions) {
            if (transaction.date.startsWith(currentMonth)) {
                history.addView(transactionCard(transaction), params(0, 7, 0, 0));
                hasMonthTransactions = true;
            }
        }
        if (!hasMonthTransactions) {
            history.addView(text("За этот месяц пока нет операций.",
                    13, MUTED, false), params(0, 8, 0, 0));
        }
        content.addView(history);
    }

    private void requestVoiceEntry() {
        if (voiceRecognizer != null) {
            stopVoiceEntry();
            return;
        }
        if (ContextCompat.checkSelfPermission(requireContext(),
                android.Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            microphonePermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO);
            return;
        }
        startVoiceEntry();
    }

    private void startVoiceEntry() {
        if (!isAdded() || voiceRecognizer != null) {
            return;
        }
        int requestId = ++voiceRequestId;
        voiceButton.setText("Слушаю… нажми, чтобы остановить");
        voiceRecognizer = new OfflineVoiceRecognizer(requireContext(),
                new OfflineVoiceRecognizer.Listener() {
                    @Override
                    public void onResult(String utterance) {
                        if (requestId != voiceRequestId || !isAdded()) {
                            return;
                        }
                        voiceRecognizer = null;
                        List<FinanceVoiceParser.Entry> entries = FinanceVoiceParser.parse(utterance);
                        if (entries.isEmpty()) {
                            Toast.makeText(requireContext(),
                                    "Не распознала сумму. Скажи, например: «потратил тысячу рублей на йогу».",
                                    Toast.LENGTH_LONG).show();
                            refresh();
                            return;
                        }
                        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                                .format(new Date());
                        for (FinanceVoiceParser.Entry entry : entries) {
                            dataStore.addTransaction(entry.title, entry.amountCents,
                                    entry.income, date, entry.category);
                        }
                        Toast.makeText(requireContext(),
                                "Голосом добавлено операций: " + entries.size(),
                                Toast.LENGTH_SHORT).show();
                        refresh();
                    }

                    @Override
                    public void onError(String message) {
                        if (requestId == voiceRequestId && isAdded()) {
                            voiceRecognizer = null;
                            Toast.makeText(requireContext(), message, Toast.LENGTH_LONG).show();
                            refresh();
                        }
                    }
                });
        voiceRecognizer.start();
    }

    private void stopVoiceEntry() {
        voiceRequestId++;
        if (voiceRecognizer != null) {
            voiceRecognizer.stop();
            voiceRecognizer = null;
        }
        if (isAdded()) {
            Toast.makeText(requireContext(), "Голосовой ввод остановлен.",
                    Toast.LENGTH_SHORT).show();
            refresh();
        }
    }

    private void shiftMonth(int amount) {
        try {
            java.util.Calendar calendar = java.util.Calendar.getInstance();
            calendar.setTime(new SimpleDateFormat("yyyy-MM", Locale.US).parse(currentMonth));
            calendar.add(java.util.Calendar.MONTH, amount);
            currentMonth = new SimpleDateFormat("yyyy-MM", Locale.US).format(calendar.getTime());
            refresh();
        } catch (java.text.ParseException exception) {
            Toast.makeText(requireContext(), "Не удалось изменить выбранный месяц.",
                    Toast.LENGTH_LONG).show();
        }
    }

    private View categoryBudget(OfflineDataStore.CategorySpending item) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(11), dp(8), dp(11), dp(8));
        row.setBackground(rounded(TILE, STROKE, 14));
        LinearLayout labels = new LinearLayout(requireContext());
        labels.setGravity(Gravity.CENTER_VERTICAL);
        TextView category = text(item.category, 14, TEXT, true);
        labels.addView(category, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        labels.addView(text(money(item.amountCents) + " ₽", 13, TEXT, true));
        row.addView(labels);
        if (item.budgetCents > 0) {
            ProgressBar progress = new ProgressBar(requireContext(), null,
                    android.R.attr.progressBarStyleHorizontal);
            progress.setMax(100);
            progress.setProgress((int) Math.min(100, item.amountCents * 100 / item.budgetCents));
            progress.setProgressTintList(android.content.res.ColorStateList.valueOf(
                    item.amountCents > item.budgetCents ? RED : GREEN));
            row.addView(progress, params(0, 4, 0, 0));
            row.addView(text("Лимит " + money(item.budgetCents) + " ₽"
                            + (item.amountCents > item.budgetCents ? " · превышен" : ""),
                    11, item.amountCents > item.budgetCents ? RED : MUTED, false),
                    params(0, 3, 0, 0));
        } else {
            TextView setLimit = text("Нажми, чтобы задать лимит бюджета", 11, BLUE, false);
            row.addView(setLimit, params(0, 3, 0, 0));
        }
        row.setOnClickListener(view -> showBudgetDialog(item.category));
        return row;
    }

    private boolean containsCategory(List<OfflineDataStore.CategorySpending> categories,
                                    String name) {
        for (OfflineDataStore.CategorySpending category : categories) {
            if (category.category.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private void showBudgetDialog(String category) {
        EditText amountInput = input("Месячный лимит, ₽", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        long existing = dataStore.getMonthlyBudget(category);
        if (existing > 0) {
            amountInput.setText(String.format(Locale.US, "%.2f", existing / 100.0));
        }
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle("Бюджет: " + category)
                .setView(amountInput)
                .setNegativeButton("Отмена", null)
                .setNeutralButton("Снять лимит", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
                dataStore.saveMonthlyBudget(category, 0);
                dialog.dismiss();
                refresh();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    long cents = parseAmount(amountInput.getText().toString());
                    if (cents <= 0) {
                        amountInput.setError("Укажите лимит больше нуля.");
                        return;
                    }
                    dataStore.saveMonthlyBudget(category, cents);
                    dialog.dismiss();
                    refresh();
                });
        });
        dialog.show();
    }

    private void showEditTransactionDialog(OfflineDataStore.Transaction transaction) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText titleInput = input("Описание операции", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        titleInput.setText(transaction.title);
        form.addView(titleInput);
        EditText amountInput = input("Сумма, ₽", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        amountInput.setText(String.format(Locale.US, "%.2f",
                transaction.amountCents / 100.0));
        form.addView(amountInput, params(0, 7, 0, 0));
        EditText categoryInput = input("Категория", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        categoryInput.setText(transaction.category);
        form.addView(categoryInput, params(0, 7, 0, 0));
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(transaction.isIncome ? "Изменить доход" : "Изменить расход")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = titleInput.getText().toString().trim();
                    String category = categoryInput.getText().toString().trim();
                    long cents = parseAmount(amountInput.getText().toString());
                    if (title.isEmpty() || category.isEmpty() || cents <= 0) {
                        Toast.makeText(requireContext(),
                                "Заполни описание, категорию и сумму больше нуля.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    dataStore.updateTransaction(transaction.id, title, cents,
                            transaction.isIncome, transaction.date, category);
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    private void showAddTransactionDialog(boolean income) {
        showCategorizedEntryDialog(income ? "Доход" : "Прочее", income);
    }

    private void showCategorizedEntryDialog(String initialCategory, boolean income) {
        LinearLayout form = new LinearLayout(requireContext());
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(8), dp(20), 0);
        EditText titleInput = input("Описание операции", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        form.addView(titleInput);
        EditText amountInput = input("Сумма, ₽", InputType.TYPE_CLASS_NUMBER
                | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        form.addView(amountInput, params(0, 7, 0, 0));
        EditText categoryInput = input("Категория", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        categoryInput.setText(initialCategory);
        form.addView(categoryInput, params(0, 7, 0, 0));

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(income ? "Новый доход" : "Новый расход")
                .setView(form)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String title = titleInput.getText().toString().trim();
                    String category = categoryInput.getText().toString().trim();
                    long cents = parseAmount(amountInput.getText().toString());
                    if (title.isEmpty() || cents <= 0 || category.isEmpty()) {
                        Toast.makeText(requireContext(),
                                "Заполни описание, категорию и сумму больше нуля.",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US)
                            .format(new Date());
                    dataStore.addTransaction(title, cents, income, date, category);
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    private long parseAmount(String value) {
        try {
            double amount = Double.parseDouble(value.trim().replace(" ", "").replace(',', '.'));
            if (!Double.isFinite(amount) || amount <= 0 || amount > Long.MAX_VALUE / 100.0) {
                return 0;
            }
            return Math.round(amount * 100);
        } catch (NumberFormatException exception) {
            return 0;
        }
    }

    private View transactionCard(OfflineDataStore.Transaction transaction) {
        LinearLayout row = new LinearLayout(requireContext());
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(9), dp(6), dp(9));
        row.setBackground(rounded(TILE, STROKE, 12));
        TextView icon = text(transaction.isIncome ? "↗" : "↙", 21,
                transaction.isIncome ? GREEN : RED, true);
        row.addView(icon, wrapParams(0, 0, 9, 0));
        LinearLayout labels = new LinearLayout(requireContext());
        labels.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(transaction.title, 14, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(title);
        TextView details = text(transaction.category + " · " + displayDate(transaction.date),
                11, MUTED, false);
        details.setSingleLine(true);
        details.setEllipsize(TextUtils.TruncateAt.END);
        labels.addView(details, params(0, 2, 0, 0));
        row.addView(labels, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView amount = text((transaction.isIncome ? "+" : "−")
                + money(transaction.amountCents) + " ₽", 13,
                transaction.isIncome ? GREEN : RED, true);
        amount.setSingleLine(true);
        row.addView(amount, wrapParams(4, 0, 3, 0));
        Button edit = compactButton("✎", () -> showEditTransactionDialog(transaction));
        row.addView(edit, wrapParams(4, 0, 0, 0));
        Button delete = compactButton("×", () -> new AlertDialog.Builder(requireContext())
                .setMessage("Удалить эту операцию?")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Удалить", (dialog, which) -> {
                    dataStore.deleteTransaction(transaction.id);
                    refresh();
                }).show());
        row.addView(delete);
        return row;
    }

    private View metric(String label, String amount, int accent) {
        LinearLayout card = card();
        card.setPadding(dp(8), dp(12), dp(8), dp(12));
        card.addView(text(label, 9, MUTED, true));
        card.addView(text(amount + " ₽", 13, accent, true), params(0, 5, 0, 0));
        return card;
    }

    private View categoryShortcut(String title, String subtitle, int accent, Runnable action) {
        LinearLayout box = card();
        box.setGravity(Gravity.CENTER);
        box.setPadding(dp(6), dp(10), dp(6), dp(10));
        TextView icon = text(title.equals("Зарплата") ? "▣" : "↗", 23, accent, true);
        box.addView(icon);
        box.addView(text(title, 12, TEXT, true), params(0, 3, 0, 0));
        box.addView(text(subtitle, 10, MUTED, false));
        box.setOnClickListener(view -> action.run());
        return box;
    }

    private View incomeCategoryCard(String category) {
        LinearLayout box = card();
        box.setPadding(dp(9), dp(8), dp(9), dp(8));
        LinearLayout titleRow = new LinearLayout(requireContext());
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView icon = text("↗", 17, GREEN, true);
        titleRow.addView(icon, wrapParams(0, 0, 5, 0));
        TextView title = text(category, 14, TEXT, true);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titleRow.addView(title, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView editMenu = text("⋮", 18, MUTED, true);
        editMenu.setGravity(Gravity.CENTER);
        editMenu.setContentDescription("Изменить категорию " + category);
        editMenu.setBackground(rounded(TILE, STROKE, 10));
        editMenu.setOnClickListener(view -> showQuickIncomeCategoryActions(category));
        titleRow.addView(editMenu, new LinearLayout.LayoutParams(dp(36), dp(36)));
        box.addView(titleRow);
        box.addView(text("Доход · добавить", 11, MUTED, false), params(0, 2, 0, 0));
        box.setOnClickListener(view -> showCategorizedEntryDialog(category, true));
        box.setOnLongClickListener(view -> {
            showQuickIncomeCategoryActions(category);
            return true;
        });
        return box;
    }

    private void showQuickIncomeCategoryActions(String category) {
        new AlertDialog.Builder(requireContext())
                .setTitle(category)
                .setItems(new String[]{"Изменить название", "Удалить категорию"},
                        (dialog, which) -> {
                            if (which == 0) {
                                showQuickIncomeCategoryDialog(category);
                            } else {
                                new AlertDialog.Builder(requireContext())
                                        .setMessage("Убрать «" + category
                                                + "» из быстрых доходов? Сохранённые операции останутся.")
                                        .setNegativeButton("Отмена", null)
                                        .setPositiveButton("Удалить",
                                                (confirm, action) -> {
                                                    dataStore.deleteQuickIncomeCategory(category);
                                                    refresh();
                                                }).show();
                            }
                        }).show();
    }

    private void showQuickIncomeCategoryDialog(@Nullable String existing) {
        EditText nameInput = input("Например: Премия", InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        if (existing != null) {
            nameInput.setText(existing);
            nameInput.setSelection(nameInput.length());
        }
        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setTitle(existing == null ? "Новая категория дохода"
                        : "Изменить категорию дохода")
                .setView(nameInput)
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Сохранить", null)
                .create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(view -> {
                    String name = nameInput.getText().toString().trim();
                    if (name.isEmpty()) {
                        nameInput.setError("Укажи название категории.");
                        return;
                    }
                    if (name.length() > 32) {
                        nameInput.setError("Название должно быть не длиннее 32 символов.");
                        return;
                    }
                    for (String current : dataStore.getQuickIncomeCategories()) {
                        if (!current.equalsIgnoreCase(existing == null ? "" : existing)
                                && current.equalsIgnoreCase(name)) {
                            nameInput.setError("Такая категория уже есть.");
                            return;
                        }
                    }
                    dataStore.saveQuickIncomeCategory(existing, name);
                    dialog.dismiss();
                    refresh();
                }));
        dialog.show();
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(requireContext());
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(14), dp(13), dp(14), dp(13));
        card.setBackground(rounded(CARD, STROKE, 18));
        return card;
    }

    private TextView chip(String label, Runnable action) {
        TextView chip = text(label, 11, BLUE, true);
        chip.setGravity(Gravity.CENTER);
        chip.setPadding(dp(8), dp(7), dp(8), dp(7));
        chip.setBackground(rounded(CARD, STROKE, 14));
        chip.setOnClickListener(view -> action.run());
        return chip;
    }

    private Button button(String label, int color, Runnable action) {
        Button button = new Button(requireContext());
        button.setText(label);
        button.setTextSize(13);
        button.setTextColor(0xFFFFFFFF);
        button.setAllCaps(false);
        button.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private Button compactButton(String label, Runnable action) {
        Button button = new Button(requireContext());
        button.setText(label);
        button.setTextSize(18);
        button.setTextColor(MUTED);
        button.setMinWidth(dp(30));
        button.setMinHeight(dp(32));
        button.setPadding(dp(4), 0, dp(4), 0);
        button.setAllCaps(false);
        button.setBackground(rounded(CARD, STROKE, 10));
        button.setOnClickListener(view -> action.run());
        return button;
    }

    private EditText input(String hint, int inputType) {
        EditText editText = new EditText(requireContext());
        editText.setHint(hint);
        editText.setTextColor(TEXT);
        editText.setHintTextColor(MUTED);
        editText.setInputType(inputType);
        editText.setSingleLine(true);
        editText.setBackground(rounded(TILE, STROKE, 12));
        editText.setPadding(dp(12), dp(9), dp(12), dp(9));
        return editText;
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

    private GradientDrawable rounded(int color, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp(radius));
        drawable.setStroke(dp(1), stroke);
        return drawable;
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

    private LinearLayout.LayoutParams weightParams(float weight) {
        return weightParams(weight, 0);
    }

    private LinearLayout.LayoutParams weightParams(float weight, int marginStart) {
        LinearLayout.LayoutParams result = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        result.setMargins(dp(marginStart), 0, 0, 0);
        return result;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private String money(long cents) {
        return new DecimalFormat("#,##0").format(cents / 100.0);
    }

    private String displayMonth(String month) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM", Locale.US).parse(month);
            return parsed == null ? month
                    : new SimpleDateFormat("LLLL yyyy", new Locale("ru")).format(parsed);
        } catch (java.text.ParseException exception) {
            return month;
        }
    }

    private String displayDate(String date) {
        try {
            Date parsed = new SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(date);
            return parsed == null ? date
                    : new SimpleDateFormat("d MMM", new Locale("ru")).format(parsed);
        } catch (java.text.ParseException exception) {
            return date;
        }
    }

    @Override
    public void onDestroy() {
        if (voiceRecognizer != null) {
            stopVoiceEntry();
        }
        if (dataStore != null) {
            dataStore.close();
        }
        super.onDestroy();
    }
}
