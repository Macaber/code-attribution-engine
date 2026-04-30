package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.util.List;

@Data
@Builder
public class AttributionJobData {
    private String mergeId;
    private String repoName;
    private String userId;
    private String sysCode;
    private String title;
    private List<MergeFileDetail> fileDetails;
    private List<AiMessageDto> aiMessages;
}
