// ============================================
// 统计信息模块
// ============================================

// 加载统计信息
function loadStatistics() {
    const today = new Date();
    today.setHours(0, 0, 0, 0);
    
    const todayTasks = mockTasks.filter(task => {
        if (!task.createTime) return false;
        const taskDate = new Date(task.createTime);
        taskDate.setHours(0, 0, 0, 0);
        return taskDate.getTime() === today.getTime();
    });
    
    const successCount = mockTasks.filter(t => (t.taskStatus || t.status) === 'success').length;
    const failedCount = mockTasks.filter(t => (t.taskStatus || t.status) === 'failed').length;
    const buildingCount = mockTasks.filter(t => (t.taskStatus || t.status) === 'building' || (t.taskStatus || t.status) === 'deploying').length;
    
    const todayBuildCountEl = document.getElementById('todayBuildCount');
    const successCountEl = document.getElementById('successCount');
    const failedCountEl = document.getElementById('failedCount');
    const buildingCountEl = document.getElementById('buildingCount');
    
    if (todayBuildCountEl) todayBuildCountEl.textContent = todayTasks.length;
    if (successCountEl) successCountEl.textContent = successCount;
    if (failedCountEl) failedCountEl.textContent = failedCount;
    if (buildingCountEl) buildingCountEl.textContent = buildingCount;
}


