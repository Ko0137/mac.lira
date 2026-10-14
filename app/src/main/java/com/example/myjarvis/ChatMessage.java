package com.example.myjarvis;

public class ChatMessage {
    private String message;
    private boolean isUser;
    private String time;

    // Новый конструктор со временем
    public ChatMessage(String message, boolean isUser, String time) {
        this.message = message;
        this.isUser = isUser;
        this.time = time;
    }

    // Старый конструктор для MainActivity (чтобы не было ошибок компиляции)
    public ChatMessage(String message, boolean isUser) {
        this.message = message;
        this.isUser = isUser;
        this.time = ""; 
    }

    public String getMessage() {
        return message;
    }

    // Метод-псевдоним для ChatMessageAdapter
    public String getText() {
        return message;
    }

    public boolean isUser() {
        return isUser;
    }

    public String getTime() {
        return time;
    }
}
