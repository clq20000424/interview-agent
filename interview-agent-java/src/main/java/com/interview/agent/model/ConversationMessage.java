package com.interview.agent.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConversationMessage {

    private String role;
    private String content;

    @JsonProperty("message_type")
    private String messageType;

    @JsonProperty("created_at")
    private LocalDateTime createdAt;
}
