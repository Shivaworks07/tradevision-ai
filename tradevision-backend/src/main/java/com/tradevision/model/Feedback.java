package com.tradevision.model;

import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import java.time.LocalDateTime;

@Data @NoArgsConstructor
@Document(collection = "feedback")
public class Feedback {
    @Id private String id;
    private String  userId;
    private String  mobile;
    private String  type;          // BUG | SUGGESTION | FEATURE_REQUEST | OTHER
    private String  title;
    private String  description;
    private String  page;          // which page/feature
    private String  screenshotBase64;  // base64 image (optional)
    private String  screenshotMime;    // image/png, image/jpeg
    private String  status;        // OPEN | IN_REVIEW | RESOLVED | CLOSED
    private String  adminNote;
    private String  priority;      // LOW | MEDIUM | HIGH | CRITICAL
    private LocalDateTime createdAt  = LocalDateTime.now();
    private LocalDateTime updatedAt  = LocalDateTime.now();
    private String  userAgent;
    private String  appVersion;
}
