package com.macaber.attribution.dto;

import lombok.Data;

@Data
public class MergeFileDetail {
    private String path;
    private String code;
    private String diff;
}
