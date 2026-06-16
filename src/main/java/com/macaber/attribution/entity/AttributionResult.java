package com.macaber.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("attribution_reports")
public class AttributionResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("merge_id")
    private String mergeId;

    @TableField("repo_name")
    private String repoName;

    @TableField("user_id")
    private String userId;

    @TableField("sys_code")
    private String sysCode;

    @TableField("title")
    private String title;
    
    @TableField("source")
    private String source;

    @TableField("target")
    private String target;

    @TableField("total_code_lines")
    private Integer totalCodeLines;

    @TableField("diff_lines")
    private Integer diffLines;

    @TableField("analyzed_lines")
    private Integer analyzedLines;

    @TableField("ai_contributed_lines")
    private Double aiContributedLines;

    @TableField("ai_contribution_ratio")
    private Double aiContributionRatio;

    @TableField("skipped_lines")
    private Integer skippedLines;

    @TableField("skipped_file_count")
    private Integer skippedFileCount;

    @TableField("strict_matches")
    private Integer strictMatches;

    @TableField("fuzzy_matches")
    private Integer fuzzyMatches;

    @TableField("deep_refactor_matches")
    private Integer deepRefactorMatches;

    @TableField("no_matches")
    private Integer noMatches;

    @TableField("elapsed_ms")
    private Integer elapsedMs;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
