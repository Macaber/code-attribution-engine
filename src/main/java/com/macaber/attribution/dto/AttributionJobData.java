package com.macaber.attribution.dto;

import lombok.Builder;
import lombok.Data;
import java.io.Serializable;
import java.util.List;

/**
 * Job data queued to Redis for attribution processing.
 *
 * Only carries lightweight metadata and file details (diff + code).
 * AI messages are fetched by the worker from DB based on involved userIds
 * extracted from diff line prefixes.
 */
@Data
@Builder
public class AttributionJobData implements Serializable {
    private static final long serialVersionUID = 1L;

    private String mergeId;
    private Long reportId;
    private String repoName;
    private String userId;
    private String sysCode;
    private String title;
    private String source;
    private String target;
    private List<MergeFileDetail> fileDetails;
    private Integer timeframeDays;
}
