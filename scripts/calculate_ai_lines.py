#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
计算 opencode_code_track 表中 function_argument 字段内 AI 生成的代码总行数。

统计规则：
1. 根据输入的 start_time 和 end_time 筛选记录。
2. 采用分页机制（默认每次 500 条数据）读取数据。
3. 解析 function_argument 字段：
   - 对于 action 为 write：计算 content 字段的非空行数。
   - 对于 action 为 edit：计算 newstring（或 new_string）字段的行数。
"""

import argparse
import difflib
import json
import logging
import os
import sys
from datetime import datetime
import pymysql

# 设置日志格式
logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s [%(levelname)s] %(message)s",
    datefmt="%Y-%m-%d %H:%M:%S"
)


def count_lines(text: str) -> int:
    """
    计算字符串文本的行数。
    如果 text 为 None 或空字符串，返回 0。
    splitlines() 能正确处理各种换行符 (\n, \r\n, \r)。
    """
    if not text or not isinstance(text, str):
        return 0
    return len(text.splitlines())


def count_effective_edit_lines(old_text: str, new_text: str, strip_whitespace: bool = False) -> int:
    """
    计算 edit 操作中 newstring 相对于 oldstring 的实际有效新增/修改行数。
    过滤掉 newstring 中与 oldstring 完全相同的行。
    
    :param old_text: 修改前的文本 (oldString)
    :param new_text: 修改后的文本 (newString)
    :param strip_whitespace: 是否忽略缩进/首尾空格过滤
    """
    if not new_text or not isinstance(new_text, str):
        return 0
    if not old_text or not isinstance(old_text, str):
        return len(new_text.splitlines())

    new_lines = new_text.splitlines()
    old_lines = old_text.splitlines()

    if strip_whitespace:
        old_set = set(line.strip() for line in old_lines if line.strip())
        effective_lines = [line for line in new_lines if line.strip() and line.strip() not in old_set]
    else:
        old_set = set(old_lines)
        effective_lines = [line for line in new_lines if line not in old_set]

    return len(effective_lines)



def get_field(obj: dict, *keys):
    """
    通用 JSON 字段提取函数，支持大小写不敏感及驼峰 (camelCase) / 下划线 (snake_case) 模糊匹配。
    例如: oldString, old_string 均可成功提取。
    """
    if not isinstance(obj, dict):
        return None
    # 1. 优先精准匹配
    for k in keys:
        if k in obj:
            return obj[k]
    # 2. 忽略大小写及下划线对比查找
    lowered_map = {str(k).lower().replace("_", ""): v for k, v in obj.items()}
    for k in keys:
        norm_k = str(k).lower().replace("_", "")
        if norm_k in lowered_map:
            return lowered_map[norm_k]
    return None


# 常见编程代码扩展名
CODE_EXTENSIONS = {
    'java', 'ts', 'js', 'vue', 'py', 'go', 'c', 'cpp', 'h', 'hpp', 'cs', 
    'php', 'rb', 'html', 'css', 'less', 'scss', 'sql', 'sh', 'json', 'xml', 
    'yml', 'yaml', 'rs', 'kt', 'swift', 'm', 'jsx', 'tsx'
}

# 明确要排除的非代码文件扩展名
EXCLUDE_EXTENSIONS = {
    'md', 'markdown', 'txt', 'log', 'pdf', 'doc', 'docx', 'xls', 'xlsx', 
    'png', 'jpg', 'jpeg', 'gif', 'svg', 'ico', 'zip', 'tar', 'gz', 'exe', 'dll'
}


def is_code_file(filename: str) -> bool:
    """判断文件名或路径是否属于代码文件"""
    if not filename or not isinstance(filename, str):

        return True  # 若无文件名，默认不排除，由后续逻辑决定

    # 取最后一个点后面的扩展名
    ext = filename.rstrip().split(".")[-1].lower() if "." in filename else ""
    
    if ext in EXCLUDE_EXTENSIONS:
        return False
    
    if ext in CODE_EXTENSIONS:
        return True

    # 如果不在显式排除列表且无常见后缀，默认当做有效文件
    return True


def parse_function_argument(arg_data, db_function_name=None):
    """
    解析 function_argument 字段，判断是 write 还是 edit 操作，并提取对应文本内容、旧文本及文件名。
    
    :param arg_data: function_argument 数据库字段内容
    :param db_function_name: 数据库表中的 function_name 字段值 (例如 'edit' 或 'write')
    返回元组: (action_type, text_content, file_path, old_content)
    """
    if not arg_data:
        return None, None, None, None

    # 如果已经是 dict 对象
    if isinstance(arg_data, dict):
        obj = arg_data
    elif isinstance(arg_data, str):
        try:
            obj = json.loads(arg_data)
            # 处理可能的二次 JSON 编码情况
            if isinstance(obj, str):
                obj = json.loads(obj)
        except (json.JSONDecodeError, TypeError):
            return None, None, None, None
    else:
        return None, None, None, None

    if not isinstance(obj, dict):
        return None, None, None, None

    # 兼容各种驼峰 (newString/oldString/filePath) 与下划线字段名
    file_path = get_field(obj, "filePath", "filepath", "file_name", "filename", "path", "file")
    old_string = get_field(obj, "oldString", "oldstring", "old_string", "oldContent", "old_content")
    new_string = get_field(obj, "newString", "newstring", "new_string", "newContent", "new_content")
    content = get_field(obj, "content")

    # 1. 优先使用数据库字段 function_name 确定操作类型
    action_type = None
    if db_function_name and isinstance(db_function_name, str):
        fn_str = db_function_name.lower().strip()
        if "edit" in fn_str:
            action_type = "edit"
        elif "write" in fn_str:
            action_type = "write"

    # 2. 如果数据库 function_name 为空，从 JSON 中查找 action / type
    if not action_type:
        action = str(get_field(obj, "action", "type", "name", "tool") or "").lower()
        if "write" in action:
            action_type = "write"
        elif "edit" in action:
            action_type = "edit"

    # 3. 隐式推导 fallback
    if not action_type:
        if new_string is not None:
            action_type = "edit"
        elif content is not None:
            action_type = "write"

    # 4. 根据决定的 action_type 返回对应的内容
    if action_type == "write":
        # 兼容部分场景中 write 将内容存放在 content 或 newString 的情况
        text_content = content if content is not None else new_string
        return "write", text_content, file_path, None
    elif action_type == "edit":
        return "edit", new_string, file_path, old_string

    return None, None, file_path, None


def calculate_ai_lines(
    host,
    port,
    user,
    password,
    database,
    start_time,
    end_time,
    user_oa=None,
    table_name="opencode_code_track",
    time_column="created_at",
    id_column="id",
    file_column="file_name",
    fn_column="function_name",
    arg_column="function_arguments",
    batch_size=500
):
    """
    按时间范围、user_oa 并在仅统计代码文件的前提下分页读取数据并计算 AI 生成代码行数。
    """
    logging.info(f"正在连接数据库 {host}:{port}/{database} ...")
    connection = pymysql.connect(
        host=host,
        port=port,
        user=user,
        password=password,
        database=database,
        charset="utf8mb4",
        cursorclass=pymysql.cursors.DictCursor
    )

    total_records = 0
    total_write_lines = 0
    total_edit_lines = 0
    total_write_count = 0
    total_edit_count = 0
    skipped_records = 0
    skipped_non_code_records = 0

    # 动态构建 WHERE 条件
    where_clauses = [f"`{time_column}` >= %s", f"`{time_column}` <= %s"]
    base_params = [start_time, end_time]

    # 1. 过滤 user_oa (支持单个或逗号分隔多个)
    oa_list = []
    if user_oa:
        if isinstance(user_oa, str):
            oa_list = [oa.strip() for oa in user_oa.split(",") if oa.strip()]
        elif isinstance(user_oa, (list, tuple)):
            oa_list = list(user_oa)

        if len(oa_list) == 1:
            where_clauses.append("`user_oa` = %s")
            base_params.append(oa_list[0])
        elif len(oa_list) > 1:
            placeholders = ", ".join(["%s"] * len(oa_list))
            where_clauses.append(f"`user_oa` IN ({placeholders})")
            base_params.extend(oa_list)

    where_sql = " AND ".join(where_clauses)

    try:
        with connection.cursor() as cursor:
            # 查询符合条件的记录总数
            count_sql = f"""
                SELECT COUNT(*) as total 
                FROM `{table_name}` 
                WHERE {where_sql}
            """
            cursor.execute(count_sql, base_params)
            total_in_db = cursor.fetchone()["total"]
            
            user_info_str = f" 用户: {user_oa} |" if user_oa else ""
            logging.info(f"在时间范围 [{start_time}] 至 [{end_time}] |{user_info_str} 共找到 {total_in_db} 条记录。")

            if total_in_db == 0:
                logging.warning("符合条件的记录数为 0。")
                return

            last_id = 0
            page = 1

            # 分页读取 (默认每页 500 条)
            while True:
                page_where_sql = f"{where_sql} AND `{id_column}` > %s"
                page_params = base_params + [last_id, batch_size]

                sql = f"""
                    SELECT `{id_column}`, `{fn_column}`, `{arg_column}`, `{time_column}`, `{file_column}`
                    FROM `{table_name}`
                    WHERE {page_where_sql}
                    ORDER BY `{id_column}` ASC
                    LIMIT %s
                """
                cursor.execute(sql, page_params)
                rows = cursor.fetchall()

                if not rows:
                    break

                page_write_lines = 0
                page_edit_lines = 0

                for row in rows:
                    last_id = row[id_column]
                    total_records += 1

                    db_file_name = row.get(file_column)
                    db_function_name = row.get(fn_column)

                    # 容错兼容 function_arguments (复数) 与 function_argument (单数)
                    arg_raw = row.get(arg_column) or row.get("function_arguments") or row.get("function_argument")
                    action_type, text_content, json_file_path, old_content = parse_function_argument(arg_raw, db_function_name)

                    # 确定最终的文件路径 (优先 DB 字段，次之 JSON 里的路径)
                    target_file = db_file_name or json_file_path

                    # 校验是否为跟代码相关的文件 (过滤 .md 等非代码文件)
                    if not is_code_file(target_file):
                        skipped_non_code_records += 1
                        continue

                    if action_type == "write":
                        lines = count_lines(text_content)
                        total_write_lines += lines
                        page_write_lines += lines
                        total_write_count += 1
                    elif action_type == "edit":
                        # 过滤 newString 和 oldString 相同的未变动行
                        lines = count_effective_edit_lines(old_content, text_content)
                        total_edit_lines += lines
                        page_edit_lines += lines
                        total_edit_count += 1
                    else:
                        skipped_records += 1

                logging.info(
                    f"已处理第 {page} 页 ({len(rows)} 条记录) | "
                    f"本页 Write 行数: {page_write_lines}, Edit 行数: {page_edit_lines}"
                )
                page += 1

    finally:
        connection.close()

    total_ai_lines = total_write_lines + total_edit_lines

    # 3. 输出汇总信息
    print("\n" + "=" * 55)
    print("           AI 生成代码行数统计结果           ")
    print("=" * 55)
    print(f"数据表名    : {table_name}")
    print(f"过滤用户 OA : {user_oa if user_oa else '未限制 (包含全部用户)'}")
    print(f"统计文件范围: 仅限代码相关文件 (已排除 .md, .txt, .log 等非代码文件)")
    print(f"统计时间范围: {start_time} ~ {end_time}")
    print(f"处理记录总数: {total_records}")
    print(f"Write 操作数 : {total_write_count} 次 | 生成代码行数: {total_write_lines}")
    print(f"Edit  操作数 : {total_edit_count} 次 | 生成代码行数: {total_edit_lines}")
    print(f"过滤非代码文件: {skipped_non_code_records} 条")
    print(f"跳过/无效记录: {skipped_records} 条")
    print("-" * 55)
    print(f"AI 生成代码总行数: {total_ai_lines} 行")
    print("=" * 55 + "\n")

    return {
        "total_records": total_records,
        "write_lines": total_write_lines,
        "edit_lines": total_edit_lines,
        "total_ai_lines": total_ai_lines,
        "write_count": total_write_count,
        "edit_count": total_edit_count,
        "skipped_non_code_records": skipped_non_code_records,
        "skipped_records": skipped_records
    }


def main():
    parser = argparse.ArgumentParser(description="计算 opencode_code_track 表中 AI 生成的行数")
    
    # 数据库连接参数（也可以从环境变量获取）
    parser.add_argument("--host", default=os.getenv("MYSQL_HOST", "localhost"), help="MySQL Host")
    parser.add_argument("--port", type=int, default=int(os.getenv("MYSQL_PORT", "3306")), help="MySQL Port")
    parser.add_argument("--user", default=os.getenv("MYSQL_USER", "root"), help="MySQL User")
    parser.add_argument("--password", default=os.getenv("MYSQL_PASSWORD", "attribution123"), help="MySQL Password")
    parser.add_argument("--database", default=os.getenv("MYSQL_DATABASE", "code_attribution"), help="Database Name")
    
    # 表名与列名
    parser.add_argument("--table", default="opencode_code_track", help="数据表名")
    parser.add_argument("--time-column", default="created_at", help="时间列名")
    parser.add_argument("--id-column", default="id", help="主键列名")
    parser.add_argument("--file-column", default="file_name", help="文件名列名")
    parser.add_argument("--fn-column", default="function_name", help="函数名列名")
    parser.add_argument("--arg-column", default="function_arguments", help="参数 JSON 所在列名 (默认 function_arguments)")
    
    # 过滤条件
    parser.add_argument("--start-time", required=True, help="起始时间，例如: '2026-07-01 00:00:00'")
    parser.add_argument("--end-time", required=True, help="结束时间，例如: '2026-07-31 23:59:59'")
    parser.add_argument("--user-oa", default=None, help="可选：过滤 user_oa，例如 'zhangsan' 或逗号分隔 'zhangsan,lisi'")
    
    # 分页参数
    parser.add_argument("--batch-size", type=int, default=500, help="每页读取条数，默认 500")

    args = parser.parse_args()

    calculate_ai_lines(
        host=args.host,
        port=args.port,
        user=args.user,
        password=args.password,
        database=args.database,
        start_time=args.start_time,
        end_time=args.end_time,
        user_oa=args.user_oa,
        table_name=args.table,
        time_column=args.time_column,
        id_column=args.id_column,
        file_column=args.file_column,
        fn_column=args.fn_column,
        arg_column=args.arg_column,
        batch_size=args.batch_size
    )

if __name__ == "__main__":
    main()


