package com.macaber.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("attribution_chunk_details")
public class AttributionChunkDetail {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("report_id")
    private Long reportId;

    @TableField("user_id")
    private String userId;

    @TableField("file_path")
    private String filePath;

    @TableField("start_line")
    private Integer startLine;

    @TableField("end_line")
    private Integer endLine;

    @TableField("total_lines")
    private Integer totalLines;

    @TableField("analyzed_lines")
    private Integer analyzedLines;

    @TableField("attribution")
    private String attribution;

    @TableField("contributed_lines")
    private Double contributedLines;

    @TableField("matched_message_id")
    private String matchedMessageId;

    @TableField("matched_message_ids")
    private String matchedMessageIds;

    @TableField("score")
    private Double score;

    @TableField("match_type")
    private String matchType;

    @TableField("level")
    private String level;
}
