// 环境配置 文案（由 OpsFlowI18n.register 登记；key 前缀 environments.）
OpsFlowI18n.register({
    zh: {
        'environments.type.prod': '生产环境',
        'environments.type.nonprod': '非生产环境',
        'environments.empty': '暂无环境',
        'environments.form.name': '环境名称',
        'environments.form.namePlaceholder': '例如: dev, uat, prod',
        'environments.form.cluster': '关联集群',
        'environments.form.selectCluster': '请选择集群',
        'environments.form.namespace': 'K8s命名空间',
        'environments.form.type': '环境类型',
        'environments.form.createTitle': '添加环境',
        'environments.msg.detailFailed': '获取环境详情失败',
        'environments.msg.notFound': '环境不存在',
        'environments.form.editTitle': '编辑环境',
        'environments.msg.loadFailed': '加载环境失败',
        'environments.msg.deleteConfirm': '确定要删除这个环境吗？',
        // dashboard.html 静态文案
        'environments.table.clusterAddress': '集群地址',
        'environments.table.namespace': '命名空间'
    },
    en: {
        'environments.type.prod': 'Production',
        'environments.type.nonprod': 'Non-production',
        'environments.empty': 'No environments',
        'environments.form.name': 'Environment name',
        'environments.form.namePlaceholder': 'e.g. dev, uat, prod',
        'environments.form.cluster': 'Cluster',
        'environments.form.selectCluster': 'Select a cluster',
        'environments.form.namespace': 'K8s namespace',
        'environments.form.type': 'Environment type',
        'environments.form.createTitle': 'Add environment',
        'environments.msg.detailFailed': 'Failed to load environment details',
        'environments.msg.notFound': 'Environment not found',
        'environments.form.editTitle': 'Edit environment',
        'environments.msg.loadFailed': 'Failed to load environment',
        'environments.msg.deleteConfirm': 'Delete this environment?',
        // dashboard.html 静态文案
        'environments.table.clusterAddress': 'Cluster address',
        'environments.table.namespace': 'Namespace'
    }
});
