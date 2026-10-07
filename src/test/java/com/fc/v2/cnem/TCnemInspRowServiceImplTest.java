package com.fc.v2.cnem;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.fc.v2.mapper.auto.TCnemInspRowMapper;
import com.fc.v2.model.auto.TCnemInspRow;
import com.fc.v2.service.impl.TCnemInspRowServiceImpl;

/**
 * RC-2026-0417 整改验收：检验申报整批受理。
 * 逐条对应复盘报告第三节的四条整改要求。
 */
class TCnemInspRowServiceImplTest {

    private TCnemInspRowServiceImpl service;
    private InMemoryInspRowMapper mapper;

    @BeforeEach
    void setUp() {
        this.service = new TCnemInspRowServiceImpl();
        this.mapper = new InMemoryInspRowMapper();
        ReflectionTestUtils.setField(this.service, "cnemInspRowMapper", this.mapper);
    }

    private static TCnemInspRow row(String itemCode, String qty) {
        TCnemInspRow r = new TCnemInspRow();
        r.setItemCode(itemCode);
        r.setQty(qty == null ? null : new BigDecimal(qty));
        return r;
    }

    private static List<TCnemInspRow> validRows(int n) {
        List<TCnemInspRow> rows = new ArrayList<TCnemInspRow>();
        for (int i = 1; i <= n; i++) {
            rows.add(row("EQ-" + i, "1"));
        }
        return rows;
    }

    private List<TCnemInspRow> stored(String batchNo) {
        return this.mapper.store.values().stream()
                .filter(r -> batchNo.equals(r.getBatchNo()))
                .sorted(Comparator.comparing(TCnemInspRow::getRowNo,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(Collectors.toList());
    }

    /** 空批次返回 0，不落任何记录 */
    @Test
    void emptyBatchReturnsZero() {
        assertEquals(0, this.service.submitBatch("B-EMPTY", null));
        assertEquals(0, this.service.submitBatch("B-EMPTY", new ArrayList<TCnemInspRow>()));
        assertTrue(this.mapper.store.isEmpty());
    }

    /** 整改一：全部合规的行逐行受理入库，批次码以窗口申报为准，原始行次按册上顺位 */
    @Test
    void allValidRowsAccepted() {
        List<TCnemInspRow> rows = Arrays.asList(row("EQ-1", "10"), row("EQ-2", "20"), row("EQ-3", "30"));
        assertEquals(3, this.service.submitBatch("B-OK", rows));
        List<TCnemInspRow> filed = stored("B-OK");
        assertEquals(3, filed.size());
        for (int i = 0; i < 3; i++) {
            assertEquals(Integer.valueOf(1), filed.get(i).getStatus(), "第 " + (i + 1) + " 行应已受理");
            assertEquals(Integer.valueOf(i + 1), filed.get(i).getRowNo(), "原始行次应按册上顺位落账");
            assertEquals(Integer.valueOf(0), filed.get(i).getDelFlag());
        }
    }

    /**
     * 整改一、二（事故原景）：第 180 行空值不再炸整批——缺项行当场驳回记退回明细，
     * 合规行照常受理；缺项原样保留，系统不代填、不以零计。
     */
    @Test
    void invalidRowsRejectedOnTheSpotWithoutAbortingBatch() {
        List<TCnemInspRow> rows = Arrays.asList(
                row("EQ-1", "10"),
                row("  ", "20"),      // 缺设备编号
                row("EQ-3", null),    // 缺累计运行小时数（事故直接原因）
                row("EQ-4", "0"),     // 零不构成有效申报（不以零计）
                row("EQ-5", "50"));
        assertEquals(2, this.service.submitBatch("B-MIX", rows));

        List<TCnemInspRow> filed = stored("B-MIX");
        assertEquals(5, filed.size(), "合规行与退回行都应各自落账，不再清空已受理行");
        assertEquals(Arrays.asList(1, 2, 2, 2, 1),
                filed.stream().map(TCnemInspRow::getStatus).collect(Collectors.toList()),
                "第 2/3/4 行当场驳回，第 1/5 行照常受理，互不拖累");
        // 缺项原样留在退回明细里：不代填、不以零计
        TCnemInspRow rejected = filed.get(2);
        assertEquals(Integer.valueOf(2), rejected.getStatus());
        assertNull(rejected.getQty(), "缺项不得由系统代填");
    }

    /** 整改一：驳回逐行留痕，失败明细按原始行号升序回查（前两次驳回无理由可查之弊） */
    @Test
    void listErrorsReturnsRejectedRowsOrderedByRowNo() {
        List<TCnemInspRow> rows = Arrays.asList(
                row("EQ-1", "10"), row(null, "20"), row("EQ-3", null), row("EQ-4", "40"));
        assertEquals(2, this.service.submitBatch("B-ERR", rows));

        List<TCnemInspRow> errors = this.service.listErrors("B-ERR");
        assertEquals(2, errors.size());
        assertEquals(Arrays.asList(Integer.valueOf(2), Integer.valueOf(3)),
                errors.stream().map(TCnemInspRow::getRowNo).collect(Collectors.toList()));
        assertTrue(errors.stream().allMatch(r -> Integer.valueOf(2).equals(r.getStatus())));
    }

    /** 整改三：批首空位按 150 行计——150 行照收，151 行整批不入库（返回 -1），余量另起新批 */
    @Test
    void capIs150AndOverLimitBatchIsRefusedWholesale() {
        assertEquals(150, this.service.submitBatch("B-150", validRows(150)));
        assertEquals(150, stored("B-150").size());

        assertEquals(-1, this.service.submitBatch("B-151", validRows(151)));
        assertTrue(stored("B-151").isEmpty(), "超限整批不入库，连退回明细也不落");
    }

    /** 整改四：同一批次再次投递仅作核对——不另立新记录、不回改前次已受理内容，返回在册已受理行数 */
    @Test
    void redeliveryOnlyReconcilesAndNeverRewrites() {
        List<TCnemInspRow> first = Arrays.asList(row("EQ-1", "10"), row("EQ-2", null), row("EQ-3", "30"));
        assertEquals(2, this.service.submitBatch("B-RE", first));

        // 企业“改好”第 2 行后整批重投：仍按首次受理结果核对，不得翻回、不得补录
        List<TCnemInspRow> second = Arrays.asList(row("EQ-1", "10"), row("EQ-2", "20"), row("EQ-3", "30"));
        assertEquals(2, this.service.submitBatch("B-RE", second));

        List<TCnemInspRow> filed = stored("B-RE");
        assertEquals(3, filed.size(), "二次投递不另立新记录");
        assertEquals(Arrays.asList(1, 2, 1),
                filed.stream().map(TCnemInspRow::getStatus).collect(Collectors.toList()),
                "前次已受理的行维持原样，退回行亦维持退回");
        assertNull(filed.get(1).getQty(), "退回行缺项不得借二次投递补写");
    }

    /** 批次边界受控：批次码以受理窗口申报为准，不从行数据反推 */
    @Test
    void batchIdentityComesFromTheDeclaredParameter() {
        TCnemInspRow r = row("EQ-1", "10");
        r.setBatchNo("B-ROW");
        assertEquals(1, this.service.submitBatch("B-PARAM", Arrays.asList(r)));
        assertEquals(1, stored("B-PARAM").size());
        assertTrue(stored("B-ROW").isEmpty());
    }

    /**
     * 内存版 {@link TCnemInspRowMapper}：按 LinkedHashMap 落账，按 QueryWrapper 的
     * eq 条件逐项比对；selectList 恒按 row_no 升序（本服务唯一的列表查询即失败明细）。
     */
    static class InMemoryInspRowMapper implements TCnemInspRowMapper {

        final Map<Long, TCnemInspRow> store = new LinkedHashMap<Long, TCnemInspRow>();
        private long seq = 1L;

        private static final Pattern EQ_PATTERN =
                Pattern.compile("([a-zA-Z_0-9]+)\\s*=\\s*#\\{ew\\.paramNameValuePairs\\.(MPGENVAL\\d+)\\}");

        @Override
        public int insert(TCnemInspRow r) {
            if (r.getId() == null) {
                r.setId(Long.valueOf(this.seq++));
            }
            this.store.put(r.getId(), r);
            return 1;
        }

        @Override
        public TCnemInspRow selectById(Serializable id) {
            return this.store.get(id);
        }

        @Override
        public Integer selectCount(Wrapper<TCnemInspRow> w) {
            int c = 0;
            for (TCnemInspRow r : this.store.values()) {
                if (matches(r, w)) {
                    c++;
                }
            }
            return Integer.valueOf(c);
        }

        @Override
        public List<TCnemInspRow> selectList(Wrapper<TCnemInspRow> w) {
            return this.store.values().stream()
                    .filter(r -> matches(r, w))
                    .sorted(Comparator.comparing(TCnemInspRow::getRowNo,
                            Comparator.nullsLast(Comparator.naturalOrder())))
                    .collect(Collectors.toList());
        }

        @Override
        public TCnemInspRow selectOne(Wrapper<TCnemInspRow> w) {
            return selectList(w).stream().findFirst().orElse(null);
        }

        private static boolean matches(TCnemInspRow r, Wrapper<TCnemInspRow> w) {
            Matcher m = EQ_PATTERN.matcher(w.getSqlSegment());
            Map<String, Object> params = ((AbstractWrapper<?, ?, ?>) w).getParamNameValuePairs();
            while (m.find()) {
                Object expect = params.get(m.group(2));
                if (!Objects.equals(expect, columnValue(r, m.group(1)))) {
                    return false;
                }
            }
            return true;
        }

        private static Object columnValue(TCnemInspRow r, String column) {
            switch (column) {
                case "id":
                    return r.getId();
                case "batch_no":
                    return r.getBatchNo();
                case "row_no":
                    return r.getRowNo();
                case "status":
                    return r.getStatus();
                case "del_flag":
                    return r.getDelFlag();
                default:
                    throw new IllegalArgumentException("fake 未覆盖的列: " + column);
            }
        }

        @Override
        public int deleteById(Serializable id) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public int deleteByMap(Map<String, Object> columnMap) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public int delete(Wrapper<TCnemInspRow> queryWrapper) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public int deleteBatchIds(Collection<? extends Serializable> idList) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public int updateById(TCnemInspRow entity) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public int update(TCnemInspRow entity, Wrapper<TCnemInspRow> updateWrapper) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public List<TCnemInspRow> selectBatchIds(Collection<? extends Serializable> idList) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public List<TCnemInspRow> selectByMap(Map<String, Object> columnMap) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public List<Map<String, Object>> selectMaps(Wrapper<TCnemInspRow> queryWrapper) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public List<Object> selectObjs(Wrapper<TCnemInspRow> queryWrapper) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public <E extends IPage<TCnemInspRow>> E selectPage(E page, Wrapper<TCnemInspRow> queryWrapper) {
            throw new UnsupportedOperationException("fake");
        }

        @Override
        public <E extends IPage<Map<String, Object>>> E selectMapsPage(E page, Wrapper<TCnemInspRow> queryWrapper) {
            throw new UnsupportedOperationException("fake");
        }
    }
}
