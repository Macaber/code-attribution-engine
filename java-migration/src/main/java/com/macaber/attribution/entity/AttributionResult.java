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
@TableName("attribution_results")
public class AttributionResult {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("merge_id")
    private String mergeId;

    @TableField("repo_name")
    private String repoName;

    @TableField("user_oa")
    private String userOa;

    @TableField("total_code_lines")
    private Integer totalCodeLines;

    @TableField("analyzed_lines")
    private Integer analyzedLines;

    @TableField("ai_contributed_lines")
    private Double aiContributedLines;

    @TableField("ai_contribution_ratio")
    private Double aiContributionRatio;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
