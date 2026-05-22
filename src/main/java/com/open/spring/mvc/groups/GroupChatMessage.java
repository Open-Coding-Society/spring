package com.open.spring.mvc.groups;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class GroupChatMessage {
    private String id;
    private String name;
    private String message;
    private String date;
    private String image;

    public GroupChatMessage(String name, String message, String date, String image) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.message = message;
        this.date = date;
        this.image = image;
    }
}
