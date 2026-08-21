package com.rsi.agent.service;

import java.time.Instant;
import java.util.List;

import com.rsi.agent.annotation.RequiresScope;
import com.rsi.agent.common.BizException;
import com.rsi.agent.domain.ApprovalEntity;
import com.rsi.agent.dto.PlatformDtos.ApprovalDecisionRequest;
import com.rsi.agent.dto.PlatformDtos.ApprovalDto;
import com.rsi.agent.repository.ApprovalRepository;
import com.rsi.agent.security.Identity;
import jakarta.transaction.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

/** Human-in-the-Loop 审批。 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepository;

    public ApprovalService(ApprovalRepository approvalRepository) {
        this.approvalRepository = approvalRepository;
    }

    public List<ApprovalDto> list(String status, String sessionId) {
        PageRequest page = PageRequest.of(0, 200);
        List<ApprovalEntity> rows;
        if (sessionId != null && !sessionId.isBlank()) {
            rows = approvalRepository.findBySessionIdOrderByCreatedAtDesc(sessionId);
        } else if (status != null && !status.isBlank()) {
            rows = approvalRepository.findByStatusOrderByCreatedAtDesc(status, page);
        } else {
            rows = approvalRepository.findAllByOrderByCreatedAtDesc(page);
        }
        return rows.stream().map(ApprovalDto::from).toList();
    }

    public ApprovalDto get(String approvalId) {
        return ApprovalDto.from(require(approvalId));
    }

    /**
     * 审批放行/拒绝。
     *
     * <p>只改状态，不在这里恢复执行 —— 恢复由 {@code POST /chat/resume/{id}} 单独触发，
     * 因为恢复是一次完整的（可能再次中断的）编排，语义上不属于「做出决策」。
     */
    @RequiresScope("approval:decide")
    @Transactional
    public ApprovalDto decide(String approvalId, ApprovalDecisionRequest request, Identity identity) {
        ApprovalEntity approval = require(approvalId);
        if (!"pending".equals(approval.getStatus())) {
            throw BizException.conflict("该审批已处理：" + approval.getStatus());
        }

        approval.setStatus(request.decision());
        approval.setDecidedBy(identity.username());
        approval.setDecisionComment(request.comment() == null ? "" : request.comment());
        approval.setDecidedAt(Instant.now());
        approvalRepository.save(approval);
        return ApprovalDto.from(approval);
    }

    public ApprovalEntity require(String approvalId) {
        return approvalRepository.findById(approvalId)
                .orElseThrow(() -> BizException.notFound("审批单"));
    }

    public long pendingCount() {
        return approvalRepository.countByStatus("pending");
    }
}
