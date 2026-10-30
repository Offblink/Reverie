/* Reverie web 共享胶水 —— 四视图共用。页面专属逻辑别放这里。
 *
 * 与 Java 侧的桥契约（改一边必须同步另一边，SPEC §3.2）：
 *   JS → 原生：window.Reverie.<method>(...)，即 ReverieBridge 宿主对象。
 *     **只对方法加 @JavascriptInterface，JS 才看得见**。
 *     方法缺失时 bridge() 回 null —— 页面按空态降级，绝不抛顶层。
 *   原生 → JS：
 *     Host.onEvent(ev)  引擎事件（M2 起：segment-delta/segment-done/
 *                       choice-made/error）
 *     Host.onState(st)  全量快照 {settings(不含key), story, busy}——结构性
 *                       变化后原生推，页面也可 bridge("getState") 主动拉。
 *     Host.back()       返回键：true = 页面自己消费（zoom 缩回），false = 原生继续。
 */
(function () {
  "use strict";

  /**
   * 同步调原生：返回值是 JSON 就解析成对象，否则原样返回。
   * 桥缺失/方法缺失/异常一律回 null。
   */
  window.bridge = function (method, args) {
    try {
      if (typeof window.Reverie === 'undefined') return null;
      var fn = window.Reverie[method];
      if (typeof fn !== 'function') return null;
      var raw = args === undefined ? fn.call(window.Reverie)
                                   : fn.call(window.Reverie, JSON.stringify(args));
      if (typeof raw === 'string') {
        try { return JSON.parse(raw); } catch (e) { return raw; }
      }
      return raw === undefined ? null : raw;
    } catch (e) {
      console.log('reverie:bridge-error ' + method + ' ' + e);
      return null;
    }
  };

  /** 事件落点：页面按需覆写钩子，没覆写的默认空实现 */
  window.Host = {
    onEvent: function (ev) {},
    onState: function (st) {},
    back: function () { return false; }
  };

  // Reduce Transparency → 不透明玻璃回退（glass.css 的 body.reduce-transparency）
  // Android 13+/桌面 Chrome 支持该媒体特性时生效；不支持则维持常规玻璃。
  try {
    if (window.matchMedia && window.matchMedia('(prefers-reduced-transparency: reduce)').matches) {
      document.body.classList.add('reduce-transparency');
    }
  } catch (e) { }

  // 桥健康自报（排错入口：adb logcat | grep reverie:boot）
  console.log(
    'reverie:boot Reverie=' + typeof window.Reverie +
    ' methods=' + (window.Reverie ? Object.keys(window.Reverie).join(',') : '-')
  );

  /** 页内 toast（深色 pill） */
  var toastEl = null, toastTimer = 0;
  window.toast = function (msg, ms) {
    if (!toastEl) {
      toastEl = document.createElement('div');
      toastEl.className = 'toast';
      document.body.appendChild(toastEl);
    }
    toastEl.textContent = msg;
    toastEl.classList.add('on');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(function () { toastEl.classList.remove('on'); }, ms || 2200);
  };

  window.esc = function (s) {
    return String(s == null ? '' : s)
      .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;');
  };
})();
