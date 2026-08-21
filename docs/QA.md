# QA

围绕 `agent-platform-java` 的架构答疑。所有答案对应仓库里可运行的代码，
凡是项目里**没做**的部分都会明确标注，并说明补上要改哪里。

---

# 第一部分 · 问题清单

1. 主 Agent 如何做并发？
2. 熔断机制怎么做？
3. 上下文压缩怎么做？
4. MCP、Skill、Agent 具体各自用来干什么？举一个业务上的例子详细说明。
5. 记忆是怎么做的？harness 怎么做？
6. 主 Agent 是怎么做路由的？
7. 关键信息如何不被丢失？
8. 子 Agent 依赖主 Agent 的上下文，它的上下文如何与主 Agent 隔离？
9. Agent 如何保证权限？统一鉴权怎么做？错误码怎么写的？
10. MCP 工具如何进行写操作？是否允许写操作？
11. MCP 调用时，对端是 gRPC 就走 gRPC、是 HTTP 就走 HTTP，具体怎么调用的？

---
---

# 第二部分 · 逐题解答

---

## Q1. 主 Agent 如何做并发？

### 先说结论

**编排内部是串行的，没有并行扇出。** 并发只存在于两个地方：请求级隔离和工具超时隔离。

这是一个需要说清楚的边界 —— 项目里 `AgentRuntime` 的主循环、Skill 的步骤执行、
子 Agent 的多步执行，全部是顺序执行，不存在同时发起多个工具调用的路径。

### 实际存在的三层并发

**① 请求级：虚拟线程**

```yaml
# application.yml
spring:
  threads:
    virtual:
      enabled: true
```

SSE 是长连接，一个对话可能挂几十秒。如果用平台线程，Tomcat 默认 200 个工作线程
很快被占满。虚拟线程让编排跑在自己的载体上，Tomcat 线程立刻释放：

```java
// service/ChatService.java
ResponseBodyEmitter emitter = new ResponseBodyEmitter(300_000L);
Thread.ofVirtual().start(() -> runTurn(emitter, sessionId, message, identity, null));
return emitter;
```

这里有个必须手动处理的坑 —— **ThreadLocal 不跨线程**：

```java
private void runTurn(...) {
    IdentityContext.set(identity);   // 必须重建，否则 @RequiresScope 切面取不到身份直接拒绝
    try { ... } finally {
        IdentityContext.clear();
        TraceContext.clear();
    }
}
```

**② 工具级：超时隔离**

每次工具调用被丢到独立的虚拟线程池，主链路只等 `Future.get(timeout)`：

```java
// aspect/ResilienceAspect.java
private final ExecutorService timeoutPool =
        Executors.newCachedThreadPool(Thread.ofVirtual().name("tool-", 0).factory());

Future<Object> future = timeoutPool.submit(() -> { ... });
try {
    return future.get(config.toolTimeoutSeconds(), TimeUnit.SECONDS);   // 默认 30s
} catch (TimeoutException e) {
    future.cancel(true);
    throw new TimeoutException("调用超时（>" + config.toolTimeoutSeconds() + "s）");
}
```

作用不是提速，是**保证一个卡死的下游不会挂住整条编排链路**。没有这层，
一个不返回的 HTTP 请求会让这次对话永远悬着，SSE 连接也不会关闭。

**③ 编排级：无并行**

```java
// agent/AgentRuntime.java —— 主循环严格串行
while (state.iterations < config.maxIterations()) {
    plan = route(request, context, candidates, state, emit);
    state.iterations++;
    if (!plan.needsExecution()) break;
    execute(plan, request, state, emit);      // 一次只执行一步
    ...
}
```

Skill 的多步同理，按注册顺序逐步执行；子 Agent 在自己的 budget 内也是逐步执行。

### 为什么串行是当前的合理选择

不是偷懒，有三个具体原因：

**① 步骤之间有真实的数据依赖。** `customer_360` 第二步的入参就是第一步的产物：

```json
{ "id": "orders", "tool": "crm.get_orders",
  "input_map": { "customer_id": "$steps.search.data.customers.0.id" } }
```

`$steps.search.data.customers.0.id` 是一条显式依赖边 —— 客户号得先查出来才能查订单，
这两步天然无法并行。

**② 写操作要审批。** 高风险工具执行前会中断整条链路等人工审批
（`state.status = "waiting_approval"` 后直接 return）。并行执行时"中断"的语义会变得混乱：
已经并发出去的其它调用是取消还是继续？

**③ 串行才可解释。** 前端轨迹面板逐条渲染编排过程，Trace 的 span 有 `sequence` 字段。
并行执行的时间线对排查问题反而是负担。

### 要做并行需要改什么

Skill 的 `steps` **已经是一张显式依赖图**，这是并行化最省事的入口：

1. 解析每个 step 的 `input_map` 中的 `$steps.<id>.*` 引用，建立依赖边
2. 拓扑排序分层，同一层的步骤互不依赖
3. 同层用 `StructuredTaskScope`（Java 21）或 `CompletableFuture.allOf` 并发执行

约束条件（不能一并发了之）：

| 要处理的问题 | 处理方式 |
| --- | --- |
| 写操作并发不安全 | 同层只允许 `readOnly=true` 的工具并发，写操作强制串行 |
| `LoopGuard` 用普通 `HashMap` | 换 `ConcurrentHashMap`，或按分支各持一份 |
| `state.evidence` 是 `ArrayList` | 并发写入需要同步，或各分支收集后合并 |
| 熔断计数会被瞬间打满 | 同层并发数要设上限 |
| Trace span 的 `sequence` 失去线性含义 | 需要引入 parent-span 表达树形结构 |

子 Agent 之间也可以并行（比如同时问 HR 和 ITSM），但当前 `route` 一次只产出一个
`Delegate`，要并行得先把路由的输出从"单个 plan"改成"plan 列表"。

**代码位置**：`agent/AgentRuntime.java:120`（主循环）、`aspect/ResilienceAspect.java:56`
（超时池）、`service/ChatService.java:108`（虚拟线程）

---

## Q2. 熔断机制怎么做？

### 三段式：超时 → 重试 → 熔断

三件事织在同一个切面里，业务代码一行都看不到：

```java
// mcp/ToolInvoker.java —— 调用方只写这一行
@Resilient(key = "#server.name")
public Object invoke(McpServerEntity server, String toolName, Map<String, Object> args) {
    return resolve(server.getTransport()).call(server, toolName, args);
}
```

`aspect/ResilienceAspect.java`（`@Order(200)`）负责织入：

```
allow(key)? ──否──> 直接抛 CircuitOpenException（不发请求，不占用下游）
    │是
    ↓
attempt 1..3
    ├── 超时 30s（独立虚拟线程 + Future.get）
    ├── 成功 → circuitBreaker.record(key, true) → 返回
    └── 失败 → isRetryable(error)?
              ├── 否 → record 不计数，立即抛出
              └── 是 → record(key, false)，退避后重试
                       退避 = min(2^(n-1) × 400ms, 4000ms) + random(0..200ms)
                       即 400 / 800 / 1600 ms，带抖动防惊群
```

### 熔断器本体

```java
// mcp/CircuitBreaker.java
public enum State { CLOSED, OPEN, HALF_OPEN }
```

滑动窗口计数，参数在 `application.yml`：

```yaml
resilience:
  tool-timeout-seconds: 30
  tool-max-retries: 3
  circuit-failure-rate: 0.6      # 窗口失败率阈值
  circuit-window-seconds: 300    # 5 分钟滑动窗口
  circuit-probe-seconds: 30      # 冷却 30 秒后半开探活
```

**状态流转：**

```java
// CLOSED → OPEN：样本量够 且 失败率超阈值
if (window.state == State.CLOSED && window.events.size() >= 5
        && failureRate(window) > config.circuitFailureRate()) {
    window.state = State.OPEN;
    window.openedAt = now;
}

// OPEN → HALF_OPEN：冷却时间到（在 state() 里惰性判断，不需要定时器）
if (window.state == State.OPEN
        && System.currentTimeMillis() - window.openedAt >= config.circuitProbeSeconds() * 1000L) {
    window.state = State.HALF_OPEN;
}

// HALF_OPEN → CLOSED / OPEN：一次探活直接定生死
if (window.state == State.HALF_OPEN) {
    window.state = ok ? State.CLOSED : State.OPEN;
    if (!ok) window.openedAt = now;
}
```

三个细节值得注意：

- **`events.size() >= 5` 的最小样本量**：没有这个门槛，服务刚启动时第一次调用失败
  就是 100% 失败率，会被立刻熔断
- **惰性判断半开**：状态转换发生在 `state()` 被调用时，不需要后台定时任务
- **熔断粒度是 Server 而不是 Tool**：key 由 SpEL `#server.name` 求值。
  下游挂了通常是整个服务挂了，按工具熔断会让同一个服务的其它工具继续打过去

### 最关键的边界：什么算"故障"

这是实现时最容易写错的地方 ——

```java
// aspect/ResilienceAspect.java
private boolean isRetryable(Throwable error) {
    if (error instanceof BizException || error instanceof IllegalArgumentException) return false;
    if (error instanceof CircuitOpenException) return false;
    return error instanceof TimeoutException
            || error instanceof IOException
            || error instanceof ConnectException
            || error instanceof SocketTimeoutException
            || error instanceof org.springframework.web.client.RestClientException;
}
```

**只有传输类故障才重试并计入熔断统计。**

反例：用户连续查了 10 个不存在的工号，HR 系统 10 次都正常返回
`{"error": "employee not found"}`。如果这算失败率 100%，整个 HR 服务会被熔断，
后面所有正常查询全部失败 —— 明明下游好好的。

所以业务错误在网关层就被拦下来了，压根不会抛异常给切面：

```java
// mcp/McpGateway.java
if (data instanceof Map<?, ?> map && map.get("error") != null) {
    return ToolResult.fail("business_error", ..., false, ...);   // retryable=false
}
```

### 错误分类总表

| error_type | 触发条件 | 重试 | 计入熔断 |
| --- | --- | --- | --- |
| `timeout` | 超过 30s | ✅ | ✅ |
| `transport_error` | 连接/协议异常 | ✅ | ✅ |
| `business_error` | 下游返回体带 `error` 字段 | ❌ | ❌ |
| `invalid_arguments` | 入参与 schema 不匹配 | ❌ | ❌ |
| `circuit_open` | 熔断已打开 | ❌ | —（本就不发请求） |

### 熔断状态可观测

```java
public synchronized Map<String, Map<String, Object>> snapshot() {
    // { "hr": {"state":"closed","samples":12,"failure_rate":0.083}, ... }
}
```

熔断不是黑盒 —— 状态、样本数、实时失败率都能查到，否则线上出问题时
根本说不清"到底熔了没有"。

**代码位置**：`mcp/CircuitBreaker.java`、`aspect/ResilienceAspect.java`、
`mcp/McpGateway.java`、`config/AgentProperties.java`

---

## Q3. 上下文压缩怎么做？

### 先分层，再压缩

压缩的前提是分层。三层按**稳定性**排序，越靠前越不变：

```java
// agent/ContextAssembler.java
String stable  = Prompts.supervisorSystem();          // L1 整会话不变
String task    = taskLayer(identity, capabilities, summary);  // L2 任务变化时重装配
String session = sessionLayer(turns, evidence);        // L3 每轮变化
String text = stable + "\n\n" + task + "\n\n" + session;
```

| 层 | 内容 | 变化频率 |
| --- | --- | --- |
| **L1 稳定层** | System Prompt、工具协议、行为约束 | 整会话不变 |
| **L2 任务层** | 身份与 scope、当前可见能力清单、历史摘要 | 任务切换时 |
| **L3 会话层** | 最近对话、工具执行证据 | 每轮 |

**为什么按这个顺序排：** 前缀不变才能命中 prefix cache。如果把每轮都变的对话
放在最前面，整个 prompt 的缓存每轮都会失效。这也是 `Prompts` 用文本块写成
编译期常量的原因 —— 保证同一份字节序列。

### 触发条件

```java
if (tokens > config.contextWindowTokens() * config.compressTriggerRatio()) {
    Compression compression = compress(turns, summary);
    ...
}
```

即 `180000 × 0.7 = 126000` tokens 触发。留 30% 余量是因为：压缩本身要调一次 LLM，
而且压缩后还要装下本轮的新内容。等到撑满再压就来不及了。

token 估算是轻量的，不调 tokenizer：

```java
public static int estimateTokens(String text) {
    long cjk = CJK.matcher(text).results().count();
    long rest = text.length() - cjk;
    return (int) (cjk + Math.max(1, rest / 4));   // 中文 1 字≈1 token，其余 4 字符≈1 token
}
```

### 压缩策略：保留头尾，压缩中段

```java
private static final int HEAD_KEEP = 2;
private static final int TAIL_KEEP = 6;

List<Turn> head   = turns.subList(0, HEAD_KEEP);
List<Turn> tail   = turns.subList(turns.size() - TAIL_KEEP, turns.size());
List<Turn> middle = turns.subList(HEAD_KEEP, turns.size() - TAIL_KEEP);

String summary = summarize(middleText, existingSummary);

List<Turn> kept = new ArrayList<>(head);
kept.addAll(tail);
```

**为什么是头尾而不是只留最近的：**

- **头 2 轮**：用户的原始诉求在这里。丢了它，后面越聊越偏
- **尾 6 轮**：当前正在推进的事情，直接影响下一步决策
- **中段**：过程性内容，压成结构化摘要即可

`turns.size() <= 8` 时直接不压缩 —— 压缩本身有成本（一次 LLM 调用），
短会话不值得。

### 摘要的硬约束

压缩最大的风险是丢关键信息，所以摘要 prompt 里把"必须保留什么"写死：

```java
private static final String SUMMARY_SYSTEM = """
        你是会话压缩器。把中段对话压缩为结构化摘要，严格保留：
        1) 用户的原始诉求与后续变更；2) 已完成的步骤及其结果；3) 尚未解决的问题；
        4) 关键工具结果（编号、金额、日期、状态）；5) 审批状态。
        丢弃寒暄与重复内容。用中文，不超过 400 字，使用分条列表。
        """;
```

第 4 条和第 5 条是业务上最要命的：工单号丢了后面就接不上，
审批状态丢了会重复发起审批。

### 压缩不能依赖一个会挂的服务

LLM 不可用时退化为确定性摘要 —— 用正则锚定业务实体：

```java
private String heuristicSummary(String middleText, String existingSummary) {
    Pattern keyLine = Pattern.compile("(E\\d{4}|INC-|RB-|C-\\d{3}|SO-|已提交|已创建|失败|待审批|金额|工单)");
    for (String line : middleText.split("\n")) {
        if (kept >= 12) break;
        if (keyLine.matcher(line).find()) { body.append("- ").append(line.trim()).append('\n'); kept++; }
    }
    ...
}
```

质量不如 LLM 摘要，但**保证带业务实体的行一定活下来**。
如果压缩依赖 LLM 而 LLM 挂了，结果是整个会话不可用 —— 这个代价比摘要质量下降大得多。

### 摘要会持久化

```java
// service/ChatService.java
if (result.summary() != null && !result.summary().isBlank()) {
    session.setSummary(result.summary());
}
```

下一轮从 `session.getSummary()` 取出，作为 L2 的一部分重新注入。所以压缩是**增量**的：
第二次压缩时把已有摘要和新的中段一起送进去，而不是从头再来。

**代码位置**：`agent/ContextAssembler.java`、`service/ChatService.java:262`

---

## Q4. MCP、Skill、Agent 具体各自用来干什么？举一个业务例子

### 一句话定位

| 层 | 是什么 | 类比 | 谁来写 |
| --- | --- | --- | --- |
| **MCP 工具** | 原子能力，一个动词 | 一个 API 接口 | 各业务系统的开发 |
| **Skill** | 固化的 SOP，步骤确定的流程 | 一份作业指导书 | 业务专家 / 平台运营 |
| **子 Agent** | 领域专家，需要判断的场景 | 一个部门的接线员 | 平台 + 领域负责人 |

三者的关系不是并列的，是**由下往上组合**的：
工具是砖，Skill 是预制墙板，Agent 是知道该砌哪面墙的工头。

### 完整业务例子：客户经理的日常

假设一家制造企业，客户经理小王每天要处理这些事。看这三层各自怎么发挥作用。

---

#### 场景一：「东京精密制造这个客户的订单总额是多少？」→ 走 Skill

这个问题看起来是一句话，但底层需要**两次调用**：

1. 客户名是中文，业务系统里的主键是 `C-001` —— 得先按关键词搜出客户号
2. 拿客户号去查订单，再把金额加总

**如果只有 MCP 工具**，模型需要自己想明白："先搜客户，从返回结果里挑出 id，
再拿这个 id 查订单"。这个推理每次都要做一遍，而且可能出错 —— 比如搜出 3 个客户时挑错了那个。

**做成 Skill 之后**，这个流程被固化下来：

```json
{
  "name": "customer_360",
  "display_name": "客户 360 视图",
  "input_schema": {
    "type": "object",
    "properties": { "keyword": { "type": "string" } },
    "required": ["keyword"]
  },
  "steps": [
    { "id": "search", "type": "tool", "tool": "crm.search_customer",
      "input_map": { "keyword": "$input.keyword" } },
    { "id": "orders", "type": "tool", "tool": "crm.get_orders",
      "input_map": { "customer_id": "$steps.search.data.customers.0.id" } }
  ],
  "output_template": "客户 {{$steps.search.data.customers.0.name}} 累计订单金额 {{$steps.orders.data.total_amount}}。"
}
```

关键是 `$steps.search.data.customers.0.id` 这一句 —— **数据依赖被写成了配置**，
不再依赖模型每次现推。带来三个直接好处：

- **确定性**：同样的问题，执行路径 100% 一致，不会这次对下次错
- **可测试**：`POST /api/skills/{id}/dry-run` 可以真实试跑，注册时就验证编排通不通
- **省 token**：模型只需要决定"用不用 customer_360"，不需要在上下文里塞两个工具的 schema 再自己串

**什么时候该做成 Skill：步骤固定、不需要判断、会被反复使用。**

---

#### 场景二：「E1002 的域账号密码重置一下」→ 走子 Agent

这件事表面上只要调一个 `itsm.reset_password`，但实际处理时要考虑：

- 这个工号存在吗？
- 是不是应该先查一下有没有相关工单？
- 重置是写操作，还是高风险的 —— 要不要走审批？
- 如果 AD 域控返回失败，是建个工单让人工处理，还是直接报错？

**这些判断没法写成固定步骤**，因为它取决于中间结果。这正是子 Agent 的用武之地：

```json
{
  "name": "it_support_agent",
  "display_name": "IT 支持 Agent",
  "description": "负责 IT 服务台：知识库检索、工单查询与创建、账号密码重置。输入为问题描述或工单号，输出为处理结论或工单回执。",
  "business_domain": "it",
  "system_prompt": "你是 IT 支持域业务 Agent。先检索知识库，能自助解决的直接给方案；需要人工介入的创建工单。涉及账号重置等写操作必须确认工号。",
  "tool_allowlist": [
    "itsm.search_kb", "itsm.get_ticket", "itsm.list_tickets",
    "itsm.create_ticket", "itsm.update_ticket", "itsm.reset_password"
  ],
  "required_scopes": ["it:read"],
  "max_iterations": 6,
  "positive_examples": ["VPN 连不上", "帮我重置域账号密码", "工单 INC-20260801 什么状态"],
  "negative_examples": ["我还剩多少年假", "报销标准是多少"]
}
```

它拿到任务后在自己的 6 步预算内自行决定调什么、调几次。实际跑出来的轨迹：

```
路由决策 → 分发子 Agent · it_support_agent    置信度 0.95
分发子 Agent · IT 支持 Agent                  工具边界 6 个 · 预算 6 步
⏸ 触发人工审批  itsm.reset_password
   风险原因：`itsm.reset_password` 为写操作/高风险工具，执行后将变更下游业务系统数据。
   影响范围：影响系统：ITSM 服务台；作用对象：employee_id=E1002
   → 人工点「批准并继续执行」
✓ 审批放行，恢复执行
调用 itsm.reset_password  { "employee_id": "E1002" }
✓ 16ms  {"status":"已重置","temp_password_sent_to":"like@example.com","expires_in_minutes":30}
```

**什么时候该做成子 Agent：需要根据中间结果做判断、步骤数不固定、有领域知识。**

---

#### 场景三：「今天天气怎么样？」→ 三层都不走

平台里没有任何天气能力。规则引擎给全部 8 个候选能力打分，最高分 < 0.2 阈值，
判定为无匹配能力，返回澄清：

> 我没有找到可以直接处理该请求的内部能力。你可以补充更多信息（例如工号、工单号、客户名），或换一种说法再试一次。

**不硬编答案，也不硬调工具** —— 这本身是设计的一部分。

---

### 为什么必须是三层，不能少

**假如只有 MCP 工具（没有 Skill 和 Agent）：**

平台有 16 个工具。每次对话要把 16 个工具的完整 schema 塞进 prompt，
模型在 16 个选项里挑，还要自己串联多步调用。工具涨到 150 个时，
光工具描述就吃掉几万 token，选错率急剧上升。

**假如只有 Agent（没有 Skill）：**

`customer_360` 这种固定流程每次都靠模型现推，同一个问题今天走两步、明天走三步，
结果不可复现，出了问题没法定位是模型的锅还是数据的锅。

**假如只有 Skill（没有 Agent）：**

所有分支都得预先枚举。「VPN 连不上」这种问题的处理路径依赖知识库检索结果，
根本没法穷举成固定步骤。

**三层组合起来，才有能力收敛的效果：**

```
GET /api/metrics/capabilities
{
  "total_tools": 16,
  "supervisor_visible": 8,        // 4 子 Agent + 4 Skill，工具 0 个
  "convergence_rate": 0.5
}
```

16 个工具被 Skill 和子 Agent 全部"认领"，主 Agent 单次请求只需要在
**8 个高层能力**里做选择，一个原始工具都不用看。工具数量继续增长时，
主 Agent 的候选集不会跟着膨胀 —— 这是这套结构真正的价值。

### 决策表：新需求该做成什么

| 判断 | 结论 |
| --- | --- |
| 是一次原子的系统操作（一个 API） | **MCP 工具** |
| 步骤固定、有明确数据依赖、会反复用 | **Skill** |
| 需要按中间结果判断、步骤数不定、有领域知识 | **子 Agent** |
| 跨多个业务域 | 主 Agent 编排多个子 Agent |

**代码位置**：`mcp/builtin/`（工具）、`agent/SkillTemplates.java` + `service/SkillService.java`（Skill）、
`service/SubAgentService.java` + `agent/AgentRuntime.java:432`（子 Agent）、
`agent/CapabilityRegistry.java`（收敛）

---

## Q5. 记忆是怎么做的？harness 怎么做？

### 记忆：四层，作用域各不相同

| 层 | 存在哪 | 生命周期 | 内容 |
| --- | --- | --- | --- |
| **L0 轮内证据** | `State.evidence`（内存） | 单次 turn | 本轮工具调用的结果摘要 |
| **L1 最近对话** | `messages` 表，取最近 20 轮 | 会话内 | user / assistant 原文 |
| **L2 会话摘要** | `chat_sessions.summary` | 会话内，持久 | 压缩后的中段摘要 |
| **L3 平台知识** | 注册中心 + `traces` 表 | 跨会话，永久 | 能力描述、正负样例、历史轨迹 |

**L0 轮内证据** —— 这是最容易被忽略但最关键的一层：

```java
// agent/AgentRuntime.java
private static final class State {
    final List<String> evidence = new ArrayList<>();
    ...
}

// 每次工具调用后追加
subEvidence.add("[%s] `%s` → %s".formatted(agent.getName(),
        entry.get().qualifiedName(), toolResult.summary(400)));
```

它在下一次路由时被重新注入 prompt，让模型知道"已经查到什么了"：

```java
private String buildRoutePrompt(String question, State state) {
    StringBuilder sb = new StringBuilder("用户当前请求：").append(question).append('\n');
    if (!state.evidence.isEmpty()) {
        sb.append("\n本轮已获取的工具执行证据：\n");
        for (int i = 0; i < state.evidence.size(); i++) {
            sb.append(i + 1).append(". ").append(state.evidence.get(i)).append('\n');
        }
        sb.append("\n如果证据已足够回答用户，请选择 answer；否则继续选择下一步执行路径。\n");
    }
    ...
}
```

**L1 最近对话**：

```java
private static final int HISTORY_TURNS = 20;

List<MessageEntity> rows = messageRepository.findBySessionIdAndRoleInOrderByCreatedAtDesc(
        sessionId, List.of("user", "assistant"), PageRequest.of(0, HISTORY_TURNS));
// 倒序查出来再反转 —— 数据库只需要一个降序索引
```

**L2 会话摘要**：见 Q3。压缩产物写回 `session.summary`，下轮作为 L2 注入，是增量累积的。

**L3 平台知识**：这一层容易被漏掉，但它才是平台"越用越准"的地方 ——
Agent 的 `description`、`positive_examples`、`negative_examples` 就是路由的长期记忆。
路由置信度低于阈值时会打点：

```java
emit.accept(AgentEvent.of("guard", "kind", "low_confidence",
        "message", "路由置信度 %.2f 低于阈值 %.2f，已记录用于 description 与 few-shot 优化。"
                .formatted(plan.confidence(), config.routeConfidenceThreshold())));
```

这些记录 + `traces` 表 + 评测集构成一条闭环：低置信度样本 → 补进负样例 → 重跑评测验证。

### 这套记忆没有什么

**没有向量库，没有跨会话语义检索，没有用户长期画像。** 换个会话，
上一个会话里说过的事完全不可见。

要补上的话，最小改动是：把 `session.summary` 和 `traces` 灌进向量库，
在 `ContextAssembler.taskLayer()`（L2）里按当前问题检索 top-k 注入。
选 L2 而不是 L3 是因为 L2 只在任务切换时重装配，不会每轮都破坏 prefix cache。

### harness：模型只决定一步，其余全由外壳保证

harness 指 `AgentRuntime` 这层运行时外壳。它的作用是：
**把一个不可靠的模型，包装成一个行为可预测的系统。**

```
prepare ──> route ──> execute ──> route（带着新证据重新决策）
              │          ├──> finalize（证据够了 / 达上限 / 被护栏阻断）
              │          └──> 挂起（等待人工审批）
              └──> finalize（answer / clarify 无需执行）
```

模型在整个过程里只做一件事：**在给定候选集里选下一步**。其余七件事全是外壳的职责：

**① 状态集中管理**

```java
private static final class State {
    final List<String> evidence = new ArrayList<>();
    final Map<String, String> approvals = new HashMap<>();
    final List<String> routeHistory = new ArrayList<>();
    LoopGuard guard;
    int iterations, toolCalls, toolFailures;
    boolean hadSuccessfulWrite, degraded;
    String finalAnswer;
    String status = "running";
    Map<String, Object> pendingApproval;
}
```

一个对象装完，避免十几个参数在方法之间传来传去。

**② 预算约束** —— 主循环 `max-iterations: 12`，子 Agent 自带 `max_iterations`（默认 6）。
没有预算，一个绕圈的模型能把 token 烧到没有上限。

**③ 收敛条件** —— 主循环有五个出口，缺一个都可能空转：

```java
if (!plan.needsExecution()) break;                    // answer / clarify
if (state.finalAnswer != null || "blocked".equals(state.status)) break;   // 已有结论 / 被护栏阻断
if (state.degraded && !state.evidence.isEmpty()) break;                   // 规则引擎模式，拿到证据即收敛
int size = state.routeHistory.size();
if (size >= 2 && state.routeHistory.get(size - 1)
        .equals(state.routeHistory.get(size - 2))) break;                 // 同一路径连选两次，无进展
// 以及 while 条件本身：iterations < maxIterations
```

第三条是规则引擎模式专用的 —— 规则引擎没有"证据是否充分"的判断力，
不主动收敛就会一直重复选同一个能力。

**④ 三类护栏**：`LoopGuard`（重复调用）、`AnswerVerifier`（防幻觉）、置信度阈值。见 Q7。

**⑤ 中断-恢复**：审批时把执行计划落库，恢复时**从执行节点直接续跑，不重新路由**：

```java
if (request.isResume()) {
    plan = request.resume().plan();
    state.approvals.put(request.resume().signature(), request.resume().decision());
    execute(plan, request, state, emit);        // 跳过 route
}
```

不跳过路由的话，恢复时可能路由到另一个工具上 —— 那就等于审批批的是 A、执行的是 B。

**⑥ 事件流 + 观测**：每一步 `emit` 一个 SSE 事件给前端，同时 `collector.addSpan` 落库。

**⑦ 降级**：`state.degraded = !llmRouter.available()`，LLM 不可用时全链路仍然跑得通。

**⑧ 异常兜底**：

```java
} catch (Exception e) {
    state.status = "failed";
    state.finalAnswer = "执行过程中发生异常：" + e.getMessage();
    return finish(state, null, null, null, request, collector);   // 仍然落 trace
} finally {
    TraceContext.clear();
}
```

失败也要留下完整记录 —— 失败的 trace 恰恰是最需要排查的那些。

**代码位置**：`agent/AgentRuntime.java`、`agent/ContextAssembler.java`、
`service/ChatService.java:229`（历史加载）

---

## Q6. 主 Agent 是怎么做路由的？

### 第一步不是"选"，是"减少选项"

路由质量的上限由候选集决定。主 Agent 拿到的候选集经过四重收窄：

```java
// agent/CapabilityRegistry.java
for (McpToolEntity tool : tools) {
    if (identity.hasScopes(tool.getRequiredScopes())) snapshot.addTool(entry);
    else snapshot.denyTool(entry.qualifiedName());     // ① 权限
}
// ② 租户：agent.tenantId == identity.tenantId || "default"
// ③ 状态：enabled && status == "published"
// ④ 收敛：被 Skill / 子 Agent 认领的工具，不给 Supervisor 直接看
```

第四条是关键。实测 16 个工具全部被认领，主 Agent 的候选集里工具数为 **0**：

```
event: context
data: {"visible_tools":0,"visible_skills":4,"visible_agents":4,"total_tools":16}
```

### 第二步：LLM 优先，规则引擎兜底

```java
// agent/AgentRuntime.java
LlmResult result = llmRouter.route(context.text(), userPrompt, request.question(), candidates, true);
RoutePlan plan = result.plan() != null ? result.plan()
        : ruleEngine.route(request.question(), candidates, true);
```

注意 `llmRouter.route` 自己内部还有一层 primary → fallback 模型切换
（`claude-opus-5` → `claude-sonnet-5`）。所以降级实际上是三级：
**主模型 → 备用模型 → 规则引擎**。

### 第三步：输出是密封类型，不是字符串

```java
// agent/RoutePlan.java
public sealed interface RoutePlan {
    record CallTool(String tool, Map<String, Object> arguments, double confidence, String reason)
            implements RoutePlan {}
    record RunSkill(String skill, Map<String, Object> arguments, double confidence, String reason)
            implements RoutePlan {}
    record Delegate(String agent, double confidence, String reason) implements RoutePlan {}
    record Reply(String message, double confidence, String reason) implements RoutePlan {}
    record Clarify(String message, double confidence, String reason) implements RoutePlan {}
}
```

分发用模式匹配，**编译器保证穷尽**：

```java
switch (plan) {
    case RoutePlan.CallTool call -> executeTool(call, request, state, emit);
    case RoutePlan.RunSkill skill -> executeSkill(skill, request, state, emit);
    case RoutePlan.Delegate delegate -> executeAgent(delegate, request, state, emit);
    case RoutePlan.Reply ignored -> { }
    case RoutePlan.Clarify ignored -> { }
}
```

将来加第六种执行路径而这里忘了处理，是**编译错误**，不是线上一个静默的 default 分支。

### 第四步：置信度阈值 —— 但只对主 Agent 生效

```java
public RoutePlan route(String question, RouteCandidates candidates, boolean enforceConfidence) {
    ...
    if (enforceConfidence && confidence < confidenceThreshold) {   // 0.7
        return new RoutePlan.Clarify(...);
    }
}
```

`enforceConfidence` 只在 Supervisor 调用时为 `true`。原因写在注释里：

> 主 Agent 面对全域能力，低置信度执行风险高，应转澄清；
> 子 Agent 的领域边界已由上层确定，不该被阈值二次拦截。

子 Agent 只有 6 个同域工具，此时"不太确定用哪个"和主 Agent 的"不确定该找谁"
完全不是一个风险级别 —— 用同一个阈值卡会导致子 Agent 什么都不敢干。

### 规则引擎的四因子打分

LLM 不可用时的确定性路由，四个因子都是实测调出来的：

```java
// llm/RuleEngine.java
// ① 词面重合 —— 分母做饱和处理
int denominator = Math.min(Math.max(questionTokens.size(), 4), 12);
double base = (double) overlap.size() / denominator;
if (overlap.size() < 2) return 0;     // 仅单字重合属于噪声

// ② 正负样例
if (正样例整句命中率 > 0.6) base += 0.35;
if (负样例整句命中率 > 0.6) base -= 0.4;

// ③ 实体感知：问题里的 E1001 正好是该能力入参
if (entityMatch) score += 0.2;

// ④ 参数可满足性：必填参数抽不出来 → 腰斩
if (!candidate.requiredArgs().isEmpty() && missingRequired) score *= 0.5;
```

**分母饱和**这一条踩过坑：中文按"单字 + 二元组"切分，一个长问句的 token 数会被
二元组撑得很大，直接除以总数会系统性低估匹配度，导致长问句全都路由不出去。

**参数可满足性**解决的是另一类问题：某个工具描述上"更像"，但必填参数根本抽不出来，
选中了也是白调。降权之后，宁可选一个稍微不那么像但真能跑通的。

分层加权体现"优先高层能力"的策略：

```java
Map<Candidate.Kind, Double> weights = Map.of(
        Candidate.Kind.AGENT, 1.0, Candidate.Kind.SKILL, 1.05, Candidate.Kind.TOOL, 0.95);
```

Skill 权重最高 —— 同等匹配度下，确定性的 SOP 优于让 Agent 自由发挥。

### 第五步：执行后带着证据回到路由

```java
while (state.iterations < config.maxIterations()) {
    plan = route(...);              // 每次都带上 state.evidence 重新决策
    if (!plan.needsExecution()) break;
    execute(plan, ...);
    ...
}
```

这是 ReAct 的核心：路由不是一次性的，而是"决策 → 执行 → 看结果 → 再决策"的循环。
`buildRoutePrompt` 把已有证据拼进 prompt，并明确问模型"够了就选 answer"。

### 实测结果

黄金测试集 10 例，**Agent 选择准确率 100%**：

```
✓ E1001 是哪个部门的？            → agent:hr_agent
✓ 我还剩多少年假？工号 E1001       → skill:employee_leave_brief
✓ VPN 连不上怎么办？              → skill:it_incident_triage
✓ 东京精密制造这个客户的订单总额？   → skill:customer_360
✓ 报销单 RB-2026-0912 什么状态？   → agent:finance_agent
✓ 今天天气怎么样？                → clarify
...
```

注意有个用例最初是错的：「今天天气怎么样？」当时返回 `answer`，
但返回的文案其实是在请用户补充信息。**标签和内容不一致**，
改成 `clarify` 后准确率从 90% 到 100% —— 这不是改分数，是修一个真实的语义 bug。

**代码位置**：`agent/AgentRuntime.java:172`（route）、`agent/RoutePlan.java`、
`llm/RuleEngine.java`、`agent/CapabilityRegistry.java`

---

## Q7. 关键信息如何不被丢失？

六道保险，从"压缩时不丢"一直到"事后可追溯"。

### ① 压缩保留头尾

见 Q3。头 2 轮锁住原始诉求，尾 6 轮锁住当前进展，只压中段。

### ② 摘要 prompt 硬性列出必留项

```
严格保留：1) 用户的原始诉求与后续变更；2) 已完成的步骤及其结果；
3) 尚未解决的问题；4) 关键工具结果（编号、金额、日期、状态）；5) 审批状态。
```

### ③ 降级摘要用正则锚定业务实体

```java
Pattern keyLine = Pattern.compile("(E\\d{4}|INC-|RB-|C-\\d{3}|SO-|已提交|已创建|失败|待审批|金额|工单)");
```

LLM 挂了也保证带工号、工单号、金额、审批状态的行活下来。

### ④ 工具证据独立于对话历史

这一条最重要。`evidence` **不在 `turns` 里**，它在 L3 单独成节：

```java
private String sessionLayer(List<Turn> turns, List<String> evidence) {
    if (turns != null && !turns.isEmpty()) { sb.append("## 最近对话\n"); ... }
    if (evidence != null && !evidence.isEmpty()) {
        sb.append("\n## 已获取的工具执行证据\n");
        for (int i = 0; i < evidence.size(); i++) {
            sb.append(i + 1).append(". ").append(evidence.get(i)).append('\n');
        }
    }
}
```

而 `compress()` 的入参只有 `turns` 和 `summary` —— **压缩根本碰不到 evidence**。
工具查出来的工号、金额、工单号是本轮决策的事实依据，不能被摘要过程稀释。

### ⑤ 防幻觉校验：不许声称没做过的事

```java
// agent/guard/AnswerVerifier.java
public static Verdict verify(String answer, boolean hadSuccessfulWrite, List<String> evidence)
```

模型说"已为你提交请假申请"，但本轮没有任何成功的写操作
（`state.hadSuccessfulWrite == false`）→ 判定为幻觉，用工具原始结果覆盖模型的答复。

这里调过一次：最初的正则太宽，把"已完成以下调用"也误判成声称写成功了。
后来收紧到"写动词 + 业务对象"的组合，并把兜底文案改成中性的
"本轮的工具调用结果如下"，避免自己触发自己。

### ⑥ 压缩的是 prompt，不是审计记录

**这是"不丢"的最终兜底：数据库里一条都不少。**

| 表 | 记了什么 | 会被压缩吗 |
| --- | --- | --- |
| `messages` | 每一条 user / assistant 原文 | ❌ 永久 |
| `traces` | 每个 turn 的 30 个字段 | ❌ 永久 |
| `trace_spans` | 每一步的 input / output / 耗时 / 状态 | ❌ 永久 |
| `approvals` | 审批单、工具名、参数、决策人 | ❌ 永久 |
| L1/L2/L3 上下文 | 送给模型的 prompt | ✅ 超阈值才压 |

压缩只影响**这一次送给模型看多少**。任何时候都能从 `trace_id` 回放出完整过程：

```
GET /api/traces/{traceId}
→ spans: [上下文装配, 路由决策, 工具 hr.get_employee, 工具 hr.get_leave_balance, 结果汇总]
```

助手消息的 `meta` 里挂着 `trace_id`，从对话可以直接跳到全链路。

### ⑦ 附加：审批签名带参数

```java
LoopGuard.signature(tool, args)   // SHA-1(工具名 + TreeMap 规范化后的参数)
```

审批以"工具名 + 参数"为键，**参数一变就要重新审批** ——
防止"批的是查 A，执行的是改 B"。`TreeMap` 规范化保证参数顺序不同不算不同调用。

**代码位置**：`agent/ContextAssembler.java`、`agent/guard/AnswerVerifier.java`、
`agent/guard/LoopGuard.java`、`service/TraceService.java`

---

## Q8. 子 Agent 的上下文如何与主 Agent 隔离？

### 隔离清单

隔离发生在 `AgentRuntime.executeAgent()`，一共六项：

```java
// agent/AgentRuntime.java:432
List<ToolEntry> tools = registry.agentTools(request.snapshot(), agent);

// ① 候选集：只含自己 allowlist 内的工具，没有 Skill，也没有其他 Agent
RouteCandidates candidates = registry.agentCandidates(tools);

// ② 能力快照：全新构造，不是主 Agent 那份
CapabilitySnapshot subSnapshot = CapabilitySnapshot.ofTools(tools, request.snapshot().totalToolCount());

// ③ System Prompt：子 Agent 自己的，不含主 Agent 的编排指令
String system = Prompts.subagentSystem(agent.getSystemPrompt()) + "\n\n" + candidates.describe(25);

// ④ 证据列表：独立的 subEvidence，主 Agent 的 evidence 完全不可见
List<String> subEvidence = new ArrayList<>();

// ⑤ 执行预算：用自己的 maxIterations，不共享主循环计数
int budget = agent.getMaxIterations() <= 0 ? 6 : agent.getMaxIterations();

// ⑥ 置信度：不受阈值二次拦截
LlmResult result = llmRouter.route(system, userPrompt.toString(), request.question(), candidates, false);
//                                                                                              ^^^^^
```

| 项 | 主 Agent | 子 Agent |
| --- | --- | --- |
| System Prompt | `Prompts.supervisorSystem()` | `Prompts.subagentSystem(agent.systemPrompt)` |
| 候选集 | 4 Agent + 4 Skill + 0 工具 | 6 个同域工具，无 Agent 无 Skill |
| 对话历史 | 最近 20 轮 + 摘要 | **完全没有** |
| 证据 | `state.evidence` | `subEvidence`（独立） |
| 预算 | 12 轮 | 6 步 |
| 置信度阈值 | 0.7 强制 | 不强制 |

### 接口很窄：一个字符串进，一串字符串出

**主 → 子，只传原始问题：**

```java
StringBuilder userPrompt = new StringBuilder("待办任务：").append(request.question()).append('\n');
```

不传对话历史，不传会话摘要，不传主 Agent 已有的证据。子 Agent 看不到用户之前聊过什么。

**子 → 主，只回结果摘要：**

```java
subEvidence.add("[%s] `%s` → %s".formatted(agent.getName(),
        entry.get().qualifiedName(), toolResult.summary(400)));
...
state.evidence.addAll(subEvidence);      // 回传的只有这些字符串
```

`summary(400)` 是截断的摘要，**原始工具 payload 不回传**。
一次 `crm.get_orders` 可能返回几百条订单，全塞回主 Agent 的上下文毫无意义。

### 为什么要这么隔离

**① 结构性杜绝二次分发。** 子 Agent 的候选集里根本没有 `Agent` 类型的候选项，
所以它在结构上就不可能再分发给另一个子 Agent。这不是靠 prompt 里写"你不要再分发"，
而是**它连这个选项都看不到**。防止 A→B→A 的无限委派。

**② 越界调用被双重拦截。** 即使模型硬编造一个工具名：

```java
Optional<ToolEntry> entry = subSnapshot.tool(call.tool());
if (entry.isEmpty()) {
    emit.accept(AgentEvent.of("guard", "kind", "out_of_scope_tool",
            "message", "子 Agent %s 请求了 allowlist 之外的工具 %s，已拦截。"
                    .formatted(agent.getName(), call.tool())));
    subEvidence.add("[%s] 尝试使用领域外工具 `%s`，已被工具边界拦截。".formatted(...));
    break;
}
```

一层是候选集里没有（模型看不到），一层是执行前校验（模型瞎编也调不动）。

**③ 上下文污染与成本。** 主 Agent 的上下文里有 4 个 Agent + 4 个 Skill 的描述、
20 轮对话、历史摘要。这些对一个只管 ITSM 的子 Agent 全是噪声，
既增加 token 成本，也稀释它对自己领域工具的注意力。

### 有意共享的部分

隔离不是越彻底越好，这几样**故意**共享：

| 共享项 | 为什么 |
| --- | --- |
| `state.guard`（LoopGuard） | 循环检测要跨层，否则主 Agent 调一次、子 Agent 再调一次同样的工具检测不到 |
| `state.toolCalls / toolFailures` | 指标要统计整个 turn，不是分开算 |
| `state.approvals` | 审批结果对整个 turn 有效，同一个调用不能审两次 |
| `state.degraded` | 降级状态是全局的 |
| `TraceContext` 的 collector | span 要落在同一条 trace 上，否则轨迹断成两截 |
| `request.question()` | 子 Agent 需要知道用户到底问了什么 |

**代码位置**：`agent/AgentRuntime.java:432-537`、`agent/CapabilityRegistry.java`、
`agent/CapabilitySnapshot.java`、`agent/Prompts.java`

---

## Q9. Agent 如何保证权限？统一鉴权？错误码怎么写的？

### 权限：四层递进

核心原则 —— **权限在服务端计算，模型只能看到它有权看到的东西。**

```
① 认证        JwtAuthFilter          没登录 / Token 失效        → 401
② API 授权    @RequiresScope 切面     角色没有接口权限            → 403
③ 能力裁剪    CapabilityRegistry     无权的工具不进候选集         → 模型根本看不到
④ 执行拦截    AgentRuntime           越界工具 / 高风险写操作      → 护栏拦截 / 审批中断
```

第 ③ 层是最关键的。传统做法是"模型选了工具，执行前查一下有没有权限"，
这套是**无权的工具压根不出现在 prompt 里**：

```java
// agent/CapabilityRegistry.java
if (identity.hasScopes(tool.getRequiredScopes())) {
    snapshot.addTool(entry);                    // 进候选集
} else {
    snapshot.denyTool(entry.qualifiedName());   // 记录被拒，用于解释"为什么看不到"
}
```

记录 `deniedTools` 是为了可解释 —— 用户问"为什么它查不了工资"时，
`GET /api/agents/{id}/effective-capabilities` 能直接答出来是被哪条权限拦的。

### 统一鉴权的四个零件

**① 身份解析：`JwtAuthFilter` → ThreadLocal**

```java
// security/IdentityContext.java —— 请求级 ThreadLocal
IdentityContext.set(identity);
```

**② 注入：自定义参数解析器**

```java
@GetMapping("/chat/sessions")
public List<SessionDto> listSessions(@CurrentUser Identity identity) { ... }
```

`CurrentUserArgumentResolver` 从 ThreadLocal 取，取不到直接 401。
Controller 里不出现任何 Token 解析代码。

**③ 校验：AOP 切面，织在 Service 层**

```java
// aspect/ScopeCheckAspect.java
@Before("@annotation(...RequiresScope) || @within(...RequiresScope)")
public void check(JoinPoint joinPoint) {
    Identity identity = IdentityContext.require();
    if (!identity.hasScopes(required)) {
        throw BizException.forbidden("当前角色缺少权限：" + String.join(", ", required));
    }
}
```

**为什么织在 Service 而不是 Controller：** SSE 编排、评测跑批都会绕过 Controller
直接调服务。权限必须卡在业务入口，不是 HTTP 入口。

这里有个 Spring AOP 的坑值得记：切面最初标了 `@Order(Ordered.HIGHEST_PRECEDENCE)`，
结果它排在了 `ExposeInvocationInterceptor` 前面，启动就报
`No MethodInvocation found`。改成 `@Order(10)` 解决 —— 优先级最高不等于越靠前越好，
框架自己的基础设施拦截器必须在最前面。

三个切面的顺序也是设计过的：

```
@Order(10)   ScopeCheckAspect      权限   —— 没权限就不该产生 trace，也不该重试
@Order(100)  TraceAspect           观测
@Order(200)  ResilienceAspect      容错   —— 最靠近业务方法，重试的是真实调用
```

**④ scope 定义：`ScopeRegistry` 两类合一**

```java
private static final Map<String, Set<String>> ROLE_SCOPES = Map.of(
        "admin", Set.of("*"),
        "developer", Set.of("mcp:read", "mcp:write", ..., "hr:read", "hr:write", "fin:write", ...),
        "viewer", Set.of("mcp:read", "skill:read", ..., "hr:read", "it:read", "crm:read", "fin:read"));
```

- **平台管理 scope**（`mcp:write` / `agent:write` / `approval:decide`）：谁能改注册中心
- **业务数据 scope**（`hr:read` / `fin:write` / `it:admin`）：Agent 运行时能碰哪些工具

两类共用一套角色映射，所以"能改配置"和"能调工具"天然一致，
不会出现"能注册工具但调不动"的割裂。

**实测**：viewer 调写接口 → `403 {"detail":"当前角色缺少权限：mcp:write"}`

**凭证从不进模型上下文** —— 下游鉴权由网关注入（`HttpTransport.authHeaders`），
Bearer Token / API Key 存在 Server 配置里，既不出现在 prompt 里，也不出现在工具参数里。

### 错误码：两套，分工明确

**第一套：面向 API 调用方（HTTP）**

```java
// common/ErrorCode.java
public enum ErrorCode {
    BAD_REQUEST(HttpStatus.BAD_REQUEST),          // 400 参数/业务前置校验不过
    UNAUTHORIZED(HttpStatus.UNAUTHORIZED),        // 401 未认证
    FORBIDDEN(HttpStatus.FORBIDDEN),              // 403 scope 不足
    NOT_FOUND(HttpStatus.NOT_FOUND),              // 404 资源不存在
    CONFLICT(HttpStatus.CONFLICT),                // 409 名称重复等冲突
    UPSTREAM_ERROR(HttpStatus.BAD_GATEWAY),       // 502 下游 MCP Server 故障
    INTERNAL(HttpStatus.INTERNAL_SERVER_ERROR);   // 500 兜底
}
```

抛出侧用静态工厂，读起来就是一句话：

```java
throw BizException.conflict("子 Agent 名称已存在：" + request.name());
throw BizException.badRequest("description 至少 20 字，需说明能力范围与输入输出规范");
throw BizException.forbidden("当前角色缺少权限：mcp:write");
```

出口统一：

```java
// common/GlobalExceptionHandler.java
@ExceptionHandler(BizException.class)
public ResponseEntity<Map<String, Object>> handleBiz(BizException ex) {
    return ResponseEntity.status(ex.code().status()).body(Map.of("detail", ex.getMessage()));
}
```

**为什么响应体是 `{"detail": "..."}` 而不是 `{code, message, data}`：**
既有前端要求原样复用，它的 axios 拦截器读的就是 `error.response.data.detail`。
这是真实约束下的取舍 —— **契约优先于个人偏好**。成功响应同理直接返回 DTO，
不做 `{code:0, data:...}` 包装。

**第二套：面向模型和护栏（工具层）**

工具调用失败**不抛异常**，而是返回结构化的 `ToolResult`：

```json
{ "success": false, "error_type": "business_error",
  "error_message": "员工不存在：E9999", "latency_ms": 12, "attempts": 1 }
```

`error_type` 只有 5 个值（见 Q2 表格）。这套错误码的消费者不是人，是：

- **重试逻辑**：`isRetryable()` 据此决定重试还是立即失败
- **熔断器**：据此决定计不计入失败率
- **模型**：`invalid_arguments` 回传给模型自纠参数
- **LoopGuard**：据此判断是不是同一个错误在反复发生

**两套错误码为什么不能合并：** HTTP 状态码面向调用方，语义是"这次请求怎么了"；
`error_type` 面向编排逻辑，语义是"这次失败该怎么处置"。
一个下游 500 对 API 调用方是 502，对编排逻辑是"可重试的 transport_error" —— 两件事。

**代码位置**：`security/`、`aspect/ScopeCheckAspect.java`、`common/ErrorCode.java`、
`common/BizException.java`、`common/GlobalExceptionHandler.java`、`mcp/ToolResult.java`

---

## Q10. MCP 工具如何进行写操作？是否允许写操作？

### 允许，但必须显式声明并走完三道闸

一个只读平台没有业务价值 —— 查得到假期余额但请不了假，那还是个查询系统。
所以写操作必须支持，代价是**在声明、权限、审批三个环节都要显式表态**。

### 第一步：声明

```java
// mcp/builtin/ItsmToolProvider.java
@McpTool(description = "重置员工域账号密码（写操作，高风险）。",
         risk = "high", readOnly = false, scopes = "it:admin")
public Map<String, Object> resetPassword(
        @ToolParam(value = "员工工号", required = true) String employeeId) { ... }
```

注解的默认值是**保守**的：

```java
// annotation/McpTool.java
String risk() default "low";
boolean readOnly() default true;      // 默认只读 —— 想写必须显式声明
```

平台当前的 6 个写工具：

| 工具 | risk | scope |
| --- | --- | --- |
| `hr.submit_leave_request` | high | `hr:write` |
| `itsm.create_ticket` | medium | `it:write` |
| `itsm.update_ticket` | high | `it:write` |
| `itsm.reset_password` | high | `it:admin` |
| `crm.create_task` | medium | `crm:write` |
| `finance.submit_expense` | high | `fin:write` |

注意 `itsm.reset_password` 用的是 `it:admin` 而不是 `it:write` —— 重置密码比建工单危险，
权限粒度要跟着风险走。

### 第二步：三道闸

**闸一 · scope。** `viewer` 只有 `it:read`，`itsm.reset_password` 要 `it:admin`，
所以这个工具**根本不会进入 viewer 的候选集**（Q9 第 ③ 层）。

**闸二 · 人工审批。**

```java
private boolean requiresApproval(ToolEntry entry) {
    return "high".equals(entry.tool().getRiskLevel()) || !entry.tool().isReadOnly();
}
```

判定是 `||` —— **只要是写操作就要审批，哪怕标了 medium**。
`crm.create_task` 风险不高，但它确实往下游写数据，所以同样拦。

触发后整条编排挂起：

```java
state.status = "waiting_approval";
return finish(state, plan, context, candidates, request, collector);
```

前端收到 SSE 的 `approval_required` 事件，渲染出审批卡片：

```
高风险操作待人工审批
工具    itsm.reset_password
参数    {"employee_id":"E1002"}
风险原因 `itsm.reset_password` 为写操作/高风险工具，执行后将变更下游业务系统数据。
影响范围 影响系统：ITSM 服务台；作用对象：employee_id=E1002
[批准并继续执行]  [拒绝]
```

**闸三 · 签名绑定参数。**

```java
LoopGuard.signature(tool, args)    // SHA-1(工具名 + TreeMap 规范化参数)
```

审批以"工具名 + 参数"为键。**参数变了就是一次新审批** ——
杜绝"批的是重置 E1002，执行时改成 E1001"。

### 恢复执行：必须只执行一次

审批通过后从执行节点直接续跑，跳过路由：

```java
if (request.isResume()) {
    plan = request.resume().plan();                     // 用落库的原计划，不重新路由
    state.approvals.put(request.resume().signature(), request.resume().decision());
    execute(plan, request, state, emit);
}
```

这里踩过一个真实的坑：**恢复后工具被执行了两次**。原因是恢复流程走完 `execute`
之后又落回了主循环。修复方式是在 prepare 阶段就设好 `degraded`，
让 `_after_execute` 的收敛条件生效，同时恢复分支直接 `execute` 后进入 finalize，
不回主循环。写操作执行两次意味着重复提交报销单 —— 这类 bug 必须在验收里专门测。

实测完整闭环（浏览器）：

```
调用 itsm.reset_password  { "employee_id": "E1002" }
✓ itsm.reset_password  16ms
{"status":"已重置","temp_password_sent_to":"like@example.com","expires_in_minutes":30,"system":"AD"}
本轮汇总：状态 success · 0 轮 1 次调用 · 21ms
```

工具**只被调用了 1 次**。

### 第三步：事后校验

```java
AnswerVerifier.verify(answer, state.hadSuccessfulWrite, state.evidence)
```

模型说"已为你提交"但 `hadSuccessfulWrite == false` → 判定幻觉，
用工具原始结果覆盖。写操作的幻觉比查询的幻觉严重得多 ——
用户以为报销单交了，其实没有。

### 远程 Server 的写操作怎么判定

`risk_level` / `read_only` 是**平台扩展字段，不是 MCP 标准**。
标准 MCP 的 `tools/list` 只返回 `name` / `description` / `inputSchema`。
对接第三方 Server 拿不到这两个字段时，按写动词启发式推断：

```
create / update / delete / reset / submit / cancel / approve → readOnly=false, risk=high
```

**保守优先**：宁可把一个只读工具误判成写操作（多走一次审批），
也不能把写操作误判成只读（直接执行）。误判的代价不对称。

**代码位置**：`annotation/McpTool.java`、`mcp/builtin/`、`agent/AgentRuntime.java:568`
（passApproval）、`service/ApprovalService.java`、`agent/guard/AnswerVerifier.java`

---

## Q11. 对端是 gRPC 就走 gRPC、是 HTTP 就走 HTTP，怎么调用的？

### 先说清楚现状

**目前实现了 4 种传输，其中没有 gRPC：**

| transport | 实现 | 用途 |
| --- | --- | --- |
| `builtin` | `BuiltinTransport` | 进程内反射调用，零网络开销 |
| `stdio` | `StdioTransport` | 拉起子进程，JSON-RPC over stdin/stdout |
| `streamable_http` | `HttpTransport` | JSON-RPC over HTTP，MCP 官方远程传输 |
| `sse` | `SseTransport` | Server-Sent Events 长连接 |

**gRPC 没有实现。** 但这套结构的设计目标就是让"加一种传输"变成加一个类的事 ——
下面说清楚机制，以及加 gRPC 具体要写什么。

### 机制：策略接口 + IoC 集合注入

**① 定义 SPI**

```java
// mcp/ToolTransport.java
public interface ToolTransport {
    String transport();                                        // 我支持哪种传输
    List<ToolDescriptor> discover(McpServerEntity server) throws Exception;   // 探测工具清单
    Object call(McpServerEntity server, String toolName, Map<String, Object> args) throws Exception;
}
```

**② 容器把所有实现收集起来，构造期建索引**

```java
// mcp/ToolInvoker.java
@Component
public class ToolInvoker {
    private final Map<String, ToolTransport> transports;

    public ToolInvoker(List<ToolTransport> transportBeans) {          // ← 集合注入
        this.transports = transportBeans.stream()
                .collect(Collectors.toMap(ToolTransport::transport, Function.identity()));
    }
```

Spring 会把容器里**所有** `ToolTransport` 类型的 Bean 注入成一个 List。
这里按 `transport()` 建成 Map，就得到了一张策略表。

**③ 分发就是一次查表**

```java
    public ToolTransport resolve(String transport) {
        ToolTransport impl = transports.get(transport);
        if (impl == null) throw BizException.badRequest("不支持的传输方式：" + transport);
        return impl;
    }

    @Resilient(key = "#server.name")
    public Object invoke(McpServerEntity server, String toolName, Map<String, Object> args)
            throws Exception {
        return resolve(server.getTransport()).call(server, toolName, args);
    }
}
```

**整个调用链里没有一个 `if (transport == "http")` 或 `switch`。**
这是 IoC 消除条件分支的典型用法 —— 分支被"注册到容器"这个动作替代了。

### 选哪种传输：注册时决定，存在数据库

```json
POST /api/mcp/servers
{
  "name": "hr",
  "transport": "streamable_http",
  "endpoint": "https://hr-mcp.internal.corp/mcp",
  "auth_type": "bearer",
  "auth_value": "***"
}
```

`McpServerEntity.transport` 这个字段就是策略表的 key。运行时
`resolve(server.getTransport())` 一查就知道走哪个实现。

同一个平台里可以四种传输并存：HR 走 HTTP、代码分析工具走 stdio 子进程、
内置工具走反射 —— 对上层完全透明。`AgentRuntime` 只知道
`gateway.call(server, tool, args)`，不知道底下是什么协议。

### HTTP 传输的实现细节

```java
// mcp/transport/HttpTransport.java
@Override public String transport() { return "streamable_http"; }

@Override
public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
    JsonNode result = rpc(server, JsonRpcSupport.listToolsRequest(requestId.getAndIncrement()));
    return JsonRpcSupport.parseTools(server.getName(), result);
}
```

两个实现上的细节：

```java
// 认证头由传输层注入 —— 凭证不进 prompt，也不进工具参数
.headers(headers -> authHeaders(server).forEach(headers::set))

// Streamable HTTP 可能以 SSE 帧回包，取最后一个 data: 行
private String stripSseFraming(String body) { ... }
```

### 加 gRPC 需要写什么

**一个类，40 行左右，其它全不动：**

```java
// mcp/transport/GrpcTransport.java（尚未实现，这是要写的东西）
@Component                                    // ← 只要标这个，容器自动收进策略表
public class GrpcTransport implements ToolTransport {

    private final Map<String, ManagedChannel> channels = new ConcurrentHashMap<>();

    @Override
    public String transport() { return "grpc"; }        // ← 数据库里 transport="grpc" 就路由到这里

    @Override
    public List<ToolDescriptor> discover(McpServerEntity server) throws Exception {
        var stub = McpServiceGrpc.newBlockingStub(channel(server))
                .withDeadlineAfter(10, TimeUnit.SECONDS)
                .withCallCredentials(credentials(server));      // 认证同样在传输层注入
        ListToolsResponse response = stub.listTools(ListToolsRequest.newBuilder().build());
        return response.getToolsList().stream()
                .map(t -> ToolDescriptor.from(server.getName(), t))   // 归一化成平台描述符
                .toList();
    }

    @Override
    public Object call(McpServerEntity server, String toolName, Map<String, Object> args)
            throws Exception {
        CallToolResponse response = McpServiceGrpc.newBlockingStub(channel(server))
                .withDeadlineAfter(30, TimeUnit.SECONDS)
                .callTool(CallToolRequest.newBuilder()
                        .setName(toolName)
                        .setArgumentsJson(MAPPER.writeValueAsString(args))
                        .build());
        return MAPPER.readValue(response.getResultJson(), Map.class);
    }

    private ManagedChannel channel(McpServerEntity server) {
        return channels.computeIfAbsent(server.getEndpoint(),
                ep -> ManagedChannelBuilder.forTarget(ep).useTransportSecurity().build());
    }
}
```

**加完之后自动获得的东西（一行都不用写）：**

| 能力 | 从哪来 |
| --- | --- |
| 超时 30s | `@Resilient` 在 `ToolInvoker.invoke` 上，gRPC 走的是同一个方法 |
| 指数退避重试 3 次 | 同上 |
| 服务级熔断 | 同上，`#server.name` 求值不关心传输类型 |
| 错误码归一化 | `McpGateway.call` 的 catch 链 |
| 权限校验 | `CapabilityRegistry` 按 `required_scopes` 裁剪，与传输无关 |
| 审批 / 护栏 | `AgentRuntime` 只看 `ToolEntry`，不看传输 |
| Trace / Span | `TraceAspect` 织在同一层 |

需要改动的地方只有两处，都是配置性质的：

1. `pom.xml` 加 gRPC 依赖和 protobuf 插件
2. `McpRegistryService` 校验 `transport` 字段的枚举里加上 `"grpc"`

**`McpGateway`、`ToolInvoker`、`AgentRuntime`、三个切面、前端 —— 一行都不用改。**

### 为什么不用 switch-case

如果分发写成：

```java
switch (server.getTransport()) {
    case "builtin" -> builtinTransport.call(...);
    case "streamable_http" -> httpTransport.call(...);
    // 加 gRPC 要改这里
}
```

每加一种传输就要改这个方法 —— 违反开闭原则，而且这个 switch 会随着协议增多
散布到 `discover` / `call` / 健康检查等多个地方。

策略表的写法把"新增传输"从**修改代码**变成了**注册 Bean**。
`ToolInvoker` 那 30 行代码写完之后就再也不用动了。

**代码位置**：`mcp/ToolTransport.java`、`mcp/ToolInvoker.java`、`mcp/transport/`、
`mcp/McpGateway.java`、`domain/McpServerEntity.java`

---
---

## 附：本文提到的"尚未实现"清单

如实标注，避免把设计意图当成既成事实：

| 项 | 现状 | 补上要动什么 |
| --- | --- | --- |
| 编排内并行扇出 | 无，全链路串行 | Skill steps 拓扑排序分层 + `StructuredTaskScope`；`LoopGuard` 换并发容器；只读工具才允许并发 |
| gRPC 传输 | 无，现有 4 种（builtin/stdio/streamable_http/sse） | 新增一个 `ToolTransport` 实现类，标 `@Component` |
| 跨会话语义记忆 | 无，记忆止于会话内 | `session.summary` + `traces` 灌向量库，在 L2 检索注入 |
| 分布式熔断状态 | 单机 `ConcurrentHashMap` | 多实例部署需换 Redis，否则每个实例各熔各的 |

## 附：问题与代码位置对照

| 问题 | 主要代码 |
| --- | --- |
| Q1 并发 | `agent/AgentRuntime.java:120`、`aspect/ResilienceAspect.java:56`、`service/ChatService.java:108` |
| Q2 熔断 | `mcp/CircuitBreaker.java`、`aspect/ResilienceAspect.java`、`mcp/McpGateway.java` |
| Q3 上下文压缩 | `agent/ContextAssembler.java` |
| Q4 MCP/Skill/Agent | `mcp/builtin/`、`service/SkillService.java`、`service/SubAgentService.java`、`agent/CapabilityRegistry.java` |
| Q5 记忆 / harness | `agent/AgentRuntime.java`、`service/ChatService.java:229` |
| Q6 路由 | `agent/RoutePlan.java`、`llm/RuleEngine.java`、`agent/AgentRuntime.java:172` |
| Q7 关键信息 | `agent/guard/AnswerVerifier.java`、`agent/guard/LoopGuard.java`、`service/TraceService.java` |
| Q8 上下文隔离 | `agent/AgentRuntime.java:432`、`agent/CapabilitySnapshot.java` |
| Q9 权限 / 鉴权 / 错误码 | `security/`、`aspect/ScopeCheckAspect.java`、`common/` |
| Q10 写操作 | `annotation/McpTool.java`、`service/ApprovalService.java` |
| Q11 传输协议 | `mcp/ToolTransport.java`、`mcp/ToolInvoker.java`、`mcp/transport/` |
