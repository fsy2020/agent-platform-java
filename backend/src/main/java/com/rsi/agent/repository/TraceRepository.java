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
public interface TraceRepository extends JpaRepository<TraceEntity, String> {

        List<TraceEntity> findAllByOrderByCreatedAtDesc(Pageable pageable);

        @Query("""
               select t from TraceEntity t
               where (:sessionId is null or t.sessionId = :sessionId)
                 and (:status is null or t.status = :status)
                 and (:routeType is null or t.routeType = :routeType)
               order by t.createdAt desc
               """)
        List<TraceEntity> search(@Param("sessionId") String sessionId,
                                 @Param("status") String status,
                                 @Param("routeType") String routeType,
                                 Pageable pageable);
}
