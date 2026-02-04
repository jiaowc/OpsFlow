package com.opsflow.web.controller;

import com.offbytwo.jenkins.model.Job;
import com.opsflow.api.dto.*;
import com.opsflow.integration.jenkins.JenkinsClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

/**
 * Jenkins 视图控制器
 */
@RestController
@RequestMapping("/api/jenkins")
public class JenkinsViewController {

    private static final Logger log = LoggerFactory.getLogger(JenkinsViewController.class);

    @Autowired
    private JenkinsClient jenkinsClient;
    
    // 线程池用于并行处理作业
    private final ExecutorService executorService = Executors.newFixedThreadPool(10);

    /**
     * 获取 Jenkins 作业列表视图（优化版本：并行处理，快速加载）
     */
    @GetMapping("/jobs")
    public List<JenkinsJobViewDTO> getJobsView(@RequestParam(defaultValue = "false") boolean includeHealth) {
        try {
            Map<String, Job> jobs = jenkinsClient.getAllJobs();
            if (jobs == null || jobs.isEmpty()) {
                return Collections.emptyList();
            }
            
            // 使用并行流处理作业，提高性能
            List<CompletableFuture<JenkinsJobViewDTO>> futures = jobs.entrySet().stream()
                .map(entry -> CompletableFuture.supplyAsync(() -> {
                    return processJob(entry.getKey(), entry.getValue(), includeHealth);
                }, executorService))
                .collect(Collectors.toList());
            
            // 等待所有任务完成并收集结果
            List<JenkinsJobViewDTO> jobViews = futures.stream()
                .map(CompletableFuture::join)
                .filter(jobView -> jobView != null)
                .collect(Collectors.toList());
            
            return jobViews;
        } catch (Exception e) {
            throw new RuntimeException("获取Jenkins作业列表失败: " + e.getMessage(), e);
        }
    }
    
    /**
     * 处理单个作业（提取为独立方法以便并行处理）
     */
    private JenkinsJobViewDTO processJob(String jobName, Job job, boolean includeHealth) {
        try {
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = job.details();
            if (jobDetails == null) {
                return null;
            }
            
            JenkinsJobViewDTO jobView = new JenkinsJobViewDTO();
            jobView.setName(jobName);
            jobView.setUrl(job.getUrl());
            
            // 获取最后构建信息（优化：快速获取，减少详情查询）
            com.offbytwo.jenkins.model.Build lastBuild = jobDetails.getLastBuild();
            if (lastBuild != null) {
                try {
                    // 优化：使用超时机制，避免长时间等待
                    com.offbytwo.jenkins.model.BuildWithDetails lastBuildDetails = lastBuild.details();
                    if (lastBuildDetails != null) {
                        if (lastBuildDetails.getResult() != null) {
                            jobView.setStatus(lastBuildDetails.getResult().name());
                        } else {
                            jobView.setStatus(lastBuildDetails.isBuilding() ? "BUILDING" : "UNKNOWN");
                        }
                        jobView.setBuilding(lastBuildDetails.isBuilding());
                        // 优化：duration 可能为 null，需要检查
                        Long duration = lastBuildDetails.getDuration();
                        if (duration != null) {
                            jobView.setLastDuration(duration);
                            jobView.setLastDurationText(formatDuration(duration));
                        }
                    }
                } catch (Exception e) {
                    // 忽略构建详情获取失败，设置默认值
                    jobView.setStatus("UNKNOWN");
                }
            } else {
                jobView.setStatus("UNKNOWN");
            }
            
            // 获取最后成功构建（优化：只获取编号，不获取详情）
            com.offbytwo.jenkins.model.Build lastSuccessfulBuild = jobDetails.getLastSuccessfulBuild();
            if (lastSuccessfulBuild != null) {
                jobView.setLastSuccessfulBuild(lastSuccessfulBuild.getNumber());
                // 优化：不获取详情，减少HTTP请求
                // 如果需要时间，可以从构建号推断或使用缓存
            }
            
            // 获取最后失败构建（优化：只获取编号）
            com.offbytwo.jenkins.model.Build lastFailedBuild = jobDetails.getLastFailedBuild();
            if (lastFailedBuild != null) {
                jobView.setLastFailedBuild(lastFailedBuild.getNumber());
            }
            
            // 优化健康度计算：可选，默认不计算以提升性能
            // 健康度计算需要查询多个构建详情，非常耗时
            if (includeHealth) {
                try {
                    int successCount = 0;
                    int totalCount = 0;
                    List<com.offbytwo.jenkins.model.Build> builds = jobDetails.getBuilds();
                    if (builds != null && !builds.isEmpty()) {
                        // 进一步优化：只检查最近2次构建
                        int checkLimit = Math.min(2, builds.size());
                        for (int i = 0; i < checkLimit; i++) {
                            com.offbytwo.jenkins.model.Build build = builds.get(i);
                            try {
                                // 优化：只获取基本信息，不获取完整详情
                                com.offbytwo.jenkins.model.BuildWithDetails buildDetails = build.details();
                                if (buildDetails != null && buildDetails.getResult() != null) {
                                    totalCount++;
                                    if (buildDetails.getResult().name().equals("SUCCESS")) {
                                        successCount++;
                                    }
                                }
                            } catch (Exception e) {
                                // 忽略单个构建的错误
                            }
                        }
                    }
                    
                    // 根据成功率设置健康度图标
                    if (totalCount > 0) {
                        double successRate = (double) successCount / totalCount;
                        if (successRate >= 0.8) {
                            jobView.setHealthIcon("sun");
                            jobView.setHealthDescription("健康度良好");
                        } else if (successRate >= 0.5) {
                            jobView.setHealthIcon("cloud");
                            jobView.setHealthDescription("健康度一般");
                        } else {
                            jobView.setHealthIcon("storm");
                            jobView.setHealthDescription("健康度较差");
                        }
                    } else {
                        jobView.setHealthIcon("unknown");
                        jobView.setHealthDescription("暂无构建历史");
                    }
                } catch (Exception e) {
                    jobView.setHealthIcon("unknown");
                    jobView.setHealthDescription("无法获取健康度");
                }
            } else {
                // 默认不计算健康度，提升加载速度
                jobView.setHealthIcon("unknown");
                jobView.setHealthDescription("点击刷新健康度");
            }
            
            return jobView;
        } catch (Exception e) {
            // 忽略单个作业的错误，继续处理其他作业
            System.err.println("处理作业失败: " + jobName + ", 错误: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取 Pipeline 阶段视图（优化版本：分步加载，支持分页）
     */
    @GetMapping("/pipeline/{jobName}")
    public PipelineViewDTO getPipelineView(@PathVariable String jobName, 
                                          @RequestParam(defaultValue = "1") int page,
                                          @RequestParam(defaultValue = "10") int pageSize) {
        try {
            PipelineViewDTO pipelineView = new PipelineViewDTO();
            pipelineView.setJobName(jobName);
            
            com.offbytwo.jenkins.model.JobWithDetails jobDetails = jenkinsClient.getJobDetails(jobName);
            if (jobDetails == null) {
                throw new RuntimeException("作业不存在: " + jobName);
            }
            
            // 获取当前构建状态
            com.offbytwo.jenkins.model.Build lastBuild = jobDetails.getLastBuild();
            if (lastBuild != null) {
                com.offbytwo.jenkins.model.BuildWithDetails lastBuildDetails = lastBuild.details();
                if (lastBuildDetails != null) {
                    if (lastBuildDetails.getResult() != null) {
                        pipelineView.setCurrentStatus(lastBuildDetails.getResult().name());
                    } else {
                        pipelineView.setCurrentStatus(lastBuildDetails.isBuilding() ? "BUILDING" : "UNKNOWN");
                    }
                }
            }
            
            // 优化：只获取当前页需要的数据，而不是先获取100个
            // 先获取构建总数（不获取详情，快速）
            com.offbytwo.jenkins.model.JobWithDetails jobDetailsForCount = jenkinsClient.getJobDetails(jobName);
            int totalBuilds = 0;
            if (jobDetailsForCount != null) {
                List<com.offbytwo.jenkins.model.Build> allBuilds = jobDetailsForCount.getBuilds();
                if (allBuilds != null) {
                    totalBuilds = allBuilds.size();
                }
            }
            
            // 只获取当前页需要的构建数量
            // 计算需要获取的构建范围
            int startIndex = (page - 1) * pageSize;
            int endIndex = startIndex + pageSize;
            int limit = endIndex; // 获取到 endIndex 为止的构建
            
            List<Map<String, Object>> buildHistoryRaw = jenkinsClient.getBuildHistory(jobName, limit);
            
            // 只取当前页的数据
            if (buildHistoryRaw.size() > startIndex) {
                buildHistoryRaw = buildHistoryRaw.subList(
                    startIndex, 
                    Math.min(startIndex + pageSize, buildHistoryRaw.size())
                );
            } else {
                buildHistoryRaw = Collections.emptyList();
            }
            
            // 先创建所有构建的基本信息（不获取阶段信息，快速返回）
            List<BuildHistoryDTO> buildHistory = new ArrayList<>();
            Map<String, List<Long>> stageDurationsMap = new HashMap<>(); // 用于计算平均时间
            
            for (Map<String, Object> buildRaw : buildHistoryRaw) {
                Integer buildNumber = (Integer) buildRaw.get("number");
                String buildUrl = (String) buildRaw.get("url");
                String status = (String) buildRaw.get("result");
                Long timestamp = (Long) buildRaw.get("timestamp");
                Long duration = (Long) buildRaw.get("duration");
                Boolean building = (Boolean) buildRaw.get("building");
                
                BuildHistoryDTO buildHistoryDTO = new BuildHistoryDTO();
                buildHistoryDTO.setBuildNumber(buildNumber);
                buildHistoryDTO.setBuildUrl(buildUrl);
                buildHistoryDTO.setStatus(status != null ? status : (building != null && building ? "BUILDING" : "UNKNOWN"));
                buildHistoryDTO.setTotalDuration(duration);
                buildHistoryDTO.setTotalDurationText(formatDuration(duration));
                
                if (timestamp != null && timestamp > 0) {
                    buildHistoryDTO.setBuildTime(new Date(timestamp));
                    buildHistoryDTO.setBuildTimeText(formatBuildTime(timestamp));
                }
                
                // 不在这里获取阶段信息，让前端异步加载
                buildHistoryDTO.setStages(Collections.emptyList());
                
                buildHistory.add(buildHistoryDTO);
            }
            
            pipelineView.setBuildHistory(buildHistory);
            
            // 设置分页信息
            pipelineView.setTotalBuilds(totalBuilds);
            pipelineView.setCurrentPage(page);
            pipelineView.setPageSize(pageSize);
            pipelineView.setTotalPages((int) Math.ceil((double) totalBuilds / pageSize));
            
            // 计算平均阶段时间
            Map<String, Long> averageStageTimes = new HashMap<>();
            Map<String, String> averageStageTimesText = new HashMap<>();
            
            for (Map.Entry<String, List<Long>> entry : stageDurationsMap.entrySet()) {
                String stageName = entry.getKey();
                List<Long> durations = entry.getValue();
                if (!durations.isEmpty()) {
                    long average = durations.stream().mapToLong(Long::longValue).sum() / durations.size();
                    averageStageTimes.put(stageName, average);
                    averageStageTimesText.put(stageName, formatDuration(average));
                }
            }
            
            pipelineView.setAverageStageTimes(averageStageTimes);
            pipelineView.setAverageStageTimesText(averageStageTimesText);
            
            // 计算完整运行平均时间
            if (!buildHistory.isEmpty()) {
                long totalDuration = buildHistory.stream()
                    .filter(b -> b.getTotalDuration() != null && b.getTotalDuration() > 0)
                    .mapToLong(BuildHistoryDTO::getTotalDuration)
                    .sum();
                long count = buildHistory.stream()
                    .filter(b -> b.getTotalDuration() != null && b.getTotalDuration() > 0)
                    .count();
                if (count > 0) {
                    long averageFullRun = totalDuration / count;
                    pipelineView.setAverageFullRunTime(averageFullRun);
                    pipelineView.setAverageFullRunTimeText(formatDuration(averageFullRun));
                }
            }
            
            return pipelineView;
        } catch (Exception e) {
            throw new RuntimeException("获取Pipeline视图失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取单个构建的阶段信息（用于分步加载）
     */
    @GetMapping("/pipeline/{jobName}/build/{buildNumber}/stages")
    public ResponseEntity<?> getBuildStages(@PathVariable String jobName, 
                                                  @PathVariable Integer buildNumber) {
        try {
            List<Map<String, Object>> stagesRaw = jenkinsClient.getPipelineStages(jobName, buildNumber);
            
            // 如果返回 null，表示明确不是 Pipeline 构建
            if (stagesRaw == null) {
                // 返回 404，表示不是 Pipeline 构建
                return ResponseEntity.status(404).body(Collections.singletonMap("error", "NOT_PIPELINE"));
            }
            
            // 如果返回空列表，可能是临时问题（网络、超时等），返回 503 表示服务暂时不可用
            if (stagesRaw.isEmpty()) {
                // 返回 503，表示可能是临时问题，可以重试
                return ResponseEntity.status(503).body(Collections.singletonMap("error", "LOAD_FAILED"));
            }
            
            // 正常返回阶段信息
            return ResponseEntity.ok(convertStages(stagesRaw));
        } catch (Exception e) {
            // 其他异常，返回 500
            return ResponseEntity.status(500).body(Collections.singletonMap("error", "INTERNAL_ERROR"));
        }
    }

    /**
     * 获取Pipeline阶段的日志
     */
    @GetMapping("/pipeline/{jobName}/build/{buildNumber}/stage/{stageId}/log")
    public Map<String, String> getStageLog(@PathVariable String jobName,
                                            @PathVariable Integer buildNumber,
                                            @PathVariable String stageId,
                                            @RequestParam(required = false) String stageName) {
        try {
            String log = jenkinsClient.getStageLog(jobName, buildNumber, stageId, stageName != null ? stageName : "");
            Map<String, String> result = new HashMap<>();
            result.put("log", log);
            result.put("jobName", jobName);
            result.put("buildNumber", String.valueOf(buildNumber));
            result.put("stageId", stageId);
            result.put("stageName", stageName != null ? stageName : "");
            return result;
        } catch (Exception e) {
            throw new RuntimeException("获取阶段日志失败: " + e.getMessage(), e);
        }
    }

    /**
     * 转换阶段数据
     */
    private List<PipelineStageDTO> convertStages(List<Map<String, Object>> stagesRaw) {
        if (stagesRaw == null || stagesRaw.isEmpty()) {
            return Collections.emptyList();
        }
        
        return stagesRaw.stream().map(stageRaw -> {
            PipelineStageDTO stage = new PipelineStageDTO();
            stage.setId((String) stageRaw.get("id"));
            stage.setName((String) stageRaw.get("name"));
            stage.setStatus((String) stageRaw.get("status"));
            
            Object durationObj = stageRaw.get("durationMillis");
            if (durationObj instanceof Number) {
                Long duration = ((Number) durationObj).longValue();
                stage.setDurationMillis(duration);
                stage.setDurationText(formatDuration(duration));
            }
            
            Object startTimeObj = stageRaw.get("startTimeMillis");
            if (startTimeObj instanceof Number) {
                stage.setStartTimeMillis(((Number) startTimeObj).longValue());
            }
            
            // 处理子阶段
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subStagesRaw = (List<Map<String, Object>>) stageRaw.get("subStages");
            if (subStagesRaw != null && !subStagesRaw.isEmpty()) {
                stage.setSubStages(convertStages(subStagesRaw));
            }
            
            return stage;
        }).collect(Collectors.toList());
    }

    /**
     * 转换阶段数据为Map格式（用于构建状态API）
     */
    private List<Map<String, Object>> convertStagesToMap(List<Map<String, Object>> stagesRaw) {
        if (stagesRaw == null || stagesRaw.isEmpty()) {
            return Collections.emptyList();
        }
        
        return stagesRaw.stream().map(stageRaw -> {
            Map<String, Object> stageMap = new HashMap<>();
            stageMap.put("id", stageRaw.get("id"));
            stageMap.put("name", stageRaw.get("name"));
            stageMap.put("status", stageRaw.get("status"));
            
            Object durationObj = stageRaw.get("durationMillis");
            if (durationObj instanceof Number) {
                Long duration = ((Number) durationObj).longValue();
                stageMap.put("durationMillis", duration);
                stageMap.put("durationText", formatDuration(duration));
            }
            
            Object startTimeObj = stageRaw.get("startTimeMillis");
            if (startTimeObj instanceof Number) {
                stageMap.put("startTimeMillis", ((Number) startTimeObj).longValue());
            }
            
            // 处理子阶段
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> subStagesRaw = (List<Map<String, Object>>) stageRaw.get("subStages");
            if (subStagesRaw != null && !subStagesRaw.isEmpty()) {
                stageMap.put("subStages", convertStagesToMap(subStagesRaw));
            }
            
            return stageMap;
        }).collect(Collectors.toList());
    }

    /**
     * 格式化持续时间
     */
    private String formatDuration(Long durationMillis) {
        if (durationMillis == null || durationMillis <= 0) {
            return "0ms";
        }
        
        if (durationMillis < 1000) {
            return durationMillis + "ms";
        } else if (durationMillis < 60000) {
            double seconds = durationMillis / 1000.0;
            return String.format("%.1fs", seconds);
        } else {
            long minutes = durationMillis / 60000;
            long seconds = (durationMillis % 60000) / 1000;
            return minutes + "m " + seconds + "s";
        }
    }

    /**
     * 格式化构建时间
     */
    private String formatBuildTime(Long timestamp) {
        if (timestamp == null || timestamp <= 0) {
            return "-";
        }
        
        Date date = new Date(timestamp);
        SimpleDateFormat sdf = new SimpleDateFormat("MM月dd日 HH:mm");
        return sdf.format(date);
    }

    /**
     * 触发Jenkins作业构建
     */
    /**
     * 获取作业的参数定义
     */
    @GetMapping("/job/{jobName}/parameters")
    public List<Map<String, Object>> getJobParameters(@PathVariable String jobName) {
        try {
            return jenkinsClient.getJobParameters(jobName);
        } catch (Exception e) {
            throw new RuntimeException("获取作业参数定义失败: " + e.getMessage(), e);
        }
    }

    /**
     * 获取 GitParameter 的分支列表
     */
    @GetMapping("/job/{jobName}/parameter/{paramName}/choices")
    public List<String> getParameterChoices(@PathVariable String jobName, 
                                            @PathVariable String paramName) {
        try {
            return jenkinsClient.getParameterChoices(jobName, paramName);
        } catch (Exception e) {
            log.error("获取参数选项失败，作业: {}, 参数: {}", jobName, paramName, e);
            return Collections.emptyList();
        }
    }
    
    /**
     * 触发Jenkins作业构建（支持参数）
     */
    @PostMapping("/build/{jobName}")
    public Map<String, Object> buildJob(@PathVariable String jobName, 
                                        @RequestBody(required = false) Map<String, String> parameters) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            if (parameters == null) {
                parameters = Collections.emptyMap();
            }
            log.info("收到构建请求，作业: {}, 参数数量: {}", jobName, parameters.size());
            if (!parameters.isEmpty()) {
                log.debug("构建参数: {}", parameters);
            }
            
            int buildNumber = jenkinsClient.buildJob(jobName, parameters);
            result.put("success", true);
            result.put("buildNumber", buildNumber);
            result.put("message", "构建已触发，构建号: #" + buildNumber);
            log.info("构建请求处理成功，作业: {}, 构建号: #{}", jobName, buildNumber);
        } catch (Exception e) {
            log.error("构建请求处理失败，作业: {}", jobName, e);
            result.put("success", false);
            result.put("message", "触发构建失败: " + e.getMessage());
        }
        
        return result;
    }

    /**
     * 获取构建状态和进度信息（包含阶段信息）
     */
    @GetMapping("/build/{jobName}/{buildNumber}/status")
    public Map<String, Object> getBuildStatus(@PathVariable String jobName, 
                                               @PathVariable int buildNumber) {
        Map<String, Object> result = new HashMap<>();
        
        try {
            com.offbytwo.jenkins.model.BuildWithDetails buildDetails = jenkinsClient.getBuildDetails(jobName, buildNumber);
            
            if (buildDetails == null) {
                result.put("exists", false);
                result.put("building", false);
                result.put("status", "UNKNOWN");
                return result;
            }
            
            result.put("exists", true);
            result.put("building", buildDetails.isBuilding());
            
            if (buildDetails.getResult() != null) {
                result.put("status", buildDetails.getResult().name());
            } else {
                result.put("status", buildDetails.isBuilding() ? "BUILDING" : "UNKNOWN");
            }
            
            // 获取构建持续时间
            Long duration = buildDetails.getDuration();
            if (duration != null && duration > 0) {
                result.put("duration", duration);
            }
            
            // 获取构建时间戳
            Long timestamp = buildDetails.getTimestamp();
            if (timestamp != null && timestamp > 0) {
                result.put("timestamp", timestamp);
            }
            
            // 获取构建URL
            result.put("url", buildDetails.getUrl());
            
            // 获取Pipeline阶段信息
            try {
                List<Map<String, Object>> stagesRaw = jenkinsClient.getPipelineStages(jobName, buildNumber);
                if (stagesRaw != null && !stagesRaw.isEmpty()) {
                    // 转换阶段信息为Map格式
                    List<Map<String, Object>> stages = convertStagesToMap(stagesRaw);
                    result.put("stages", stages);
                    result.put("isPipeline", true);
                } else {
                    result.put("stages", Collections.emptyList());
                    result.put("isPipeline", false);
                }
            } catch (Exception e) {
                log.debug("获取构建阶段信息失败（可能不是Pipeline构建）: {} #{}", jobName, buildNumber, e);
                result.put("stages", Collections.emptyList());
                result.put("isPipeline", false);
            }
            
        } catch (Exception e) {
            log.error("获取构建状态失败，作业: {}, 构建号: #{}", jobName, buildNumber, e);
            result.put("exists", false);
            result.put("building", false);
            result.put("status", "ERROR");
            result.put("error", e.getMessage());
        }
        
        return result;
    }
}

