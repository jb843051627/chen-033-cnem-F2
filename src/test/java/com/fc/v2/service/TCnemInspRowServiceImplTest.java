package com.fc.v2.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.TCnemInspRowMapper;
import com.fc.v2.model.auto.TCnemInspRow;
import com.fc.v2.service.impl.TCnemInspRowServiceImpl;

/**
 * RC-2026-0417 整改验证：整批受理四条整改要求的行级行为测试。
 * 以内存表模拟 Mapper，覆盖：逐行核验、缺项驳回、批上限、同批幂等。
 */
class TCnemInspRowServiceImplTest {

    /** 内存表：模拟 t_cnem_insp_row 落账 */
    private List<TCnemInspRow> store;
    private TCnemInspRowMapper mapper;
    private TCnemInspRowServiceImpl service;

    @BeforeEach
    void setUp() {
        store = new ArrayList<TCnemInspRow>();
        mapper = mock(TCnemInspRowMapper.class);
        when(mapper.insert(any(TCnemInspRow.class))).thenAnswer(inv -> {
            TCnemInspRow r = inv.getArgument(0);
            store.add(r);
            return 1;
        });
        when(mapper.selectCount(any())).thenAnswer(inv -> {
            QueryWrapper<TCnemInspRow> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            assertTrue(seg.contains("batch_no"), "存在查询须按批次码");
            assertTrue(seg.contains("del_flag"), "存在查询须带删除标记");
            String batchNo = stringParam(w);
            int cnt = 0;
            for (TCnemInspRow r : store) {
                if (Objects.equals(r.getBatchNo(), batchNo) && Integer.valueOf(0).equals(r.getDelFlag())) {
                    cnt++;
                }
            }
            return Integer.valueOf(cnt);
        });
        when(mapper.selectList(any())).thenAnswer(inv -> {
            QueryWrapper<TCnemInspRow> w = inv.getArgument(0);
            String seg = w.getSqlSegment();
            assertTrue(seg.contains("batch_no") && seg.contains("status") && seg.contains("del_flag"),
                    "失败明细须按批次+状态+删除标记过滤");
            assertTrue(seg.contains("ORDER BY") && seg.contains("row_no"),
                    "失败明细须按行号排序");
            String batchNo = stringParam(w);
            return store.stream()
                    .filter(r -> Objects.equals(r.getBatchNo(), batchNo))
                    .filter(r -> Integer.valueOf(2).equals(r.getStatus()))
                    .filter(r -> Integer.valueOf(0).equals(r.getDelFlag()))
                    .sorted(Comparator.comparing(TCnemInspRow::getRowNo,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        });
        service = new TCnemInspRowServiceImpl();
        ReflectionTestUtils.setField(service, "cnemInspRowMapper", mapper);
    }

    private static String stringParam(QueryWrapper<?> w) {
        for (Map.Entry<String, Object> e : w.getParamNameValuePairs().entrySet()) {
            if (e.getValue() instanceof String) {
                return (String) e.getValue();
            }
        }
        return null;
    }

    private static TCnemInspRow row(Integer rowNo, String itemCode, BigDecimal qty) {
        TCnemInspRow r = new TCnemInspRow();
        r.setRowNo(rowNo);
        r.setItemCode(itemCode);
        r.setQty(qty);
        return r;
    }

    private static List<TCnemInspRow> validRows(int n) {
        List<TCnemInspRow> list = new ArrayList<TCnemInspRow>();
        for (int i = 1; i <= n; i++) {
            list.add(row(Integer.valueOf(i), "EQ-" + i, new BigDecimal("100")));
        }
        return list;
    }

    /** 契约：空批次返回 0，且不触碰数据层 */
    @Test
    void emptyBatchReturnsZero() {
        assertEquals(0, service.submitBatch("B0", null));
        assertEquals(0, service.submitBatch("B0", new ArrayList<TCnemInspRow>()));
        verify(mapper, never()).insert(any(TCnemInspRow.class));
        verify(mapper, never()).selectCount(any());
    }

    /** 整改（一）（二）：逐行核验，不合规当场驳回进失败明细，合规行照常受理，整批不再中止 */
    @Test
    void rowByRowValidationRejectsOnlyBadRows() {
        List<TCnemInspRow> rows = new ArrayList<TCnemInspRow>();
        rows.add(row(1, "EQ-001", new BigDecimal("100")));   // 合规
        rows.add(row(2, "EQ-002", null));                    // 缺实采值
        rows.add(row(3, null, new BigDecimal("50")));        // 缺申报值
        rows.add(row(4, "  ", null));                        // 两项皆缺
        rows.add(row(5, "EQ-005", BigDecimal.ZERO));         // 申报 0 为册上原值，受理

        int accepted = service.submitBatch("B1", rows);

        assertEquals(2, accepted, "返回成功行数");
        assertEquals(5, store.size(), "合规与驳回行均落账");
        // 合规行：已受理
        assertEquals(Integer.valueOf(1), store.get(0).getStatus());
        assertEquals(Integer.valueOf(1), store.get(4).getStatus(), "申报 0 不以缺项论");
        // 驳回行：已退回、保留原始行次、落驳回理由、缺项不代填不以零计
        TCnemInspRow r2 = store.get(1);
        assertEquals(Integer.valueOf(2), r2.getStatus());
        assertEquals(Integer.valueOf(2), r2.getRowNo(), "保留原始行号");
        assertNull(r2.getQty(), "缺实采值不以零计");
        assertTrue(r2.getRemark().contains("累计运行小时数"), "记录驳回理由");
        TCnemInspRow r3 = store.get(2);
        assertEquals(Integer.valueOf(2), r3.getStatus());
        assertNull(r3.getItemCode(), "缺申报值不代填");
        assertTrue(r3.getRemark().contains("设备编号"));
        TCnemInspRow r4 = store.get(3);
        assertTrue(r4.getRemark().contains("设备编号") && r4.getRemark().contains("累计运行小时数"));
        // 全部行挂批次码、删除标记正常，且无一行被翻回待受理
        for (TCnemInspRow r : store) {
            assertEquals("B1", r.getBatchNo());
            assertEquals(Integer.valueOf(0), r.getDelFlag());
            assertTrue(r.getStatus() == 1 || r.getStatus() == 2, "不得出现待受理态");
        }
    }

    /** 整改（三）：批首空位按 150 行计，超限整批不入库返回 -1，余量另起新批承接 */
    @Test
    void batchLimitRejectsOversizeWholesale() {
        // 复盘事故现场：317 行整批投递，超 150 上限
        assertEquals(-1, service.submitBatch("B2", validRows(317)));
        assertEquals(0, store.size(), "超限整批不入库");
        assertEquals(-1, service.submitBatch("B2", validRows(151)));
        assertEquals(0, store.size());
        // 收手后余量另起新批：同批次码未落账，拆分后可正常受理
        assertEquals(150, service.submitBatch("B2", validRows(150)));
        assertEquals(150, store.size());
        assertEquals(17, service.submitBatch("B2-余量", validRows(17)));
        assertEquals(167, store.size());
    }

    /** 整改（四）：同一批次再次投递仅作核对，已受理行维持原样，不另立新记录 */
    @Test
    void redeliveryIsReconcileOnly() {
        List<TCnemInspRow> first = new ArrayList<TCnemInspRow>();
        first.add(row(1, "EQ-1", new BigDecimal("10")));
        first.add(row(2, "EQ-2", null));
        first.add(row(3, "EQ-3", new BigDecimal("30")));
        assertEquals(2, service.submitBatch("B3", first));
        assertEquals(3, store.size(), "首次落账 3 行（2 受理 + 1 退回）");

        // 第二次投递：同样的批次码，哪怕内容有变也仅作核对
        List<TCnemInspRow> again = new ArrayList<TCnemInspRow>();
        again.add(row(1, "EQ-1", new BigDecimal("999")));
        again.add(row(2, "EQ-2", new BigDecimal("20")));
        again.add(row(4, "EQ-4", new BigDecimal("40")));
        assertEquals(0, service.submitBatch("B3", again), "再次投递不新受理");

        assertEquals(3, store.size(), "不另立新记录");
        assertEquals(Integer.valueOf(1), store.get(0).getStatus(), "已受理维持原样");
        assertEquals(new BigDecimal("10"), store.get(0).getQty(), "已受理值不被回改");
        assertEquals(Integer.valueOf(2), store.get(1).getStatus(), "已退回维持原样");
        assertNull(store.get(1).getQty(), "驳回行缺项不被补写");
        verify(mapper, never()).updateById(any(TCnemInspRow.class));
        verify(mapper, never()).update(any(), any());
    }

    /** 失败明细：按行号升序，只含本批已退回行 */
    @Test
    void listErrorsOrderedByRowNo() {
        List<TCnemInspRow> rows = new ArrayList<TCnemInspRow>();
        rows.add(row(5, null, new BigDecimal("1")));
        rows.add(row(2, "EQ-2", null));
        rows.add(row(9, "EQ-9", new BigDecimal("9")));
        rows.add(row(1, "", new BigDecimal("1")));
        service.submitBatch("B4", rows);

        List<TCnemInspRow> errors = service.listErrors("B4");
        assertEquals(3, errors.size());
        assertEquals(Integer.valueOf(1), errors.get(0).getRowNo());
        assertEquals(Integer.valueOf(2), errors.get(1).getRowNo());
        assertEquals(Integer.valueOf(5), errors.get(2).getRowNo());
        for (TCnemInspRow e : errors) {
            assertEquals(Integer.valueOf(2), e.getStatus());
        }
        assertTrue(service.listErrors(null).isEmpty());
        assertTrue(service.listErrors("  ").isEmpty());
    }

    /** 批次码：入参为准；入参空缺回退批首行所载 */
    @Test
    void batchNoResolution() {
        List<TCnemInspRow> rows = validRows(2);
        rows.get(0).setBatchNo("B-HEAD");
        assertEquals(2, service.submitBatch(null, rows));
        for (TCnemInspRow r : store) {
            assertEquals("B-HEAD", r.getBatchNo());
        }
        store.clear();
        rows = validRows(1);
        rows.get(0).setBatchNo("B-HEAD");
        assertEquals(1, service.submitBatch("B-PARAM", rows));
        assertEquals("B-PARAM", store.get(0).getBatchNo());
    }

    /** 事故复盘核心回归：单行空值不再中止整批、不再清空已受理行 */
    @Test
    void singleBadRowNoLongerAbortsBatch() {
        List<TCnemInspRow> rows = validRows(150);
        rows.get(99).setQty(null);  // 第 100 行：累计运行小时数为空（事故同型）
        rows.get(129).setQty(null); // 第 130 行：同样空值
        int accepted = service.submitBatch("B5", rows);
        assertEquals(148, accepted, "空值行当场驳回，其余照常受理");
        assertEquals(150, store.size(), "已受理行不被清空");
        assertEquals(2, service.listErrors("B5").size());
        Mockito.verify(mapper, Mockito.times(150)).insert(any(TCnemInspRow.class));
    }
}
