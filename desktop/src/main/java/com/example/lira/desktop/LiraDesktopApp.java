package com.example.lira.desktop;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.control.ScrollPane;
import javafx.stage.Stage;

import java.awt.Desktop;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalTime;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.security.SecureRandom;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public final class LiraDesktopApp extends Application {
    private static final String BACKGROUND = "#0e1621";
    private static final String CARD = "#17212b";
    private static final String TEXT = "#f2f5f7";
    private static final String MUTED = "#91a2af";
    private static final String ACCENT = "#00e676";

    private DesktopStore store;
    private Stage stage;
    private BorderPane shell;
    private Label pageTitle;
    private TableView<DesktopStore.FinanceEntry> financeTable;
    private TilePane goalsCards;
    private TableView<DesktopStore.NoteEntry> notesTable;
    private TableView<DesktopStore.BudgetEntry> budgetTable;
    private TableView<String> incomeCategoryTable;
    private Label goalCountLabel;
    private VBox chatMessages;
    private VBox plannerPanel;
    private LocalDate plannerDate = LocalDate.now();
    private DesktopStore.GoalEntry selectedGoal;
    private OfflineSpeechRecognizer speechRecognizer;
    private Button voiceButton;
    private final ApplicationLauncher applicationLauncher = new ApplicationLauncher();
    private final ExecutorService appLauncher = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "lira-app-launcher");
        thread.setDaemon(true);
        return thread;
    });
    private SyncServer syncServer;
    private LocalDate selectedMonth = LocalDate.now().withDayOfMonth(1);
    private boolean wakeWordMode;
    private boolean awaitingVoiceCommand;

    @Override
    public void start(Stage stage) throws Exception {
        this.stage = stage;
        installSpeechModel();
        store = new DesktopStore();
        shell = new BorderPane();
        shell.setStyle("-fx-background-color: " + BACKGROUND + ";");
        shell.setLeft(navigation());
        shell.setTop(header());
        stage.setTitle("L.I.R.A. — личный помощник");
        stage.setMinWidth(900);
        stage.setMinHeight(620);
        stage.setScene(new Scene(shell, 1120, 760));
        stage.setOnCloseRequest(event -> closeStore());
        showChat();
        stage.show();
    }

    private void showSettings() {
        pageTitle.setText("Настройки");
        CheckBox speech = new CheckBox("Озвучивать ответы L.I.R.A.");
        CheckBox wakeWord = new CheckBox("Голосовой режим по слову «Лира»");
        speech.setStyle("-fx-text-fill: " + TEXT + ";");
        wakeWord.setStyle("-fx-text-fill: " + TEXT + ";");
        try {
            speech.setSelected(Boolean.parseBoolean(store.setting("speech_output", "false")));
            wakeWord.setSelected(Boolean.parseBoolean(
                    store.setting("wake_word_mode", "false")));
        } catch (SQLException exception) {
            report("Не удалось загрузить настройки.", exception);
        }
        Label modelStatus = label("Офлайн-распознавание русского языка включено.", 13, MUTED);
        Button save = actionButton("Сохранить настройки", ACCENT);
        save.setOnAction(event -> {
            try {
                store.saveSetting("speech_output", String.valueOf(speech.isSelected()));
                store.saveSetting("wake_word_mode", String.valueOf(wakeWord.isSelected()));
                wakeWordMode = wakeWord.isSelected();
                Alert confirmation = new Alert(Alert.AlertType.INFORMATION,
                        "Настройки сохранены.", ButtonType.OK);
                confirmation.showAndWait();
            } catch (SQLException exception) {
                report("Не удалось сохранить настройки.", exception);
            }
        });
        Label syncStatus = label("Сервер синхронизации выключен.", 13, MUTED);
        Button startSync = actionButton("Включить синхронизацию по Wi‑Fi", "#5288c1");
        Button stopSync = actionButton("Выключить сервер синхронизации", "#516272");
        startSync.setOnAction(event -> {
            try {
                if (syncServer != null) {
                    syncServer.close();
                }
                String token = String.format(Locale.ROOT, "%08d",
                        new SecureRandom().nextInt(100_000_000));
                syncServer = new SyncServer(store, token,
                        new RemoteCommandHandler(applicationLauncher));
                syncServer.start();
                List<String> addresses = SyncServer.localAddresses();
                syncStatus.setText("Адрес ПК: " + (addresses.isEmpty()
                        ? "не удалось определить" : String.join(", ", addresses))
                        + "\nПорт: " + SyncServer.PORT + "\nКод пары: " + token
                        + "\nВведите адрес и код в настройках Android. Только доверенная Wi‑Fi сеть."
                        + "\nКоманды с телефона: «запусти на ПК …», «открой на ПК …», "
                        + "«найди на ПК …».");
            } catch (IOException exception) {
                report("Не удалось запустить синхронизацию.", exception);
            }
        });
        stopSync.setOnAction(event -> {
            if (syncServer != null) {
                syncServer.close();
                syncServer = null;
            }
            syncStatus.setText("Сервер синхронизации выключен.");
        });
        VBox content = new VBox(18, speech, wakeWord, modelStatus, save,
                label("Синхронизация с Android по локальной Wi‑Fi сети", 16, TEXT),
                startSync, stopSync, syncStatus,
                label("Пока соединение незашифровано: включай только в доверенной сети.",
                        12, MUTED));
        content.setPadding(new Insets(28));
        shell.setCenter(content);
        try {
            wakeWordMode = Boolean.parseBoolean(store.setting("wake_word_mode", "false"));
        } catch (SQLException exception) {
            report("Не удалось загрузить режим активации.", exception);
        }
    }

    private void speak(String text) {
        try {
            if (!Boolean.parseBoolean(store.setting("speech_output", "false"))) {
                return;
            }
            String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
            if (os.contains("win")) {
                String encoded = java.util.Base64.getEncoder().encodeToString(
                        text.getBytes(java.nio.charset.StandardCharsets.UTF_16LE));
                String script = "$t=[Text.Encoding]::Unicode.GetString("
                        + "[Convert]::FromBase64String('" + encoded + "'));"
                        + "(New-Object -ComObject SAPI.SpVoice).Speak($t) | Out-Null";
                new ProcessBuilder("powershell.exe", "-NoLogo", "-NoProfile",
                        "-WindowStyle", "Hidden", "-EncodedCommand",
                        java.util.Base64.getEncoder().encodeToString(script.getBytes(
                                java.nio.charset.StandardCharsets.UTF_16LE))).start();
            } else if (os.contains("mac")) {
                new ProcessBuilder("say", text).start();
            } else {
                new ProcessBuilder("spd-say", text).start();
            }
        } catch (IOException | SQLException exception) {
            report("Не удалось запустить озвучивание.", exception);
        }
    }

    private Node navigation() {
        VBox nav = new VBox(10);
        nav.setPadding(new Insets(22, 14, 22, 14));
        nav.setPrefWidth(205);
        nav.setStyle("-fx-background-color: " + CARD + ";");
        Label logo = new Label("L.I.R.A.");
        logo.setStyle("-fx-text-fill: " + ACCENT
                + "; -fx-font-size: 23px; -fx-font-weight: bold;");
        Label subtitle = label("ОФЛАЙН-ПОМОЩНИК", 10, MUTED);
        VBox brand = new VBox(4, logo, subtitle);
        brand.setPadding(new Insets(4, 8, 24, 8));
        nav.getChildren().add(brand);
        nav.getChildren().addAll(
                navButton("Чат", this::showChat),
                navButton("Финансы", this::showFinance),
                navButton("Цели и планер", this::showGoals),
                navButton("Заметки", this::showNotes),
                navButton("Настройки", this::showSettings));
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        nav.getChildren().add(spacer);
        Label local = label("Данные хранятся\nна этом компьютере", 11, MUTED);
        local.setWrapText(true);
        local.setPadding(new Insets(0, 8, 6, 8));
        nav.getChildren().add(local);
        return nav;
    }

    private Node header() {
        HBox header = new HBox();
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(18, 26, 16, 28));
        header.setStyle("-fx-border-color: transparent transparent #263746 transparent;");
        pageTitle = label("Чат", 22, TEXT);
        header.getChildren().add(pageTitle);
        return header;
    }

    private Button navButton(String title, Runnable action) {
        Button button = new Button(title);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setAlignment(Pos.CENTER_LEFT);
        button.setPadding(new Insets(12, 13, 12, 13));
        button.setStyle("-fx-background-color: transparent; -fx-text-fill: " + TEXT
                + "; -fx-font-size: 14px; -fx-background-radius: 10;");
        button.setOnAction(event -> action.run());
        return button;
    }

    private void showChat() {
        pageTitle.setText("Чат");
        chatMessages = new VBox(12);
        chatMessages.setPadding(new Insets(10));
        try {
            for (DesktopStore.ChatEntry message : store.messages()) {
                appendMessage(message.message(), message.user(), false);
            }
        } catch (SQLException exception) {
            report("Не удалось загрузить историю чата.", exception);
        }
        TextField input = new TextField();
        input.setPromptText("Напиши сообщение или команду");
        styleInput(input);
        Button send = actionButton("Отправить", ACCENT);
        voiceButton = actionButton("🎙 Голос", "#5288c1");
        voiceButton.setOnAction(event -> toggleVoiceInput());
        send.setOnAction(event -> {
            String text = input.getText().trim();
            if (!text.isEmpty()) {
                input.clear();
                processMessage(text);
            }
        });
        input.setOnAction(event -> send.fire());
        HBox composer = new HBox(10, input, voiceButton, send);
        composer.setAlignment(Pos.CENTER);
        VBox.setVgrow(input, Priority.NEVER);
        HBox.setHgrow(input, Priority.ALWAYS);
        ScrollPane history = new ScrollPane(chatMessages);
        history.setFitToWidth(true);
        history.setStyle("-fx-background: " + BACKGROUND + "; -fx-background-color: "
                + BACKGROUND + ";");
        VBox body = new VBox(12, history, composer);
        body.setPadding(new Insets(20, 26, 24, 26));
        VBox.setVgrow(history, Priority.ALWAYS);
        shell.setCenter(body);
    }

    private void processMessage(String text) {
        appendMessage(text, true, true);
        String normalized = text.trim();
        String lower = normalized.toLowerCase(Locale.ROOT);
        if (lower.startsWith("найди ") || lower.startsWith("найти ")) {
            String query = normalized.substring(normalized.indexOf(' ') + 1).trim();
            openSearch(query);
            appendMessage("Открываю поиск: " + query, false, true);
            return;
        }
        if (lower.startsWith("открой ") || lower.startsWith("открыть ")) {
            String name = normalized.substring(normalized.indexOf(' ') + 1).trim();
            launchApplication(name);
            return;
        }
        if (lower.startsWith("запиши ") || lower.startsWith("записать заметку ")) {
            String note = normalized.substring(normalized.indexOf(' ') + 1).trim();
            saveVoiceNote(note);
            return;
        }
        List<FinanceVoiceParser.Entry> financeEntries = FinanceVoiceParser.parse(normalized);
        if (!financeEntries.isEmpty()) {
            try {
                for (FinanceVoiceParser.Entry entry : financeEntries) {
                    store.addTransaction(entry.title(), entry.amountCents(), entry.income(),
                            entry.category());
                }
                appendMessage("Записала " + financeEntries.size()
                        + " финансовых операций.", false, true);
                if (financeTable != null) {
                    refreshFinance();
                }
                return;
            } catch (SQLException exception) {
                report("Не удалось сохранить голосовые операции.", exception);
            }
        }
        if (lower.startsWith("добавь задачу ") || lower.startsWith("добавить задачу ")) {
            addTaskFromCommand(normalized.substring(normalized.indexOf(' ') + 1)
                    .replaceFirst("(?iu)^задачу\\s+", ""));
            return;
        }
        appendMessage("Я работаю офлайн: могу открыть программу или найти запрос в интернете. "
                + "Можно также записать расход, доход, заметку или задачу.", false, true);
    }

    private void launchApplication(String requestedName) {
        appendMessage("Ищу установленную программу «" + requestedName + "»…", false, true);
        appLauncher.execute(() -> {
            try {
                if (applicationLauncher.open(requestedName)) {
                    Platform.runLater(() ->
                            appendMessage("Запускаю «" + requestedName + "».", false, true));
                } else {
                    Platform.runLater(() -> {
                        openSearch(requestedName);
                        appendMessage("Не нашла установленную программу «" + requestedName
                                + "». Открыла поиск в браузере.", false, true);
                    });
                }
            } catch (IOException exception) {
                Platform.runLater(() -> report("Не удалось найти или открыть программу.",
                        exception));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private void openSearch(String query) {
        try {
            if (Desktop.isDesktopSupported()) {
                Desktop.getDesktop().browse(URI.create(
                        "https://www.google.com/search?q="
                                + java.net.URLEncoder.encode(query, java.nio.charset.StandardCharsets.UTF_8)));
            } else {
                throw new IOException("Системный браузер недоступен.");
            }
        } catch (IOException | IllegalArgumentException exception) {
            report("Не удалось открыть поиск в браузере.", exception);
        }
    }

    private void toggleVoiceInput() {
        if (speechRecognizer != null && speechRecognizer.isListening()) {
            speechRecognizer.close();
            voiceButton.setText("🎙 Голос");
            return;
        }
        speechRecognizer = new OfflineSpeechRecognizer(
                OfflineSpeechRecognizer.defaultModelPath());
        voiceButton.setText("Слушаю… нажми, чтобы остановить");
        speechRecognizer.start(recognized -> Platform.runLater(() -> {
            String text = recognized;
            if (text.isBlank() || chatMessages == null) {
                return;
            }
            if (wakeWordMode && !awaitingVoiceCommand
                    && !text.toLowerCase(Locale.ROOT).contains("лира")) {
                return;
            }
            if (wakeWordMode && !awaitingVoiceCommand) {
                text = text.replaceFirst("(?iu).*?лира[,:\\s]*", "").trim();
                if (text.isBlank()) {
                    awaitingVoiceCommand = true;
                    speak("Слушаю команду.");
                    return;
                }
            }
            awaitingVoiceCommand = false;
            processMessage(text);
        }), exception -> Platform.runLater(() -> {
            speechRecognizer = null;
            voiceButton.setText("🎙 Голос");
            report("Не удалось распознать речь.", exception);
        }));
    }

    private void installSpeechModel() throws IOException {
        Path model = Path.of(System.getProperty("user.home"), ".lira", "model-ru");
        if (Files.isRegularFile(model.resolve("am").resolve("final.mdl"))) {
            return;
        }
        try (InputStream resource = getClass().getResourceAsStream("/model-ru.zip")) {
            if (resource == null) {
                throw new IOException("В комплекте отсутствует русская офлайн-модель речи.");
            }
            Files.createDirectories(model);
            try (ZipInputStream zip = new ZipInputStream(resource)) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    Path destination = model.resolve(entry.getName()).normalize();
                    if (!destination.startsWith(model.normalize())) {
                        throw new IOException("Некорректный путь в архиве офлайн-модели.");
                    }
                    if (entry.isDirectory()) {
                        Files.createDirectories(destination);
                    } else {
                        Files.createDirectories(destination.getParent());
                        Files.copy(zip, destination, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    zip.closeEntry();
                }
            }
        }
    }

    private void saveVoiceNote(String text) {
        String content = text.replaceFirst("(?iu)^(запиши|записать заметку)\\s*", "").trim();
        if (content.isBlank()) {
            appendMessage("Скажи или напиши текст заметки после слова «запиши».",
                    false, true);
            return;
        }
        try {
            String title = content.length() > 40 ? content.substring(0, 40) : content;
            store.addNote(title, content);
            appendMessage("Сохранила заметку.", false, true);
            if (notesTable != null) {
                refreshNotes();
            }
        } catch (SQLException exception) {
            report("Не удалось сохранить заметку.", exception);
        }
    }

    private void addTaskFromCommand(String title) {
        if (title.isBlank()) {
            appendMessage("Напиши название задачи после «добавь задачу».",
                    false, true);
            return;
        }
        try {
            store.addTask(title, LocalDate.now(), "09:00", null);
            appendMessage("Добавила в план на сегодня: " + title, false, true);
        } catch (SQLException exception) {
            report("Не удалось добавить задачу.", exception);
        }
    }

    private void appendMessage(String text, boolean user, boolean persist) {
        Label bubble = label(text, 14, TEXT);
        bubble.setWrapText(true);
        bubble.setMaxWidth(700);
        bubble.setPadding(new Insets(12, 16, 12, 16));
        bubble.setStyle("-fx-background-color: " + (user ? "#1c3c36" : CARD)
                + "; -fx-background-radius: 14;");
        HBox row = new HBox(bubble);
        row.setAlignment(user ? Pos.CENTER_RIGHT : Pos.CENTER_LEFT);
        chatMessages.getChildren().add(row);
        if (persist) {
            try {
                store.saveMessage(text, user);
                if (!user) {
                    speak(text);
                }
            } catch (SQLException exception) {
                report("Не удалось сохранить сообщение.", exception);
            }
        }
    }

    private void showFinance() {
        pageTitle.setText("Финансы");
        financeTable = new TableView<>();
        financeTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        financeTable.getColumns().add(column("Дата", entry -> entry.date()));
        financeTable.getColumns().add(column("Операция", entry -> entry.title()));
        financeTable.getColumns().add(column("Категория", entry -> entry.category()));
        financeTable.getColumns().add(column("Сумма", entry -> (entry.income() ? "+" : "−")
                + money(entry.amountCents())));
        DatePicker monthPicker = new DatePicker(selectedMonth);
        monthPicker.setOnAction(event -> {
            if (monthPicker.getValue() != null) {
                selectedMonth = monthPicker.getValue().withDayOfMonth(1);
                refreshFinance();
                budgetTable.getItems().setAll(loadBudgets());
                ((VBox) shell.getCenter()).getChildren().set(0, financeSummary(selectedMonth));
            }
        });
        refreshFinance();
        budgetTable = new TableView<>();
        budgetTable.setPrefHeight(180);
        budgetTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        budgetTable.getColumns().add(column("Категория", entry -> entry.category()));
        budgetTable.getColumns().add(column("Бюджет", entry -> money(entry.limitCents())));
        budgetTable.getColumns().add(column("Потрачено", entry -> money(entry.spentCents())));
        budgetTable.getColumns().add(column("Остаток",
                entry -> money(entry.limitCents() - entry.spentCents())));
        budgetTable.getItems().setAll(loadBudgets());
        incomeCategoryTable = new TableView<>();
        incomeCategoryTable.setPrefHeight(115);
        incomeCategoryTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_LAST_COLUMN);
        incomeCategoryTable.getColumns().add(column("Быстрые категории доходов", value -> value));
        try {
            incomeCategoryTable.getItems().setAll(store.incomeCategories());
        } catch (SQLException exception) {
            report("Не удалось загрузить категории доходов.", exception);
        }
        incomeCategoryTable.setRowFactory(table -> {
            javafx.scene.control.TableRow<String> row = new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) {
                    showQuickIncomeDialog(row.getItem());
                }
            });
            return row;
        });
        Button expense = actionButton("＋ Расход", "#ff6b6b");
        Button income = actionButton("＋ Доход", ACCENT);
        Button edit = actionButton("Изменить операцию", "#5288c1");
        Button delete = actionButton("Удалить операцию", "#516272");
        Button setBudget = actionButton("Настроить бюджет", "#5288c1");
        Button addIncomeCategory = actionButton("＋ Категория дохода", "#5288c1");
        Button editIncomeCategory = actionButton("Переименовать", "#516272");
        Button deleteIncomeCategory = actionButton("Удалить категорию", "#516272");
        expense.setOnAction(event -> showTransactionDialog(false));
        income.setOnAction(event -> showTransactionDialog(true));
        edit.setOnAction(event -> {
            DesktopStore.FinanceEntry selected = financeTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showTransactionDialog(selected.income(), selected);
            }
        });
        delete.setOnAction(event -> deleteSelectedTransaction());
        setBudget.setOnAction(event -> {
            DesktopStore.BudgetEntry selected = budgetTable.getSelectionModel().getSelectedItem();
            showBudgetDialog(selected == null ? "" : selected.category(),
                    selected == null ? 0 : selected.limitCents());
        });
        addIncomeCategory.setOnAction(event -> showIncomeCategoryDialog(null));
        editIncomeCategory.setOnAction(event -> {
            String selected = incomeCategoryTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showIncomeCategoryDialog(selected);
            }
        });
        deleteIncomeCategory.setOnAction(event -> deleteSelectedIncomeCategory());
        HBox actions = new HBox(10, expense, income, edit, delete);
        HBox budgetActions = new HBox(10, label("Бюджеты месяца", 16, TEXT), monthPicker,
                setBudget);
        HBox categoryActions = new HBox(10, addIncomeCategory, editIncomeCategory,
                deleteIncomeCategory);
        VBox content = new VBox(14, financeSummary(selectedMonth), actions, financeTable,
                budgetActions, budgetTable, label("Быстрый ввод дохода", 16, TEXT),
                categoryActions, incomeCategoryTable);
        content.setPadding(new Insets(22, 26, 22, 26));
        VBox.setVgrow(financeTable, Priority.ALWAYS);
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setStyle("-fx-background: " + BACKGROUND + "; -fx-background-color: "
                + BACKGROUND + ";");
        shell.setCenter(scroller);
    }

    private Node financeSummary(LocalDate month) {
        try {
            long[] totals = store.monthlyTotals(month.toString().substring(0, 7));
            HBox summary = new HBox(14,
                    metric("ДОХОДЫ", money(totals[0]), ACCENT),
                    metric("РАСХОДЫ", money(totals[1]), "#ff6b6b"),
                    metric("БАЛАНС", money(totals[0] - totals[1]), "#77aaff"));
            for (Node node : summary.getChildren()) {
                HBox.setHgrow(node, Priority.ALWAYS);
            }
            return summary;
        } catch (SQLException exception) {
            report("Не удалось рассчитать баланс.", exception);
            return new Label();
        }
    }

    private HBox metric(String title, String amount, String color) {
        Label name = label(title, 11, MUTED);
        Label value = label(amount, 20, color);
        VBox box = new VBox(6, name, value);
        box.setPadding(new Insets(16));
        box.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 14;");
        return new HBox(box);
    }

    private void refreshFinance() {
        try {
            financeTable.getItems().setAll(store.transactions().stream()
                    .filter(entry -> entry.date().startsWith(selectedMonth.toString()
                            .substring(0, 7))).toList());
        } catch (SQLException exception) {
            report("Не удалось загрузить операции.", exception);
        }
    }

    private void showTransactionDialog(boolean income) {
        showTransactionDialog(income, null);
    }

    private void showTransactionDialog(boolean income, DesktopStore.FinanceEntry existing) {
        TextField title = new TextField();
        title.setPromptText(income ? "Например: Зарплата" : "Например: Продукты");
        TextField amount = new TextField();
        amount.setPromptText("Сумма в рублях");
        TextField category = new TextField(income ? "Зарплата" : "Прочее");
        DatePicker date = new DatePicker(LocalDate.now());
        if (existing != null) {
            title.setText(existing.title());
            amount.setText(java.math.BigDecimal.valueOf(existing.amountCents(), 2).toPlainString());
            category.setText(existing.category());
            date.setValue(LocalDate.parse(existing.date()));
        }
        VBox form = new VBox(10, label("Описание", 12, MUTED), title,
                label("Сумма", 12, MUTED), amount, label("Категория", 12, MUTED), category,
                label("Дата", 12, MUTED), date);
        styleFields(title, amount, category);
        Alert dialog = formDialog(existing == null
                ? income ? "Добавить доход" : "Добавить расход" : "Изменить операцию", form);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                String description = title.getText().trim();
                long cents = parseCents(amount.getText());
                if (description.isEmpty() || cents <= 0 || category.getText().isBlank()) {
                    throw new IllegalArgumentException("Заполни название, категорию и сумму.");
                }
                if (existing == null) {
                    store.addTransaction(description, cents, income, date.getValue(),
                            category.getText().trim());
                } else {
                    store.updateTransaction(existing.id(), description, cents, income,
                            date.getValue(), category.getText().trim());
                }
                refreshFinance();
                ((VBox) ((ScrollPane) shell.getCenter()).getContent()).getChildren()
                        .set(0, financeSummary(selectedMonth));
                budgetTable.getItems().setAll(loadBudgets());
            } catch (IllegalArgumentException | SQLException exception) {
                report("Не удалось сохранить операцию.", exception);
            }
        });
    }

    private List<DesktopStore.BudgetEntry> loadBudgets() {
        try {
            return store.budgets(selectedMonth.toString().substring(0, 7));
        } catch (SQLException exception) {
            report("Не удалось загрузить бюджеты.", exception);
            return List.of();
        }
    }

    private void deleteSelectedTransaction() {
        DesktopStore.FinanceEntry selected = financeTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                "Удалить операцию «" + selected.title() + "»?",
                ButtonType.CANCEL, ButtonType.OK);
        confirmation.showAndWait().ifPresent(button -> {
            if (button == ButtonType.OK) {
                try {
                    store.deleteTransaction(selected.id());
                    refreshFinance();
                    budgetTable.getItems().setAll(loadBudgets());
                    ((VBox) ((ScrollPane) shell.getCenter()).getContent()).getChildren()
                            .set(0, financeSummary(selectedMonth));
                } catch (SQLException exception) {
                    report("Не удалось удалить операцию.", exception);
                }
            }
        });
    }

    private void showBudgetDialog(String initialCategory, long initialLimit) {
        TextField category = new TextField(initialCategory);
        category.setPromptText("Например: Продукты");
        TextField amount = new TextField(initialLimit == 0 ? ""
                : java.math.BigDecimal.valueOf(initialLimit, 2).toPlainString());
        amount.setPromptText("Лимит на месяц в рублях");
        styleFields(category, amount);
        Alert dialog = formDialog("Месячный бюджет",
                new VBox(10, label("Категория", 12, MUTED), category,
                        label("Лимит", 12, MUTED), amount));
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                if (category.getText().isBlank()) {
                    throw new IllegalArgumentException("Укажи категорию.");
                }
                store.saveBudget(category.getText().trim(), parseCents(amount.getText()));
                budgetTable.getItems().setAll(loadBudgets());
            } catch (SQLException | IllegalArgumentException exception) {
                report("Не удалось сохранить бюджет.", exception);
            }
        });
    }

    private void showIncomeCategoryDialog(String existing) {
        TextField category = new TextField(existing == null ? "" : existing);
        category.setPromptText("Например: Премия");
        styleInput(category);
        Alert dialog = formDialog(existing == null ? "Новая категория дохода"
                : "Изменить категорию", category);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            if (category.getText().isBlank()) {
                report("Укажи название категории.", null);
                return;
            }
            try {
                store.saveIncomeCategory(existing, category.getText().trim());
                incomeCategoryTable.getItems().setAll(store.incomeCategories());
            } catch (SQLException exception) {
                report("Не удалось сохранить категорию.", exception);
            }
        });
    }

    private void deleteSelectedIncomeCategory() {
        String selected = incomeCategoryTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        try {
            store.deleteIncomeCategory(selected);
            incomeCategoryTable.getItems().setAll(store.incomeCategories());
        } catch (SQLException exception) {
            report("Не удалось удалить категорию.", exception);
        }
    }

    private void showQuickIncomeDialog(String categoryName) {
        TextField description = new TextField(categoryName);
        TextField amount = new TextField();
        amount.setPromptText("Сумма в рублях");
        styleFields(description, amount);
        Alert dialog = formDialog("Быстрый доход: " + categoryName,
                new VBox(10, label("Описание", 12, MUTED), description,
                        label("Сумма", 12, MUTED), amount));
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                if (description.getText().isBlank()) {
                    throw new IllegalArgumentException("Укажи описание дохода.");
                }
                store.addTransaction(description.getText().trim(), parseCents(amount.getText()),
                        true, selectedMonth.withDayOfMonth(LocalDate.now().getDayOfMonth()),
                        categoryName);
                refreshFinance();
                ((VBox) ((ScrollPane) shell.getCenter()).getContent()).getChildren()
                        .set(0, financeSummary(selectedMonth));
            } catch (SQLException | IllegalArgumentException exception) {
                report("Не удалось записать доход.", exception);
            }
        });
    }

    private void showGoals() {
        pageTitle.setText("Цели и планер");
        Button add = actionButton("＋ Новая цель", ACCENT);
        add.setOnAction(event -> showGoalDialog());
        goalCountLabel = label("Загружаю цели…", 13, MUTED);
        VBox goalsHeader = new VBox(6,
                label("Твои цели", 22, TEXT),
                label("Большие задачи становятся понятнее, если отмечать каждый шаг.", 13, MUTED));
        HBox goalsToolbar = new HBox(12, goalCountLabel, new Region(), add);
        HBox.setHgrow(goalsToolbar.getChildren().get(1), Priority.ALWAYS);
        goalsCards = new TilePane();
        goalsCards.setHgap(14);
        goalsCards.setVgap(14);
        goalsCards.setPrefColumns(2);
        goalsCards.setTileAlignment(Pos.TOP_LEFT);
        goalsCards.setPrefTileWidth(440);
        goalsCards.setStyle("-fx-background-color: transparent;");
        goalsCards.widthProperty().addListener((observable, previous, width) -> {
            int columns = width.doubleValue() < 720 ? 1 : 2;
            goalsCards.setPrefColumns(columns);
            goalsCards.setPrefTileWidth(Math.max(280,
                    (width.doubleValue() - (columns - 1) * goalsCards.getHgap()) / columns));
        });
        refreshGoals();

        DatePicker date = new DatePicker(plannerDate);
        date.setOnAction(event -> {
            if (date.getValue() != null) {
                plannerDate = date.getValue();
                refreshPlanner();
            }
        });
        Button addTask = actionButton("＋ Задача", ACCENT);
        addTask.setOnAction(event -> showTaskDialog());
        Button addBlock = actionButton("＋ Пункт дня", "#5288c1");
        addBlock.setOnAction(event -> showDailyBlockDialog());
        Button configureCycle = actionButton("Настроить цикл работы / отдыха", "#516272");
        configureCycle.setOnAction(event -> configureWorkCycle());
        Button previousDay = compactButton("‹", "#253440");
        Button today = compactButton("Сегодня", "#253440");
        Button nextDay = compactButton("›", "#253440");
        previousDay.setOnAction(event -> {
            plannerDate = plannerDate.minusDays(1);
            date.setValue(plannerDate);
            refreshPlanner();
        });
        today.setOnAction(event -> {
            plannerDate = LocalDate.now();
            date.setValue(plannerDate);
            refreshPlanner();
        });
        nextDay.setOnAction(event -> {
            plannerDate = plannerDate.plusDays(1);
            date.setValue(plannerDate);
            refreshPlanner();
        });
        HBox plannerDateControls = new HBox(8, previousDay, today, nextDay, date);
        plannerDateControls.setAlignment(Pos.CENTER_LEFT);
        FlowPane plannerControls = new FlowPane(10, 10, plannerDateControls,
                addTask, addBlock, configureCycle);
        plannerControls.setAlignment(Pos.CENTER_LEFT);
        plannerPanel = new VBox(12);
        refreshPlanner();
        VBox plannerHeader = new VBox(6,
                label("План на каждый день", 22, TEXT),
                label("Задачи, привычки и заметки дня — в одном месте.", 13, MUTED));
        VBox content = new VBox(24,
                goalsHeader, goalsToolbar, goalsCards,
                new Region(), plannerHeader, plannerControls, plannerPanel);
        content.setPadding(new Insets(28, 32, 36, 32));
        content.getChildren().get(3).setStyle(
                "-fx-background-color: #263746; -fx-pref-height: 1;");
        ScrollPane scroller = new ScrollPane(content);
        scroller.setFitToWidth(true);
        scroller.setStyle("-fx-background: " + BACKGROUND + "; -fx-background-color: "
                + BACKGROUND + ";");
        shell.setCenter(scroller);
    }

    private void refreshGoals() {
        if (goalsCards == null) {
            return;
        }
        try {
            List<DesktopStore.GoalEntry> goals = store.goals();
            if (selectedGoal != null) {
                selectedGoal = goals.stream()
                        .filter(goal -> goal.id() == selectedGoal.id())
                        .findFirst().orElse(null);
            }
            goalCountLabel.setText(goals.size() == 1 ? "1 активная цель"
                    : goals.size() + " активных целей");
            goalsCards.getChildren().setAll(goals.stream().map(this::goalCard).toList());
            if (goals.isEmpty()) {
                VBox empty = new VBox(8,
                        label("Пока нет целей", 16, TEXT),
                        label("Добавь первую цель — срок и прогресс будут всегда на виду.", 12, MUTED));
                styleCard(empty);
                goalsCards.getChildren().setAll(empty);
            }
        } catch (SQLException exception) {
            report("Не удалось загрузить цели.", exception);
        }
    }

    private VBox goalCard(DesktopStore.GoalEntry goal) {
        LocalDate start = LocalDate.parse(goal.startDate());
        LocalDate deadline = LocalDate.parse(goal.targetDate());
        long daysLeft = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), deadline);
        long totalDays = Math.max(1,
                java.time.temporal.ChronoUnit.DAYS.between(start, deadline));
        long elapsedDays = Math.max(0, Math.min(totalDays,
                java.time.temporal.ChronoUnit.DAYS.between(start, LocalDate.now())));
        double scheduleProgress = elapsedDays / (double) totalDays;

        Label title = label(goal.title(), 17, TEXT);
        title.setWrapText(true);
        Label term = pill(goal.term(), "#283d4b", "#9fd8ff");
        Label days = pill(daysLeft < 0 ? "Срок прошёл"
                : daysLeft == 0 ? "Дедлайн сегодня" : daysLeft + " дн. осталось",
                daysLeft < 0 ? "#482f35" : "#263b33",
                daysLeft < 0 ? "#ff9a9a" : "#83efb8");
        HBox badges = new HBox(8, term, days);
        badges.setAlignment(Pos.CENTER_LEFT);

        ProgressBar timeline = new ProgressBar(scheduleProgress);
        timeline.setMaxWidth(Double.MAX_VALUE);
        timeline.setStyle("-fx-accent: " + (scheduleProgress >= 1 ? "#f5bd66" : ACCENT)
                + ";");
        Label timelineCaption = label(
                "Срок: " + elapsedDays + " из " + totalDays + " дн.", 11, MUTED);
        Label dates = label(start + "   →   " + deadline, 12, MUTED);
        Label progressCaption;
        try {
            int entries = store.goalProgress(goal.id()).size();
            progressCaption = label("Отметок прогресса: " + entries, 12, "#a7b8c4");
        } catch (SQLException exception) {
            report("Не удалось загрузить прогресс цели.", exception);
            progressCaption = label("Прогресс временно недоступен", 12, MUTED);
        }

        Button edit = compactButton("Изменить", "#283d4b");
        edit.setOnAction(event -> showGoalDialog(goal));
        Button mark = compactButton("＋ Шаг", "#20583e");
        mark.setOnAction(event -> {
            selectedGoal = goal;
            markGoalProgress();
        });
        Button history = compactButton("История", "#253440");
        history.setOnAction(event -> {
            selectedGoal = goal;
            showGoalProgressHistory();
        });
        Button delete = compactButton("Удалить", "#462e35");
        delete.setOnAction(event -> {
            Alert confirmation = new Alert(Alert.AlertType.CONFIRMATION,
                    "Удалить цель и связанные с ней отметки прогресса?", ButtonType.CANCEL,
                    ButtonType.OK);
            confirmation.setTitle("Удаление цели");
            confirmation.setHeaderText(goal.title());
            confirmation.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK) {
                    try {
                        store.deleteGoal(goal.id());
                        if (selectedGoal != null && selectedGoal.id() == goal.id()) {
                            selectedGoal = null;
                        }
                        refreshGoals();
                        refreshPlanner();
                    } catch (SQLException exception) {
                        report("Не удалось удалить цель.", exception);
                    }
                }
            });
        });
        HBox actions = new HBox(7, edit, mark, history, delete);
        actions.setAlignment(Pos.CENTER_LEFT);
        VBox card = new VBox(12, badges, title, timeline, timelineCaption, dates,
                progressCaption, actions);
        card.setPadding(new Insets(18));
        card.setMinWidth(260);
        card.setPrefWidth(420);
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 16;"
                + " -fx-border-color: #253746; -fx-border-radius: 16;");
        return card;
    }

    private void showGoalDialog() {
        showGoalDialog(null);
    }

    private void showGoalDialog(DesktopStore.GoalEntry existing) {
        TextField title = new TextField();
        title.setPromptText("Например: накопить на отпуск");
        DatePicker start = new DatePicker(LocalDate.now());
        DatePicker target = new DatePicker(LocalDate.now().plusMonths(1));
        TextField term = new TextField("месяц");
        if (existing != null) {
            title.setText(existing.title());
            start.setValue(LocalDate.parse(existing.startDate()));
            target.setValue(LocalDate.parse(existing.targetDate()));
            term.setText(existing.term());
        }
        VBox form = new VBox(10, label("Название", 12, MUTED), title,
                label("Дата начала", 12, MUTED), start,
                label("Срок / тип периода", 12, MUTED), term,
                label("Дата завершения", 12, MUTED), target);
        styleFields(title, term);
        Alert dialog = formDialog(existing == null ? "Новая цель" : "Изменить цель", form);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            if (title.getText().isBlank() || target.getValue() == null
                    || start.getValue() == null || target.getValue().isBefore(start.getValue())) {
                report("Укажи название и корректный срок цели.", null);
                return;
            }
            try {
                String period = term.getText().isBlank() ? "срок" : term.getText().trim();
                if (existing == null) {
                    store.addGoal(title.getText().trim(), start.getValue(), target.getValue(), period);
                } else {
                    store.updateGoal(existing.id(), title.getText().trim(), start.getValue(),
                            target.getValue(), period);
                }
                refreshGoals();
            } catch (SQLException exception) {
                report("Не удалось сохранить цель.", exception);
            }
        });
    }

    private void markGoalProgress() {
        DesktopStore.GoalEntry selected = selectedGoal;
        if (selected == null) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Прогресс цели");
        dialog.setHeaderText(selected.title());
        dialog.setContentText("Что сделал сегодня?");
        dialog.showAndWait().ifPresent(note -> {
            try {
                store.markGoalProgress(selected.id(), LocalDate.now(), note.trim());
                refreshGoals();
            } catch (SQLException exception) {
                report("Не удалось записать прогресс.", exception);
            }
        });
    }

    private void showGoalProgressHistory() {
        DesktopStore.GoalEntry selected = selectedGoal;
        if (selected == null) {
            return;
        }
        try {
            String content = store.goalProgress(selected.id()).stream()
                    .map(entry -> entry.date() + "  " + entry.note())
                    .reduce((first, second) -> first + "\n" + second)
                    .orElse("Прогресса пока нет.");
            Alert dialog = new Alert(Alert.AlertType.INFORMATION, content, ButtonType.OK);
            dialog.setTitle("История цели");
            dialog.setHeaderText(selected.title());
            dialog.showAndWait();
        } catch (SQLException exception) {
            report("Не удалось загрузить историю прогресса.", exception);
        }
    }

    private void refreshPlanner() {
        if (plannerPanel == null) {
            return;
        }
        plannerPanel.getChildren().clear();
        try {
            List<DesktopStore.TaskEntry> tasks = store.tasks(plannerDate);
            List<DesktopStore.DailyBlockEntry> habits = store.dailyBlocks();
            long completedTasks = tasks.stream().filter(DesktopStore.TaskEntry::done).count();
            long completedHabits = habits.stream()
                    .filter(block -> {
                        try {
                            return store.dailyEntryDone(plannerDate, block.id());
                        } catch (SQLException exception) {
                            report("Не удалось загрузить отметки привычек.", exception);
                            return false;
                        }
                    }).count();
            long totalEntries = tasks.size() + habits.size();
            long completedEntries = completedTasks + completedHabits;
            double completion = totalEntries == 0 ? 0
                    : completedEntries / (double) totalEntries;

            Label dayTitle = label(plannerDate.equals(LocalDate.now())
                    ? "Сегодня" : plannerDate.format(
                            java.time.format.DateTimeFormatter.ofPattern("EEEE, d MMMM",
                                    new Locale("ru", "RU"))), 17, TEXT);
            Label cycle = label(cycleSummary().replaceFirst("^ · ", ""), 12, "#a8c9df");
            ProgressBar completionBar = new ProgressBar(completion);
            completionBar.setMaxWidth(Double.MAX_VALUE);
            completionBar.setStyle("-fx-accent: " + ACCENT + ";");
            Label completionCaption = label(totalEntries == 0
                    ? "Добавь задачи или привычки — и план на день появится здесь."
                    : completedEntries + " из " + totalEntries + " пунктов выполнено",
                    12, MUTED);
            HBox dayHeading = new HBox(10, dayTitle, pill(
                    Math.round(completion * 100) + "% дня", "#263b33", "#83efb8"));
            dayHeading.setAlignment(Pos.CENTER_LEFT);
            VBox overview = new VBox(10, dayHeading, cycle, completionBar, completionCaption);
            overview.setPadding(new Insets(18));
            overview.setStyle("-fx-background-color: linear-gradient(to right, #1b302b, "
                    + "#17212b); -fx-background-radius: 16; -fx-border-color: #285142;"
                    + " -fx-border-radius: 16;");
            plannerPanel.getChildren().add(overview);

            VBox taskCard = new VBox(10);
            taskCard.getChildren().add(label("Задачи", 16, TEXT));
            if (tasks.isEmpty()) {
                taskCard.getChildren().add(label(
                        "Пока свободно. Добавь задачу, чтобы спланировать день.", 12, MUTED));
            }
            for (DesktopStore.TaskEntry task : tasks) {
                CheckBox done = new CheckBox();
                done.setSelected(task.done());
                done.setOnAction(event -> {
                    try {
                        store.setTaskDone(task.id(), done.isSelected());
                        refreshPlanner();
                    } catch (SQLException exception) {
                        report("Не удалось обновить задачу.", exception);
                    }
                });
                Label time = pill(task.time(), "#263b4b", "#9fd8ff");
                Label title = label(task.title(), 13, task.done() ? MUTED : TEXT);
                title.setWrapText(true);
                if (task.done()) {
                    title.setStyle("-fx-font-size: 13px; -fx-text-fill: " + MUTED
                            + "; -fx-strikethrough: true;");
                }
                VBox description = new VBox(4, title);
                if (!task.goalTitle().isBlank()) {
                    description.getChildren().add(label("Цель: " + task.goalTitle(), 11, MUTED));
                }
                HBox.setHgrow(description, Priority.ALWAYS);
                Button edit = compactButton("Изменить", "#283d4b");
                edit.setOnAction(event -> showTaskDialog(task));
                Button remove = compactButton("Удалить", "#462e35");
                remove.setOnAction(event -> {
                    try {
                        store.deleteTask(task.id());
                        refreshPlanner();
                    } catch (SQLException exception) {
                        report("Не удалось удалить задачу.", exception);
                    }
                });
                HBox row = new HBox(10, done, time, description, edit, remove);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(9, 10, 9, 10));
                row.setStyle("-fx-background-color: #1e2b36; -fx-background-radius: 11;");
                taskCard.getChildren().add(row);
            }
            styleCard(taskCard);
            plannerPanel.getChildren().add(taskCard);

            VBox habitCard = new VBox(10);
            HBox habitHeader = new HBox(8,
                    label("Ежедневные привычки", 16, TEXT),
                    pill(completedHabits + "/" + habits.size(), "#263b33", "#83efb8"));
            habitHeader.setAlignment(Pos.CENTER_LEFT);
            habitCard.getChildren().add(habitHeader);
            if (habits.isEmpty()) {
                habitCard.getChildren().add(label(
                        "Добавь повторяющиеся пункты дня, чтобы не держать их в голове.", 12, MUTED));
            }
            for (DesktopStore.DailyBlockEntry block : habits) {
                CheckBox check = new CheckBox(block.title());
                check.setSelected(store.dailyEntryDone(plannerDate, block.id()));
                check.setStyle("-fx-text-fill: " + TEXT + "; -fx-font-size: 13px;");
                check.setOnAction(event -> {
                    try {
                        store.setDailyEntry(plannerDate, block.id(), check.isSelected());
                        refreshPlanner();
                    } catch (SQLException exception) {
                        report("Не удалось сохранить отметку.", exception);
                    }
                });
                Label subtitle = label(block.subtitle(), 11, MUTED);
                Button edit = compactButton("⋯", "#253440");
                edit.setOnAction(event -> showDailyBlockActions(block));
                HBox row = new HBox(10, check, subtitle, new Region(), edit);
                HBox.setHgrow(row.getChildren().get(2), Priority.ALWAYS);
                row.setAlignment(Pos.CENTER_LEFT);
                habitCard.getChildren().add(row);
            }
            styleCard(habitCard);
            plannerPanel.getChildren().add(habitCard);

            DesktopStore.DayRecordEntry record = store.dayRecord(plannerDate);
            TextField development = new TextField(record.developmentNote());
            TextField diary = new TextField(record.diary());
            TextField rest = new TextField(record.restActivity());
            styleFields(development, diary, rest);
            ComboBox<String> effort = new ComboBox<>();
            effort.getItems().addAll("LOW", "NORMAL", "HIGH");
            effort.setValue(record.effort());
            ComboBox<String> energy = new ComboBox<>();
            energy.getItems().addAll("LOW", "NORMAL", "HIGH");
            energy.setValue(record.energy());
            effort.setMaxWidth(Double.MAX_VALUE);
            energy.setMaxWidth(Double.MAX_VALUE);
            effort.setStyle("-fx-background-color: #1e2934; -fx-text-fill: " + TEXT + ";");
            energy.setStyle("-fx-background-color: #1e2934; -fx-text-fill: " + TEXT + ";");
            Button saveDay = actionButton("Сохранить дневник дня", ACCENT);
            Label saveHint = label("Самочувствие, заметки и отдых сохраняются на этом устройстве.",
                    11, MUTED);
            saveDay.setOnAction(event -> {
                try {
                    store.saveDayRecord(new DesktopStore.DayRecordEntry(plannerDate,
                            effort.getValue(), energy.getValue(), development.getText(),
                            diary.getText(), rest.getText()));
                    saveHint.setText("Сохранено · " + LocalTime.now().format(
                            java.time.format.DateTimeFormatter.ofPattern("HH:mm")));
                } catch (SQLException exception) {
                    report("Не удалось сохранить дневную запись.", exception);
                }
            });
            HBox wellbeing = new HBox(12,
                    fieldGroup("Усилие", effort), fieldGroup("Энергия", energy));
            HBox.setHgrow(wellbeing.getChildren().get(0), Priority.ALWAYS);
            HBox.setHgrow(wellbeing.getChildren().get(1), Priority.ALWAYS);
            VBox journal = new VBox(10,
                    label("Заметки дня", 16, TEXT),
                    label("Коротко зафиксируй состояние и то, что хочется запомнить.", 12, MUTED),
                    wellbeing,
                    fieldGroup("Развитие", development),
                    fieldGroup("Дневник", diary),
                    fieldGroup("Отдых и восстановление", rest),
                    new HBox(12, saveDay, saveHint));
            styleCard(journal);
            plannerPanel.getChildren().add(journal);
        } catch (SQLException exception) {
            report("Не удалось загрузить план на день.", exception);
        }
    }

    private String cycleSummary() {
        try {
            if (!Boolean.parseBoolean(store.setting("work_cycle_enabled", "true"))) {
                return " · режим 2/2 выключен";
            }
            int workDays = Integer.parseInt(store.setting("work_cycle_work_days", "2"));
            int restDays = Integer.parseInt(store.setting("work_cycle_rest_days", "2"));
            LocalDate start = LocalDate.parse(store.setting("work_cycle_start",
                    LocalDate.now().toString()));
            long days = Math.max(0, java.time.temporal.ChronoUnit.DAYS.between(start, plannerDate));
            int cycleDay = (int) (days % (workDays + restDays)) + 1;
            return " · " + (cycleDay <= workDays ? "рабочий" : "отдых")
                    + " день " + cycleDay + " цикла " + workDays + "/" + restDays;
        } catch (SQLException | RuntimeException exception) {
            return "";
        }
    }

    private void showTaskDialog() {
        showTaskDialog(null);
    }

    private void showTaskDialog(DesktopStore.TaskEntry existing) {
        TextField title = new TextField();
        title.setPromptText("Название задачи");
        TextField time = new TextField(existing == null ? "09:00" : existing.time());
        DatePicker date = new DatePicker(existing == null ? plannerDate : existing.date());
        if (existing != null) {
            title.setText(existing.title());
        }
        ComboBox<DesktopStore.GoalEntry> goal = new ComboBox<>();
        try {
            goal.getItems().setAll(store.goals());
        } catch (SQLException exception) {
            report("Не удалось загрузить список целей.", exception);
            return;
        }
        goal.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(DesktopStore.GoalEntry item) {
                return item == null ? "Без цели" : item.title();
            }

            @Override
            public DesktopStore.GoalEntry fromString(String value) {
                return null;
            }
        });
        goal.setPromptText("Привязать к цели (необязательно)");
        if (existing != null && existing.goalId() != null) {
            goal.getItems().stream().filter(item -> item.id() == existing.goalId())
                    .findFirst().ifPresent(goal::setValue);
        }
        VBox form = new VBox(10, label("Задача", 12, MUTED), title,
                label("Дата", 12, MUTED), date, label("Время (ЧЧ:ММ)", 12, MUTED),
                time, label("Цель", 12, MUTED), goal);
        styleFields(title, time);
        Alert dialog = formDialog(existing == null ? "Добавить задачу" : "Изменить задачу", form);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                if (title.getText().isBlank() || date.getValue() == null
                        || !time.getText().matches("(?:[01]\\d|2[0-3]):[0-5]\\d")) {
                    throw new IllegalArgumentException("Проверь название, дату и время задачи.");
                }
                DesktopStore.GoalEntry selectedGoal = goal.getValue();
                Long goalId = selectedGoal == null ? null : selectedGoal.id();
                if (existing == null) {
                    store.addTask(title.getText().trim(), date.getValue(), time.getText(), goalId);
                } else {
                    store.updateTask(existing.id(), title.getText().trim(), date.getValue(),
                            time.getText(), goalId);
                }
                plannerDate = date.getValue();
                refreshPlanner();
            } catch (SQLException | IllegalArgumentException exception) {
                report("Не удалось добавить задачу.", exception);
            }
        });
    }

    private void showDailyBlockDialog() {
        TextField title = new TextField();
        title.setPromptText("Название пункта");
        TextField subtitle = new TextField();
        subtitle.setPromptText("Подсказка (необязательно)");
        ComboBox<Integer> group = new ComboBox<>();
        group.getItems().addAll(0, 1, 2, 3, 4);
        group.setValue(2);
        group.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(Integer value) {
                return switch (value) {
                    case 0 -> "Утро";
                    case 1 -> "Развитие";
                    case 2 -> "Фокус";
                    case 3 -> "Тело";
                    default -> "Завершение дня";
                };
            }

            @Override
            public Integer fromString(String value) {
                return 2;
            }
        });
        styleFields(title, subtitle);
        Alert dialog = formDialog("Новый пункт дня",
                new VBox(10, label("Название", 12, MUTED), title,
                        label("Подсказка", 12, MUTED), subtitle,
                        label("Раздел", 12, MUTED), group));
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                if (title.getText().isBlank()) {
                    throw new IllegalArgumentException("Укажи название пункта.");
                }
                String[] names = {"🌅 Утро", "🧠 Развитие", "🎯 Фокус",
                        "💪 Тело", "🌙 Завершение дня"};
                String[] captions = {"Запустить день без перегруза",
                        "Выбери собственное направление", "Работа над главным",
                        "Движение по силам", "Спокойный финиш"};
                int groupPosition = group.getValue();
                String groupId = "group_" + groupPosition;
                int position = (int) store.dailyBlocks().stream()
                        .filter(item -> item.groupId().equals(groupId))
                        .count();
                store.saveDailyBlock(new DesktopStore.DailyBlockEntry(UUID.randomUUID().toString(),
                        groupId, names[groupPosition], captions[groupPosition],
                        title.getText().trim(), subtitle.getText().trim(),
                        groupPosition, position));
                refreshPlanner();
            } catch (SQLException | IllegalArgumentException exception) {
                report("Не удалось добавить пункт.", exception);
            }
        });
    }

    private void showDailyBlockActions(DesktopStore.DailyBlockEntry block) {
        TextInputDialog dialog = new TextInputDialog(block.title());
        dialog.setTitle("Изменить пункт");
        dialog.setHeaderText("Изменить название или оставь пустым, чтобы удалить пункт.");
        dialog.showAndWait().ifPresent(value -> {
            try {
                if (value.isBlank()) {
                    store.deleteDailyBlock(block.id());
                } else {
                    store.saveDailyBlock(new DesktopStore.DailyBlockEntry(block.id(),
                            block.groupId(), block.groupTitle(), block.groupCaption(),
                            value.trim(), block.subtitle(), block.groupPosition(),
                            block.position()));
                }
                refreshPlanner();
            } catch (SQLException exception) {
                report("Не удалось сохранить пункт.", exception);
            }
        });
    }

    private void configureWorkCycle() {
        TextField work = new TextField();
        TextField rest = new TextField();
        DatePicker start = new DatePicker();
        CheckBox enabled = new CheckBox("Включить режим рабочего цикла");
        try {
            work.setText(store.setting("work_cycle_work_days", "2"));
            rest.setText(store.setting("work_cycle_rest_days", "2"));
            start.setValue(LocalDate.parse(store.setting("work_cycle_start",
                    LocalDate.now().toString())));
            enabled.setSelected(Boolean.parseBoolean(
                    store.setting("work_cycle_enabled", "true")));
        } catch (SQLException | RuntimeException exception) {
            report("Не удалось прочитать настройки цикла.", exception);
            return;
        }
        styleFields(work, rest);
        Alert dialog = formDialog("Рабочий график",
                new VBox(10, enabled, label("Рабочих дней подряд (1–31)", 12, MUTED), work,
                        label("Дней отдыха подряд (1–31)", 12, MUTED), rest,
                        label("Первый день цикла", 12, MUTED), start));
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            try {
                int workDays = Integer.parseInt(work.getText());
                int restDays = Integer.parseInt(rest.getText());
                if (workDays < 1 || workDays > 31 || restDays < 1 || restDays > 31
                        || start.getValue() == null) {
                    throw new IllegalArgumentException("Введи значения от 1 до 31 и дату начала.");
                }
                store.saveSetting("work_cycle_enabled", String.valueOf(enabled.isSelected()));
                store.saveSetting("work_cycle_work_days", String.valueOf(workDays));
                store.saveSetting("work_cycle_rest_days", String.valueOf(restDays));
                store.saveSetting("work_cycle_start", start.getValue().toString());
                refreshPlanner();
            } catch (SQLException | IllegalArgumentException exception) {
                report("Не удалось сохранить график.", exception);
            }
        });
    }

    private void showNotes() {
        pageTitle.setText("Заметки");
        notesTable = new TableView<>();
        notesTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        notesTable.getColumns().add(column("Дата", note -> note.createdAt()));
        notesTable.getColumns().add(column("Название", note -> note.title()));
        notesTable.getColumns().add(column("Текст", note -> note.content()));
        refreshNotes();
        Button add = actionButton("＋ Новая заметка", ACCENT);
        Button edit = actionButton("Изменить", "#5288c1");
        Button delete = actionButton("Удалить выбранную", "#516272");
        add.setOnAction(event -> showNoteDialog());
        edit.setOnAction(event -> {
            DesktopStore.NoteEntry selected = notesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                showNoteDialog(selected);
            }
        });
        delete.setOnAction(event -> {
            DesktopStore.NoteEntry selected = notesTable.getSelectionModel().getSelectedItem();
            if (selected != null) {
                try {
                    store.deleteNote(selected.id());
                    refreshNotes();
                } catch (SQLException exception) {
                    report("Не удалось удалить заметку.", exception);
                }
            }
        });
        notesTable.setRowFactory(table -> {
            javafx.scene.control.TableRow<DesktopStore.NoteEntry> row =
                    new javafx.scene.control.TableRow<>();
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && event.getClickCount() == 2) {
                    showNoteDialog(row.getItem());
                }
            });
            return row;
        });
        VBox content = new VBox(16, new HBox(10, add, edit, delete), notesTable);
        content.setPadding(new Insets(22, 26, 22, 26));
        VBox.setVgrow(notesTable, Priority.ALWAYS);
        shell.setCenter(content);
    }

    private void refreshNotes() {
        try {
            notesTable.getItems().setAll(store.notes());
        } catch (SQLException exception) {
            report("Не удалось загрузить заметки.", exception);
        }
    }

    private void showNoteDialog() {
        showNoteDialog(null);
    }

    private void showNoteDialog(DesktopStore.NoteEntry existing) {
        TextField title = new TextField();
        title.setPromptText("Название заметки");
        TextArea body = new TextArea();
        body.setPromptText("Текст");
        body.setWrapText(true);
        if (existing != null) {
            title.setText(existing.title());
            body.setText(existing.content());
        }
        styleInput(title);
        styleInput(body);
        VBox form = new VBox(10, title, body);
        Alert dialog = formDialog(existing == null ? "Новая заметка" : "Изменить заметку", form);
        dialog.getButtonTypes().setAll(ButtonType.CANCEL, ButtonType.OK);
        dialog.showAndWait().ifPresent(button -> {
            if (button != ButtonType.OK) {
                return;
            }
            if (title.getText().isBlank() || body.getText().isBlank()) {
                report("Заполни название и текст заметки.", null);
                return;
            }
            try {
                if (existing == null) {
                    store.addNote(title.getText().trim(), body.getText().trim());
                } else {
                    store.updateNote(existing.id(), title.getText().trim(), body.getText().trim());
                }
                refreshNotes();
            } catch (SQLException exception) {
                report("Не удалось сохранить заметку.", exception);
            }
        });
    }

    private <T> TableColumn<T, String> column(String title,
                                              java.util.function.Function<T, String> value) {
        TableColumn<T, String> column = new TableColumn<>(title);
        column.setCellValueFactory(cell ->
                new javafx.beans.property.SimpleStringProperty(value.apply(cell.getValue())));
        return column;
    }

    private Alert formDialog(String title, Node content) {
        Alert dialog = new Alert(Alert.AlertType.NONE);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.getDialogPane().setContent(content);
        dialog.getDialogPane().setPrefWidth(430);
        dialog.getDialogPane().setStyle("-fx-background-color: " + CARD
                + "; -fx-text-fill: " + TEXT + ";");
        return dialog;
    }

    private Button actionButton(String title, String color) {
        Button button = new Button(title);
        button.setPadding(new Insets(10, 16, 10, 16));
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: #101820;"
                + " -fx-font-weight: bold; -fx-background-radius: 10;");
        return button;
    }

    private Button compactButton(String title, String color) {
        Button button = new Button(title);
        button.setPadding(new Insets(7, 10, 7, 10));
        button.setStyle("-fx-background-color: " + color + "; -fx-text-fill: #dce7ed;"
                + " -fx-font-size: 11px; -fx-background-radius: 9;");
        return button;
    }

    private Label pill(String text, String background, String foreground) {
        Label pill = label(text, 11, foreground);
        pill.setPadding(new Insets(5, 9, 5, 9));
        pill.setStyle("-fx-font-size: 11px; -fx-text-fill: " + foreground
                + "; -fx-background-color: " + background + "; -fx-background-radius: 20;");
        return pill;
    }

    private VBox fieldGroup(String title, Node field) {
        return new VBox(6, label(title, 11, MUTED), field);
    }

    private void styleCard(VBox card) {
        card.setPadding(new Insets(16));
        card.setStyle("-fx-background-color: " + CARD + "; -fx-background-radius: 15;"
                + " -fx-border-color: #253746; -fx-border-radius: 15;");
    }

    private Label label(String text, int size, String color) {
        Label label = new Label(text);
        label.setStyle("-fx-font-size: " + size + "px; -fx-text-fill: " + color + ";");
        return label;
    }

    private void styleFields(TextField... fields) {
        for (TextField field : fields) {
            styleInput(field);
        }
    }

    private void styleInput(javafx.scene.control.TextInputControl input) {
        input.setStyle("-fx-background-color: #1e2934; -fx-text-fill: " + TEXT
                + "; -fx-prompt-text-fill: " + MUTED + "; -fx-background-radius: 8;");
    }

    private long parseCents(String text) {
        String normalized = text.trim().replace(" ", "").replace(',', '.');
        try {
            java.math.BigDecimal value = new java.math.BigDecimal(normalized);
            if (value.signum() <= 0) {
                return 0;
            }
            return value.movePointRight(2).longValueExact();
        } catch (NumberFormatException | ArithmeticException exception) {
            throw new IllegalArgumentException("Укажи корректную сумму в рублях.");
        }
    }

    private String money(long cents) {
        return NumberFormat.getCurrencyInstance(new Locale("ru", "RU"))
                .format(cents / 100.0);
    }

    private void report(String message, Exception exception) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle("L.I.R.A.");
        alert.setHeaderText(message);
        alert.setContentText(exception == null ? "" : exception.getMessage());
        alert.showAndWait();
    }

    private void closeStore() {
        appLauncher.shutdownNow();
        if (syncServer != null) {
            syncServer.close();
            syncServer = null;
        }
        if (store != null) {
            try {
                store.close();
            } catch (SQLException exception) {
                exception.printStackTrace();
            }
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
