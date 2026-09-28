import 'htmx.org';

window.clearAfter = function(selector, timeout) {
    setTimeout(() => {
        const target = document.querySelector(selector);
        if (!target) return;

        if ('value' in target && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) {
            target.value = '';
        } else {
            target.innerHTML = '';
        }
    }, timeout);
}