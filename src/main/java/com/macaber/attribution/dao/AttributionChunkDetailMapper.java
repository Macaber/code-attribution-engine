package com.macaber.attribution.dao;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.macaber.attribution.dto.ChunkQueryResultDto;
import com.macaber.attribution.entity.AttributionChunkDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Map;

@Mapper
public interface AttributionChunkDetailMapper extends BaseMapper<AttributionChunkDetail> {

    @Select("<script>" +
            "SELECT c.id, c.report_id as reportId, c.user_id as userId, c.file_path as filePath, " +
            "c.start_line as startLine, c.end_line as endLine, c.total_lines as totalLines, " +
            "c.analyzed_lines as analyzedLines, c.attribution, c.contributed_lines as contributedLines, " +
            "c.matched_message_id as matchedMessageId, c.matched_message_ids as matchedMessageIds, " +
            "c.score, c.match_type as matchType, c.level, " +
            "r.repo_name as repoName, r.sys_code as sysCode, r.source, r.target, r.created_at as reportCreatedAt " +
            "FROM attribution_chunk_details c " +
            "JOIN attribution_reports r ON c.report_id = r.id " +
            "<where>" +
            "  <if test='userId != null and userId != \"\"'> AND c.user_id = #{userId} </if>" +
            "  <if test='repoName != null and repoName != \"\"'> AND r.repo_name LIKE CONCAT('%', #{repoName}, '%') </if>" +
            "  <if test='sysCode != null and sysCode != \"\"'> AND r.sys_code = #{sysCode} </if>" +
            "  <if test='startDate != null and startDate != \"\"'> AND r.created_at &gt;= #{startDate} </if>" +
            "  <if test='endDate != null and endDate != \"\"'> AND r.created_at &lt;= #{endDate} </if>" +
            "</where>" +
            "ORDER BY c.id DESC" +
            "</script>")
    IPage<ChunkQueryResultDto> selectChunkWithReportPage(
            IPage<ChunkQueryResultDto> page,
            @Param("userId") String userId,
            @Param("repoName") String repoName,
            @Param("sysCode") String sysCode,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );

    @Select("<script>" +
            "SELECT SUM(c.analyzed_lines) as totalAnalyzedLines, " +
            "SUM(c.contributed_lines) as totalAiContributedLines " +
            "FROM attribution_chunk_details c " +
            "JOIN attribution_reports r ON c.report_id = r.id " +
            "<where>" +
            "  <if test='userId != null and userId != \"\"'> AND c.user_id = #{userId} </if>" +
            "  <if test='repoName != null and repoName != \"\"'> AND r.repo_name LIKE CONCAT('%', #{repoName}, '%') </if>" +
            "  <if test='sysCode != null and sysCode != \"\"'> AND r.sys_code = #{sysCode} </if>" +
            "  <if test='startDate != null and startDate != \"\"'> AND r.created_at &gt;= #{startDate} </if>" +
            "  <if test='endDate != null and endDate != \"\"'> AND r.created_at &lt;= #{endDate} </if>" +
            "</where>" +
            "</script>")
    Map<String, Object> selectChunkSummary(
            @Param("userId") String userId,
            @Param("repoName") String repoName,
            @Param("sysCode") String sysCode,
            @Param("startDate") String startDate,
            @Param("endDate") String endDate
    );
}

