package com.opsflow.service.impl.pipeline;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.opsflow.api.dto.PipelineViewDTO;
import com.opsflow.common.constant.BuildStatus;
import com.opsflow.dao.mapper.BuildJobMapper;
import com.opsflow.dao.model.BuildJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 流水线详情的「当前」构建：深链（focus）指定哪次就标哪次，并能翻到它所在的页；
 * 不带 focus 时保持原来的规则（运行中的优先，否则本页第一条）。
 */
@SuppressWarnings("unchecked")
class PipelineStageViewFocusTest {

    private final PipelineJobQueryService service = new PipelineJobQueryService();
    private BuildJobMapper buildJobMapper;
    private PipelineJobSupport support;

    /** build 3 是旧的那次（深链指向它），build 4 是最新且正在运行的那次 */
    private final BuildJob build3 = job(3L, BuildStatus.SUCCESS, LocalDateTime.of(2026, 11, 27, 21, 37, 24));
    private final BuildJob build4 = job(4L, BuildStatus.BUILDING, LocalDateTime.of(2026, 11, 27, 21, 38, 57));
    private final BuildJob otherTaskBuild = job(9L, BuildStatus.SUCCESS, LocalDateTime.of(2026, 11, 27, 20, 0, 0));

    /** 「比 focus 新的构建有几条」查询返回的数量 */
    private long newerThanFocus = 1;
    /** 「focus 是否属于这个任务」查询返回的数量 */
    private long focusInSameTask = 1;
    private final AtomicReference<String> newerSql = new AtomicReference<>();

    private static BuildJob job(Long id, String status, LocalDateTime createTime) {
        BuildJob job = new BuildJob();
        job.setId(id);
        job.setStatus(status);
        job.setCreateTime(createTime);
        job.setTaskName("演示-shop-ui");
        job.setServiceId(1L);
        job.setEnvId(2L);
        job.setPipelineTemplateId(8L);
        return job;
    }

    @BeforeEach
    void setUp() throws Exception {
        for (Field field : PipelineJobQueryService.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(Autowired.class)) {
                ReflectionTestUtils.setField(service, field.getName(), mock(field.getType()));
            }
        }
        buildJobMapper = (BuildJobMapper) ReflectionTestUtils.getField(service, "buildJobMapper");
        support = (PipelineJobSupport) ReflectionTestUtils.getField(service, "support");
        when(support.buildTaskGroupWrapper(any())).thenAnswer(inv -> new QueryWrapper<BuildJob>().eq("task_name", "演示-shop-ui"));
        when(buildJobMapper.selectById(3L)).thenReturn(build3);
        when(buildJobMapper.selectById(4L)).thenReturn(build4);
        when(buildJobMapper.selectById(9L)).thenReturn(otherTaskBuild);
        when(buildJobMapper.selectList(any(Wrapper.class))).thenReturn(Arrays.asList(build4, build3));
        when(buildJobMapper.selectCount(any(Wrapper.class))).thenAnswer(inv -> {
            String sql = ((QueryWrapper<BuildJob>) inv.getArgument(0)).getSqlSegment();
            if (sql.contains("id >")) {
                newerSql.set(sql);
                return newerThanFocus;
            }
            if (sql.contains("id =")) {
                return focusInSameTask;
            }
            return 2L;
        });
    }

    private String listSql() {
        ArgumentCaptor<Wrapper<BuildJob>> captor = ArgumentCaptor.forClass(Wrapper.class);
        verify(buildJobMapper, atLeastOnce()).selectList(captor.capture());
        List<Wrapper<BuildJob>> all = captor.getAllValues();
        return ((QueryWrapper<BuildJob>) all.get(all.size() - 1)).getSqlSegment();
    }

    @Test
    void 不带深链时仍然高亮运行中的构建() {
        PipelineViewDTO view = service.getStageView(3L, 1, 10);

        assertEquals(4L, view.getHighlightJobId());
        assertEquals(BuildStatus.BUILDING, view.getCurrentStatus());
        assertEquals(1, view.getCurrentPage());
    }

    @Test
    void 深链指向旧构建时高亮链接里的那次而不是运行中的那次() {
        PipelineViewDTO view = service.getStageView(3L, 1, 10, 3L);

        assertEquals(3L, view.getHighlightJobId());
        assertEquals(BuildStatus.SUCCESS, view.getCurrentStatus());
    }

    @Test
    void 从任务的另一次构建进入_深链仍定位到指定那次() {
        PipelineViewDTO view = service.getStageView(4L, 1, 10, 3L);

        assertEquals(3L, view.getHighlightJobId());
    }

    @Test
    void 深链的构建不属于这个任务时忽略_按原规则高亮() {
        focusInSameTask = 0;

        PipelineViewDTO view = service.getStageView(3L, 1, 10, 9L);

        assertEquals(4L, view.getHighlightJobId());
    }

    @Test
    void 深链的构建不存在时忽略() {
        PipelineViewDTO view = service.getStageView(3L, 1, 10, 404L);

        assertEquals(4L, view.getHighlightJobId());
    }

    @Test
    void 页码为0时翻到深链构建所在的页() {
        newerThanFocus = 12;

        PipelineViewDTO view = service.getStageView(4L, 0, 10, 3L);

        assertEquals(2, view.getCurrentPage());
        assertTrue(listSql().endsWith("LIMIT 10, 10"), listSql());
        String sql = newerSql.get();
        assertTrue(sql.contains("create_time >") && sql.contains("create_time =") && sql.contains("id >"), sql);
    }

    @Test
    void 恰好排满一页时深链构建落在下一页的第一条() {
        newerThanFocus = 10;
        assertEquals(2, service.getStageView(4L, 0, 10, 3L).getCurrentPage());

        newerThanFocus = 9;
        assertEquals(1, service.getStageView(4L, 0, 10, 3L).getCurrentPage());
    }

    @Test
    void 用户自己翻页时不重新定位() {
        newerThanFocus = 12;

        PipelineViewDTO view = service.getStageView(4L, 1, 10, 3L);

        assertEquals(1, view.getCurrentPage());
        assertEquals(3L, view.getHighlightJobId());
    }

    @Test
    void 构建历史按创建时间和ID倒序() {
        service.getStageView(3L, 1, 10);

        assertTrue(listSql().contains("ORDER BY create_time DESC,id DESC"), listSql());
    }
}
