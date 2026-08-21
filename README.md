# RSI Agent Platform · Java Spring 版

企业内部智能体管理平台。前端与 Python 版**完全一致**（原样复用），后端用
**Spring Boot 3.3.5 + Java 21** 重写，刻意用足 IoC / AOP / 反射等语言与框架特性。

- 📄 **[设计答疑文档 docs/DESIGN-QA.md](docs/DESIGN-QA.md)** —— REST 封装 / MCP 数据格式 /
  Agent 数据格式 / 权限保证 / 一次交互记录了什么

## 快速启动

后端（默认 8000 端口，H2 文件库，首次启动自动灌入演示数据）：

```bash
cd backend && mvn spring-boot:run
```

前端（默认 5173 端口，已配置 `/api` 代理到 8000）：

```bash
cd frontend && npm install && npm run dev
```

演示账号：`admin/admin123`（全权）、`ops/ops123`（运维）、`viewer/viewer123`（只读）。

未配置 `AGENT_LLM_API_KEY` 时自动降级到规则引擎，全链路（路由 / Skill 编排 /
子 Agent 委派 / 审批中断 / 观测落库 / 评测）仍完整可跑。

## 能力概览

| 模块 | 内容 |
| --- | --- |
| MCP 网关 | 4 个内置 Server / 16 个工具；支持 builtin / stdio / streamable_http / sse 四种传输 |
| Skill 注册中心 | 声明式多步编排，步骤间 `$steps.x.data.y` 引用传参，支持 dry-run 试运行 |
| 子 Agent 注册中心 | 工具 allowlist + Skill 绑定 + 权限 scope + 执行预算 |
| Supervisor | 能力可见性收敛（16 工具 → 8 可见能力）、置信度阈值、循环护栏、防幻觉校验 |
| 人机协同 | 高风险/写操作中断 → 审批 → 恢复；审批以「工具名+参数签名」为键 |
| 观测 | Trace + Span 全链路落库，SSE 实时推送编排过程 |
| 评测 | 黄金测试集跑批，路由准确率 / 工具成功率 / 时延 |

当前实测：黄金测试集 10 例，**Agent 选择准确率 100%**，工具调用成功率 89%。

## 语言 / 框架特性用在哪

| 特性 | 位置 |
| --- | --- |
| IoC 构造注入 | 全部 Service |
| IoC 集合注入（策略表） | `mcp/ToolInvoker` ← `List<ToolTransport>` |
| `@ConfigurationProperties` + record | `config/AgentProperties` |
| AOP · 权限 | `aspect/ScopeCheckAspect`（`@Order(10)`） |
| AOP · 观测 | `aspect/TraceAspect`（`@Order(100)`） |
| AOP · 容错 | `aspect/ResilienceAspect`（`@Order(200)`，重试 + 熔断 + 超时） |
| 反射 · Schema 生成 | `mcp/ToolSchemaFactory`：方法签名 → MCP JSON Schema |
| 反射 · 工具注册与调用 | `mcp/BuiltinToolRegistry`：扫描 `@McpToolProvider`，按名绑参 + 类型转换 |
| 元注解组合 | `annotation/McpToolProvider`（内含 `@Component`） |
| SpEL | `@Resilient(key = "#server.name")` |
| 密封接口 + 模式匹配 | `agent/RoutePlan` + `AgentRuntime` 的穷尽 switch |
| record | 全部 DTO / 值对象 |
| 泛型 | `domain/JsonAttributeConverter<T>` |
| 文本块 | `agent/Prompts` |
| 虚拟线程 | SSE 编排、工具超时控制 |
| 自定义参数解析器 | `@CurrentUser` ← `CurrentUserArgumentResolver` |

## 目录

```
backend/src/main/java/com/rsi/agent/
├── annotation/     自定义注解（@McpTool @RequiresScope @Traced @Resilient @CurrentUser …）
├── aspect/         三个切面：权限 / 观测 / 容错
├── agent/          Supervisor 编排、能力注册与裁剪、RoutePlan、Prompt
├── mcp/            MCP 网关、反射注册、Schema 生成、四种传输
│   ├── builtin/    内置工具（HR / ITSM / CRM / Finance）—— 普通 Java 方法
│   └── transport/  builtin / stdio / streamable_http / sse
├── llm/            LLM 客户端 + 规则引擎降级
├── security/       身份、JWT、scope 注册表
├── observability/  Trace / Span 上下文
├── domain/         JPA 实体
├── repository/     Spring Data 接口
├── dto/            record DTO
├── service/        业务服务
├── controller/     REST 入口
├── common/         异常与全局处理
└── config/         Web / 应用配置
```
