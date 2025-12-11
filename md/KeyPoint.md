# di-brain RAG & Text2SQL 关键流程详解

本文档详细分析 di-brain 项目中 RAG 检索、知识上下文组装、LLM 调用以及 SQL 生成与执行的完整流程。

---

## 目录

1. [RAG 结果何时送入 LLM](#1-rag-结果何时送入-llm)
2. [RAG 检索的完整流程](#2-rag-检索的完整流程)
3. [检索范围详解](#3-检索范围详解)
4. [检索产出的数据形态](#4-检索产出的数据形态)
5. [SQL 生成与执行](#5-sql-生成与执行)
6. [核心代码解析](#6-核心代码解析)

---

## 1. RAG 结果何时送入 LLM

RAG 检索出来的结果**不是直接传给 LLM**，而是被拼接进 `chat_history.msg_list` 的消息文本中，然后整个消息列表作为对话上下文发送给 LLM。

### 1.1 入口函数：`retrieve_knowledge_base_as_context`

**文件位置**：`di_brain/ask_data/graph.py`

这是 RAG 上下文检索的核心函数，负责：
1. 调用 `compose_kb_context()` 从多个知识源检索并组装上下文
2. 将检索结果写入 `SystemMessage` 或 `HumanMessage`
3. 更新 `chat_history.msg_list` 供后续 LLM 调用

```python
# di_brain/ask_data/graph.py (第 47-127 行)
def retrieve_knowledge_base_as_context(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["invoke_msg_with_llm"]]:
    logger.info("Starting retrieve_knowledge_base_as_context step")
    user_query = state.get("user_query")
    new_kb_list: list[str] = []
    new_msg_list: list[str] = []

    # 1. 处理 prefill_hive_table_ 前缀的 KB，将表名追加到用户问题中
    for kb in state.get("knowledge_base_list", []):
        if kb.startswith("prefill_hive_table_"):
            table_name = kb.replace("prefill_hive_table_", "")
            user_query = f"{user_query} \n (user mentioned table [{table_name}])"

    if state.get("knowledge_base_list"):
        # 2. 过滤出新的 KB（排除历史已检索的）
        historical_kb_list = state.get("chat_history", {}).get(
            "knowledge_base_list_history", []
        )
        new_kb_list = [
            kb for kb in state.get("knowledge_base_list", [])
            if kb not in historical_kb_list
        ]
        
        # 3. 核心检索：调用 compose_kb_context 组装上下文
        retrieve_context, related_glossaries, related_rules = (
            compose_kb_context(new_kb_list, user_query) if new_kb_list else ""
        )

        if state.get("chat_history"):
            # 4a. 有历史对话：追加 HumanMessage（RAG上下文 + 用户问题）
            new_msg_list = state.get("chat_history", {}).get("msg_list", [])
            new_msg_list.append(
                HumanMessage(content=retrieve_context + "\n" + user_query)
            )
        else:
            # 4b. 无历史对话：构造 SystemMessage（角色提示 + RAG上下文 + 搜索指令）
            new_msg_list = [
                SystemMessage(
                    content=role_prompt.format(
                        now_date=datetime.now().strftime("%Y-%m-%d"),
                        user_background_info=state.get("chat_context", {}).get(
                            "user_background_info", ""
                        ),
                    )
                    + retrieve_context
                    + search_instruct_prompt
                ),
                HumanMessage(content=user_query),
            ]
    
    # 5. 返回更新后的状态，跳转到 invoke_msg_with_llm
    return Command(
        goto="invoke_msg_with_llm",
        update={
            "user_query": user_query,
            "chat_history": AskDataHistoryInfo(
                msg_list=new_msg_list,
                knowledge_base_list_history=list(set(new_kb_list)),
            ),
            "related_docs": get_related_doc_by_kb(new_kb_list),
            "related_glossaries": related_glossaries,
            "related_rules": related_rules,
        },
    )
```

### 1.2 真正调用 LLM 的位置：`invoke_msg_with_llm`

**文件位置**：`di_brain/ask_data/graph.py`

```python
# di_brain/ask_data/graph.py (第 158-193 行)
def invoke_msg_with_llm(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["llm_res_router", "generate_final_resp"]]:
    logger.info("Starting invoke_msg_with_llm step")
    conf = Configuration.from_runnable_config(config)
    
    # 检查是否超过最大调用次数
    if (state.get("now_invoke_llm_times") 
        and state.get("now_invoke_llm_times") >= conf.max_llm_invoke):
        return Command(
            goto="generate_final_resp",
            update={
                "fail_answer_reason": "ExceedMaxInvokeTimes",
                "now_llm_answer": StructOutput(
                    data=DontKnow(reason="LLM reach max invoke times")
                ),
            },
        )
    
    # 1. 获取带结构化输出的 LLM
    structured_llm = GET_STRUCTURED_LLM(conf.model).with_structured_output(
        schema=StructOutput
    )
    
    # 2. 取出包含 RAG 上下文的消息列表
    msg_list = state.get("chat_history", {}).get("msg_list", [])
    
    # 3. ★★★ 真正调用 LLM 的地方 ★★★
    now_llm_answer = structured_llm.invoke(msg_list)
    
    # 4. 将 LLM 回答追加到消息历史
    msg_list.append(AIMessage(content=str(now_llm_answer)))
    
    return Command(
        goto="llm_res_router",
        update={
            "now_llm_answer": now_llm_answer,
            "now_invoke_llm_times": (state.get("now_invoke_llm_times") or 0) + 1,
            "chat_history": chat_history,
        },
    )
```

### 1.3 二次检索：`search_related_tables`

当 LLM 返回 `SearchMoreInfoThenAnswer` 类型的响应时，会触发二次检索，补充更多表详情：

```python
# di_brain/ask_data/graph.py (第 196-260 行)
def search_related_tables(
    state: AskDataState, config: RunnableConfig
) -> Command[Literal["invoke_msg_with_llm"]]:
    logger.info("Starting search_related_tables step")
    
    # 1. 获取合并后的 manifest
    manifest = get_merged_manifest(
        state.get("knowledge_base_list"), state.get("user_query")
    )
    
    # 2. 根据 LLM 要求搜索的表名，找到相似的表
    similar_table_manifest = manifest.find_similar_tables(
        state.get("now_llm_answer").data.search_tables
    )
    
    # 3. 获取表的详细信息
    table_details = get_table_details_by_full_table_names(
        similar_table_manifest.get_full_table_names()
    )
    
    # 4. 按用户区域偏好过滤
    user_hobby_tables = filter_table_details_by_region(
        state.get("user_hobby"),
        table_details,
    )

    # 5. 拼接表详情字符串
    res = "\n\n".join([table.to_str() for table in user_hobby_tables])
    
    # 6. 将表详情作为新的 HumanMessage 追加
    msg_list = state.get("chat_history", {}).get("msg_list", [])
    msg_list.append(
        HumanMessage(content=searched_table_detail_prompt.format(table_details=res))
    )
    
    # 7. 再次跳转到 invoke_msg_with_llm，用补充后的上下文再调用 LLM
    return Command(
        goto="invoke_msg_with_llm",
        update={
            "chat_history": chat_history,
            "related_table_manifest": related_manifest,
        },
    )
```

### 1.4 多 KB 汇总场景：`summarize`

**文件位置**：`di_brain/ask_data_global/graph.py`

当请求涉及多个 knowledge_base 时，会并行检索各个 KB，然后在 `summarize` 节点汇总：

```python
# di_brain/ask_data_global/graph.py (第 190-289 行)
def summarize(state: AskDataGlobalState, config: RunnableConfig) -> AskDataGlobalOutput:
    # 1. 获取所有搜索结果
    results = state.get("market_search_results", [])
    results = [result for result in results if result.get("has_result", False)]

    # 2. 汇总所有 KB 的检索结果
    all_tables: list[TableDetail] = []
    all_docs: list[RelatedDoc] = []
    all_glossaries: list[TopicGlossaryDto] = []
    all_rules: list[TopicRuleDto] = []

    for result in results:
        table_details = result.get("related_tables", [])
        if table_details and not is_topic_kb_name(result.get("kb_name", "")):
            table_details = filter_similar_tables(table_details)
            result["related_tables"] = table_details
        all_tables.extend(table_details)
        all_docs.extend(result.get("related_docs", []))
        all_glossaries.extend(result.get("related_glossaries", []))
        all_rules.extend(result.get("related_rules", []))
    
    # 3. 构建汇总 prompt
    prompt = f"""Please analyze and summarize the search results from different markets...
    User Query: {state["user_query"]}
    Search Results:
    {chr(10).join([
        f'''
        Knowledge Base: {result.get("market_name", "Unknown")}
        Result: {result.get("result_context", "")}
        Related Tables:
        {chr(10).join([f"- {table.idc_region}.{table.schema}.{table.table_name}" 
                       for table in result.get("related_tables", [])])}
        '''
        for result in results
    ])}
    ...
    """

    # 4. 调用 LLM 生成汇总报告
    structured_llm = GET_SPECIFIC_LLM("gpt-4.1-mini", extra_config={"disable_streaming": True})
    summary = structured_llm.invoke(prompt).content
    summary = format_table_names(summary, all_tables)

    # 5. 返回最终输出
    return Command(goto=END, update=AskDataGlobalOutput(
        related_tables=all_tables,
        related_docs=all_docs,
        related_glossaries=all_glossaries,
        related_rules=all_rules,
        result_context=summary,
        shared_agent_memory=AskDataGlobalHistoryInfo(recommend_tables=all_tables),
    ))
```

---

## 2. RAG 检索的完整流程

### 2.1 流程时序图

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           RAG 检索完整流程                                    │
└─────────────────────────────────────────────────────────────────────────────┘

用户请求 (knowledge_base_list + user_query)
    │
    ▼
┌─────────────────────────────────────────┐
│  1. retrieve_knowledge_base_as_context  │
│     - 解析 knowledge_base_list          │
│     - 调用 compose_kb_context()         │
└──────────────────┬──────────────────────┘
                   │
    ┌──────────────┼──────────────┬────────────────┐
    ▼              ▼              ▼                ▼
┌────────┐  ┌────────────┐  ┌──────────┐  ┌──────────────┐
│ Mart   │  │ Table      │  │ Table    │  │ Glossary &   │
│ Doc    │  │ Manifest   │  │ Detail   │  │ Rule         │
│(MySQL) │  │ (MySQL)    │  │ (MySQL)  │  │ (KB Service) │
└────┬───┘  └─────┬──────┘  └────┬─────┘  └──────┬───────┘
     │            │               │               │
     └────────────┴───────────────┴───────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  full_context_prompt    │
              │  拼接为一段长文本        │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  写入 msg_list          │
              │  (SystemMessage /       │
              │   HumanMessage)         │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  2. invoke_msg_with_llm │
              │  structured_llm.invoke  │
              │  (msg_list)             │
              └───────────┬─────────────┘
                          │
                          ▼
              ┌─────────────────────────┐
              │  3. llm_res_router      │
              │  根据 LLM 响应类型路由   │
              └───────────┬─────────────┘
                          │
         ┌────────────────┼────────────────┐
         ▼                ▼                ▼
   DirectAnswer    SearchMoreInfo      DontKnow
   (直接回答)      (需要更多信息)       (无法回答)
         │                │                │
         ▼                ▼                ▼
  generate_final_resp  search_related_tables  generate_final_resp
         │                │                │
         │                ▼                │
         │       再次调用 LLM             │
         │       (补充表详情后)            │
         │                │                │
         └────────────────┴────────────────┘
                          │
                          ▼
                    最终输出结果
```

### 2.2 知识上下文组装：`compose_kb_context`

**文件位置**：`di_brain/ask_data/kb_context_composer.py`

这是 RAG 的核心组装函数，从四个来源聚合知识：

```python
# di_brain/ask_data/kb_context_composer.py (第 57-96 行)
def compose_kb_context(
    kb_list: List[str], user_query: str
) -> tuple[str, list[TopicGlossaryDto], list[TopicRuleDto]]:
    """Compose knowledge base context from multiple sources.

    This function combines context from three sources:
    1. Table manifests (merged manifest)
    2. Table details
    3. Documentation (mart docs)

    Args:
        kb_list: List of knowledge base names
        user_query: User's query string

    Returns:
        A concatenated string containing all context information
    """
    try:
        # 1. 获取表清单（Manifest）
        manifest_table_list = get_merged_manifest_as_context(kb_list, user_query)
        
        # 2. 获取表详情（Schema、字段、分区等）
        table_details_str_opt = get_table_details_as_context(kb_list)
        
        # 3. 获取术语表和业务规则（仅 Topic 类型 KB）
        glossary_and_rule_str, related_glossaries, related_rules = (
            get_glossary_and_rule_as_context(kb_list, user_query)
        )
        
        # 4. 获取 Mart 文档
        mart_doc_str = get_doc_as_context(kb_list)

        # 5. 使用模板拼接所有上下文
        return (
            full_context_prompt.format(
                manifest_table_list=manifest_table_list,
                table_details_str_opt=table_details_str_opt,
                glossary_and_rule_str_opt=glossary_and_rule_str,
                mart_doc_str=mart_doc_str,
            ),
            related_glossaries,
            related_rules,
        )
    except Exception as e:
        raise ValueError(f"Error composing knowledge base context: {e}")
```

### 2.3 上下文模板

```python
# di_brain/ask_data/kb_context_composer.py (第 19-40 行)
full_context_prompt = """
# Mart Doc
Here is the document can help you answer the question.

{mart_doc_str}


{glossary_and_rule_str_opt}


# Table Manifests
Here are some hive tables related to the data mart, you can search the table in the document to find the answer.

{manifest_table_list}

Note: For "Table Name" with "_{cid}" placeholder:
- If "Table Name" doesn't contain "_{cid}" placeholder, keep it unchanged.
- If "Table Name" contains "_{cid}" placeholder and user doesn't specify a region in the question, keep "_{cid}" unchanged.
- If "Table Name" contains "_{cid}" placeholder and user specifies a region in the question, replace "_{cid}" with the country code (e.g., _sg for Singapore, _br for Brazil).

{table_details_str_opt}
"""
```

---

## 3. 检索范围详解

### 3.1 MySQL 数据库：`shopee_di_rag_db`

#### 3.1.1 核心表：`knowledge_base_details_v1_5_0`

**配置位置**：`di_brain/config/default_config_json.py`

```python
"mysql_config": {
    "host": "master.e821f28ca694983e.mysql.cloud.test.shopee.io",
    "port": 6606,
    "user": "sg_di_test",
    "password": "WxLTBRO_M9rAzsL8dxHq",
    "database": "shopee_di_rag_db"
},
"ask_data": {
    "table_name": "knowledge_base_details_v1_5_0"
}
```

**数据模型**：`di_brain/ask_data/database/model.py`

```python
@dataclass
class KnowledgeBaseDetail:
    """Class representing a record from the knowledge_base_details table."""

    # Document types 文档类型常量
    TYPE_DATAMAP_TABLE_MANIFEST: ClassVar[str] = "datamap_table_manifest"   # 表清单
    TYPE_DATAMAP_TABLE_DETAIL: ClassVar[str] = "datamap_table_detail"       # 表详情(JSON)
    TYPE_DATAMAP: ClassVar[str] = "datamap"
    TYPE_CONFLUENCE: ClassVar[str] = "confluence"                            # Confluence 文档
    TYPE_GOOGLE_DOC: ClassVar[str] = "google_doc"                           # Google Doc 文档
    TYPE_DATAMART_DESC_DOC: ClassVar[str] = "datamart_desc_doc"             # Mart 描述文档
    TYPE_DATA_GROUP_DOC_SUMMARY: ClassVar[str] = "doc_summary"              # 文档摘要
    TYPE_DATAMART_SUMMARY: ClassVar[str] = "datamart_desc_doc_summary"      # Mart 摘要

    # Fields matching database columns 数据库字段
    id: Optional[int] = None
    knowledge_base_name: Optional[str] = None    # KB 名称，如 "prefill_hive_table_SG.db.table"
    source_url: str = ""                          # 原文链接
    document_type: str = ""                       # 文档类型
    title: str = ""                               # 文档标题
    index_info: str = ""                          # 索引信息
    text_content: Optional[str] = None            # ★ 核心内容字段（可能是纯文本或 JSON）
    created_at: Optional[datetime] = None
    updated_at: Optional[datetime] = None
```

**各文档类型的用途**：

| document_type | 用途 | text_content 格式 |
|---------------|------|-------------------|
| `datamap_table_manifest` | 表清单，列出可用表及简要说明 | 纯文本 |
| `datamap_table_detail` | 表详情（字段、分区、示例数据） | JSON |
| `datamart_desc_doc` | Mart 业务文档说明 | 纯文本 |
| `confluence` | Confluence 落库的文档 | 纯文本 |
| `google_doc` | Google Doc 落库的文档 | 纯文本 |

#### 3.1.2 辅助表：`mart_top_sql_tab`

存储各表的高频 SQL 示例，供 LLM 参考：

```python
"generate_sql": {
    "table_name": "mart_top_sql_tab"
}
```

### 3.2 Milvus 向量数据库

**配置位置**：`di_brain/hive_query.py`

#### 3.2.1 表级向量库：`di_rag_hive_table_with_ai_desc_v2`

```python
# di_brain/hive_query.py (第 135-158 行)
def get_table_retriever() -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_COLLECTION_NAME", "di_rag_hive_table_with_ai_desc_v2"
        ),
        embedding_function=get_embeddings_model(),
        vector_field="table_vector",      # 向量字段
        primary_field="uid",              # 主键：idc_region.schema.table_name
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": RETRIEVE_LIMIT,          # 100
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,
        },
    )
```

**Collection 字段**：
- `uid`：主键，格式 `idc_region.schema.table_name`
- `schema`：库名
- `table_group_name`：表分组
- `business_domain`：业务域
- `data_marts`：所属 Mart
- `description` / `ai_desc`：表描述（人工 + AI 生成）
- `table_vector`：向量字段（用于语义检索）

#### 3.2.2 表+列向量库：`di_rag_hive_table_with_columns_and_ai_desc_v2`

```python
# di_brain/hive_query.py (第 108-132 行)
def get_table_with_column_retriever() -> BaseRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_TABLE_SCHEMA_COLLECTION_NAME",
            "di_rag_hive_table_with_columns_and_ai_desc_v2",
        ),
        embedding_function=get_embeddings_model(),
        vector_field="table_vector",
        primary_field="uid",
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": RETRIEVE_LIMIT,
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
            "score_threshold": 600,
        },
    )
```

#### 3.2.3 列级向量库：`di_rag_hive_column_info_v2`

```python
# di_brain/hive_query.py (第 161-183 行)
def get_hive_column_retriever(filter: str) -> MilvusWithSimilarityRetriever:
    vs = MilvusWithQuery(
        connection_args=milvus_config,
        collection_name=os.environ.get(
            "MILVUS_COLUMN_COLLECTION_NAME", "di_rag_hive_column_info_v2"
        ),
        embedding_function=get_embeddings_model(),
        vector_field="column_vector",
        primary_field="id",
    )
    return MilvusWithSimilarityRetriever(
        vectorstore=vs,
        search_kwargs={
            "k": 200,
            "expr": filter,               # 可按 table_uid 过滤
            "param": {
                "metric_type": "L2",
                "params": {"nprobe": 1200, "reorder_k": 200},
            },
        },
    )
```

**Collection 字段**：
- `id`：列 ID
- `table_uid`：所属表的 uid
- `column_name`：列名
- `partition`：是否分区列
- `column_vector`：向量字段

### 3.3 Elasticsearch

#### 3.3.1 Index：`di-rag-hive-description`

```python
# di_brain/hive_query.py (第 93-105 行)
def get_es_table_retriever() -> BaseRetriever:
    bm25_retriever = ElasticsearchAdvanceRetriever.from_es_params(
        index_name="di-rag-hive-description",
        body_func=bm25_query,
        url=os.environ.get(
            "ES_HOST", "http://portal-regdi-es-717-general-test.data-infra.shopee.io:80"
        ),
        username="elastic",
        password="KgpcZdQkIhMI",
        document_mapper=es_hint_to_doc_mapper,
    )
    return bm25_retriever
```

**BM25 查询构建**：

```python
# di_brain/hive_query.py (第 50-85 行)
def bm25_query(search_query: str, metadata: Dict) -> Dict:
    search_body = {
        "query": {
            "bool": {
                "must": {"match": {"text": {"query": search_query, "fuzziness": "0"}}}
            }
        },
        "size": RETRIEVE_LIMIT,  # 100
    }

    # 支持按 data_marts / table_schemas 过滤
    filter_condition = metadata.get("retrieve_filters")
    if filter_condition:
        mart_cond = filter_condition.get("data_marts")
        schema_cond = filter_condition.get("table_schemas")
        
        if mart_cond:
            mart_cond_dict = gen_should_condition("data_marts", mart_cond)
        if schema_cond:
            schema_cond_dict = gen_should_condition("schema", schema_cond)
        
        if final_cond_dict:
            search_body["query"]["bool"]["filter"] = final_cond_dict

    return search_body
```

**ES 结果映射**：

```python
# di_brain/hive_query.py (第 38-47 行)
def es_hint_to_doc_mapper(hit: Mapping[str, Any]) -> Document:
    content = hit["_source"].pop("text")          # 文本内容
    other_properties = hit["_source"]             # 其它字段作为 metadata
    other_properties["_score"] = hit["_score"]
    other_properties["uid"] = "%s.%s.%s" % (
        other_properties["idc_region"],
        other_properties["schema"],
        other_properties["table_name"],
    )
    return Document(page_content=content, metadata=other_properties)
```

### 3.4 检索范围总结表

| 存储类型 | 位置 | 粒度 | 主要内容 | RAG 用途 |
|---------|------|------|----------|----------|
| MySQL | `shopee_di_rag_db.knowledge_base_details_v1_5_0` | KB 记录 | 表清单、表详情、Mart 文档 | 结构化 KB 上下文 |
| MySQL | `shopee_di_rag_db.mart_top_sql_tab` | 表级 | 高频 SQL 示例 | SQL 示例参考 |
| Milvus | `di_rag_hive_table_with_ai_desc_v2` | 表级 | 表描述向量 | 语义检索表 |
| Milvus | `di_rag_hive_table_with_columns_and_ai_desc_v2` | 表级(带列) | 表+列综合向量 | Schema 上下文 |
| Milvus | `di_rag_hive_column_info_v2` | 列级 | 列描述向量 | 字段检索 |
| ES | `di-rag-hive-description` | 表级 | 表描述文本 | BM25 文本检索 |

---

## 4. 检索产出的数据形态

### 4.1 表清单（Manifest）

```
# Table Manifests
Here are some hive tables related to the data mart:

| Table Name | Description | Business Domain |
|------------|-------------|-----------------|
| SG.dwd.order_fact | 订单事实表 | Order |
| SG.dim.user_dim | 用户维度表 | User |
```

### 4.2 表详情（TableDetail）

JSON 格式，包含：
- `table_name`：表名
- `schema`：库名
- `idc_region`：区域
- `table_desc`：表描述
- `columns`：字段列表（字段名、类型、是否分区、描述）
- `sample_data`：示例数据
- `integrity_score`：完整性评分

### 4.3 Mart 文档

```
doc name: Order Mart Introduction
doc content: Order Mart 包含所有订单相关的事实表和维度表...
```

### 4.4 Glossary & Rule（仅 Topic KB）

```python
# di_brain/ask_data/kb_context_composer.py (第 147-186 行)
def get_glossary_and_rule_as_context(
    kb_list: List[str], user_query: str
) -> tuple[str, list[TopicGlossaryDto], list[TopicRuleDto]]:
    if kb_list and len(kb_list) > 0:
        if is_topic_kb_name(kb_list[0]):
            topic_id = from_kb_name_to_topic_id(kb_list[0])
            glossaries = kb_topic_client.get_topic_glossaries(int(topic_id), user_query)
            rules = kb_topic_client.get_topic_rules(int(topic_id), user_query)

            glossary_str = ""
            if glossaries:
                for glossary in glossaries:
                    synonym_str = (
                        f" (synonym: {glossary.synonym})" if glossary.synonym else ""
                    )
                    glossary_str += f"Glossary: {glossary.glossary_name}{synonym_str} - {glossary.desc}\n"

            rule_str = ""
            if rules:
                for rule in rules:
                    rule_str += f"Rule: {rule.rule_desc}\n"

            return (
                glossary_and_rule_prompt.format(
                    glossary_str=glossary_str, rule_str=rule_str
                ),
                glossaries,
                rules,
            )

    return "", [], []
```

---

## 5. SQL 生成与执行

### 5.1 SQL 生成流程

**核心文件**：`di_brain/text2sql/text2sql_step.py`

SQL 生成使用 Compass 模型（专门针对 SQL 场景优化的内部模型），支持多参数并行尝试：

```python
# di_brain/text2sql/text2sql_step.py (第 72-73 行)
llm = GET_SPECIFIC_LLM("gemini-2.5-flash", extra_config={"disable_streaming": True})
llm_compass = GET_SPECIFIC_LLM("codecompass-sql")

# SQL 生成的多组参数配置（用于并行尝试）
SQL_GENERATION_COMPASS_CONFIGS = [
    {"temperature": 0.9, "topP": 0.8, "topK": 20, "repetitionPenalty": 1},
    {"temperature": 0.65, "topP": 0.84, "topK": 8, "repetitionPenalty": 1},
    {"temperature": 0.95, "topP": 0.78, "topK": 20, "repetitionPenalty": 1.02},
    {"temperature": 0.3, "topP": 0.82, "topK": 10, "repetitionPenalty": 1},
]
```

**生成节点**：`generate_sql_compass`

```python
# di_brain/text2sql/text2sql_step.py (第 1044-1095 行)
def generate_sql_compass(state: Text2SQLAskHumanState, config: RunnableConfig) -> dict:
    """Node for SQL generation with optimized parallel execution"""
    
    iteration_configs = SQL_GENERATION_COMPASS_CONFIGS

    # Step 1: 先用默认参数尝试
    logger.info("Starting SQL generation with default parameters...")
    default_result = call_compass_with_params(
        state, None, "sql_compass", True, config=config
    )

    # 如果默认 SQL 有效，直接返回
    if default_result["sql_validated"]:
        logger.info("Default SQL is valid, returning immediately")
        return {
            "sql": default_result["sql"],
            "llm_output": default_result["llm_output"],
            "sql_validated": default_result["sql_validated"],
            "error": default_result["error"],
        }

    # Step 2: 默认无效，并行尝试其他参数
    logger.info("Default SQL is invalid, submitting alternative parameters in parallel...")
    
    with ThreadPoolExecutor(max_workers=len(iteration_configs)) as executor:
        future_to_params = {
            executor.submit(
                call_compass_with_params,
                state, params, "sql_compass", True, config=config,
            ): params
            for params in iteration_configs
        }

        for future in as_completed(future_to_params):
            result = future.result()
            if result["sql_validated"]:
                # 找到有效 SQL，取消其他任务并返回
                for remaining_future in future_to_params:
                    if not remaining_future.done():
                        remaining_future.cancel()
                return result
```

### 5.2 SQL 执行

**核心文件**：`di_brain/chat_bi/starrocks_client.py`

SQL 执行由 `StarRocksClient` 负责，支持 StarRocks 和 MySQL 协议：

```python
# di_brain/chat_bi/starrocks_client.py (第 108-141 行)
def execute_sql_mysql(
    self,
    sql: str,
    catalog: str = None,
    fetch_result: bool = True,
    idc_region: str = "SG",
) -> Optional[List[Tuple]]:
    """
    通过 MySQL 协议执行 SQL 语句
    
    ✅ 支持所有 SQL 语句: CREATE, DROP, ALTER, SELECT, INSERT 等
    """
    if catalog is None:
        catalog = self.catalog

    try:
        with self.get_mysql_connection(idc_region) as conn:
            with conn.cursor() as cursor:
                cursor.execute(f"SET CATALOG {catalog};")
                cursor.execute("set sql_dialect = 'trino';")
                cursor.execute(sql)
                if fetch_result:
                    return cursor.fetchall()
                return None
    except Exception as e:
        logger.info(f"execute error: {e}")
        raise
```

### 5.3 执行触发条件

SQL 执行不是自动触发的，需要满足以下条件：

```python
# di_brain/router/tool_router.py (第 418-429 行)
final_intent: Annotated[
    Literal[
        "data_discovery",
        "generate_sql",
        "fix_sql",
        "execute_sql_and_analyze_result",   # ★ 只有这个意图才会执行
        "search_log",
    ],
    "The final, high-level intent or goal of the user's question. "
    "If the user's problem involves data search or analysis, the ultimate goal must be to Execute SQL and Analyze Results.",
]
```

---

## 6. 核心代码解析

### 6.1 LangGraph 状态流转

**ask_data 图结构**：

```
START
  │
  ▼
retrieve_knowledge_base_as_context  ──→  invoke_msg_with_llm
                                              │
                                              ▼
                                        llm_res_router
                                              │
                      ┌───────────────────────┼───────────────────────┐
                      ▼                       ▼                       ▼
              DirectAnswer           SearchMoreInfoThenAnswer     DontKnow
                      │                       │                       │
                      ▼                       ▼                       ▼
             generate_final_resp     search_related_tables    generate_final_resp
                      │                       │                       │
                      │                       ▼                       │
                      │              invoke_msg_with_llm              │
                      │                       │                       │
                      └───────────────────────┴───────────────────────┘
                                              │
                                              ▼
                                             END
```

### 6.2 LLM 结构化输出

```python
# di_brain/ask_data/state.py (第 54-80 行)
class DirectAnswer(BaseModel):
    answer: str = Field(..., description="The answer to the question")
    related_tables: list[str] = Field(
        ..., description="The tables related to the question"
    )

class SearchMoreInfoThenAnswer(BaseModel):
    search_tables: list[str] = Field(
        ..., description="The tables to search for more information"
    )

class DontKnow(BaseModel):
    reason: str = Field(..., description="The reason why the answer is not found")

class StructOutput(BaseModel):
    data: Union[DirectAnswer, SearchMoreInfoThenAnswer, DontKnow] = Field(
        ...,
        description="""
    1. if you can find the answer in the document, just answer, the data should be DirectAnswer type
    2. if you can't find the answer in the document, the data should be SearchMoreInfoThenAnswer type
    3. if the question is not related to the document, the data should be DontKnow type
    """,
    )
```

### 6.3 关键常量

```python
# di_brain/hive_query.py
RETRIEVE_LIMIT = 100                    # 单次检索最多返回 100 条
FINAL_RETURN_LIMIT = 10                 # 最终返回 10 条
TABLE_COLUMN_ORIGIN_RETRIVE_LIMIT = 22  # 列信息检索 22 条

# Milvus 向量搜索参数
"search_kwargs": {
    "k": RETRIEVE_LIMIT,
    "param": {
        "metric_type": "L2",
        "params": {"nprobe": 1200, "reorder_k": 200},
    },
    "score_threshold": 600
}
```

---

## 总结

1. **RAG 检索**：通过 `compose_kb_context` 从 MySQL KB 表、Milvus 向量库、ES 全文索引聚合知识
2. **上下文注入**：检索结果被拼接进 `SystemMessage/HumanMessage`，写入 `chat_history.msg_list`
3. **LLM 调用**：`invoke_msg_with_llm` 将整个 `msg_list` 传给 LLM，LLM 返回结构化响应
4. **迭代检索**：若 LLM 需要更多信息，触发 `search_related_tables` 补充表详情后再次调用
5. **SQL 生成**：使用 Compass 模型，支持多参数并行尝试，确保生成有效 SQL
6. **SQL 执行**：由 `StarRocksClient` 执行，需用户显式意图 `execute_sql_and_analyze_result` 才触发
