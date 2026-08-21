# 平台设计答疑

针对五个问题的逐条说明，全部对应 `agent-platform-java` 里可运行的代码。

---

## 一、RESTful API 是怎么封装的？

封装分五层，每一层只解决一件事。核心思路是：**把横切关注点从业务代码里抽走**，让 Controller 里只剩协议转换。

### 1. 分层结构

```
HTTP 请求
  ↓
JwtAuthFilter                认证：解析 Token → 身份放进 ThreadLocal
  ↓
Controller                   协议转换：绑定参数 → 调服务 → 返回 DTO
  ↓  @CurrentUser 参数解析器自动注入身份
  ↓  @Valid 触发 Bean Validation
Service                      业务逻辑
  ↓  @RequiresScope  → ScopeCheckAspect（授权）
  ↓  @Traced         → TraceAspect（观测）
  ↓  @Resilient      → ResilienceAspect（重试/熔断）
Repository                   Spring Data 动态代理生成实现
```

### 2. Controller 有多薄

一个典型接口只有三行 —— 没有 try-catch，没有 if-role，没有手动解析 Token：

```java
// controller/RegistryController.java
@PostMapping("/mcp/servers")
@ResponseStatus(HttpStatus.CREATED)
public McpServerDto createServer(@Valid @RequestBody McpServerCreateRequest request,
                                 @CurrentUser Identity identity) {
    return mcpService.createServer(request, identity);
}
```

- `@Valid` → 校验失败由 `GlobalExceptionHandler` 转成 400，字段错误自动拼成人话
- `@CurrentUser` → `CurrentUserArgumentResolver` 从 ThreadLocal 取身份，取不到直接 401
- 权限 → 服务层 `@RequiresScope("mcp:write")` 由切面拦截，返回 403
- 业务异常 → `BizException.conflict(...)` 抛出，全局处理器转 409

### 3. 出入参：全部用 record

```java
// dto/RegistryDtos.java
public record McpToolDto(String id, String serverId, String name, ...) {
    public static McpToolDto from(McpToolEntity tool) { ... }   // 转换逻辑贴在 DTO 上
}
```

不可变、无样板 getter、`from()` 静态工厂集中转换。**实体不直接出网** —— 否则一改表结构就破坏 API 契约。

### 4. 命名转换在序列化层，不污染 Java 侧

Java 用 camelCase，JSON 出网自动转 snake_case：

```java
// config/WebConfig.java
@Bean
public Jackson2ObjectMapperBuilderCustomizer snakeCaseCustomizer() {
    return builder -> builder.propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
}
```

`riskLevel` → `risk_level`，一次配置全局生效。

### 5. 错误响应统一为 `{"detail": "..."}`

```java
// common/GlobalExceptionHandler.java
@ExceptionHandler(BizException.class)
public ResponseEntity<Map<String, Object>> handleBiz(BizException ex) {
    return ResponseEntity.status(ex.code().status()).body(Map.of("detail", ex.getMessage()));
}
```

错误码收敛成 `ErrorCode` 枚举（BAD_REQUEST / FORBIDDEN / CONFLICT / UPSTREAM_ERROR …），每个枚举绑定一个 HTTP 状态。

> **为什么是 `detail` 而不是常见的 `{code, message, data}` 包装？**
> 因为前端要求原样复用，它的 axios 拦截器读的就是 `error.response.data.detail`。
> 这是一个真实约束下的取舍：**契约优先于个人偏好**。成功响应同理直接返回 DTO / 数组，
> 不做 `{code:0, data:...}` 包装。

### 6. SSE 接口的特殊处理

对话接口要流式推送编排过程。这里踩了一个坑值得记：

```java
// service/ChatService.java
emitter.send("event: " + event + "\n" + "data: " + payload + "\n\n", MediaType.TEXT_PLAIN);
```

Spring 的 `SseEmitter.event().data(obj)` 输出的是 `data:{...}`（**冒号后没有空格**）。SSE 规范允许省略这个空格，但前端按 `"data: "` 前缀解析。前端不能改，所以改用 `ResponseBodyEmitter` 手写标准帧。

### 7. 用到的语言与框架特性小结

| 特性 | 用在哪 | 解决什么 |
| --- | --- | --- |
| **IoC / 构造注入** | 全部服务 | 依赖显式、可测试、无 `@Autowired` 字段注入 |
| **IoC 集合注入** | `ToolInvoker(List<ToolTransport>)` | 新增传输方式零改网关代码 |
| **AOP** | 三个切面 | 权限 / 观测 / 容错从业务代码剥离 |
| **反射** | `BuiltinToolRegistry` | Java 方法自动变成 MCP 工具 |
| **自定义注解** | `@McpTool` `@RequiresScope` `@Traced` `@Resilient` `@CurrentUser` | 声明式表达意图 |
| **元注解组合** | `@McpToolProvider` 叠加 `@Component` | 一个注解既声明语义又完成 Bean 注册 |
| **SpEL** | `@Resilient(key = "#server.name")` | 一个切面服务任意签名的方法 |
| **密封接口 + 模式匹配** | `RoutePlan` | 执行路径穷尽性由编译器保证 |
| **record** | DTO / 配置 / 值对象 | 不可变 + 零样板 |
| **泛型** | `JsonAttributeConverter<T>` | 一份序列化逻辑服务所有集合字段 |
| **文本块** | `Prompts` | Prompt 保持编译期常量，利于 prefix cache |
| **虚拟线程** | SSE 编排、工具超时控制 | 长连接不占用平台线程 |

---

## 二、MCP 的数据格式（举例）

MCP 走 **JSON-RPC 2.0**。平台内部把所有来源的工具都归一到同一个描述符结构。

### 1. 工具定义（tools/list 的响应）

这是 `hr.get_leave_balance` 的真实数据，**没有一行手写** —— 由反射从 Java 方法签名生成：

```java
// mcp/builtin/HrToolProvider.java —— 业务方只写这些
@McpTool(description = "查询员工年假/病假剩余额度。", scopes = "hr:read")
public Map<String, Object> getLeaveBalance(
        @ToolParam(value = "员工工号", required = true) String employeeId) { ... }
```

平台反射后产出：

```json
{
  "name": "get_leave_balance",
  "description": "查询员工年假/病假剩余额度。",
  "input_schema": {
    "type": "object",
    "properties": {
      "employee_id": { "type": "string", "description": "员工工号" }
    },
    "required": ["employee_id"]
  },
  "risk_level": "low",
  "read_only": true,
  "required_scopes": ["hr:read"]
}
```

转换规则（`mcp/ToolSchemaFactory.java`）：

| 来源 | 目标 |
| --- | --- |
| 方法名 `getLeaveBalance` | 工具名 `get_leave_balance`（camelCase → snake_case） |
| 形参名 `employeeId` | 属性名 `employee_id`（依赖编译期 `-parameters`） |
| 形参类型 `String` / `int` / `double` / `boolean` / `List` | `string` / `integer` / `number` / `boolean` / `array` |
| `@ToolParam(value=...)` | 属性的 `description` |
| `@ToolParam(required=true)` | 进 `required` 数组 |
| `@McpTool(risk/readOnly/scopes)` | 平台治理字段（风险等级 / 是否只读 / 权限） |

> `risk_level`、`read_only`、`required_scopes` 是**平台扩展**，不是 MCP 标准字段。
> 标准 MCP 只有 `name` / `description` / `inputSchema`。平台需要这三项来做审批与权限，
> 所以在归一化时补齐 —— 对接远程 Server 拿不到时，按「写动词」启发式推断（`create/update/delete/reset/submit` → 高风险）。

### 2. 调用请求（tools/call）

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "method": "tools/call",
  "params": {
    "name": "get_leave_balance",
    "arguments": { "employee_id": "E1001" }
  }
}
```

### 3. 调用响应

MCP 的返回是 **content 数组**（可含 text / image / resource），不是裸 JSON：

```json
{
  "jsonrpc": "2.0",
  "id": 2,
  "result": {
    "content": [
      {
        "type": "text",
        "text": "{\"employee_id\":\"E1001\",\"annual\":12.5,\"sick\":10,\"used_annual\":3.5,\"remaining_annual\":9.0}"
      }
    ],
    "isError": false
  }
}
```

平台从 `content[].text` 拼出文本，尝试 `JSON.parse`，失败则包成 `{"text": "..."}`。

### 4. 平台内部的归一化结果

无论来自 builtin 反射、stdio 进程还是远程 HTTP，最终都变成同一个 `ToolResult`：

```json
{
  "success": true,
  "data": { "employee_id": "E1001", "annual": 12.5, "remaining_annual": 9.0 },
  "error_type": "",
  "error_message": "",
  "latency_ms": 12,
  "attempts": 1,
  "server": "hr",
  "tool": "get_leave_balance"
}
```

失败时 `error_type` 收敛成有限枚举 —— 这是容错策略能生效的前提：

| error_type | 含义 | 是否重试 | 是否计入熔断 |
| --- | --- | --- | --- |
| `timeout` | 调用超时 | ✅ | ✅ |
| `transport_error` | 连接/协议错误 | ✅ | ✅ |
| `business_error` | 下游返回 `{"error": ...}`（如「员工不存在」） | ❌ | ❌ |
| `invalid_arguments` | 入参与 schema 不匹配 | ❌ | ❌ |
| `circuit_open` | 服务已熔断 | ❌ | — |

> **业务错误不计入熔断**是关键边界。一次「员工不存在」如果被当作服务故障，
> 反复几次就会把整个 HR 系统熔断掉 —— 这是实现时最容易写错的地方。

---

## 三、Agent 的数据格式（举例）—— 结构化还是非结构化？

**答案：结构化的。** Agent 是一条数据库记录，不是一段 prompt 文本。

这是关键设计决策：如果 Agent 只是「一段 system prompt」，就没法做权限校验、版本管理、能力裁剪和审计。把它结构化之后，注册中心才能对它做治理。

### 1. 子 Agent 的完整数据（`hr_agent` 实例）

```json
{
  "id": "a3f2c1d4e5b64789a012345678901234",
  "name": "hr_agent",
  "display_name": "人事助手 Agent",

  "description": "负责人事域：员工档案与组织架构查询、假期额度查询、请假申请提交。输入为员工工号或姓名，输出为结构化人事信息或申请回执。",
  "business_domain": "hr",

  "system_prompt": "你是人事域业务 Agent。只处理员工信息、组织架构、假期与请假事务。涉及提交请假等写操作时，必须先确认工号与日期完整。",

  "tool_allowlist": [
    "hr.get_employee", "hr.get_leave_balance",
    "hr.list_department", "hr.submit_leave_request"
  ],
  "skill_names": ["employee_leave_brief"],
  "required_scopes": ["hr:read"],

  "positive_examples": ["E1001 是哪个部门的", "帮我提交 8 月 20 到 22 号的年假", "我还剩多少年假"],
  "negative_examples": ["VPN 连不上", "帮我查客户订单", "报销标准是多少"],
  "usage_examples": ["查询 E1001 的部门和上级 → hr.get_employee"],

  "owner": "platform",
  "version": "1.0.0",
  "risk_level": "high",
  "status": "published",
  "enabled": true,
  "tenant_id": "default",
  "max_iterations": 6,
  "tags": ["hr", "人事"],

  "call_count": 12,
  "success_count": 11
}
```

### 2. 每个字段解决什么问题

| 字段 | 用途 | 谁消费 |
| --- | --- | --- |
| `description` | **主 Agent 唯一的路由依据** | 路由决策（LLM + 规则引擎） |
| `positive/negative_examples` | 路由匹配与 few-shot；负样例抑制邻域误路由 | 路由打分 |
| `tool_allowlist` | 工具边界，越界调用直接拦截 | 运行时能力裁剪 |
| `skill_names` | 可执行的标准 SOP | 运行时能力裁剪 |
| `required_scopes` | 该 Agent 本身需要的权限 | 权限过滤 |
| `risk_level` | 是否需要人工审批 | HITL |
| `max_iterations` | 单次委派的执行预算，防失控 | 子 Agent 循环 |
| `tenant_id` | 租户隔离 | 能力可见性 |
| `version` / `owner` | 版本与责任人，可回滚可追责 | 治理 |
| `status` / `enabled` | 灰度与下线 | 能力可见性 |
| `call_count` / `success_count` | 运行指标 | 观测 |

### 3. 结构化带来的三个直接好处

**① 注册时可校验。** description 太短或缺正样例会显著提高误路由率，所以在入口就拦住：

```java
// service/SubAgentService.java
if (description.strip().length() < 20) {
    throw BizException.badRequest("description 至少 20 字，需说明能力范围与输入输出规范…");
}
if (positiveExamples == null || positiveExamples.isEmpty()) {
    throw BizException.badRequest("必须提供至少一条 positive_examples（正样例）…");
}
```

**② 运行时可裁剪。** 结构化字段让「声明的能力」和「实际生效的能力」可以分开计算：

```
GET /api/agents/{id}/effective-capabilities
{
  "declared_tools":  ["hr.get_employee", "hr.get_leave_balance", "hr.list_department", "hr.submit_leave_request"],
  "effective_tools": ["hr.get_employee", "hr.get_leave_balance", "hr.list_department",
                      "hr.submit_leave_request", "itsm.search_kb"],
  "denied_by_permission": [],
  "total_platform_tools": 16,
  "supervisor_visible_tools": 0
}
```

（`itsm.search_kb` 是绑定的 Skill 步骤带进来的；`supervisor_visible_tools: 0` 说明 16 个工具全被子 Agent / Skill 认领，主 Agent 一个都不用直接看。）

**③ 只有非结构化的那一小块，才是 prompt。** `system_prompt` 是唯一的自由文本字段，其余全部是结构化元数据。运行时把结构化字段渲染进 prompt：

```
## 可用业务子 Agent（复杂跨系统流程优先分发）
- `hr_agent`：人事助手 Agent（业务域：hr）—— 负责人事域：员工档案与组织架构查询…
  - 适用示例：E1001 是哪个部门的 / 帮我提交 8 月 20 到 22 号的年假 / 我还剩多少年假
  - 不适用：VPN 连不上 / 帮我查客户订单
```

### 4. Skill 的数据格式（顺带说明）

Skill 是**声明式依赖图**，步骤间用引用语法传参：

```json
{
  "name": "customer_360",
  "input_schema": { "type": "object", "properties": { "keyword": {"type": "string"} }, "required": ["keyword"] },
  "steps": [
    { "id": "search", "type": "tool", "tool": "crm.search_customer",
      "input_map": { "keyword": "$input.keyword" } },
    { "id": "orders", "type": "tool", "tool": "crm.get_orders",
      "input_map": { "customer_id": "$steps.search.data.customers.0.id" } }
  ],
  "output_template": "客户 {{$steps.search.data.customers.0.name}} 累计订单金额 {{$steps.orders.data.total_amount}}。"
}
```

`$steps.search.data.customers.0.id` 就是一条显式的数据依赖边 —— 第二步用第一步查出来的客户号。当前按注册顺序线性执行，但依赖关系是可读可校验的，要并行只需按依赖做拓扑排序。

---

## 四、Agent 如何保证权限？

权限是**四层递进**的，任何一层不过都无法执行。核心原则：**权限在服务端计算，模型只能看到它有权看到的东西**。

### 第 1 层：认证 —— 你是谁

`JwtAuthFilter` 解析 Bearer Token，把身份放进请求级 ThreadLocal：

```java
// security/Identity.java
public record Identity(String id, String username, String displayName,
                       String role, String tenantId, Set<String> scopes) {
    public boolean hasScopes(List<String> required) {
        if (scopes.contains("*")) return true;          // admin 通配
        return scopes.containsAll(required);
    }
}
```

角色 → scope 的映射集中在 `ScopeRegistry`，分两类：

- **平台管理 scope**：`mcp:write` `agent:write` `approval:decide` …（谁能改注册中心）
- **业务数据 scope**：`hr:read` `fin:write` `it:admin` …（Agent 运行时能碰哪些工具）

两类共用一套角色映射，所以「能改配置」和「能调工具」的权限天然一致。

### 第 2 层：API 授权 —— 你能调这个接口吗

声明式，由 AOP 拦截：

```java
@RequiresScope("mcp:write")
public McpServerDto createServer(...) { ... }
```

```java
// aspect/ScopeCheckAspect.java
@Before("@annotation(...RequiresScope) || @within(...RequiresScope)")
public void check(JoinPoint joinPoint) {
    Identity identity = IdentityContext.require();       // 没登录 → 401
    if (!identity.hasScopes(required)) {
        throw BizException.forbidden("当前角色缺少权限：" + String.join(", ", required));
    }
}
```

实测：viewer 调用写接口 → `HTTP 403 {"detail":"当前角色缺少权限：mcp:write"}`

> **为什么放在 Service 而不是 Controller？** 因为内部调用（评测跑批、SSE 编排）
> 也会绕过 Controller 直接调服务，权限必须在业务入口而不是 HTTP 入口。

### 第 3 层：能力裁剪 —— 模型能看见什么

这是最关键的一层。**权限不足的工具根本不会进入 prompt**，模型无从选择：

```java
// agent/CapabilityRegistry.java
for (McpToolEntity tool : tools) {
    ToolEntry entry = new ToolEntry(tool, server);
    if (identity.hasScopes(tool.getRequiredScopes())) {
        snapshot.addTool(entry);          // 进候选集
    } else {
        snapshot.denyTool(entry.qualifiedName());   // 记录被拒，用于解释「为什么看不到」
    }
}
```

同一套逻辑作用于 Skill（`required_scopes`）、子 Agent（`required_scopes` + `tenant_id`）。

再叠加两重收窄：

- **租户隔离**：`agent.tenantId == identity.tenantId || "default"`
- **工具边界**：子 Agent 只拿到自己 allowlist 内的工具，候选集里连别的工具名都没有

```java
// 子 Agent 的候选集只含自己的工具，不放 Skill 与其他 Agent
CapabilitySnapshot subSnapshot = CapabilitySnapshot.ofTools(tools, total);
```

### 第 4 层：执行前拦截 —— 真要动手时再拦一道

即使模型硬编造了一个工具名，执行前仍会被拦：

```java
Optional<ToolEntry> entry = subSnapshot.tool(call.tool());
if (entry.isEmpty()) {
    emit("guard", "out_of_scope_tool", "子 Agent 请求了 allowlist 之外的工具，已拦截");
    break;
}
```

高风险 / 写操作再加一道**人工审批**：

```java
private boolean requiresApproval(ToolEntry entry) {
    return "high".equals(entry.tool().getRiskLevel()) || !entry.tool().isReadOnly();
}
```

审批以 `签名(工具名 + 参数)` 为键，**参数一变就需要重新审批** —— 防止「批了查 A，实际改 B」。

### 一张表总结

| 层 | 拦什么 | 失败表现 | 代码 |
| --- | --- | --- | --- |
| 认证 | 没登录 / Token 失效 | 401 | `JwtAuthFilter` + `IdentityContext.require()` |
| API 授权 | 角色无接口权限 | 403 | `@RequiresScope` + `ScopeCheckAspect` |
| 能力裁剪 | 无权的工具不进候选集 | 模型根本看不到 | `CapabilityRegistry.load()` |
| 执行拦截 | 越界工具 / 高风险写操作 | 护栏拦截 / 审批中断 | `AgentRuntime` |

**凭证从不进模型上下文。** 下游鉴权由网关统一注入（`HttpTransport.authHeaders`），Bearer Token / API Key 存在 Server 配置里，既不出现在 prompt 里，也不出现在工具参数里。

---

## 五、用户发一句「你好」，一次交互记录了哪些数据？

下面是真实执行的完整数据落地过程。「你好」是个典型的**无匹配能力**请求，正好能说明「不执行也照样全量记录」。

### 时序

```
用户输入「你好」
  │
  ├─ 1. 落 messages 表：role=user
  ├─ 2. 能力裁剪 → 装配上下文 → 发 context 事件
  ├─ 3. 路由决策 → 发 route 事件（未匹配到能力 → clarify）
  ├─ 4. 无需执行，直接收敛 → 发 answer 事件
  ├─ 5. 落 messages 表：role=assistant
  ├─ 6. 落 traces 表：1 条
  ├─ 7. 落 trace_spans 表：N 条
  └─ 8. 更新 chat_sessions：turn_count / total_tokens / status
```

### 1. 消息表 `messages`（2 条）

```json
// 用户消息
{ "id": "9c1a…", "session_id": "0c53…", "role": "user", "content": "你好",
  "meta": {}, "created_at": "2026-08-21T10:31:02Z" }

// 助手消息 —— meta 里挂着 trace_id，从对话可直接跳到全链路
{ "id": "7f2b…", "session_id": "0c53…", "role": "assistant",
  "content": "我没有找到可以直接处理该请求的内部能力。你可以补充更多信息（例如工号、工单号、客户名），或换一种说法再试一次。",
  "meta": {
    "trace_id": "3a7e9f…",
    "route": { "route_type": "clarify", "target": "" },
    "tool_calls": 0,
    "latency_ms": 5,
    "degraded": true
  },
  "created_at": "2026-08-21T10:31:02Z" }
```

### 2. Trace 表 `traces`（1 条，一个 turn 一条）

```json
{
  "id": "3a7e9f…",
  "session_id": "0c53…",
  "turn_index": 1,
  "user_id": "1b8c…",
  "tenant_id": "default",

  "question": "你好",
  "answer": "我没有找到可以直接处理该请求的内部能力…",

  "route_type": "clarify",
  "route_target": "",
  "route_confidence": 0.3,
  "route_reason": "规则引擎未匹配到任何能力，转为向用户澄清",

  "visible_tool_count": 8,
  "total_tool_count": 16,

  "prompt_chars": 2824,
  "prompt_tokens": 0,
  "completion_tokens": 0,
  "total_tokens": 0,
  "cache_hit_tokens": 0,

  "latency_ms": 5,
  "iterations": 1,
  "tool_calls": 0,
  "tool_failures": 0,

  "context_compressed": false,
  "model_used": "rule-engine",
  "model_fallback": false,
  "degraded_to_rules": true,

  "status": "success",
  "error_type": "",
  "error_message": "",
  "recovery_action": "",
  "created_at": "2026-08-21T10:31:02Z"
}
```

**每个字段是为了回答一个具体问题：**

| 字段组 | 回答什么问题 |
| --- | --- |
| `route_type` / `target` / `confidence` / `reason` | 为什么走了这条路？置信度够不够？ |
| `visible_tool_count` / `total_tool_count` | 收敛有没有生效？（8/16） |
| `prompt_tokens` / `completion_tokens` / `prompt_chars` | 成本花在哪？上下文占多大？ |
| `iterations` / `tool_calls` / `tool_failures` | 绕了几圈？工具挂没挂？ |
| `context_compressed` | 有没有触发压缩？ |
| `model_used` / `model_fallback` / `degraded_to_rules` | 用的哪个模型？切备用了吗？降级了吗？ |
| `status` / `error_type` / `recovery_action` | 失败在哪一环？做了什么恢复动作？ |
| `latency_ms` | 慢在哪？ |

### 3. Span 表 `trace_spans`（本例 2 条）

Span 是 Trace 的细粒度分解，支持逐步回放：

```json
[
  {
    "sequence": 1,
    "name": "上下文装配",
    "span_type": "context",
    "input":  { "turns": 0 },
    "output": { "tokens": 1411, "compressed": false, "visible_tools": 8 },
    "status": "success",
    "latency_ms": 0
  },
  {
    "sequence": 2,
    "name": "路由决策 → clarify",
    "span_type": "route",
    "input":  { "question": "你好",
                "candidates": { "agents": 4, "skills": 4, "tools": 0 } },
    "output": { "route_type": "clarify", "target": "",
                "confidence": 0.3, "reason": "规则引擎未匹配到任何能力，转为向用户澄清" },
    "status": "success",
    "latency_ms": 0
  }
]
```

**如果问的是「E1001 的年假还剩多少」，Span 会变成 6 条：**

| # | name | span_type | 记录内容 |
| --- | --- | --- | --- |
| 1 | 上下文装配 | `context` | token 数、是否压缩、可见能力数 |
| 2 | 路由决策 → skill | `route` | 选中 `employee_leave_brief`，置信度 0.95 |
| 3 | 工具 hr.get_employee | `tool` | 入参 `{employee_id: E1001}`、结果摘要、耗时 |
| 4 | 工具 hr.get_leave_balance | `tool` | 同上 |
| 5 | 工具 itsm.search_kb | `tool` | 同上（可选步骤） |
| 6 | 结果汇总 | `llm` | 证据条数、是否被防幻觉护栏纠正 |

**高风险请求还会多两类 Span：**

- `approval` —— 审批中断，记录工具名、参数、审批单号
- `guard` —— 护栏拦截，记录 kind（`low_confidence` / `repeated_failure` / `out_of_scope_tool` / `unverified_completion_claim`）

### 4. 会话表 `chat_sessions`（更新）

```json
{
  "id": "0c53…",
  "title": "你好",
  "turn_count": 1,          // +1
  "total_tokens": 0,        // 累加本轮
  "summary": "",            // 触发压缩时才写入
  "status": "active"        // 若中断则为 waiting_approval
}
```

### 5. 工具指标（本例未触发）

有工具调用时，`mcp_tools` 表上会累加：

```json
{ "name": "get_leave_balance", "call_count": 13, "success_count": 12, "total_latency_ms": 1560 }
```

由此算出工具级成功率与平均耗时，喂给 `/api/metrics/tools`。

### 6. 前端实时看到的事件流

同一份数据同时以 SSE 推给前端（下面是「你好」的真实帧）：

```
event: session
data: {"session_id":"0c53…","title":"你好"}

event: context
data: {"compressed":false,"context_tokens":1411,"visible_tools":0,"visible_skills":4,"visible_agents":4,"total_tools":16}

event: status
data: {"phase":"routing","message":"Supervisor 正在识别意图并选择执行路径…"}

event: route
data: {"route_type":"clarify","target":"","confidence":0.3,"reason":"规则引擎未匹配到任何能力，转为向用户澄清","degraded":true,"model":"rule-engine","low_confidence":true}

event: answer
data: {"text":"我没有找到可以直接处理该请求的内部能力…","streamed":false}

event: done
data: {"status":"success","answer":"…","route":{…},"evidence":[],"iterations":1,"tool_calls":0,
       "tool_failures":0,"pending_approval":null,"context_tokens":1411,"compressed":false,
       "visible_tool_count":8,"total_tool_count":16,"latency_ms":5,"prompt_tokens":0,
       "completion_tokens":0,"model_used":"rule-engine","model_fallback":false,"degraded":true,
       "guard":{"distinct_calls":0,"total_calls":0,"repeated_failures":0}}
```

### 7. 「你好」被判定成什么类型？

**`clarify`（澄清追问）**，置信度 0.3。

判定过程（`llm/RuleEngine.java`）：

1. 分词：`{你, 好, 你好}` —— 停用词过滤后剩下的有效 token 极少
2. 对全部 8 个候选能力（4 子 Agent + 4 Skill）打分
3. 最高分 < 0.2 阈值 → 判定为「未匹配到任何内部能力」
4. 返回 `Clarify`，文案是请用户补充信息

**为什么是 clarify 而不是 answer？** 因为返回的文案本身就是在请用户补充信息。让「路径标签」和「实际说的话」保持一致，观测统计和评测口径才不会互相打架 —— 这一点在评测跑批时被发现并修正过（原先标 `answer`，导致黄金测试集的期望值对不上）。

**配置了 API Key 时会怎样？** 走 LLM 路由，模型大概率返回：

```json
{ "route_type": "answer", "target": "", "confidence": 0.9,
  "reason": "问候语，无需调用任何业务能力",
  "message": "你好！我是企业内部智能助手，可以帮你查询人事、IT、客户和财务相关信息…" }
```

此时 `route_type` 记为 `answer`，`model_used` 记为 `claude-opus-5`，`degraded_to_rules` 为 `false`。**记录的字段完全一样** —— 这正是观测设计的目的：无论走哪条路径、用哪个模型，落库的结构不变，因此可以横向对比。

---

## 附：五个问题对应的代码位置

| 问题 | 主要代码 |
| --- | --- |
| REST 封装 | `controller/`、`dto/`、`common/GlobalExceptionHandler`、`config/WebConfig` |
| MCP 数据格式 | `mcp/ToolSchemaFactory`、`mcp/ToolDescriptor`、`mcp/ToolResult`、`mcp/transport/` |
| Agent 数据格式 | `domain/SubAgentEntity`、`dto/RegistryDtos.SubAgentDto`、`service/SubAgentService` |
| 权限保证 | `security/`、`aspect/ScopeCheckAspect`、`agent/CapabilityRegistry`、`agent/AgentRuntime` |
| 一次交互记录 | `observability/`、`service/TraceService`、`service/ChatService` |
