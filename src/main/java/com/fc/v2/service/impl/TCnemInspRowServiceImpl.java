package com.fc.v2.service.impl;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TCnemInspRowMapper;
import com.fc.v2.model.auto.TCnemInspRow;
import com.fc.v2.service.ITCnemInspRowService;

/**
 * 检验申报行 Service业务层处理（batch-process 形状：整批提交）
 *
 * <p>按 RC-2026-0417 复盘报告整改，逐条对应：</p>
 * （一）导入前置解析，按校验规则逐行核验；不合规之行当场驳回（记入失败明细），不进入受理集，
 *       不再因单行不合规中止整批、清空已受理行；
 * （二）每一申报行须同时具备申报值（设备编号）与实采值（累计运行小时数），缺一不予受理；
 *       系统不代填、不以零计——驳回行按册上原值落账，缺项保持空缺；
 * （三）批首所载行数为该批准许上限，空位按 150 行计；达到该行数即收手，超限整批不入库，
 *       余量另起新批承接；
 * （四）同一批次再次投递仅作核对：前次已受理的行维持原样，不回改、不另立新记录。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class TCnemInspRowServiceImpl implements ITCnemInspRowService {

    /** 单批准许行数上限：批首空位按 150 行计，达到即收手，余量另起新批 */
    private static final int MAX_ROWS_PER_BATCH = 150;

    /** 行状态：已受理 */
    private static final int STATUS_ACCEPTED = 1;
    /** 行状态：已退回 */
    private static final int STATUS_REJECTED = 2;

    @javax.annotation.Resource
    private TCnemInspRowMapper cnemInspRowMapper;

    @Override
    public TCnemInspRow selectTCnemInspRowById(Long id) {
        return this.cnemInspRowMapper.selectById(id);
    }

    /**
     * 整批提交：逐行校验，合法的入库、非法的记入失败明细（保留原始行号），返回成功行数。
     * 同一批次重复提交不得重复入库；行数超过单批上限返回 -1 且整批不入库；空批次返回 0。
     */
    @Override
    public int submitBatch(String batchNo, List<TCnemInspRow> rows) {
        // 空批次返回 0
        if (rows == null || rows.isEmpty()) {
            return 0;
        }
        String no = resolveBatchNo(batchNo, rows);
        // 整改（四）：同一批次再次投递仅作核对——前次已受理的行维持原样，不回改、不另立新记录
        if (batchOnFile(no)) {
            return 0;
        }
        // 整改（三）：批首所载行数为该批准许上限（空位按 150 行计），超限整批不入库，余量另起新批
        if (rows.size() > MAX_ROWS_PER_BATCH) {
            return -1;
        }
        // 整改（一）：逐行核验，不合规之行当场驳回、不进入受理集，合规行正常受理
        int accepted = 0;
        for (TCnemInspRow r : rows) {
            if (r == null) {
                // 册上没有这一行就不认：空行不落任何记录
                continue;
            }
            String reason = rejectReason(r);
            r.setBatchNo(no);
            r.setDelFlag(Integer.valueOf(0));
            if (reason == null) {
                r.setStatus(Integer.valueOf(STATUS_ACCEPTED));
                this.cnemInspRowMapper.insert(r);
                accepted++;
            } else {
                // 当场驳回：保留原始行次与册上原值（不代填、不以零计），并落驳回理由备查
                r.setStatus(Integer.valueOf(STATUS_REJECTED));
                r.setRemark(reason);
                this.cnemInspRowMapper.insert(r);
            }
        }
        return accepted;
    }

    @Override
    public List<TCnemInspRow> listErrors(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return new ArrayList<TCnemInspRow>();
        }
        return this.cnemInspRowMapper.selectList(new QueryWrapper<TCnemInspRow>()
                .eq("batch_no", batchNo)
                .eq("status", STATUS_REJECTED)
                .eq("del_flag", 0)
                .orderByAsc("row_no"));
    }

    /** 批次码以入参为准；入参空缺时回退批首行所载（与基线口径一致） */
    private String resolveBatchNo(String batchNo, List<TCnemInspRow> rows) {
        if (batchNo != null && !batchNo.trim().isEmpty()) {
            return batchNo;
        }
        TCnemInspRow head = rows.get(0);
        return head == null ? null : head.getBatchNo();
    }

    /** 该批次是否已有落账（已受理或已退回均视为已投递过），是则再次投递仅作核对 */
    private boolean batchOnFile(String batchNo) {
        if (batchNo == null || batchNo.trim().isEmpty()) {
            return false;
        }
        Integer cnt = this.cnemInspRowMapper.selectCount(new QueryWrapper<TCnemInspRow>()
                .eq("batch_no", batchNo)
                .eq("del_flag", 0));
        return cnt != null && cnt.intValue() > 0;
    }

    /**
     * 整改（二）：每一申报行须同时具备申报值（设备编号）与实采值（累计运行小时数），
     * 缺一不予受理；系统不代填、不以零计。合规返回 null，否则返回驳回理由。
     */
    private String rejectReason(TCnemInspRow r) {
        boolean missItem = r.getItemCode() == null || r.getItemCode().trim().isEmpty();
        boolean missQty = r.getQty() == null;
        if (missItem && missQty) {
            return "驳回：缺设备编号（申报值）与累计运行小时数（实采值）";
        }
        if (missItem) {
            return "驳回：缺设备编号（申报值）";
        }
        if (missQty) {
            return "驳回：缺累计运行小时数（实采值）";
        }
        return null;
    }
}
