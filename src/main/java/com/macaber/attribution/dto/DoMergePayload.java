package com.macaber.attribution.dto;

import lombok.Data;

@Data
public class DoMergePayload {
    private String oa;
    private String sysCode;
    private String sysName;
    private String repoName;
    private String mergeId;
    private String title;
    private String createTime;
    private String detail; // JSON string mapping to List<MergeFileDetail>
}
