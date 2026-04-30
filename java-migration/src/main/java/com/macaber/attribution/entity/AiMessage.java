package com.macaber.attribution.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("ai_messages")
public class AiMessage {

    @TableId(type = IdType.AUTO)
    private Long id;

    @TableField("user_oa")
    private String userOa;

    @TableField("function_name")
    private String functionName;

    @TableField("function_arguments")
    private String functionArguments;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
