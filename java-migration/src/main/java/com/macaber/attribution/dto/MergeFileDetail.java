package com.macaber.attribution.dto;

import lombok.Data;
import java.io.Serializable;

@Data
public class MergeFileDetail implements Serializable {
    private static final long serialVersionUID = 1L;

    private String path;
    private String code;
    private String diff;
}
