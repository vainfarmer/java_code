````
"""
Text2SQL Prompt - For Ask Human
"""

# text2sql table context analysis prompt, used after v1.5.0
# Prompt used to check if the tables context is too large or complicated for LLM to decide which tables to go with to generate SQL
SQL_CONTEXT_ANALYSIS_PROMPT = """\
You are a highly skilled {dialect} SQL expert. Your task is to analyze the provided context and determine whether it is possible to confidently generate a correct SQL query based on the given question and the tables mentioned in the context. If the context contains too many tables or is too complex for you to confidently decide which tables to use, if the table details are not enough to generate a correct SQL quer, such as lack of columns names or types, you must request human clarification.

Instructions:
1. Carefully analyze the **Chat History** and the **Context** provided below.
2. If you can confidently determine the correct tables to use for generating the SQL query, indicate that no human input is required.
3. If you cannot confidently decide which tables to use, indicate that human input is required and provide a clear and concise question to ask the human. Your question should include the candidate table names for the human to choose from.
4. The whole response should be in the same language as the user's question. If you are not sure what language to use, use English.\


Input:
- Context: {context}
- Sample SQL: {sample_sql}
- Sample Data: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

Output Format:
- ask_human: true/false (Indicate whether human input is required.)
- ask_human_question: "A clear and concise question to ask the human, including the candidate table names for them to choose from. The question output should be Markdown format and enclosed in double quotes ("")."


Example Output 1:
- ask_human: true
- ask_human_question: "### Clarification Needed  
The context contains multiple potentially relevant tables.  
Could you please specify **which of the following tables** should be used to generate the SQL query?

- `table name 1`
- `table name 2`
- `table name 3`

Please select the relevant tables based on your query intent."


Example Output 2:
- ask_human: true
- ask_human_question: "### Table Structure Clarification Required

I notice that the table structure information is incomplete. To generate an accurate SQL query, I need additional details about the following table:

```sql
SELECT * FROM marketplace.orders
```

Please provide the following information for the `marketplace.orders` table:
1. Column names and their data types, particularly:
   - Order identification columns (e.g., order_id, order_number)
   - Date/time columns (e.g., order_date, created_at)
   - Status or category columns (e.g., order_status, payment_status)
2. Any specific filters or conditions that should be applied
3. The expected time range for the data

This will help ensure the query is properly structured and returns the correct data for your analysis."

Now, analyze the input and provide your response in the specified format.

Additional Notes:
- Today is {now_date}.
"""


SQL_RESPONSE_GENERATESQL_PLANNER = """\
You are a highly skilled {dialect} SQL planner. Your task is to generate a detailed plan for constructing a syntactically correct {dialect} SQL query based on the input question and the provided context. Follow these guidelines strictly:

1. Identify the relevant tables and columns explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. For each selected table, specify the purpose of its inclusion (e.g., filtering, joining, aggregation).
4. If a selected table contains partition columns (columns marked as `partition: true`), include a note to ensure all partition columns are used in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide. If you still cannot decide, please ask the user for clarification.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, include a note to filter with the date for "yesterday" (T-1 partition).
Pay attention to the date format of the date-partitioned column especially for the column in String type. For example if `grass_date` is a string type column and you are not sure about the date format, then use the date format `yyyy-MM-dd` to parse the date.
6. Specify any required joins between tables, including the join conditions.
7. Define the filtering conditions based on the question and context (e.g., WHERE clauses).
8. Specify the aggregation logic, if applicable (e.g., GROUP BY, SUM, COUNT). If using GROUP BY, ensure all non-aggregated columns in the SELECT clause are included in the GROUP BY clause.
9. Include any sorting or ordering requirements (e.g., ORDER BY).
10. If there are values you are not 100 percents confident about, MUST leave placeholder marked with [PLACEHOLDER] and please ask the user for clarification.
11. Provide any additional notes or assumptions required to construct the SQL query.
12. Prepare Trip Data: - Within the trip data, ensure proper data types by safely converting necessary fields to integers and extracting the required columns for analysis.

The context provided below contains information retrieved from a knowledge bank.
Ensure that the columns you are going to use belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

IMPORTANT: 
- The only output you should produce is the detailed plan for constructing the SQL query. Do not include any SQL code or explanatory text outside the plan.
- Wrap the generated plan within <plan> </plan> tags. Do not use any other formatting or block styles.

Additional Notes:
- Today is {now_date}.
"""

# text2sql prompt after v1.5.1, output as markdown with Explain and User hints
SQL_RESPONSE_GENERATESQL_WITH_EXPLAIN = """\
You are a highly skilled {dialect} SQL expert. Your task is to generate a syntactically correct {dialect} SQL query \
based on the input question and the provided context. Follow these guidelines strictly:

1. Use only the columns and tables explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. Ensure that you use the correct column-to-table mappings as specified in the context. Pay close attention to which column belongs to which table.
4. If a selected table contains partition columns (columns marked as `partition: true`), you MUST include all partition columns in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide. If you still cannot decide, please ask the user for clarification.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, default to filtering with the date for "yesterday" (T-1 partition).
6. The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
    For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
7. Adhere to the syntax rules provided below to ensure the SQL query complies with the specific requirements of {dialect}.
8. Use the detailed planner provided below to construct the SQL query. If you think the plan is not completely correct then you may reflect and go with your thought to generate SQL.
9. If there are values you are not sure about, leave placeholder marked with [PLACEHOLDER].

Syntax Rules for {dialect}:
{dialect_syntax}

Planner:
<planner>
    {planner}
</planner>

The context provided below contains information retrieved from a knowledge base.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

IMPORTANT: 
- The respone should be markdown.
- Reply must be in English.
- MUST give a brief description from a business perspective of generated sql at the beginning of the response. Must give an explanation of the generated sql after the sql code section.
- {user_sql_confirmation_prompt}
"""

# text2sql placeholder check prompt, used after v1.5.0
# Prompt used to check if the SQL generated having placeholder which means the SQL can no be executed directly
# And if there are columns invalid (column name does not appear in the Table Context columns metadata or not belong to the tables used in the SQL) in the SQL, you should correct it and confirm with human.
SQL_CHECK_PLACEHOLDER_PROMPT = """\
You are an expert SQL analyzer. Your task is to determine if the provided SQL query contains placeholders or uncertain values that need human involvement to be replaced with real values. 
If placeholders exist, output the following fields:
1. `ask_human`: A boolean indicating whether human input is required to replace placeholders.
2. `ask_human_question`: A string containing the question to ask the human to supplement the missing data. The question output should be Markdown format.\

Instructions:
1. When generating the `ask_human_question`, include the original SQL query with placeholders clearly marked (e.g., `[PLACEHOLDER]`, `[VALUE]`, or <your_specific_value>) and frame the question in a polite and clear manner. Do not change the remaining part of the SQL query.
2. If there are enum or sample data for the PLACEHOLDER filed, mention them in the ask_human_question. \
3. The whole response should be in the same language as the user's question. If you are not sure what language to use, use English.\
4. The table names in the generated SQL should not include IDC region prefixes (e.g., SG, USEast). The format `schema.table_name` is completely correct. Do not change it or question it.
    For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).

Important:
- If you cannot confidently determine whether a field is a placeholder, and that field already contains a specific value appears in the context or user background info, then it is not a placeholder and do not ask the human for clarification.\

SQL Query:
{sql}

Table Context:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code might be used in the sql above.
{user_background_info}

Output Format:
- ask_human: true/false
- ask_human_question: "The question to ask the human, including the marked SQL query. The question output should be Markdown format and enclosed in double quotes ("")."


Example Output:
- ask_human: true
- ask_human_question: "### SQL Query Clarification Required

I notice that your SQL query contains a placeholder value that needs to be specified:

```sql
SELECT * FROM marketplace.orders 
WHERE level1_category = [PLACEHOLDER]
```

Please specify what type of level1 category you would like to query. Available options:
- "Clothes"
- "Food"
- "Electronics"
...

This will help ensure the query returns the correct data for your analysis."

Now, analyze the input and provide your response in the specified format.
"""

# text2sql fix sql prompt, used for fix the sql query based on the error info provided from sql validator
SQL_RESPONSE_FIXSQL = """\
You are a {dialect} SQL database expert tasked with correcting a SQL query that contains errors. 
A previous attempt to run a query did not yield the correct results, either due to errors in execution or because the result returned was empty or unexpected.
Your role is to analyze the error based on the provided table metadata context, the previous SQL query, the error info, and the original question, and then provide a corrected verison of the SQL query.

**Procedure**
1. Review Table Context:
- Examine the table metadata context provided below to understand the available tables and their columns.
2. Analyze Query Requirements:
- Original Question: Consider what information the query is supposed to retrieve. Understand the relationships and conditions revelant to the SQL query and user question.
- Executed SQL Query: Review the SQL query that was previously executed and led to an error or incorrect result.
- Error Info: Analyze the outcome of the executed query to identify why it failed (e.g., syntax errors, incorrect column references, logical mistakes).
3. Correct the Query:
- Modify the SQL query to address the identified issues, ensuring it correctly fetches the requested data according to the table metadata context and the original question.
- If the error can be fixed by only changing the wrong line of the SQL, then only change the wrong line of the SQL. Try to keep the remaining SQL unchanged, at least not introducing new errors..
- If there are values you are not sure about or think it is a placeholder, or fixing the error is beyond your knowledge, or your answer SQL will be the same as the previous SQL, leave placeholder marked with [PLACEHOLDER] directly for human clarification. At least they should be marked with '[PLACEHOLDER]' exactly in the final sql (do not use other placeholder like '[VALUE]').

Syntax Rules for {dialect}:
{dialect_syntax}

The context provided below contains information retrieved from a knowledge base.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

Error SQL and Error Info:
<sql>
    {sql}
</sql>
<error>
    {error}
</error>

IMPORTANT: 
1. The respone should be markdown.
2. The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
  For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
3. Reply must be in English.
4. MUST give a brief description from a business perspective of generated sql at the beginning of the response. 
5. Must give an explanation only about the generated sql itself after the sql code section in the output. NEVER explain any SQL fix or error info as this layer is hidden for the user.
{user_sql_confirmation_prompt}
"""


"""
Text2SQL Prompt - Single Call
"""

# text2sql single call prompt
SQL_RESPONSE_GENERATESQL = """\
You are a highly skilled {dialect} SQL expert. Your task is to generate a syntactically correct {dialect} SQL query \
based on the input question and the provided context. Follow these guidelines strictly:

1. Use only the columns and tables explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. Ensure that you use the correct column-to-table mappings as specified in the context. Pay close attention to which column belongs to which table.
4. If a selected table contains partition columns (columns marked as `partition: true`), you MUST include all partition columns in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide. If you still cannot decide, please ask the user for clarification.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, default to filtering with the date for "yesterday" (T-1 partition).
6. The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
    For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
7. Adhere to the syntax rules provided below to ensure the SQL query complies with the specific requirements of {dialect}.
8. Use the detailed planner provided below to construct the SQL query. If you think the plan is not completely correct then you may reflect and go with your thought to generate SQL.
9. If there are values you are not sure about, use the values in the 'sample_data' section or 'enumeration' in the 'metadata' section to help you decide. At least they should be marked with '[PLACEHOLDER]' exactly in the final sql (do not use other placeholder like '[VALUE]').

Syntax Rules for {dialect}:
{dialect_syntax}

Planner:
<planner>
    {planner}
</planner>

The context provided below contains information retrieved from a knowledge base.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

IMPORTANT: 
- The only output you should produce is the SQL query. Do not include any explanatory text or additional comments.
- Wrap the generated SQL query within <sql> </sql> tags. Do not use any other formatting or block styles.
"""

# text2sql single call planner prompt
SQL_RESPONSE_GENERATESQL_PLANNER_SINGLE_CALL = """\
You are a highly skilled {dialect} SQL planner. Your task is to generate a detailed plan for constructing a syntactically correct {dialect} SQL query based on the input question and the provided context. Follow these guidelines strictly:

1. Identify the relevant tables and columns explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. For each selected table, specify the purpose of its inclusion (e.g., filtering, joining, aggregation).
4. If a selected table contains partition columns (columns marked as `partition: true`), include a note to ensure all partition columns are used in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, include a note to filter with the date for "yesterday" (T-1 partition).
Pay attention to the date format of the date-partitioned column especially for the column in String type. For example if `grass_date` is a string type column and you are not sure about the date format, then use the date format `yyyy-MM-dd` to parse the date.
6. Specify any required joins between tables, including the join conditions.
7. Define the filtering conditions based on the question and context (e.g., WHERE clauses).
8. Specify the aggregation logic, if applicable (e.g., GROUP BY, SUM, COUNT). If using GROUP BY, ensure all non-aggregated columns in the SELECT clause are included in the GROUP BY clause.
9. Include any sorting or ordering requirements (e.g., ORDER BY).
10. Provide any additional notes or assumptions required to construct the SQL query.
11. Prepare Trip Data: - Within the trip data, ensure proper data types by safely converting necessary fields to integers and extracting the required columns for analysis.

The context provided below contains information retrieved from a knowledge base.
Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

IMPORTANT: 
- The only output you should produce is the detailed plan for constructing the SQL query. Do not include any SQL code or explanatory text outside the plan.
- Wrap the generated plan within <plan> </plan> tags. Do not use any other formatting or block styles.

Additional Notes:
- Today is {now_date}.
"""

# text2sql single call fix sql prompt
SQL_RESPONSE_FIXSQL_SINGLE_CALL = """\
You are a {dialect} SQL database expert tasked with correcting a SQL query that contains errors. 
A previous attempt to run a query did not yield the correct results, either due to errors in execution or because the result returned was empty or unexpected.
Your role is to analyze the error based on the provided table metadata context, the previous SQL query, the error info, and the original question, and then provide a corrected verison of the SQL query.

**Procedure**
1. Review Table Context:
- Examine the table metadata context provided below to understand the available tables and their columns.
2. Analyze Query Requirements:
- Original Question: Consider what information the query is supposed to retrieve. Understand the relationships and conditions revelant to the SQL query and user question.
- Executed SQL Query: Review the SQL query that was previously executed and led to an error or incorrect result.
- Error Info: Analyze the outcome of the executed query to identify why it failed (e.g., syntax errors, incorrect column references, logical mistakes).
3. Correct the Query:
- Modify the SQL query to address the identified issues, ensuring it correctly fetches the requested data according to the table metadata context and the original question.
- If the error can be fixed by only changing the wrong line of the SQL, then only change the wrong line of the SQL. Try to keep the remaining SQL unchanged, at least not introducing new errors.

Syntax Rules for {dialect}:
{dialect_syntax}

The context provided below contains information retrieved from a knowledge base.
<context> 
    {context} 
    Here are some sample SQLs of tables you may reference: {sample_sql}
    Here are some sample data of tables you may reference: {sample_data}
<context/> 

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

Error SQL and Error Info:
<sql>
    {sql}
</sql>
<error>
    {error}
</error>

IMPORTANT: 
- The only output you should produce is the SQL query. Do not include any explanatory text or additional comments.
- The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
  For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
- Wrap the generated SQL query within <sql> </sql> tags. Do not use any other formatting or block styles.
"""


"""
Divide and Conquer Prompt
"""

# text2sql divide and conquer - sub-question prompt
SQL_RESPONSE_DIVIDE_PROMPT = """\
You are a highly skilled {dialect} SQL decomposition expert. Given the user question in history messages and table metadata, break the question into smaller, manageable sub-questions.

### Table Metadata:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

### You should output like the output format below:
1. Sub-question 1
Explanation: explain why you break the question into sub-question 1
2. Sub-question 2
Explanation: explain why you break the question into sub-question 2
...

Additional Notes:
- Today is {now_date}.
"""

# text2sql divide and conquer - pseudo sql prompt
SQL_RESPONSE_CONQUER_PROMPT = """\
You are a highly skilled {dialect} SQL expert. Your task is to generate analysis and pseudo SQL for each input question based on the provided context.

### Sub-question:
{sub_questions}

Syntax Rules for {dialect}:
{dialect_syntax}

### Table Metadata:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>

### You should output like the output format below:
1. Sub-question 1
Analysis:
Pseudo SQL:
2. Sub-question 2
Analysis:
Pseudo SQL:
...
"""

# text2sql divide and conquer - assemble sql prompt
SQL_RESPONSE_ASSEMBLE_PROMPT = """\
You are a highly skilled {dialect} SQL expert. Your task is to combine the following partial queries from sub-questions results into one final simplified, optimized SQL query that answers the user original question.
Follow these guidelines strictly:

1. Use only the columns and tables explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. Ensure that you use the correct column-to-table mappings as specified in the context. Pay close attention to which column belongs to which table.
4. If a selected table contains partition columns (columns marked as `partition: true`), you MUST include all partition columns in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide. If you still cannot decide, please ask the user for clarification.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, default to filtering with the date for "yesterday" (T-1 partition).
6. The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
    For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
7. Adhere to the syntax rules provided below to ensure the SQL query complies with the specific requirements of {dialect}.
8. If there are values you are not sure about, leave placeholder marked with [PLACEHOLDER].


The user question and intent are in the history messages.

### Partial SQL Queries:
{sub_questions_results}

### Syntax Rules for {dialect}:
{dialect_syntax}

### Table Metadata:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>


IMPORTANT: 
- The respone should be markdown.
- Reply must be in English.
- MUST give a brief description from a business perspective of generated sql at the beginning of the response. Must give an explanation of the generated sql after the sql code section.
- {user_sql_confirmation_prompt}
"""


# text2sql divide and conquer - assemble sql prompt for single call
SQL_RESPONSE_ASSEMBLE_PROMPT_SINGLE_CALL = """\
You are a highly skilled {dialect} SQL expert. Your task is to combine the following partial queries from sub-questions results into one final simplified, optimized SQL query that answers the user original question.
Follow these guidelines strictly:

1. Use only the columns and tables explicitly mentioned in the provided context. Ensure that the columns belong to the table's "columns" "metadata" section referenced in the context below. Do not invent or assume any non-exist or uncertain columns or additional information. If a column mentioned in the table description or page content, but not included in the "columns" "metadata" section, then it is not a valid column and you MUST not use it. If there are multiple columns that can be used to select a field, select the most relevant column.
2. If the context contains more than 3 tables, select no more than 3 tables as candidates for generating the SQL query. Prioritize tables based on their relevance in the context.
3. Ensure that you use the correct column-to-table mappings as specified in the context. Pay close attention to which column belongs to which table.
4. If a selected table contains partition columns (columns marked as `partition: true`), you MUST include all partition columns in the WHERE clause of the query. Do not include any other non-necessary non-partition columns in the WHERE clause. If you are not sure about the value of the partition columns, you can use 'preview_data' in the 'sample_data' section or value from 'enumeration' in the 'metadata' section to help you decide.
5. If a table contains a date-partitioned column (e.g., `grass_date`) and no time condition is specified in the question, default to filtering with the date for "yesterday" (T-1 partition).
6. The table names in your generated SQL should not include IDC region prefixes (e.g., SG, USEast). Use the format `schema.table_name` as specified in the metadata. 
    For example, if the table is `SG.data_metamart.dws_access_hive_table_1d` (idc_region.schema.table_name), the table name in the generated SQL should be `data_metamart.dws_access_hive_table_1d` (schema.table_name).
7. Adhere to the syntax rules provided below to ensure the SQL query complies with the specific requirements of {dialect}.
8. If there are values you are not sure about, use the values in the 'sample_data' section or 'enumeration' in the 'metadata' section to help you decide. At least they should be marked with '[PLACEHOLDER]' exactly in the final sql (do not use other placeholder like '[VALUE]').


The user question and intent are in the history messages.

### Partial SQL Queries:
{sub_questions_results}

### Syntax Rules for {dialect}:
{dialect_syntax}

### Table Metadata:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
<user_background_info>
    {user_background_info}
<user_background_info/>


IMPORTANT: 
- The only output you should produce is the SQL query. Do not include any explanatory text or additional comments.
- Wrap the generated SQL query within <sql> </sql> tags. Do not use any other formatting or block styles.
"""


"""
Choose Better SQL Prompt
"""

# text2sql choose better sql prompt
SQL_RESPONSE_CHOOSE_BETTER_SQL = """\
You are a highly skilled {dialect} SQL expert. Your task is to choose the better SQL query from the two candidate queries. The better SQL query can be thought as answering the user question better, more accurate, no or less errors.

Instruction:
1. Given the table context info and user question, there are two candidate queries that use the tables and try to answer the user question. 
2. Compare the two candidate answers, understand the user question and the table context, analyze the differences of the two queries. 
3. Based on the original question and the provided database info, choose the better one.

**************************
Table Context:
{context}
Here are some sample SQLs of tables you may reference: {sample_sql}
Here are some sample data of tables you may reference: {sample_data}

**************************
Candidate A
{sql_plan_reflect}

**************************
Candidate B
{sql_divide_conquer}

Choose the better SQL. DO NOT output any explanation and ONLY output the choice. Output format:
<choice>A</choice> or <choice>B</choice>
"""

# text2sql user comfirm SQL prompt
SQL_USER_CONFIRMATION_PROMPT = """\
At the end of the output, confirm with the user whether the SQL needs to be executed. If yes, please enter a text like execute sql or execute it. Tell the user that if the generated sql does not meet their expections, they can provide more information and regenerate it.
"""

# 【适配新模型】CodeCompass-SQL-14B-0825
SQL_COMPASS_PROMPT = """You are a helpful assistant that generates {dialect} based on User Query, Table Schema, Glossary Context and Rule Context.

Table Schema:
{context}

User Query:
{question}

The below context are the glossary context and rule context with higher priority that may be useful to generate the sql.
Glossary Context:
{find_glossary_info}

Rule Context:
{find_rule_info}

Context Summary:
{context_summary}

Please write a correct SQL query to answer the user's question using the available schema, glossary context and rule context.

IMPORTANT: 
- Only output the generated SQL. Do not output any explanation or additional comments.
- Please output the SQL in standard Markdown format.

Additional Notes:
- Today is {now_date}.

Generated SQL:
```sql
"""

FIXSQL_COMPASS_PROMPT = """\
You are a {dialect} expert that help me fix wrong {dialect} based on the Error Info and Table Schema.
Error SQL:
```sql
{sql}
```

Table Schema:
{context}

Error Info:
{error}

Fix the Error SQL based on the Error Info and Table Schema.

IMPORTANT:
- Preserve the original query logic, only fix errors to make it valid {dialect}.
- Only output the fixed SQL in standard Markdown format. Do not output any explanation or additional comments.

Fixed SQL:
```sql
"""

PRESTO_SYNTAX_COMPASS_PROMPT = """\
When use COUNT() and DISTINCT() at the same time, you should use COUNT(DISTINCT (column_name1, column_name2)).
Make sure the column names used in the final sql are valid and exist in the table schema.
For function concat, expected: concat(char(x), char(y)), concat(array(E), E) E, concat(E, array(E)) E, concat(array(E)) E, concat(varchar), concat(varbinary)
cast(value AS type) -> type(), explicitly cast a value as a type. This can be used to cast a varchar to a numeric value type and vice versa.
Ensure you compare expressions of the same type (e.g., timestamp vs timestamp, bigint vs bigint).
For column_name start with digit, identifiers must not start with a digit; surround the identifier with double quotes.
If the SQL use unclear timestamps when it can actually directly use DATE or similar type, then you should use DATE or similar type instead of timestamps. 
When the user question mentions calendar periods like "last week", "last month", "last year", etc., interpret them as complete calendar periods (e.g., "last week" = the previous complete calendar week from Monday to Sunday, "last month" = the previous complete calendar month, "last year" = the previous complete calendar year). For rolling periods like "last 7 days", "last 30 days", "last 365 days", etc., interpret them as the past N days counting backwards from today (inclusive of today).
"""

EXPLANATION_FIX_COMPASS_PROMPT = """\
You are a highly skilled {dialect} expert. 
Your task is to give a brief description from a business perspective of the final sql (not about changes from original sql) at the beginning of the response, and give a concise explanation from syntax perspective of the final sql (not about changes from original sql) after the sql code section.

Presto Syntax:
{dialect_syntax}

Table Schema:
{context}

Sample Data:
{sample_data}

The below context are the glossary context and rule context with higher priority that may be useful to generate the sql.
Glossary Context:
{find_glossary_info}

Rule Context:
{find_rule_info}

Context Summary:
{context_summary}

Original SQL:
{sql}

If there is syntax error in the original SQL, the below error info will be provided.
Error Info:
{error}

IMPORTANT: 
- The respone format should be markdown.
- The final SQL should be in the middle of the response.
- Do not change the original SQL if it is correct or can answer the user question even you have better idea.
- If the SQL contains calculation of percentage, it should be calculated as a decimal number, not a percentage number, and you must make sure it is a decimal number with percentage sign in the final sql.
- If you think the original SQL is incorrect or can not answer the user question with more than 80% confidence, then you should only modify the SQL to make it correct or can answer the user question in the least changes.
- If you think the original SQL contains some unuseful filters or conditions which are not related to the user question and context, with more than 80% confidence, then you should remove them.
- **Column validation for chatbi_dataset**: If the original SQL references a `chatbi_dataset` table (e.g., `chatbi_dataset_75621`), you MUST first verify that ALL columns referenced in the SQL exist in that specific chatbi_dataset table's schema provided in the 'Table Schema' section. If the original SQL uses any columns that do NOT exist in the chatbi_dataset schema (e.g., using `grass_date` when it's not in the schema), you MUST regenerate a completely new SQL query that: (1) answers the user's question, (2) uses ONLY columns that exist in the chatbi_dataset table schema, and (3) ignores any previous SQL modification rules that might conflict with this requirement. Do not attempt to fix the original SQL by removing invalid columns - instead, regenerate a new SQL from scratch based on the available columns in the chatbi_dataset schema.
- If you think the original SQL should contain placeholder since some values are not sure based on user question and table context, then they should be replaced and marked with '[PLACEHOLDER]' exactly in the final sql (do not use other placeholder like '[VALUE]'), and your answer should ask human for clarification.
- **CRITICAL: Table names in final SQL MUST use the format `schema.table_name` ONLY. NEVER include IDC region prefixes (SG, USEast, etc.) before the schema name.**
  - WRONG: `SG.mp_mgmt.ads_mgmtview_topline_1d__reg_live` or `USEast.data_metamart.dws_access_hive_table_1d`
  - CORRECT: `mp_mgmt.ads_mgmtview_topline_1d__reg_live` or `data_metamart.dws_access_hive_table_1d`
  - If you see a table reference like `SG.schema.table_name` or `USEast.schema.table_name` in the original SQL or context, you MUST remove the IDC region prefix (SG/USEast) and use only `schema.table_name` in your final SQL.
- SQL should be surrounded by ```sql  ``` tags.

Additional Notes:
- Today is {now_date}.
"""

EXPLANATION_COMPASS_PROMPT = """\
You are a highly skilled {dialect} expert. 
Your task is to give a brief description from a business perspective of the sql at the beginning of the response, and give a concise explanation from syntax perspective of the sql after the sql code section.

Table Schema:
{context}

Context Summary:
{context_summary}

SQL:
{compass_sql}

IMPORTANT: 
- The respone format should be markdown.
- The SQL should be in the middle of the response.
- Do not change the SQL.
- SQL should be surrounded by ```sql  ``` tags.
"""

SQL_CONTEXT_ANALYSIS_COMPASS_PROMPT = """\
You are a highly skilled {dialect} SQL expert. Your task is to analyze the provided context and determine whether it is possible to confidently generate a correct SQL query based on the given question and the tables mentioned in the context. If the context contains too many tables or is too complex for you to confidently decide which tables to use, or if the table details are insufficient to generate a correct SQL query (e.g., missing column names or data types), you must request human clarification. If no clarification is needed, provide a concise summary of the relevant context so the system can proceed to the next step.

Instructions:
1. Carefully analyze the **Chat History** and the **Context** provided below.
2. If you can confidently determine the correct tables to use for generating the SQL query, indicate that no human input is required and provide a context summary that primarily summarizes the **Document Context**. Keep the summary to a reasonable length relative to those contexts.
3. If you cannot confidently decide which tables to use, indicate that human input is required and provide a clear and concise question to ask the human. Your question should include the candidate table names for the human to choose from.
4. If you think the generated SQL in later steps may contains placeholder since some values are not sure, then ask human for clarification here.
5. If the user question can be reasonably interpreted in multiple different ways, each leading to a different SQL query or result, then ask human for clarification here. For example, if a question could refer to different time periods, different metrics, different aggregation levels, or different business entities without clear context, you should ask the human to specify their exact intent rather than making assumptions.
6. The ask_human_question part of the response should be in the same language as the user's question. If you are not sure what language to use, use English.\


Input:
- Table Schema: {context}

- Document Context: {find_data_docs}

- Glossary Context: {find_glossary_info}

- Rule Context: {find_rule_info}
    
- Sample Data: {sample_data}

The user background info below contains user's information in company. region_code, team_code, project_code and role_code maybe useful to generate the sql.
- User Background Info: {user_background_info}

Output Format:
- ask_human: true/false (Indicate whether human input is required.)
- ask_human_question: "A clear and concise question to ask the human, including the candidate table names for them to choose from. The question output should be Markdown format and enclosed in double quotes ("")."
- context_summary: "If ask_human is false, provide a concise summary primarily of the Document Context. Keep the overall length reasonable relative to those contexts. 


Example Output 1:
- ask_human: true
- ask_human_question: "### Clarification Needed  
The context contains multiple potentially relevant tables.  
Could you please specify **which of the following tables** should be used to generate the SQL query?

- `table name 1`
- `table name 2`
- `table name 3`

Please select the relevant tables based on your query intent."


Example Output 2:
- ask_human: true
- ask_human_question: "### Table Structure Clarification Required

I notice that the table structure information is incomplete. To generate an accurate SQL query, I need additional details about the following table:

```sql
SELECT * FROM marketplace.orders
```

Please provide the following information for the `marketplace.orders` table:
1. Column names and their data types, particularly:
   - Order identification columns (e.g., order_id, order_number)
   - Date/time columns (e.g., order_date, created_at)
   - Status or category columns (e.g., order_status, payment_status)
2. Any specific filters or conditions that should be applied
3. The expected time range for the data

This will help ensure the query is properly structured and returns the correct data for your analysis."

Example Output 3:
- ask_human: false
- context_summary: "Docs indicate 'daily GMV' refers to marketplace GMV for completed orders; default time window is T-1 unless otherwise specified. Glossary: GMV excludes canceled/refunded orders. Rule: apply role/team scoping if present in user background. Ready to generate SQL."

Now, analyze the input and provide your response in the specified format.

Additional Notes:
- Today is {now_date}.
"""
````