package com.rsi.agent.repository;

import java.util.List;
import java.util.Optional;

import com.rsi.agent.domain.*;
import com.rsi.agent.domain.EvalEntities.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * 仓储接口 —— 没有一行实现。
 *
 * <p>Spring Data 在启动时为它生成动态代理，按方法名解析出 JPQL。
 * 这是容器「按约定生成实现」的典型场景：声明意图，实现由框架反射产出。
 */
public interface McpServerRepository extends JpaRepository<McpServerEntity, String> {

        Optional<McpServerEntity> findByName(String name);
        boolean existsByName(String name);
        List<McpServerEntity> findAllByOrderByCreatedAtAsc();
}
