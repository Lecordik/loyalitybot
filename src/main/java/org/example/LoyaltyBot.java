package org.example;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.HybridBinarizer;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.GetFile;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Document;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.PhotoSize;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URL;
import java.sql.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Component
@RestController
@RequestMapping("/api")
public class LoyaltyBot extends TelegramLongPollingBot {
    @Value("${telegram.bot.token}")
    private String botToken;
    @Value("${telegram.bot.username}")
    private String botUsername;
    @Value("${telegram.webapp.url:}")
    private String webAppUrl;
    @Value("${telegram.channel.url}")
    private String channelUrl;
    @Value("${spring.datasource.url}")
    private String dbUrl;

    @Value("${spring.datasource.username}")
    private String dbUser;

    @Value("${spring.datasource.password}")
    private String dbPassword;
    @Value("${telegram.channel.id}")
    private String orderGroupId;

    private final Map<Long, List<Product>> pendingPurchases = new ConcurrentHashMap<>();

    public LoyaltyBot() {
        System.out.println("Initializing LoyaltyBot with webAppUrl: " + webAppUrl);
    }

    @Override
    public String getBotUsername() {
        return botUsername;
    }

    @Override
    public String getBotToken() {
        return botToken;
    }

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage()) {
            long chatId = update.getMessage().getChatId();
            if (update.getMessage().hasPhoto()) {
                processReceipt(chatId, update.getMessage().getPhoto(), null);
            } else if (update.getMessage().hasDocument()) {
                processReceipt(chatId, null, update.getMessage().getDocument());
            } else if (update.getMessage().hasText() && update.getMessage().getText().equals("/start")) {
                sendMenu(chatId);
            } else {
                sendMenu(chatId);
            }
        } else if (update.hasCallbackQuery()) {
            handleCallbackQuery(update.getCallbackQuery());
        }
    }

    private void sendMenu(long chatId) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Добро пожаловать в программу лояльности EskinLab! 🌿\n" +
                "Отправляйте чек (фото или PDF) для начисления бонусов, просматривайте баланс или выбирайте эксклюзивные товары в каталоге.\n\n" +
                "Выберите действие:");

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton sendReceipt = new InlineKeyboardButton();
        sendReceipt.setText("Отправить чек");
        sendReceipt.setCallbackData("send_receipt");
        row1.add(sendReceipt);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton checkBalance = new InlineKeyboardButton();
        checkBalance.setText("Посмотреть баланс");
        checkBalance.setCallbackData("check_balance");
        row2.add(checkBalance);

        if (webAppUrl != null && !webAppUrl.trim().isEmpty()) {
            List<InlineKeyboardButton> row3 = new ArrayList<>();
            InlineKeyboardButton openCatalog = new InlineKeyboardButton();
            openCatalog.setText("Открыть каталог");
            openCatalog.setUrl(webAppUrl);
            row3.add(openCatalog);
            keyboard.add(row3);
            System.out.println("Added WebApp button with URL: " + webAppUrl);
        } else {
            System.err.println("Warning: telegram.webapp.url is not set or empty in application.properties, skipping WebApp button");
        }

        keyboard.add(row1);
        keyboard.add(row2);
        markup.setKeyboard(keyboard);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void processReceipt(long chatId, List<PhotoSize> photos, Document document) {
        List<Product> purchasedProducts = new ArrayList<>();
        String receiptText = null;

        if (document != null) {
            receiptText = extractTextFromPDF(document.getFileId());
        } else if (photos != null) {
            PhotoSize photo = photos.get(photos.size() - 1);
            String fileId = photo.getFileId();
            String qrData = decodeQRCode(fileId);
            if (qrData != null) {
                purchasedProducts = parseQRData(qrData, chatId);
            } else {
                receiptText = extractTextFromImage(fileId);
            }
        }

        if (receiptText != null) {
            purchasedProducts = parseReceiptText(receiptText, chatId);
        }

        if (purchasedProducts.isEmpty()) {
            sendMessage(chatId, "Товары не найдены в чеке. Убедитесь, что чек содержит товары с нашим ИНН.");
            return;
        }

        pendingPurchases.put(chatId, purchasedProducts);
        sendConfirmationKeyboard(chatId, purchasedProducts);
    }

    private String decodeQRCode(String fileId) {
        try {
            GetFile getFile = new GetFile();
            getFile.setFileId(fileId);
            String filePath = execute(getFile).getFilePath();
            String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

            BufferedImage image = ImageIO.read(new URL(fileUrl).openStream());
            LuminanceSource source = new BufferedImageLuminanceSource(image);
            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));
            Result result = new MultiFormatReader().decode(bitmap);
            return result.getText();
        } catch (NotFoundException e) {
            return null;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private String extractTextFromImage(String fileId) {
        System.err.println("OCR not implemented. Please provide extracted text or integrate OCR service.");
        return null;
    }

    private String extractTextFromPDF(String fileId) {
        try {
            GetFile getFile = new GetFile();
            getFile.setFileId(fileId);
            String filePath = execute(getFile).getFilePath();
            String fileUrl = "https://api.telegram.org/file/bot" + botToken + "/" + filePath;

            InputStream inputStream = new URL(fileUrl).openStream();
            PDDocument document = PDDocument.load(inputStream);
            PDFTextStripper stripper = new PDFTextStripper();
            String text = stripper.getText(document);
            document.close();
            inputStream.close();
            return text;
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    private List<Product> parseQRData(String qrData, long chatId) {
        List<Product> purchased = new ArrayList<>();
        String[] targetINNs = {"2311342177", "772157761755"};

        String[] parts = qrData.split("\\|");
        String items = "";
        String inn = "";
        for (String part : parts) {
            if (part.startsWith("item=")) {
                items = part.substring(5);
            } else if (part.startsWith("inn=")) {
                inn = part.substring(4);
            }
        }

        if (!Arrays.asList(targetINNs).contains(inn)) {
            return purchased;
        }

        for (String item : items.split(",")) {
            purchased.add(new Product(0, item, inn, 500));
        }
        return purchased;
    }

    private List<Product> parseReceiptText(String receiptText, long chatId) {
        List<Product> purchased = new ArrayList<>();
        String[] targetINNs = {"2311342177", "772157761755"};
        String currentInn = null;

        String[] lines = receiptText.split("\n");
        Pattern innPattern = Pattern.compile("ИНН продавца\\s+(\\d{10,12})");
        Pattern itemPattern = Pattern.compile("\\d+\\s+(.+?)\\s+\\d+\\.\\d{2}");

        for (String line : lines) {
            line = line.trim();
            Matcher innMatcher = innPattern.matcher(line);
            if (innMatcher.find()) {
                currentInn = innMatcher.group(1);
                continue;
            }

            Matcher itemMatcher = itemPattern.matcher(line);
            if (itemMatcher.find() && currentInn != null && Arrays.asList(targetINNs).contains(currentInn)) {
                String itemName = itemMatcher.group(1).trim();
                purchased.add(new Product(0, itemName, currentInn, 500));
            }
        }

        return purchased;
    }

    private void sendConfirmationKeyboard(long chatId, List<Product> products) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText("Подтвердите товары из чека:\n" + products.stream()
                .map(p -> p.name + " (" + p.points + " баллов)")
                .collect(Collectors.joining("\n")));

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        List<List<InlineKeyboardButton>> keyboard = new ArrayList<>();

        List<InlineKeyboardButton> row1 = new ArrayList<>();
        InlineKeyboardButton confirm = new InlineKeyboardButton();
        confirm.setText("Подтвердить");
        confirm.setCallbackData("confirm_purchase");
        row1.add(confirm);

        List<InlineKeyboardButton> row2 = new ArrayList<>();
        InlineKeyboardButton cancel = new InlineKeyboardButton();
        cancel.setText("Отменить");
        cancel.setCallbackData("cancel_purchase");
        row2.add(cancel);

        keyboard.add(row1);
        keyboard.add(row2);
        markup.setKeyboard(keyboard);
        message.setReplyMarkup(markup);

        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    private void handleCallbackQuery(CallbackQuery callbackQuery) {
        long chatId = callbackQuery.getMessage().getChatId();
        String data = callbackQuery.getData();

        if (data.equals("send_receipt")) {
            sendMessage(chatId, "Отправьте чек (фото или PDF).");
        } else if (data.equals("confirm_purchase")) {
            List<Product> products = pendingPurchases.get(chatId);
            if (products != null) {
                String uniqueCode = getOrCreateClient(chatId);
                savePurchases(uniqueCode, products);
                double bonus = calculateBonuses(products, uniqueCode);
                sendMessage(chatId, String.format("Чек обработан! Найдено товаров: %d\nНачислено бонусов: %.2f", products.size(), bonus));
                pendingPurchases.remove(chatId);
            }
        } else if (data.equals("cancel_purchase")) {
            pendingPurchases.remove(chatId);
            sendMessage(chatId, "Обработка чека отменена.");
        } else if (data.equals("check_balance")) {
            String uniqueCode = getOrCreateClient(chatId);
            double balance = getBalance(uniqueCode);
            sendMessage(chatId, String.format("Ваш баланс: %.2f баллов", balance));
        }
    }

    private double calculateBonuses(List<Product> products, String uniqueCode) {
        double bonus = 0.0;
        double bonusRate = 0.05;

        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            for (Product product : products) {
                bonus += product.points * bonusRate;
            }
            String sql = "UPDATE clients SET bonus_balance = bonus_balance + ? WHERE unique_code = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setDouble(1, bonus);
            stmt.setString(2, uniqueCode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return bonus;
    }

    private double getBalance(String uniqueCode) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "SELECT bonus_balance FROM clients WHERE unique_code = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, uniqueCode);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getDouble("bonus_balance");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return 0.0;
    }

    private String getUniqueCodeByTelegramId(long telegramId) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "SELECT unique_code FROM clients WHERE telegram_id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setLong(1, telegramId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("unique_code");
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    private void savePurchases(String uniqueCode, List<Product> products) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "INSERT INTO purchases (client_id, product_id, product_name, purchase_date) " +
                    "VALUES ((SELECT client_id FROM clients WHERE unique_code = ?), ?, ?, ?)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            for (Product product : products) {
                stmt.setString(1, uniqueCode);
                stmt.setInt(2, product.id);
                stmt.setString(3, product.name);
                stmt.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private String getOrCreateClient(long telegramId) {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "SELECT unique_code FROM clients WHERE telegram_id = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setLong(1, telegramId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return rs.getString("unique_code");
            }

            String uniqueCode = String.valueOf(new Random().nextInt(Integer.MAX_VALUE));
            sql = "INSERT INTO clients (telegram_id, unique_code, bonus_balance) VALUES (?, ?, 0.0)";
            stmt = conn.prepareStatement(sql);
            stmt.setLong(1, telegramId);
            stmt.setString(2, uniqueCode);
            stmt.executeUpdate();
            return uniqueCode;
        } catch (SQLException e) {
            e.printStackTrace();
            return null;
        }
    }

    private void sendMessage(long chatId, String text) {
        SendMessage message = new SendMessage();
        message.setChatId(String.valueOf(chatId));
        message.setText(text);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
        }
    }

    public void startNewsletter() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
        scheduler.scheduleAtFixedRate(this::sendNewsletter, 0, 24, TimeUnit.HOURS);
    }

    private void sendNewsletter() {
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "SELECT c.telegram_id, c.unique_code " +
                    "FROM clients c " +
                    "WHERE NOT EXISTS (" +
                    "    SELECT 1 FROM purchases p " +
                    "    WHERE p.client_id = c.client_id " +
                    "    AND p.product_id IN (SELECT product_id FROM products WHERE inn IN (?, ?))" +
                    "    AND p.purchase_date > ?)";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setString(1, "2311342177");
            stmt.setString(2, "772157761755");
            stmt.setTimestamp(3, new Timestamp(System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000));
            ResultSet rs = stmt.executeQuery();

            while (rs.next()) {
                long telegramId = rs.getLong("telegram_id");
                String message = "Вы давно не покупали наши эксклюзивные товары! " +
                        "Посмотрите новинки в нашем канале: " + channelUrl;
                sendMessage(telegramId, message);
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // API для получения баланса
    @GetMapping("/balance")
    public ResponseEntity<Double> getUserBalance(@RequestParam("telegramId") long telegramId) {
        String uniqueCode = getUniqueCodeByTelegramId(telegramId);
        if (uniqueCode == null) {
            return ResponseEntity.badRequest().build();
        }
        double balance = getBalance(uniqueCode);
        return ResponseEntity.ok(balance);
    }

    // API для создания заказа
    @PostMapping("/order")
    public ResponseEntity<String> createOrder(@RequestBody OrderRequest orderRequest) {
        String uniqueCode = getUniqueCodeByTelegramId(orderRequest.getTelegramId());
        if (uniqueCode == null) {
            return ResponseEntity.badRequest().body("Пользователь не найден");
        }

        double balance = getBalance(uniqueCode);
        double orderCost = orderRequest.getItems().stream()
                .mapToDouble(item -> item.getPoints())
                .sum();

        if (balance < orderCost) {
            return ResponseEntity.badRequest().body("Недостаточно баллов");
        }

        // Списываем баллы
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPassword)) {
            String sql = "UPDATE clients SET bonus_balance = bonus_balance - ? WHERE unique_code = ?";
            PreparedStatement stmt = conn.prepareStatement(sql);
            stmt.setDouble(1, orderCost);
            stmt.setString(2, uniqueCode);
            stmt.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при списании баллов");
        }

        // Отправляем заказ в группу
        String orderMessage = String.format("Новый заказ от пользователя %d (%s, %s):\n%s\nОбщая стоимость: %.2f баллов",
                orderRequest.getTelegramId(),
                orderRequest.getName(),
                orderRequest.getPhone(),
                orderRequest.getItems().stream()
                        .map(item -> String.format("- %s: %d баллов", item.getName(), item.getPoints()))
                        .collect(Collectors.joining("\n")),
                orderCost);

        SendMessage message = new SendMessage();
        message.setChatId(orderGroupId);
        message.setText(orderMessage);
        try {
            execute(message);
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при отправке заказа в группу");
        }

        return ResponseEntity.ok("Заказ успешно отправлен");
    }

    // DTO для заказа
    static class OrderRequest {
        private long telegramId;
        private String name;
        private String phone;
        private List<OrderItem> items;

        public long getTelegramId() {
            return telegramId;
        }

        public void setTelegramId(long telegramId) {
            this.telegramId = telegramId;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getPhone() {
            return phone;
        }

        public void setPhone(String phone) {
            this.phone = phone;
        }

        public List<OrderItem> getItems() {
            return items;
        }

        public void setItems(List<OrderItem> items) {
            this.items = items;
        }
    }

    static class OrderItem {
        private String name;
        private int points;

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getPoints() {
            return points;
        }

        public void setPoints(int points) {
            this.points = points;
        }
    }
}