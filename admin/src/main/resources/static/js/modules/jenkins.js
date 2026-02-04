// ============================================
// Jenkins 视图模块
// ============================================

let currentJobName = null;
let allJobs = []; // 存储所有作业
let currentView = 'all'; // 当前选中的视图
let currentPipelinePage = 1; // 当前Pipeline分页
let pipelinePageSize = 10; // 每页大小
let jobsCache = null; // 缓存作业列表
let jobsCacheTime = 0; // 缓存时间戳
const CACHE_DURATION = 30000; // 缓存30秒

// 智能刷新：如果在 Pipeline 视图页面，刷新 Pipeline 视图；否则刷新作业列表
async function refreshJenkinsView() {
    // 检查是否在 Pipeline 视图页面
    const pipelineContainer = document.getElementById('pipelineViewContainer');
    if (pipelineContainer && currentJobName) {
        // 在 Pipeline 视图页面，刷新 Pipeline 视图
        console.log('刷新 Pipeline 视图:', currentJobName);
        await loadPipelineView(currentJobName, currentPipelinePage);
    } else {
        // 在作业列表页面，刷新作业列表
        console.log('刷新作业列表');
        await loadJenkinsJobsView();
    }
}
window.refreshJenkinsView = refreshJenkinsView;

// 加载 Jenkins 作业列表视图
async function loadJenkinsJobsView() {
    // 清除当前 Pipeline 视图状态
    currentJobName = null;
    
    const container = document.getElementById('jenkinsJobsView');
    if (!container) {
        console.error('jenkinsJobsView container not found');
        return;
    }
    
    // 显示加载状态（带进度提示）
    container.innerHTML = `
        <div style="padding: 40px; text-align: center;">
            <div style="color: #999; margin-bottom: 10px;">正在加载Jenkins作业列表...</div>
            <div style="color: #667eea; font-size: 14px;">这可能需要几秒钟时间，请稍候</div>
            <div style="margin-top: 20px;">
                <div style="display: inline-block; width: 40px; height: 40px; border: 4px solid #f3f4f6; border-top-color: #667eea; border-radius: 50%; animation: spin 1s linear infinite;"></div>
            </div>
            <style>
                @keyframes spin {
                    to { transform: rotate(360deg); }
                }
            </style>
        </div>
    `;
    
    try {
        // 检查缓存
        const now = Date.now();
        if (jobsCache && (now - jobsCacheTime) < CACHE_DURATION) {
            console.log('Using cached jobs data');
            allJobs = jobsCache;
            renderJenkinsViewWithCategories(jobsCache);
            // 后台异步刷新健康度信息
            loadHealthInfoAsync();
            return;
        }
        
        // 快速加载：不包含健康度信息（健康度信息可以后续异步加载）
        const response = await fetch('/api/jenkins/jobs?includeHealth=false');
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error('获取作业列表失败: ' + (errorText || response.statusText));
        }
        
        const jobs = await response.json();
        allJobs = jobs; // 保存所有作业
        jobsCache = jobs; // 更新缓存
        jobsCacheTime = now;
        
        // 立即渲染视图分类和作业列表（不包含健康度）
        renderJenkinsViewWithCategories(jobs);
        
        // 延迟恢复最新构建进度显示，确保页面元素已渲染完成
        setTimeout(() => {
            restoreLatestBuildProgress(jobs).catch(error => {
                console.error('恢复构建进度失败:', error);
            });
        }, 100);
        
        // 后台异步加载健康度信息（可选，提升用户体验）
        loadHealthInfoAsync();
    } catch (error) {
        console.error('Load Jenkins jobs view error:', error);
        container.innerHTML = 
            '<div style="padding: 40px; text-align: center; color: #dc2626;">加载失败: ' + error.message + '</div>';
    }
}
// 注册到全局作用域
window.loadJenkinsJobsView = loadJenkinsJobsView;

// 异步加载健康度信息（后台加载，不阻塞页面显示）
async function loadHealthInfoAsync() {
    try {
        // 延迟500ms后加载，确保页面已经渲染完成
        await new Promise(resolve => setTimeout(resolve, 500));
        
        const response = await fetch('/api/jenkins/jobs?includeHealth=true');
        if (response.ok) {
            const jobsWithHealth = await response.json();
            // 更新缓存中的健康度信息
            if (jobsCache && jobsWithHealth) {
                const healthMap = new Map();
                jobsWithHealth.forEach(job => {
                    healthMap.set(job.name, {
                        healthIcon: job.healthIcon,
                        healthDescription: job.healthDescription
                    });
                });
                
                // 更新缓存
                jobsCache.forEach(job => {
                    const healthInfo = healthMap.get(job.name);
                    if (healthInfo) {
                        job.healthIcon = healthInfo.healthIcon;
                        job.healthDescription = healthInfo.healthDescription;
                    }
                });
                
                // 更新当前显示的作业列表
                allJobs.forEach(job => {
                    const healthInfo = healthMap.get(job.name);
                    if (healthInfo) {
                        job.healthIcon = healthInfo.healthIcon;
                        job.healthDescription = healthInfo.healthDescription;
                    }
                });
                
                // 重新渲染健康度列
                updateHealthIcons(jobsWithHealth);
            }
        }
    } catch (error) {
        console.warn('Failed to load health info:', error);
        // 健康度加载失败不影响主功能
    }
}

// 更新健康度图标显示
function updateHealthIcons(jobsWithHealth) {
    if (!jobsWithHealth || jobsWithHealth.length === 0) return;
    
    const healthMap = new Map();
    jobsWithHealth.forEach(job => {
        healthMap.set(job.name, {
            healthIcon: job.healthIcon,
            healthDescription: job.healthDescription
        });
    });
    
    // 更新表格中的健康度单元格（使用 data-job-name 属性更可靠）
    document.querySelectorAll('.health-cell[data-job-name]').forEach(healthCell => {
        const jobName = healthCell.getAttribute('data-job-name');
        const healthInfo = healthMap.get(jobName);
        if (healthInfo && healthInfo.healthIcon !== 'unknown') {
            healthCell.innerHTML = getHealthIcon(healthInfo.healthIcon);
            healthCell.title = healthInfo.healthDescription || '';
        }
    });
}

// 渲染带视图分类的Jenkins视图
function renderJenkinsViewWithCategories(jobs) {
    const container = document.getElementById('jenkinsJobsView');
    if (!container) return;
    
    // 提取视图分类（基于作业名称的前缀或模式）
    const views = extractViewsFromJobs(jobs);
    
    // 渲染视图分类标签
    const viewTabsHtml = renderViewTabs(views);
    
    // 根据当前视图过滤作业
    const filteredJobs = filterJobsByView(jobs, currentView);
    
    // 渲染作业列表
    const jobsTableHtml = renderJenkinsJobsTable(filteredJobs);
    
    container.innerHTML = viewTabsHtml + jobsTableHtml;
}

// 从作业列表中提取视图分类
function extractViewsFromJobs(jobs) {
    const views = new Set(['all']); // 默认包含"所有"
    
    if (!jobs || jobs.length === 0) {
        return Array.from(views);
    }
    
    // 根据作业名称提取视图
    // 策略1: 按作业名称的前缀（如 dev-xxx, test-xxx）
    jobs.forEach(job => {
        if (job.name) {
            // 检查是否有分隔符（- 或 _）
            const parts = job.name.split(/[-_]/);
            if (parts.length > 1) {
                const prefix = parts[0].toLowerCase();
                // 只添加常见的环境前缀
                if (['dev', 'test', 'uat', 'pre', 'prod', 'staging', 'demo'].includes(prefix)) {
                    views.add(prefix);
                }
            }
        }
    });
    
    // 策略2: 如果作业名称直接是视图名（如 dev, test）
    jobs.forEach(job => {
        if (job.name) {
            const name = job.name.toLowerCase();
            if (['dev', 'test', 'uat', 'pre', 'prod', 'staging', 'demo'].includes(name)) {
                views.add(name);
            }
        }
    });
    
    // 按字母顺序排序（all 始终在第一位）
    const sortedViews = Array.from(views).sort((a, b) => {
        if (a === 'all') return -1;
        if (b === 'all') return 1;
        return a.localeCompare(b);
    });
    
    return sortedViews;
}

// 渲染视图分类标签（带数量徽章）
function renderViewTabs(views) {
    // 计算每个视图的作业数量
    const viewCounts = {};
    views.forEach(view => {
        if (view === 'all') {
            viewCounts[view] = allJobs.length;
        } else {
            viewCounts[view] = filterJobsByView(allJobs, view).length;
        }
    });
    
    const tabsHtml = views.map(view => {
        const viewName = view === 'all' ? '所有' : view.toLowerCase();
        const isActive = view === currentView;
        const count = viewCounts[view] || 0;
        
        return `
            <div class="view-tab-item ${isActive ? 'active' : ''}" 
                 onclick="switchJenkinsView('${view}')"
                 style="display: inline-flex; align-items: center; padding: 8px 16px; 
                        margin-right: 24px; cursor: pointer; position: relative;
                        border-bottom: 2px solid ${isActive ? '#667eea' : 'transparent'};
                        transition: all 0.2s;">
                <span style="color: ${isActive ? '#667eea' : '#6b7280'}; 
                            font-size: 14px; font-weight: ${isActive ? '500' : '400'};
                            margin-right: 8px;">
                    ${viewName}
                </span>
                <span style="display: inline-flex; align-items: center; justify-content: center;
                            min-width: 20px; height: 20px; padding: 0 6px;
                            background: ${isActive ? '#667eea' : '#e5e7eb'};
                            color: ${isActive ? '#fff' : '#6b7280'};
                            border-radius: 10px; font-size: 12px; font-weight: 500;">
                    ${count}
                </span>
            </div>
        `;
    }).join('');
    
    return `
        <div style="background: #fff; padding: 0; margin-bottom: 20px; border-bottom: 1px solid #e5e7eb;">
            <div style="display: flex; align-items: center; padding: 0 20px;">
                ${tabsHtml}
            </div>
        </div>
    `;
}

// 根据视图过滤作业
function filterJobsByView(jobs, view) {
    if (view === 'all' || !view) {
        return jobs;
    }
    
    return jobs.filter(job => {
        if (!job.name) return false;
        
        const jobName = job.name.toLowerCase();
        const viewLower = view.toLowerCase();
        
        // 检查作业名称是否以视图名开头（如 dev-xxx）
        if (jobName.startsWith(viewLower + '-') || jobName.startsWith(viewLower + '_')) {
            return true;
        }
        
        // 检查作业名称是否完全匹配视图名（如 dev）
        if (jobName === viewLower) {
            return true;
        }
        
        return false;
    });
}

// 切换视图
function switchJenkinsView(view) {
    currentView = view;
    renderJenkinsViewWithCategories(allJobs);
}
window.switchJenkinsView = switchJenkinsView;

// 渲染作业列表表格（从原来的 renderJenkinsJobsView 函数中提取）
function renderJenkinsJobsTable(jobs) {
    if (!jobs || jobs.length === 0) {
        return '<div style="padding: 40px; text-align: center; color: #999;">当前视图下暂无作业</div>';
    }
    
    return `
        <div class="jenkins-jobs-table">
            <table class="jenkins-table">
                <thead>
                    <tr>
                        <th style="width: 40px;">S</th>
                        <th style="width: 40px;">W</th>
                        <th>名称</th>
                        <th>上次持续时间</th>
                        <th style="width: 200px;">构建进度</th>
                        <th style="width: 80px;">操作</th>
                    </tr>
                </thead>
                <tbody>
                    ${jobs.map(job => {
                        const statusIcon = getStatusIcon(job.status, job.building);
                        const healthIcon = getHealthIcon(job.healthIcon);
                        const duration = job.lastDurationText || '-';
                        
                        return `
                            <tr data-job-name="${job.name}">
                                <td class="status-cell">${statusIcon}</td>
                                <td class="health-cell" data-job-name="${job.name}">${healthIcon}</td>
                                <td>
                                    <a href="${job.url}" target="_blank" style="color: #667eea; text-decoration: none;">
                                        ${job.name}
                                    </a>
                                </td>
                                <td>${duration}</td>
                                <td class="build-progress-cell" data-job-name="${job.name}" style="padding: 8px; min-width: 400px;">
                                    <div id="build-progress-${job.name}" style="display: none;">
                                        <div id="build-stages-${job.name}" style="display: flex; flex-direction: row; gap: 8px; overflow-x: auto; padding-bottom: 4px;">
                                            <!-- 阶段卡片将动态插入这里 -->
                                        </div>
                                    </div>
                                </td>
                                <td>
                                    <div style="display: flex; align-items: center; gap: 8px; justify-content: center;">
                                        <button onclick="window.showPipelineView('${job.name}')" 
                                                style="padding: 6px 14px; background: #667eea; color: #fff; 
                                                       border: none; border-radius: 4px; cursor: pointer; 
                                                       font-size: 13px; font-weight: 500; 
                                                       height: 32px; min-width: 60px;
                                                       transition: background 0.2s;"
                                                onmouseover="this.style.background='#5568d3'" 
                                                onmouseout="this.style.background='#667eea'">
                                            查看
                                    </button>
                                        <button onclick="window.buildJenkinsJob('${job.name}')" 
                                                style="padding: 0; background: #10b981; color: #fff; 
                                                       border: none; border-radius: 4px; cursor: pointer; 
                                                       display: flex; align-items: center; justify-content: center;
                                                       width: 32px; height: 32px; min-width: 32px;
                                                       transition: background 0.2s;"
                                                onmouseover="this.style.background='#059669'" 
                                                onmouseout="this.style.background='#10b981'">
                                            <svg width="12" height="12" viewBox="0 0 12 12" fill="currentColor" style="margin-left: 1px;">
                                                <path d="M2 2l8 4-8 4V2z"/>
                                            </svg>
                                    </button>
                                    </div>
                                </td>
                            </tr>
                        `;
                    }).join('')}
                </tbody>
            </table>
        </div>
    `;
}
    
// 渲染 Jenkins 作业列表视图（保留用于兼容性，实际使用 renderJenkinsViewWithCategories）
function renderJenkinsJobsView(jobs) {
    renderJenkinsViewWithCategories(jobs);
}

// 获取状态图标
function getStatusIcon(status, building) {
    if (building) {
        return '<span class="status-icon building" title="构建中">🔄</span>';
    }
    
    switch (status) {
        case 'SUCCESS':
            return '<span class="status-icon success" title="成功">✓</span>';
        case 'FAILURE':
            return '<span class="status-icon failure" title="失败">✗</span>';
        case 'UNSTABLE':
            return '<span class="status-icon unstable" title="不稳定">⚠</span>';
        case 'ABORTED':
            return '<span class="status-icon aborted" title="已中止">⊘</span>';
        default:
            return '<span class="status-icon unknown" title="未知">?</span>';
    }
}

// 获取健康度图标
function getHealthIcon(healthIcon) {
    switch (healthIcon) {
        case 'sun':
            return '<span class="health-icon sun" title="健康度良好">☀️</span>';
        case 'cloud':
            return '<span class="health-icon cloud" title="健康度一般">☁️</span>';
        case 'storm':
            return '<span class="health-icon storm" title="健康度较差">⛈️</span>';
        default:
            return '<span class="health-icon unknown" title="未知">❓</span>';
    }
}

// 格式化构建信息
function formatBuildInfo(buildNumber, buildTime) {
    if (!buildNumber) {
        return '-';
    }
    
    let text = `#${buildNumber}`;
    if (buildTime) {
        const date = new Date(buildTime);
        const now = new Date();
        const diffDays = Math.floor((now - date) / (1000 * 60 * 60 * 24));
        const month = date.getMonth() + 1;
        const day = date.getDate();
        text += ` (${month}月${diffDays}天)`;
    }
    
    return text;
}

// 显示 Pipeline 阶段视图（直接在页面上显示，不使用模态框）
async function showPipelineView(jobName) {
    currentJobName = jobName;
    
    const container = document.getElementById('jenkinsJobsView');
    if (!container) {
        console.error('jenkinsJobsView container not found');
        return;
    }
    
    // 显示加载状态
    container.innerHTML = `
        <div style="margin-bottom: 20px;">
            <button class="btn-secondary" onclick="window.loadJenkinsJobsView()" 
                    style="margin-bottom: 10px;">← 返回作业列表</button>
        </div>
        <div id="pipelineViewContainer" style="background: #fff; padding: 20px; border-radius: 4px;">
            <div style="padding: 40px; text-align: center; color: #999;">加载中...</div>
        </div>
    `;
    
    // 加载 Pipeline 数据
    await loadPipelineView(jobName);
}
window.showPipelineView = showPipelineView;

// 查看 Pipeline 阶段视图（保留用于兼容性，使用模态框）
async function viewPipeline(jobName) {
    currentJobName = jobName;
    
    if (typeof window.showModal !== 'function') {
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    // 显示 Pipeline 视图模态框
    const content = `
        <div id="pipelineViewContainer" style="max-height: 80vh; overflow-y: auto;">
            <div style="padding: 20px; text-align: center; color: #999;">加载中...</div>
        </div>
    `;
    
    window.showModal(`Pipeline 视图 - ${jobName}`, content, null, 'large');
    
    // 加载 Pipeline 数据
    await loadPipelineView(jobName);
}

// 加载 Pipeline 阶段视图（分步加载：先加载基本信息，再异步加载阶段详情）
async function loadPipelineView(jobName, page = 1) {
    const container = document.getElementById('pipelineViewContainer');
    if (!container) {
        console.error('pipelineViewContainer not found');
        return;
    }
    
    currentPipelinePage = page;
    
    try {
        // 第一步：快速加载基本信息（不包含阶段详情）
        const response = await fetch(`/api/jenkins/pipeline/${encodeURIComponent(jobName)}?page=${page}&pageSize=${pipelinePageSize}`);
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error('获取 Pipeline 视图失败: ' + (errorText || response.statusText));
        }
        
        const pipelineView = await response.json();
        
        // 先渲染基本信息（不包含阶段详情）
        renderPipelineViewBasic(pipelineView);
        
        // 第二步：异步加载每个构建的阶段详情
        if (pipelineView.buildHistory && pipelineView.buildHistory.length > 0) {
            loadBuildStagesAsync(jobName, pipelineView.buildHistory);
        }
    } catch (error) {
        console.error('Load pipeline view error:', error);
        container.innerHTML = 
            '<div style="padding: 40px; text-align: center; color: #dc2626;">加载失败: ' + error.message + '</div>';
    }
}

// 异步加载构建阶段信息（优化：分批加载，避免过多并发请求）
// 加载单个构建的阶段信息（带重试机制）
async function loadBuildStagesWithRetry(jobName, buildNumber, maxRetries = 2) {
    for (let attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            const response = await fetch(`/api/jenkins/pipeline/${encodeURIComponent(jobName)}/build/${buildNumber}/stages`);
            
            if (response.ok) {
                const stages = await response.json();
                // 检查阶段信息是否为空
                if (stages && Array.isArray(stages) && stages.length > 0) {
                    return { success: true, stages: stages, errorType: null };
                } else {
                    // 阶段信息为空，可能是非 Pipeline 构建或构建太旧
                    return { success: false, stages: null, errorType: 'not_pipeline' };
                }
            } else if (response.status === 404) {
                // 404 表示明确不是 Pipeline 构建
                // 尝试读取响应体（可选）
                try {
                    const errorBody = await response.json();
                    console.log(`Build #${buildNumber} is not a Pipeline build (404):`, errorBody);
                } catch (e) {
                    // 忽略解析错误
                }
                return { success: false, stages: null, errorType: 'not_pipeline' };
            } else if (response.status === 503) {
                // 503 表示可能是临时问题（网络、超时等），可以重试
                try {
                    const errorBody = await response.json();
                    console.log(`Build #${buildNumber} stages load failed (503):`, errorBody);
                } catch (e) {
                    // 忽略解析错误
                }
                
                if (attempt < maxRetries) {
                    console.log(`Build #${buildNumber} stages load failed (503), retrying... (attempt ${attempt + 1}/${maxRetries})`);
                    // 等待后重试，指数退避
                    await new Promise(resolve => setTimeout(resolve, 1000 * (attempt + 1)));
                    continue;
                } else {
                    // 重试失败后，返回 load_failed 错误类型
                    return { success: false, stages: null, errorType: 'load_failed' };
                }
            } else {
                // 其他错误（500 等）
                try {
                    const errorBody = await response.json();
                    console.error(`Build #${buildNumber} stages load failed (${response.status}):`, errorBody);
                } catch (e) {
                    console.error(`Build #${buildNumber} stages load failed (${response.status})`);
                }
                
                if (attempt < maxRetries) {
                    console.log(`Build #${buildNumber} stages load failed (${response.status}), retrying... (attempt ${attempt + 1}/${maxRetries})`);
                    await new Promise(resolve => setTimeout(resolve, 1000 * (attempt + 1)));
                    continue;
                } else {
                    return { success: false, stages: null, errorType: 'error' };
                }
            }
        } catch (error) {
            // 网络错误或其他异常
            if (attempt < maxRetries) {
                console.log(`Build #${buildNumber} stages load error, retrying... (attempt ${attempt + 1}/${maxRetries}):`, error.message);
                await new Promise(resolve => setTimeout(resolve, 1000 * (attempt + 1)));
                continue;
            } else {
                console.error(`Load stages for build #${buildNumber} error after ${maxRetries} retries:`, error);
                return { success: false, stages: null, errorType: 'error' };
            }
        }
    }
    return { success: false, stages: null, errorType: 'error' };
}

async function loadBuildStagesAsync(jobName, buildHistory) {
    // 保存 jobName 到全局变量，供 updateBuildStages 使用
    const savedJobName = jobName;
    if (!buildHistory || buildHistory.length === 0) return;
    
    // 优化：分批加载，每批3个，避免过多并发请求导致Jenkins服务器压力过大
    const BATCH_SIZE = 3;
    const batches = [];
    
    for (let i = 0; i < buildHistory.length; i += BATCH_SIZE) {
        batches.push(buildHistory.slice(i, i + BATCH_SIZE));
    }
    
    // 逐批加载
    for (const batch of batches) {
        // 并行加载当前批次
        const loadPromises = batch.map(async (build) => {
            if (!build.buildNumber) {
                console.warn('Build number is missing:', build);
                return;
            }
            
            try {
                const result = await loadBuildStagesWithRetry(savedJobName, build.buildNumber);
                
                if (result && result.success && result.stages) {
                    // 更新对应构建的阶段信息（传递 jobName）
                    updateBuildStages(savedJobName, build.buildNumber, result.stages);
                } else {
                    // 显示错误信息
                    updateBuildStagesWithError(build.buildNumber, result?.errorType || 'error');
                }
            } catch (error) {
                // 捕获任何未预期的错误，确保不会影响其他构建的加载
                console.error(`Unexpected error loading stages for build #${build.buildNumber}:`, error);
                updateBuildStagesWithError(build.buildNumber, 'error');
            }
        });
        
        // 等待当前批次完成（使用 Promise.allSettled 确保即使有失败也不会中断）
        const results = await Promise.allSettled(loadPromises);
        
        // 检查是否有失败的 Promise
        results.forEach((result, index) => {
            if (result.status === 'rejected') {
                const build = batch[index];
                console.error(`Failed to load stages for build #${build?.buildNumber}:`, result.reason);
                if (build && build.buildNumber) {
                    updateBuildStagesWithError(build.buildNumber, 'error');
                }
            }
        });
        
        // 批次之间稍微延迟，避免对Jenkins服务器造成过大压力
        if (batches.indexOf(batch) < batches.length - 1) {
            await new Promise(resolve => setTimeout(resolve, 100));
        }
    }
    
    // 重新计算平均阶段时间
    recalculateAverageStageTimes(buildHistory);
}

// 更新构建阶段信息（显示错误或提示）
function updateBuildStagesWithError(buildNumber, errorType) {
    const buildRow = document.querySelector(`[data-build-number="${buildNumber}"]`);
    if (!buildRow) return;
    
    const stagesContainer = buildRow.querySelector('.pipeline-build-stages');
    if (!stagesContainer) return;
    
    let message = '暂无阶段信息';
    let tooltip = '';
    let canRetry = false;
    
    if (errorType === 'not_pipeline') {
        message = '非 Pipeline 构建';
        tooltip = '此构建不是 Pipeline 类型，无法显示阶段信息';
    } else if (errorType === 'load_failed') {
        message = '加载失败，点击重试';
        tooltip = '阶段信息加载失败，可能是网络问题或 Jenkins 暂时不可用，点击可重试';
        canRetry = true;
    } else if (errorType === 'error') {
        message = '加载失败，点击重试';
        tooltip = '阶段信息加载失败，请点击重试';
        canRetry = true;
    }
    
    // 获取 jobName（从 data-job-name 属性或全局变量）
    const jobName = buildRow.getAttribute('data-job-name') || window.currentJobName || '';
    const retryButton = canRetry ? `
        <button onclick="retryLoadBuildStages('${jobName}', ${buildNumber})" 
                style="margin-top: 8px; padding: 4px 12px; background: #667eea; color: #fff; 
                       border: none; border-radius: 4px; cursor: pointer; font-size: 12px;">
            重试
        </button>
    ` : '';
    
    stagesContainer.innerHTML = `
        <div style="padding: 20px; text-align: center; color: #999; font-size: 13px;" title="${tooltip}">
            ${message}
            ${retryButton}
        </div>
    `;
}

// 重试加载构建阶段信息
async function retryLoadBuildStages(jobName, buildNumber) {
    const buildRow = document.querySelector(`[data-build-number="${buildNumber}"]`);
    if (!buildRow) return;
    
    const stagesContainer = buildRow.querySelector('.pipeline-build-stages');
    if (!stagesContainer) return;
    
    // 显示加载中
    stagesContainer.innerHTML = `
        <div style="padding: 20px; text-align: center; color: #667eea; font-size: 13px;">
            加载中...
        </div>
    `;
    
    try {
        // 重试加载（增加重试次数）
        const result = await loadBuildStagesWithRetry(jobName, buildNumber, 3);
        
        if (result && result.success && result.stages) {
            updateBuildStages(jobName, buildNumber, result.stages);
        } else {
            updateBuildStagesWithError(buildNumber, result?.errorType || 'error');
        }
    } catch (error) {
        console.error(`Retry load stages failed for build #${buildNumber}:`, error);
        updateBuildStagesWithError(buildNumber, 'error');
    }
}
window.retryLoadBuildStages = retryLoadBuildStages;

// 更新构建的阶段信息
function updateBuildStages(jobName, buildNumber, stages) {
    const buildRow = document.querySelector(`[data-build-number="${buildNumber}"]`);
    if (!buildRow) return;
    
    const stagesContainer = buildRow.querySelector('.pipeline-build-stages');
    if (!stagesContainer) return;
    
    // 渲染阶段
    let stagesHtml = '';
    if (stages && Array.isArray(stages) && stages.length > 0) {
        stagesHtml = stages.map((stage) => {
            const stageStatus = getStageStatusIcon(stage.status || 'UNKNOWN');
            // 格式化持续时间
            let stageDuration = '-';
            if (stage.durationText) {
                stageDuration = stage.durationText;
            } else if (stage.durationMillis) {
                stageDuration = formatDuration(stage.durationMillis);
            } else if (stage.duration) {
                stageDuration = stage.duration;
            }
            const stageName = stage.name || 'Unknown';
            const statusColor = getStageStatusColor(stage.status || 'UNKNOWN');
            
            // 添加点击事件查看日志
            const stageId = stage.id || stageName;
            // 转义特殊字符，避免在 onclick 中出错
            const escapedJobName = (jobName || '').replace(/'/g, "\\'");
            const escapedStageId = (stageId || '').replace(/'/g, "\\'");
            const escapedStageName = (stageName || '').replace(/'/g, "\\'");
            
            return `
                <div onclick="window.showStageLog('${escapedJobName}', ${buildNumber}, '${escapedStageId}', '${escapedStageName}')" 
                     style="display: inline-block; padding: 10px 12px; margin: 4px; 
                            background: ${statusColor}; border-radius: 4px; 
                            min-width: 120px; text-align: center; vertical-align: top;
                            border: 1px solid #e5e7eb; cursor: pointer; transition: all 0.2s;
                            box-shadow: 0 1px 2px rgba(0,0,0,0.1);"
                     onmouseover="this.style.transform='scale(1.05)'; this.style.boxShadow='0 2px 4px rgba(0,0,0,0.15)';"
                     onmouseout="this.style.transform='scale(1)'; this.style.boxShadow='0 1px 2px rgba(0,0,0,0.1)';"
                     title="点击查看日志">
                    <div style="font-size: 12px; font-weight: 500; margin-bottom: 6px; color: #1f2937;">
                        ${stageName}
                    </div>
                    <div style="font-size: 11px; color: #6b7280; margin-bottom: 4px;">
                        ${stageDuration}
                    </div>
                    <div style="font-size: 14px;">
                        ${stageStatus}
                    </div>
                </div>
            `;
        }).join('');
    } else {
        stagesHtml = '<div style="padding: 20px; text-align: center; color: #999; font-size: 13px;">暂无阶段信息</div>';
    }
    
    stagesContainer.innerHTML = stagesHtml;
}

// 显示阶段日志
async function showStageLog(jobName, buildNumber, stageId, stageName) {
    if (typeof window.showModal !== 'function') {
        alert('模态框功能未加载，请刷新页面');
        return;
    }
    
    // 显示加载中的模态框
    const loadingContent = `
        <div id="stageLogContainer" style="max-height: 70vh; overflow-y: auto; background: #1e1e1e; color: #d4d4d4; 
             padding: 20px; font-family: 'Consolas', 'Monaco', 'Courier New', monospace; font-size: 13px; 
             border-radius: 4px;">
            <div style="text-align: center; padding: 40px; color: #999;">
                <div style="display: inline-block; width: 40px; height: 40px; border: 4px solid #333; 
                            border-top-color: #667eea; border-radius: 50%; animation: spin 1s linear infinite; 
                            margin-bottom: 16px;"></div>
                <div>正在加载日志...</div>
            </div>
        </div>
    `;
    
    window.showModal(`构建日志 - ${stageName} (#${buildNumber})`, loadingContent, null, 'large');
    
    try {
        // 传递 stageName 参数，用于从完整日志中提取特定阶段的日志
        const url = `/api/jenkins/pipeline/${encodeURIComponent(jobName)}/build/${buildNumber}/stage/${encodeURIComponent(stageId)}/log?stageName=${encodeURIComponent(stageName)}`;
        const response = await fetch(url);
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error('获取日志失败: ' + (errorText || response.statusText));
        }
        
        const data = await response.json();
        const log = data.log || '暂无日志';
        
        // 渲染日志内容
        const logContainer = document.getElementById('stageLogContainer');
        if (logContainer) {
            // 转义HTML并保留换行
            const escapedLog = log
                .replace(/&/g, '&amp;')
                .replace(/</g, '&lt;')
                .replace(/>/g, '&gt;')
                .replace(/\n/g, '<br>')
                .replace(/\r/g, '');
            
            logContainer.innerHTML = `
                <div style="margin-bottom: 12px; padding-bottom: 12px; border-bottom: 1px solid #333;">
                    <div style="color: #667eea; font-weight: 600; margin-bottom: 4px;">作业: ${jobName}</div>
                    <div style="color: #999; font-size: 12px;">构建: #${buildNumber} | 阶段: ${stageName}</div>
                </div>
                <pre style="margin: 0; white-space: pre-wrap; word-wrap: break-word; line-height: 1.6;">${escapedLog}</pre>
            `;
            
            // 自动滚动到底部
            logContainer.scrollTop = logContainer.scrollHeight;
        }
    } catch (error) {
        console.error('Load stage log error:', error);
        const logContainer = document.getElementById('stageLogContainer');
        if (logContainer) {
            logContainer.innerHTML = `
                <div style="padding: 40px; text-align: center; color: #dc2626;">
                    <div style="font-size: 16px; font-weight: 600; margin-bottom: 8px;">加载失败</div>
                    <div style="font-size: 14px; color: #999;">${error.message}</div>
                </div>
            `;
        }
    }
}
window.showStageLog = showStageLog;

// 格式化持续时间（毫秒转可读格式）
function formatDuration(millis) {
    if (!millis || millis <= 0) return '-';
    const seconds = Math.floor(millis / 1000);
    const minutes = Math.floor(seconds / 60);
    const hours = Math.floor(minutes / 60);
    
    if (hours > 0) {
        return `${hours}h ${minutes % 60}m`;
    } else if (minutes > 0) {
        return `${minutes}m ${seconds % 60}s`;
    } else {
        return `${seconds}s`;
    }
}

// 重新计算平均阶段时间
function recalculateAverageStageTimes(buildHistory) {
    const stageDurationsMap = {};
    
    buildHistory.forEach(build => {
        if (build.stages && Array.isArray(build.stages)) {
            build.stages.forEach(stage => {
                if (stage.name && stage.durationMillis) {
                    if (!stageDurationsMap[stage.name]) {
                        stageDurationsMap[stage.name] = [];
                    }
                    stageDurationsMap[stage.name].push(stage.durationMillis);
                }
            });
        }
    });
    
    // 计算平均值并更新显示
    const averageStagesContainer = document.querySelector('.pipeline-average-stages');
    if (averageStagesContainer && Object.keys(stageDurationsMap).length > 0) {
        // 这里可以更新平均阶段时间的显示
        // 暂时先不实现，因为需要重新渲染整个视图
    }
}

// 渲染 Pipeline 阶段视图（基本信息，不包含阶段详情）
function renderPipelineViewBasic(pipelineView) {
    const container = document.getElementById('pipelineViewContainer');
    if (!container) {
        console.error('pipelineViewContainer not found');
        return;
    }
    
    if (!pipelineView) {
        container.innerHTML = '<div style="padding: 40px; text-align: center; color: #999;">暂无数据</div>';
        return;
    }
    
    const currentStatus = getStatusIcon(pipelineView.currentStatus, false);
    const averageFullRunTime = pipelineView.averageFullRunTimeText || '-';
    
    // 分页信息
    const totalBuilds = pipelineView.totalBuilds || 0;
    const currentPage = pipelineView.currentPage || 1;
    const pageSize = pipelineView.pageSize || 10;
    const totalPages = pipelineView.totalPages || 1;
    
    // 渲染平均阶段时间
    let averageStagesHtml = '';
    if (pipelineView.averageStageTimesText && Object.keys(pipelineView.averageStageTimesText).length > 0) {
        averageStagesHtml = `
            <div class="pipeline-average-stages">
                <div class="pipeline-average-header">
                    <strong>Average stage times:</strong>
                    <span>(full run time: ~${averageFullRunTime})</span>
                </div>
                <div class="pipeline-average-content">
                    ${Object.entries(pipelineView.averageStageTimesText).map(([name, time]) => {
                        const duration = pipelineView.averageStageTimes[name] || 0;
                        const maxDuration = Math.max(...Object.values(pipelineView.averageStageTimes || {}));
                        const widthPercent = maxDuration > 0 ? (duration / maxDuration * 100) : 0;
                        
                        return `
                            <div class="pipeline-average-item">
                                <span class="stage-name">${name}</span>
                                <span class="stage-time">${time}</span>
                                <div class="stage-bar-container">
                                    <div class="stage-bar" style="width: ${widthPercent}%"></div>
                                </div>
                            </div>
                        `;
                    }).join('')}
                </div>
            </div>
        `;
    }
    
    // 渲染构建历史（先显示基本信息，阶段信息异步加载）
    let buildHistoryHtml = '';
    if (pipelineView.buildHistory && Array.isArray(pipelineView.buildHistory) && pipelineView.buildHistory.length > 0) {
        buildHistoryHtml = pipelineView.buildHistory.map((build) => {
            const buildStatus = getStatusIcon(build.status || 'UNKNOWN', false);
            const buildTime = build.buildTimeText || build.buildTime || '-';
            const hasChanges = build.hasChanges ? '有变更' : 'No Changes';
            const totalDuration = build.totalDurationText || build.totalDuration || '-';
            const buildNumber = build.buildNumber || build.number || 'N/A';
            
            // 阶段信息先显示加载中，后续异步加载
            const stagesHtml = `
                <div class="pipeline-build-stages" style="display: flex; flex-wrap: wrap; gap: 4px;">
                    <div style="padding: 20px; text-align: center; color: #999; font-size: 13px; width: 100%;">
                        <span style="display: inline-block; width: 16px; height: 16px; border: 2px solid #e5e7eb; 
                                     border-top-color: #667eea; border-radius: 50%; animation: spin 1s linear infinite; 
                                     margin-right: 8px;"></span>
                        加载阶段信息中...
                    </div>
                        </div>
                    `;
                    
                    return `
                <div data-build-number="${buildNumber}" data-job-name="${pipelineView.jobName || ''}"
                     style="margin-bottom: 16px; padding: 16px; border: 1px solid #e5e7eb; 
                            border-radius: 6px; background: #fff; box-shadow: 0 1px 2px rgba(0,0,0,0.05);">
                    <div style="display: flex; align-items: center; gap: 16px; margin-bottom: 12px; 
                               padding-bottom: 12px; border-bottom: 1px solid #f3f4f6;">
                        <div style="font-weight: 600; color: #667eea; font-size: 14px;">#${buildNumber}</div>
                        <div style="color: #6b7280; font-size: 13px;">${buildTime}</div>
                        <div style="color: #6b7280; font-size: 13px;">${hasChanges}</div>
                        <div style="color: #6b7280; font-size: 13px;">${totalDuration}</div>
                        <div style="margin-left: auto;">${buildStatus}</div>
                    </div>
                    ${stagesHtml}
                        </div>
                    `;
                }).join('');
    } else {
        buildHistoryHtml = '<div style="padding: 40px; text-align: center; color: #999; font-size: 14px;">暂无构建历史</div>';
    }
    
    // 渲染分页控件
    let paginationHtml = '';
    if (totalPages > 1) {
        const prevDisabled = currentPage <= 1;
        const nextDisabled = currentPage >= totalPages;
        
        paginationHtml = `
            <div style="display: flex; justify-content: center; align-items: center; gap: 12px; margin-top: 20px; padding: 16px;">
                <button onclick="window.loadPipelineView('${pipelineView.jobName}', ${currentPage - 1})" 
                        ${prevDisabled ? 'disabled' : ''}
                        style="padding: 8px 16px; border: 1px solid #d1d5db; background: ${prevDisabled ? '#f3f4f6' : '#fff'}; 
                               color: ${prevDisabled ? '#9ca3af' : '#374151'}; border-radius: 4px; cursor: ${prevDisabled ? 'not-allowed' : 'pointer'};
                               font-size: 14px; ${prevDisabled ? 'opacity: 0.5;' : ''}">
                    上一页
                </button>
                <span style="color: #6b7280; font-size: 14px;">
                    第 ${currentPage} / ${totalPages} 页，共 ${totalBuilds} 条
                </span>
                <button onclick="window.loadPipelineView('${pipelineView.jobName}', ${currentPage + 1})" 
                        ${nextDisabled ? 'disabled' : ''}
                        style="padding: 8px 16px; border: 1px solid #d1d5db; background: ${nextDisabled ? '#f3f4f6' : '#fff'}; 
                               color: ${nextDisabled ? '#9ca3af' : '#374151'}; border-radius: 4px; cursor: ${nextDisabled ? 'not-allowed' : 'pointer'};
                               font-size: 14px; ${nextDisabled ? 'opacity: 0.5;' : ''}">
                    下一页
                </button>
                </div>
            `;
    }
    
    const html = `
        <div class="pipeline-view">
            <div class="pipeline-header">
                <h3>阶段视图</h3>
                <div class="pipeline-status">
                    <span>${currentStatus}</span>
                    <span style="margin-left: 8px;">${pipelineView.jobName}</span>
                </div>
            </div>
            
            ${averageStagesHtml}
            
            <div class="pipeline-build-history" style="margin-top: 30px;">
                <h4 style="margin: 0 0 20px 0; font-size: 16px; font-weight: 600; color: #1f2937;">构建历史</h4>
                <div style="background: #f9fafb; padding: 20px; border-radius: 6px; border: 1px solid #e5e7eb;">
                    ${buildHistoryHtml}
            </div>
                ${paginationHtml}
        </div>
        </div>
        <style>
            @keyframes spin {
                to { transform: rotate(360deg); }
            }
        </style>
    `;
    
    container.innerHTML = html;
}

// 渲染 Pipeline 阶段视图（保留用于兼容性）
function renderPipelineView(pipelineView) {
    renderPipelineViewBasic(pipelineView);
}

// 获取阶段状态图标
function getStageStatusIcon(status) {
    switch (status) {
        case 'SUCCESS':
        case 'SUCCESSFUL':
            return '<span class="stage-status-icon success">✓</span>';
        case 'FAILURE':
        case 'FAILED':
            return '<span class="stage-status-icon failure">✗</span>';
        case 'UNSTABLE':
            return '<span class="stage-status-icon unstable">⚠</span>';
        case 'ABORTED':
            return '<span class="stage-status-icon aborted">⊘</span>';
        case 'IN_PROGRESS':
        case 'RUNNING':
            return '<span class="stage-status-icon building">🔄</span>';
        case 'NOT_EXECUTED':
        case 'SKIPPED':
            return '<span class="stage-status-icon skipped">⊘</span>';
        default:
            return '<span class="stage-status-icon unknown">?</span>';
    }
}

// 获取阶段状态颜色
function getStageStatusColor(status) {
    switch (status) {
        case 'SUCCESS':
        case 'SUCCESSFUL':
            return '#d1fae5'; // 浅绿色
        case 'FAILURE':
        case 'FAILED':
            return '#fee2e2'; // 浅红色
        case 'UNSTABLE':
            return '#fef3c7'; // 浅黄色
        case 'ABORTED':
            return '#e5e7eb'; // 浅灰色
        case 'IN_PROGRESS':
        case 'RUNNING':
            return '#dbeafe'; // 浅蓝色
        case 'NOT_EXECUTED':
        case 'SKIPPED':
            return '#f3f4f6'; // 浅灰色
        default:
            return '#f9fafb'; // 默认浅灰色
    }
}

// 获取阶段状态类名
function getStageStatusClass(status) {
    switch (status) {
        case 'SUCCESS':
        case 'SUCCESSFUL':
            return 'stage-success';
        case 'FAILURE':
        case 'FAILED':
            return 'stage-failure';
        case 'UNSTABLE':
            return 'stage-unstable';
        case 'ABORTED':
            return 'stage-aborted';
        case 'IN_PROGRESS':
        case 'RUNNING':
            return 'stage-building';
        case 'NOT_EXECUTED':
        case 'SKIPPED':
            return 'stage-skipped';
        default:
            return 'stage-unknown';
    }
}

// HTML转义函数（防止XSS）
function escapeHtml(text) {
    if (text == null) return '';
    const div = document.createElement('div');
    div.textContent = text;
    return div.innerHTML;
}

// 显示构建参数编辑窗口
async function showBuildParametersModal(jobName) {
    try {
        // 获取作业参数定义
        console.log('正在获取作业参数定义:', jobName);
        const response = await fetch(`/api/jenkins/job/${encodeURIComponent(jobName)}/parameters`);
        if (!response.ok) {
            const errorText = await response.text();
            console.error('获取作业参数定义失败:', response.status, errorText);
            throw new Error('获取作业参数定义失败: ' + (errorText || response.statusText));
        }
        
        const parameters = await response.json();
        console.log('获取到的参数定义:', parameters);
        
        // 构建参数表单HTML（类似Jenkins原生界面）
        let formHtml = '';
        if (parameters && parameters.length > 0) {
            // 添加提示信息（类似Jenkins）
            formHtml = `
                <div style="margin-bottom: 20px; padding: 12px; background: #f0f0f0; border-left: 4px solid #4a90e2; border-radius: 4px;">
                    <p style="margin: 0; color: #333; font-size: 14px; font-weight: 500;">需要如下参数用于构建项目:</p>
                </div>
            `;
            
            formHtml += parameters.map((param, index) => {
                const paramName = param.name || '';
                const paramType = param.type || '';
                const paramDesc = param.description || '';
                const defaultValue = param.defaultValue || '';
                const choices = param.choices || [];
                
                let inputHtml = '';
                const isGitParameter = paramType.includes('GitParameterDefinition');
                const isChoiceParameter = paramType.includes('ChoiceParameterDefinition');
                
                // 根据参数类型生成不同的输入控件
                if (isGitParameter || (isChoiceParameter && choices.length > 0) || choices.length > 0) {
                    // GitParameter 或 ChoiceParameter（下拉框，支持搜索）
                    // 对于 GitParameter，先显示默认值，然后动态加载选项
                    const optionsHtml = choices.length > 0 ? choices.map(choice => {
                        const selected = choice === defaultValue ? 'selected' : '';
                        return `<option value="${escapeHtml(choice)}" ${selected}>${escapeHtml(choice)}</option>`;
                    }).join('') : (defaultValue ? `<option value="${escapeHtml(defaultValue)}" selected>${escapeHtml(defaultValue)}</option>` : '');
                    
                    // 创建可搜索的下拉框（类似 Jenkins 样式）
                    inputHtml = `
                        <div style="position: relative; width: 100%; max-width: 500px;">
                            <select id="param_${index}" name="${escapeHtml(paramName)}" 
                                    class="jenkins-param-select"
                                    data-param-type="${isGitParameter ? 'git' : 'choice'}"
                                    data-job-name="${escapeHtml(jobName)}"
                                    data-param-name="${escapeHtml(paramName)}"
                                    style="width: 100%; padding: 6px 30px 6px 10px; border: 1px solid #ccc; 
                                           border-radius: 3px; font-size: 13px; background: #fff; 
                                           font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
                                           appearance: none; background-image: url('data:image/svg+xml;charset=UTF-8,<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"12\" height=\"12\" viewBox=\"0 0 12 12\"><path fill=\"%23333\" d=\"M6 9L1 4h10z\"/></svg>');
                                           background-repeat: no-repeat; background-position: right 10px center;
                                           cursor: pointer;">
                                ${optionsHtml}
                                ${isGitParameter && choices.length === 0 ? '<option value="" disabled>加载中...</option>' : ''}
                            </select>
                        </div>
                    `;
                } else if (paramType.includes('BooleanParameterDefinition')) {
                    // 布尔参数（复选框）
                    const checked = defaultValue === true || defaultValue === 'true' ? 'checked' : '';
                    inputHtml = `
                        <label style="display: flex; align-items: center; gap: 8px; cursor: pointer;">
                            <input type="checkbox" id="param_${index}" name="${escapeHtml(paramName)}" ${checked}
                                   style="width: 16px; height: 16px; cursor: pointer; margin: 0;">
                            <span style="color: #333; font-size: 13px;">${checked ? '是' : '否'}</span>
                        </label>
                    `;
                } else {
                    // 文本参数（输入框）
                    inputHtml = `
                        <input type="text" id="param_${index}" name="${escapeHtml(paramName)}" 
                               value="${escapeHtml(String(defaultValue))}"
                               placeholder=""
                               style="width: 100%; max-width: 500px; padding: 6px 10px; border: 1px solid #ccc; 
                                      border-radius: 3px; font-size: 13px; background: #fff;
                                      font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;">
                    `;
                }
                
                // 参数名称和描述（类似Jenkins样式）
                const nameHtml = `
                    <div style="margin-bottom: 4px;">
                        <span style="font-weight: 600; color: #333; font-size: 13px;">${escapeHtml(paramName)}</span>
                    </div>
                `;
                
                const descHtml = paramDesc ? `
                    <div style="margin-bottom: 8px; color: #666; font-size: 12px; line-height: 1.4;">
                        ${escapeHtml(paramDesc)}
                    </div>
                ` : '';
                
                return `
                    <div style="margin-bottom: 24px; padding-bottom: 16px; border-bottom: 1px solid #e0e0e0;">
                        ${nameHtml}
                        ${descHtml}
                        <div>
                            ${inputHtml}
                        </div>
                    </div>
                `;
            }).join('');
        } else {
            // 没有参数
            formHtml = `
                <div style="padding: 40px; text-align: center; color: #666;">
                    <p style="margin: 0; font-size: 14px;">此作业没有配置构建参数</p>
                    <p style="margin: 8px 0 0 0; font-size: 12px; color: #999;">点击确认将使用默认参数触发构建</p>
                </div>
            `;
        }
        
        const modalContent = `
            <div style="max-height: 70vh; overflow-y: auto; background: #fff;">
                <form id="buildParametersForm" style="padding: 24px;">
                    ${formHtml}
                </form>
            </div>
        `;
        
        // 显示模态框
        const onConfirm = () => {
            const form = document.getElementById('buildParametersForm');
            if (!form) return;
            
            const buildParams = {};
            
            // 收集所有参数值（使用闭包中的parameters变量）
            if (parameters && parameters.length > 0) {
                parameters.forEach((param, index) => {
                    const paramName = param.name;
                    const paramType = param.type || '';
                    const input = document.getElementById(`param_${index}`);
                    
                    if (input) {
                        if (paramType.includes('BooleanParameterDefinition')) {
                            // 布尔参数
                            buildParams[paramName] = input.checked ? 'true' : 'false';
                        } else {
                            // 文本或选择参数
                            const value = input.value || '';
                            buildParams[paramName] = value;
                        }
                        console.log(`参数 ${paramName}: ${buildParams[paramName]}`);
                    } else {
                        console.warn(`未找到参数输入框: param_${index} (${paramName})`);
                    }
                });
            } else {
                console.log('没有参数，使用空参数对象');
            }
            
            console.log('收集到的构建参数:', buildParams);
            
            // 提交构建
            submitBuildWithParameters(jobName, buildParams);
        };
        
        window.showModal(`构建参数 - ${jobName}`, modalContent, onConfirm, 'medium');
        
        // 延迟加载 GitParameter 的选项（确保 DOM 已渲染）
        setTimeout(() => {
            loadGitParameterChoices(jobName, parameters);
        }, 100);
        
    } catch (error) {
        console.error('Get job parameters error:', error);
        alert('获取作业参数定义失败: ' + error.message);
    }
}

// 加载 GitParameter 的选项
async function loadGitParameterChoices(jobName, parameters) {
    if (!parameters || parameters.length === 0) return;
    
    for (let i = 0; i < parameters.length; i++) {
        const param = parameters[i];
        const paramType = param.type || '';
        const paramName = param.name || '';
        
        // 如果是 GitParameter 且没有 choices，动态加载
        if (paramType.includes('GitParameterDefinition')) {
            const selectElement = document.getElementById(`param_${i}`);
            if (!selectElement) continue;
            
            try {
                // 显示加载状态
                selectElement.innerHTML = '<option value="" disabled>加载分支列表...</option>';
                
                // 获取分支列表
                const response = await fetch(`/api/jenkins/job/${encodeURIComponent(jobName)}/parameter/${encodeURIComponent(paramName)}/choices`);
                if (response.ok) {
                    const choices = await response.json();
                    const defaultValue = param.defaultValue || '';
                    
                    if (choices && choices.length > 0) {
                        // 构建选项 HTML
                        let optionsHtml = '';
                        if (defaultValue && choices.includes(defaultValue)) {
                            optionsHtml = `<option value="${escapeHtml(defaultValue)}" selected>${escapeHtml(defaultValue)}</option>`;
                            choices.forEach(choice => {
                                if (choice !== defaultValue) {
                                    optionsHtml += `<option value="${escapeHtml(choice)}">${escapeHtml(choice)}</option>`;
                                }
                            });
                        } else {
                            optionsHtml = choices.map(choice => {
                                const selected = choice === defaultValue ? 'selected' : '';
                                return `<option value="${escapeHtml(choice)}" ${selected}>${escapeHtml(choice)}</option>`;
                            }).join('');
                        }
                        
                        selectElement.innerHTML = optionsHtml;
                        console.log(`成功加载 ${choices.length} 个分支选项: ${paramName}`);
                    } else {
                        // 如果没有选项，保留默认值
                        if (defaultValue) {
                            selectElement.innerHTML = `<option value="${escapeHtml(defaultValue)}" selected>${escapeHtml(defaultValue)}</option>`;
                        } else {
                            selectElement.innerHTML = '<option value="">无可用分支</option>';
                        }
                    }
                } else {
                    console.warn(`获取分支列表失败: ${paramName}`);
                    // 保留默认值
                    const defaultValue = param.defaultValue || '';
                    if (defaultValue) {
                        selectElement.innerHTML = `<option value="${escapeHtml(defaultValue)}" selected>${escapeHtml(defaultValue)}</option>`;
                    }
                }
            } catch (error) {
                console.error(`加载分支列表失败: ${paramName}`, error);
                // 保留默认值
                const defaultValue = param.defaultValue || '';
                if (defaultValue) {
                    selectElement.innerHTML = `<option value="${escapeHtml(defaultValue)}" selected>${escapeHtml(defaultValue)}</option>`;
                }
            }
        }
    }
}

// 存储正在构建的作业信息
const buildingJobs = new Map(); // jobName -> { buildNumber, startTime, intervalId }

// 提交带参数的构建请求
async function submitBuildWithParameters(jobName, parameters) {
    try {
        console.log('提交构建请求:', jobName, parameters);
        
        const response = await fetch(`/api/jenkins/build/${encodeURIComponent(jobName)}`, {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(parameters)
        });
        
        console.log('构建请求响应状态:', response.status, response.statusText);
        
        if (!response.ok) {
            const errorText = await response.text();
            console.error('构建请求失败:', response.status, errorText);
            throw new Error(errorText || '触发构建失败');
        }
        
        const result = await response.json();
        console.log('构建请求响应结果:', result);
        
        if (result.success) {
            // 关闭模态框
            if (typeof window.closeModal === 'function') {
                window.closeModal();
            }
            
            // 显示构建进度条并开始轮询
            const buildNumber = result.buildNumber;
            showBuildProgress(jobName, buildNumber);
            startBuildProgressPolling(jobName, buildNumber);
            
            // 保存到 localStorage（showBuildProgress 中已保存，这里确保保存）
            const buildInfo = {
                jobName: jobName,
                buildNumber: buildNumber,
                timestamp: Date.now()
            };
            localStorage.setItem(`build-progress-${jobName}`, JSON.stringify(buildInfo));
            
            // 不显示alert，直接显示进度条
            console.log('✓ 构建已触发，构建号: #' + buildNumber);
        } else {
            const errorMsg = result.message || '触发构建失败';
            console.error('构建失败:', errorMsg);
            throw new Error(errorMsg);
        }
    } catch (error) {
        console.error('Build Jenkins job error:', error);
        alert('触发构建失败: ' + error.message);
    }
}

// 显示构建进度条
function showBuildProgress(jobName, buildNumber) {
    const progressContainer = document.getElementById(`build-progress-${jobName}`);
    const stagesContainer = document.getElementById(`build-stages-${jobName}`);
    
    if (progressContainer && stagesContainer) {
        progressContainer.style.display = 'block';
        stagesContainer.innerHTML = ''; // 清空之前的阶段
        
        // 保存到 localStorage，用于页面刷新后恢复
        const buildInfo = {
            jobName: jobName,
            buildNumber: buildNumber,
            timestamp: Date.now()
        };
        localStorage.setItem(`build-progress-${jobName}`, JSON.stringify(buildInfo));
    }
}

// 恢复最新构建进度显示（页面刷新后调用，默认显示最新构建记录）
async function restoreLatestBuildProgress(jobs) {
    if (!jobs || jobs.length === 0) return;
    
    // 检查每个作业的最新构建状态（使用 Promise.allSettled 避免单个作业失败影响其他作业）
    const restorePromises = jobs.map(async (job) => {
        try {
            // 检查页面元素是否存在
            const progressContainer = document.getElementById(`build-progress-${job.name}`);
            if (!progressContainer) {
                console.debug('构建进度容器不存在:', job.name);
                return;
            }
            
            let buildNumberToShow = null;
            let shouldPoll = false;
            
            // 优先检查 localStorage 中是否有正在构建的记录
            const savedBuildInfo = localStorage.getItem(`build-progress-${job.name}`);
            if (savedBuildInfo) {
                try {
                    const buildInfo = JSON.parse(savedBuildInfo);
                    const savedTime = buildInfo.timestamp || 0;
                    const now = Date.now();
                    
                    // 只恢复最近30分钟内的构建记录
                    if (now - savedTime < 30 * 60 * 1000) {
                        // 检查构建是否仍然存在且有效（添加超时和错误处理）
                        try {
                            const controller = new AbortController();
                            const timeoutId = setTimeout(() => controller.abort(), 5000); // 5秒超时
                            
                            const response = await fetch(`/api/jenkins/build/${encodeURIComponent(job.name)}/${buildInfo.buildNumber}/status`, {
                                signal: controller.signal
                            });
                            clearTimeout(timeoutId);
                            
                            if (response.ok) {
                                const status = await response.json();
                                if (status.exists) {
                                    buildNumberToShow = buildInfo.buildNumber;
                                    shouldPoll = status.building;
                                }
                            }
                        } catch (error) {
                            if (error.name === 'AbortError') {
                                console.debug('获取构建状态超时:', job.name);
                            } else {
                                console.debug('获取构建状态失败:', job.name, error);
                            }
                        }
                    }
                    
                    // 如果构建不存在或已过期，清除 localStorage
                    if (!buildNumberToShow) {
                        localStorage.removeItem(`build-progress-${job.name}`);
                    }
                } catch (error) {
                    console.debug('恢复保存的构建信息失败:', job.name, error);
                    localStorage.removeItem(`build-progress-${job.name}`);
                }
            }
            
            // 如果没有保存的构建信息，或者保存的构建已完成，获取最新构建记录
            if (!buildNumberToShow) {
                try {
                    // 获取最新构建记录（添加超时处理）
                    const controller1 = new AbortController();
                    const timeoutId1 = setTimeout(() => controller1.abort(), 5000); // 5秒超时
                    
                    const pipelineResponse = await fetch(`/api/jenkins/pipeline/${encodeURIComponent(job.name)}?page=1&pageSize=1`, {
                        signal: controller1.signal
                    });
                    clearTimeout(timeoutId1);
                    
                    if (pipelineResponse.ok) {
                        const pipelineData = await pipelineResponse.json();
                        if (pipelineData.buildHistory && pipelineData.buildHistory.length > 0) {
                            const latestBuild = pipelineData.buildHistory[0];
                            const latestBuildNumber = latestBuild.buildNumber;
                            
                            // 检查构建状态（添加超时处理）
                            try {
                                const controller2 = new AbortController();
                                const timeoutId2 = setTimeout(() => controller2.abort(), 5000); // 5秒超时
                                
                                const statusResponse = await fetch(`/api/jenkins/build/${encodeURIComponent(job.name)}/${latestBuildNumber}/status`, {
                                    signal: controller2.signal
                                });
                                clearTimeout(timeoutId2);
                                
                                if (statusResponse.ok) {
                                    const status = await statusResponse.json();
                                    if (status.exists) {
                                        buildNumberToShow = latestBuildNumber;
                                        shouldPoll = status.building;
                                        
                                        // 保存最新构建信息到 localStorage
                                        const buildInfo = {
                                            jobName: job.name,
                                            buildNumber: latestBuildNumber,
                                            timestamp: Date.now()
                                        };
                                        localStorage.setItem(`build-progress-${job.name}`, JSON.stringify(buildInfo));
                                    }
                                }
                            } catch (error) {
                                if (error.name === 'AbortError') {
                                    console.debug('获取构建状态超时:', job.name);
                                } else {
                                    console.debug('获取构建状态失败:', job.name, error);
                                }
                            }
                        }
                    }
                } catch (error) {
                    if (error.name === 'AbortError') {
                        console.debug('获取Pipeline信息超时:', job.name);
                    } else {
                        console.debug('获取作业最新构建失败:', job.name, error);
                    }
                }
            }
            
            // 显示构建进度
            if (buildNumberToShow) {
                // 再次检查元素是否存在（可能在异步操作期间页面被重新渲染）
                const progressContainerCheck = document.getElementById(`build-progress-${job.name}`);
                if (progressContainerCheck) {
                    showBuildProgress(job.name, buildNumberToShow);
                    
                    if (shouldPoll) {
                        // 正在构建，开始轮询
                        startBuildProgressPolling(job.name, buildNumberToShow);
                    } else {
                        // 构建已完成，只显示状态（不轮询）
                        checkBuildStatus(job.name, buildNumberToShow, Date.now());
                    }
                }
            }
        } catch (error) {
            console.debug('恢复构建进度失败:', job.name, error);
        }
    });
    
    // 等待所有作业的恢复操作完成（不阻塞，使用 allSettled 避免单个失败影响整体）
    await Promise.allSettled(restorePromises);
}

// 渲染构建阶段（横向卡片式）
function renderBuildStages(jobName, buildNumber, stages, building) {
    const stagesContainer = document.getElementById(`build-stages-${jobName}`);
    if (!stagesContainer) return;
    
    if (!stages || stages.length === 0) {
        // 如果没有阶段信息，显示简单的进度条
        stagesContainer.innerHTML = `
            <div style="display: flex; align-items: center; gap: 8px;">
                <div style="flex: 1; height: 6px; background: #e5e7eb; border-radius: 3px; overflow: hidden;">
                    <div id="build-progress-bar-${jobName}" 
                         style="height: 100%; background: linear-gradient(90deg, #10b981 0%, #059669 100%); 
                                width: ${building ? '50%' : '100%'}; transition: width 0.3s ease; border-radius: 3px;">
                    </div>
                </div>
            </div>
        `;
        return;
    }
    
    // 渲染横向卡片式阶段列表
    stagesContainer.style.display = 'flex';
    stagesContainer.style.flexDirection = 'row';
    stagesContainer.style.gap = '8px';
    stagesContainer.style.overflowX = 'auto';
    stagesContainer.style.paddingBottom = '4px';
    
    let stagesHtml = '';
    stages.forEach((stage, index) => {
        const stageId = stage.id || `stage-${index}`;
        const stageName = stage.name || `阶段 ${index + 1}`;
        const stageStatus = stage.status || 'PENDING';
        const duration = stage.durationText || '';
        
        // 根据状态设置颜色和图标
        let statusColor = '#9ca3af'; // 默认灰色
        let statusIcon = '○';
        let cardBgColor = '#f3f4f6'; // 默认灰色背景
        let cardBorderColor = '#e5e7eb';
        
        if (stageStatus === 'SUCCESS') {
            statusColor = '#059669'; // 深绿色（与图片一致）
            statusIcon = '✓';
            cardBgColor = '#d1fae5'; // 浅绿色背景（与图片一致）
            cardBorderColor = '#a7f3d0';
        } else if (stageStatus === 'FAILURE' || stageStatus === 'ABORTED') {
            statusColor = '#ef4444'; // 红色
            statusIcon = '✗';
            cardBgColor = '#fee2e2'; // 浅红色背景
            cardBorderColor = '#ef4444';
        } else if (stageStatus === 'IN_PROGRESS' || stageStatus === 'RUNNING') {
            statusColor = '#3b82f6'; // 蓝色
            statusIcon = '⟳';
            cardBgColor = '#dbeafe'; // 浅蓝色背景
            cardBorderColor = '#3b82f6';
        } else if (stageStatus === 'PAUSED') {
            statusColor = '#f59e0b'; // 橙色
            statusIcon = '⏸';
            cardBgColor = '#fef3c7'; // 浅黄色背景
            cardBorderColor = '#f59e0b';
        }
        
        stagesHtml += `
            <div class="build-stage-card" 
                 data-job-name="${jobName}" 
                 data-build-number="${buildNumber}"
                 data-stage-id="${stageId}"
                 data-stage-name="${stageName}"
                 onclick="window.showStageLogFromProgress('${jobName}', ${buildNumber}, '${stageId}', '${stageName}')"
                 style="display: flex; flex-direction: column; align-items: center; justify-content: center;
                        min-width: 110px; max-width: 150px; padding: 10px 14px; 
                        background: ${cardBgColor}; 
                        border: 1px solid ${cardBorderColor};
                        border-radius: 8px; 
                        cursor: pointer; 
                        transition: all 0.2s;
                        box-shadow: 0 1px 3px rgba(0,0,0,0.08);
                        flex-shrink: 0;"
                 onmouseover="this.style.transform='translateY(-2px)'; this.style.boxShadow='0 4px 8px rgba(0,0,0,0.12)'"
                 onmouseout="this.style.transform='translateY(0)'; this.style.boxShadow='0 1px 3px rgba(0,0,0,0.08)'">
                <div style="display: flex; align-items: center; gap: 8px; margin-bottom: 6px; width: 100%; justify-content: center;">
                    <span style="color: ${statusColor}; font-size: 18px; font-weight: 700; line-height: 1; flex-shrink: 0;">${statusIcon}</span>
                    <span style="font-size: 13px; font-weight: 500; color: #1f2937; white-space: nowrap; text-align: center; flex: 1;">${stageName}</span>
                </div>
                ${duration ? `<span style="font-size: 12px; color: #4b5563; font-weight: 400; margin-top: 2px;">${duration}</span>` : ''}
            </div>
        `;
    });
    
    stagesContainer.innerHTML = stagesHtml;
}

// 从进度条显示阶段日志
async function showStageLogFromProgress(jobName, buildNumber, stageId, stageName) {
    if (typeof window.showStageLog === 'function') {
        await window.showStageLog(jobName, buildNumber, stageId, stageName);
    } else {
        console.error('showStageLog 函数未定义');
    }
}
window.showStageLogFromProgress = showStageLogFromProgress;

// 开始轮询构建状态
function startBuildProgressPolling(jobName, buildNumber) {
    // 清除之前的轮询（如果存在）
    if (buildingJobs.has(jobName)) {
        const oldInfo = buildingJobs.get(jobName);
        if (oldInfo.intervalId) {
            clearInterval(oldInfo.intervalId);
        }
    }
    
    const startTime = Date.now();
    buildingJobs.set(jobName, { buildNumber, startTime, intervalId: null });
    
    // 立即检查一次
    checkBuildStatus(jobName, buildNumber, startTime);
    
    // 每2秒轮询一次
    const intervalId = setInterval(() => {
        checkBuildStatus(jobName, buildNumber, startTime);
    }, 2000);
    
    buildingJobs.get(jobName).intervalId = intervalId;
}

// 检查构建状态并更新进度条
async function checkBuildStatus(jobName, buildNumber, startTime) {
    try {
        const response = await fetch(`/api/jenkins/build/${encodeURIComponent(jobName)}/${buildNumber}/status`);
        
        if (!response.ok) {
            console.error('获取构建状态失败:', response.status);
            return;
        }
        
        const status = await response.json();
        const progressContainer = document.getElementById(`build-progress-${jobName}`);
        
        if (!progressContainer) {
            return;
        }
        
        if (!status.exists) {
            // 构建不存在，隐藏进度条
            progressContainer.style.display = 'none';
            stopBuildProgressPolling(jobName);
            return;
        }
        
        const elapsed = Date.now() - startTime;
        const elapsedSeconds = Math.floor(elapsed / 1000);
        
        // 渲染阶段信息
        if (status.stages && status.stages.length > 0) {
            renderBuildStages(jobName, buildNumber, status.stages, status.building);
        } else {
            // 如果没有阶段信息，显示简单的进度条
            const stagesContainer = document.getElementById(`build-stages-${jobName}`);
            if (stagesContainer) {
                const progressBar = document.getElementById(`build-progress-bar-${jobName}`);
                if (progressBar) {
                    if (status.building) {
                        const estimatedDuration = 60000; // 60秒
                        let progress = Math.min(95, (elapsed / estimatedDuration) * 100);
                        progressBar.style.width = progress + '%';
                    } else {
                        progressBar.style.width = '100%';
                    }
                }
            }
        }
        
        // 不再显示构建状态文本，只显示阶段卡片
        if (!status.building) {
            // 构建完成，停止轮询
            
            // 停止轮询，但保留显示（不隐藏进度条）
            stopBuildProgressPolling(jobName);
            
            // 更新 localStorage，保留构建信息
            const buildInfo = {
                jobName: jobName,
                buildNumber: buildNumber,
                timestamp: Date.now()
            };
            localStorage.setItem(`build-progress-${jobName}`, JSON.stringify(buildInfo));
        }
    } catch (error) {
        console.error('检查构建状态失败:', error);
    }
}

// 停止构建进度轮询
function stopBuildProgressPolling(jobName) {
    if (buildingJobs.has(jobName)) {
        const info = buildingJobs.get(jobName);
        if (info.intervalId) {
            clearInterval(info.intervalId);
        }
        buildingJobs.delete(jobName);
    }
}

// 触发Jenkins作业构建（显示参数编辑窗口）
async function buildJenkinsJob(jobName) {
    await showBuildParametersModal(jobName);
}
window.buildJenkinsJob = buildJenkinsJob;

// 注册所有函数到全局作用域
window.viewPipeline = viewPipeline;
window.loadPipelineView = loadPipelineView;
window.renderPipelineView = renderPipelineView;

