// ============================================
// 公共 UI：页面内提示、确认框、输入框
// 取代浏览器原生的 alert / confirm / prompt。全站统一用这里的三个函数，模块里不要再直接调原生弹窗。
//   notify(message, type)                      提示条，不阻塞
//   await confirmDialog(message, options)      确认框，返回 true / false
//   await promptDialog(message, value, options) 输入框，返回字符串；取消返回 null
// ============================================

(function () {
    const TOAST_LIMIT = 4;
    const DURATION = { success: 2500, info: 3500, warning: 4500, error: 6000 };
    const FLASH_KEY = 'opsflow.flashNotify';

    function tr(key, fallback) {
        if (typeof window.t === 'function') {
            const text = window.t(key);
            if (text && text !== key) return text;
        }
        return fallback;
    }

    // 没有显式指定类型时按文案判断，方便从 alert 平移过来的调用
    function inferType(text) {
        if (/失败|错误|异常|出错|无权|拒绝|error|fail/i.test(text)) return 'error';
        if (/成功|已保存|已删除|已创建|已更新|已复制|完成|success/i.test(text)) return 'success';
        return 'warning';
    }

    function toastHost() {
        let host = document.getElementById('uiToastHost');
        if (!host) {
            host = document.createElement('div');
            host.id = 'uiToastHost';
            host.className = 'ui-toast-host';
            host.setAttribute('aria-live', 'polite');
            document.body.appendChild(host);
        }
        return host;
    }

    /**
     * 页面右上角的提示条。
     * @param {*} message 文本（按纯文本显示，支持换行）
     * @param {'success'|'error'|'warning'|'info'} [type] 不传时按文案判断
     */
    function notify(message, type) {
        const text = message == null ? '' : String(message);
        if (!text.trim()) return;
        const kind = DURATION[type] ? type : inferType(text);
        const host = toastHost();

        const toast = document.createElement('div');
        toast.className = 'ui-toast is-' + kind;
        toast.setAttribute('role', kind === 'error' ? 'alert' : 'status');
        const body = document.createElement('div');
        body.className = 'ui-toast-text';
        body.textContent = text;
        const close = document.createElement('button');
        close.type = 'button';
        close.className = 'ui-toast-close';
        close.setAttribute('aria-label', tr('common.close', '关闭'));
        close.textContent = '×';
        toast.appendChild(body);
        toast.appendChild(close);

        let timer = null;
        const dismiss = () => {
            if (timer) clearTimeout(timer);
            if (!toast.parentNode) return;
            toast.classList.add('is-leaving');
            setTimeout(() => toast.remove(), 180);
        };
        const arm = () => {
            // 文字越长停留越久，至多延长到 3 倍
            const extra = Math.min(DURATION[kind] * 2, Math.max(0, text.length - 20) * 60);
            timer = setTimeout(dismiss, DURATION[kind] + extra);
        };
        close.addEventListener('click', dismiss);
        // 鼠标停在上面时不消失，方便看完长的错误信息
        toast.addEventListener('mouseenter', () => { if (timer) clearTimeout(timer); });
        toast.addEventListener('mouseleave', arm);

        host.appendChild(toast);
        while (host.children.length > TOAST_LIMIT) {
            host.firstElementChild.remove();
        }
        arm();
    }

    /**
     * 提示后马上要刷新或跳转页面时用：提示条会在下一个页面加载后显示。
     */
    function notifyAfterReload(message, type) {
        try {
            sessionStorage.setItem(FLASH_KEY, JSON.stringify({ message: String(message == null ? '' : message), type: type || null }));
        } catch (e) {
            // 存不了就退化为立即提示
            notify(message, type);
        }
    }

    function showFlash() {
        let raw = null;
        try {
            raw = sessionStorage.getItem(FLASH_KEY);
            if (raw) sessionStorage.removeItem(FLASH_KEY);
        } catch (e) {
            return;
        }
        if (!raw) return;
        try {
            const data = JSON.parse(raw);
            notify(data.message, data.type || undefined);
        } catch (e) {
            // 忽略损坏的数据
        }
    }

    // 对话框是独立浮层，层级高于普通弹窗（.modal-overlay），可以叠在表单弹窗之上
    function openDialog(build) {
        return new Promise(resolve => {
            const previousFocus = document.activeElement;
            const overlay = document.createElement('div');
            overlay.className = 'ui-dialog-overlay';
            const dialog = document.createElement('div');
            dialog.className = 'ui-dialog';
            dialog.setAttribute('role', 'dialog');
            dialog.setAttribute('aria-modal', 'true');
            overlay.appendChild(dialog);

            let settled = false;
            const finish = value => {
                if (settled) return;
                settled = true;
                document.removeEventListener('keydown', onKeydown, true);
                overlay.remove();
                if (previousFocus && typeof previousFocus.focus === 'function' && document.contains(previousFocus)) {
                    previousFocus.focus();
                }
                resolve(value);
            };

            const api = build(dialog, finish);

            function onKeydown(event) {
                if (event.key === 'Escape') {
                    event.preventDefault();
                    event.stopPropagation();
                    finish(api.cancelValue);
                } else if (event.key === 'Enter' && !event.isComposing) {
                    // 多行输入框里回车是换行；按钮上的回车交给按钮自己
                    const tag = event.target && event.target.tagName;
                    if (tag === 'TEXTAREA' || tag === 'BUTTON') return;
                    event.preventDefault();
                    event.stopPropagation();
                    api.submit();
                }
            }
            document.addEventListener('keydown', onKeydown, true);
            // 点遮罩等同取消，避免误触的危险操作被当成确认
            overlay.addEventListener('mousedown', event => {
                if (event.target === overlay) finish(api.cancelValue);
            });

            document.body.appendChild(overlay);
            if (api.focus) api.focus.focus();
        });
    }

    function appendTitle(dialog, title) {
        const header = document.createElement('div');
        header.className = 'ui-dialog-title';
        header.textContent = title;
        dialog.appendChild(header);
    }

    function appendMessage(dialog, message) {
        const body = document.createElement('div');
        body.className = 'ui-dialog-message';
        body.textContent = message == null ? '' : String(message);
        dialog.appendChild(body);
        return body;
    }

    function appendButtons(dialog, options, onCancel, onConfirm) {
        const footer = document.createElement('div');
        footer.className = 'ui-dialog-footer';
        const cancel = document.createElement('button');
        cancel.type = 'button';
        cancel.className = 'btn-secondary';
        cancel.textContent = options.cancelText || tr('common.cancel', '取消');
        cancel.addEventListener('click', onCancel);
        const confirm = document.createElement('button');
        confirm.type = 'button';
        confirm.className = options.danger ? 'btn-primary ui-dialog-danger' : 'btn-primary';
        confirm.textContent = options.confirmText || tr('common.confirm', '确认');
        confirm.addEventListener('click', onConfirm);
        footer.appendChild(cancel);
        footer.appendChild(confirm);
        dialog.appendChild(footer);
        return { cancel, confirm };
    }

    /**
     * 确认框。
     * @param {string} message
     * @param {{title?: string, confirmText?: string, cancelText?: string, danger?: boolean}} [options]
     *        danger 不传时按文案判断：含「删除 / 清除 / 重置 / 回滚」等字样视为危险操作
     * @returns {Promise<boolean>}
     */
    function confirmDialog(message, options) {
        const opts = Object.assign({}, options);
        if (opts.danger == null) {
            opts.danger = /删除|清除|清空|重置|回滚|移除|解除|delete|remove|reset/i.test(String(message || ''));
        }
        return openDialog((dialog, finish) => {
            appendTitle(dialog, opts.title || tr('common.confirmTitle', '请确认'));
            appendMessage(dialog, message);
            const buttons = appendButtons(dialog, opts, () => finish(false), () => finish(true));
            // 危险操作默认聚焦在「取消」上，回车不会直接执行
            return {
                cancelValue: false,
                submit: () => finish(true),
                focus: opts.danger ? buttons.cancel : buttons.confirm
            };
        });
    }

    /**
     * 输入框。
     * @param {string} message 说明文字
     * @param {string} [defaultValue]
     * @param {{title?: string, confirmText?: string, placeholder?: string, required?: boolean, multiline?: boolean}} [options]
     * @returns {Promise<string|null>} 取消时为 null
     */
    function promptDialog(message, defaultValue, options) {
        const opts = Object.assign({}, options);
        return openDialog((dialog, finish) => {
            appendTitle(dialog, opts.title || tr('common.inputTitle', '请输入'));
            appendMessage(dialog, message);
            const input = document.createElement(opts.multiline ? 'textarea' : 'input');
            input.className = 'ui-dialog-input';
            if (!opts.multiline) input.type = 'text';
            if (opts.multiline) input.rows = 3;
            input.value = defaultValue == null ? '' : String(defaultValue);
            if (opts.placeholder) input.placeholder = opts.placeholder;
            dialog.appendChild(input);
            const error = document.createElement('div');
            error.className = 'ui-dialog-error';
            dialog.appendChild(error);

            const submit = () => {
                const value = input.value;
                if (opts.required && !value.trim()) {
                    error.textContent = tr('common.required', '此项不能为空');
                    input.focus();
                    return;
                }
                finish(value);
            };
            appendButtons(dialog, opts, () => finish(null), submit);
            setTimeout(() => { if (typeof input.select === 'function') input.select(); }, 0);
            return { cancelValue: null, submit, focus: input };
        });
    }

    window.notify = notify;
    window.notifyAfterReload = notifyAfterReload;
    window.confirmDialog = confirmDialog;
    window.promptDialog = promptDialog;

    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', showFlash);
    } else {
        showFlash();
    }
})();
