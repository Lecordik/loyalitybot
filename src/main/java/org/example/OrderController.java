package org.example;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.exceptions.TelegramApiException;
import org.telegram.telegrambots.meta.bots.AbsSender;

@RestController
@RequestMapping("/api/orders")
public class OrderController {
    private final AbsSender bot;
    @Value("${telegram.channel.id}")
    private String channelId;

    public OrderController(AbsSender bot) {
        this.bot = bot;
    }

    @PostMapping
    public ResponseEntity<String> createOrder(@RequestBody OrderRequest order) {
        String message = String.format("Новый заказ:\nИмя: %s\nТелефон: %s\nТовары:\n%s\nИтого: %d баллов",
                order.getName(), order.getPhone(),
                String.join("\n", order.getItems().stream()
                        .map(item -> item.getName() + " - " + item.getPoints() + " баллов")
                        .toList()),
                order.getItems().stream().mapToInt(OrderRequest.Item::getPoints).sum());

        SendMessage sendMessage = new SendMessage();
        sendMessage.setChatId(channelId);
        sendMessage.setText(message);

        try {
            bot.execute(sendMessage);
            return ResponseEntity.ok("Заказ отправлен в канал");
        } catch (TelegramApiException e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Ошибка при отправке заказа");
        }
    }
}
