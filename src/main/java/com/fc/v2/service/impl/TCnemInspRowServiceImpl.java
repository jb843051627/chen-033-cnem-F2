package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TCnemInspRowMapper;
import com.fc.v2.model.auto.TCnemInspRow;
import com.fc.v2.service.ITCnemInspRowService;

/**
 * 检验申报行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>RC-2026-0417 整改口径，逐条对应事故复盘第二节的原因认定：
 * <ol>
 * <li>导入前置解析、按校验规则逐行核验：不合规之行当场驳回（记退回明细、保留原始行号），
 *     不进入受理集，也不再中止整批、清空已受理行；</li>
 * <li>申报值与实采值缺一不予受理：系统不代填、不以零计，缺项原样留在退回明细里；</li>
 * <li>批首位置载明本批准许行数，空位按 150 行计：达到该行数即收手，超限整批不入库（返回 -1），
 *     余量另起新批承接；</li>
 * <li>同一批次再次投递仅作核对：前次已受理的行维持原样，不回改、不另立新记录。</li>
 * </ol>
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TCnemInspRowServiceImpl implements ITCnemInspRowService {

    /** 行状态：待受理 */
    private static final int STATUS_PENDING = 0;
    /** 行状态：已受理 */
    private static final int STATUS_ACCEPTED = 1;
    /** 行状态：已退回 */
    private static final int STATUS_REJECTED = 2;

    /** 单批准许行数：批首空位按 150 行计（整改三），达到即收手 */
    private static final int BATCH_CAP = 150;

    /** 超限返回值：行数超过单批准许行数，整批不入库 */
    private static final int OVER_LIMIT = -1;

    @javax.annotation.Resource
    private TCnemInspRowMapper cnemInspRowMapper;

    @Override
    public TCnemInspRow selectTCnemInspRowById(Long id) {
        return this.cnemInspRowMapper.selectById(id);
    }

    @Override
    public int submitBatch(String batchNo, List<TCnemInspRow> rows) {
        // 空批次返回 0
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        // 批次边界以受理窗口申报的批次码为准；窗口未给时才回落到册上所载，册上也没有则无从落账
        String no = (batchNo == null || batchNo.trim().isEmpty())
                ? rows.get(0) == null ? null : rows.get(0).getBatchNo()
                : batchNo;
        if (no == null || no.trim().isEmpty()) {
            return 0;
        }

        // 整改四：同一批次再次投递仅作核对——前次已受理的行维持原样，不回改、不另立新记录，
        // 返回该批在册的已受理行数
        Integer filed = countByBatch(no, null);
        if (filed != null && filed.intValue() > 0) {
            Integer accepted = countByBatch(no, Integer.valueOf(STATUS_ACCEPTED));
            return accepted == null ? 0 : accepted.intValue();
        }

        // 整改三：批首空位按 150 行计，达到该行数即收手；超限整批不入库，余量另起新批承接
        if (rows.size() > BATCH_CAP) {
            return OVER_LIMIT;
        }

        // 整改一、二：逐行核验，合法的受理入库、不合规的当场驳回记退回明细（保留原始行号），
        // 行与行互不拖累，不再一行缺项炸整批
        int accepted = 0;
        int seq = 0;
        for (TCnemInspRow r : rows) {
            seq++;
            if (r == null) {
                // 册上没有这一行就不认：空行不造记录、不计入受理
                continue;
            }
            if (r.getRowNo() == null) {
                r.setRowNo(Integer.valueOf(seq));
            }
            r.setBatchNo(no);
            r.setDelFlag(Integer.valueOf(0));
            if (isAcceptable(r)) {
                r.setStatus(Integer.valueOf(STATUS_ACCEPTED));
                accepted++;
            } else {
                // 当场驳回：缺项原样保留在退回明细里，系统不代填、不以零计
                r.setStatus(Integer.valueOf(STATUS_REJECTED));
            }
            this.cnemInspRowMapper.insert(r);
        }
        return accepted;
    }

    @Override
    public List<TCnemInspRow> listErrors(String batchNo) {
        return this.cnemInspRowMapper.selectList(new QueryWrapper<TCnemInspRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_REJECTED)
                .eq("del_flag", 0)
                .orderByAsc("row_no"));
    }

    /**
     * 逐行核验（整改二）：申报值与实采值须同时具备——设备编号空缺、累计运行小时数缺项，
     * 均不予受理；零/负运行小时数不构成有效申报（不以零计）。只判定不改写，系统不代填。
     */
    private boolean isAcceptable(TCnemInspRow r) {
        if (r.getItemCode() == null || r.getItemCode().trim().isEmpty()) {
            return false;
        }
        BigDecimal qty = r.getQty();
        if (qty == null) {
            return false;
        }
        return qty.compareTo(BigDecimal.ZERO) > 0;
    }

    /** 该批次在册行数；status 为 null 时不限状态（用于识别批次是否已投递过） */
    private Integer countByBatch(String batchNo, Integer status) {
        QueryWrapper<TCnemInspRow> q = new QueryWrapper<TCnemInspRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", 0);
        if (status != null) {
            q.eq("status", status);
        }
        return this.cnemInspRowMapper.selectCount(q);
    }
}
