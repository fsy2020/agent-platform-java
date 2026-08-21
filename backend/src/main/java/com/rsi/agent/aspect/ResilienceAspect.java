package com.rsi.agent.aspect;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.rsi.agent.annotation.Resilient;
import com.rsi.agent.common.BizException;
import com.rsi.agent.config.AgentProperties;
import com.rsi.agent.mcp.CircuitBreaker;
import com.rsi.agent.mcp.CircuitOpenException;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

/**
 * 容错切面：超时 → 指数退避重试 → 熔断，三件事一次织入。
 *
 * <p>设计要点：
 * <ul>
 *   <li>熔断分组键用 <b>SpEL</b> 从入参求值（默认 {@code #server.name}），
 *       因此同一个切面能服务任意签名的方法</li>
 *   <li>只有<b>传输类故障</b>才重试并计入熔断统计；业务异常（参数不合法、
 *       实体不存在）立即返回 —— 否则一次「员工不存在」会被放大成三次调用，
 *       还会把整个下游服务拖进熔断</li>
 *   <li>超时用独立线程 + Future.get 实现，保证卡死的下游不会挂住调用方</li>
 * </ul>
 */
@Aspect
@Component
@Order(200)
public class ResilienceAspect {

    private static final Logger log = LoggerFactory.getLogger(ResilienceAspect.class);
    private static final SpelExpressionParser PARSER = new SpelExpressionParser();

    private final CircuitBreaker circuitBreaker;
    private final AgentProperties.Resilience config;
    private final ExecutorService timeoutPool =
            Executors.newCachedThreadPool(Thread.ofVirtual().name("tool-", 0).factory());

    public ResilienceAspect(CircuitBreaker circuitBreaker, AgentProperties properties) {
        this.circuitBreaker = circuitBreaker;
        this.config = properties.resilience();
    }

    @Around("@annotation(resilient)")
    public Object execute(ProceedingJoinPoint joinPoint, Resilient resilient) throws Throwable {
        String key = resolveKey(joinPoint, resilient);

        if (!circuitBreaker.allow(key)) {
            throw new CircuitOpenException(key);
        }

        Throwable last = null;
        for (int attempt = 1; attempt <= config.toolMaxRetries(); attempt++) {
            try {
                Object result = callWithTimeout(joinPoint::proceed);  // 见 ThrowingAction
                circuitBreaker.record(key, true);
                return result;

            } catch (Throwable error) {
                last = error;
                boolean retryable = isRetryable(error);

                if (retryable) {
                    circuitBreaker.record(key, false);
                }
                if (!retryable || attempt == config.toolMaxRetries()) {
                    throw error;
                }

                long backoffMs = Math.min((long) (Math.pow(2, attempt - 1) * 400), 4000)
                        + (long) (Math.random() * 200);
                log.info("调用 {} 第 {} 次失败（{}），{}ms 后重试",
                        key, attempt, error.getClass().getSimpleName(), backoffMs);
                Thread.sleep(backoffMs);
            }
        }
        throw last != null ? last : new IllegalStateException("未知错误");
    }

    /**
     * 超时控制：卡死的下游不能挂住整条编排链路。
     *
     * <p>{@code ProceedingJoinPoint.proceed()} 声明抛 Throwable，而 Callable 只允许 Exception，
     * 因此用自定义函数式接口承接，并在跨线程时包一层再原样解开，保证异常类型不失真。
     */
    private Object callWithTimeout(ThrowingAction action) throws Throwable {
        Future<Object> future = timeoutPool.submit(() -> {
            try {
                return action.run();
            } catch (Throwable t) {
                throw new CrossThreadError(t);
            }
        });
        try {
            return future.get(config.toolTimeoutSeconds(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new TimeoutException("调用超时（>" + config.toolTimeoutSeconds() + "s）");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof CrossThreadError wrapper) throw wrapper.getCause();
            throw cause != null ? cause : e;
        }
    }

    /** 允许抛 Throwable 的动作，用于承接 proceed()。 */
    @FunctionalInterface
    private interface ThrowingAction {
        Object run() throws Throwable;
    }

    /** 仅用于把原始异常搬过线程边界，不参与业务语义。 */
    private static final class CrossThreadError extends RuntimeException {
        CrossThreadError(Throwable cause) { super(cause); }
    }

    /**
     * 错误分类 —— 决定「重试还是立即失败」的唯一依据。
     * 业务异常重试没有意义，只会放大下游压力。
     */
    private boolean isRetryable(Throwable error) {
        if (error instanceof BizException || error instanceof IllegalArgumentException) return false;
        if (error instanceof CircuitOpenException) return false;
        return error instanceof TimeoutException
                || error instanceof IOException
                || error instanceof ConnectException
                || error instanceof SocketTimeoutException
                || error instanceof org.springframework.web.client.RestClientException;
    }

    /** 用 SpEL 从方法入参求出熔断分组键，默认取第一个参数的 name 属性。 */
    private String resolveKey(ProceedingJoinPoint joinPoint, Resilient resilient) {
        String expression = resilient.key().isBlank() ? "#server.name" : resilient.key();
        try {
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            EvaluationContext context = new StandardEvaluationContext();
            String[] names = signature.getParameterNames();
            Object[] values = joinPoint.getArgs();
            for (int i = 0; i < names.length && i < values.length; i++) {
                context.setVariable(names[i], values[i]);
            }
            Object value = PARSER.parseExpression(expression).getValue(context);
            return value == null ? signature.getName() : String.valueOf(value);
        } catch (Exception e) {
            return joinPoint.getSignature().getName();
        }
    }
}
