package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@Builder
public class AiMessageDto {
    private String messageId;
    private String userId;
    private LocalDateTime timestamp;
    private String rawContent;
    private String fileName;
    /** Content after normalization (populated during processing) */
    private String normalizedContent;
}
