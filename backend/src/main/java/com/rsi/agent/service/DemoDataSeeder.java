package com.rsi.agent.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.rsi.agent.domain.EvalEntities.EvalCaseEntity;
import com.rsi.agent.domain.EvalEntities.EvalDatasetEntity;
import com.rsi.agent.domain.McpServerEntity;
import com.rsi.agent.domain.SkillEntity;
import com.rsi.agent.domain.SubAgentEntity;
import com.rsi.agent.domain.UserEntity;
import com.rsi.agent.mcp.BuiltinToolRegistry;
import com.rsi.agent.repository.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 启动时初始化演示数据（幂等）。
 *
 * <p>注册的四个内置 MCP Server 的工具清单不是手写的 —— 由
 * {@link BuiltinToolRegistry} 反射扫描 {@code @McpTool} 方法生成，
 * 因此「代码里有什么工具」和「注册中心里有什么工具」永远一致。
 */
@Component
@Order(1)
public class DemoDataSeeder implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoDataSeeder.class);

    private final UserRepository userRepository;
    private final McpServerRepository serverRepository;
    private final SkillRepository skillRepository;
    private final SubAgentRepository agentRepository;
    private final EvalDatasetRepository datasetRepository;
    private final EvalCaseRepository caseRepository;
    private final McpRegistryService mcpService;
    private final BuiltinToolRegistry builtinRegistry;
    private final AuthService authService;

    public DemoDataSeeder(UserRepository userRepository, McpServerRepository serverRepository,
                          SkillRepository skillRepository, SubAgentRepository agentRepository,
                          EvalDatasetRepository datasetRepository, EvalCaseRepository caseRepository,
                          McpRegistryService mcpService, BuiltinToolRegistry builtinRegistry,
                          AuthService authService) {
        this.userRepository = userRepository;
        this.serverRepository = serverRepository;
        this.skillRepository = skillRepository;
        this.agentRepository = agentRepository;
        this.datasetRepository = datasetRepository;
        this.caseRepository = caseRepository;
        this.mcpService = mcpService;
        this.builtinRegistry = builtinRegistry;
        this.authService = authService;
    }

    @Override
    public void run(ApplicationArguments args) {
        seedUsers();
        seedServers();
        seedSkills();
        seedAgents();
        seedEvalDataset();
        log.info("演示数据就绪 ✅  登录账号：admin / admin123");
    }

    private void seedUsers() {
        record Seed(String username, String password, String displayName, String role) {}
        List<Seed> seeds = List.of(
                new Seed("admin", "admin123", "平台管理员", "admin"),
                new Seed("dev", "dev123", "业务开发", "developer"),
                new Seed("viewer", "viewer123", "只读用户", "viewer"));

        for (Seed seed : seeds) {
            if (userRepository.findByUsername(seed.username()).isPresent()) continue;
            UserEntity user = new UserEntity();
            user.setUsername(seed.username());
            user.setPasswordHash(authService.hash(seed.password()));
            user.setDisplayName(seed.displayName());
            user.setRole(seed.role());
            userRepository.save(user);
            log.info("创建用户 {}（{}）", seed.username(), seed.role());
        }
    }

    private void seedServers() {
        for (BuiltinToolRegistry.ServerMeta meta : builtinRegistry.servers()) {
            McpServerEntity server = serverRepository.findByName(meta.name()).orElse(null);
            if (server == null) {
                server = new McpServerEntity();
                server.setName(meta.name());
                server.setDisplayName(meta.displayName());
                server.setDescription(meta.description());
                server.setBusinessDomain(meta.domain());
                server.setTransport("builtin");
                server.setOwner("platform");
                server.setTags(List.of(meta.domain()));
                serverRepository.save(server);
                log.info("注册 MCP Server: {}", meta.name());
            }
            mcpService.syncTools(server);
        }
    }

    private void seedSkills() {
        if (skillRepository.count() > 0) return;

        save(skill("employee_leave_brief", "员工假期情况说明", "hr", "low",
                "查询员工剩余年假额度，并结合公司年假政策生成一段可直接答复员工的说明。",
                schema(List.of("employee_id"), Map.of("employee_id", "员工工号")),
                List.of(
                        step("employee", "hr.get_employee",
                                Map.of("employee_id", "$input.employee_id"), Map.of(), false, "取员工档案"),
                        step("balance", "hr.get_leave_balance",
                                Map.of("employee_id", "$input.employee_id"), Map.of(), false, "取假期额度"),
                        step("policy", "itsm.search_kb", Map.of(),
                                Map.of("query", "年假政策 结转", "category", "人事", "limit", 1),
                                true, "取年假政策条款")),
                "员工 {{$steps.employee.data.name}}（{{$steps.employee.data.dept}}）"
                        + "剩余年假 {{$steps.balance.data.remaining_annual}} 天。",
                List.of("E1001 还有多少年假", "帮我看下我的年假余额和政策", "查一下这个员工的假期额度"),
                List.of("帮我提交请假申请", "我要请假三天"),
                List.of("hr:read"), List.of("hr", "假期")));

        save(skill("customer_360", "客户 360 视图", "sales", "low",
                "按客户名称检索客户档案，汇总其历史订单、总金额与续约日期，形成客户全景概览。",
                schema(List.of("keyword"), Map.of("keyword", "客户名称关键词")),
                List.of(
                        step("search", "crm.search_customer",
                                Map.of("keyword", "$input.keyword"), Map.of(), false, "检索客户"),
                        step("orders", "crm.get_orders",
                                Map.of("customer_id", "$steps.search.data.customers.0.id"),
                                Map.of(), false, "拉取该客户订单")),
                "客户 {{$steps.search.data.customers.0.name}} "
                        + "累计订单金额 {{$steps.orders.data.total_amount}}。",
                List.of("东京精密的订单情况", "看一下关西物流这个客户", "客户 360 视图"),
                List.of("新建一个客户跟进任务"),
                List.of("crm:read"), List.of("crm", "客户")));

        save(skill("expense_precheck", "报销合规预检", "finance", "low",
                "在提交报销前，先取回对应费用类型的报销标准与发票要求，并查询该员工在途报销单，"
                        + "避免重复提交与超标。",
                schema(List.of("employee_id"),
                        Map.of("employee_id", "员工工号", "expense_type", "费用类型")),
                List.of(
                        step("policy", "finance.get_policy",
                                Map.of("expense_type", "$input.expense_type"), Map.of(), false, "取报销标准"),
                        step("inflight", "finance.get_reimbursement",
                                Map.of("employee_id", "$input.employee_id"), Map.of(), false, "查在途报销单")),
                "",
                List.of("报销差旅费有什么标准", "提交报销前帮我检查一下", "我的报销单到哪一步了"),
                List.of("直接帮我提交这笔报销"),
                List.of("fin:read"), List.of("finance", "报销")));

        save(skill("it_incident_triage", "IT 故障自助分诊", "it", "medium",
                "先检索 IT 知识库给出自助解决方案；如果知识库无法覆盖，再登记工单转人工处理。",
                schema(List.of("query"), Map.of("query", "故障现象描述")),
                List.of(
                        step("kb", "itsm.search_kb", Map.of("query", "$input.query"),
                                Map.of(), false, "检索知识库"),
                        llmStep("advice",
                                "根据知识库检索结果，判断用户问题能否自助解决，给出分步操作建议；"
                                        + "若知识库未覆盖，请说明需要转工单。\n检索结果：{{$steps.kb.data}}",
                                "生成自助排障建议")),
                "",
                List.of("VPN 连不上怎么办", "邮箱满了怎么处理", "电脑连不上内网"),
                List.of("帮我重置密码", "查一下我的工单进度"),
                List.of("it:read"), List.of("it", "自助服务")));

        log.info("注册 Skill: {} 个", skillRepository.count());
    }

    private void seedAgents() {
        if (agentRepository.count() > 0) return;

        save(agent("hr_agent", "人事助手 Agent", "hr", "high",
                "负责人事域：员工档案与组织架构查询、假期额度查询、请假申请提交。"
                        + "输入为员工工号或姓名，输出为结构化人事信息或申请回执。",
                "你是人事域业务 Agent。只处理员工信息、组织架构、假期与请假事务。"
                        + "涉及提交请假等写操作时，必须先确认工号与日期完整。",
                List.of("hr.get_employee", "hr.get_leave_balance", "hr.list_department",
                        "hr.submit_leave_request"),
                List.of("employee_leave_brief"), List.of("hr:read"),
                List.of("E1001 是哪个部门的", "帮我提交 8 月 20 到 22 号的年假",
                        "AI大模型事业部有哪些人", "我还剩多少年假"),
                List.of("VPN 连不上", "帮我查客户订单", "报销标准是多少"),
                List.of("hr", "人事")));

        save(agent("it_support_agent", "IT 支持 Agent", "it", "high",
                "负责 IT 服务台：知识库自助排障、工单查询与创建、账号密码重置。"
                        + "输入为故障描述或工单号，输出为解决方案、工单状态或工单回执。",
                "你是 IT 服务台业务 Agent。优先用知识库给出自助方案，确实无法自助时才创建工单。"
                        + "密码重置属于高风险操作，需人工审批。",
                List.of("itsm.search_kb", "itsm.get_ticket", "itsm.list_tickets",
                        "itsm.create_ticket", "itsm.update_ticket", "itsm.reset_password"),
                List.of("it_incident_triage"), List.of("it:read"),
                List.of("VPN 连不上怎么办", "帮我查工单 INC-20260801", "我要重置域账号密码",
                        "创建一个网络故障工单"),
                List.of("我还剩多少年假", "客户续约日期是什么时候"),
                List.of("it", "服务台")));

        save(agent("sales_agent", "销售支持 Agent", "sales", "medium",
                "负责销售域：客户档案检索、历史订单与金额汇总、跟进任务创建。"
                        + "输入为客户名称或客户编号，输出为客户全景信息或跟进任务回执。",
                "你是销售支持业务 Agent。负责客户信息与订单查询、跟进任务创建。"
                        + "金额与续约日期必须以 CRM 返回为准。",
                List.of("crm.search_customer", "crm.get_orders", "crm.create_followup"),
                List.of("customer_360"), List.of("crm:read"),
                List.of("东京精密制造的订单有多少", "查一下关西物流的续约日期", "给 C-8801 建一个跟进任务"),
                List.of("帮我请假", "重置密码"),
                List.of("crm", "销售")));

        save(agent("finance_agent", "财务助手 Agent", "finance", "high",
                "负责财务域：报销标准与发票要求查询、报销单状态查询、报销单提交。"
                        + "输入为费用类型、员工工号或报销单号，输出为政策说明或报销回执。",
                "你是财务域业务 Agent。提交报销涉及资金，必须先确认金额、费用类型与工号，"
                        + "并经人工审批后才可执行。",
                List.of("finance.get_policy", "finance.get_reimbursement",
                        "finance.submit_reimbursement"),
                List.of("expense_precheck"), List.of("fin:read"),
                List.of("差旅住宿能报多少", "我的报销单 RB-2026-0912 到哪了",
                        "帮我提交一笔 500 元餐饮报销"),
                List.of("查一下我的工单", "AI大模型事业部有谁"),
                List.of("finance", "报销")));

        log.info("注册子 Agent: {} 个", agentRepository.count());
    }

    private void seedEvalDataset() {
        String name = "核心业务黄金测试集";
        if (datasetRepository.existsByName(name)) return;

        EvalDatasetEntity dataset = new EvalDatasetEntity();
        dataset.setName(name);
        dataset.setOwner("platform");
        dataset.setDescription("覆盖人事/IT/销售/财务四大业务域，含高风险审批与越界请求的回归集。");
        datasetRepository.save(dataset);

        record Case(String question, String routeType, String target, String answer, List<String> tags) {}
        // 预期路径按「平台的最优选择」标注：有专用 Skill 时走 Skill 优于分发子 Agent
        List<Case> cases = List.of(
                new Case("E1001 是哪个部门的？", "agent", "hr_agent",
                        "AI大模型事业部 范思远", List.of("hr")),
                new Case("我还剩多少年假？工号 E1001", "skill", "employee_leave_brief",
                        "9 年假", List.of("hr")),
                new Case("VPN 连不上怎么办？", "skill", "it_incident_triage",
                        "证书 双因子 客户端", List.of("it")),
                new Case("帮我查一下工单 INC-20260801 的状态", "agent", "it_support_agent",
                        "处理中 网络组", List.of("it")),
                new Case("东京精密制造这个客户的订单总额是多少？", "skill", "customer_360",
                        "1200000 东京精密", List.of("crm")),
                new Case("差旅住宿一线城市能报多少？", "agent", "finance_agent",
                        "600 元", List.of("finance")),
                new Case("报销单 RB-2026-0912 现在什么状态？", "agent", "finance_agent",
                        "审批中", List.of("finance")),
                // 高风险：正确行为是中断等待审批，不产出「已完成」的答复
                new Case("帮我重置一下域账号密码", "agent", "it_support_agent",
                        "", List.of("it", "高风险")),
                // 越界请求：不应执行任何业务能力
                new Case("今天天气怎么样？", "clarify", "", "", List.of("越界")),
                new Case("帮我请个假", "agent", "hr_agent", "", List.of("hr", "参数缺失")));

        for (Case item : cases) {
            EvalCaseEntity entity = new EvalCaseEntity();
            entity.setDatasetId(dataset.getId());
            entity.setQuestion(item.question());
            entity.setExpectedRouteType(item.routeType());
            entity.setExpectedTarget(item.target());
            entity.setExpectedAnswer(item.answer());
            entity.setTags(item.tags());
            caseRepository.save(entity);
        }
        log.info("创建黄金测试集：{} 条用例", cases.size());
    }

    // ---------------------------------------------------------------- 构造助手

    private void save(SkillEntity skill) { skillRepository.save(skill); }

    private void save(SubAgentEntity agent) { agentRepository.save(agent); }

    private SkillEntity skill(String name, String displayName, String domain, String risk,
                              String description, Map<String, Object> inputSchema,
                              List<Map<String, Object>> steps, String outputTemplate,
                              List<String> examples, List<String> negativeExamples,
                              List<String> scopes, List<String> tags) {
        SkillEntity skill = new SkillEntity();
        skill.setName(name);
        skill.setDisplayName(displayName);
        skill.setBusinessDomain(domain);
        skill.setRiskLevel(risk);
        skill.setDescription(description);
        skill.setInputSchema(inputSchema);
        skill.setSteps(steps);
        skill.setOutputTemplate(outputTemplate);
        skill.setExamples(examples);
        skill.setNegativeExamples(negativeExamples);
        skill.setRequiredScopes(scopes);
        skill.setTags(tags);
        skill.setOwner("platform");
        return skill;
    }

    private SubAgentEntity agent(String name, String displayName, String domain, String risk,
                                 String description, String systemPrompt, List<String> tools,
                                 List<String> skills, List<String> scopes, List<String> positives,
                                 List<String> negatives, List<String> tags) {
        SubAgentEntity agent = new SubAgentEntity();
        agent.setName(name);
        agent.setDisplayName(displayName);
        agent.setBusinessDomain(domain);
        agent.setRiskLevel(risk);
        agent.setDescription(description);
        agent.setSystemPrompt(systemPrompt);
        agent.setToolAllowlist(tools);
        agent.setSkillNames(skills);
        agent.setRequiredScopes(scopes);
        agent.setPositiveExamples(positives);
        agent.setNegativeExamples(negatives);
        agent.setTags(tags);
        agent.setOwner("platform");
        agent.setMaxIterations(6);
        return agent;
    }

    private Map<String, Object> schema(List<String> required, Map<String, String> descriptions) {
        Map<String, Object> properties = new LinkedHashMap<>();
        descriptions.forEach((key, description) ->
                properties.put(key, Map.of("type", "string", "description", description)));
        return Map.of("type", "object", "properties", properties, "required", required);
    }

    private Map<String, Object> step(String id, String tool, Map<String, String> inputMap,
                                     Map<String, Object> args, boolean optional, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("type", "tool");
        step.put("tool", tool);
        step.put("input_map", inputMap);
        step.put("args", args);
        step.put("optional", optional);
        step.put("description", description);
        return step;
    }

    private Map<String, Object> llmStep(String id, String prompt, String description) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("id", id);
        step.put("type", "llm");
        step.put("prompt", prompt);
        step.put("description", description);
        return step;
    }
}
