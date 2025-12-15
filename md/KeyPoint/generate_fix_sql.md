# DI-Brain Generate SQL & Fix SQL 详解

## 一、核心概念

### 1. Generate SQL 的整体目标

**Generate SQL** 是将自然语言查询转换为结构化 SQL 语句的核心功能。它需要：

- 理解用户意图（What data? When? Which filters?）
- 识别相关的表和列
- 构建语义正确的 SQL 查询
- 处理边界条件（Placeholder 检测、验证、修复）

### 2. Fix SQL 的目标

**Fix SQL** 用于纠正执行失败或结果不符的 SQL 查询。它包括：

- 错误分析（语法错误 vs 逻辑错误 vs 权限错误）
- 错误修复
- 验证修复结果
- Placeholder 处理

---

## 二、Generate SQL 完整流程

### 2.1 高层架构流程图

```
用户自然语言查询
  ↓
[1. 预处理 & 上下文分析]
  ├─ 提取表、列、过滤条件等关键信息
  ├─ 判断是否需要人工确认（ask_human）
  └─ 决定是否继续还是结束
  ↓
[2. 双路并行 SQL 生成]（如果需要）
  ├─ Path A: Plan-and-Reflect 方法
  │   ├─ Step A1: 生成详细计划（Planner）
  │   └─ Step A2: 基于计划生成 SQL（Reflect）
  │
  └─ Path B: Divide-and-Conquer 方法
      ├─ Step B1: 分解用户查询为子问题
      ├─ Step B2: 逐个回答子问题（Conquer）
      └─ Step B3: 组装最终 SQL
  ↓
[3. 选择最优 SQL]
  ├─ 比较 A 和 B 的两条 SQL
  ├─ LLM 选择更好的一条
  └─ 如果只有一条可用，使用该条
  ↓
[4. Placeholder 检测 & 验证]
  ├─ 检测是否包含 [PLACEHOLDER]
  ├─ 如果有 → ask_human
  └─ SQL 语法校验
  ↓
[5. 修复（可选）]
  ├─ 如果 SQL 不合法 → Fix SQL
  └─ 最多重试 2 次
  ↓
最终输出 SQL
```

### 2.2 两种生成策略详解

项目中使用**并行双路生成策略**，同时执行两种不同的 SQL 生成方法，然后由 LLM 选择最优结果。

#### **并行执行架构图**

```
                     ┌─────────────────────────────────────┐
                     │       preprocess_and_analyze_context │
                     │   (预处理：状态序列化、方言提取、上下文组装)│
                     └─────────────────────────────────────┘
                                      │
                                      ▼
                     ┌─────────────────────────────────────┐
                     │         continue_generate_sql        │
                     │        (准备开始 SQL 生成)            │
                     └─────────────────────────────────────┘
                                      │
                    ┌─────────────────┴─────────────────┐
                    │                                    │
                    ▼                                    ▼
    ┌───────────────────────────┐      ┌───────────────────────────┐
    │ generate_sql_plan_and_reflect │      │ generate_sql_divide_and_conquer│
    │        (策略 A)              │      │        (策略 B)              │
    │                             │      │                             │
    │  ┌─────────────────────┐   │      │  ┌─────────────────────┐   │
    │  │ Step 1: Planner     │   │      │  │ Step 1: Divide      │   │
    │  │   生成详细计划        │   │      │  │   分解为子问题        │   │
    │  └─────────┬───────────┘   │      │  └─────────┬───────────┘   │
    │            │               │      │            │               │
    │            ▼               │      │            ▼               │
    │  ┌─────────────────────┐   │      │  ┌─────────────────────┐   │
    │  │ Step 2: SQL         │   │      │  │ Step 2: Conquer     │   │
    │  │   基于计划生成SQL     │   │      │  │   逐个解答子问题      │   │
    │  └─────────────────────┘   │      │  └─────────┬───────────┘   │
    │                             │      │            │               │
    │                             │      │            ▼               │
    │                             │      │  ┌─────────────────────┐   │
    │                             │      │  │ Step 3: Assemble    │   │
    │                             │      │  │   组装最终 SQL        │   │
    │                             │      │  └─────────────────────┘   │
    └───────────────────────────┘      └───────────────────────────┘
                    │                                    │
                    └─────────────────┬─────────────────┘
                                      │
                                      ▼
                     ┌─────────────────────────────────────┐
                     │  choose_better_sql_and_analyze_placeholder │
                     │   (LLM 选择最优 SQL + Placeholder 检测)     │
                     └─────────────────────────────────────┘
```

#### **图的定义代码**（`text2sql_ask_human_graph.py`）

```python
def build_text2sql_ask_human_graph() -> StateGraph:
    workflow = StateGraph(Text2SQLAskHumanState, ...)
    
    # 添加节点
    workflow.add_node("preprocess_and_analyze_context", preprocess_and_analyze_context)
    workflow.add_node("generate_sql_plan_and_reflect", generate_sql_plan_and_reflect)
    workflow.add_node("generate_sql_divide_and_conquer", generate_sql_divide_and_conquer)
    workflow.add_node("choose_better_sql_and_analyze_placeholder", choose_better_sql_and_analyze_placeholder)
    workflow.add_node("fix_and_validate_sql", fix_and_validate_sql)
    workflow.add_node("generate_output", generate_output)
    
    # 关键：并行执行两种策略
    workflow.add_edge("continue_generate_sql", "generate_sql_plan_and_reflect")
    workflow.add_edge("continue_generate_sql", "generate_sql_divide_and_conquer")
    
    # 两种策略都汇聚到选择节点
    workflow.add_edge("generate_sql_plan_and_reflect", "choose_better_sql_and_analyze_placeholder")
    workflow.add_edge("generate_sql_divide_and_conquer", "choose_better_sql_and_analyze_placeholder")
    
    return workflow.compile()
```

---

#### **Strategy A: Plan-and-Reflect（计划与反思）详解**

**核心思路**：先规划，再执行（两步走）

##### **Step 1: 生成详细计划（Planner）**

```python
# 代码位置：di_brain/text2sql/text2sql_step.py
def generate_sql_plan_and_reflect(state: Text2SQLAskHumanState):
    # Step 1: 调用 Planner 生成计划
    planner = synthesizers["planner"].invoke(state)
    # ...
```

**Planner Prompt 核心指令**（`SQL_RESPONSE_GENERATESQL_PLANNER`）：

```
You are a highly skilled {dialect} SQL planner. Your task is to generate a detailed plan 
for constructing a syntactically correct {dialect} SQL query.

Follow these guidelines strictly:
1. 识别上下文中明确提及的相关表和列
2. 如果上下文包含超过 3 个表，选择不超过 3 个最相关的表
3. 为每个选中的表指定用途（过滤、连接、聚合）
4. 如果表包含分区列（partition: true），确保在 WHERE 子句中使用
5. 如果日期列没有指定时间条件，默认过滤"昨天"（T-1）
6. 指定表之间的 Join 条件
7. 定义过滤条件（WHERE 子句）
8. 指定聚合逻辑（GROUP BY、SUM、COUNT）
9. 包含排序要求（ORDER BY）
10. 如果有不确定的值，使用 [PLACEHOLDER] 标记
```

**Planner 输出示例**：
```
1. 选择表：mp_order.dws_order_gmv_1d
   - 用途：聚合订单金额
   
2. 分区列处理：
   - grass_date 是分区列，必须在 WHERE 中使用
   - 日期格式参考 sample_data: "2024-12-14"
   
3. 过滤条件：
   - WHERE grass_date = date_add('day', -1, current_date)
   - WHERE region = 'SG'
   
4. 聚合逻辑：
   - SELECT SUM(gmv) AS total_gmv
   - GROUP BY region
   
5. 排序：
   - ORDER BY total_gmv DESC
```

##### **Step 2: 基于计划生成 SQL**

```python
def generate_sql_plan_and_reflect(state: Text2SQLAskHumanState):
    planner = synthesizers["planner"].invoke(state)
    
    # Step 2: 基于计划生成 SQL
    llm_output = synthesizers["sql"].invoke({**state, "planner": planner})
    sql = extract_generated_sql(llm_output)
    
    return {
        "sql_results": [
            {
                "method": "plan_and_reflect",
                "sql": sql,
                "llm_output": llm_output,
            }
        ]
    }
```

**SQL 生成 Prompt 核心指令**（`SQL_RESPONSE_GENERATESQL_WITH_EXPLAIN`）：

```
You are a highly skilled {dialect} SQL expert. Your task is to generate a syntactically 
correct {dialect} SQL query based on the input question and the provided context.

关键输入：
- Planner: {planner}  ← 上一步生成的计划
- Context: {context}  ← 表元数据
- Sample SQL: {sample_sql}
- Sample Data: {sample_data}

特殊规则：
- 使用详细的 Planner 来构建 SQL
- 如果你认为计划不完全正确，可以反思并按自己的想法生成 SQL（Reflect 能力）
- 不确定的值使用 [PLACEHOLDER] 标记
```

**Strategy A 总结**：

| 步骤 | LLM 调用 | 输入 | 输出 |
|------|---------|------|------|
| **Planner** | 1 次 | context, sample_sql, sample_data, question | 详细的 SQL 构建计划 |
| **SQL Generation** | 1 次 | planner + context + question | 最终 SQL + 解释 |
| **总计** | 2 次 | - | - |

**优点**：
- 具有明确的思维链（CoT）结构
- 可以显示 LLM 的推理过程
- 对复杂查询更稳定
- **Reflect 能力**：LLM 可以反思计划的正确性并自行调整

**缺点**：
- 需要两次 LLM 调用
- Token 消耗较多

**适用场景**：
- 复杂的多表 Join
- 涉及多个过滤条件
- 需要聚合操作的查询

---

#### **Strategy B: Divide-and-Conquer（分而治之）详解**

**核心思路**：化整为零，逐个解决（三步走）

##### **Step 1: 分解问题（Divide）**

```python
# 代码位置：di_brain/text2sql/text2sql_step.py
def generate_sql_divide_and_conquer(state: Text2SQLAskHumanState):
    # Step 1: 分解问题为子问题
    sub_questions = synthesizers["question_divide"].invoke(state)
    # ...
```

**Divide Prompt 核心指令**（`SQL_RESPONSE_DIVIDE_PROMPT`）：

```
You are a highly skilled {dialect} SQL decomposition expert. 
Given the user question and table metadata, break the question into smaller, 
manageable sub-questions.

输出格式：
1. Sub-question 1
   Explanation: 为什么分解出这个子问题
2. Sub-question 2
   Explanation: ...
```

**Divide 输出示例**：

```
原始问题："查询每个区域最近 7 天的销售总额和订单数量"

分解结果：
1. Sub-question 1: "哪些表包含区域维度和销售额数据？"
   Explanation: 需要确定数据来源

2. Sub-question 2: "如何计算最近 7 天的日期范围？"
   Explanation: 需要确定时间过滤条件的写法

3. Sub-question 3: "如何按区域进行聚合？"
   Explanation: 需要确定 GROUP BY 的字段

4. Sub-question 4: "订单数量应该用哪个字段计算？"
   Explanation: COUNT(*) 还是 SUM(order_cnt)
```

##### **Step 2: 逐个解答子问题（Conquer）**

```python
def generate_sql_divide_and_conquer(state: Text2SQLAskHumanState):
    sub_questions = synthesizers["question_divide"].invoke(state)
    
    # Step 2: 逐个解答子问题
    sub_questions_results = synthesizers["question_conquer"].invoke(
        {
            **state,
            "sub_questions": sub_questions,
        }
    )
    # ...
```

**Conquer Prompt 核心指令**（`SQL_RESPONSE_CONQUER_PROMPT`）：

```
You are a highly skilled {dialect} SQL expert. Your task is to generate 
analysis and pseudo SQL for each input question based on the provided context.

输入：
- Sub-questions: {sub_questions}
- Context: {context}

输出格式：
1. Sub-question 1
   Analysis: 详细分析
   Pseudo SQL: SELECT ... FROM ... WHERE ...

2. Sub-question 2
   Analysis: ...
   Pseudo SQL: ...
```

**Conquer 输出示例**：

```
1. Sub-question 1: "哪些表包含区域维度和销售额数据？"
   Analysis: 根据上下文，mp_order.dws_order_gmv_1d 表包含 region 和 gmv 列
   Pseudo SQL: 
     SELECT region, gmv FROM mp_order.dws_order_gmv_1d

2. Sub-question 2: "如何计算最近 7 天的日期范围？"
   Analysis: grass_date 是分区列，格式为 YYYY-MM-DD
   Pseudo SQL: 
     WHERE grass_date >= date_add('day', -7, current_date)

3. Sub-question 3: "如何按区域进行聚合？"
   Analysis: 使用 GROUP BY region
   Pseudo SQL: 
     SELECT region, SUM(gmv) GROUP BY region

4. Sub-question 4: "订单数量应该用哪个字段计算？"
   Analysis: 表中有 order_cnt 列
   Pseudo SQL: 
     SELECT SUM(order_cnt) AS total_orders
```

##### **Step 3: 组装最终 SQL（Assemble）**

```python
def generate_sql_divide_and_conquer(state: Text2SQLAskHumanState):
    sub_questions = synthesizers["question_divide"].invoke(state)
    sub_questions_results = synthesizers["question_conquer"].invoke({...})
    
    # Step 3: 组装最终 SQL
    llm_output = synthesizers["sql_assemble"].invoke(
        {
            **state,
            "sub_questions": sub_questions,
            "sub_questions_results": sub_questions_results,
        }
    )
    sql = extract_generated_sql(llm_output)
    
    return {
        "sql_results": [
            {
                "method": "divide_conquer",
                "sql": sql,
                "llm_output": llm_output,
            }
        ]
    }
```

**Assemble Prompt 核心指令**（`SQL_RESPONSE_ASSEMBLE_PROMPT`）：

```
You are a highly skilled {dialect} SQL expert. Your task is to combine 
the following partial queries from sub-questions results into one final 
simplified, optimized SQL query that answers the user original question.

输入：
- Partial SQL Queries: {sub_questions_results}
- Context: {context}

关键规则：
1. 只使用上下文中明确提到的列和表
2. 如果有超过 3 个表，选择最多 3 个最相关的
3. 分区列必须在 WHERE 子句中使用
4. 如果日期列没有指定条件，默认过滤"昨天"
5. 遵循 {dialect} 语法规则
```

**Assemble 输出示例**：

```sql
SELECT 
    region,
    SUM(gmv) AS total_gmv,
    SUM(order_cnt) AS total_orders
FROM mp_order.dws_order_gmv_1d
WHERE grass_date >= date_add('day', -7, current_date)
  AND grass_date < current_date
GROUP BY region
ORDER BY total_gmv DESC
```

**Strategy B 总结**：

| 步骤 | LLM 调用 | 输入 | 输出 |
|------|---------|------|------|
| **Divide** | 1 次 | context, question | 子问题列表 |
| **Conquer** | 1 次 | sub_questions, context | 每个子问题的分析 + Pseudo SQL |
| **Assemble** | 1 次 | sub_questions_results, context | 组装后的最终 SQL |
| **总计** | 3 次 | - | - |

**优点**：
- 将复杂问题分解为简单问题
- 每个子问题更容易准确回答
- 对多层次查询友好
- 显式的问题分解过程，便于调试

**缺点**：
- 三次 LLM 调用（分解 + 回答 + 组装）
- Token 消耗最多
- 子问题分解本身可能不精确

**适用场景**：
- 非常复杂的业务逻辑
- 多层次的聚合和分组
- 涉及众多表的关联查询

---

#### **两种策略对比表**

| 特性 | Plan-and-Reflect | Divide-and-Conquer |
|------|------------------|-------------------|
| **LLM 调用次数** | 2 次 | 3 次 |
| **Token 消耗** | 中等 | 最多 |
| **思维模式** | 整体规划 → 执行 | 分解 → 解答 → 组装 |
| **优势** | 稳定、CoT 清晰 | 复杂问题分解能力强 |
| **劣势** | 对极复杂问题可能不够 | 分解质量依赖 LLM |
| **Reflect 能力** | ✅ 有（可反思计划） | ❌ 无 |
| **调试友好度** | 中 | 高（每步输出可见） |

---

### 2.3 两条 SQL 的选择机制

#### **选择流程**

```python
def choose_better_sql(state):
    """选择两条 SQL 中的最优一条"""
    
    # 1. 提取两条结果
    plan_reflect_result = next(
        r for r in sql_results 
        if r["method"] == "plan_and_reflect"
    )
    
    divide_conquer_result = next(
        r for r in sql_results 
        if r["method"] == "divide_conquer"
    )
    
    # 2. 如果其中一个缺失，使用另一个（降级策略）
    if not plan_reflect_result or not divide_conquer_result:
        return plan_reflect_result["sql"] if plan_reflect_result else ""
    
    # 3. 调用 LLM 进行比较
    result = LLM.invoke(SQL_RESPONSE_CHOOSE_BETTER_SQL, {
        "sql_plan_reflect": plan_reflect_result["sql"],
        "sql_divide_conquer": divide_conquer_result["sql"],
        "context": context,
        "question": question,
    })
    
    # 4. 解析选择结果
    choice = parse_choice(result)  # 返回 'A' 或 'B'
    
    if choice == 'A':
        return plan_reflect_result["sql"]
    else:
        return divide_conquer_result["sql"]
```

#### **选择标准（LLM Prompt）**

```
You are a highly skilled SQL expert. Your task is to choose the better SQL query 
from the two candidate queries.

The better SQL query can be thought as:
- Answering the user question better
- More accurate
- No or less errors

Instruction:
1. Given the table context info and user question, analyze differences
2. Based on the original question and provided database info, choose the better one

Output format: <choice>A</choice> or <choice>B</choice>
```

#### **选择的关键因素**

| 因素 | 描述 | 权重 |
|------|------|------|
| **语法正确性** | SQL 是否能通过语法校验 | ⭐⭐⭐⭐⭐ |
| **逻辑准确性** | SQL 是否正确回答用户问题 | ⭐⭐⭐⭐⭐ |
| **表列映射** | 是否正确使用表和列 | ⭐⭐⭐⭐ |
| **Placeholder 数量** | [PLACEHOLDER] 的数量（越少越好） | ⭐⭐⭐ |
| **SQL 简洁性** | SQL 长度和复杂度（越简洁越好） | ⭐⭐ |
| **性能考虑** | 是否有明显的性能问题 | ⭐⭐ |

---

### 2.4 LLM 参数配置

#### **SQL 生成参数配置**

```python
SQL_GENERATION_COMPASS_CONFIGS = [
    {
        "temperature": 0.9,    # 高温度，多样性
        "topP": 0.8,           # 核心采样 80%
        "topK": 20,
        "repetitionPenalty": 1,
    },  # 第 1 次 - 高温度多样化尝试
    
    {
        "temperature": 0.65,   # 中等温度
        "topP": 0.84,
        "topK": 8,             # topK 小，更聚焦
        "repetitionPenalty": 1,
    },  # 第 2 次 - 平衡策略
    
    {
        "temperature": 0.95,   # 很高温度
        "topP": 0.78,
        "topK": 20,
        "repetitionPenalty": 1.02,  # 轻微重复惩罚
    },  # 第 3 次 - 高创意尝试
    
    {
        "temperature": 0.3,    # 很低温度，确定性强
        "topP": 0.82,
        "topK": 10,
        "repetitionPenalty": 1,
    },  # 第 4 次 - 保守策略
]
```

**参数含义**：
- **temperature** (0.0-2.0)：
  - 高（>0.8）→ 多样性强，创意好，但可能不稳定
  - 中（0.6-0.8）→ 平衡
  - 低（<0.4）→ 确定性强，但创意差
  
- **topP** (0.0-1.0)：核心采样，只考虑概率和达到 P 值的 token
  
- **topK**：只考虑概率最高的 K 个 token
  
- **repetitionPenalty**：惩罚重复 token，值 > 1.0 时启用

#### **最优参数策略**

```python
# 根据失败次数调整参数
if first_attempt_failed:
    # 第 1 次尝试失败 → 使用更多样化的参数
    use_config = SQL_GENERATION_COMPASS_CONFIGS[1]  # temp=0.65
    
elif second_attempt_failed:
    # 第 2 次尝试失败 → 尝试高温度创新
    use_config = SQL_GENERATION_COMPASS_CONFIGS[2]  # temp=0.95
    
elif third_attempt_failed:
    # 第 3 次尝试失败 → 使用保守策略
    use_config = SQL_GENERATION_COMPASS_CONFIGS[3]  # temp=0.3
```

---

### 2.5 Placeholder 处理机制

#### **什么是 Placeholder？**

```sql
-- 示例 1：不确定的列值
SELECT user_id, SUM(order_amount) 
FROM orders
WHERE grass_date = '[PLACEHOLDER]'  -- ← 不知道具体日期
GROUP BY user_id;

-- 示例 2：不确定的表
SELECT * FROM [PLACEHOLDER]  -- ← 多个可能的表

-- 示例 3：不确定的条件
WHERE payment_status = [PLACEHOLDER]  -- ← 可能的值：completed/pending/failed
```

#### **检测机制**

```python
def check_placeholder_in_sql(state):
    """检测 SQL 中的 Placeholder"""
    sql = state["sql"]
    
    # 正则表达式匹配
    placeholder_pattern = r"\[.*PLACEHOLDER.*\]"
    
    if re.search(placeholder_pattern, sql, re.IGNORECASE):
        # 找到 Placeholder，需要问人
        state["ask_human_status"] = {
            "ask_human": True,
            "ask_human_question": f"""
            The generated SQL contains a placeholder that requires additional information.
            
            {sql}
            
            Please provide the necessary details for the [PLACEHOLDER] markers.
            """
        }
        return state
    
    return state
```

#### **Placeholder 的优势和劣势**

| 方面 | 优点 | 缺点 |
|------|------|------|
| **用户体验** | 明确告诉用户需要补充信息 | 中断用户工作流 |
| **准确性** | 避免 LLM 的随意猜测 | 需要用户参与 |
| **透明性** | 清楚地展示系统的限制 | 可能影响使用体验 |

---

## 三、Fix SQL 完整流程

### 3.1 修复的触发条件

```python
def should_fix_sql(state):
    """判断是否需要修复 SQL"""
    
    # 条件 1：已经尝试了太多次
    if state.get("fix_attempts", 0) >= 2:
        return False  # 不再修复，给出错误
    
    # 条件 2：SQL 存在错误
    if state.get("error") is None:
        return False  # 没有错误，不需要修复
    
    # 条件 3：用户已要求确认
    if state.get("ask_human_status", {}).get("ask_human", False):
        return False  # 用户已确认，不修复
    
    return True
```

### 3.2 修复流程

#### **Single-Pass Fix**

```
错误 SQL
  ↓
[解析错误信息]
  ├─ 语法错误（语法不对）
  ├─ 逻辑错误（表不存在、列不存在）
  ├─ 权限错误（无查询权限）
  └─ 其他错误
  ↓
[调用 Fix LLM]
  输入：错误 SQL + 错误信息 + 表元数据 + 用户问题
  输出：修复后的 SQL
  ↓
[验证修复结果]
  ├─ 语法检查 → Pass: 返回修复结果
  │                Fail: 生成新错误信息
  ├─ Placeholder 检测 → 有: ask_human
  │                      无: 继续
  └─ 决定是否再修一遍
  ↓
最终 SQL（修复成功或给出错误）
```

#### **LLM Fix 的 Prompt 策略**

```python
def fix_sql(state):
    """修复错误 SQL"""
    
    # 构建修复 Prompt
    fix_prompt = f"""
    You are a {dialect} SQL database expert tasked with correcting a SQL query 
    that contains errors.
    
    **Original Question**: {state['question']}
    **Error SQL**: {state['sql']}
    **Error Info**: {state['error']}
    
    **Procedure**:
    1. Review the error and understand what went wrong
    2. Analyze the table metadata to understand schema
    3. Correct the query to address the identified issues
    
    **Constraints**:
    - If the error can be fixed by only changing one line, only change that line
    - If unsure about a value, mark it with [PLACEHOLDER]
    - Preserve original query logic when possible
    """
    
    fixed_sql = LLM.invoke(fix_prompt)
    return fixed_sql
```

#### **修复参数配置**

```python
SQL_FIX_COMPASS_CONFIGS = [
    # 高创意 - 探索完全不同的方法
    {"temperature": 1.2, "topP": 0.95, "topK": 40, "repetitionPenalty": 1.1},
    
    # 平衡创意 - 系统性问题解决
    {"temperature": 0.8, "topP": 0.9, "topK": 20, "repetitionPenalty": 1.05},
    
    # 保守创意 - 谨慎修复
    {"temperature": 0.6, "topP": 0.85, "topK": 10, "repetitionPenalty": 1.02},
    
    # 高探索 - 寻找新颖解决方案
    {"temperature": 1.0, "topP": 0.98, "topK": 50, "repetitionPenalty": 1.15},
]
```

**特点**：
- 温度更高（0.6-1.2 vs 生成时的 0.3-0.95）
- 更高的 topP/topK，鼓励创意
- 更强的 repetitionPenalty，防止重复错误

---

### 3.3 修复的重试策略

```
第 1 次生成 SQL
  ↓
尝试执行 → 成功: 结束
        ↓
        失败 → 分析错误
             ↓
        [第 1 次修复]（fix_attempts = 0）
             ↓
        验证修复后 SQL
        成功: 结束
        失败: 继续
             ↓
        [第 2 次修复]（fix_attempts = 1）
             ↓
        验证修复后 SQL
        成功: 结束
        失败: 放弃（fix_attempts = 2）→ ask_human 或返回错误
```

**关键参数**：
```python
MAX_FIX_ATTEMPTS = 2  # 最多修复 2 次

if state.get("fix_attempts", 0) >= MAX_FIX_ATTEMPTS:
    # 放弃修复，返回当前错误信息
    return state
```

---

## 四、参数最优化指南

### 4.1 何时使用 Plan-and-Reflect vs Divide-and-Conquer

| 场景 | 建议策略 | 原因 |
|------|--------|------|
| 简单查询（单表）| Plan-and-Reflect | 足够有效，Token 少 |
| 中等复杂（2-3 表）| Plan-and-Reflect | 通常足够，更高效 |
| 复杂查询（3+ 表） | Divide-and-Conquer | 将问题分解更有利 |
| 多层聚合 | Divide-and-Conquer | 子问题更清晰 |
| 用户不确定性高 | Plan-and-Reflect | 便于显示推理过程 |

### 4.2 LLM 参数调优建议

#### **调优目标**

```
质量 ↗ ← 目标
成本 ↘
延迟 ↘
```

#### **调优策略**

**目标 1：提高准确率**
```python
# 使用更多温度变化
increase_temperature_variance = True

# 配置 1: 保守 (temperature=0.3)
# 配置 2: 平衡 (temperature=0.65)
# 配置 3: 创意 (temperature=0.95)

# 结果：4 次并行调用，取最好的
result = best_of(
    [result1, result2, result3, result4],
    metric=sql_quality_score
)
```

**目标 2：降低成本**
```python
# 使用单次调用代替多次
use_plan_and_reflect_only = True  # 不使用 divide-and-conquer

# 结果：从 3 次 LLM 调用降为 2 次
```

**目标 3：降低延迟**
```python
# 并行化两条路径
parallel_execution = True

# plan-and-reflect 和 divide-and-conquer 同时运行
# 而不是串联
# 结果：总耗时 ≈ max(time_A, time_B) 而不是 time_A + time_B
```

### 4.3 实时监控和自适应

#### **关键指标**

| 指标 | 目标值 | 监控方式 |
|------|--------|---------|
| **First-Pass Success Rate** | > 85% | 无需修复的比例 |
| **Placeholder Rate** | < 5% | 需要用户输入的比例 |
| **Fix Success Rate** | > 70% | 修复后成功的比例 |
| **Avg Token Usage** | < 8K | 单次查询平均 Token |
| **Avg Latency** | < 15s | 单次查询平均耗时 |

#### **自适应调整**

```python
def adapt_parameters(metrics):
    """根据实时指标调整参数"""
    
    if metrics['first_pass_success'] < 0.85:
        # 成功率低 → 增加多次尝试
        config['use_parallel_generation'] = True
        config['num_generation_attempts'] = 4
    
    if metrics['placeholder_rate'] > 0.1:
        # Placeholder 过多 → 改进 prompt
        config['improve_prompt'] = True
        config['add_examples'] = True
    
    if metrics['fix_success_rate'] < 0.6:
        # 修复成功率低 → 增加修复次数或改进 fix prompt
        config['max_fix_attempts'] = 3
        config['use_higher_temp_for_fix'] = True
    
    return config
```

---

## 五、错误类型与修复策略

### 5.1 常见 SQL 错误类型

| 错误类型 | 症状 | 修复策略 | 难度 |
|---------|------|--------|------|
| **语法错误** | 关键字错误、括号不匹配 | LLM 可直接修复 | 低 |
| **表不存在** | Table not found | 检查表名、确认权限 | 中 |
| **列不存在** | Column not found | 检查列名、验证 schema | 中 |
| **类型不匹配** | Type mismatch in JOIN | 添加类型转换 | 中 |
| **逻辑错误** | 空结果或结果不符 | 需要理解业务逻辑 | 高 |
| **权限错误** | Access denied | ask_human 确认权限 | 高 |
| **分区错误** | Partition not found | 修复分区条件 | 中 |

### 5.2 修复案例

#### **案例 1：列名错误**

```
原始查询："查询订单的总金额"
生成 SQL：
    SELECT SUM(order_amount) FROM orders
    ↑ 错误：列名应该是 total_amount

修复过程：
1. 错误信息："Column 'order_amount' does not exist"
2. 查看表元数据 → 列名列表包括 'total_amount'
3. 修复 SQL：
    SELECT SUM(total_amount) FROM orders
4. 重新执行 → 成功
```

#### **案例 2：表 Join 错误**

```
原始查询："查询每个用户的订单数和总消费"
生成 SQL：
    SELECT u.user_id, COUNT(*), SUM(o.total_amount)
    FROM users u
    JOIN orders o ON u.user_id = o.seller_id  -- ← 错误
    GROUP BY u.user_id

修复过程：
1. 错误信息：结果不符预期（Join 条件错误）
2. 分析：users 和 orders 应该通过 buyer_id 关联，不是 seller_id
3. 修复 SQL：
    SELECT u.user_id, COUNT(*), SUM(o.total_amount)
    FROM users u
    JOIN orders o ON u.user_id = o.buyer_id
    GROUP BY u.user_id
4. 重新执行 → 成功
```

#### **案例 3：Placeholder 不确定性**

```
原始查询："查询最近 7 天的销售额"
生成 SQL：
    SELECT SUM(amount) FROM sales
    WHERE sale_date >= '[PLACEHOLDER]'  -- ← 不知道起始日期

检测过程：
1. 检测到 [PLACEHOLDER] marker
2. ask_human_status.ask_human = True
3. ask_human_question = "请指定查询起始日期（YYYY-MM-DD 格式）"

用户输入：2024-12-05
最终 SQL：
    SELECT SUM(amount) FROM sales
    WHERE sale_date >= '2024-12-05'
```

---

## 六、提升 Generate & Fix SQL 准确率的方法

### 6.1 Generate SQL 优化方向

#### **1. Prompt 工程优化**

**当前**：
- 清晰的 Planner Prompt
- 用户背景信息注入
- 样本 SQL 和数据示例

**可改进**：
- [ ] **Few-shot 学习增强**：添加更多的 (问题, SQL) 对示例
- [ ] **示例多样性**：包含各种复杂度的查询示例
- [ ] **反例学习**：加入"错误的 SQL"和解释为什么错误
- [ ] **领域特定 Prompt**：针对不同类型的查询（聚合、Join、窗口函数）优化 Prompt
- [ ] **思维链（CoT）强化**：让 LLM 显式说出每一步的推理

#### **2. 模型选择和参数调优**

**当前**：
- 使用 Compass 模型（内部定制 LLM）
- 4 种参数配置并行执行
- 选择最优结果

**可改进**：
- [ ] **多模型融合**：使用不同的模型（GPT-4, Claude, Qwen）并投票
- [ ] **参数学习**：用 Bayesian 优化找到最优的 temperature、topP、topK
- [ ] **动态参数**：根据查询复杂度动态调整参数
- [ ] **模型特定化**：为 SQL 生成训练专用的小模型

#### **3. 上下文优化**

**当前**：
- 提供表元数据（列名、类型、样本数据）
- 提供业务规则和术语

**可改进**：
- [ ] **智能上下文剪裁**：只提供相关表，隐藏无关表
- [ ] **样本数据优化**：提供更有代表性的数据样本
- [ ] **表关系图**：显示表间的 Join 关系
- [ ] **Query 示例优化**：提供与用户查询最相似的 SQL 示例

#### **4. 后处理优化**

**当前**：
- SQL 语法校验
- Placeholder 检测

**可改进**：
- [ ] **逻辑一致性检查**：验证 SELECT/WHERE/GROUP BY 的逻辑一致性
- [ ] **结果合理性检查**：如果预测执行结果，检查是否合理
- [ ] **自动修复常见错误**：不依赖人工干预，自动修复已知错误模式
- [ ] **性能优化提示**：检测 N+1 查询等性能问题

### 6.2 Fix SQL 优化方向

#### **1. 错误分类优化**

**当前**：
- 通用的 Fix Prompt
- 相同的修复策略

**可改进**：
- [ ] **错误类型识别**：自动分类错误类型（语法、逻辑、权限等）
- [ ] **针对性修复**：不同错误类型使用不同的 Fix Prompt
  ```python
  if error_type == "SyntaxError":
      fix_prompt = SYNTAX_ERROR_FIX_PROMPT
  elif error_type == "ColumnNotFound":
      fix_prompt = COLUMN_NOT_FOUND_FIX_PROMPT
  elif error_type == "LogicError":
      fix_prompt = LOGIC_ERROR_FIX_PROMPT
  ```

#### **2. Fix 重试策略优化**

**当前**：
- 最多 2 次修复
- 固定的参数配置

**可改进**：
- [ ] **累进式修复**：
  - 第 1 次：保守修复（只改最小化改动）
  - 第 2 次：激进修复（尝试更大改动）
  - 第 3 次：重新生成（放弃修复，重新生成）
- [ ] **错误驱动的策略**：根据错误信息选择合适的修复 Prompt
- [ ] **增加修复次数**：对于重要查询允许更多次修复

#### **3. 修复验证增强**

**当前**：
- 语法校验
- Placeholder 检测

**可改进**：
- [ ] **预测执行**：在真正执行前预测结果是否合理
- [ ] **与原查询对比**：确保修复不会改变查询意义
- [ ] **多维度验证**：
  ```python
  def validate_fixed_sql(original_sql, fixed_sql):
      checks = [
          syntax_check(fixed_sql),           # 语法是否正确
          logic_check(original_sql, fixed_sql),  # 逻辑是否保留
          performance_check(fixed_sql),      # 是否有性能问题
          compatibility_check(fixed_sql),    # 是否兼容方言
      ]
      return all(checks)
  ```

### 6.3 两条 SQL 选择的优化

#### **当前方法**：
- LLM 直接选择（Simple Comparison）

#### **可改进的方法**：

**1. 多维度评分**
```python
def score_sql(sql, question, context):
    """对 SQL 进行多维度评分"""
    scores = {
        "syntax": syntax_validity_score(sql),              # 语法正确率
        "logic": logic_consistency_score(sql, question),   # 逻辑一致性
        "completeness": completeness_score(sql),           # 完整性（无 Placeholder）
        "simplicity": simplicity_score(sql),               # 简洁性
        "performance": performance_score(sql),             # 性能评分
        "coverage": table_coverage_score(sql, context),    # 表覆盖率
    }
    
    # 加权求和
    weights = {
        "syntax": 0.3,
        "logic": 0.3,
        "completeness": 0.2,
        "simplicity": 0.1,
        "performance": 0.05,
        "coverage": 0.05,
    }
    
    final_score = sum(scores[k] * weights[k] for k in weights.keys())
    return final_score
```

**2. 试执行比较**
```python
# 对两条 SQL 进行试执行（使用限制条件）
result_a = execute_with_limit(sql_a, limit=100)
result_b = execute_with_limit(sql_b, limit=100)

# 比较结果
if result_a.error and not result_b.error:
    return sql_b
elif result_b.error and not result_a.error:
    return sql_a
elif len(result_a.rows) > 0 and len(result_b.rows) == 0:
    return sql_a
```

**3. 混合策略**
```python
def choose_sql_hybrid(sql_a, sql_b, question, context):
    """混合多个信号进行选择"""
    
    # 信号 1：LLM 直接比较
    llm_choice = llm_compare(sql_a, sql_b, question, context)
    
    # 信号 2：多维度评分
    score_a = score_sql(sql_a, question, context)
    score_b = score_sql(sql_b, question, context)
    score_choice = 'A' if score_a > score_b else 'B'
    
    # 信号 3：试执行结果
    exec_a_success = try_execute(sql_a)
    exec_b_success = try_execute(sql_b)
    exec_choice = 'A' if exec_a_success and not exec_b_success else ('B' if exec_b_success else None)
    
    # 投票
    votes = [llm_choice, score_choice, exec_choice]
    votes = [v for v in votes if v]
    
    if votes.count('A') > votes.count('B'):
        return sql_a
    else:
        return sql_b
```

---

## 七、实战案例与问题排查

### 7.1 常见问题场景

#### **场景 1：Divide-and-Conquer 生成的 SQL 子问题分解不好**

**症状**：
- 生成的子问题与原问题关联不强
- 组装后的 SQL 逻辑混乱

**原因分析**：
- Divide Prompt 不够精准
- LLM 理解用户意图不足

**解决方案**：
```python
# 改进 Divide Prompt
IMPROVED_DIVIDE_PROMPT = """
You are tasked with breaking down a complex SQL query request into sub-questions.

Guidelines:
1. Each sub-question should be independent but related
2. Sub-questions should be answerable with the available table metadata
3. The results of sub-questions should be easily combinable

Original Question: {question}

Break this down into 3-5 sub-questions. Each should:
- Start with specific SQL context (SELECT/FROM/WHERE)
- Be answerable with the provided table metadata
- Relate back to the original question

Format:
Sub-question 1: [specific SQL-focused question]
Sub-question 2: [specific SQL-focused question]
...
"""
```

#### **场景 2：Fix SQL 循环修复失败**

**症状**：
- 第 1 次修复失败
- 第 2 次修复仍然失败
- 最后仍需要返回错误

**原因分析**：
- 错误过于复杂，LLM 无法理解
- Fix Prompt 没有足够的错误信息上下文
- 修复方向错误

**解决方案**：
```python
def advanced_fix_with_analysis(state):
    """增强的修复流程，包含错误分析"""
    
    # Step 1: 深度错误分析
    error_analysis = analyze_error_in_depth(
        original_sql=state['sql'],
        error_message=state['error'],
        table_context=state['context']
    )
    
    # Step 2: 生成修复指导
    fix_guidance = generate_fix_guidance(
        error_analysis=error_analysis,
        question=state['question'],
        original_sql=state['sql']
    )
    
    # Step 3: 基于指导的修复
    fixed_sql = fix_sql_with_guidance(
        sql=state['sql'],
        guidance=fix_guidance,
        context=state['context']
    )
    
    return fixed_sql
```

#### **场景 3：SQL 生成准确率随查询复杂度指数下降**

**症状**：
- 简单查询：95% 准确率
- 中等复杂：70% 准确率
- 复杂查询：40% 准确率

**原因分析**：
- Token 限制导致信息丢失
- 表过多导致 LLM 混淆
- 涉及多个 Join 导致逻辑复杂

**解决方案**：
```python
def adaptive_strategy_by_complexity(question, tables, state):
    """根据查询复杂度选择策略"""
    
    complexity_score = calculate_complexity(question, tables)
    
    if complexity_score < 3:
        # 简单查询：Plan-and-Reflect 足够
        strategy = "plan_and_reflect_only"
        num_attempts = 2
        
    elif complexity_score < 5:
        # 中等复杂：两种方法并行
        strategy = "parallel_both"
        num_attempts = 3
        
    else:
        # 复杂查询：Divide-and-Conquer + 更多重试
        strategy = "divide_and_conquer_only"
        num_attempts = 4
        use_larger_model = True  # 使用更强模型
        use_step_by_step = True  # 逐步验证
    
    return strategy
```

---

## 八、性能基准与目标

### 8.1 关键性能指标（KPI）

| 指标 | 当前值 | 目标值 | 优先级 |
|------|--------|--------|--------|
| **First-Pass Success Rate** | 70% | 85% | ⭐⭐⭐⭐⭐ |
| **One-Shot Fix Rate** | 60% | 75% | ⭐⭐⭐⭐ |
| **Placeholder Rate** | 8% | < 3% | ⭐⭐⭐⭐ |
| **Average Tokens per Query** | 12K | < 10K | ⭐⭐⭐ |
| **Average Latency** | 18s | < 12s | ⭐⭐⭐ |
| **Two-Method Consensus** | 65% | > 80% | ⭐⭐⭐ |

### 8.2 成本与延迟权衡

```
┌─────────────────────────────────────────┐
│ Cost vs Quality vs Latency Triangle     │
├─────────────────────────────────────────┤
│                                         │
│         High Quality                    │
│            ▲                            │
│           /│\                           │
│          / │ \                          │
│         /  │  \                         │
│        /   │   \                        │
│       /    │    \                       │
│      /     │     \                      │
│     /      │      \                     │
│    / Paral- │-Seq.  \                   │
│   /  lel   │  Single \                  │
│  /    Both │  Call    \                 │
│ /          │           \                │
│Low Cost         Latency                 │
│             ← Tradeoff →                │
│                                         │
│ Recommended: Pick 2 or find balance     │
└─────────────────────────────────────────┘
```

**策略选择**：
- 低成本优先 → 单次调用 (Plan-and-Reflect)
- 低延迟优先 → 并行两种方法
- 高质量优先 → 4 倍参数尝试 + 人工验证



### 9.4 关键代码文件索引

| 功能 | 文件路径 |
|------|--------|
| SQL 生成主流程 | `di_brain/text2sql/text2sql_step.py` |
| 图定义 | `di_brain/text2sql/text2sql_ask_human_graph.py` |
| Prompt 模板 | `di_brain/text2sql/text2sql_prompt.py` |
| SQL 提取和验证 | `di_brain/text2sql/sql_extractor.py` |
| 状态定义 | `di_brain/text2sql/state.py` |
| 工具入口 | `di_brain/router/tool_router.py` → `generate_sql`, `fix_sql` |

---

**文档版本**: v1.0  
**最后更新**: 2024-12-12  
**作者**: AI 助手  
**相关文档**: `find_data.md`

